package rinha

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Ref

import rinha.application.FraudScoreUseCase
import rinha.infrastructure.http.HttpServer
import rinha.infrastructure.loader.{BinaryIndexLoader, ConfigLoader, Env, ReferenceDataLoader}
import rinha.infrastructure.search.{VPTree, VPTreeSearchAdapter}

import java.nio.file.{Files, Paths}

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    for
      ready <- Ref.of[IO, Boolean](false)

      _      <- IO.println("Loading configuration...")
      config <- IO.blocking(ConfigLoader.loadFromEnv())
      (normalization, mccRisk) = config

      _    <- IO.println("Loading index...")
      tree <- IO.blocking(loadTree())
      _    <- IO.println(s"Loaded ${tree.size} reference vectors")

      searchPort = new VPTreeSearchAdapter(tree)
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

  private def loadTree(): VPTree =
    val indexDir = Env.getOrElse("INDEX_DIR", "")
    if indexDir.nonEmpty && Files.exists(Paths.get(indexDir, "meta.bin")) then
      println(s"Using pre-built binary index from $indexDir (mmap)")
      BinaryIndexLoader.loadFromEnv()
    else
      println("Binary index not found, falling back to JSON parsing + tree build")
      ReferenceDataLoader.loadFromEnv().tree
