package gtedeploy

import java.io.ByteArrayOutputStream

import com.amazonaws.services.ecr.model.DescribeRepositoriesRequest
import gtedeploy.util.StringTemplate
import sbt.Keys._
import sbt._

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  * Created by takezoux2 on 2016/05/31.
  */
object GTEDeployPlugin extends AutoPlugin with AWSFunctions with Zipper {


  override def requires: Plugins = com.typesafe.sbt.packager.docker.DockerPlugin

  private val DockerKeys = com.typesafe.sbt.packager.docker.DockerPlugin.autoImport

  object autoImport extends Keys{
    val GTEDeploy = config("gdep")

    val ECR = config("ecr").extend(GTEDeploy)
    val EBS = config("ebs").extend(GTEDeploy)
    val S3 = config("s3").extend(GTEDeploy)

  }

  import autoImport._


  override lazy val projectSettings =
    commonSettings ++ inConfig(GTEDeploy)(
      commonSettingsInGTEDeploy ++
      ecrSettings ++
      ebsSettings ++
      gitSettings ++
      phaseSettings
    ) ++ Seq(
      commands ++= deployCommands
    )


  def commonSettings = Seq(
    awsRegion := "ap-northeast-1",
    staging := "stage1",
    ecrRepository := None,
    replaceAppVersion := false,
    ebextensionsDir := None,
    dockerrunTemplateFile := None,
    deployTimeout := 10.minutes,
    envName <<= (staging,appName in EBS) map getEnvName,
    configFile := file("deploy/gdep.conf")
  )

  def deployCommands = {
    Seq(forceDeployCommand)
  }
  def forceDeployCommand = Command.args("gdep-publish", "<staging>",Help("publish",("a","b"),"detail"))( (state,args) => {

    val ext = Project.extract(state)

    val deployState = args.headOption match{
      case Some(st) => {
        state.log.info(s"Switch staging = ${st}")
        ext.append(staging := st,state)
      }
      case None => {
        state
      }
    }

    deployState.log.info("Begin deploy")
    Project.runTask(allPhase in GTEDeploy,deployState) match{
      case Some((successState,Value(_))) => {
        state
      }
      case Some((failedState,Inc(_))) => {
        failedState.log.info("Done in fail")
        state.fail
      }
      case None => {
        state.log.warn("Threr are bugs on plugin.")
        state.fail
      }
    }
  })


  def commonSettingsInGTEDeploy = Seq(
    workingDir <<= (baseDirectory) map getWorkingDir,
    gteDeployConf <<= (configFile) map getGTEDeployConf
  )

  def getWorkingDir(baseDir: File) =
    new File(baseDir,"target/gtedeploy")

  def getGTEDeployConf(file: File) =
    GTEDeployConf.load(file)


  // ecr settings
  def ecrSettings : Seq[Setting[_]] = Seq(
    repositoryUri <<= (ecrRepository,appName in ECR,awsRegion in ECR,streams,gteDeployConf) map getRepositoryUri,
    dockerImageNameToPush <<= (repositoryUri,version in GTEDeploy) map getDockerImageNameToPush,
    tagDockerImage <<= (DockerKeys.dockerTarget in DockerKeys.Docker,dockerImageNameToPush,streams) map taskTagDockerImage,
    loginDocker <<= (awsRegion in ECR,streams,gteDeployConf) map taskLoginDocker,
    pushToEcr <<= (dockerImageNameToPush,streams) map taskPushToEcr
  )

  /**
    * Try to get repository uri from appName in ECR if key "ecrRepository" is not set.
    *
    * @param ecrRepo
    * @param appName
    * @param region
    * @param streams
    * @return
    */
  def getRepositoryUri(ecrRepo: Option[String],appName: String,region: String,streams: TaskStreams, conf: GTEDeployConf) = {
    val log = streams.log
    ecrRepo.getOrElse {
      useEcrClient(conf,region){ client =>
        val res = client.describeRepositories(new DescribeRepositoriesRequest().
          withRepositoryNames(appName))

        val repos = res.getRepositories()
        if(repos.size() == 0){
          fail(s"Can't find ecr repository for ${appName}")
        }else{
          repos.get(0).getRepositoryUri
        }
      }
    }
  }

  def getDockerImageNameToPush(repoUri: String,tag: String) =
    s"${repoUri}:${tag}"


  // ecr tasks


