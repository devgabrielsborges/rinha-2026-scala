package rinha.infrastructure.http

import cats.effect.IO
import cats.effect.kernel.Ref
import org.http4s.*
import org.http4s.dsl.io.*

object ReadyRoute:

  def routes(ready: Ref[IO, Boolean]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "ready" =>
      ready.get.flatMap { isReady =>
        if isReady then IO.pure(Response[IO](Status.Ok))
        else IO.pure(Response[IO](Status.ServiceUnavailable))
      }
    }
