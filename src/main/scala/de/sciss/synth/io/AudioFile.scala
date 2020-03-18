/*
 *  AudioFile.scala
 *  (AudioFile)
 *
 *  Copyright (c) 2004-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.io

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, File, FileInputStream, IOException, InputStream, OutputStream, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.{Channels, Channel => NIOChannel}

import de.sciss.synth.io.AudioFileHeader.opNotSupported

import scala.math.{max, min}

/** The <code>AudioFile</code> allows reading and writing
  * of sound files. It can operate both on a <code>RandomAccessFile</code>
  * created from a <code>File</code> instance, or on
  * an kind of <code>InputStream</code> (not every codec will
  * support this though, and functionality might be limited, for example
  * seeking is not possible with a plain <code>InputStream</code>).
  *
  * The codecs are registered with <code>AudioFileType</code>.
  * The codecs that come with AudioFile are found in the <code>impl</code>
  * package.
  *
  * Reading and writing data requires a user-buffer which holds de-interleaved
  * floating point data, that is a two dimensional <code>Array</code> which
  * holds <code>Float</code> data. A type alias <code>Frames</code> is provided
  * for this, and two helper methods <code>buffer</code>: one static to
  * construct an arbitrary user-buffer, one in class <code>AudioFile</code>
  * which creates a buffer with the appropriate channel number.
  *
  * @see    AudioFileType
  *
  * @todo   the copyTo method uses a user-buffer. it should
  *         check for the possibility to directly transfer data
  *         if input and output are compatible.
  */
object AudioFile extends ReaderFactory {
  /** Opens an audio file for reading.
    *
    * @param  f  the path name of the file
    * @return a new <code>AudioFile</code> object
    *         whose header is already parsed and can
    *         be obtained through the <code>spec</code> method.
    *
    * @throws java.io.IOException if the file was not found, could not be reader
    *                     or has an unknown or unsupported format
    */
  @throws(classOf[IOException])
  def openRead(f: File): AudioFile = {
    val raf   = new RandomAccessFile(f, "r")
    val dis   = dataInput(Channels.newInputStream(raf.getChannel))
    val hr    = createHeaderReader(dis)
    finishOpenFileRead(f, raf, hr)
  }

  private[io] def openFileWithReader(f: File, reader: AudioFileType.CanRead): AudioFile = {
    val raf = new RandomAccessFile(f, "r")
    finishOpenFileRead(f, raf, reader)
  }

  private def finishOpenFileRead(f: File, raf: RandomAccessFile, hr: AudioFileType.CanRead): AudioFile = {
    raf.seek(0L) // BufferedInputStream did advance the position!
    val afh   = hr.read(raf)
    val buf   = createBuffer(afh)
    val spec  = afh.spec
    val sf    = spec.sampleFormat
    val br    = sf.readerFactory.map(_.apply(raf.getChannel, buf, spec.numChannels))
      .getOrElse(noDecoder(sf))
    new ReadableFileImpl(f, raf, afh, br)
  }

  @throws(classOf[IOException])
  def openRead(is: InputStream): AudioFile = {
    val dis   = dataInput(is)
    val hr    = createHeaderReader(dis)
    finishOpenStreamRead(dis, hr)
  }

  private[io] def openStreamWithReader(is: InputStream, reader: AudioFileType.CanRead): AudioFile = {
    val dis = dataInput(is)
    finishOpenStreamRead(dis, reader)
  }

  private def finishOpenStreamRead(dis: DataInputStream, hr: AudioFileType.CanRead): AudioFile = {
    val afh   = hr.read(dis)
    val buf   = createBuffer(afh)
    val spec  = afh.spec
    val sf    = spec.sampleFormat
    val br    = sf.readerFactory.map(_.apply(Channels.newChannel(dis), buf, spec.numChannels))
      .getOrElse(noDecoder(sf))
    new ReadableStreamImpl(dis, afh, br)
  }

  @throws(classOf[IOException])
  private def createHeaderReader(dis: DataInputStream): AudioFileType.CanRead = {
    val fileType = identify(dis).getOrElse(throw new IOException("Unrecognized audio file format"))
    fileType match {
      case cr: AudioFileType.CanRead  => cr
      case _                          => noDecoder(fileType)
    }
  }

