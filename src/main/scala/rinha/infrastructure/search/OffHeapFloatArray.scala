package rinha.infrastructure.search

import sun.misc.Unsafe

import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}

/**
 * Off-heap float array backed by either native memory (Unsafe) or memory-mapped I/O.
 *
 * When mmap-backed, multiple JVM processes sharing the same file get identical physical pages from
 * the OS, cutting per-instance memory from ~168MB to near-zero for the vector store.
 */
final class OffHeapFloatArray private (
  val address: Long,
  val length: Int,
  private val mmap: MappedByteBuffer | Null
):

  @inline def get(index: Int): Float =
    OffHeapFloatArray.unsafe.getFloat(address + index.toLong * 4L)

  def free(): Unit =
    if mmap == null then OffHeapFloatArray.unsafe.freeMemory(address)

object OffHeapFloatArray:

  private[infrastructure] val unsafe: Unsafe =
    val f = classOf[Unsafe].getDeclaredField("theUnsafe")
    f.setAccessible(true)
    f.get(null).asInstanceOf[Unsafe]

  private lazy val AddressField =
    val f = classOf[java.nio.Buffer].getDeclaredField("address")
    f.setAccessible(true)
    f

  def allocate(length: Int): OffHeapFloatArray =
    val bytes = length.toLong * 4L
    val addr  = unsafe.allocateMemory(bytes)
    new OffHeapFloatArray(addr, length, null)

  def fromArray(arr: Array[Float]): OffHeapFloatArray =
    val oha = allocate(arr.length)
    unsafe.copyMemory(
      arr,
      Unsafe.ARRAY_FLOAT_BASE_OFFSET.toLong,
      null,
      oha.address,
      arr.length.toLong * 4L
    )
    oha

  def fromMmap(path: Path, length: Int): OffHeapFloatArray =
    val fc = FileChannel.open(path, StandardOpenOption.READ)
    try
      val buf  = fc.map(FileChannel.MapMode.READ_ONLY, 0, length.toLong * 4L)
      val addr = AddressField.getLong(buf)
      new OffHeapFloatArray(addr, length, buf)
    finally fc.close()
