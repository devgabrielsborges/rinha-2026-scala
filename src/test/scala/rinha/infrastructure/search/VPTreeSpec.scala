package rinha.infrastructure.search

import munit.FunSuite
import rinha.domain.{Label, Neighbor}

import scala.util.Random

class VPTreeSpec extends FunSuite:

  private val dims = 14
  private val k    = 5

  private def bruteForceKNN(
    vectors: Array[Float],
    labels: java.util.BitSet,
    dims: Int,
    query: Array[Float],
    k: Int,
    n: Int
  ): List[Neighbor] =
    val results = (0 until n).map { i =>
      var sum = 0.0f
      var d   = 0
      while d < dims do
        val diff = query(d) - vectors(i * dims + d)
        sum += diff * diff
        d += 1
      val label = if labels.get(i) then Label.Fraud else Label.Legit
      Neighbor(i, sum, label)
    }
    results.sortBy(_.distanceSq).take(k).toList

  private def generateDataset(n: Int, rng: Random): (Array[Float], java.util.BitSet) =
    val vectors = new Array[Float](n * dims)
    val labels  = new java.util.BitSet(n)
    var i       = 0
    while i < n do
      var d = 0
      while d < dims do
        vectors(i * dims + d) = rng.nextFloat() * 2.0f - 1.0f
        d += 1
      if rng.nextFloat() < 0.3f then labels.set(i)
      i += 1
    (vectors, labels)

  test("KNN results match brute-force on 1000 vectors") {
    val rng          = new Random(12345)
    val n            = 1000
    val (vecs, lbls) = generateDataset(n, rng)

    val tree = VPTree.build(vecs, lbls, dims, n, new Random(42))

    val queryRng = new Random(99)
    var matches  = 0
    val trials   = 50

    (0 until trials).foreach { _ =>
      val query       = Array.fill(dims)(queryRng.nextFloat() * 2.0f - 1.0f)
      val vpResult    = tree.searchKNN(query, k)
      val bruteResult = bruteForceKNN(vecs, lbls, dims, query, k, n)

      assertEquals(vpResult.length, k, "should return exactly k neighbors")

      val vpIndices    = vpResult.map(_.index).toSet
      val bruteIndices = bruteResult.map(_.index).toSet
      if vpIndices == bruteIndices then matches += 1
    }

    assert(matches == trials, s"VP-Tree matched brute-force in $matches/$trials queries")
  }

  test("KNN returns correct labels") {
    val rng          = new Random(777)
    val n            = 200
    val (vecs, lbls) = generateDataset(n, rng)

    val tree  = VPTree.build(vecs, lbls, dims, n, new Random(42))
    val query = Array.fill(dims)(0.0f)

    val results = tree.searchKNN(query, k)
    results.foreach { neighbor =>
      val expectedLabel = if lbls.get(neighbor.index) then Label.Fraud else Label.Legit
      assertEquals(neighbor.label, expectedLabel, s"label mismatch for index ${neighbor.index}")
    }
  }

  test("KNN results are sorted by distance") {
    val rng          = new Random(555)
    val n            = 500
    val (vecs, lbls) = generateDataset(n, rng)

    val tree  = VPTree.build(vecs, lbls, dims, n, new Random(42))
    val query = Array.fill(dims)(0.5f)

    val results = tree.searchKNN(query, k)
    results.sliding(2).foreach {
      case List(a, b) => assert(a.distanceSq <= b.distanceSq, "results not sorted by distance")
      case _          => ()
    }
  }

  test("handles sentinel -1 values in vectors") {
    val n    = 100
    val vecs = new Array[Float](n * dims)
    val lbls = new java.util.BitSet(n)
    val rng  = new Random(111)

    var i = 0
    while i < n do
      var d = 0
      while d < dims do
        vecs(i * dims + d) =
          if (d == 5 || d == 6) && rng.nextFloat() < 0.5f then -1.0f
          else rng.nextFloat()
        d += 1
      if rng.nextFloat() < 0.3f then lbls.set(i)
      i += 1

    val tree  = VPTree.build(vecs, lbls, dims, n, new Random(42))
    val query = new Array[Float](dims)
    query(5) = -1.0f
    query(6) = -1.0f
    (0 until dims).foreach(d => if d != 5 && d != 6 then query(d) = 0.5f)

    val results = tree.searchKNN(query, k)
    assertEquals(results.length, k)

    val bruteResults = bruteForceKNN(vecs, lbls, dims, query, k, n)
    val vpIndices    = results.map(_.index).toSet
    val bruteIndices = bruteResults.map(_.index).toSet
    assertEquals(vpIndices, bruteIndices, "sentinel vectors: VP-Tree must match brute-force")
  }

  test("small dataset: fewer points than k") {
    val n    = 3
    val vecs = Array.fill(n * dims)(0.5f)
    val lbls = new java.util.BitSet(n)
    lbls.set(0)

    val tree    = VPTree.build(vecs, lbls, dims, n, new Random(42))
    val query   = Array.fill(dims)(0.5f)
    val results = tree.searchKNN(query, k)

    assertEquals(results.length, n, "should return all points when n < k")
  }
