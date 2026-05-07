package rinha.tools

import rinha.infrastructure.loader.{Env, ReferenceDataLoader}
import rinha.infrastructure.search.VPTree

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/**
 * Standalone tool that pre-builds the VP-Tree binary index at Docker build time.
 *
 * Reads references.json.gz, constructs the VP-Tree, and serializes the internal arrays to binary
 * files (vectors.bin, order.bin, medians.bin, labels.bin, meta.bin) for fast mmap-based loading at
 * runtime.
 */
object IndexBuilder:

  private val Dims = 14

  def main(args: Array[String]): Unit =
    val dataDir = Env.getOrElse("DATA_DIR", "resources")
    val refFile = Env.getOrElse("REFERENCES_FILE", "references.json.gz")
    val outDir  = Env.getOrElse("INDEX_DIR", dataDir)

    val inputPath  = Paths.get(dataDir, refFile)
    val outputPath = Paths.get(outDir)

    println(s"IndexBuilder: reading $inputPath ...")
    val (vectors, labels, count) = ReferenceDataLoader.parseGzippedJson(inputPath)
    println(s"IndexBuilder: loaded $count vectors (dims=$Dims)")

    println("IndexBuilder: building VP-Tree ...")
    val (_, buildData) = VPTree.buildWithData(vectors, labels, Dims, count)
    println("IndexBuilder: VP-Tree built")

    Files.createDirectories(outputPath)
    println(s"IndexBuilder: writing binary index to $outputPath ...")

    writeMeta(outputPath.resolve("meta.bin"), count, Dims)
    writeFloatArray(outputPath.resolve("vectors.bin"), vectors, count * Dims)
    writeIntArray(outputPath.resolve("order.bin"), buildData.order, count)
    writeFloatArray(outputPath.resolve("medians.bin"), buildData.medians, count)
    writeLabels(outputPath.resolve("labels.bin"), labels, count)

    println("IndexBuilder: binary index written successfully")
    printSummary(outputPath)

  private def writeMeta(path: Path, size: Int, dims: Int): Unit =
    val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(size)
    buf.putInt(dims)
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
    val files = List("meta.bin", "vectors.bin", "order.bin", "medians.bin", "labels.bin")
    files.foreach { name =>
      val path = dir.resolve(name)
      if Files.exists(path) then
        val sizeKB = Files.size(path) / 1024.0
        println(f"  $name: $sizeKB%.1f KB")
    }
