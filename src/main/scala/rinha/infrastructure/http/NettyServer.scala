package rinha.infrastructure.http

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ChannelInitializer, ChannelOption, EventLoopGroup}
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{HttpObjectAggregator, HttpServerCodec}

import rinha.application.FraudScoreUseCase
import rinha.infrastructure.loader.Env

import java.util.concurrent.atomic.AtomicBoolean

final class NettyServer(useCase: FraudScoreUseCase):

  private val ready = new AtomicBoolean(false)

  def setReady(): Unit = ready.set(true)

  def start(): Unit =
    val port = Env.getOrElse("HTTP_PORT", "8080").toInt
    val host = Env.getOrElse("HTTP_HOST", "0.0.0.0")

    val useEpoll = Epoll.isAvailable
    val bossGroup: EventLoopGroup =
      if useEpoll then new EpollEventLoopGroup(1) else new NioEventLoopGroup(1)
    val workerGroup: EventLoopGroup =
      if useEpoll then new EpollEventLoopGroup() else new NioEventLoopGroup()

    try
      val bootstrap = new ServerBootstrap()
      bootstrap
        .group(bossGroup, workerGroup)
        .channel(
          if useEpoll then classOf[EpollServerSocketChannel]
          else classOf[NioServerSocketChannel]
        )
        .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
        .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
        .option(ChannelOption.SO_BACKLOG, Integer.valueOf(1024))
        .childHandler(
          new ChannelInitializer[SocketChannel]:
            override def initChannel(ch: SocketChannel): Unit =
              ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(new RequestHandler(useCase, ready))
        )

      println(s"Starting Netty on $host:$port (epoll=$useEpoll)")
      val channel = bootstrap.bind(host, port).sync().channel()
      channel.closeFuture().sync()
    finally
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
