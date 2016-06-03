package gtedeploy

import java.io.File
import java.util.Base64

import com.amazonaws.regions.{Regions, Region}
import com.amazonaws.services.ecr.{AmazonECR, AmazonECRClient}
import com.amazonaws.services.ecr.model.GetAuthorizationTokenRequest
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.s3.AmazonS3Client

/**
  * Created by takezoux2 on 2016/05/19.
  */
trait AWSFunctions {

  def toRegion(region: String) = {
    Region.getRegion(Regions.fromName(region))
  }

  def useEcrClient[T](conf: GTEDeployConf,region: String)( scope: AmazonECR => T) : T = {

    val client = conf.awsAuth.map(cr => new AmazonECRClient(cr)).getOrElse(new AmazonECRClient())
    client.setRegion(toRegion(region))

    try{
      scope(client)
    }finally{
      client.shutdown()
    }
  }


  def getDockerLoginInfo()(implicit client: AmazonECR) = {
    val res = client.getAuthorizationToken(new GetAuthorizationTokenRequest())
    val data = res.getAuthorizationData().get(0)

    data.getAuthorizationToken

    val Array(username,password) = new String(Base64.getDecoder.decode(data.getAuthorizationToken),"utf-8").split(":")


    (username,password,data.getProxyEndpoint)
  }



  def useEBSClient[T](conf: GTEDeployConf,region: String)(func: AWSElasticBeanstalkClient => T) : T = {

    val client = conf.awsAuth.map(cr => new AWSElasticBeanstalkClient(cr)).getOrElse(new AWSElasticBeanstalkClient())
    client.setRegion(toRegion(region))

    try{
      func(client)
    }finally{
      client.shutdown()
    }
  }
  object ebs {
    def createAppVersion(appName: String, versionLabel: String, desc: String, s3Bucket: String, s3Key: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.createApplicationVersion(new CreateApplicationVersionRequest().
        withApplicationName(appName).
        withDescription(desc).
        withVersionLabel(versionLabel).
        withSourceBundle(new S3Location(s3Bucket, s3Key)))

      res.getApplicationVersion().getVersionLabel
    }
    def deleteVersionLabel(appName: String,versionLabel: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.deleteApplicationVersion(new DeleteApplicationVersionRequest().
        withApplicationName(appName).
        withVersionLabel(versionLabel))
      true
    }

    def existsAppVersion(appName: String,versionLabel: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.describeApplicationVersions(new DescribeApplicationVersionsRequest().
        withApplicationName(appName).
        withVersionLabels(versionLabel))

      res.getApplicationVersions.size() > 0
    }

    def updateEnvironment(appName: String, envName: String, versionLabel: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.updateEnvironment(new UpdateEnvironmentRequest().
        withApplicationName(appName).
        withEnvironmentName(envName).
        withVersionLabel(versionLabel))
    }


    def getHelth(appName: String, envName: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.describeEnvironmentHealth(new DescribeEnvironmentHealthRequest().
        withEnvironmentName(envName).
        withAttributeNames("All"))


      (res.getHealthStatus, res.getColor)

    }

    def describeApplication(appName: String)(implicit cli: AWSElasticBeanstalkClient) = {
      val res = cli.describeApplications(
        new DescribeApplicationsRequest().
          withApplicationNames(appName))

      if(res.getApplications.size() > 0){
        Some(res.getApplications.get(0))
      }else{
        None
      }
    }

    def describeEnvironment(appName : String,envName: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.describeEnvironments(
        new DescribeEnvironmentsRequest().
          withApplicationName(appName).
          withEnvironmentNames(envName))

      if(res.getEnvironments.size > 0) {
        val env = res.getEnvironments.get(0)
        Some(env)
      }else{
        None
      }

    }

    def describeAppVersion(appName: String,appVersion: String)(implicit client: AWSElasticBeanstalkClient) = {
      val res = client.describeApplicationVersions(
        new DescribeApplicationVersionsRequest().
          withApplicationName(appName).
          withVersionLabels(appVersion)
      )

      if(res.getApplicationVersions.size() > 0){
        Some(res.getApplicationVersions.get(0))
      }else{
        None
      }
    }

  }


  def useS3Client[T](conf: GTEDeployConf,region: String)(func: AmazonS3Client => T) : T = {
    val client =  conf.awsAuth.map(cr => new AmazonS3Client(cr)).getOrElse(new AmazonS3Client())
    client.setRegion(toRegion(region))

    try{
      func(client)
    }finally{
      client.shutdown()
    }
  }

  def existsBucket(bucket: String)(implicit amazonS3Client: AmazonS3Client) = {
    val res = amazonS3Client.getBucketLocation(bucket)
    res
  }

  def upload(bucket: String,key: String,file: File)(implicit amazonS3Client: AmazonS3Client) = {
    val res = amazonS3Client.putObject(bucket,key,file)
    key
  }

  class EndpointMap(name: String,map: Map[String,String]){
    def apply(region: String) = {
      map.getOrElse(region,{
        throw new RuntimeException(s"${name} doesn't have endpoint in region:${region}")
      })
    }
  }

  object EndpointMap{
    def apply(name: String)(elems: (String,String)*) = new EndpointMap(name,elems.toMap)
  }


}
