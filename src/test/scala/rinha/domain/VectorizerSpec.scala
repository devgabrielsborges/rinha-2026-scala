package rinha.domain

import munit.FunSuite

import java.time.Instant

class VectorizerSpec extends FunSuite:

  private val norm = NormalizationConstants(
    maxAmount = 10000.0f,
    maxInstallments = 12.0f,
    amountVsAvgRatio = 10.0f,
    maxMinutes = 1440.0f,
    maxKm = 1000.0f,
    maxTxCount24h = 20.0f,
    maxMerchantAvgAmount = 10000.0f
  )

  private val mccRisk = MccRiskMap(
    Map(
      "5411" -> 0.15f,
      "5812" -> 0.30f,
      "5912" -> 0.20f,
      "5944" -> 0.45f,
      "7801" -> 0.80f,
      "7802" -> 0.75f,
      "7995" -> 0.85f,
      "4511" -> 0.35f,
      "5311" -> 0.25f,
      "5999" -> 0.50f
    )
  )

  private def assertVectorClose(actual: Array[Float], expected: Array[Float], tol: Float = 0.001f)(
    implicit loc: munit.Location
  ): Unit =
    assertEquals(actual.length, expected.length, "vector dimension mismatch")
    actual.zip(expected).zipWithIndex.foreach { case ((a, e), i) =>
      assertEqualsFloat(a, e, tol, s"dim $i: expected $e, got $a")
    }

  test("legit transaction with null last_transaction — from competition rules") {
    val tx = TransactionRequest(
      id = "tx-1329056812",
      transaction = TransactionData(
        amount = 41.12,
        installments = 2,
        requestedAt = Instant.parse("2026-03-11T18:45:53Z")
      ),
      customer = CustomerData(
        avgAmount = 82.24,
        txCount24h = 3,
        knownMerchants = List("MERC-003", "MERC-016")
      ),
      merchant = MerchantData(id = "MERC-016", mcc = "5411", avgAmount = 60.25),
      terminal = TerminalData(isOnline = false, cardPresent = true, kmFromHome = 29.23),
      lastTransaction = None
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)
    val expected = Array(0.0041f, 0.1667f, 0.05f, 0.7826f, 0.3333f, -1f, -1f, 0.0292f, 0.15f, 0f,
      1f, 0f, 0.15f, 0.006f)

    assertVectorClose(result, expected)
  }

  test("fraud transaction with null last_transaction — from competition rules") {
    val tx = TransactionRequest(
      id = "tx-3330991687",
      transaction = TransactionData(
        amount = 9505.97,
        installments = 10,
        requestedAt = Instant.parse("2026-03-14T05:15:12Z")
      ),
      customer = CustomerData(
        avgAmount = 81.28,
        txCount24h = 20,
        knownMerchants = List("MERC-008", "MERC-007", "MERC-005")
      ),
      merchant = MerchantData(id = "MERC-068", mcc = "7802", avgAmount = 54.86),
      terminal = TerminalData(isOnline = false, cardPresent = true, kmFromHome = 952.27),
      lastTransaction = None
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)
    val expected = Array(0.9506f, 0.8333f, 1.0f, 0.2174f, 0.8333f, -1f, -1f, 0.9523f, 1.0f, 0f, 1f,
      1f, 0.75f, 0.0055f)

    assertVectorClose(result, expected)
  }

  test("transaction with last_transaction present") {
    val tx = TransactionRequest(
      id = "tx-with-last",
      transaction = TransactionData(
        amount = 384.88,
        installments = 3,
        requestedAt = Instant.parse("2026-03-11T20:23:35Z")
      ),
      customer = CustomerData(
        avgAmount = 769.76,
        txCount24h = 3,
        knownMerchants = List("MERC-009", "MERC-001")
      ),
      merchant = MerchantData(id = "MERC-001", mcc = "5912", avgAmount = 298.95),
      terminal = TerminalData(isOnline = false, cardPresent = true, kmFromHome = 13.709),
      lastTransaction = Some(
        LastTransactionData(
          timestamp = Instant.parse("2026-03-11T14:58:35Z"),
          kmFromCurrent = 18.863
        )
      )
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)

    assertEqualsFloat(result(0), 0.0385f, 0.001f)  // 384.88/10000
    assertEqualsFloat(result(1), 0.25f, 0.001f)    // 3/12
    assertEqualsFloat(result(5), 0.2257f, 0.001f)  // 325 min / 1440
    assertEqualsFloat(result(6), 0.01886f, 0.001f) // 18.863/1000
    assert(result(5) >= 0.0f, "minutes_since_last should not be sentinel")
    assert(result(6) >= 0.0f, "km_from_last should not be sentinel")
  }

  test("unknown MCC defaults to 0.5") {
    val tx = TransactionRequest(
      id = "tx-unknown-mcc",
      transaction = TransactionData(
        amount = 100.0,
        installments = 1,
        requestedAt = Instant.parse("2026-01-01T12:00:00Z")
      ),
      customer = CustomerData(avgAmount = 100.0, txCount24h = 1, knownMerchants = List.empty),
      merchant = MerchantData(id = "MERC-X", mcc = "9999", avgAmount = 100.0),
      terminal = TerminalData(isOnline = true, cardPresent = false, kmFromHome = 0.0),
      lastTransaction = None
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)
    assertEquals(result(12), MccRiskMap.DefaultRisk)
  }

  test("clamping: amount exceeds max_amount") {
    val tx = TransactionRequest(
      id = "tx-clamp",
      transaction = TransactionData(
        amount = 20000.0,
        installments = 24,
        requestedAt = Instant.parse("2026-06-15T23:59:59Z")
      ),
      customer = CustomerData(avgAmount = 50.0, txCount24h = 100, knownMerchants = List.empty),
      merchant = MerchantData(id = "MERC-Y", mcc = "5411", avgAmount = 50000.0),
      terminal = TerminalData(isOnline = false, cardPresent = true, kmFromHome = 5000.0),
      lastTransaction = None
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)
    assertEquals(result(0), 1.0f)  // clamped amount
    assertEquals(result(1), 1.0f)  // clamped installments
    assertEquals(result(2), 1.0f)  // clamped ratio
    assertEquals(result(7), 1.0f)  // clamped km_from_home
    assertEquals(result(8), 1.0f)  // clamped tx_count_24h
    assertEquals(result(13), 1.0f) // clamped merchant_avg_amount
  }

  test("avg_amount = 0 does not cause division by zero") {
    val tx = TransactionRequest(
      id = "tx-zero-avg",
      transaction = TransactionData(
        amount = 100.0,
        installments = 1,
        requestedAt = Instant.parse("2026-01-01T00:00:00Z")
      ),
      customer = CustomerData(avgAmount = 0.0, txCount24h = 0, knownMerchants = List.empty),
      merchant = MerchantData(id = "MERC-Z", mcc = "5411", avgAmount = 0.0),
      terminal = TerminalData(isOnline = false, cardPresent = false, kmFromHome = 0.0),
      lastTransaction = None
    )

    val result = Vectorizer.vectorize(tx, norm, mccRisk)
    assert(!result(2).isNaN, "amount_vs_avg should not be NaN")
    assert(!result(2).isInfinite, "amount_vs_avg should not be infinite")
  }