  private final val useDirect = sys.props.getOrElse("de.sciss.synth.io.AudioFile.DirectMemory", "false").toBoolean

  private def createBuffer(afh: AudioFileHeader): ByteBuffer = {
    val spec      = afh.spec
    val frameSize = (spec.sampleFormat.bitsPerSample >> 3) * spec.numChannels
    val bufFrames = max(1, 65536 / max(1, frameSize))
    val bufSize   = bufFrames * frameSize
    val byteBuf   = if (useDirect) ByteBuffer.allocateDirect(bufSize) else ByteBuffer.allocate(bufSize)
    byteBuf.order(afh.byteOrder)
  }

  private def dataInput (is: InputStream ) = new DataInputStream (new BufferedInputStream (is, 1024))
  private def dataOutput(os: OutputStream) = new DataOutputStream(new BufferedOutputStream(os, 1024))

  private def noDecoder(msg: AnyRef) = throw new IOException(s"No decoder for $msg")
  private def noEncoder(msg: AnyRef) = throw new IOException(s"No encoder for $msg")

  @throws(classOf[IOException])
  def openWrite(path: String, spec: AudioFileSpec): AudioFile = openWrite(new File(path), spec)

  /** Opens an audio file for reading/writing. The pathname
    * is determined by the <code>file</code> field of the provided <code>AudioFileInfo</code>.
    * If a file denoted by this path already exists, it will be
    * deleted before opening.
    * <p>
    * Note that the initial audio file header is written immediately.
    * Special tags for the header thus need to be set in the <code>AudioFileInfo</code>
    * before calling this method, including markers and regions. It is not
    * possible to writer markers and regions after the file has been opened
    * (since the header size has to be constant).
    *
    * @param  f  the path name of the file.
    * @param  spec   format and resolution of the new audio file.
    *                the header is immediately written to the hard-disc
    *
    * @throws java.io.IOException if the file could not be created or the
    *                     format is unsupported
    */
  @throws(classOf[IOException])
  def openWrite(f: File, spec: AudioFileSpec): AudioFile = {
    val hw  = createHeaderWriter(spec)
    if (f.exists) f.delete()
    val raf = new RandomAccessFile(f, "rw")
    val afh = hw.write(raf, spec)
    val buf = createBuffer(afh)
    val sf  = spec.sampleFormat
    val ch  = raf.getChannel
    sf.bidiFactory match {
      case Some(bbf) =>
        val bb = bbf(ch, ch, buf, spec.numChannels)
        new BidiFileImpl(f, raf, afh, bb)
      case None =>
        val bw = sf.writerFactory.map(_.apply(ch, buf, spec.numChannels)).getOrElse(noEncoder(sf))
        new WritableFileImpl(f, raf, afh, bw)
    }
  }

  @throws(classOf[IOException])
  def openWrite(os: OutputStream, spec: AudioFileSpec): AudioFile = {
    val hw  = createHeaderWriter(spec)
    val dos = dataOutput(os)
    val afh = hw.write(dos, spec)
    val buf = createBuffer(afh)
    val sf  = spec.sampleFormat
    val bw  = sf.writerFactory.map(_.apply(Channels.newChannel(dos), buf, spec.numChannels))
      .getOrElse(noEncoder(sf))
    new WritableStreamImpl(dos, afh, bw)
  }

  private def createHeaderWriter(spec: AudioFileSpec): AudioFileType.CanWrite =
    spec.fileType match {
      case cw: AudioFileType.CanWrite => cw
      case other                      => noEncoder(other)
    }

  def buffer(numChannels: Int, bufFrames: Int = 8192): Frames =
    Array.ofDim[Float](numChannels, bufFrames)

  def readSpec(path: String): AudioFileSpec = readSpec(new File(path))

  def readSpec(f: File): AudioFileSpec = {
    val raf = new RandomAccessFile(f, "r")
    try {
      val dis   = dataInput(Channels.newInputStream(raf.getChannel))
      val hr    = createHeaderReader(dis)
      raf.seek(0L) // BufferedInputStream did advance the position!
      hr.read(raf).spec
    } finally {
      raf.close()
    }
  }

