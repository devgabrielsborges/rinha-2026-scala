package rinha

import rinha.application.FraudScoreUseCase
import rinha.infrastructure.http.NettyServer
import rinha.infrastructure.loader.{BinaryIndexLoader, ConfigLoader, Env, ReferenceDataLoader}
import rinha.infrastructure.search.{IVFIndex, IVFIndexBuilder, IVFSearchAdapter}

import java.nio.file.{Files, Paths}

object Main:

  private val WarmupIterations = 1000

  def main(args: Array[String]): Unit =
    println("Loading configuration...")
    val (normalization, mccRisk) = ConfigLoader.loadFromEnv()

    println("Loading index...")
    val index = loadIndex()
    println(s"Loaded ${index.size} reference vectors")

    val searchPort = new IVFSearchAdapter(index)
    val useCase    = new FraudScoreUseCase(searchPort, normalization, mccRisk)

    println("Warming up JIT compiler...")
    warmup(index)
    println("Warmup complete")

    val server = new NettyServer(useCase)
    server.setReady()
    server.start()

  private def warmup(index: IVFIndex): Unit =
    val query = new Array[Float](14)
    val rng   = new java.util.Random(0)
    var i     = 0
    while i < WarmupIterations do
      var j = 0
      while j < 14 do
        query(j) = rng.nextFloat()
        j += 1
      index.searchKNN(query, 5)
      i += 1

  private def loadIndex(): IVFIndex =
    val indexDir = Env.getOrElse("INDEX_DIR", "")
    if indexDir.nonEmpty && Files.exists(Paths.get(indexDir, "meta.bin")) then
      println(s"Using pre-built IVF index from $indexDir")
      BinaryIndexLoader.loadFromEnv()
    else
      println("IVF index not found, building from JSON (slow)...")
      buildFromJson()

  private def buildFromJson(): IVFIndex =
    val dataDir                  = Env.getOrElse("DATA_DIR", "resources")
    val refFile                  = Env.getOrElse("REFERENCES_FILE", "references.json.gz")
    val path                     = Paths.get(dataDir, refFile)
    val (vectors, labels, count) = ReferenceDataLoader.parseGzippedJson(path)
    val (index, _) = IVFIndexBuilder.build(
      vectors,
      labels,
      14,
      count,
      nClusters = 3000,
      nProbe = 8
    )
    index
