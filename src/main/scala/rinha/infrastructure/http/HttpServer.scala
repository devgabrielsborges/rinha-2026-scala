package rinha.infrastructure.http

import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.semigroupk.*
import com.comcast.ip4s.*
import org.http4s.HttpRoutes
import org.http4s.netty.server.NettyServerBuilder
import org.http4s.server.Server

import rinha.application.FraudScoreUseCase
import rinha.infrastructure.loader.Env

object HttpServer:

  def resource(
    useCase: FraudScoreUseCase,
    ready: Ref[IO, Boolean]
  ): Resource[IO, Server] =
    val portStr  = Env.getOrElse("HTTP_PORT", "8080")
    val hostStr  = Env.getOrElse("HTTP_HOST", "0.0.0.0")
    val httpPort = Port.fromString(portStr).getOrElse(port"8080")
    val httpHost = Host.fromString(hostStr).getOrElse(host"0.0.0.0")

    val allRoutes: HttpRoutes[IO] =
      FraudScoreRoute.routes(useCase) <+> ReadyRoute.routes(ready)

    NettyServerBuilder[IO]
      .withHttpApp(allRoutes.orNotFound)
      .bindHttp(httpPort.value, httpHost.toString)
      .resource
