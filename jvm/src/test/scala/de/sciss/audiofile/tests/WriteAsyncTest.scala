package de.sciss.audiofile.tests

import java.io.File
import java.util.Locale

import de.sciss.audiofile.AudioFile

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object WriteAsyncTest {
  def main(args: Array[String]): Unit = {
    val directMemory = args.headOption.contains("--direct")
    sys.props.put(AudioFile.KEY_DIRECT_MEMORY, directMemory.toString)
    println(s"direct-memory = $directMemory")
    Locale.setDefault(Locale.US)
    val pathIn  = "/data/projects/Anemone/rec/anemone_akademie_graz_181128.aif" // c. 1.6 GB file
    val pathOut = "/data/temp/_killme.aif"
    val fileIn  = new File(pathIn )
    val fileOut = new File(pathOut)

    implicit val ec: ExecutionContext = ExecutionContext.global

    val fut = Future(()).flatMap { _ =>
      runAsync(fileIn = fileIn, fileOut = fileOut)
    }
    val res = Await.result(fut, Duration.Inf)
    println(s"result = $res")

    sys.exit()
  }

  def runAsync(fileIn: File, fileOut: File)(implicit ec: ExecutionContext): Future[Unit] = {
    println("Reading file asynchronously...")
    val t1 = System.currentTimeMillis()
    for {
      afIn  <- AudioFile.openReadAsync  (fileIn .toURI)
      afOut <- AudioFile.openWriteAsync (fileOut.toURI, afIn.spec)
      _     <- {
        val N     = afIn.numFrames
        println(s"numFrames = $N")
        val bsz   = 8192
        val buf   = afIn.buffer(bsz)

        def loop(off: Long): Future[Unit] =
          if (off >= N) Future.successful(())
          else {
            val len = math.min(bsz, N - off).toInt
//            println(s"off = $off, len = $len, thread = ${Thread.currentThread().hashCode().toHexString}")
            for {
              _ <- afIn .read (buf, 0, len)
              _ <- afOut.write(buf, 0, len)
              _ <- loop(off + len)
            } yield ()
          }

        loop(0L)
      }
      _ <- afOut.flush().andThen { case tr =>
        println(s"Closing (success? ${tr.isSuccess})")
        afIn  .close()
        afOut .close()
      }

    } yield {
      val t2 = System.currentTimeMillis()
      println(f"Copied file, took ${(t2 - t1).toDouble / 1000}%1.1f s.")
      ()
    }
  }
}
