package gtedeploy

/**
  * Created by takezoux2 on 2016/06/03.
  */
sealed trait DoctorResult {

}

object DoctorResult{

  /**
    * Everything ok.
    */
  case object Ok extends DoctorResult

  /**
    * There are warnings you should fix.
    */
  case object Warning extends DoctorResult

  /**
    * There are errors you must fix.
    */
  case object Error extends DoctorResult

}
