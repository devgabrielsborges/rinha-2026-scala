package rinha.domain

final case class FraudDecision(approved: Boolean, fraudScore: Float)

object FraudDecision:

  val ThresholdDenominator: Int  = 5
  val ApprovalThreshold: Float   = 0.6f
  val SafeDefault: FraudDecision = FraudDecision(approved = true, fraudScore = 0.0f)

  def fromFraudCount(fraudCount: Int): FraudDecision =
    val score    = fraudCount.toFloat / ThresholdDenominator
    val approved = score < ApprovalThreshold
    FraudDecision(approved, score)
