package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util.HashMap

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{HeadStage, Command => Cmd, LeafBuilder}

import org.log4s.getLogger

import scala.collection.mutable
import scala.concurrent.Future

private class FlowControl[T](http2Stage: Http2StageConcurrentOps[T],
                          inboundWindow: Int,
                          http2Settings: Settings,
                                  codec: Http20FrameDecoder with Http20FrameEncoder,
                          headerEncoder: HeaderEncoder[T]) { self =>

  private type Http2Msg = NodeMsg.Http2Msg[T]

  private val logger = getLogger
  private val nodeMap = new HashMap[Int, Stream]()

  private val oConnectionWindow = new FlowWindow(http2Settings.outbound_initial_window_size)
  private val iConnectionWindow = new FlowWindow(http2Settings.inboundWindow)

  /////////////////////////// Stream management //////////////////////////////////////

  def getNode(streamId: Int): Option[Stream] = Option(nodeMap.get(streamId))

  def nodeCount(): Int = nodeMap.size()

  def removeNode(streamId: Int, reason: Throwable, sendDisconnect: Boolean): Option[Stream] = {
    val node = nodeMap.remove(streamId)
    if (node != null && node.isConnected()) {
      node.closeStream(reason)
      if(sendDisconnect) node.inboundCommand(Cmd.Disconnected)
      Some(node)
    }
    else None
  }

  def nodes(): Seq[Stream] =
    mutable.WrappedArray.make(nodeMap.values().toArray())

  def closeAllNodes(): Unit = nodes().foreach { node =>
    removeNode(node.streamId, Cmd.EOF, true)
  }

  ////////////////////////////////////////////////////////////////////////////////////

  def makeStream(streamId: Int): Stream = {
    val stream = http2Stage.makePipeline(streamId).base(new Stream(streamId))
    nodeMap.put(streamId, stream)
    stream.inboundCommand(Cmd.Connected)
    stream
  }

  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): MaybeError = {
    logger.trace(s"Updated window of stream $streamId by $sizeIncrement. ConnectionOutbound: $oConnectionWindow")

    if (sizeIncrement <= 0) {
      if (streamId == 0) Error(PROTOCOL_ERROR(s"Invalid WINDOW_UPDATE size: $sizeIncrement", fatal = true))
      else Error(PROTOCOL_ERROR(streamId, fatal = false))
    }
    else if (streamId == 0) {
      oConnectionWindow.window += sizeIncrement

      // Allow all the nodes to attempt to write if they want to
      nodes().forall { node =>
        node.incrementOutboundWindow(0)
        oConnectionWindow() > 0
      }
      Continue
    }
    else {
      getNode(streamId).foreach(_.incrementOutboundWindow(sizeIncrement))
      Continue
    }
  }

  def onInitialWindowSizeChange(newWindow: Int): Unit = {
    val diff = newWindow - http2Settings.outbound_initial_window_size
    logger.trace(s"Adjusting outbound windows by $diff")
    http2Settings.outbound_initial_window_size = newWindow
    oConnectionWindow.window += diff

    nodes().foreach { node =>
      node.incrementOutboundWindow(diff)
    }
  }

  final class Stream(streamId: Int)
    extends AbstractStream[T](streamId,
      new FlowWindow(inboundWindow),
      new FlowWindow(http2Settings.outbound_initial_window_size),
      iConnectionWindow,
      oConnectionWindow,
      http2Settings,
      codec,
      headerEncoder) with HeadStage[Http2Msg] {

    override def name: String = s"Stream[$streamId]"

    ///////////////////////////////////////////////////////////////


    override protected[FlowControl] def closeStream(t: Throwable): Unit = super.closeStream(t)

    override def outboundCommand(cmd: OutboundCommand): Unit =
      http2Stage.streamCommand(this, cmd)

    // Write buffers to the socket
    override protected def writeBuffers(data: Seq[ByteBuffer]): Future[Unit] =
      http2Stage.writeBuffers(data)

    override def readRequest(size: Int): Future[Http2Msg] =
      http2Stage.streamRead(this)

    override def writeRequest(data: Http2Msg): Future[Unit] = writeRequest(data :: Nil)

    override def writeRequest(data: Seq[Http2Msg]): Future[Unit] =
      http2Stage.streamWrite(this, data)
  }
}