  /** Note that this method advances in
    * the provided input stream, its
    * previous position is not reset.
    */
  @throws(classOf[IOException])
  def readSpec(dis: DataInputStream): AudioFileSpec = {
    val hr = createHeaderReader(dis)
    hr.read(dis).spec
  }

  @throws(classOf[IOException])
  def identify(path: String): Option[AudioFileType] = identify(new File(path))

  /** Determines the type of audio file.
    *
    * @param		f   the pathname of the file
    * @return		the type code as defined in <code>AudioFileInfo</code>,
    *            e.g. <code>TYPE_AIFF</code>. Returns <code>TYPE_UNKNOWN</code>
    *            if the file could not be identified.
    *
    * @throws java.io.IOException if the file could not be reader
    */
  @throws(classOf[IOException])
  def identify(f: File): Option[AudioFileType.CanIdentify] = {
    val dis = dataInput(new FileInputStream(f))
    try {
      identify(dis)
    } finally {
      dis.close()
    }
  }

  @throws(classOf[IOException])
  def identify(dis: DataInputStream): Option[AudioFileType.CanIdentify] =
    AudioFileType.known.find { f =>
      dis.mark(1024)
      try {
        f.identify(dis)
      } catch {
        case _: IOException => false
      } finally {
        dis.reset()
      }
    }

  private trait Basic extends AudioFile {
    protected final var framePositionVar: Long = 0L

    protected def afh: AudioFileHeader
    protected def bh : BufferHandler

    final def position: Long = framePositionVar

    def spec: AudioFileSpec = afh.spec

    @throws(classOf[IOException])
    final def copyTo(target: AudioFile, len: Long): AudioFile = {
      val tempBufSize = min(len, 8192).toInt
      val tempBuf     = Array.ofDim[Float](spec.numChannels, tempBufSize)
      var remaining   = len

      while (remaining > 0) {
        val chunkLen = min(remaining, tempBufSize).toInt
        read(tempBuf, 0, chunkLen)
        target.write(tempBuf, 0, chunkLen)
        remaining -= chunkLen
      }
      this
    }

    override def toString: String = {
      val s           = spec.toString
      val specString  = s.substring(14)
      s"AudioFile@$accessString($sourceString,$specString)"
    }

    protected def accessString: String
    protected def sourceString: String

    final def cleanUp(): Unit =
      try {
        close()
      } catch {
        case _: IOException =>
      }
  }

  private trait Readable extends Basic {
    final def isReadable = true

    protected def bh: BufferReader

    override def numFrames: Long = spec.numFrames

    @throws(classOf[IOException])
    final def read(data: Frames, off: Int, len: Int): AudioFile = {
      bh.read(data, off, len)
      framePositionVar += len
      this
    }
  }

  private trait ReadOnly extends Readable {
    final def isWritable = false

    @throws(classOf[IOException])
    final def flush(): AudioFile = opNotSupported

    @throws(classOf[IOException])
    final def write(data: Frames, off: Int, len: Int): AudioFile = opNotSupported

    protected final def accessString = "r"
  }

  private trait Writable extends Basic {
    final def isWritable = true

    protected def bh : BufferWriter
    protected def afh: WritableAudioFileHeader

    protected final var numFramesVar: Long = 0L

    override /*final*/ def numFrames: Long = numFramesVar

    override final def spec: AudioFileSpec = afh.spec.copy(numFrames = numFramesVar)

    @throws(classOf[IOException])
    final def write(data: Frames, off: Int, len: Int): AudioFile = {
      bh.write(data, off, len)
      framePositionVar += len
      if (framePositionVar > numFramesVar) numFramesVar = framePositionVar
      this
    }

    @throws(classOf[IOException])
    final def flush(): AudioFile = {
      afh.update(numFrames)
      this
    }
  }

  private trait Bidi extends Readable with Writable {
    override protected def bh: BufferBidi

    protected final def accessString = "rw"
  }

  private trait WriteOnly extends Writable {
    final def isReadable = false

    protected final def accessString = "w"

