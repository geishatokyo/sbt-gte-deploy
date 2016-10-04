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
object GTEDeployPlugin extends AutoPlugin with Doctor with AWSFunctions with Zipper {


  override def requires: Plugins = com.typesafe.sbt.packager.docker.DockerPlugin

  private val DockerKeys = com.typesafe.sbt.packager.docker.DockerPlugin.autoImport

  object autoImport extends Keys

  import autoImport._


  override lazy val projectSettings =
    commonSettings ++ inConfig(GTEDeploy)(
      commonSettingsInGTEDeploy ++
      checkSettings ++
      ecrSettings ++
      ebsSettings ++
      gitSettings ++
      phaseSettings ++
      doctorSettings
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

    val (ops,params) = args.partition(_.startsWith("-"))

    val deployState = params.headOption match{
      case Some(st) => {
        state.log.info(s"Switch staging = ${st}")
        ext.append(staging := st,state)
      }
      case None => {
        state
      }
    }

    val skipCheck = ops.contains("-skip-check")

    val phaseTask = if(skipCheck) skipCheckPhase else allPhase

    deployState.log.info("Begin deploy")
    Project.runTask(phaseTask in GTEDeploy,deployState) match{
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

  // check settings

  def checkSettings : Seq[Setting[_]] = Seq(
    noCheck := {DoctorResult.Ok},
    strictCheck := doctor.value,
    briefCheck <<= (awsRegion in EBS, appName in EBS, envName,streams,gteDeployConf) map runBriefCheck,
    preCheck := briefCheck.value
  )

  def runBriefCheck(region: String,appName: String,envName: String,s: TaskStreams, conf: GTEDeployConf) = {

    useEBSClient(conf,region){implicit cli =>
      val (status,color) = ebs.getHelth(appName,envName)
      if(color == "Green" && status == "Ok") {
        DoctorResult.Ok
      }else if(status == "Ok"){
        s.log.warn(s"Env status is Ok but not so good color:${color}.")
        DoctorResult.Warning
      }else{
        s.log.error(s"Bad env status.${status}(${color})")
        DoctorResult.Error
      }
    }
  }


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
    dockerrunTemplateArgs <<= (appName in EBS,staging,repositoryUri,version in GTEDeploy,DockerKeys.dockerExposedPorts in DockerKeys.Docker) map getDockerrunTemplateArgs,
    makeEbsZip <<= (dockerrunTemplate,dockerrunTemplateArgs,
      ebextensionsDir,ebsZipFile,streams
      ) map taskMakeEbsZip,
    uploadEbsZip <<= (awsRegion in S3,ebsZipBucketName,ebsZipFile,streams,gteDeployConf) map taskUploadEbsZip,
    createAppVersion <<= (awsRegion in EBS,ebsZipBucketName,ebsZipFile,
      appName in EBS, versionLabel,versionLabelDescription,
      replaceAppVersion,streams,gteDeployConf) map taskCreateAppVersion,
    updateEnvironment <<= (awsRegion in EBS,appName in EBS,envName in EBS,versionLabel,gteDeployConf) map taskUpdateEnvironment,
    waitFinishDeploy <<= (awsRegion in EBS,appName,envName,deployTimeout,streams,gteDeployConf) map taskWaitFinishDeploy,
    genDockerrunAwsJson := {
      import sbt.complete.Parsers.spaceDelimited
      val args: Seq[String] = spaceDelimited("export path").parsed
      val s = streams.value
      args.headOption match{
        case Some(filePath) => taskGenerateDockerrunAwsJson(filePath,s)
        case None => taskGenerateDockerrunAwsJson("deploy/Dockerrun.aws.json",s)
      }
    }
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
      case None => new String(
        sbt.IO.readBytes(
          getClass.getClassLoader.getResourceAsStream("Dockerrun.aws.json.v2template")),
        "utf-8")
    }
  }

