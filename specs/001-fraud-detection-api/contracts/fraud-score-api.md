# API Contract: Fraud Detection

**Base URL**: `http://localhost:9999`

## Endpoints

### GET /ready

Health/readiness probe.

**Response**:
- `200 OK` — API is ready to serve fraud-score requests.
- `503 Service Unavailable` — API is still loading data or building
  the search index.

No response body required.

### POST /fraud-score

Evaluate a credit card transaction for fraud.

**Request**:

Content-Type: `application/json`

```json
{
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
}
```

**Request Fields**:

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | string | yes | Transaction identifier |
| `transaction.amount` | number | yes | Transaction value |
| `transaction.installments` | integer | yes | Number of installments |
| `transaction.requested_at` | string (ISO 8601) | yes | UTC timestamp |
| `customer.avg_amount` | number | yes | Customer's historical average |
| `customer.tx_count_24h` | integer | yes | Transactions in last 24h |
| `customer.known_merchants` | string[] | yes | Previously used merchants |
| `merchant.id` | string | yes | Merchant identifier |
| `merchant.mcc` | string | yes | Merchant Category Code |
| `merchant.avg_amount` | number | yes | Merchant's average ticket |
| `terminal.is_online` | boolean | yes | Online vs. in-person |
| `terminal.card_present` | boolean | yes | Card physically present |
| `terminal.km_from_home` | number | yes | Distance from cardholder home |
| `last_transaction` | object or null | yes | Previous transaction data |
| `last_transaction.timestamp` | string (ISO 8601) | conditional | Required if `last_transaction` is not null |
| `last_transaction.km_from_current` | number | conditional | Required if `last_transaction` is not null |

**Response**:

Content-Type: `application/json`

```json
{
  "approved": false,
  "fraud_score": 0.8
}
```

**Response Fields**:

| Field | Type | Values | Notes |
|-------|------|--------|-------|
| `approved` | boolean | `true` / `false` | `true` if `fraud_score < 0.6` |
| `fraud_score` | number | 0.0, 0.2, 0.4, 0.6, 0.8, 1.0 | `fraud_neighbors / 5` |

**Status Codes**:
- `200 OK` — Always returned for valid requests, regardless of
  fraud decision.

**Error Handling**:
- Malformed JSON or missing fields: return
  `{ "approved": true, "fraud_score": 0.0 }` with HTTP 200
  (safe default — avoids HTTP 5xx penalty of weight 5).

## Vectorization Contract

The API MUST transform the request into a 14-dimensional float vector
using the formulas defined in REGRAS_DE_DETECCAO.md before performing
KNN search. The mapping from JSON fields to vector dimensions is:

| Dim | Formula |
|-----|---------|
| 0 | `clamp(transaction.amount / max_amount)` |
| 1 | `clamp(transaction.installments / max_installments)` |
| 2 | `clamp((transaction.amount / customer.avg_amount) / amount_vs_avg_ratio)` |
| 3 | `hour(transaction.requested_at) / 23` |
| 4 | `dayOfWeek(transaction.requested_at) / 6` (Mon=0, Sun=6) |
| 5 | `clamp(minutes / max_minutes)` or `-1` if `last_transaction` is null |
| 6 | `clamp(last_transaction.km_from_current / max_km)` or `-1` if null |
| 7 | `clamp(terminal.km_from_home / max_km)` |
| 8 | `clamp(customer.tx_count_24h / max_tx_count_24h)` |
| 9 | `1.0` if `terminal.is_online` else `0.0` |
| 10 | `1.0` if `terminal.card_present` else `0.0` |
| 11 | `1.0` if `merchant.id NOT IN customer.known_merchants` else `0.0` |
| 12 | `mcc_risk[merchant.mcc]` (default `0.5` if MCC unknown) |
| 13 | `clamp(merchant.avg_amount / max_merchant_avg_amount)` |

`clamp(x)` = `max(0.0, min(1.0, x))`

## KNN Search Contract

- **k**: 5
- **Metric**: Euclidean distance (squared distance for ordering)
- **Dataset**: 3,000,000 reference vectors from `references.json.gz`
- **Result**: 5 nearest neighbors with their labels (`fraud`/`legit`)
- **Score**: `count(label == fraud) / 5`
- **Threshold**: `fraud_score < 0.6` → approved
