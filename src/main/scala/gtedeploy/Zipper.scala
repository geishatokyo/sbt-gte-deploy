package gtedeploy

import java.io.{FileInputStream, ByteArrayOutputStream, File}
import java.util.zip.{ZipEntry, ZipOutputStream}

/**
  * Created by takezoux2 on 2016/05/20.
  */
trait Zipper {

  trait ZipElem

  case class ZipBinary(entryName: String,data: Array[Byte]) extends ZipElem
  case class ZipFile(entryName: String,file: File) extends ZipElem

  object ZipFile{
    def apply(parent: File,file: File) : ZipElem = {
      ZipFile(parent.toURI.relativize(file.toURI).toString,file)
    }
  }

  def zip(zipElems: ZipElem*) : Array[Byte] = {
    val bao = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bao)

    lazy val buffer = new Array[Byte](1024)

    zipElems.foreach(_ match{
      case ZipBinary(entryName,data) => {
        zos.putNextEntry(new ZipEntry(entryName))
        zos.write(data)
        zos.closeEntry()
      }
      case ZipFile(entryName,file) => {
        zos.putNextEntry(new ZipEntry(entryName))

        val fio = new FileInputStream(file)
        val buf = buffer
        var readSize = fio.read(buf)
        while( readSize > 0){
          zos.write(buf,0,readSize)
          readSize = fio.read(buf)
        }
        fio.close()

        zos.closeEntry()
      }
    })

    zos.finish()
    zos.flush()
    zos.close()

    bao.close()

    bao.toByteArray


  }

}
