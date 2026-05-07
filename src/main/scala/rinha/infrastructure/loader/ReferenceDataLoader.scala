package rinha.infrastructure.loader

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import rinha.infrastructure.search.VPTree

import java.io.{BufferedInputStream, FileInputStream}
import java.nio.file.{Path, Paths}
import java.util.zip.GZIPInputStream

object ReferenceDataLoader:

  private val Dims = 14

  private final case class ReferenceRecord(vector: Array[Float], label: String)
  private given JsonValueCodec[ReferenceRecord] = JsonCodecMaker.make

  final case class LoadResult(
    vectors: Array[Float],
    labels: java.util.BitSet,
    size: Int,
    tree: VPTree
  )

  def loadFromEnv(): LoadResult =
    val dataDir = sys.env.getOrElse("DATA_DIR", "resources")
    val refFile = sys.env.getOrElse("REFERENCES_FILE", "references.json.gz")
    val path    = Paths.get(dataDir, refFile)
    load(path)

  def load(path: Path): LoadResult =
    val (vectors, labels, count) = parseGzippedJson(path)
    val tree                     = VPTree.build(vectors, labels, Dims, count)
    LoadResult(vectors, labels, count, tree)

  private def parseGzippedJson(path: Path): (Array[Float], java.util.BitSet, Int) =
    val fis = new FileInputStream(path.toFile)
    val bis = new BufferedInputStream(fis, 1024 * 256)
    val gis = new GZIPInputStream(bis, 1024 * 256)

    try
      var vectors = new Array[Float](1024 * Dims)
      val labels  = new java.util.BitSet(1024)
      var count   = 0

      scanJsonArrayFromStream[ReferenceRecord](gis) { record =>
        if count * Dims >= vectors.length then
          val newVectors = new Array[Float](vectors.length * 2)
          System.arraycopy(vectors, 0, newVectors, 0, vectors.length)
          vectors = newVectors

        System.arraycopy(record.vector, 0, vectors, count * Dims, Dims)
        if record.label == "fraud" then labels.set(count)
        count += 1
        true
      }

      val trimmed = if count * Dims < vectors.length then
        val t = new Array[Float](count * Dims)
        System.arraycopy(vectors, 0, t, 0, count * Dims)
        t
      else vectors

      (trimmed, labels, count)
    finally gis.close()
