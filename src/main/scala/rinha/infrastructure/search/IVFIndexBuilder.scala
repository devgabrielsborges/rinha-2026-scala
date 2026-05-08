package rinha.infrastructure.search

import scala.util.Random

/**
 * Builds an IVF (Inverted File) index from reference vectors using mini-batch k-means clustering.
 *
 * The build proceeds in two phases:
 *   1. Mini-batch k-means: iteratively refine cluster centroids using random subsets.
 *   2. Assignment: assign every vector to its nearest centroid and build the inverted lists.
 */
object IVFIndexBuilder:

  final case class IVFBuildResult(
    centroids: Array[Float],
    clusterOffsets: Array[Int],
    permutation: Array[Int]
  )

  def build(
    vectors: Array[Float],
    labels: java.util.BitSet,
    dims: Int,
    size: Int,
    nClusters: Int,
    nProbe: Int,
    kmeansIterations: Int = 15,
    miniBatchSize: Int = 50000,
    seed: Long = 42L
  ): (IVFIndex, IVFBuildResult) =
    val rng = new Random(seed)

    val centroids = initCentroids(vectors, dims, size, nClusters, rng)
    miniBatchKMeans(vectors, dims, size, centroids, nClusters, kmeansIterations, miniBatchSize, rng)

    val assignments            = assignAll(vectors, dims, size, centroids, nClusters)
    val (offsets, permutation) = buildInvertedLists(assignments, size, nClusters)

    val result = IVFBuildResult(centroids, offsets, permutation)
    val index =
      IVFIndex.fromHeapArray(
        vectors,
        labels,
        dims,
        centroids,
        nClusters,
        offsets,
        permutation,
        nProbe,
        size
      )
    (index, result)

  private def initCentroids(
    vectors: Array[Float],
    dims: Int,
    size: Int,
    nClusters: Int,
    rng: Random
  ): Array[Float] =
    val centroids = new Array[Float](nClusters * dims)
    val chosen    = new java.util.HashSet[Int](nClusters * 2)
    var c         = 0
    while c < nClusters do
      var idx = rng.nextInt(size)
      while chosen.contains(idx) do idx = rng.nextInt(size)
      chosen.add(idx)
      System.arraycopy(vectors, idx * dims, centroids, c * dims, dims)
      c += 1
    centroids

  private def miniBatchKMeans(
    vectors: Array[Float],
    dims: Int,
    size: Int,
    centroids: Array[Float],
    nClusters: Int,
    iterations: Int,
    batchSize: Int,
    rng: Random
  ): Unit =
    val counts = new Array[Int](nClusters)
    val sums   = new Array[Double](nClusters * dims)

    var iter = 0
    while iter < iterations do
      java.util.Arrays.fill(counts, 0)
      java.util.Arrays.fill(sums, 0.0)

      var b = 0
      while b < batchSize do
        val idx     = rng.nextInt(size)
        val nearest = findNearest(vectors, idx * dims, centroids, dims, nClusters)
        counts(nearest) += 1
        val sOff = nearest * dims
        val vOff = idx * dims
        var d    = 0
        while d < dims do
          sums(sOff + d) += vectors(vOff + d)
          d += 1
        b += 1

      var c = 0
      while c < nClusters do
        if counts(c) > 0 then
          val cOff = c * dims
          var d    = 0
          while d < dims do
            centroids(cOff + d) = (sums(cOff + d) / counts(c)).toFloat
            d += 1
        c += 1

      iter += 1

  private def findNearest(
    vectors: Array[Float],
    vOff: Int,
    centroids: Array[Float],
    dims: Int,
    nClusters: Int
  ): Int =
    var bestDist = Float.MaxValue
    var bestIdx  = 0
    var c        = 0
    while c < nClusters do
      val dist = squaredEuclidean(vectors, vOff, centroids, c * dims, dims)
      if dist < bestDist then
        bestDist = dist
        bestIdx = c
      c += 1
    bestIdx

  private def assignAll(
    vectors: Array[Float],
    dims: Int,
    size: Int,
    centroids: Array[Float],
    nClusters: Int
  ): Array[Int] =
    val assignments = new Array[Int](size)
    var i           = 0
    while i < size do
      assignments(i) = findNearest(vectors, i * dims, centroids, dims, nClusters)
      i += 1
    assignments

  private def buildInvertedLists(
    assignments: Array[Int],
    size: Int,
    nClusters: Int
  ): (Array[Int], Array[Int]) =
    val counts = new Array[Int](nClusters)
    var i      = 0
    while i < size do
      counts(assignments(i)) += 1
      i += 1

    val offsets = new Array[Int](nClusters + 1)
    var c       = 0
    while c < nClusters do
      offsets(c + 1) = offsets(c) + counts(c)
      c += 1

    val permutation = new Array[Int](size)
    val cursors     = new Array[Int](nClusters)
    System.arraycopy(offsets, 0, cursors, 0, nClusters)

    i = 0
    while i < size do
      val cluster = assignments(i)
      permutation(cursors(cluster)) = i
      cursors(cluster) += 1
      i += 1

    (offsets, permutation)

  private def squaredEuclidean(
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
    sum
