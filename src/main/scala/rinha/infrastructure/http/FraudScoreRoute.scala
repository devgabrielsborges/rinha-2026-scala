package rinha.infrastructure.http

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import rinha.application.FraudScoreUseCase
import rinha.domain.FraudDecision
import rinha.infrastructure.json.{FraudDecisionEncoder, TransactionDecoder}

object FraudScoreRoute:

  private val jsonContentType = `Content-Type`(MediaType.application.json)

  def routes(useCase: FraudScoreUseCase): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "fraud-score" =>
      req.body.compile.to(Array).flatMap { bytes =>
        val decision =
          try useCase.evaluate(TransactionDecoder.decode(bytes))
          catch case _: Exception => FraudDecision.SafeDefault

        val body = FraudDecisionEncoder.encode(decision)
        IO.pure(
          Response[IO](Status.Ok)
            .withEntity(body)
            .withContentType(jsonContentType)
        )
      }
    }
