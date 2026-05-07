package rinha.infrastructure.loader

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

object Env:

  private val dotEnv: Map[String, String] = loadDotEnv()

  def getOrElse(key: String, default: String): String =
    sys.env.getOrElse(key, dotEnv.getOrElse(key, default))

  private def loadDotEnv(): Map[String, String] =
    val path = Paths.get(".env")
    if !Files.exists(path) then return Map.empty

    Files
      .readAllLines(path)
      .asScala
      .iterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap { line =>
        val idx = line.indexOf('=')
        if idx > 0 then
          val key   = line.substring(0, idx).trim
          val value = line.substring(idx + 1).trim
          Some(key -> value)
        else None
      }
      .toMap
