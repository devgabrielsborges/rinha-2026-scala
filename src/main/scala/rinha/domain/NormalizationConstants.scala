package rinha.domain

final case class NormalizationConstants(
  maxAmount: Float,
  maxInstallments: Float,
  amountVsAvgRatio: Float,
  maxMinutes: Float,
  maxKm: Float,
  maxTxCount24h: Float,
  maxMerchantAvgAmount: Float
)
