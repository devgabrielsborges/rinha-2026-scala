package rinha.application

import rinha.domain.Neighbor

trait VectorSearchPort:
  def findKNearest(query: Array[Float], k: Int): List[Neighbor]
