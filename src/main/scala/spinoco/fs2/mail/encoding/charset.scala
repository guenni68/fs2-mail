package spinoco.fs2.mail.encoding

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset._

import fs2.{Pipe, _}
import fs2.util.Effect
import scodec.bits.ByteVector
import spinoco.fs2.mail.interop.{ByteVectorChunk, StringChunk}

import scala.annotation.tailrec

object charset {

  /** converts stream of chars to stream of strings **/
  def stringChunks[F[_]]: Pipe[F, Char, String] = {
    _.chunks.map { ch => StringChunk.asString(ch) }
  }

  /** convert stream of strings to stream of chars **/
  def charStream[F[_]]: Pipe[F, String, Char] = {
    _.flatMap { s => Stream.chunk(StringChunk(s)) }
  }

  def decodeAscii[F[_] : Effect]: Pipe[F, Byte, Char] =
    decode(StandardCharsets.US_ASCII)

  def encodeAscii[F[_] : Effect]: Pipe[F, Char, Byte] =
    encode(StandardCharsets.US_ASCII)

  def decodeUTF8[F[_] : Effect]: Pipe[F, Byte, Char] =
    decode(StandardCharsets.UTF_8)

  def encodeUTF8[F[_] : Effect]: Pipe[F, Char, Byte] =
    encode(StandardCharsets.UTF_8)

  /** decodes bytes given supplied charset into stream of utf8 strings **/
  def decode[F[_]](chs: Charset)(implicit F: Effect[F]): Pipe[F, Byte, Char] = { s =>
    Stream.eval(F.delay(chs.newDecoder())) flatMap { decoder =>

      def go(buff: ByteVector): Pipe[F, Chunk[Byte], Char] = {

        _.uncons1 flatMap {
          case Some((chunk, tail)) =>
            if (chunk.isEmpty) go(buff)(tail)
            else {
              val bs = chunk.toBytes
              val bv = buff ++ ByteVector.view(bs.values, bs.offset, bs.size)
              val bb = bv.toByteBuffer
              val (result, outChunk) = impl.decodeBuff(decoder, bb, last = false)
              result match {
                case CoderResult.OVERFLOW =>
                  Stream.fail(new Throwable("Unexpected Decoding Overflow")) // impossible

                case CoderResult.UNDERFLOW =>
                  // we may have still bytes remaining in input buffer, if so, we have to
                  // store these data and use in next invocation
                  val buff0 = if (bb.remaining() == 0) ByteVector.empty else ByteVector.view(bb)
                  Stream.chunk(outChunk) ++ go(buff0)(tail)

                case other =>
                  Stream.fail(new Throwable(s"Unexpected Result when decodeing: $other"))
              }
            }

          case None =>
            def flush: Stream[F, Char] = {
              Stream.eval(F.delay(impl.decodeFlush(decoder))) flatMap { case (result, out) => result match {
                case CoderResult.OVERFLOW =>
                  Stream.fail(new Throwable("Unexpected Decoding Overflow (flush)")) // impossible

                case CoderResult.UNDERFLOW =>
                  Stream.chunk(out)

                case other =>
                  Stream.fail(new Throwable(s"Unexpected Decoding Result when flushing: $result"))
              }}
            }

            val bb = buff.toByteBuffer
            val (result, outChunk) = impl.decodeBuff(decoder, bb, last = true)
            result match {
              case CoderResult.OVERFLOW =>
                Stream.fail(new Throwable("Unexpected Decoding Overflow (last)")) // impossible

              case CoderResult.UNDERFLOW =>
                Stream.chunk(outChunk) ++ flush

              case other =>
                if (other.isError) Stream.fail(new Throwable(s"Unexpected Result when finalizing decode: $result"))
                else Stream.chunk(outChunk) ++ flush
            }
        }
      }

      (s.chunks through go(ByteVector.empty)).scope
    }
  }