  def taskTagDockerImage(builtImageName: String,imageNameToPush: String,s: TaskStreams) : String = {

    val log = s.log
    val ret = Process(Seq("docker","tag",builtImageName,imageNameToPush)) ! s.log

    if(ret == 0){
      log.info(s"Tag Docker image:${imageNameToPush}")
      imageNameToPush
    }else{
      throw new RuntimeException("Fail to tag docker image.")
    }
  }
  def taskLoginDocker(region: String,s: TaskStreams,conf: GTEDeployConf) : Unit = {
    val log = s.log

    val (username,password,endpoint) = useEcrClient(conf,region){ implicit client =>
      getDockerLoginInfo()
    }
    val fullCommand = s"docker login -u ${username} -p ${password} -e none ${endpoint}"
    log.debug(s"exec: ${fullCommand}")

    val loginCommand = Seq("docker", "login", "-u", username, "-p", password, "-e", "none", endpoint)
    val ret = Process(loginCommand) ! log
    if (ret != 0) {
      throw new RuntimeException("Fail to login ECR")
    }
  }

  def taskPushToEcr(imageName: String, s: TaskStreams) = {
    val log = s.log

    val pushCommand = Seq("docker", "push", imageName)
    val ret = Process(pushCommand) ! log
    if (ret != 0) {
      throw new RuntimeException(s"Fail to push ECR:${imageName}")
    }
  }



  // ebs settings

  def ebsSettings : Seq[Setting[_]] = Seq(
    ebsZipBucketName <<= (appName in EBS) map getEbsZipBucketName,
    ebsZipName <<= (appName in EBS,version in GTEDeploy) map getEbsZipName,
    ebsZipFile <<= (workingDir,ebsZipName) map getEbsZipFile,
    versionLabel := {(version in GTEDeploy).value},
    versionLabelDescription := commitId.value,
    dockerrunTemplate <<= (dockerrunTemplateFile) map getDockerrunTemplate,
    makeEbsZip <<= (dockerrunTemplate,repositoryUri,DockerKeys.dockerExposedPorts in DockerKeys.Docker,
      ebextensionsDir,ebsZipFile,streams
      ) map taskMakeEbsZip,
    uploadEbsZip <<= (awsRegion in S3,ebsZipBucketName,ebsZipFile,streams,gteDeployConf) map taskUploadEbsZip,
    createAppVersion <<= (awsRegion in EBS,ebsZipBucketName,ebsZipFile,
      appName in EBS, versionLabel,versionLabelDescription,
      replaceAppVersion,streams,gteDeployConf) map taskCreateAppVersion,
    updateEnvironment <<= (awsRegion in EBS,appName in EBS,envName in EBS,versionLabel,gteDeployConf) map taskUpdateEnvironment,
    waitFinishDeploy <<= (awsRegion in EBS,appName,envName,deployTimeout,streams,gteDeployConf) map taskWaitFinishDeploy
  )

  def getEnvName(staging: String,appName: String) =
    s"${staging}-${appName}"


  def getEbsZipBucketName(appName: String) =
    s"app-version-${appName}"

  def getEbsZipName(appName: String,version: String) = {
    s"${appName}_${version}.zip"
  }
  def getEbsZipFile(workingDir: File,zipName: String) = {
    new File(workingDir,zipName)
  }

  def getDockerrunTemplate(templateFile: Option[File]) = {
    templateFile match{
      case Some(file) => sbt.IO.read(file)
      case None => DockerrunTemplate
    }
  }

  val DockerrunTemplate =
    """{
      |  "AWSEBDockerrunVersion": "1",
      |  "Image" : {
      |    "Name" : "${dockerImageURI}",
      |    "Update": "true"
      |  },
      |  "Ports" : [
      |    { "ContainerPort": "${port}" },
      |    { "ContainerPort": "${sslPort}" }
      |  ],
      |  "Logging": "/opt/docker/log"
      |}
    """.stripMargin



  // ebs tasks

  def taskMakeEbsZip(template: String,repositoryUri: String,ports: Seq[Int],
                           ebextensionsDir: Option[File],
                           zipPath: File,
                           s: TaskStreams) : File = {
    val dockerrun = StringTemplate.render(template,
      "dockerImageURI" -> repositoryUri,
      "port" -> ports.headOption.getOrElse(9000).toString,
      "sslPort" -> ports.drop(1).headOption.getOrElse(9443).toString
    )

    val extentions : List[ZipElem] = ebextensionsDir.map(dir => {
      dir.listFiles().map(f => {
        ZipFile(".ebextensions/" + f.getName,f)
      }).toList
    }).getOrElse(Nil)

    s.log.info(("Zip next files -->" :: "Dockerrun.aws.json" :: ebextensionsDir.map(dir => {
      dir.listFiles().map(f => ".ebextensions/" + f.getName).toList
    }).getOrElse(Nil)).mkString("\n"))


    val zipped = zip( (ZipBinary("Dockerrun.aws.json",dockerrun.getBytes("utf-8")) :: extentions):_*)
    IO.write(zipPath,zipped)
    zipPath
  }