  def getDockerrunTemplateArgs(
                              appName: String,
                              staging: String,
                              repositoryUri: String,
                              version: String,
                              ports: Seq[Int]) = {
    Seq(
      "appName" -> appName,
      "staging" -> staging,
      "dockerImageURI" -> repositoryUri,
      "dockerImageUri" -> repositoryUri,
      "port" -> ports.headOption.getOrElse(9000).toString,
      "sslPort" -> ports.drop(1).headOption.getOrElse(9443).toString
    )
  }



  // ebs tasks

  def taskGenerateDockerrunAwsJson(exportPath: String,s: TaskStreams) = {
    val d = new File(exportPath)
    val f = if(d.isDirectory){
      new File(d,"Dockerrun.aws.json")
    }else{
      d
    }

    if(f.exists()){
      s.log.info(
        s"""${exportPath} already exists.
           |-- Add setting --
           |dockerrunTemplateFile := Some("${exportPath}")
         """.stripMargin)
    }else {
      val template = getDockerrunTemplate(None)
      sbt.IO.write(f, template)

      s.log.info(
        s"""
        |Generate ${exportPath}.
        |-- Add setting --
        |dockerrunTemplateFile := Some("${exportPath}")
        """.stripMargin
      )

    }
  }

  def taskMakeEbsZip(template: String,templateArgs: Seq[(String,String)],
                           ebextensionsDir: Option[File],
                           zipPath: File,
                           s: TaskStreams) : File = {
    val dockerrun = StringTemplate.render(template,
      templateArgs:_*
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
    useS3Client(conf,region){ implicit c =>
      s.log.info(s"Upload ${zipFile} to S3[${bucketName}:${zipFile.getName}]")
      s3.upload(bucketName,zipFile.getName,zipFile)
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
        throw new RuntimeException("Fail to upload ebs zip.Same version already exists.")
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
      def waitUntil(st : String,until: Long,count : Int = 0) : (String,String) = {
        Thread.sleep(1000)
        if(count % 5 == 0) print(".")
        val (status, color) = ebs.getHelth(appName, envName)
        if(status == st) {
          if(new Date().getTime < until){
            waitUntil(st,until,count + 1)
          }else {
            println()
            (status,color)
          }
        }
        else {
          println()
          (status,color)
        }
      }

      print("Waiting start deploy")
      val (st,cl) = waitUntil("Ok",start + 30.seconds.toMillis)
      if(st == "Ok"){
        s.log.info(s"Stat:${st}(${cl}).Maybe deploy is not triggered.")
        return true
      }
      s.log.info(s"Stat change: ${st}(${cl})")
      val (lastS,lastC) = if(st == "Info"){
        print("Deploying")
        waitUntil("Info",start + timeout.toMillis)
      }else (st,cl)

      s.log.info(s"Last Stat: ${lastS}(${lastC})")

      if (lastS == "Info") {
        s.log.warn("Timeout to deploy")
        false
      } else if (lastC == "Green") {
        s.log.success("Success to deploy")
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
    runCheckPhase(),
    runBuildPhase(),
    runPushPhase(),
    runMakeVersionLabelPhase(),
    runDeployPhase(),
    runWaitFinishPhase(),
    runAllPhase(),
    runSkipCheckPhase()
  )

  def runCheckPhase() = {
    checkPhase := {
      preCheck.value match{
        case DoctorResult.Ok =>
        case DoctorResult.Warning =>
        case DoctorResult.Error => {
          throw new RuntimeException("Fail to precheck")
        }
      }
    }
  }

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
    makeAppVersionPhase := (Def.taskDyn {
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
      checkPhase,
      buildPhase,
      pushPhase,
      makeAppVersionPhase,
      deployPhase,
      waitFinishPhase
    ).value
  }
  def runSkipCheckPhase() = {
    skipCheckPhase := Def.sequential(
      buildPhase,
      pushPhase,
      makeAppVersionPhase,
      deployPhase,
      waitFinishPhase
    ).value
  }


}
