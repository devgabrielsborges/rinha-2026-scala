package rinha.domain

import java.time.Instant

final case class TransactionRequest(
  id: String,
  transaction: TransactionData,
  customer: CustomerData,
  merchant: MerchantData,
  terminal: TerminalData,
  lastTransaction: Option[LastTransactionData]
)

final case class TransactionData(
  amount: Double,
  installments: Int,
  requestedAt: Instant
)

final case class CustomerData(
  avgAmount: Double,
  txCount24h: Int,
  knownMerchants: List[String]
)

final case class MerchantData(
  id: String,
  mcc: String,
  avgAmount: Double
)

final case class TerminalData(
  isOnline: Boolean,
  cardPresent: Boolean,
  kmFromHome: Double
)

final case class LastTransactionData(
  timestamp: Instant,
  kmFromCurrent: Double
)