    @throws(classOf[IOException])
    final def read(data: Frames, off: Int, len: Int): AudioFile = opNotSupported
  }

  private trait StreamLike extends Basic {
    final def file: Option[File] = None

    @throws(classOf[IOException])
    final def seek(frame: Long): AudioFile = opNotSupported

    protected final def sourceString = "<stream>"
  }

  private trait FileLike extends Basic {
    protected def f  : File
    protected def raf: RandomAccessFile

    final def file: Option[File] = Some(f)

    private val sampleDataOffset = raf.getFilePointer

    protected final def sourceString: String = f.toString

    @throws(classOf[IOException])
    final def seek(frame: Long): AudioFile = {
      val physical = sampleDataOffset + frame * bh.frameSize
      raf.seek(physical)
      framePositionVar = frame
      this
    }

    final def isOpen: Boolean = raf.getChannel.isOpen
  }

  private trait ReadOnlyFileLike extends FileLike with ReadOnly {
    @throws(classOf[IOException])
    final def close(): Unit = raf.close()
  }

  private trait WritableFileLike extends FileLike with Writable {
    @throws(classOf[IOException])
    final def close(): Unit =
      try {
        flush()
      } finally {
        raf.close()
      }
  }

   private trait WriteOnlyFileLike extends WritableFileLike with WriteOnly
   private trait BidiFileLike      extends WritableFileLike with Bidi

  private trait ReadOnlyStreamLike extends StreamLike with ReadOnly {
    protected def dis: DataInputStream

    private var closed: Boolean = false

    @throws(classOf[IOException])
    final def close(): Unit = {
      closed = true
      dis.close()
    }

    final def isOpen: Boolean = closed
  }

  private trait WriteOnlyStreamLike extends StreamLike with WriteOnly {
    protected def dos: DataOutputStream

    private var closed: Boolean = false

    @throws(classOf[IOException])
    final def close(): Unit = {
      closed = true
      try {
        flush()
      } finally {
        dos.close()
      }
    }

    final def isOpen: Boolean = closed
  }

  private final class ReadableStreamImpl(protected val dis: DataInputStream, protected val afh: AudioFileHeader,
                                         protected val bh: BufferReader)
    extends ReadOnlyStreamLike

  private final class ReadableFileImpl(protected val f: File,
                                       protected val raf: RandomAccessFile, protected val afh: AudioFileHeader,
                                       protected val bh: BufferReader)
    extends ReadOnlyFileLike

  private final class WritableFileImpl(protected val f: File,
                                       protected val raf: RandomAccessFile, protected val afh: WritableAudioFileHeader,
                                       protected val bh: BufferWriter)
    extends WriteOnlyFileLike

  private final class WritableStreamImpl(protected val dos: DataOutputStream, protected val afh: WritableAudioFileHeader,
                                         protected val bh: BufferWriter)
    extends WriteOnlyStreamLike

  private final class BidiFileImpl(protected val f: File,
                                   protected val raf: RandomAccessFile, protected val afh: WritableAudioFileHeader,
                                   protected val bh: BufferBidi)
    extends BidiFileLike
}

trait AudioFile extends NIOChannel {
  //-------- public methods --------

  /** Returns a description of the audio file's specification. */
  def spec: AudioFileSpec

  /** Returns the underlying <code>File</code> if it was
    * provided to the <code>AudioFile</code> constructor.
    * For an <code>AudioFile</code> created from an <code>InputStream</code>
    * this will return <code>None</code>.
    */
  def file: Option[File]

  def isReadable: Boolean
  def isWritable: Boolean

  /** Reads sample frames from the current position
    *
    * @param  data	buffer to hold the frames reader from hard-disc.
    *               the samples will be de-interleaved such that
    *               data[0][] holds the first channel, data[1][]
    *               holds the second channel etc.
    *               ; it is allowed to have null arrays in the data
    *               (e.g. data[0] == null), in which case these channels
    *               are skipped when reading
    * @param  off  off in the buffer in sample frames, such
    *              that he first frame of the first channel will
    *              be placed in data[0][off] etc.
    * @param  len  number of continuous frames to reader.
    *
    * @throws java.io.IOException if a reader error or end-of-file occurs.
    */
  @throws(classOf[IOException])
  def read(data: Frames, off: Int, len: Int): AudioFile

