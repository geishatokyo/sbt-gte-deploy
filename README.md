# GeishaTokyo's deploy plugin

## これは何？

芸者東京で使っているデプロイプラグインです。<br />
PlayFrameworkをDockerImage化し、AWS ECRにPushし、AWS EBSへデプロイを行います。



## 事前準備

'show gdep:doctor'を実行することで、環境が整っているかチェックが出来ます。  
また、'show gdep:doctor'実行後'last gdep:doctor'で詳細や解決策を見ることが出来ます。


### ビルドマシン

#### 1:Docker

ローカルでdockerコマンドを使える状態にして下さい。

#### 2:sbtでPluginの設定

※ まだMavenCentralにPushしていないため、ソースからビルドしてpublishLocalしないと動きません

project/plugins.sbt
``` scala
addSbtPlugin("com.geishatokyo" % "sbt-gte-deploy" % "0.0.1")
```

build.sbt
```scala
enablePlugins(gtedeploy.GTEDeployPlugin)
```

を追加して下さい。

#### 3:AWS access/secret key(Optional)

[Config](Configure)を参照して下さい。

### AWS

ドキュメント中の{{appName}},{{staging}}は、sbtで設定した値を使用して下さい。<br />
また、このドキュメントは、デフォルトでpluginを使う場合の設定です。sbtで設定変更可能です。

#### ECR(EC2 Code Registry)

DockerImageのPush先です。<br />
レポジトリ名:{{appName}}を作成して下さい。<br />
関連Key: appName in ECR,ecrRepository 

#### S3

EBSのVersionLabel用のファイルを置くバケットです。<br />
バケット名:app-version-{{appName}}を作成して下さい。<br />
関連Key: appName in EBS,ebsZipBucketName in GTEDeploy

#### EBS

アプリケーション:{{appName}}<br />
環境:{{staging}}-{{appName}}<br />
を作成して下さい。環境の方は、シングルDockerContainerを選択して下さい。<br />
関連Key: appName in EBS, staging, envName

#### IAM Role

作成したEBSが、ECR,S3へのアクセス権を持っていることを確認してください。
また、AccessKeyを設定していない場合は、ビルドマシンにEBS,ECR,S3へのアクセス権を付与して下さい。

## build.sbtの設定

```
appName := {{your app name}}

awsRegion in ECR := "us-east-1" // 現在は一部のRegionでのみ使用可能です。
```

の追加だけで動作します。

```
appName := name
```
とすることで、プロジェクトの名前をそのまま利用可能です。


## デプロイ方法


```
sbt gdep:doctor
```
で環境が整っている事を確認できます。

```
sbt gdep-publish
# stagingを指定してデプロイする場合は引数を渡す
sbt gdep-publish production
```
でデプロイ可能です。


## Reference

* [Keys](doc/Keys.md)
* [Config](doc/Config.md)
