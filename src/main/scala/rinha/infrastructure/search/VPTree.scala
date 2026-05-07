package rinha.infrastructure.search

import rinha.domain.{Label, Neighbor}

import scala.collection.mutable
import scala.util.Random

/**
 * Flat array-based Vantage-Point Tree for exact KNN search in metric spaces.
 *
 * The tree reorders an index array so that each node's vantage point sits at `start`, closer points
 * occupy [start+1, mid), and farther points occupy [mid, end). Medians are stored in a parallel
 * array indexed by the `start` position.
 *
 * @param vectors
 *   contiguous flat Float array: vectors(i*dims .. i*dims+dims-1) is vector i
 * @param labels
 *   BitSet where bit i is set if vector i is fraud
 * @param dims
 *   number of dimensions per vector
 * @param order
 *   permutation array built during construction
 * @param medians
 *   median distance stored per node (indexed by start position)
 * @param size
 *   number of vectors
 */
final class VPTree private (
  private val vectors: Array[Float],
  private val labels: java.util.BitSet,
  private val dims: Int,
  private val order: Array[Int],
  private val medians: Array[Float],
  val size: Int
):

  def searchKNN(query: Array[Float], k: Int): List[Neighbor] =
    val heap = new VPTree.MaxHeap(k)
    search(0, size, query, k, heap)
    heap.toSortedList(idx => labelFor(idx))

  private def search(
    start: Int,
    end: Int,
    query: Array[Float],
    k: Int,
    heap: VPTree.MaxHeap
  ): Unit =
    if start >= end then return

    val vpIdx = order(start)
    val dist  = euclideanDist(query, 0, vectors, vpIdx * dims, dims)

    heap.tryInsert(vpIdx, dist)

    if start + 1 >= end then return

    val mid = start + 1 + (end - start - 1) / 2
    val mu  = medians(start)

    if dist < mu then
      search(start + 1, mid, query, k, heap)
      if dist + heap.maxDist >= mu then search(mid, end, query, k, heap)
    else
      search(mid, end, query, k, heap)
      if dist - heap.maxDist <= mu then search(start + 1, mid, query, k, heap)

  private def euclideanDist(
    a: Array[Float],
    aOff: Int,
    b: Array[Float],
    bOff: Int,
    d: Int
  ): Float =
    var sum = 0.0f
    var i   = 0
    while i < d do
      val diff = a(aOff + i) - b(bOff + i)
      sum += diff * diff
      i += 1
    math.sqrt(sum.toDouble).toFloat

  def labelFor(index: Int): Label =
    if labels.get(index) then Label.Fraud else Label.Legit

object VPTree:

  def build(
    vectors: Array[Float],
    labels: java.util.BitSet,
    dims: Int,
    size: Int
  ): VPTree = build(vectors, labels, dims, size, new Random(42))

  def build(
    vectors: Array[Float],
    labels: java.util.BitSet,
    dims: Int,
    size: Int,
    rng: Random
  ): VPTree =
    val order   = Array.tabulate(size)(identity)
    val medians = new Array[Float](size)
    val dists   = new Array[Float](size)

    buildRecursive(vectors, dims, order, medians, dists, 0, size, rng)
    new VPTree(vectors, labels, dims, order, medians, size)

  private def buildRecursive(
    vectors: Array[Float],
    dims: Int,
    order: Array[Int],
    medians: Array[Float],
    dists: Array[Float],
    start: Int,
    end: Int,
    rng: Random
  ): Unit =
    if end - start <= 1 then return

    // pick a random vantage point and swap to start
    val vpPos = start + rng.nextInt(end - start)
    swap(order, start, vpPos)
    val vpIdx = order(start)

    // compute distances from vantage point to all other points in range
    var i = start + 1
    while i < end do
      dists(i) = euclideanDistStatic(vectors, vpIdx * dims, vectors, order(i) * dims, dims)
      i += 1

    // partition around median
    val mid = start + 1 + (end - start - 1) / 2
    nthElement(order, dists, start + 1, end, mid)
    medians(start) = dists(mid)

    buildRecursive(vectors, dims, order, medians, dists, start + 1, mid, rng)
    buildRecursive(vectors, dims, order, medians, dists, mid, end, rng)

  /** Quickselect to partition so that dists[order[k]] is the k-th smallest. */
  private def nthElement(
    order: Array[Int],
    dists: Array[Float],
    lo: Int,
    hi: Int,
    k: Int
  ): Unit =
    var left  = lo
    var right = hi - 1

    while left < right do
      val pivotDist = dists(left + (right - left) / 2)
      var i         = left
      var j         = right

      while i <= j do
        while dists(i) < pivotDist do i += 1
        while dists(j) > pivotDist do j -= 1
        if i <= j then
          swap(order, i, j)
          val tmp = dists(i); dists(i) = dists(j); dists(j) = tmp
          i += 1
          j -= 1

      if j < k then left = i
      if k < i then right = j

  private def swap(arr: Array[Int], a: Int, b: Int): Unit =
    val tmp = arr(a); arr(a) = arr(b); arr(b) = tmp

  private def euclideanDistStatic(
    a: Array[Float],
    aOff: Int,
    b: Array[Float],
    bOff: Int,
    d: Int
  ): Float =
    var sum = 0.0f
    var i   = 0
    while i < d do
      val diff = a(aOff + i) - b(bOff + i)
      sum += diff * diff
      i += 1
    math.sqrt(sum.toDouble).toFloat

  /** Fixed-size max-heap for KNN search. Keeps the k closest neighbors. */
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
        buf += Neighbor(idx, distances(i) * distances(i), labelFor(idx))
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
        // swap
        val tmpI = indices(i); indices(i) = indices(largest); indices(largest) = tmpI
        val tmpD = distances(i); distances(i) = distances(largest); distances(largest) = tmpD
        i = largest