  @throws(classOf[IOException])
  final def read(data: Frames): AudioFile = {
    var ch  = 0
    var num = 0
    while (ch < data.length) {
      val cd = data(ch)
      if (cd != null) {
        num = cd.length
        ch  = data.length
      } else {
        ch += 1
      }
    }
    read(data, 0, num)
  }

  def buffer(bufFrames: Int = 8192): Frames =
    AudioFile.buffer(numChannels, bufFrames)

  /** Moves the file pointer to a specific
    * frame.
    *
    * @param  frame the sample frame which should be
    *               the new file position. this is really
    *               the sample index and not the physical file pointer.
    * @throws java.io.IOException when a seek error occurs or you try to
    *                     seek past the file's end.
    */
  @throws(classOf[IOException])
  def seek(frame: Long): AudioFile

  /** Flushes pending buffer content, and
    * updates the sound file header information
    * (i.e. numFrames fields). Usually you
    * will not have to call this method directly,
    * unless you pause writing for some time
    * and want the file information to appear
    * as accurate as possible.
    */
  def flush(): AudioFile

  /** Returns the current file pointer in sample frames
    *
    * @return		the sample frame index which is the off
    *            for the next reader or writer operation.
    *
    * @throws java.io.IOException		when the position cannot be queried
    */
  def position: Long

  @throws(classOf[IOException])
  final def position_=(frame: Long): Unit = seek(frame)

  /** Writes sample frames to the file starting at the current position.
    *
    * @param  data	buffer holding the frames to writer to hard-disc.
    *               the samples must be de-interleaved such that
    *               data[0][] holds the first channel, data[1][]
    *               holds the second channel etc.
    * @param  off  off in the buffer in sample frames, such
    *              that he first frame of the first channel will
    *              be reader from data[0][off] etc.
    * @param  len  number of continuous frames to writer.
    *
    * @throws java.io.IOException if a writer error occurs.
    */
  @throws(classOf[IOException])
  def write(data: Frames, off: Int, len: Int): AudioFile

  @throws(classOf[IOException])
  final def write(data: Frames): AudioFile = {
    var ch  = 0
    var num = 0
    while (ch < data.length) {
      val cd = data(ch)
      if (cd != null) {
        num = cd.length
        ch  = data.length
      } else {
        ch += 1
      }
    }
    write(data, 0, num)
  }

  /** Returns the number of frames
    * in the file.
    *
    * @return	the number of sample frames
    *          in the file. includes pending
    *          buffer content
    *
    * @throws	java.io.IOException	this is never thrown
    *                       but declared as of the <code>InterleavedStreamFile</code>
    *                       interface
    */
  def numFrames: Long

  /** Convenience method: Returns the number of channels
    * in the file. Same as <code>spec.numChannels</code>.
    *
    * @return	the number of channels
    */
  final def numChannels: Int            = spec.numChannels

  final def sampleRate : Double         = spec.sampleRate

  final def sampleFormat: SampleFormat  = spec.sampleFormat

  final def fileType    : AudioFileType = spec.fileType

  /** Copies sample frames from a source sound file
    * to a target file (either another sound file
    * or any other class implementing the
    * <code>InterleavedStreamFile</code> interface).
    * Both files must have the same number of channels.
    *
    * @param	target	to file to copy to from this audio file
    * @param	numFrames	the number of frames to copy. Reading
    *                    and writing begins at the current positions
    *                    of both files.
    *
    * @throws	java.io.IOException	if a read or writer error occurs
    */
  @throws(classOf[IOException])
  def copyTo(target: AudioFile, numFrames: Long): AudioFile

  /** Flushes and closes the file
    *
    * @throws java.io.IOException if an error occurs during buffer flush
    *                     or closing the file.
    */
  @throws(classOf[IOException])
  def close(): Unit

  /** Flushes and closes the file. As opposed
    * to <code>close()</code>, this does not
    * throw any exceptions but simply ignores any errors.
    *
    * @see	#close()
    */
  def cleanUp(): Unit
}