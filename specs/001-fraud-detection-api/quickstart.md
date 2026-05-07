# Quickstart: Fraud Detection API

## Prerequisites

- JDK 21+ (GraalVM recommended for native-image)
- sbt 1.x
- Docker + Docker Compose
- Reference data files in `resources/`:
  - `references.json.gz` (~16 MB)
  - `mcc_risk.json`
  - `normalization.json`

## Local Development

### 1. Clone and setup

```bash
git clone <repo-url>
cd rinha-2026-scala
```

### 2. Build and test

```bash
sbt compile
sbt test
```

### 3. Run locally (single instance)

```bash
sbt run
```

The API starts on port 8080. Test with:

```bash
# Check readiness
curl -s http://localhost:8080/ready

# Send a fraud-score request
curl -s -X POST http://localhost:8080/fraud-score \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-test-001",
    "transaction": { "amount": 41.12, "installments": 2, "requested_at": "2026-03-11T18:45:53Z" },
    "customer": { "avg_amount": 82.24, "tx_count_24h": 3, "known_merchants": ["MERC-003", "MERC-016"] },
    "merchant": { "id": "MERC-016", "mcc": "5411", "avg_amount": 60.25 },
    "terminal": { "is_online": false, "card_present": true, "km_from_home": 29.23 },
    "last_transaction": null
  }'
```

Expected response:
```json
{ "approved": true, "fraud_score": 0.0 }
```

## Docker Deployment

### 1. Build the Docker image

```bash
docker build -t rinha-2026-scala:latest --platform linux/amd64 .
```

### 2. Run the full stack

```bash
docker compose up -d
```

This starts:
- nginx (load balancer) on port 9999
- api1 on port 8080 (internal)
- api2 on port 8080 (internal)

### 3. Verify

```bash
# Wait for readiness
until curl -s -o /dev/null -w "%{http_code}" http://localhost:9999/ready | grep -q 200; do
  sleep 1
done
echo "API is ready"

# Test fraud detection
curl -s -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-test-001",
    "transaction": { "amount": 9505.97, "installments": 10, "requested_at": "2026-03-14T05:15:12Z" },
    "customer": { "avg_amount": 81.28, "tx_count_24h": 20, "known_merchants": ["MERC-008", "MERC-007", "MERC-005"] },
    "merchant": { "id": "MERC-068", "mcc": "7802", "avg_amount": 54.86 },
    "terminal": { "is_online": false, "card_present": true, "km_from_home": 952.27 },
    "last_transaction": null
  }'
```

Expected response (fraudulent transaction):
```json
{ "approved": false, "fraud_score": 1.0 }
```

### 4. Check resource usage

```bash
docker stats --no-stream
```

Verify: total CPU <= 1.0, total memory <= 350 MB.

### 5. Run the load test

```bash
# Install k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/)
k6 run test/test-script.js
```

## Project Structure

```
src/main/scala/rinha/
├── domain/          # Pure domain logic (no framework deps)
├── application/     # Use cases and port traits
├── infrastructure/  # HTTP, JSON, VP-Tree, data loading
└── Main.scala       # Wiring and startup

src/test/scala/rinha/
├── domain/          # Unit tests for vectorizer
├── application/     # Use case tests
└── infrastructure/  # Integration tests
```

## Key Commands

| Command | Description |
|---------|-------------|
| `sbt compile` | Compile the project |
| `sbt test` | Run all tests |
| `sbt run` | Run locally (single instance) |
| `sbt assembly` | Build fat JAR |
| `sbt nativeImage` | Build GraalVM native binary (if configured) |
| `docker compose up -d` | Start full stack (nginx + 2 APIs) |
| `docker compose down` | Stop all containers |
| `docker compose logs -f` | Follow container logs |
