package gtedeploy

import java.io.File

import com.amazonaws.auth.{BasicAWSCredentials, AWSCredentials}
import com.typesafe.config.{Config, ConfigFactory}

/**
  * Created by takezoux2 on 2016/06/03.
  */
object GTEDeployConf{

  def load(file: File) : GTEDeployConf = {

    if(!file.exists()) return emptyConf

    val conf = ConfigFactory.parseFile(file)

    GTEDeployConf(loadAwsAuth(conf))
  }

  private def loadAwsAuth(conf: Config) : Option[AWSCredentials] = {
    if(!conf.hasPath("aws")) return None
    val awsBlock = conf.atKey("aws")
    val accessKey = getOneOf(awsBlock,"accessKey","accessKeyId","access_key_id","access_key")
    val secretAccessKey = getOneOf(awsBlock,"secretAccessKey","secretKey","secret_access_key","secret_key")
    for(ak <- accessKey;
      sk <- secretAccessKey
    ) yield new BasicAWSCredentials(ak,sk)
  }

  def emptyConf = GTEDeployConf(None)

  private def getOneOf(conf: Config,keys: String*) : Option[String] = {
    keys.collectFirst({
      case key if conf.hasPath(key) => conf.getString(key)
    })
  }


}

case class GTEDeployConf(
                        awsAuth : Option[AWSCredentials]

                        ) {

}

