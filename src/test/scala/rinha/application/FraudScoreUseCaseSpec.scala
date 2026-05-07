package rinha.application

import munit.FunSuite
import rinha.domain.*

import java.time.Instant

class FraudScoreUseCaseSpec extends FunSuite:

  private val norm = NormalizationConstants(
    maxAmount = 10000.0f,
    maxInstallments = 12.0f,
    amountVsAvgRatio = 10.0f,
    maxMinutes = 1440.0f,
    maxKm = 1000.0f,
    maxTxCount24h = 20.0f,
    maxMerchantAvgAmount = 10000.0f
  )

  private val mccRisk = MccRiskMap(Map("5411" -> 0.15f))

  private val sampleTx = TransactionRequest(
    id = "tx-test",
    transaction = TransactionData(
      amount = 100.0,
      installments = 1,
      requestedAt = Instant.parse("2026-01-01T12:00:00Z")
    ),
    customer = CustomerData(avgAmount = 100.0, txCount24h = 1, knownMerchants = List.empty),
    merchant = MerchantData(id = "MERC-1", mcc = "5411", avgAmount = 100.0),
    terminal = TerminalData(isOnline = false, cardPresent = true, kmFromHome = 10.0),
    lastTransaction = None
  )

  private def mockPort(fraudCount: Int): VectorSearchPort =
    new VectorSearchPort:
      override def findKNearest(query: Array[Float], k: Int): List[Neighbor] =
        (0 until k).map { i =>
          val label = if i < fraudCount then Label.Fraud else Label.Legit
          Neighbor(i, i.toFloat * 0.01f, label)
        }.toList

  test("0/5 fraud → score=0.0, approved=true") {
    val useCase = new FraudScoreUseCase(mockPort(0), norm, mccRisk)
    val result  = useCase.evaluate(sampleTx)
    assertEquals(result.fraudScore, 0.0f)
    assert(result.approved)
  }

  test("2/5 fraud → score=0.4, approved=true") {
    val useCase = new FraudScoreUseCase(mockPort(2), norm, mccRisk)
    val result  = useCase.evaluate(sampleTx)
    assertEquals(result.fraudScore, 0.4f)
    assert(result.approved)
  }

  test("3/5 fraud → score=0.6, approved=false") {
    val useCase = new FraudScoreUseCase(mockPort(3), norm, mccRisk)
    val result  = useCase.evaluate(sampleTx)
    assertEquals(result.fraudScore, 0.6f)
    assert(!result.approved)
  }

  test("5/5 fraud → score=1.0, approved=false") {
    val useCase = new FraudScoreUseCase(mockPort(5), norm, mccRisk)
    val result  = useCase.evaluate(sampleTx)
    assertEquals(result.fraudScore, 1.0f)
    assert(!result.approved)
  }
