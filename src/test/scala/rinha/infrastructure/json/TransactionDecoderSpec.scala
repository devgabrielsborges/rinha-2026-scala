package rinha.infrastructure.json

import munit.FunSuite

import java.time.Instant

class TransactionDecoderSpec extends FunSuite:

  test("decode legit transaction with last_transaction present") {
    val json = """{
      "id": "tx-3576980410",
      "transaction": {
        "amount": 384.88,
        "installments": 3,
        "requested_at": "2026-03-11T20:23:35Z"
      },
      "customer": {
        "avg_amount": 769.76,
        "tx_count_24h": 3,
        "known_merchants": ["MERC-009", "MERC-001", "MERC-001"]
      },
      "merchant": {
        "id": "MERC-001",
        "mcc": "5912",
        "avg_amount": 298.95
      },
      "terminal": {
        "is_online": false,
        "card_present": true,
        "km_from_home": 13.709
      },
      "last_transaction": {
        "timestamp": "2026-03-11T14:58:35Z",
        "km_from_current": 18.863
      }
    }"""

    val tx = TransactionDecoder.decodeFromString(json)

    assertEquals(tx.id, "tx-3576980410")
    assertEqualsDouble(tx.transaction.amount, 384.88, 0.001)
    assertEquals(tx.transaction.installments, 3)
    assertEquals(tx.transaction.requestedAt, Instant.parse("2026-03-11T20:23:35Z"))
    assertEqualsDouble(tx.customer.avgAmount, 769.76, 0.001)
    assertEquals(tx.customer.txCount24h, 3)
    assertEquals(tx.customer.knownMerchants, List("MERC-009", "MERC-001", "MERC-001"))
    assertEquals(tx.merchant.id, "MERC-001")
    assertEquals(tx.merchant.mcc, "5912")
    assert(tx.terminal.cardPresent)
    assert(!tx.terminal.isOnline)
    assert(tx.lastTransaction.isDefined)
    assertEqualsDouble(tx.lastTransaction.get.kmFromCurrent, 18.863, 0.001)
  }

  test("decode transaction with null last_transaction") {
    val json = """{
      "id": "tx-1329056812",
      "transaction": {
        "amount": 41.12,
        "installments": 2,
        "requested_at": "2026-03-11T18:45:53Z"
      },
      "customer": {
        "avg_amount": 82.24,
        "tx_count_24h": 3,
        "known_merchants": ["MERC-003", "MERC-016"]
      },
      "merchant": {
        "id": "MERC-016",
        "mcc": "5411",
        "avg_amount": 60.25
      },
      "terminal": {
        "is_online": false,
        "card_present": true,
        "km_from_home": 29.23
      },
      "last_transaction": null
    }"""

    val tx = TransactionDecoder.decodeFromString(json)

    assertEquals(tx.id, "tx-1329056812")
    assertEqualsDouble(tx.transaction.amount, 41.12, 0.001)
    assert(tx.lastTransaction.isEmpty, "null last_transaction should map to None")
  }

  test("decode fraud transaction payload") {
    val json = """{
      "id": "tx-3330991687",
      "transaction": {
        "amount": 9505.97,
        "installments": 10,
        "requested_at": "2026-03-14T05:15:12Z"
      },
      "customer": {
        "avg_amount": 81.28,
        "tx_count_24h": 20,
        "known_merchants": ["MERC-008", "MERC-007", "MERC-005"]
      },
      "merchant": {
        "id": "MERC-068",
        "mcc": "7802",
        "avg_amount": 54.86
      },
      "terminal": {
        "is_online": false,
        "card_present": true,
        "km_from_home": 952.27
      },
      "last_transaction": null
    }"""

    val tx = TransactionDecoder.decodeFromString(json)

    assertEquals(tx.id, "tx-3330991687")
    assertEqualsDouble(tx.transaction.amount, 9505.97, 0.001)
    assertEquals(tx.transaction.installments, 10)
    assertEquals(tx.merchant.mcc, "7802")
    assertEqualsDouble(tx.terminal.kmFromHome, 952.27, 0.001)
    assert(tx.lastTransaction.isEmpty)
  }
