package gtedeploy

import sbt._

import scala.concurrent.duration.FiniteDuration

/**
  * Created by takezoux2 on 2016/05/31.
  */
class Keys {


  // static sttings

  val awsRegion = SettingKey[String]("aws-region","AWS regions.You can set foreach service 'awsRegion in ECR := \"ap-northeast-1\"'.See https://docs.aws.amazon.com/ja_jp/general/latest/gr/rande.html")
  val staging = SettingKey[String]("staging","Select stage to deploy")
  val appName = SettingKey[String]("app-name","Application name")

  val ecrRepository = SettingKey[Option[String]]("ecr-repository","Full repository name.Default:get by api")

  val dockerrunTemplateFile = SettingKey[Option[File]]("dockerrun-template-file","Template file of Dockerrun.aws.json")
  val ebextensionsDir = SettingKey[Option[File]]("ebextensions-dir","Directory which contains files to add .ebextensions dir in ebs zip")

  val replaceAppVersion = SettingKey[Boolean]("replace-app-version","Replace app version if same version exists")

  val deployTimeout = SettingKey[FiniteDuration]("deploy-timeout","Deploy timeout")

  val configFile = SettingKey[File]("config-file","Path to config(Default:deploy/gdep.conf)")

  // dynamic settings
  val gteDeployConf = TaskKey[GTEDeployConf]("gte-deploy-conf","Load GTEDeploy config")

  val workingDir = TaskKey[File]("working-dir","Directory to export temp files.(default: target/gtedeploy)")
  val envName = TaskKey[String]("env-name","Deploy environment name on BeanStalk.")

  val ebsZipBucketName = TaskKey[String]("ebs-zip-bucket-name","Buckete to upload ebs zip file")

  val repositoryUri = TaskKey[String]("repository-uri","Get full ecr reposiotry path")

  val dockerImageNameToPush = TaskKey[String]("docker-image-name-to-push")

  val dockerrunTemplate = TaskKey[String]("dockerrun-template","Load Dockerrun.aws.json template")
  val ebsZipName = TaskKey[String]("ebs-zip-name","Name of ebs zip file")
  val ebsZipFile = TaskKey[File]("ebs-zip-file","Path to generateed ebs zip file")

  val commitId = TaskKey[String]("commit-id","Get git commit ID")
  val versionLabel = TaskKey[String]("version-label","EBS version label")
  val versionLabelDescription = TaskKey[String]("version-label-description","Get app version description.Default:${commitID}")

  // single tasks (only depends on settings)

  val tagDockerImage = TaskKey[String]("tag-docker-image","Tag docker image to push")
  val loginDocker = TaskKey[Unit]("login-docker","Login docker with ECR auth")
  val pushToEcr = TaskKey[Unit]("push-to-ecr","Push image to ecr")

  val makeEbsZip = TaskKey[File]("make-ebs-zip","Make ebs zip")
  val uploadEbsZip = TaskKey[Unit]("upload-ebs-zip","Upload ebs zip to S3")

  val createAppVersion = TaskKey[Unit]("create-app-version","Create app version to EBS")
  val updateEnvironment = TaskKey[Unit]("update-environment","Update EBS environment")

  val waitFinishDeploy = TaskKey[Boolean]("wait-finish-deploy","Wait until finish deploy")

  // collaborate tasks (call other tasks)

  val buildPhase = TaskKey[Unit]("build-phase","Build docker image phase")
  val pushPhase = TaskKey[Unit]("push-phase","Push docker image phase")
  val makeAppVersionPhase = TaskKey[Unit]("make-app-version-phase","Make EBS app version phase")

  val deployPhase = TaskKey[Unit]("deploy-phase","Deploy phase")
  val waitFinishPhase = TaskKey[Unit]("wait-finish-phase","Wait finish phase")

  val allPhase = TaskKey[Unit]("all-phase","run all phase")

}
