# Tasks: Fraud Detection API

**Input**: Design documents from `specs/001-fraud-detection-api/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Single project**: `src/main/scala/rinha/`, `src/test/scala/rinha/` at repository root

---

## Phase 1: Setup

**Purpose**: Project initialization, build tooling, and formatting configuration

- [X] T001 Create sbt project structure: `build.sbt`, `project/build.properties`, `project/plugins.sbt` with Scala 3.x, http4s (Netty backend), jsoniter-scala, munit dependencies
- [X] T002 [P] Create `.scalafmt.conf` with project formatting rules
- [X] T003 [P] Update `.gitignore` to include `target/`, `.idea/`, `.bsp/`, `.env`, `.DS_Store`, `metals.sbt`, `.metals/`
- [X] T004 [P] Create directory structure: `src/main/scala/rinha/domain/`, `src/main/scala/rinha/application/`, `src/main/scala/rinha/infrastructure/search/`, `src/main/scala/rinha/infrastructure/http/`, `src/main/scala/rinha/infrastructure/json/`, `src/main/scala/rinha/infrastructure/loader/`, `src/test/scala/rinha/domain/`, `src/test/scala/rinha/application/`, `src/test/scala/rinha/infrastructure/`
- [X] T005 [P] Download reference data files into `resources/`: `references.json.gz`, `mcc_risk.json`, `normalization.json` from the competition repository

**Checkpoint**: Project compiles with `sbt compile`, formatting enforced.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain entities and core abstractions that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 Create `NormalizationConstants` case class in `src/main/scala/rinha/domain/NormalizationConstants.scala` with the 7 normalization fields (maxAmount, maxInstallments, amountVsAvgRatio, maxMinutes, maxKm, maxTxCount24h, maxMerchantAvgAmount)
- [ ] T007 [P] Create `MccRiskMap` opaque type in `src/main/scala/rinha/domain/MccRiskMap.scala` with `riskFor(mcc: String): Float` method and default risk 0.5
- [ ] T008 [P] Create `Label` enum (`Fraud`, `Legit`) in `src/main/scala/rinha/domain/Label.scala`
- [ ] T009 [P] Create `FraudDecision` case class in `src/main/scala/rinha/domain/FraudDecision.scala` with `approved: Boolean` and `fraudScore: Float`
- [ ] T010 Create `TransactionRequest` and nested case classes (`TransactionData`, `CustomerData`, `MerchantData`, `TerminalData`, `LastTransactionData`) in `src/main/scala/rinha/domain/Transaction.scala` with `lastTransaction: Option[LastTransactionData]`
- [ ] T011 Create `Neighbor` case class in `src/main/scala/rinha/domain/Neighbor.scala` with `index: Int`, `distanceSq: Float`, `label: Label`
- [ ] T012 Create `VectorSearchPort` trait in `src/main/scala/rinha/application/VectorSearchPort.scala` defining `findKNearest(query: Array[Float], k: Int): List[Neighbor]`
- [ ] T013 Create `ConfigLoader` in `src/main/scala/rinha/infrastructure/loader/ConfigLoader.scala` to parse `normalization.json` → `NormalizationConstants` and `mcc_risk.json` → `MccRiskMap`

**Checkpoint**: All domain types compile, `ConfigLoader` loads both JSON files successfully.

---

## Phase 3: User Story 1 — Fraud Score Evaluation (Priority: P1)

**Goal**: Accept a transaction payload via `POST /fraud-score`, vectorize it, search 3M reference vectors for the 5 nearest neighbors, and return `{ approved, fraud_score }`.

**Independent Test**: Send known-label payloads (one legit, one fraud) and assert correct `approved` and `fraud_score` values.

### Implementation for User Story 1

- [ ] T014 [US1] Implement `Vectorizer.vectorize()` pure function in `src/main/scala/rinha/domain/Vectorizer.scala` — transforms `TransactionRequest` + `NormalizationConstants` + `MccRiskMap` into `Array[Float]` of 14 dimensions with clamp and sentinel -1 logic
- [ ] T015 [US1] Implement unit tests for `Vectorizer` in `src/test/scala/rinha/domain/VectorizerSpec.scala` — cover legit transaction, fraud transaction, null last_transaction (sentinel -1), unknown MCC (default 0.5), clamping edge cases (amount > max, installments = 0, avg_amount = 0)
- [ ] T016 [US1] Implement `VPTree` data structure in `src/main/scala/rinha/infrastructure/search/VPTree.scala` — build from flat `Array[Float]` + `BitSet` labels, support `searchKNN(query, k)` returning `List[Neighbor]`, use squared Euclidean distance with early termination, flat array-based tree (no per-node objects)
- [ ] T017 [US1] Implement unit tests for `VPTree` in `src/test/scala/rinha/infrastructure/search/VPTreeSpec.scala` — verify KNN correctness against brute-force baseline on a small dataset (~1000 vectors), test k=5, test sentinel -1 handling
- [ ] T018 [US1] Implement `VPTreeSearchAdapter` in `src/main/scala/rinha/infrastructure/search/VPTreeSearchAdapter.scala` — implements `VectorSearchPort`, wraps `VPTree` instance
- [ ] T019 [US1] Implement `ReferenceDataLoader` in `src/main/scala/rinha/infrastructure/loader/ReferenceDataLoader.scala` — decompress `references.json.gz`, parse 3M `{vector, label}` records into flat `Array[Float]` + `BitSet`, build `VPTree`
- [ ] T020 [US1] Implement `FraudScoreUseCase` in `src/main/scala/rinha/application/FraudScoreUseCase.scala` — orchestrates: vectorize(tx) → searchPort.findKNearest(vector, 5) → count frauds → compute score → return FraudDecision
- [ ] T021 [US1] Implement unit tests for `FraudScoreUseCase` in `src/test/scala/rinha/application/FraudScoreUseCaseSpec.scala` — mock `VectorSearchPort`, verify score computation (0/5=0.0→approved, 2/5=0.4→approved, 3/5=0.6→denied, 5/5=1.0→denied)
- [ ] T022 [US1] Implement `TransactionDecoder` in `src/main/scala/rinha/infrastructure/json/TransactionDecoder.scala` — jsoniter-scala codec for `TransactionRequest` with snake_case field mapping, null `last_transaction` → `None`
- [ ] T023 [US1] Implement `FraudDecisionEncoder` in `src/main/scala/rinha/infrastructure/json/FraudDecisionEncoder.scala` — jsoniter-scala codec for `FraudDecision` response serialization
- [ ] T024 [US1] Implement `FraudScoreRoute` in `src/main/scala/rinha/infrastructure/http/FraudScoreRoute.scala` — `POST /fraud-score` handler: decode JSON → call FraudScoreUseCase → encode response. On parse error, return safe default `{ "approved": true, "fraud_score": 0.0 }` with HTTP 200
- [ ] T025 [US1] Implement integration test in `src/test/scala/rinha/infrastructure/json/TransactionDecoderSpec.scala` — parse the example payloads from API.md (legit tx with null last_transaction, fraud tx), verify all fields decoded correctly

**Checkpoint**: `POST /fraud-score` returns correct results for both legit and fraud payloads. Vectorizer, VP-Tree, and use case all unit-tested.

---

## Phase 4: User Story 2 — Readiness Probe (Priority: P2)

**Goal**: Expose `GET /ready` that returns 503 during startup and 200 once the reference dataset and VP-Tree index are fully loaded.

**Independent Test**: Start the app, poll `/ready` until 200, then immediately send a fraud-score request and verify success.

### Implementation for User Story 2

- [ ] T026 [US2] Implement `ReadyRoute` in `src/main/scala/rinha/infrastructure/http/ReadyRoute.scala` — `GET /ready` handler: return 200 if data loaded, 503 otherwise. Use an `AtomicBoolean` or similar readiness flag
- [ ] T027 [US2] Implement `HttpServer` in `src/main/scala/rinha/infrastructure/http/HttpServer.scala` — http4s server with Netty backend, combine `FraudScoreRoute` + `ReadyRoute`, bind to port 8080
- [ ] T028 [US2] Implement `Main.scala` in `src/main/scala/rinha/Main.scala` — wiring: load config → load references → build VP-Tree → create use case → create routes → start HTTP server → set ready flag
- [ ] T029 [US2] Implement integration test for startup and readiness in `src/test/scala/rinha/infrastructure/http/ReadyRouteSpec.scala` — verify `/ready` returns 503 before init, 200 after init

**Checkpoint**: Application starts, `/ready` transitions from 503 to 200, `POST /fraud-score` works after readiness.

---

## Phase 5: User Story 3 — Load-Balanced Multi-Instance Deployment (Priority: P3)

**Goal**: Deploy 2 API instances behind nginx round-robin on port 9999, within 1 CPU + 350 MB RAM.

**Independent Test**: `docker compose up`, send 100 requests to port 9999, verify both instances receive traffic and all responses are correct.

### Implementation for User Story 3

- [ ] T030 [US3] Create `nginx.conf` in project root — upstream with `api1:8080` and `api2:8080`, listen on 9999, round-robin, `keepalive 64`
- [ ] T031 [US3] Create `Dockerfile` in project root — multi-stage build: Stage 1 (sbt compile + assembly/native-image), Stage 2 (minimal runtime with JRE or distroless), copy fat JAR or native binary + reference data files
- [ ] T032 [US3] Create `docker-compose.yml` in project root — nginx (0.10 CPU, 10 MB), api1 (0.45 CPU, 170 MB), api2 (0.45 CPU, 170 MB), port 9999, bridge network, healthcheck on `/ready`
- [ ] T033 [US3] Add `info.json` in project root with participant info, stack (`scala`, `nginx`), and social links
- [ ] T034 [US3] Validate deployment end-to-end: `docker compose up`, wait for `/ready`, send test payloads to port 9999, verify correct responses, check `docker stats` for resource compliance

**Checkpoint**: Full stack runs via `docker compose up`, both instances serve traffic, resource limits respected.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Performance optimization, memory tuning, and submission readiness

- [ ] T035 [P] Create pre-processing tool in `src/main/scala/rinha/tools/IndexBuilder.scala` — standalone app that reads `references.json.gz`, builds VP-Tree, serializes to binary format (`vectors.bin`, `labels.bin`, `vptree.bin`) for fast startup via mmap
- [ ] T036 [P] Update `ReferenceDataLoader` to support loading pre-built binary index files (mmap path) as alternative to JSON parsing, in `src/main/scala/rinha/infrastructure/loader/ReferenceDataLoader.scala`
- [ ] T037 [P] Update `Dockerfile` to add a pre-processing build stage that runs `IndexBuilder` to produce binary index files baked into the image
- [ ] T038 Memory optimization: profile heap usage per container with `docker stats`, tune JVM flags (`-Xms`, `-Xmx`, `-XX:MaxGCPauseMillis=1`, `-XX:+AlwaysPreTouch`), verify total stays within 350 MB
- [ ] T039 [P] Add GraalVM native-image support: add `sbt-native-image` plugin to `project/plugins.sbt`, configure reflection and resource hints, test build
- [ ] T040 Performance benchmarking: run k6 load test from `test/` directory, measure p99 latency, identify bottlenecks, tune nginx (`worker_connections`, `proxy_buffering off`)
- [ ] T041 Float32 validation: write a build-time validation script that compares VP-Tree KNN results (Float32) against brute-force (Float64) on 1000 sample queries, assert 100% neighbor ordering match
- [ ] T042 [P] Update `README.md` with project description, build instructions, and architecture overview
- [ ] T043 Prepare `submission` branch: create branch with only `docker-compose.yml`, `nginx.conf`, `info.json`, and pre-built Docker image reference

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational — core fraud detection logic
- **User Story 2 (Phase 4)**: Depends on US1 (needs FraudScoreRoute + data loading)
- **User Story 3 (Phase 5)**: Depends on US2 (needs working app with /ready endpoint)
- **Polish (Phase 6)**: Depends on US3 (needs full stack running)

### Within Each User Story

- Domain entities before services
- Services before routes
- VP-Tree before adapter before use case
- Core implementation before integration
- Unit tests alongside implementation

### Parallel Opportunities

**Phase 1** (all tasks parallelizable):
```
T001 (sbt) | T002 (scalafmt) | T003 (gitignore) | T004 (dirs) | T005 (data)
```

**Phase 2** (after T006):
```
T006 (NormConstants)
  → T007 (MccRiskMap) | T008 (Label) | T009 (FraudDecision) | T010 (Transaction)
  → T011 (Neighbor) | T012 (VectorSearchPort)
  → T013 (ConfigLoader)
