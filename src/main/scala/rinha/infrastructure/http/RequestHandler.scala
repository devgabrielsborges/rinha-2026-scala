package rinha.infrastructure.http

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.*

import rinha.application.FraudScoreUseCase
import rinha.domain.FraudDecision
import rinha.infrastructure.json.{FraudDecisionEncoder, TransactionDecoder}

import java.util.concurrent.atomic.AtomicBoolean

final class RequestHandler(
  useCase: FraudScoreUseCase,
  ready: AtomicBoolean
) extends SimpleChannelInboundHandler[FullHttpRequest]:

  private val safeBytes: Array[Byte] = FraudDecisionEncoder.encode(FraudDecision.SafeDefault)
  private val JsonType               = "application/json"

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val method = req.method()
    val uri    = req.uri()

    if method == HttpMethod.GET && uri == "/ready" then handleReady(ctx, req)
    else if method == HttpMethod.POST && uri == "/fraud-score" then handleFraudScore(ctx, req)
    else sendStatus(ctx, req, HttpResponseStatus.NOT_FOUND)

  private def handleReady(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val status =
      if ready.get() then HttpResponseStatus.OK
      else HttpResponseStatus.SERVICE_UNAVAILABLE
    sendStatus(ctx, req, status)

  private def handleFraudScore(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val content = req.content()
    val len     = content.readableBytes()
    val bytes   = new Array[Byte](len)
    content.readBytes(bytes)

    val responseBytes =
      try
        val decision = useCase.evaluate(TransactionDecoder.decode(bytes))
        FraudDecisionEncoder.encode(decision)
      catch case _: Exception => safeBytes

    val response = new DefaultFullHttpResponse(
      req.protocolVersion(),
      HttpResponseStatus.OK,
      Unpooled.wrappedBuffer(responseBytes)
    )
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, JsonType)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length)

    if !HttpUtil.isKeepAlive(req) then
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    else
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      ctx.writeAndFlush(response)

  private def sendStatus(
    ctx: ChannelHandlerContext,
    req: FullHttpRequest,
    status: HttpResponseStatus
  ): Unit =
    val response = new DefaultFullHttpResponse(req.protocolVersion(), status, Unpooled.EMPTY_BUFFER)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
    if !HttpUtil.isKeepAlive(req) then
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    else
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      ctx.writeAndFlush(response)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    ctx.close()
