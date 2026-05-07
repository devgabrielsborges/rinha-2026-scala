# Implementation Plan: Fraud Detection API

**Branch**: `001-fraud-detection-api` | **Date**: 2026-05-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/001-fraud-detection-api/spec.md`

## Summary

Build a real-time fraud detection API in Scala that vectorizes credit
card transaction payloads into 14 normalized dimensions, queries 3M
pre-labeled reference vectors for the 5 nearest neighbors via VP-Tree,
and returns a fraud score decision. Optimized for sub-millisecond p99
latency under 1 CPU + 350 MB RAM.

## Technical Context

**Language/Version**: Scala 3.x (latest LTS) on JVM 21+
**Primary Dependencies**: http4s (Netty backend), jsoniter-scala, GraalVM native-image
**Storage**: In-memory only — flat Array[Float] + VP-Tree index, memory-mapped from pre-built binary files
**Testing**: munit (unit), integration tests via docker-compose + curl/k6
**Target Platform**: Linux server (Docker, linux/amd64)
**Project Type**: Web service (HTTP API)
**Performance Goals**: p99 < 1 ms, 0% detection error rate, final score > 4000
**Constraints**: 1 CPU + 350 MB RAM total (nginx + 2 API instances), bridge networking only
**Scale/Scope**: 3M reference vectors, ~5000 requests per test run

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Clean Architecture | PASS | 3-layer structure: domain (entities, vectorizer) → application (use case, port trait) → infrastructure (VP-Tree adapter, HTTP, JSON). Dependencies point inward. |
| II. Clean Code | PASS | Single-responsibility components, intent-revealing names, no dead code. ScalaDoc on all public APIs. |
| III. Scala Best Practices | PASS | Immutable case classes, sealed trait hierarchies, Option/Either, scalafmt, -Xfatal-warnings. Mutable state only in VP-Tree hot path behind pure interface. |
| IV. Git Discipline | PASS | Explicit `git add` per file (never `git add .`), Conventional Commits, .gitignore covers target/, .idea/, .bsp/, .env. |
| Infrastructure Constraints | PASS | Resource allocation: nginx 0.10 CPU/10 MB + 2 × api 0.45 CPU/170 MB = 1.00 CPU/350 MB. |

## Project Structure

### Documentation (this feature)

```text
specs/001-fraud-detection-api/
├── plan.md              # This file
├── spec.md              # Feature specification
├── design.md            # System design document
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── contracts/           # Phase 1 output (API contracts)
│   └── fraud-score-api.md
└── quickstart.md        # Phase 1 output
```

### Source Code (repository root)

```text
src/main/scala/rinha/
├── domain/
│   ├── Transaction.scala
│   ├── FraudDecision.scala
│   ├── NormalizationConstants.scala
│   ├── MccRiskMap.scala
│   └── Vectorizer.scala
│
├── application/
│   ├── FraudScoreUseCase.scala
│   └── VectorSearchPort.scala
│
├── infrastructure/
│   ├── search/
│   │   └── VPTreeSearchAdapter.scala
│   ├── http/
│   │   ├── HttpServer.scala
│   │   ├── FraudScoreRoute.scala
│   │   └── ReadyRoute.scala
│   ├── json/
│   │   └── TransactionDecoder.scala
│   └── loader/
│       ├── ReferenceDataLoader.scala
│       └── ConfigLoader.scala
│
└── Main.scala

src/test/scala/rinha/
├── domain/
│   └── VectorizerSpec.scala
├── application/
│   └── FraudScoreUseCaseSpec.scala
└── infrastructure/
    ├── search/
    │   └── VPTreeSearchAdapterSpec.scala
    └── json/
        └── TransactionDecoderSpec.scala

project/
├── build.properties
└── plugins.sbt

build.sbt
.scalafmt.conf
docker-compose.yml
nginx.conf
Dockerfile
```

**Structure Decision**: Single-project layout. The API is a monolith
deployed as 2 identical instances behind nginx. No separate frontend
or database service.

## Complexity Tracking

No constitution violations requiring justification.

## Phases & Milestones

### Phase 1: Setup & Foundation

| Task | Estimate | Depends On |
|------|----------|------------|
| Initialize sbt project with Scala 3, dependencies | 1h | — |
| Configure scalafmt, .gitignore, build.sbt | 30m | sbt init |
| Domain entities (Transaction, FraudDecision, NormalizationConstants) | 1h | sbt init |
| Vectorizer (pure function, 14 dimensions) | 2h | Domain entities |
| VectorSearchPort trait | 30m | Domain entities |

### Phase 2: Core Implementation

| Task | Estimate | Depends On |
|------|----------|------------|
| VP-Tree data structure (build + KNN query) | 4h | VectorSearchPort |
| VPTreeSearchAdapter (implements port) | 1h | VP-Tree |
| Reference data loader (decompress + parse JSON) | 2h | — |
| Config loader (normalization.json, mcc_risk.json) | 1h | — |
| FraudScoreUseCase (orchestration) | 1h | Vectorizer + VPTreeSearchAdapter |

### Phase 3: HTTP Layer

| Task | Estimate | Depends On |
|------|----------|------------|
| JSON decoder (jsoniter-scala or streaming) | 2h | Domain entities |
| HTTP server setup (http4s + Netty) | 2h | — |
| POST /fraud-score route | 1h | FraudScoreUseCase + JSON decoder |
| GET /ready route | 30m | Data loader |
| Main.scala (wiring + startup) | 1h | All above |

### Phase 4: Docker & Deployment

| Task | Estimate | Depends On |
|------|----------|------------|
| Dockerfile (multi-stage: build + runtime) | 2h | Main.scala |
| nginx.conf (round-robin, port 9999) | 30m | — |
| docker-compose.yml (resource limits) | 1h | Dockerfile + nginx |
| Pre-processing pipeline (binary index builder) | 3h | VP-Tree |

### Phase 5: Optimization & Validation

| Task | Estimate | Depends On |
|------|----------|------------|
| Memory profiling and optimization | 2h | Docker deployment |
| GraalVM native-image configuration | 3h | Main.scala |
| Accuracy validation (brute-force baseline) | 2h | VP-Tree |
| Load testing with k6 | 2h | Docker deployment |
| Performance tuning (GC, buffer sizes, nginx) | 2h | Load testing |

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Memory budget exceeded (3M × 14 × 4B = 168 MB per instance) | High | Disqualification | Shared mmap volume, quantization, build-time pre-processing |
| VP-Tree accuracy differs from brute-force baseline | Low | Detection errors → score penalty | Validation suite against 1000+ labeled payloads during build |
| GraalVM native-image incompatible with dependencies | Medium | Fall back to JVM (higher memory) | Test native-image early; keep JVM fallback Dockerfile |
| Float32 rounding changes KNN neighbor order | Low | FP/FN detection errors | Run Float32 vs Float64 comparison on full dataset during build |
| Startup > 30s on test hardware | Medium | Test engine timeout | Pre-build binary index in Docker build stage, mmap at runtime |

## Dependencies

| Dependency | Type | Status |
|-----------|------|--------|
| `references.json.gz` (3M vectors) | Data | Available in competition repo `/resources/` |
| `mcc_risk.json` | Data | Available in competition repo `/resources/` |
| `normalization.json` | Data | Available in competition repo `/resources/` |
| GraalVM 22+ with native-image | Infra | Public Docker images available |
| nginx:alpine | Infra | Public Docker Hub image |
| http4s + Netty | Library | Maven Central |
| jsoniter-scala | Library | Maven Central |
| munit | Library | Maven Central |