```

**Phase 3** (US1 — sequential core, parallel periphery):
```
T014 (Vectorizer) → T015 (Vectorizer tests)
T016 (VPTree) → T017 (VPTree tests) → T018 (Adapter)
T019 (DataLoader)
  → T020 (FraudScoreUseCase) → T021 (UseCase tests)
T022 (JSON decoder) | T023 (JSON encoder) → T024 (Route) → T025 (integration test)
```

**Phase 6** (all marked [P] can run in parallel):
```
T035 (IndexBuilder) | T036 (mmap loader) | T039 (native-image) | T042 (README)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational entities
3. Complete Phase 3: User Story 1 (fraud detection works)
4. **STOP and VALIDATE**: Test with example payloads from API.md
5. Core value delivered — fraud detection returns correct results

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → Fraud detection works → Validate accuracy
3. Add User Story 2 → Readiness probe + full app startup → Validate lifecycle
4. Add User Story 3 → Docker deployment → Validate resource limits
5. Polish → Optimize for competition score

### Critical Path

```
T001 → T006 → T010 → T014 → T016 → T019 → T020 → T024 → T027 → T028 → T031 → T032 → T040
setup   domain  tx     vec    vptree  loader  usecase route   server  main    docker  compose  bench
```

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group (explicit `git add` per file, never `git add .`)
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
