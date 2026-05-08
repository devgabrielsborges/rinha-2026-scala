package rinha.infrastructure.search

import rinha.domain.{Label, Neighbor}

import java.nio.FloatBuffer
import scala.collection.mutable

/**
 * Inverted File (IVF) index for fast approximate KNN search.
 *
 * Vectors are stored in an off-heap FloatBuffer to avoid GC pressure on the 168MB array. Centroids
 * remain on-heap (tiny: ~164KB) for fast centroid search.
 */
final class IVFIndex private (
  private val vectors: FloatBuffer,
  private val labels: java.util.BitSet,
  private val dims: Int,
  private val centroids: Array[Float],
  private val nClusters: Int,
  private val clusterOffsets: Array[Int],
  private val permutation: Array[Int],
  private val nProbe: Int,
  val size: Int
):

  def searchKNN(query: Array[Float], k: Int): List[Neighbor] =
    val probes = findNearestCentroids(query, nProbe)
    val heap   = new IVFIndex.MaxHeap(k)

    var p = 0
    while p < nProbe do
      val c     = probes(p)
      val start = clusterOffsets(c)
      val end   = clusterOffsets(c + 1)
      var i     = start
      while i < end do
        val idx    = permutation(i)
        val distSq = squaredEuclideanOffHeap(query, idx * dims, dims)
        heap.tryInsert(idx, distSq)
        i += 1
      p += 1

    heap.toSortedList(idx => if labels.get(idx) then Label.Fraud else Label.Legit)

  private def findNearestCentroids(query: Array[Float], n: Int): Array[Int] =
    val dists   = new Array[Float](nClusters)
    val indices = new Array[Int](nClusters)
    var c       = 0
    while c < nClusters do
      dists(c) = squaredEuclideanHeap(query, centroids, c * dims, dims)
      indices(c) = c
      c += 1

    partialSort(indices, dists, n)
    java.util.Arrays.copyOf(indices, n)

  private def squaredEuclideanOffHeap(
    a: Array[Float],
    bOff: Int,
    d: Int
  ): Float =
    var sum = 0.0f
    var i   = 0
    while i < d do
      val diff = a(i) - vectors.get(bOff + i)
      sum += diff * diff
      i += 1
    sum

  private def squaredEuclideanHeap(
    a: Array[Float],
    b: Array[Float],
    bOff: Int,
    d: Int
  ): Float =
    var sum = 0.0f
    var i   = 0
    while i < d do
      val diff = a(i) - b(bOff + i)
      sum += diff * diff
      i += 1
    sum

  /** Partial sort: puts the n smallest elements at the beginning of indices (sorted by dists). */
  private def partialSort(indices: Array[Int], dists: Array[Float], n: Int): Unit =
    var lo = 0
    var hi = indices.length - 1
    while lo < hi do
      val pivot = dists(indices(lo + (hi - lo) / 2))
      var i     = lo
      var j     = hi
      while i <= j do
        while dists(indices(i)) < pivot do i += 1
        while dists(indices(j)) > pivot do j -= 1
        if i <= j then
          val tmp = indices(i); indices(i) = indices(j); indices(j) = tmp
          i += 1
          j -= 1
      if j < n - 1 then lo = i
      if n - 1 < i then hi = j

    java.util.Arrays.sort(indices, 0, n)

object IVFIndex:

  def apply(
    vectors: FloatBuffer,
    labels: java.util.BitSet,
    dims: Int,
    centroids: Array[Float],
    nClusters: Int,
    clusterOffsets: Array[Int],
    permutation: Array[Int],
    nProbe: Int,
    size: Int
  ): IVFIndex =
    new IVFIndex(
      vectors,
      labels,
      dims,
      centroids,
      nClusters,
      clusterOffsets,
      permutation,
      nProbe,
      size
    )

  def fromHeapArray(
    vectors: Array[Float],
    labels: java.util.BitSet,
    dims: Int,
    centroids: Array[Float],
    nClusters: Int,
    clusterOffsets: Array[Int],
    permutation: Array[Int],
    nProbe: Int,
    size: Int
  ): IVFIndex =
    new IVFIndex(
      FloatBuffer.wrap(vectors),
      labels,
      dims,
      centroids,
      nClusters,
      clusterOffsets,
      permutation,
      nProbe,
      size
    )

  private[search] final class MaxHeap(k: Int):
    private val indices   = new Array[Int](k)
    private val distances = new Array[Float](k)
    private var count     = 0

    def maxDist: Float = if count < k then Float.MaxValue else distances(0)

    def tryInsert(index: Int, dist: Float): Unit =
      if count < k then
        indices(count) = index
        distances(count) = dist
        count += 1
        if count == k then buildHeap()
      else if dist < distances(0) then
        indices(0) = index
        distances(0) = dist
        siftDown(0)

    def toSortedList(labelFor: Int => Label): List[Neighbor] =
      val buf = new mutable.ArrayBuffer[Neighbor](count)
      var i   = 0
      while i < count do
        val idx = indices(i)
        buf += Neighbor(idx, distances(i), labelFor(idx))
        i += 1
      buf.sortBy(_.distanceSq).toList

    private def buildHeap(): Unit =
      var i = count / 2 - 1
      while i >= 0 do
        siftDown(i)
        i -= 1

    private def siftDown(pos: Int): Unit =
      var i = pos
      while true do
        var largest = i
        val left    = 2 * i + 1
        val right   = 2 * i + 2
        if left < count && distances(left) > distances(largest) then largest = left
        if right < count && distances(right) > distances(largest) then largest = right
        if largest == i then return
        val tmpI = indices(i); indices(i) = indices(largest); indices(largest) = tmpI
        val tmpD = distances(i); distances(i) = distances(largest); distances(largest) = tmpD
        i = largest
