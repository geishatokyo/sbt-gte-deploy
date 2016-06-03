package gtedeploy

import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.amazonaws.services.ecr.model.{RepositoryNotFoundException, DescribeRepositoriesRequest}
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.s3.model.AmazonS3Exception

import sbt.Keys._
import sbt._

/**
  * Created by takezoux2 on 2016/06/03.
  */
trait Doctor { self : AutoPlugin with AWSFunctions =>

  def autoImport : Keys

  import ErrorName._

  /**
    * 引数が多すぎて<<=が使用できなくなったので、一部をまとめるためのKey
    */
  private val ebsSet = TaskKey[(String,String,String,Boolean)]("ebs-set")

  val doctorSettings = {
    val ai = autoImport
    import ai._
    Seq(
      ebsSet := { ((appName in EBS).value, envName.value, versionLabel.value,replaceAppVersion.value)},
      doctor <<= (
        appName in ECR,
        ecrRepository,
        ebsZipBucketName,
        ebsSet,
        awsRegion in ECR,
        awsRegion in EBS,
        awsRegion in S3,
        streams,
        gteDeployConf
        ) map runDoctor
    )
  }


  def runDoctor(appNameInECR : String,
                ecrRepository :  Option[String],

                bucketName: String,

                ebsSet: (String,String,String,Boolean),

                ecrRegion: String,
                ebsRegion: String,
                s3Region: String,
                s: TaskStreams, conf: GTEDeployConf) = {

    val (appNameInEBS,envName,appVersion,replaceAppVersion) = ebsSet

    val reports = Seq(
      checkECRRepositoryExists(appNameInECR,ecrRepository,ecrRegion,conf),
      checkS3(bucketName,s3Region,conf)
    ) ++ checkBeanstalk(ebsRegion,appNameInEBS,envName,appVersion,replaceAppVersion,conf)

    printReports(reports,s)

    if(reports.exists(_.isInstanceOf[Error])){
      DoctorResult.Error
    }else if(reports.exists(_.isInstanceOf[Warning])){
      DoctorResult.Warning
    }else{
      DoctorResult.Ok
    }
  }

  private def checkECRRepositoryExists(_appName: String,
                                       fullUri: Option[String],
                                       region: String,
                                       conf: GTEDeployConf) = {
    useEcrClient(conf,region){implicit cl =>

      val appName = fullUri match{
        case Some(uri) => {
          val i = uri.lastIndexOf("/")
          if(i > 0) uri.substring(i + 1)
          else uri
        }
        case None => _appName

      }

      val req = new DescribeRepositoriesRequest().withRepositoryNames(appName)

      def notFoundError = {
        val fullName = fullUri.getOrElse(appName)

        Error(
          ECRRepoNotFound,
          s"ECR repository '${appName}' not found.",
          s"ECR repository '${fullName}' not found in region ${region}",
          s"Create new ecr repository named ${appName}",
          s"""Set exist ecr repository name
             |appName in ECR := "{name}"
           """.stripMargin,
          s"""Set exist full uri
             |ecrRepository := "{repoUri}"
             |ex: 123456789.dkr.ecr.us-east-1.amazonaws.com/sample-app
           """.stripMargin
        )
      }

      try {
        val res = cl.describeRepositories(req)
        if(res.getRepositories.size() == 1){
          val trueUri = res.getRepositories.get(0).getRepositoryUri
          fullUri match{
            case Some(uri) if trueUri != uri => {
              // URIで指定された場合に一致していない
              Error(
                "ECRURINotMatch",
                "Passed URI and true URI is not match",
                s"True uri:${trueUri} Passed uri:${uri}",
                s"""Change build.sbt setting.
                  |ecrRepository := "${trueUri}"
                """.stripMargin
              )
            }
            case _ => {
              Ok
            }
          }
        }else{
          notFoundError
        }
      }catch{
        case r: RepositoryNotFoundException => {
          notFoundError
        }
        case t : Throwable => toError("ECR",t)
      }
    }

  }

  def checkS3(bucketName: String,region: String,conf: GTEDeployConf) = {
    useS3Client(conf,region){ implicit cli =>
      try{
        val loc = existsBucket(bucketName)
        Ok
      }catch{
        case e : AmazonS3Exception if e.getStatusCode == 404 => {
          Error(
            "S3BucketNotFound",
            s"S3 bucket '${bucketName}' not found",
            s"S3 bucket '${bucketName}' not found in region ${region}",
            s"Create bucket '${bucketName}",
            s"""Set exist bucket name
               |ebsZipBucketName in GTEDeploy := "_bucket_name_"
             """.stripMargin

          )
        }
        case e: Throwable => toError("S3",e)
      }
    }

  }

  def checkBeanstalk(region: String,appName: String,envName: String,appVersion: String,replace : Boolean,conf: GTEDeployConf) : Seq[Report] = {
    useEBSClient(conf,region){implicit cli =>

      val r = checkApplicationCondition(region,appName)
      if(r == Ok){
        Seq(
          checkEnvironmentCondition(region,appName,envName),
          checkAppVersionExists(region,appName,appVersion,replace)
        )
      }else{
        Seq(r)
      }
    }
  }

