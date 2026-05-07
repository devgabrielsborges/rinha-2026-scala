package rinha.application

import rinha.domain.*

final class FraudScoreUseCase(
  searchPort: VectorSearchPort,
  normalization: NormalizationConstants,
  mccRisk: MccRiskMap
):

  private val K = FraudDecision.ThresholdDenominator

  def evaluate(tx: TransactionRequest): FraudDecision =
    val vector     = Vectorizer.vectorize(tx, normalization, mccRisk)
    val neighbors  = searchPort.findKNearest(vector, K)
    val fraudCount = neighbors.count(_.label == Label.Fraud)
    FraudDecision.fromFraudCount(fraudCount)
