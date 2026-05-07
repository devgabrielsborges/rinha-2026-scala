package rinha.infrastructure.search

import rinha.application.VectorSearchPort
import rinha.domain.Neighbor

final class IVFSearchAdapter(index: IVFIndex) extends VectorSearchPort:

  override def findKNearest(query: Array[Float], k: Int): List[Neighbor] =
    index.searchKNN(query, k)
