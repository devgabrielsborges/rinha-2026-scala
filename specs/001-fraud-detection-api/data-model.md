# Data Model: Fraud Detection API

**Date**: 2026-05-07

## Domain Entities

### Transaction

The incoming request payload, deserialized from JSON.

```scala
case class TransactionRequest(
  id: String,
  transaction: TransactionData,
  customer: CustomerData,
  merchant: MerchantData,
  terminal: TerminalData,
  lastTransaction: Option[LastTransactionData]
)

case class TransactionData(
  amount: Double,
  installments: Int,
  requestedAt: java.time.Instant
)

case class CustomerData(
  avgAmount: Double,
  txCount24h: Int,
  knownMerchants: List[String]
)

case class MerchantData(
  id: String,
  mcc: String,
  avgAmount: Double
)

case class TerminalData(
  isOnline: Boolean,
  cardPresent: Boolean,
  kmFromHome: Double
)

case class LastTransactionData(
  timestamp: java.time.Instant,
  kmFromCurrent: Double
)
```

**Invariants**:
- `transaction.amount >= 0`
- `transaction.installments >= 0`
- `customer.txCount24h >= 0`
- `terminal.kmFromHome >= 0`
- `lastTransaction` is `None` when the JSON field is `null`

### NormalizationConstants

Loaded from `normalization.json` at startup. Immutable after load.

```scala
case class NormalizationConstants(
  maxAmount: Double,
  maxInstallments: Double,
  amountVsAvgRatio: Double,
  maxMinutes: Double,
  maxKm: Double,
  maxTxCount24h: Double,
  maxMerchantAvgAmount: Double
)
```

**Source**: `normalization.json` with known values:
- maxAmount = 10000, maxInstallments = 12, amountVsAvgRatio = 10
- maxMinutes = 1440, maxKm = 1000, maxTxCount24h = 20
- maxMerchantAvgAmount = 10000

### MccRiskMap

Loaded from `mcc_risk.json` at startup. Maps MCC code → risk score.

```scala
opaque type MccRiskMap = Map[String, Float]

object MccRiskMap:
  val DefaultRisk: Float = 0.5f
  def apply(entries: Map[String, Float]): MccRiskMap = entries
  extension (m: MccRiskMap)
    def riskFor(mcc: String): Float =
      m.getOrElse(mcc, DefaultRisk)
```

**Invariant**: All risk values are in [0.0, 1.0]. Unknown MCCs
default to 0.5.

### TransactionVector

The 14-dimensional normalized vector produced by the Vectorizer.

```scala
opaque type TransactionVector = Array[Float]

object TransactionVector:
  val Dimensions: Int = 14
  def apply(values: Array[Float]): TransactionVector =
    require(values.length == Dimensions)
    values
```

**Dimension mapping**:

| Index | Name | Range |
|-------|------|-------|
| 0 | amount | [0.0, 1.0] |
| 1 | installments | [0.0, 1.0] |
| 2 | amount_vs_avg | [0.0, 1.0] |
| 3 | hour_of_day | [0.0, 1.0] |
| 4 | day_of_week | [0.0, 1.0] |
| 5 | minutes_since_last_tx | [0.0, 1.0] or -1.0 |
| 6 | km_from_last_tx | [0.0, 1.0] or -1.0 |
| 7 | km_from_home | [0.0, 1.0] |
| 8 | tx_count_24h | [0.0, 1.0] |
| 9 | is_online | 0.0 or 1.0 |
| 10 | card_present | 0.0 or 1.0 |
| 11 | unknown_merchant | 0.0 or 1.0 |
| 12 | mcc_risk | [0.0, 1.0] |
| 13 | merchant_avg_amount | [0.0, 1.0] |

### ReferenceVector

A pre-labeled vector from the reference dataset.

```scala
enum Label:
  case Fraud, Legit
```

At runtime, vectors are stored as a flat `Array[Float]` (3M × 14
contiguous floats) and labels as a `BitSet` (1 = Fraud, 0 = Legit).
No per-record objects exist in memory.

### FraudDecision

The API response.

```scala
case class FraudDecision(
  approved: Boolean,
  fraudScore: Float
)
```

**Invariants**:
- `fraudScore` is one of {0.0, 0.2, 0.4, 0.6, 0.8, 1.0}
  (always `fraudCount / 5` where fraudCount ∈ {0,1,2,3,4,5})
- `approved = fraudScore < 0.6`

### Neighbor

Internal result type from KNN search.

```scala
case class Neighbor(
  index: Int,
  distanceSq: Float,
  label: Label
)
```

## Relationships

```
TransactionRequest
    │
    ▼ (vectorize with NormalizationConstants + MccRiskMap)
TransactionVector (14D)
    │
    ▼ (KNN search over ReferenceVector[3M])
List[Neighbor] (k=5)
    │
    ▼ (count frauds, compute score)
FraudDecision
```

## Data Volumes

| Entity | Count | Size in Memory |
|--------|-------|---------------|
| ReferenceVector (flat array) | 3,000,000 | 168 MB (14 × Float32) |
| Labels (BitSet) | 3,000,000 | 375 KB |
| VP-Tree structure | 3,000,000 nodes | ~24 MB (flat arrays) |
| NormalizationConstants | 1 | ~56 bytes |
| MccRiskMap | 10 entries | ~200 bytes |
| TransactionRequest (per request) | 1 | ~500 bytes (stack-allocated) |
| TransactionVector (per request) | 1 | 56 bytes (14 × Float32) |
| Neighbor heap (per request) | 5 | ~100 bytes |
