package rinha.infrastructure.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import rinha.domain.FraudDecision

object FraudDecisionEncoder:

  private final case class FraudDecisionJson(
    approved: Boolean,
    fraud_score: Float
  )

  private given JsonValueCodec[FraudDecisionJson] = JsonCodecMaker.make

  def encode(decision: FraudDecision): Array[Byte] =
    writeToArray(FraudDecisionJson(decision.approved, decision.fraudScore))

  def encodeToString(decision: FraudDecision): String =
    writeToString(FraudDecisionJson(decision.approved, decision.fraudScore))
