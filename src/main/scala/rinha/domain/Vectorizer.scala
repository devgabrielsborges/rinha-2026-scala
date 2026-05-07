package rinha.domain

import java.time.{DayOfWeek, ZoneOffset}
import java.time.temporal.ChronoUnit

object Vectorizer:

  val Dimensions: Int = 14
  val Sentinel: Float = -1.0f
  val HoursMax: Float = 23.0f
  val DaysMax: Float  = 6.0f

  inline private def clamp(x: Float): Float =
    math.max(0.0f, math.min(1.0f, x))

  /** Transforms a transaction request into a 14-dimensional normalized vector. */
  def vectorize(
    tx: TransactionRequest,
    norm: NormalizationConstants,
    mccRisk: MccRiskMap
  ): Array[Float] =
    val v = new Array[Float](Dimensions)

    val txData = tx.transaction
    val cust   = tx.customer
    val merch  = tx.merchant
    val term   = tx.terminal
    val lastTx = tx.lastTransaction

    v(0) = clamp(txData.amount.toFloat / norm.maxAmount)
    v(1) = clamp(txData.installments.toFloat / norm.maxInstallments)

    val avgAmount = cust.avgAmount
    val ratio =
      if avgAmount == 0.0 then 1.0f
      else (txData.amount / avgAmount).toFloat / norm.amountVsAvgRatio
    v(2) = clamp(ratio)

    val utcTime = txData.requestedAt.atOffset(ZoneOffset.UTC)
    v(3) = utcTime.getHour.toFloat / HoursMax
    val dow = utcTime.getDayOfWeek match
      case DayOfWeek.MONDAY    => 0
      case DayOfWeek.TUESDAY   => 1
      case DayOfWeek.WEDNESDAY => 2
      case DayOfWeek.THURSDAY  => 3
      case DayOfWeek.FRIDAY    => 4
      case DayOfWeek.SATURDAY  => 5
      case DayOfWeek.SUNDAY    => 6
    v(4) = dow.toFloat / DaysMax

    lastTx match
      case Some(lt) =>
        val minutes = ChronoUnit.MINUTES.between(lt.timestamp, txData.requestedAt).toFloat
        v(5) = clamp(minutes / norm.maxMinutes)
        v(6) = clamp(lt.kmFromCurrent.toFloat / norm.maxKm)
      case None =>
        v(5) = Sentinel
        v(6) = Sentinel

    v(7) = clamp(term.kmFromHome.toFloat / norm.maxKm)
    v(8) = clamp(cust.txCount24h.toFloat / norm.maxTxCount24h)
    v(9) = if term.isOnline then 1.0f else 0.0f
    v(10) = if term.cardPresent then 1.0f else 0.0f

    val isUnknownMerchant = !cust.knownMerchants.contains(merch.id)
    v(11) = if isUnknownMerchant then 1.0f else 0.0f

    v(12) = mccRisk.riskFor(merch.mcc)
    v(13) = clamp(merch.avgAmount.toFloat / norm.maxMerchantAvgAmount)

    v
