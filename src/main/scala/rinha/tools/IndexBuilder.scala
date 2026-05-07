package rinha.tools

import rinha.infrastructure.loader.{Env, ReferenceDataLoader}
import rinha.infrastructure.search.IVFIndexBuilder

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/**
 * Standalone tool that pre-builds the IVF binary index at Docker build time.
 *
 * Reads references.json.gz, clusters vectors via mini-batch k-means, and serializes the IVF
 * structures to binary files for fast loading at runtime.
 */
object IndexBuilder:

  private val Dims       = 14
  private val NClusters  = 3000
  private val NProbe     = 8
  private val Iterations = 15
  private val BatchSize  = 50000

  def main(args: Array[String]): Unit =
    val dataDir = Env.getOrElse("DATA_DIR", "resources")
    val refFile = Env.getOrElse("REFERENCES_FILE", "references.json.gz")
    val outDir  = Env.getOrElse("INDEX_DIR", dataDir)

    val inputPath  = Paths.get(dataDir, refFile)
    val outputPath = Paths.get(outDir)

    println(s"IndexBuilder: reading $inputPath ...")
    val (vectors, labels, count) = ReferenceDataLoader.parseGzippedJson(inputPath)
    println(s"IndexBuilder: loaded $count vectors (dims=$Dims)")

    println(
      s"IndexBuilder: building IVF index ($NClusters clusters, $Iterations k-means iterations)..."
    )
    val (_, buildData) = IVFIndexBuilder.build(
      vectors,
      labels,
      Dims,
      count,
      nClusters = NClusters,
      nProbe = NProbe,
      kmeansIterations = Iterations,
      miniBatchSize = BatchSize
    )
    println("IndexBuilder: IVF index built")

    Files.createDirectories(outputPath)
    println(s"IndexBuilder: writing binary index to $outputPath ...")

    writeMeta(outputPath.resolve("meta.bin"), count, Dims, NClusters, NProbe)
    writeFloatArray(outputPath.resolve("vectors.bin"), vectors, count * Dims)
    writeFloatArray(outputPath.resolve("centroids.bin"), buildData.centroids, NClusters * Dims)
    writeIntArray(outputPath.resolve("offsets.bin"), buildData.clusterOffsets, NClusters + 1)
    writeIntArray(outputPath.resolve("permutation.bin"), buildData.permutation, count)
    writeLabels(outputPath.resolve("labels.bin"), labels, count)

    println("IndexBuilder: binary index written successfully")
    printSummary(outputPath)

  private def writeMeta(path: Path, size: Int, dims: Int, nClusters: Int, nProbe: Int): Unit =
    val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(size)
    buf.putInt(dims)
    buf.putInt(nClusters)
    buf.putInt(nProbe)
    Files.write(path, buf.array())

  private def writeFloatArray(path: Path, data: Array[Float], count: Int): Unit =
    val fc = FileChannel.open(
      path,
      StandardOpenOption.WRITE,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    try
      val chunkFloats = 32768
      val buf         = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN)
      val fbuf        = buf.asFloatBuffer()
      var offset      = 0
      while offset < count do
        buf.clear()
        fbuf.clear()
        val n = math.min(chunkFloats, count - offset)
        fbuf.put(data, offset, n)
        buf.limit(n * 4)
        while buf.hasRemaining do fc.write(buf)
        offset += n
    finally fc.close()

  private def writeIntArray(path: Path, data: Array[Int], count: Int): Unit =
    val fc = FileChannel.open(
      path,
      StandardOpenOption.WRITE,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    try
      val chunkInts = 32768
      val buf       = ByteBuffer.allocate(chunkInts * 4).order(ByteOrder.LITTLE_ENDIAN)
      val ibuf      = buf.asIntBuffer()
      var offset    = 0
      while offset < count do
        buf.clear()
        ibuf.clear()
        val n = math.min(chunkInts, count - offset)
        ibuf.put(data, offset, n)
        buf.limit(n * 4)
        while buf.hasRemaining do fc.write(buf)
        offset += n
    finally fc.close()

  private def writeLabels(path: Path, labels: java.util.BitSet, size: Int): Unit =
    val byteCount = (size + 7) / 8
    val bytes     = new Array[Byte](byteCount)
    val rawBytes  = labels.toByteArray()
    System.arraycopy(rawBytes, 0, bytes, 0, rawBytes.length)
    Files.write(path, bytes)

  private def printSummary(dir: Path): Unit =
    val files = List(
      "meta.bin",
      "vectors.bin",
      "centroids.bin",
      "offsets.bin",
      "permutation.bin",
      "labels.bin"
    )
    files.foreach { name =>
      val path = dir.resolve(name)
      if Files.exists(path) then
        val sizeKB = Files.size(path) / 1024.0
        println(f"  $name: $sizeKB%.1f KB")
    }
