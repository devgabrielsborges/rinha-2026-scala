package rinha.domain

opaque type MccRiskMap = Map[String, Float]

object MccRiskMap:

  val DefaultRisk: Float = 0.5f

  def apply(entries: Map[String, Float]): MccRiskMap = entries

  extension (m: MccRiskMap) def riskFor(mcc: String): Float = m.getOrElse(mcc, DefaultRisk)
