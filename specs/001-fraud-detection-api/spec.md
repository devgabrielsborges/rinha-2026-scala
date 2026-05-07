# Feature Specification: Fraud Detection API

**Feature Branch**: `feat/001-fraud-detection-api`
**Created**: 2026-05-07
**Status**: Draft
**Input**: User description: "Scala API for fraud detection using vector search, focused on high-demanding requests and minimal runtime"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Fraud Score Evaluation (Priority: P1)

The load test engine sends a transaction payload to `POST /fraud-score`.
The API vectorizes the payload into 14 normalized dimensions, queries
the 3M-row reference dataset for the 5 nearest neighbors, computes
`fraud_score = fraud_count / 5`, and responds with
`{ "approved": fraud_score < 0.6, "fraud_score": <value> }`.

**Why this priority**: This is the sole business endpoint. Without it,
the system scores zero. Every point — both latency and detection — flows
through this endpoint.

**Independent Test**: Send a known-label payload, assert the response
matches the expected `approved` and `fraud_score` values derived from
exact KNN (k=5, Euclidean distance) over the reference dataset.

**Acceptance Scenarios**:

1. **Given** the API is running with the full 3M reference dataset loaded,
   **When** a legitimate transaction payload is sent,
   **Then** the response contains `"approved": true` and `fraud_score < 0.6`
   with HTTP 200 within the p99 latency budget.

2. **Given** the API is running with the full 3M reference dataset loaded,
   **When** a fraudulent transaction payload is sent,
   **Then** the response contains `"approved": false` and `fraud_score >= 0.6`
   with HTTP 200 within the p99 latency budget.

3. **Given** a payload where `last_transaction` is `null`,
   **When** the transaction is vectorized,
   **Then** dimensions 5 and 6 MUST be set to `-1` (sentinel value),
   and the KNN search MUST still return correct neighbors.

4. **Given** a payload with `merchant.mcc` not present in `mcc_risk.json`,
   **When** the transaction is vectorized,
   **Then** dimension 12 MUST use the default value `0.5`.

---

### User Story 2 - Readiness Probe (Priority: P2)

The load test engine polls `GET /ready` before sending traffic. The API
MUST respond with HTTP 2xx only after the reference dataset is fully
loaded and the search index is built and ready to serve queries.

**Why this priority**: The test engine relies on this endpoint to know
when to start. If it returns 2xx prematurely, requests arrive before the
index is ready, causing HTTP errors (weight 5 in the scoring formula).

**Independent Test**: Start the container and poll `/ready` until 2xx.
Then immediately send a fraud-score request and verify it succeeds.

**Acceptance Scenarios**:

1. **Given** the container just started and data is still loading,
   **When** `GET /ready` is called,
   **Then** the response is HTTP 503.

2. **Given** the reference dataset and search index are fully loaded,
   **When** `GET /ready` is called,
   **Then** the response is HTTP 200.

---

### User Story 3 - Load-Balanced Multi-Instance Deployment (Priority: P3)

Two API instances sit behind a round-robin load balancer (nginx). Both
instances MUST produce identical results for the same input, and the
total resource consumption MUST stay within 1 CPU + 350 MB RAM.

**Why this priority**: Required by the Rinha rules. Without the correct
topology, the submission is invalid.

**Independent Test**: Deploy via `docker-compose.yml`, send 100 requests,
verify both instances receive traffic (check logs or response headers)
and all responses are correct.

**Acceptance Scenarios**:

1. **Given** the docker-compose stack is running,
   **When** 100 requests are sent to port 9999,
   **Then** both API instances receive approximately 50 requests each
   (round-robin distribution).

2. **Given** the docker-compose stack is running,
   **When** the same payload is sent to both instances directly,
   **Then** both return identical `fraud_score` and `approved` values.

3. **Given** the docker-compose stack is running,
   **When** `docker stats` is checked,
   **Then** the sum of CPU limits is <= 1.0 and sum of memory limits
   is <= 350 MB.

---

### Edge Cases