  /**
    * Encodes stream of chars by supplied charset
    *
    * Note that due simplification, this encodes in chunks of string.
    *
    * For certaion encoding (i.e. UTF-16) this may present unnecessary characters to eb emitted (magic bytes, headers)
    *
    */
  def encode[F[_]](chs: Charset)(implicit F: Effect[F]): Pipe[F, Char, Byte] = { s =>
    Stream.eval(F.delay(chs.newEncoder())) flatMap { encoder =>

      def go(buff: String): Pipe[F, Chunk[Char], Byte] = {
        _.uncons1 flatMap {
          case Some((chunk, tail)) =>
            val s = buff + StringChunk.asString(chunk)
            val chb = CharBuffer.wrap(s)
            val (result, outChunk) = impl.encodeBuff(encoder, chb, last = false)
            result match {
              case CoderResult.OVERFLOW =>
                Stream.fail(new Throwable("Unexpected Encoding Overflow")) // impossible

              case CoderResult.UNDERFLOW =>
                // we may have still bytes remaining in input buffer, if so, we have to
                // store these data and use in next invocation
                val buff0 = chb.toString
                Stream.chunk(outChunk) ++ go(buff0)(tail)

              case other =>
                Stream.fail(new Throwable(s"Unexpected Result when decodeing: $other"))
            }

          case None =>
            def flush: Stream[F, Byte] = {
              Stream.eval(F.delay(impl.encodeFlush(encoder))) flatMap { case (result, out) => result match {
                case CoderResult.OVERFLOW =>
                  Stream.fail(new Throwable("Unexpected Encoding Overflow (flush)")) // impossible

                case CoderResult.UNDERFLOW =>
                  Stream.chunk(out)

                case other =>
                  Stream.fail(new Throwable(s"Unexpected Encoding Result when flushing: $result"))
              }}
            }

            val chb = CharBuffer.wrap(buff)
            val (result, outChunk) = impl.encodeBuff(encoder, chb, last = true)
            result match {
              case CoderResult.OVERFLOW =>
                Stream.fail(new Throwable("Unexpected Encoding Overflow (last)")) // impossible

              case CoderResult.UNDERFLOW =>
                Stream.chunk(outChunk) ++ flush

              case other =>
                if (other.isError) Stream.fail(new Throwable(s"Unexpected Result when finalizing encode: $result"))
                else Stream.chunk(outChunk) ++ flush
            }
        }
      }

      (s.chunks through go("")).scope
    }
  }



  object impl {


    /**
      * Decodes data in supplied buffer using the supplied decoder.
      *
      * This will return result of operation and next chunk of chars.
      *
      * The resulting buffer size is estimated from the size of the input data, and
      * chars per byte of the decoder.
      *
      * If the encoding signals that resulting buffer is not enough for the input buffer,
      * then this will increment the size of the buffer up to the time when all supplied data
      * will fit to the output charbuffer
      *
      * @param decoder      Decoder to use
      * @param bytes        Input bytes
      * @return
      */
    def decodeBuff(decoder: CharsetDecoder, bytes : ByteBuffer, last: Boolean): (CoderResult, Chunk[Char]) = {
      @tailrec
      def go(sz: Int): (CoderResult, Chunk[Char]) = {
        val out = CharBuffer.allocate(sz)
        val result = decoder.decode(bytes, out, last) // more input to come
        result match {
          case CoderResult.OVERFLOW => go(sz * 2 + 1)
          case other =>
            out.flip()
            (other, StringChunk(out.toString))
        }
      }

      go((bytes.remaining() * decoder.maxCharsPerByte()).toInt max 10)

    }

    /**
      * When decoder finishes its operation, then this is invoked to output any
      * internal state of the buffer.
      * @param decoder    Decoder to use
      * @return
      */
    def decodeFlush(decoder: CharsetDecoder): (CoderResult,  Chunk[Char]) = {

      @tailrec
      def go(sz: Int): (CoderResult, Chunk[Char]) = {
        val out = CharBuffer.allocate(sz)
        val result = decoder.flush(out)
        result match {
          case CoderResult.OVERFLOW => go(sz * 2 + 1)
          case other =>
            out.flip()
            (other, StringChunk(out.toString))
        }
      }

      go((decoder.maxCharsPerByte() * 10).toInt)
    }


    /**
      * Encodes data in supplied buffer using the supplied encoder.
      *
      * This will return result of the operatin and next cunk of bytes.
      *
      * The resulting buffer size is estimated from the size of the input data, and
      * chars per byte of the decoder.
      *
      * If the encoding signals that resulting buffer is not enough for the input buffer,
      * then this will increment the size of the buffer up to the time when all supplied data
      * will fit to the output charbuffer
      *
      * @param encoder      Encoder to use
      * @param chars        Input characters
      * @param last         Signals if this is last encoding to perform.
      * @return
      */
    def encodeBuff(encoder: CharsetEncoder, chars: CharBuffer, last: Boolean): (CoderResult, Chunk[Byte]) = {
      @tailrec
      def go(sz: Int): (CoderResult, Chunk[Byte]) = {
        val out = ByteBuffer.allocate(sz)
        val result = encoder.encode(chars, out, last)
        result match {
          case CoderResult.OVERFLOW => go(sz * 2 + 1)
          case other =>
            out.flip()
            (other, ByteVectorChunk(ByteVector.view(out)))
        }
      }
      go((encoder.maxBytesPerChar() * chars.remaining()).toInt)
    }


    /**
      * When encoder finishes its operation, then this is invoked to output any
      * internal state of the buffer.
      * @param encoder    Encoder to use
      * @return
      */
    def encodeFlush(encoder: CharsetEncoder): (CoderResult,  Chunk[Byte]) = {

      @tailrec
      def go(sz: Int): (CoderResult, Chunk[Byte]) = {
        val out = ByteBuffer.allocate(sz)
        val result = encoder.flush(out)
        result match {
          case CoderResult.OVERFLOW => go(sz * 2 + 1)
          case other =>
            out.flip()
            (other, ByteVectorChunk(ByteVector.view(out)))
        }
      }

      go((encoder.maxBytesPerChar() * 10).toInt)
    }

  }



}
