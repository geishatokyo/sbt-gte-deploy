# Commands


### gdep-publish

```
gdep-publish [-skip-check] [{staging}]
```

Deploy server to EBS.
(Not Recommend)If set -skip-check, skip check phase.This is not safe way.


### gdep:doctor

```
gdep:deploy
```

Check needed environment.
You can see detail by
```
last gdep:deploy
```

### gdep:pushDockerImage

```
gdep:pushDockerImage
```

Build and push Docker image to ECR.


### gdep:genDockerrunAwsJson

```
gdep:genDockerrunAwsJson [{pathToGenerate}]
```
Generate Dockerrun.aws.json template.
If not pass the path, file is generated to "deploy/Dockerrun.aws.json".

