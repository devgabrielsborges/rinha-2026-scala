package rinha.infrastructure.search

import rinha.application.VectorSearchPort
import rinha.domain.Neighbor

final class VPTreeSearchAdapter(tree: VPTree) extends VectorSearchPort:

  override def findKNearest(query: Array[Float], k: Int): List[Neighbor] =
    tree.searchKNN(query, k)
