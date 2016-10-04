# Keys

## Commons

### appName (Requred)

Type:String  
Available Configuration:ECR,EBS  

Use for application name in EBS

```scala
appName := "your-app-name"

appName in ECR := "to use another name in ECR"
```

### staging (Should set)

Type:String  
Default:stage1

Use for prefix of environment in EBS

```scala
staging := "stage1"
```

### awsRegion (Should set)

Type:String  
Available Configuration:ECR,EBS,S3  
Default:ap-northeast-1 (is japan)

AWS regions.

```scala
awsRegion := "us-east-1"//effect to all

awsRegion in ECR := "us-east-2" // apply only to ECR
```

### version in GTEDeploy(Optional)

Type:String  
Default:version in *

version to use Docker tag and EBS app version label.

### configFile(Optional)

Type:File  
Default:file("deploy/gdep.conf")

Config file path.Config file is Typesefe config.

### workingDir(Optionl)

Type:File  
Default:baseDir / "target/gtedeploy"

Directory to put temporally files.

## ECR

### ecrRepository(Optional)

Type:Option[String]  
Default:None

Full uri of ECR repository.If set None, uri is guessed from appName in ECR.


## EBS

### replaceAppVersion(Optional)

Type:Boolean  
Default:false

Update AppVersion in EBS if same version is already created.

### deployTimeout(Optional)

Type:FiniteDuration  
Default:10.minutes

Max wait time to finish deploy.

```scala
import scala.concurrent.duration._

deployTimeout := 10.minutes
```

### envName(Optional)

Type:String  
default:{staging}-{appName}

Environment name to deploy.

### dockerrunTemplateFile(Optional)

Type:Option[File]  
Default:None

Template file to create Dockerrun.aws.json.

### dockerrunTemplateArgs(Optional)

Type:Seq[(String,String)]
Default:see below

Args to replace in DockerrunTemplateFile.
Default args are

|key|value|
|${appName}|SettingKey of appName|
|${staging}|SettingKey of staging|
|${dockerImageURI}|TaskKey of repositoryUri|
|${port}|First value of dockerExposedPorts or 9000|
|${sslPort}|Second value of dockerExposedPorts or 9443|


### ebextensionsDir(Optional)

Type:Option[File]  
Default:None

Directory path which contains nginx config files include to .ebextension in EBS zip.

### ebsZipBucketName(Optional)

Type:String  
Default:app-version-{appName}

S3 bucket name to put EBS zip file.

### versionLabel(Optional)

Type:String  
Default:version in GTEDeploy

Use for EBS App version label

### versionLabelDescription(Optional)

Type:String  
Default:git commit id

Use for EBS App version description



