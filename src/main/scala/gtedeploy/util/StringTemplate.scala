package gtedeploy.util

/**
  *
  * Simple string template.
  * Replace $${key} to value in template.
  * Created by takezoux2 on 2016/05/20.
  */
object StringTemplate {

  def render(templateStr: String,values: (String,String) *) = {
    values.foldLeft(templateStr)({
      case (str,(key,value)) => {
        str.replaceAll("""(?<!\$)\$\{\s*""" + key + """\s*\}""",value)
      }
    })
  }

}
