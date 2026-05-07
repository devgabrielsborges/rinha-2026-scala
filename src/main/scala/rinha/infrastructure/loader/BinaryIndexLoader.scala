package rinha.infrastructure.loader

import rinha.infrastructure.search.VPTree

import java.io.RandomAccessFile
import java.nio.{ByteBuffer, ByteOrder, FloatBuffer, IntBuffer}
import java.nio.file.{Files, Path, Paths}

/**
 * Loads a pre-built VP-Tree binary index via memory-mapped files.
 *
 * Both API containers mmap the same binary files from the Docker image layer, so the kernel shares
 * physical pages between processes through the overlay2 page cache.
 */
object BinaryIndexLoader:

  def loadFromEnv(): VPTree =
    val indexDir = Env.getOrElse("INDEX_DIR", "")
    load(Paths.get(indexDir))

  def load(dir: Path): VPTree =
    val (size, dims) = readMeta(dir.resolve("meta.bin"))
    val vectors      = mmapFloats(dir.resolve("vectors.bin"), size.toLong * dims)
    val order        = mmapInts(dir.resolve("order.bin"), size.toLong)
    val medians      = mmapFloats(dir.resolve("medians.bin"), size.toLong)
    val labels       = readLabels(dir.resolve("labels.bin"))
    VPTree.fromBuffers(vectors, labels, dims, order, medians, size)

  private def readMeta(path: Path): (Int, Int) =
    val bytes = Files.readAllBytes(path)
    val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    (buf.getInt(), buf.getInt())

  private def mmapFloats(path: Path, count: Long): FloatBuffer =
    val raf = new RandomAccessFile(path.toFile, "r")
    try
      val mapped = raf.getChannel.map(
        java.nio.channels.FileChannel.MapMode.READ_ONLY,
        0,
        count * 4
      )
      mapped.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    finally raf.close()

  private def mmapInts(path: Path, count: Long): IntBuffer =
    val raf = new RandomAccessFile(path.toFile, "r")
    try
      val mapped = raf.getChannel.map(
        java.nio.channels.FileChannel.MapMode.READ_ONLY,
        0,
        count * 4
      )
      mapped.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    finally raf.close()

  private def readLabels(path: Path): java.util.BitSet =
    java.util.BitSet.valueOf(Files.readAllBytes(path))