- **Payload with `transaction.amount` exceeding `max_amount` (10000)**: dimension 0 MUST clamp to 1.0.
- **Payload with `transaction.installments` = 0**: dimension 1 normalizes to 0.0.
- **Payload with `customer.avg_amount` = 0**: `amount / avg_amount` would be infinity; the clamp to [0.0, 1.0] caps it at 1.0.
- **Payload with `last_transaction.timestamp` very close to `transaction.requested_at`**: minutes_since_last_tx approaches 0, normalizes to ~0.0.
- **Payload with `terminal.km_from_home` exceeding `max_km` (1000)**: clamps to 1.0.
- **Payload with `customer.known_merchants` as empty array**: `merchant.id` is always "unknown", dimension 11 = 1.
- **Concurrent burst of requests under load**: the system MUST NOT degrade into HTTP errors; graceful backpressure is preferred over 5xx.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose `POST /fraud-score` accepting the exact JSON schema defined in API.md and returning `{ "approved": bool, "fraud_score": float }` with HTTP 200.
- **FR-002**: System MUST vectorize each transaction into exactly 14 normalized dimensions following the formulas in REGRAS_DE_DETECCAO.md.
- **FR-003**: System MUST apply the `limitar(x)` clamp function (clamping to [0.0, 1.0]) on all dimensions except when the sentinel `-1` applies (dimensions 5 and 6 with null `last_transaction`).
- **FR-004**: System MUST perform K-nearest-neighbor search with k=5 over the 3M reference vectors using a distance metric (Euclidean distance recommended for accuracy parity with the test labeling).
- **FR-005**: System MUST compute `fraud_score = count(label == "fraud" in top-5) / 5` and respond `approved = fraud_score < 0.6`.
- **FR-006**: System MUST load `normalization.json` and `mcc_risk.json` at startup and use their values for vectorization. Unknown MCC codes MUST default to risk `0.5`.
- **FR-007**: System MUST decompress and index `references.json.gz` (3M vectors) at startup, before reporting readiness.
- **FR-008**: System MUST expose `GET /ready` returning HTTP 2xx only after full initialization.
- **FR-009**: System MUST listen on port 9999 (via load balancer).
- **FR-010**: System MUST NOT use test payloads as reference data or lookup tables.

### Non-Functional Requirements

- **NFR-001**: p99 latency MUST be < 10 ms under load (target: < 1 ms for maximum score).
- **NFR-002**: Total resource consumption MUST NOT exceed 1 CPU + 350 MB RAM across all containers.
- **NFR-003**: Failure rate (FP + FN + HTTP errors) MUST stay below 15% (target: < 1%).
- **NFR-004**: Docker images MUST target `linux/amd64`.
- **NFR-005**: Network mode MUST be `bridge`.
- **NFR-006**: Startup time (until `/ready` returns 200) SHOULD be < 30 seconds.
- **NFR-007**: Zero HTTP 5xx errors under sustained load (HTTP errors carry weight 5 in scoring).

### Key Entities

- **Transaction**: The incoming request payload containing transaction details, customer profile, merchant info, terminal data, and optional last_transaction.
- **ReferenceVector**: A pre-labeled 14-dimensional vector from the reference dataset, tagged as `"fraud"` or `"legit"`.
- **NormalizationConstants**: The 7 constants from `normalization.json` used to normalize raw fields into [0.0, 1.0].
- **MccRiskMap**: Mapping from MCC code (string) to risk score (float), from `mcc_risk.json`.
- **FraudDecision**: The output containing `approved` (boolean) and `fraud_score` (float).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Detection accuracy matches exact KNN (k=5, Euclidean, brute force) on the full reference set — 0 FP, 0 FN.
- **SC-002**: p99 latency under load test < 10 ms (score_p99 >= 2000).
- **SC-003**: Zero HTTP errors under load (score_det maximized).
- **SC-004**: Final score > 4000 points (top-tier competitive threshold).
- **SC-005**: Startup completes within 30 seconds on the test hardware (Mac Mini Late 2014, 2.6 GHz, 8 GB RAM).
- **SC-006**: Total memory across all containers stays within 350 MB during the load test.

## Assumptions

- The reference dataset (`references.json.gz`) is static and does not change during the test run. Pre-processing at build time or startup is safe.
- The test labeling was done with exact KNN (k=5, Euclidean, brute force). Using the same metric guarantees detection parity. Using an approximate method introduces detection risk.
- The test hardware is a Mac Mini Late 2014 (2.6 GHz i5, 8 GB RAM, Ubuntu 24.04). The API has access to ~0.4-0.45 CPU per instance.
- Float32 precision (vs. Float64) introduces negligible rounding errors for 14-dimensional Euclidean distance and does not affect KNN neighbor ordering for this dataset.
- JVM startup and GC overhead can be mitigated via GraalVM native-image or careful JVM tuning within the memory budget.
- Nginx is a suitable load balancer given its minimal resource footprint (~5-10 MB).
