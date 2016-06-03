# Configuration



## AWS

### 認証

ECR,EBS,S3へのRead/Write権限をビルドサーバーに与えて下さい。

#### IAM Roleによる設定(推奨)

ビルドマシンがEC2上で動く場合には、IAM Roleによるアクセス権の設定を推奨します。
設定している場合、認証の追加設定は不要になります。


#### gdep.confによる設定

デフォルトでは`deploy/gdep.conf`を読み込みます。

```
aws.access_key_id={{accessKey}}
aws.secret_access_key={{secretKey}}
```
を設定して下さい。

#### ~/.aws/credentialsによる設定

コマンドラインツールのaws cliと設定ファイルを共有します。
~/.aws/credentialsに

```
[default]
aws_access_key_id = {{Your access key}}
aws_secret_access_key = {{Your secret access key}}
```

を設定して下さい。すでにコマンドラインツールが入っている場合は、

`aws configure`

によって設定を行うことで、このファイルが作成されます。












