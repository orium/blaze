package blaze.pipeline.stages.http

import java.nio.ByteBuffer
import blaze.pipeline.{Command => Cmd, PipelineBuilder, TailStage}
import blaze.util.Execution._
import scala.util.{Failure, Success}
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import blaze.http_parser.Http1Parser
import Http1Parser.ASCII

import blaze.http_parser.BaseExceptions.BadRequest
import blaze.pipeline.stages.http.websocket.{WebSocketDecoder, ServerHandshaker}
import java.util.Date

import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame

/**
 * @author Bryce Anderson
 *         Created on 1/11/14
 */

abstract class HttpStage(maxReqBody: Int) extends Http1Parser with TailStage[ByteBuffer] {
  import HttpStage.RouteResult._

  private implicit def ec = directec

  val name = "HttpStage"

  val emptyBuffer = ByteBuffer.allocate(0)

  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private val headers = new ListBuffer[(String, String)]
  
  def handleRequest(method: String, uri: String, headers: Headers, body: ByteBuffer): Future[Response]
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff) =>

        logger.trace{
          buff.mark()
          val sb = new StringBuilder

          while(buff.hasRemaining) sb.append(buff.get().toChar)

          buff.reset()
          s"RequestLoop received buffer $buff. Request:\n${sb.result}"
        }

        try {
          if (!requestLineComplete() && !parseRequestLine(buff)) return requestLoop()
          if (!headersComplete() && !parseHeaders(buff)) return requestLoop()
          // we have enough to start the request
          gatherBody(buff, emptyBuffer).onComplete{
            case Success(b) =>
              val hdrs = headers.result()
              headers.clear()
              runRequest(b, hdrs)
            case Failure(t) => sendOutboundCommand(Cmd.Shutdown)
          }
        }
        catch { case t: Throwable   => sendOutboundCommand(Cmd.Shutdown) }

      case Failure(Cmd.EOF)    => sendOutboundCommand(Cmd.Shutdown)
      case Failure(t)          =>
        stageShutdown()
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  private def resetStage() {
    reset()
    uri = null
    method = null
    minor = -1
    major = -1
    headers.clear()
  }

  private def runRequest(buffer: ByteBuffer, reqHeaders: Headers): Unit = {

    handleRequest(method, uri, reqHeaders, buffer).flatMap {
      case r: HttpResponse => handleHttpResponse(r, reqHeaders)
      case WSResponse(stage) => handleWebSocket(reqHeaders, stage)
    }.onComplete {       // See if we should restart the loop
      case Success(Reload)          => resetStage(); requestLoop()
      case Success(Close)           => sendOutboundCommand(Cmd.Shutdown)
      case Success(Upgrade)         => // NOOP don't need to do anything
      case Failure(t: BadRequest)   => badRequest(t)
      case Failure(t)               => sendOutboundCommand(Cmd.Shutdown)
      case Success(other) =>
        logger.error("Shouldn't get here: " + other)
        sendOutboundCommand(Cmd.Shutdown)
    }
  }

  /** Deal with route responses of standard HTTP form */
  private def handleHttpResponse(resp: HttpResponse, reqHeaders: Headers): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(resp.code)
      .append(' ').append(resp.status).append('\r').append('\n')

    val keepAlive = isKeepAlive(reqHeaders)

    if (!keepAlive) sb.append("Connection: close\r\n")
    else if (minor == 0 && keepAlive) sb.append("Connection: Keep-Alive\r\n")

    renderHeaders(sb, resp.headers, resp.body.remaining())

    val messages = Array(ByteBuffer.wrap(sb.result().getBytes(ASCII)), resp.body)

    channelWrite(messages).map(_ => if (keepAlive) Reload else Close)(directec)
  }

  /** Deal with route response of WebSocket form */
  private def handleWebSocket(reqHeaders: Headers, stage: TailStage[WebSocketFrame]): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    ServerHandshaker.handshakeHeaders(reqHeaders) match {
      case Left((i, msg)) =>
        logger.trace(s"Invalid handshake: $i: $msg")
        sb.append("HTTP/1.1 ").append(i).append(' ').append(msg).append('\r').append('\n')
          .append('\r').append('\n')

        channelWrite(ByteBuffer.wrap(sb.result().getBytes(ASCII))).map(_ => Close)

      case Right(hdrs) =>
        logger.trace("Starting websocket request")
        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
        hdrs.foreach { case (k, v) => sb.append(k).append(": ").append(v).append('\r').append('\n') }
        sb.append('\r').append('\n')

        // write the accept headers and reform the pipeline
        channelWrite(ByteBuffer.wrap(sb.result().getBytes(ASCII))).map{_ =>
          logger.trace("Switching segments.")
          val segment = PipelineBuilder(new WebSocketDecoder(false))
            .cap(stage)
          this.replaceInline(segment)
          Upgrade
        }
    }
  }

  private def badRequest(msg: BadRequest) {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(400)
      .append(' ').append("Bad Request").append('\r').append('\n').append('\r').append('\n')

    channelWrite(ByteBuffer.wrap(sb.result().getBytes(ASCII)))
      .onComplete(_ => sendOutboundCommand(Cmd.Shutdown))
  }

  private def renderHeaders(sb: StringBuilder, headers: Traversable[(String, String)], length: Int) {
    headers.foreach{ case (k, v) =>
      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (!k.equalsIgnoreCase("Transfer-Encoding") && !k.equalsIgnoreCase("Content-Length")) {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v).append('\r').append('\n')
      }
    }
    // Add our length header last
    sb.append(s"Content-Length: ").append(length).append('\r').append('\n')
    sb.append('\r').append('\n')
  }

  private def gatherBody(buffer: ByteBuffer, cumulative: ByteBuffer): Future[ByteBuffer] = {
    if (!contentComplete()) {
      val next = parseContent(buffer)
      if (cumulative.remaining() > next.remaining()) {     // Still room
        cumulative.put(next)
        channelRead().flatMap(gatherBody(_, cumulative))
      }
      else {
        cumulative.flip()
        val n = ByteBuffer.allocate(2*(next.remaining() + cumulative.remaining()))
        n.put(cumulative).put(next)
        channelRead().flatMap(gatherBody(_, n))
      }
    }
    else {
      cumulative.flip()
      Future.successful(cumulative)
    }
  }

  def isKeepAlive(headers: Headers): Boolean = {
    val h = headers.find {
      case ("Connection", _) => true
      case _ => false
    }

    if (h.isDefined) {
      if (h.get._2.equalsIgnoreCase("Keep-Alive")) true
      else if (h.get._2.equalsIgnoreCase("close")) false
      else if (h.get._2.equalsIgnoreCase("Upgrade")) true
      else { logger.info(s"Bad Connection header value: '${h.get._2}'. Closing after request."); false }
    }
    else if (minor == 0) false
    else true
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline at " + new Date())
    shutdownParser()
  }

  def headerComplete(name: String, value: String) = {
    logger.trace(s"Received header '$name: $value'")
    headers += ((name, value))
  }

  def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) {
    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)")
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
  }
}

private object HttpStage {

  object RouteResult extends Enumeration {
    type RouteResult = Value
    val Reload, Close, Upgrade = Value
  }
}