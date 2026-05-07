package rinha

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Ref

import rinha.application.FraudScoreUseCase
import rinha.infrastructure.http.HttpServer
import rinha.infrastructure.loader.{ConfigLoader, ReferenceDataLoader}
import rinha.infrastructure.search.VPTreeSearchAdapter

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    for
      ready <- Ref.of[IO, Boolean](false)

      _      <- IO.println("Loading configuration...")
      config <- IO.blocking(ConfigLoader.loadFromEnv())
      (normalization, mccRisk) = config

      _       <- IO.println("Loading reference data and building VP-Tree index...")
      refData <- IO.blocking(ReferenceDataLoader.loadFromEnv())
      _       <- IO.println(s"Loaded ${refData.size} reference vectors")

      searchPort = new VPTreeSearchAdapter(refData.tree)
      useCase    = new FraudScoreUseCase(searchPort, normalization, mccRisk)

      exitCode <- HttpServer
        .resource(useCase, ready)
        .use { server =>
          for
            _ <- IO.println(s"Server started at ${server.baseUri}")
            _ <- ready.set(true)
            _ <- IO.println("Ready to serve requests")
            _ <- IO.never[Unit]
          yield ExitCode.Success
        }
    yield exitCode
