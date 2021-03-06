package spinoco.fs2.mail.encoding

import fs2._
import scodec.bits.ByteVector
import spinoco.fs2.mail.interop.ByteVectorChunk

object lines {

  /** decodes bytes in chunk of bytes by supplied separator. Last line is emitted even when not terminated by `separator` **/
  def by[F[_]](separator: ByteVector): Pipe[F, Byte, Chunk[Byte]] = {
    def go(buff: ByteVector): Pipe[F, Byte, Chunk[Byte]] = {
      _.uncons.flatMap {
        case Some((ch, t)) =>
          val data = buff ++ ByteVectorChunk.asByteVector(ch)
          val idx = data.indexOfSlice(separator)
          if (idx < 0) go(data)(t)
          else {
            val (h, t0) = data.splitAt(idx)
            if (t0.isEmpty) Stream.emit(ByteVectorChunk(h)) ++ go(ByteVector.empty)(t)
            else Stream.emit(ByteVectorChunk(h)) ++ go(ByteVector.empty)(Stream.chunk(ByteVectorChunk(t0.drop(separator.size))) ++ t)
          }

        case None => Stream.emit(ByteVectorChunk(buff))
      }
    }
    go(ByteVector.empty)(_).scope
  }

  /** decodes bytes to chunks according to supplied separator **/
  def byCrLf[F[_]]: Pipe[F, Byte, Chunk[Byte]] =
    by(ByteVector.view("\r\n".getBytes))

  private val crlf = ByteVector.view("\r\n".getBytes)

  /**
    * Splits bytes by lines so the first character is prepended by given `prefix` number of spaces and
    * up to `length` of  characters are included on every line.
    *
    * Note that if there are any crlf characters , then the line will be terminated as expected.
    *
    * @param prefix   Prefix size of spaces
    * @param length   max number of bytes to include onsingle line, excluding prefix.
    * @tparam F
    * @return
    */
  def blockLines[F[_]](prefix: Int = 2, length: Int = 73): Pipe[F, Byte, Byte] = {
    val prefixBytes = ByteVector.view((" " * prefix).getBytes)
    def go(buff: ByteVector): Handle[F, Byte] => Pull[F, Byte, Unit] = {
      _.receiveOption {
        case Some((ch, h)) =>
          val bv = buff ++ ByteVectorChunk.asByteVector(ch)
          val idx = bv.indexOfSlice(crlf)
          if (idx < 0) {
            val head = bv.take(length)
            if (head.size < length) go(bv)(h)
            else
              Pull.output(ByteVectorChunk(prefixBytes ++ head ++ crlf)) >>
              go(ByteVector.empty)(h.push(ByteVectorChunk(bv.drop(length))))

          } else {
            val (head, t) = bv.splitAt(idx)
            Pull.output(ByteVectorChunk(prefixBytes ++ head ++ crlf)) >>
            go(ByteVector.empty)(h.push(ByteVectorChunk(t.drop(crlf.size))))
          }

        case None =>
          if (buff.isEmpty) Pull.done
          else Pull.output(ByteVectorChunk(prefixBytes ++ buff ++ crlf))
      }
    }
    _.pull(go(ByteVector.empty))
  }

}
