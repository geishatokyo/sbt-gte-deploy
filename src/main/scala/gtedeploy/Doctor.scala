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

  val reportGit = TaskKey[Seq[Report]]("report-git")
  val reportDocker = TaskKey[Seq[Report]]("report-docker")
  val reportEcr = TaskKey[Seq[Report]]("report-ecr")
  val reportS3 = TaskKey[Seq[Report]]("report-s3")
  val reportEbs = TaskKey[Seq[Report]]("report-ebs")
  val reportAll = TaskKey[Seq[Report]]("report-all")

  val doctorSettings = {
    val ai = autoImport
    import ai._
    Seq(
      ebsSet := { ((appName in EBS).value, envName.value, versionLabel.value,replaceAppVersion.value)},
      reportGit := runReportGit(),
      reportDocker := runReportDocker(),
      reportEcr <<= (appName in ECR,ecrRepository,awsRegion in ECR,streams, gteDeployConf) map runReportEcr,
      reportS3 <<= (ebsZipBucketName,awsRegion in S3,streams, gteDeployConf) map runReportS3,
      reportEbs <<= (awsRegion in EBS,appName in EBS,envName, versionLabel, replaceAppVersion,streams, gteDeployConf) map runReportEBS,
      reportAll <<= (reportGit,reportDocker,reportEcr,reportS3,reportEbs) map mergeReports,
      doctor <<= (reportAll,streams) map runDoctor,
      autoFix := Def.taskDyn {
        val autoFixes = reportAll.value.flatMap(_.autoFix)
        val s = streams.value
        if(autoFixes.size > 0){
          autoFixes.foreach(af => s.log.info(af.explain))

          import sbt.complete.Parsers.spaceDelimited

          println("Are you ok to create aws services?(y/N)")
          val yesOrNo = readLine()
          yesOrNo match{
            case "y" | "Y" => {
              autoFixes.foreach(af => af.fixFunc())
              s.log.info("Done auto fix")
            }
            case _ => {
              s.log.info("Auto fix is canceled")
            }
          }
          Def.task[Unit](())
        }else{
          s.log.info("No available auto fixes")
          Def.task[Unit](())
        }
      }.value
    )
  }

  def runReportGit() : Seq[Report] = {
    Seq(checkGit())
  }
  def runReportDocker() : Seq[Report] = {
    Seq(checkDocker())
  }
  def runReportEcr(appNameInEcr: String,ecrRepository: Option[String],region: String,s: TaskStreams, config: GTEDeployConf) : Seq[Report] = {
    Seq(checkECRRepositoryExists(appNameInEcr,ecrRepository,region,s,config))
  }
  def runReportS3(bucketName: String, region:String, s: TaskStreams, conf: GTEDeployConf) : Seq[Report] = {
    Seq(checkS3(bucketName,region,s,conf))
  }

  def runReportEBS(region: String,appNameInEBS: String, envName: String, appVersion: String, replaceAppVersion: Boolean, s: TaskStreams, conf: GTEDeployConf) : Seq[Report] = {
    checkBeanstalk(region,appNameInEBS,envName,appVersion,replaceAppVersion,s,conf)
  }

  def mergeReports(r1: Seq[Report],r2: Seq[Report],r3: Seq[Report],r4: Seq[Report],r5: Seq[Report]) = {
    r1 ++ r2 ++ r3 ++ r4 ++ r5
  }


  def runDoctor(reports: Seq[Report],
                s: TaskStreams) = {

    printReports(reports,s)

    if(reports.exists(_.isInstanceOf[Error])){
      DoctorResult.Error
    }else if(reports.exists(_.isInstanceOf[Warning])){
      DoctorResult.Warning
    }else{
      DoctorResult.Ok
    }
  }

  private def checkGit() = {
    val r = Process(Seq("git","status")).!
    if(r == 0){
      Ok
    }else{
      Warning(
        "NotGitRepo",
        "Not git repository",
        "Git is not installed or this directory is not under git.",
        "Execute 'git init'",
        "Install git"
      )
    }
  }

  private def checkDocker() = {
    val r = Process(Seq("docker","ps")).!
    if(r == 0){
      Ok
    }else{
      Error(
        "DockerNotRunning",
        "Docker is not running",
        "Docker is not installed or not running.",
        "Install docker",
        "Run docker"
      )
    }
  }


  private def checkECRRepositoryExists(_appName: String,
                                       fullUri: Option[String],
                                       region: String,
                                       s : TaskStreams,
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
        ).withAutoFix(s"Create ECR Repository:${appName}.(Free)",
          () => createEcrRepo(region,appName,s,conf))
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

  def checkS3(bucketName: String,region: String, s: TaskStreams, conf: GTEDeployConf) = {
    useS3Client(conf,region){ implicit cli =>
      try{
        val loc = s3.existsBucket(bucketName)
        Ok
      }catch{
        case e : AmazonS3Exception if e.getStatusCode == 404 => {
          Error(
            S3BucketNotFound,
            s"S3 bucket '${bucketName}' not found",
            s"S3 bucket '${bucketName}' not found in region ${region}",
            s"Create bucket '${bucketName}",
            s"""Set exist bucket name
               |ebsZipBucketName in GTEDeploy := "_bucket_name_"
             """.stripMargin

          ).withAutoFix(s"Create new S3 bucket:${bucketName}.(Free)",
            () => createAppVersionBucket(region,bucketName,s,conf))
        }
        case e: Throwable => toError("S3",e)
      }
    }

  }

  def checkBeanstalk(region: String,appName: String,envName: String,appVersion: String,replace : Boolean,s: TaskStreams,conf: GTEDeployConf) : Seq[Report] = {
    useEBSClient(conf,region){implicit cli =>

      val r = checkApplicationCondition(region,appName,s,conf)
      if(r == Ok){
        Seq(
          checkEnvironmentCondition(region,appName,envName,s,conf),
          checkAppVersionExists(region,appName,appVersion,replace)
        )
      }else{
        Seq(
          r,
          envNotFoundError(appName,envName,region,s,conf)
        )
      }
    }
  }

  def checkApplicationCondition(region: String,appName: String,s: TaskStreams, config: GTEDeployConf)(implicit cli: AWSElasticBeanstalkClient) = {
    try{
      ebs.describeApplication(appName) match{
        case Some(app) => {
          Ok
        }
        case None => {
          Error(
            "EBSAppNotFound",
            s"${appName} not found",
            s"${appName} in ${region} not found",
            "Create new ebs application",
            """Set exist appName
              |appName in EBS := "{appName}"
            """.stripMargin
          ).withAutoFix(s"Create EBS application:${appName}.(Free)",
            () => createEBSApplication(region,appName,s,config))
        }
      }
    }catch{
      case e : Throwable => toError("ECR",e)
    }
  }

  def checkEnvironmentCondition(region: String,appName: String,envName: String,s: TaskStreams,conf: GTEDeployConf)(implicit cli: AWSElasticBeanstalkClient) = {
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
          envNotFoundError(appName,envName,region,s,conf)
        }
      }
    }catch{
      case t : Throwable=> toError("EBS",t)
    }
  }

  private def envNotFoundError(appName: String, envName: String, region: String, s: TaskStreams, conf: GTEDeployConf) = {

    Error(
      "EBSEnvNotFound",
      s"${appName}:${envName} not found",
      s"${appName}:${envName} in ${region} not found",
      "Create environment as Docker container",
      """Switch staging to existing Docker container env
        |staging := "{otherStage}"
      """.stripMargin,
      """(not recommend)Change envName to existing Docker container env
        |envName := "{environmentName}"
      """.stripMargin
    ).withAutoFix(s"Create EBS Environment:${appName}/${envName}(Not Free!!!)",
      () => createEBSEnvironment(region,appName,envName,s,conf)
    )
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


  private def createEcrRepo(region: String,repoName: String,s: TaskStreams, config: GTEDeployConf) = {
    useEcrClient(config,region)(implicit c => {
      s.log.info(s"Create ECR Repository:${repoName}")
      val r = ecr.createECRRepository(repoName).getRepository.getRepositoryUri
      s.log.debug(r.toString)
    })
  }

  private def createAppVersionBucket(region: String,bucketName: String,s: TaskStreams, config: GTEDeployConf) = {
    useS3Client(config,region)(implicit c => {
      s.log.info(s"Create AppVersion Bucket:${bucketName}")
      val r = s3.createBucket(bucketName).getName
      s.log.debug(r.toString)
    })
  }

  private def createEBSApplication(region: String, appName: String,s: TaskStreams, config: GTEDeployConf) = {
    useEBSClient(config,region)(implicit c => {
      s.log.info(s"Create EBS Application:${appName}")
      val r = ebs.createApplication(appName)
      s.log.debug(r.toString())
    })
  }
  private def createEBSEnvironment(region: String, appName: String, envName: String,s: TaskStreams, config: GTEDeployConf) = {
    useEBSClient(config,region)(implicit c => {
      s.log.info(s"Create EBS Environment:${appName}/${envName}")
      val r = ebs.createEnvironment(appName, envName)
      s.log.debug(r.toString)
    })
  }


  object ErrorName{
    val ECRRepoNotFound = "ECRRepoNotFound"
    val S3BucketNotFound = "S3BucketNotFound"
  }


  trait Report{
    def name: String
    def autoFix : Option[AutoFix]
  }
  case object Ok extends Report{
    def name = "Ok"
    val autoFix = None
  }
  trait NotOk extends Report{
    def message: String
    def detail: String
    def solutions : Seq[String]

    override def autoFix: Option[AutoFix] = {
      _autoFix
    }
    private var _autoFix : Option[AutoFix] = None
    def withAutoFix(explain: String, func: () => Unit) : this.type = {
      _autoFix = Some(AutoFix(explain,func))
      this
    }
  }

  case class Warning(name: String,message: String,detail: String, solutions: String*) extends NotOk
  case class Error(name: String,message: String,detail: String, solutions: String*) extends NotOk

  case class AutoFix(explain: String, fixFunc : () => Unit)



}