  def taskUploadEbsZip(region: String,bucketName: String,zipFile: File,s: TaskStreams,conf: GTEDeployConf) : Unit = {
    useS3Client(conf,region){ implicit s3 =>
      s.log.info(s"Upload ${zipFile} to S3[${bucketName}:${zipFile.getName}]")
      upload(bucketName,zipFile.getName,zipFile)
    }
  }

  def taskCreateAppVersion(region: String,
                           bucketName: String,
                           zipFile: File,
                           appName: String,
                           versionLabel: String,
                           description: String,
                           replace : Boolean,
                           s: TaskStreams,conf: GTEDeployConf) : Unit = {

    val fileKey = zipFile.getName
    useEBSClient(conf,region){implicit c =>
      val exists = ebs.existsAppVersion(appName,versionLabel)

      if(!exists){
        s.log(s"Create ${appName}:${versionLabel}")
        ebs.createAppVersion(appName,versionLabel,description,bucketName,fileKey)
      }else if(replace){
        s.log(s"Replace ${appName}:${versionLabel}")
        ebs.deleteVersionLabel(appName,versionLabel)
        ebs.createAppVersion(appName,versionLabel,description,bucketName,fileKey)
      }else{
        s.log(s"${appName}:${versionLabel} already exists")
        throw new RuntimeException("Fail to upload ebs zip.Because save version exists.")
      }
    }
  }

  def taskUpdateEnvironment(region: String,appName: String,envName: String, versionLabel: String, conf: GTEDeployConf) = {
    useEBSClient(conf,region)(implicit client =>
      ebs.updateEnvironment(appName,envName,versionLabel)
    )
  }

  def taskWaitFinishDeploy(region: String, appName: String, envName: String, timeout: FiniteDuration, s: TaskStreams, conf: GTEDeployConf) : Boolean = {
    import java.util.Date
    val start = new Date().getTime
    useEBSClient(conf,region) { implicit c =>

      @tailrec
      def waitUntil(st : String,until: Long) : (String,String) = {
        Thread.sleep(1000)
        val (status, color) = ebs.getHelth(appName, envName)
        if(status == st) {
          if(new Date().getTime < until){
            waitUntil(st,until)
          }else {
            (status,color)
          }
        }
        else {
          (status,color)
        }
      }

      val (st,cl) = waitUntil("Ok",start + 10.seconds.toMillis)
      if(cl == "Green"){
        s.log.info(s"Stat:${st}(${cl}).Maybe deploy is not triggered.")
        return true
      }
      s.log.info(s"Stat change: ${st}(${cl})")
      val (lastS,lastC) = if(st == "Info"){
        waitUntil("Info",start + timeout.toMillis)
      }else (st,cl)

      s.log.info(s"Last Stat: ${lastS}(${lastC})")

      if (lastS == "Info") {
        s.log.warn("Timeout to deploy")
        false
      } else if (lastC == "Green") {
        s.log.info("Success to deploy")
        true
      } else {
        s.log.info("Fail to deploy")
        false
      }
    }
  }


  // git

  val gitSettings : Seq[Setting[_]] = Seq(
    commitId := getCommitId()
  )


  def getCommitId() = {
    val output = new ByteArrayOutputStream()
    val code = Process(Seq("git","rev-parse","HEAD")) #> (output)
    if(code != 0){
      "Not git repository"
    }else {
      val result = new String(output.toByteArray, "utf-8")
      val index = result.indexOf(27.toChar)
      if (index > 0) {
        result.substring(0, index)
      } else {
        result
      }
    }
  }


  // collaboration

  val phaseSettings : Seq[Setting[_]] = Seq(
    runBuildPhase(),
    runPushPhase(),
    runMakeVersionLabelPhase(),
    runDeployPhase(),
    runWaitFinishPhase(),
    runAllPhase()
  )

  def runBuildPhase() = {
    buildPhase := Def.sequential(
      publishLocal in DockerKeys.Docker,
      tagDockerImage
    ).value
  }

  def runPushPhase() = {
    pushPhase := Def.sequential(
      loginDocker,
      pushToEcr
    ).value
  }

  def runMakeVersionLabelPhase() = {
    makeVersionLabelPhase := (Def.taskDyn {
      val log = streams.value.log
      log.debug("-- Make version label phase --")

      Def.sequential(
        makeEbsZip,
        uploadEbsZip,
        createAppVersion
      )
    } ).value
  }
  def runDeployPhase() = {
    deployPhase := updateEnvironment.value
  }

  def runWaitFinishPhase() = {
    waitFinishPhase := {waitFinishDeploy.value}
  }


  def runAllPhase() = {
    allPhase := Def.sequential(
      buildPhase,
      pushPhase,
      makeVersionLabelPhase,
      deployPhase,
      waitFinishPhase
    ).value
  }


}
