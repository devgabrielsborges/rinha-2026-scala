package rinha.infrastructure.loader

import rinha.infrastructure.search.IVFIndex

import java.nio.{ByteBuffer, ByteOrder, FloatBuffer}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/**
 * Loads a pre-built IVF binary index. Vectors go to off-heap direct memory to avoid GC scanning the
 * 168MB array. Centroids, offsets, and permutation stay on-heap (small).
 */
object BinaryIndexLoader:

  def loadFromEnv(): IVFIndex =
    val indexDir = Env.getOrElse("INDEX_DIR", "")
    load(Paths.get(indexDir))

  def load(dir: Path): IVFIndex =
    val (size, dims, nClusters, defaultProbe) = readMeta(dir.resolve("meta.bin"))
    val nProbe      = Env.getOrElse("IVF_NPROBE", defaultProbe.toString).toInt
    val vectors     = readFloatsDirect(dir.resolve("vectors.bin"), size * dims)
    val centroids   = readFloats(dir.resolve("centroids.bin"), nClusters * dims)
    val offsets     = readInts(dir.resolve("offsets.bin"), nClusters + 1)
    val permutation = readInts(dir.resolve("permutation.bin"), size)
    val labels      = readLabels(dir.resolve("labels.bin"))
    IVFIndex(vectors, labels, dims, centroids, nClusters, offsets, permutation, nProbe, size)

  private def readMeta(path: Path): (Int, Int, Int, Int) =
    val bytes = Files.readAllBytes(path)
    val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    (buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt())

  private val ChunkSize = 32768

  private def readFloatsDirect(path: Path, count: Int): FloatBuffer =
    val direct = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN)
    val fc     = FileChannel.open(path, StandardOpenOption.READ)
    try while direct.hasRemaining do fc.read(direct)
    finally fc.close()
    direct.flip()
    direct.asFloatBuffer()

  private def readFloats(path: Path, count: Int): Array[Float] =
    val arr = new Array[Float](count)
    val fc  = FileChannel.open(path, StandardOpenOption.READ)
    try
      val buf    = ByteBuffer.allocate(ChunkSize * 4).order(ByteOrder.LITTLE_ENDIAN)
      val fbuf   = buf.asFloatBuffer()
      var offset = 0
      while offset < count do
        buf.clear()
        while buf.hasRemaining do if fc.read(buf) == -1 then buf.limit(buf.position())
        buf.flip()
        fbuf.clear()
        fbuf.limit(buf.remaining() / 4)
        val n = fbuf.remaining()
        fbuf.get(arr, offset, n)
        offset += n
      arr
    finally fc.close()

  private def readInts(path: Path, count: Int): Array[Int] =
    val arr = new Array[Int](count)
    val fc  = FileChannel.open(path, StandardOpenOption.READ)
    try
      val buf    = ByteBuffer.allocate(ChunkSize * 4).order(ByteOrder.LITTLE_ENDIAN)
      val ibuf   = buf.asIntBuffer()
      var offset = 0
      while offset < count do
        buf.clear()
        while buf.hasRemaining do if fc.read(buf) == -1 then buf.limit(buf.position())
        buf.flip()
        ibuf.clear()
        ibuf.limit(buf.remaining() / 4)
        val n = ibuf.remaining()
        ibuf.get(arr, offset, n)
        offset += n
      arr
    finally fc.close()

  private def readLabels(path: Path): java.util.BitSet =
    java.util.BitSet.valueOf(Files.readAllBytes(path))
