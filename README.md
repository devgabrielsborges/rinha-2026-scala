# Rinha de Backend 2026 — Scala

Fraud detection API built with Scala 3, http4s, and a custom Vantage-Point Tree for real-time KNN classification. Two load-balanced instances behind nginx, running within **1 CPU / 350 MB**.

## Architecture

```
nginx:9999 (round-robin)
  ├─ api1:8080
  └─ api2:8080
```

Each API instance:
1. Receives a `POST /fraud-score` with transaction data
2. Vectorizes the transaction into 14 normalized dimensions
3. Finds the 5 nearest neighbors via VP-Tree (Euclidean distance)
4. Returns `fraud_score` (ratio of fraud neighbors) and `approved` flag

The VP-Tree index is **pre-built at Docker image build time** and loaded at runtime via **memory-mapped files** (`mmap`). Both containers share the same physical pages through the overlay2 page cache, keeping total memory under 350 MB.

## Stack

| Component | Technology |
|-----------|-----------|
| Language | Scala 3.3.7 (JDK 21) |
| HTTP | http4s 0.23 + Netty |
| JSON | jsoniter-scala |
| Search | Custom VP-Tree (mmap binary index) |
| Load balancer | nginx (round-robin) |
| Runtime | eclipse-temurin:21-jre-alpine |

## Quick Start

```bash
# Build and run (requires Docker)
docker compose up -d

# Wait for health checks, then test
curl http://localhost:9999/ready
curl -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{"mcc":"5411","merchant_id":"m1","lat":-23.5505,"lng":-46.6333,"amount":150.00,"transaction_time":"2024-01-15T10:30:00","card_number":"1234567890123456","first_name":"Gabriel","last_name":"Borges","timestamp":"2024-01-15T10:30:00"}'
```

## Local Development

```bash
# Prerequisites: JDK 21, sbt 1.10.x
# Place reference data in resources/
cp .env.example .env

# Run tests
sbt test

# Run locally (JSON fallback, no mmap)
sbt run
```

## Project Structure

```
src/main/scala/rinha/
├── Main.scala                          # Entry point, wiring
├── domain/                             # Entities, value objects
│   ├── Transaction.scala
│   ├── FraudDecision.scala
│   ├── Vectorizer.scala
│   └── ...
├── application/                        # Use cases, ports
│   ├── FraudScoreUseCase.scala
│   └── VectorSearchPort.scala
├── infrastructure/
│   ├── http/                           # Routes, server
│   │   ├── FraudScoreRoute.scala
│   │   ├── ReadyRoute.scala
│   │   └── HttpServer.scala
│   ├── search/                         # VP-Tree implementation
│   │   ├── VPTree.scala
│   │   └── VPTreeSearchAdapter.scala
│   ├── loader/                         # Data loading
│   │   ├── ReferenceDataLoader.scala
│   │   ├── BinaryIndexLoader.scala
│   │   ├── ConfigLoader.scala
│   │   └── Env.scala
│   └── json/                           # Codecs
│       ├── TransactionDecoder.scala
│       └── FraudDecisionEncoder.scala
└── tools/
    └── IndexBuilder.scala              # Build-time index generator
```

## Memory Budget (350 MB total)

| Service | CPU | Memory Limit |
|---------|-----|-------------|
| nginx | 0.10 | 10 MB |
| api1 | 0.45 | 170 MB |
| api2 | 0.45 | 170 MB |

Actual API instance usage is ~105 MiB. The binary index (~192 MB of float/int arrays) lives in mmap'd pages shared between containers through the kernel page cache.
