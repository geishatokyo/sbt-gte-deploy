# GeishaTokyo's deploy sbt plugin

# これは何？

芸者東京で使っているデプロイプラグインです。
PlayFrameworkをDockerImage化し、AWS ECRにPushし、AWS EBSへデプロイを行います。

# 事前準備

## ビルドマシン

### 1:Docker

ローカルでdockerコマンドを使える状態にして下さい。

### 2:AWS access/secret key(Optional)

~/.aws/credentials

```
[default]
aws_access_key_id = {{Your access key}}
aws_secret_access_key = {{Your secret access key}}
```

を作成して下さい。
※ここで設定しない場合は、build.sbtで設定する必要があります。

## AWS

(開発中)
```gdep:doctor``` に足りていない設定が出力されます。
ドキュメント中の{{appName}},{{staging}}は、sbtで設定した値を使用して下さい。
また、このドキュメントは、デフォルトでpluginを使う場合の設定です。sbtで設定変更可能です。

### ECR(EC2 Code Registry)

DockerImageのPush先です。
レポジトリ名:{{appName}}を作成して下さい。
関連Key: appName in ECR,ecrRepository 

### S3

EBSのVersionLabel用のファイルを置くバケットです。
バケット名:app-version-{{appName}}を作成して下さい。
関連Key: appName in EBS,ebsZipBucketName in GTEDeploy

### EBS

アプリケーション:{{appName}}
環境:{{staging}}-{{appName}}
を作成して下さい。環境の方は、シングルDockerContainerを選択して下さい。
関連Key: appName in EBS, staging, envName

### IAM Role

作成したEBSが、ECR,S3へのアクセス権を持っていることを確認してください。

# build.sbtの設定

```
appName := {{your app name}}

awsRegion in ECR := "us-east-1" // 現在は一部のRegionでのみ使用可能です。
```

の追加だけで動作します。

```
appName := name
```
とすることで、プロジェクトの名前をそのまま利用可能です。


# デプロイ方法

```
sbt gdep-publish
# stagingを指定してデプロイする場合は引数を渡す
sbt gdep-publish production
```
でデプロイ可能です。


# Reference

Keys

