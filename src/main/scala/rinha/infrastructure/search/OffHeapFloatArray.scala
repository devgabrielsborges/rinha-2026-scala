package rinha.infrastructure.search

import sun.misc.Unsafe

/**
 * Off-heap float array backed by native memory via sun.misc.Unsafe.
 *
 * Reads are raw pointer dereferences -- same speed as on-heap array access, but the memory is
 * invisible to the GC, eliminating scan/compaction overhead for the 168MB vectors array.
 */
final class OffHeapFloatArray private (val address: Long, val length: Int):

  @inline def get(index: Int): Float =
    OffHeapFloatArray.unsafe.getFloat(address + index.toLong * 4L)

  def free(): Unit = OffHeapFloatArray.unsafe.freeMemory(address)

object OffHeapFloatArray:

  private[infrastructure] val unsafe: Unsafe =
    val f = classOf[Unsafe].getDeclaredField("theUnsafe")
    f.setAccessible(true)
    f.get(null).asInstanceOf[Unsafe]

  def allocate(length: Int): OffHeapFloatArray =
    val bytes = length.toLong * 4L
    val addr  = unsafe.allocateMemory(bytes)
    new OffHeapFloatArray(addr, length)

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
