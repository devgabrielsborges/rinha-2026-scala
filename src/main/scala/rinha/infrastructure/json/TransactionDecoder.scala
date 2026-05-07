package rinha.infrastructure.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import rinha.domain.*

import java.time.Instant

object TransactionDecoder:

  private final case class TransactionRequestJson(
    id: String,
    transaction: TransactionDataJson,
    customer: CustomerDataJson,
    merchant: MerchantDataJson,
    terminal: TerminalDataJson,
    last_transaction: Option[LastTransactionDataJson]
  )

  private final case class TransactionDataJson(
    amount: Double,
    installments: Int,
    requested_at: Instant
  )

  private final case class CustomerDataJson(
    avg_amount: Double,
    tx_count_24h: Int,
    known_merchants: List[String]
  )

  private final case class MerchantDataJson(
    id: String,
    mcc: String,
    avg_amount: Double
  )

  private final case class TerminalDataJson(
    is_online: Boolean,
    card_present: Boolean,
    km_from_home: Double
  )

  private final case class LastTransactionDataJson(
    timestamp: Instant,
    km_from_current: Double
  )

  private given JsonValueCodec[TransactionRequestJson] = JsonCodecMaker.make

  def decode(bytes: Array[Byte]): TransactionRequest =
    val raw = readFromArray[TransactionRequestJson](bytes)
    toDomain(raw)

  def decodeFromString(json: String): TransactionRequest =
    val raw = readFromString[TransactionRequestJson](json)
    toDomain(raw)

  private def toDomain(raw: TransactionRequestJson): TransactionRequest =
    TransactionRequest(
      id = raw.id,
      transaction = TransactionData(
        amount = raw.transaction.amount,
        installments = raw.transaction.installments,
        requestedAt = raw.transaction.requested_at
      ),
      customer = CustomerData(
        avgAmount = raw.customer.avg_amount,
        txCount24h = raw.customer.tx_count_24h,
        knownMerchants = raw.customer.known_merchants
      ),
      merchant = MerchantData(
        id = raw.merchant.id,
        mcc = raw.merchant.mcc,
        avgAmount = raw.merchant.avg_amount
      ),
      terminal = TerminalData(
        isOnline = raw.terminal.is_online,
        cardPresent = raw.terminal.card_present,
        kmFromHome = raw.terminal.km_from_home
      ),
      lastTransaction = raw.last_transaction.map(lt =>
        LastTransactionData(
          timestamp = lt.timestamp,
          kmFromCurrent = lt.km_from_current
        )
      )
    )