  def checkApplicationCondition(region: String,appName: String)(implicit cli: AWSElasticBeanstalkClient) = {
    try{
      ebs.describeApplication(appName) match{
        case Some(app) => {
          Ok
        }
        case None => {
          Error(
            "ECRAppNotFound",
            s"${appName} not found",
            s"${appName} in ${region} not found",
            "Create application",
            """Set exist appName
              |appName in EBS := "{appName}"
            """.stripMargin
          )
        }
      }
    }catch{
      case e : Throwable => toError("ECR",e)
    }
  }

  def checkEnvironmentCondition(region: String,appName: String,envName: String)(implicit cli: AWSElasticBeanstalkClient) = {
    try{
      ebs.describeEnvironment(appName,envName) match{
        case Some(e) => {
          if (e.getSolutionStackName.contains("Docker")) {
            if(e.getHealthStatus == "Ok") {
              Ok
            }else if(e.getHealthStatus == "Info"){
              Warning(
                "EBSEnvStateWarning",
                s"${appName}:${envName} is in busy",
                s"${appName}:${envName} in ${region} is doing another task.",
                """Wait until become Ok"""
              )
            }else{
              Error(
                "EBSEnvStateError",
                s"${appName}:${envName} is something bad",
                s"${appName}:${envName} in ${region} health is ${e.getHealthStatus}.",
                "Wait while",
                "Fix bugs",
                "Scale up or out",
                "Recreate environment"
              )
            }
          } else {
            Error(
              "EBSNotDockerContainer",
              s"${appName}:${envName} is not docker container",
              s"${appName}:${envName} in ${region} is not docker container",
              "Recreate as Docker container",
              """Switch staging to Docker container env
            |staging := "{otherStage}"
          """.stripMargin,
              """(not recommend)Change envName to Docker container env
            |envName := {environmentName}
          """.stripMargin)
          }
        }
        case None => {
          Error(
            "EBSEnvNotFound",
            s"${appName}:${envName} not found",
            s"${appName}:${envName} in ${region} not found",
            "Create environment as Docker container",
            """Switch staging to Docker container env
              |staging := "{otherStage}"
            """.stripMargin,
            """(not recommend)Change envName to Docker container env
              |envName := {environmentName}
            """.stripMargin
          )
        }
      }
    }catch{
      case t : Throwable=> toError("EBS",t)
    }
  }

  def checkAppVersionExists(region: String,appName: String,appVersion: String,replace: Boolean)(implicit cli: AWSElasticBeanstalkClient) = {
    ebs.describeAppVersion(appName,appVersion) match{
      case Some(_) => {
        if(replace){
          Warning(
            "EBSAppVerExists",
            s"${appName}::${appVersion} already exists",
            s"${appName}::${appVersion} in ${region} already exists.Will replace.",
            "You should replace only in test env."
          )
        }else{
          Error(
            "EBSAppVerExists",
            s"${appName}::${appVersion} already exists",
            s"${appName}::${appVersion} in ${region} already exists.Deploy will fail.",
            """Increment version
              |version in EBS := "{newVersion}"
            """.stripMargin,
            """(Not recommend)Set replace flag
              |replaceAppVersion := true
            """.stripMargin
          )
        }
      }
      case None => Ok
    }




  }



  private def toError(prefix: String,t: Throwable) = {

    t match{
      case a: AmazonServiceException if a.getStatusCode == 403 => {
        Error(
          "AuthError",
          "Fail to auth",
          "Fail to authenticate.See https://github.com/geishatokyo/sbt-gte-deploy/blob/develop/doc/Config.md",
          "Set IAM role",
          "Set deploy/gdep.conf",
          "Set ~/.aws/credentials",
          "Check api access rights",
          "Check access key typo"
        )
      }
      case a: AmazonClientException if a.getMessage.contains("Connect") => {
        Error(
          "NetworkError",
          "Network is down",
          "Network is down",
          "Please check your network."
        )
      }
      case _ => {
        Error(
          s"${prefix}UnknownError",
          s"Error to access ${prefix}:${t.getMessage}",
          t.toString,
          "fix bug"
        )

      }
    }
  }

  def printReports(reports: Seq[Report],s: TaskStreams) = {

    val log = s.log

    reports.groupBy(_.name).mapValues(_.head).values.foreach({
      case Ok =>
      case no: NotOk => {
        val name = no.name
        val message = no.message
        val detail = no.detail
        val solutions = no.solutions
        no match{
          case _ : Error => log.error(s"----- ${name} -----" )
          case _ : Warning => log.warn(s"----- ${name} -----" )
        }
        log.info(message)
        if(detail.length > 0) {
          log.debug("- detail -")
          log.debug(detail)
        }

        if(solutions.size > 0) {
          log.debug("- solutions -")
          solutions.zipWithIndex.map(t => s"#${t._2 + 1} ${t._1}").foreach(sol => {
            log.debug(sol)
          })
        }
      }
    })

  }


  object ErrorName{
    val ECRRepoNotFound = "ECRRepoNotFound"
  }


  trait Report{
    def name: String
  }
  case object Ok extends Report{
    def name = "Ok"
  }
  trait NotOk extends Report{
    def message: String
    def detail: String
    def solutions : Seq[String]
  }

  case class Warning(name: String,message: String,detail: String, solutions: String*) extends NotOk
  case class Error(name: String,message: String,detail: String, solutions: String*) extends NotOk




}
