package rinha.infrastructure.loader

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import rinha.domain.{MccRiskMap, NormalizationConstants}

import java.nio.file.{Files, Path, Paths}

object ConfigLoader:

  private final case class NormalizationJson(
    max_amount: Float,
    max_installments: Float,
    amount_vs_avg_ratio: Float,
    max_minutes: Float,
    max_km: Float,
    max_tx_count_24h: Float,
    max_merchant_avg_amount: Float
  )

  private given JsonValueCodec[NormalizationJson]  = JsonCodecMaker.make
  private given JsonValueCodec[Map[String, Float]] = JsonCodecMaker.make

  def loadNormalization(path: Path): NormalizationConstants =
    val bytes = Files.readAllBytes(path)
    val raw   = readFromArray[NormalizationJson](bytes)
    NormalizationConstants(
      maxAmount = raw.max_amount,
      maxInstallments = raw.max_installments,
      amountVsAvgRatio = raw.amount_vs_avg_ratio,
      maxMinutes = raw.max_minutes,
      maxKm = raw.max_km,
      maxTxCount24h = raw.max_tx_count_24h,
      maxMerchantAvgAmount = raw.max_merchant_avg_amount
    )

  def loadMccRisk(path: Path): MccRiskMap =
    val bytes   = Files.readAllBytes(path)
    val entries = readFromArray[Map[String, Float]](bytes)
    MccRiskMap(entries)

  def loadFromEnv(): (NormalizationConstants, MccRiskMap) =
    val dataDir           = sys.env.getOrElse("DATA_DIR", "src/main/resources")
    val normalizationFile = sys.env.getOrElse("NORMALIZATION_FILE", "normalization.json")
    val mccRiskFile       = sys.env.getOrElse("MCC_RISK_FILE", "mcc_risk.json")

    val normPath = Paths.get(dataDir, normalizationFile)
    val mccPath  = Paths.get(dataDir, mccRiskFile)

    (loadNormalization(normPath), loadMccRisk(mccPath))
