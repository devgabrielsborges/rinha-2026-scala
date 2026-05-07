package rinha.infrastructure.http

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class ReadyRouteSpec extends CatsEffectSuite:

  test("GET /ready returns 503 when not ready") {
    for
      ref <- Ref.of[IO, Boolean](false)
      routes = ReadyRoute.routes(ref)
      req    = Request[IO](Method.GET, uri"/ready")
      resp <- routes.orNotFound.run(req)
    yield assertEquals(resp.status, Status.ServiceUnavailable)
  }

  test("GET /ready returns 200 when ready") {
    for
      ref <- Ref.of[IO, Boolean](true)
      routes = ReadyRoute.routes(ref)
      req    = Request[IO](Method.GET, uri"/ready")
      resp <- routes.orNotFound.run(req)
    yield assertEquals(resp.status, Status.Ok)
  }

  test("GET /ready transitions from 503 to 200 after flag set") {
    for
      ref <- Ref.of[IO, Boolean](false)
      routes = ReadyRoute.routes(ref)
      req    = Request[IO](Method.GET, uri"/ready")
      resp1 <- routes.orNotFound.run(req)
      _     <- IO(assertEquals(resp1.status, Status.ServiceUnavailable))
      _     <- ref.set(true)
      resp2 <- routes.orNotFound.run(req)
    yield assertEquals(resp2.status, Status.Ok)
  }
