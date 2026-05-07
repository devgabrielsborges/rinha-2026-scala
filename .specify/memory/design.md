# System Design: Fraud Detection API (Rinha de Backend 2026)

**Created**: 2026-05-07
**Status**: Draft
**Related Spec**: specs/001-fraud-detection-api/spec.md

---

## 1. Overview

### 1.1 Purpose

A real-time fraud detection API that transforms credit card transaction
payloads into 14-dimensional vectors, searches 3 million pre-labeled
reference vectors for the 5 nearest neighbors, and returns a fraud
score with an approve/deny decision. Designed for sub-millisecond p99
latency under extreme resource constraints (1 CPU, 350 MB RAM total).

### 1.2 Scope

- **In scope**: HTTP API (`POST /fraud-score`, `GET /ready`), transaction
  vectorization, KNN search over reference dataset, Docker deployment
  with load balancer, build-time data pre-processing.
- **Out of scope**: Model training/retraining, data collection pipelines,
  monitoring dashboards, authentication/authorization, the card
  authorization system itself.

### 1.3 Key Design Goals

| Priority | Goal | Rationale |
|----------|------|-----------|
| P0 | p99 < 1 ms under load | Each 10x latency improvement = +1000 score points. Saturates at 1 ms = +3000. |
| P0 | 0% detection error rate | Exact KNN parity with test labeling eliminates FP/FN/Err. Max detection score = +3000. |
| P1 | Fit within 1 CPU + 350 MB RAM | Hard constraint from the competition rules. Disqualified if exceeded. |
| P1 | Startup < 30 s | Must pass `/ready` check before test timeout on Mac Mini Late 2014. |
| P2 | Minimal runtime overhead | GraalVM native-image eliminates JVM warmup, reduces baseline memory, and provides predictable latency. |

## 2. Architecture

### 2.1 High-Level Diagram

```
                    ┌─────────────────────────┐
                    │      Load Balancer       │
                    │   (nginx, round-robin)   │
                    │      port 9999           │
                    │   cpu: 0.10, mem: 10MB   │
                    └──────┬──────────┬────────┘
                           │          │
               ┌───────────▼──┐  ┌────▼──────────┐
               │   API #1     │  │   API #2       │
               │  port 8080   │  │  port 8080     │
               │  cpu: 0.45   │  │  cpu: 0.45     │
               │  mem: 170MB  │  │  mem: 170MB    │
               └──────────────┘  └────────────────┘

Each API instance contains:
┌──────────────────────────────────────────────────┐
│                  API Instance                     │
│                                                   │
│  ┌─────────────┐   ┌──────────────────────────┐  │
│  │ HTTP Server  │──▶│ FraudScoreUseCase        │  │
│  │ (Netty)      │   │  vectorize() → search()  │  │
│  └─────────────┘   └──────────┬───────────────┘  │
│                               │                   │
│                    ┌──────────▼───────────────┐   │
│                    │ VectorSearchPort (trait)  │   │
│                    └──────────┬───────────────┘   │
│                               │                   │
│                    ┌──────────▼───────────────┐   │
│                    │ VPTreeSearchAdapter       │   │
│                    │ (3M vectors, in-memory)   │   │
│                    └─────────────────────────┘    │
└──────────────────────────────────────────────────┘
```

### 2.2 Component Inventory

| Component | Responsibility | Technology |
|-----------|---------------|------------|
| Load Balancer | Round-robin distribution to 2 API instances | nginx (alpine) |
| HTTP Server | Accept JSON requests, route to use case, serialize responses | Netty (via http4s or direct) |
| FraudScoreUseCase | Orchestrate vectorization + search + decision | Pure Scala (application layer) |
| TransactionVectorizer | Transform raw payload into 14D normalized vector | Pure Scala (domain service) |
| VectorSearchPort | Trait defining `findKNearest(vector, k): List[Neighbor]` | Scala trait (port) |
| VPTreeSearchAdapter | VP-Tree implementation for exact KNN over 3M vectors | Scala + off-heap memory (adapter) |
| ReferenceDataLoader | Decompress + parse `references.json.gz` at startup | Scala (infrastructure) |
| ConfigLoader | Load `normalization.json` and `mcc_risk.json` | Scala (infrastructure) |

### 2.3 Data Flow

```
Request JSON
    │
    ▼
1. HTTP Server (Netty) ─── deserialize JSON into Transaction case class
    │
    ▼
2. FraudScoreUseCase
    │
    ├──▶ 3. TransactionVectorizer.vectorize(tx, normConstants, mccRisk)
    │         └──▶ Array[Float] of 14 dimensions (clamped, normalized)
    │
    ├──▶ 4. VectorSearchPort.findKNearest(vector, k=5)
    │         └──▶ VP-Tree traversal → 5 nearest (vector, label) pairs
    │
    └──▶ 5. Compute fraud_score = fraudCount / 5
             approved = fraud_score < 0.6
    │
    ▼
6. HTTP Server ─── serialize FraudDecision to JSON → HTTP 200
```

## 3. Data Architecture

### 3.1 Data Sources

| Source | Format | Volume | Lifecycle |
|--------|--------|--------|-----------|
| `references.json.gz` | Gzipped JSON array of {vector, label} | ~16 MB compressed, ~284 MB raw, 3M records | Static — loaded once at startup |
| `mcc_risk.json` | JSON object (MCC → risk float) | <1 KB, 10 entries | Static — loaded once at startup |
| `normalization.json` | JSON object (7 constants) | <1 KB | Static — loaded once at startup |

### 3.2 In-Memory Storage Strategy

The entire reference dataset MUST fit in memory. With 350 MB total and
2 instances, each instance has ~170 MB. Memory breakdown per instance:

| Component | Estimated Size | Strategy |
|-----------|---------------|----------|
| 3M vectors (14 × Float32) | ~168 MB | Flat `Array[Float]` in contiguous off-heap memory via `ByteBuffer.allocateDirect` or `Unsafe` |
| 3M labels (1 bit each) | ~375 KB | `BitSet` — 0 for legit, 1 for fraud |
| VP-Tree nodes (3M) | ~24 MB | Array-based implicit tree (no object allocation per node) |
| HTTP server + framework | ~5 MB | Netty with minimal dependencies |
| Native binary baseline | ~10 MB | GraalVM native-image static binary |
| **Total per instance** | **~207 MB** | **Over budget — requires optimization** |

**Memory optimization strategies** (pick until budget is met):

1. **Build-time pre-processing**: Parse JSON and serialize vectors into
   a compact binary format during Docker build. At startup, load the
   binary directly via memory-mapped file — skips JSON parsing entirely
   and avoids allocating intermediate objects.

2. **Shared memory volume**: Mount a `tmpfs` volume with the pre-built
   binary index. Both API instances `mmap` the same physical pages.
   Shared pages count once against the host memory but the container
   `memory` limit applies per-container to anonymous (heap) pages only.
   This brings per-instance data overhead to near zero.

3. **Quantization to Float16 / Int8**: Reduce vector storage from 4
   bytes to 2 or 1 byte per dimension. 3M × 14 × 2 = 84 MB (Float16)
   or 3M × 14 × 1 = 42 MB (Int8). Precision loss is acceptable for
   14D Euclidean distance where values are already in [−1, 1].

4. **VP-Tree as flat array**: Store the tree structure as parallel
   arrays (vantage point index, median distance, left/right child
   offsets) instead of object nodes. Eliminates per-node object headers
   (~16 bytes/node on JVM).

**Recommended approach**: Combine (1) + (2). Pre-build the VP-Tree
binary index in a Docker build stage, store it in a shared volume, and
`mmap` from both instances. This keeps each container's heap under
50 MB while the actual data lives in shared kernel page cache.

### 3.3 Pre-Processing Pipeline (Build-Time)

```
Docker build stage:
  references.json.gz
      │
      ▼
  1. Decompress (gzip → JSON)
      │
      ▼
  2. Parse 3M {vector, label} records
      │
      ▼
  3. Build VP-Tree index
      │
      ▼
  4. Serialize to compact binary format:
     ├── vectors.bin  (3M × 14 × 4 bytes = 168 MB flat Float32 array)
     ├── labels.bin   (3M bits = 375 KB BitSet)
     └── vptree.bin   (tree structure as flat arrays)
      │
      ▼
  5. Copy binary files to runtime image
```

At runtime startup, the API `mmap`s the binary files — no parsing, no
allocation, no GC pressure. The `/ready` endpoint returns 200 once the
`mmap` is verified.

## 4. Search Algorithm: VP-Tree (Vantage-Point Tree)

### 4.1 Why VP-Tree

| Criterion | Brute Force | KD-Tree | VP-Tree | HNSW (ANN) |
|-----------|------------|---------|---------|-------------|
| Complexity per query | O(N × D) | O(N^(1−1/D)) — degrades at D=14 | O(log N) average | O(log N) |
| Exactness | Exact | Exact | Exact | Approximate |
| Memory overhead | None | O(N) | O(N) | O(N × M) — M=16+ |
| Build time | None | O(N log N) | O(N log N) | O(N log N) |
| Implementation complexity | Trivial | Moderate | Moderate | High |

VP-Tree provides **exact** KNN in **O(log N)** average time for metric
spaces. With 14 dimensions this is ~21 distance computations per query
(log2(3M) ≈ 21.5) in the best case, vs. 3M for brute force. Worst case
degrades but empirically stays under 10% of N for well-distributed data.

Exactness eliminates detection error risk — critical because FN costs
3x and HTTP errors cost 5x in the scoring formula.

### 4.2 VP-Tree Construction

```
function buildVPTree(points, start, end):
    if start >= end: return LEAF
    vantage = points[start]  // or random selection
    compute distances from vantage to all points[start+1..end]
    median = median of distances
    partition points around median (left = closer, right = farther)
    return Node(vantage, median, left=build(closer), right=build(farther))
```

The tree is built at Docker build time and serialized to binary. No
runtime construction cost.

### 4.3 VP-Tree KNN Query

```
function searchKNN(node, query, k, heap):
    dist = euclidean(query, node.vantage)
    heap.tryInsert(dist, node.vantage)
    tau = heap.maxDistance()  // current k-th nearest distance

    if dist < node.median:
        search(node.left, query, k, heap)   // closer side first
        if dist + tau >= node.median:
            search(node.right, query, k, heap)  // prune if possible
    else:
        search(node.right, query, k, heap)  // farther side first
        if dist - tau <= node.median:
            search(node.left, query, k, heap)   // prune if possible
```

The pruning condition `dist ± tau vs. median` eliminates entire subtrees.
With k=5 and 3M points, empirical testing shows 99%+ of the tree is
pruned per query.

### 4.4 Distance Computation

14-dimensional Euclidean distance with early termination:

```scala
def euclideanDistSq(a: Array[Float], aOff: Int,
                    b: Array[Float], bOff: Int,
                    dims: Int, bound: Float): Float = {
  var sum = 0.0f
  var i = 0
  while (i < dims) {
    val diff = a(aOff + i) - b(bOff + i)
    sum += diff * diff
    if (sum > bound) return sum  // early exit
    i += 1
  }
  sum
}
```

Using squared distance avoids the `sqrt` call entirely — ordering is
preserved. Early termination further reduces compute when the candidate
is clearly worse than the current k-th nearest.

## 5. Serving Architecture

### 5.1 Inference Pattern

- [x] Real-time (synchronous API)

Single synchronous request-response. No batching, no streaming, no
queuing. Each request is independent — the fraud score depends only on
the static reference dataset plus the incoming payload.

### 5.2 HTTP Server Selection

**Option A: http4s + Ember/Blaze (Cats Effect)**
- Mature, idiomatic Scala, excellent composability.
- Overhead: Cats Effect runtime, Fiber scheduling, codec abstractions.
- Risk: GC pressure from Cats Effect allocations at high throughput.

**Option B: http4s + Netty backend**
- Same API surface as Option A but backed by Netty's event loop.
- Lower latency at the transport layer but still Cats Effect overhead.

**Option C: Raw Netty with manual routing**
- Minimal overhead: zero framework allocations on the hot path.
- Direct ByteBuf → parse → compute → write response.
- Downside: more boilerplate, less idiomatic Scala.

**Option D: Undertow / Vert.x**
- High-performance HTTP servers with low overhead.
- Less idiomatic for Scala but proven in high-perf JVM workloads.

**Recommendation**: Start with **Option B** (http4s + Netty). It
provides the best balance of idiomatic Scala and performance. If
profiling shows framework overhead is the bottleneck, drop to
**Option C** (raw Netty). The Clean Architecture boundary (use case
layer) is the same in both cases — only the adapter changes.

### 5.3 JSON Handling

The request payload is fixed-schema. Avoid reflection-based JSON
libraries:

- **Preferred**: hand-written parser using `jackson-core` streaming API.
  Zero intermediate AST, zero reflection, zero case-class derivation
  overhead. Parse directly into a pre-allocated `Array[Float]`.
- **Alternative**: `jsoniter-scala` — compile-time codec generation,
  near-zero overhead, idiomatic Scala integration.
- **Avoid**: `circe` auto-derivation (recursive implicit resolution,
  intermediate `Json` AST allocation).

### 5.4 Scaling & Load Balancing

```nginx
upstream api {
    server api1:8080;
    server api2:8080;
}

server {
    listen 9999;
    location / {
        proxy_pass http://api;
    }
}
```

- **Load balancer**: nginx:alpine (~5 MB image, ~10 MB runtime).
- **Strategy**: Default round-robin. No session affinity needed.
- **Connection reuse**: `keepalive 64` in the upstream block to avoid
  connection setup overhead per request.

### 5.5 Resource Allocation

| Service | CPU | Memory | Justification |
|---------|-----|--------|---------------|
| nginx | 0.10 | 10 MB | Minimal — proxy only, no TLS, no buffering |
| api1 | 0.45 | 170 MB | Vector search + HTTP serving |
| api2 | 0.45 | 170 MB | Vector search + HTTP serving |
| **Total** | **1.00** | **350 MB** | Matches competition budget exactly |

With the shared mmap approach, actual anonymous memory per API instance
drops to ~30-50 MB (binary + stack + HTTP buffers). The 170 MB limit
provides headroom for the mmap'd files which are backed by the shared
page cache.

## 6. Runtime Strategy: GraalVM Native Image

### 6.1 Why Native Image

| Concern | JVM (HotSpot) | GraalVM Native |
|---------|--------------|----------------|
| Startup time | 2-5 seconds (class loading, JIT warmup) | ~50 ms |
| Baseline memory | 30-50 MB (metaspace, JIT compiler, class data) | 5-10 MB |
| Warmup latency | First 1000 requests have high p99 (interpreter → JIT) | No warmup — AOT compiled |
| Peak throughput | Higher (JIT profile-guided optimization) | Slightly lower (no PGO by default) |
| GC pauses | G1/ZGC can cause tail latency spikes | Serial/Epsilon GC or manual memory |

For this workload (sub-ms p99, tight memory, short test runs where JIT
warmup matters), native-image wins on every dimension except peak
throughput — which is not the bottleneck (KNN search is).

### 6.2 Native Image Configuration

- **GC**: Use Epsilon GC (no-op collector) if allocation rate is near
  zero on the hot path (which it should be with pre-allocated buffers
  and off-heap vectors). Fall back to Serial GC if Epsilon causes OOM.
- **Build flags**: `--no-fallback`, `--static` (if musl-based),
  `-O2`, `--initialize-at-build-time` for all domain classes.
- **Reflection config**: Minimal — only for JSON deserialization if
  needed. Prefer `jsoniter-scala` which generates bytecode at compile
  time and is native-image friendly.

## 7. Reliability

### 7.1 Failure Modes

| Failure | Detection | Impact | Mitigation |
|---------|-----------|--------|------------|
| VP-Tree query returns wrong neighbors | Pre-deployment validation against brute-force baseline | FP/FN detection errors | Run a validation suite of ~1000 labeled payloads at startup; abort if accuracy < 100% |
| Out-of-memory at startup | Container OOM-killed, `/ready` never returns 200 | Test never starts | Profile memory during build; use shared mmap to reduce per-instance footprint |
| Malformed payload (missing fields) | JSON parse error | HTTP 500 (weight 5 in scoring) | Return a safe default `{ "approved": true, "fraud_score": 0.0 }` instead of 500 — FP weight (1) < Err weight (5) |
| Slow response under GC pressure | p99 spike | Score loss on latency | Use Epsilon GC + zero-allocation hot path |
| Nginx connection exhaustion | 502/503 errors | HTTP errors (weight 5) | Tune `worker_connections`, `keepalive` |

### 7.2 Graceful Degradation

If the KNN search ever fails for a single request (should not happen
with static data), the fallback is to respond with
`{ "approved": true, "fraud_score": 0.0 }`. The cost of a single FP
(weight 1) or FN (weight 3) is always less than an HTTP error
(weight 5).

## 8. Clean Architecture Mapping

```
src/main/scala/rinha/
├── domain/                          # Layer 0 — no dependencies
│   ├── Transaction.scala            # case class for parsed payload
│   ├── ReferenceVector.scala        # value object for reference data
│   ├── FraudDecision.scala          # case class (approved, fraud_score)
│   ├── NormalizationConstants.scala # value object for normalization
│   └── Vectorizer.scala             # pure function: Transaction → Array[Float]
│
├── application/                     # Layer 1 — depends only on domain
│   ├── FraudScoreUseCase.scala      # orchestrates vectorize + search + decide
│   └── VectorSearchPort.scala       # trait: findKNearest(vector, k) → List[Neighbor]
│
├── infrastructure/                  # Layer 2 — depends on domain + application
│   ├── search/
│   │   └── VPTreeSearchAdapter.scala # implements VectorSearchPort
│   ├── http/
│   │   ├── HttpServer.scala         # Netty/http4s server setup
│   │   ├── FraudScoreRoute.scala    # POST /fraud-score handler
│   │   └── ReadyRoute.scala         # GET /ready handler
│   ├── json/
│   │   └── TransactionDecoder.scala # streaming JSON → Transaction
│   └── loader/
│       ├── ReferenceDataLoader.scala # load/mmap binary vectors
│       └── ConfigLoader.scala       # load normalization + mcc_risk
│
└── Main.scala                       # wiring + startup
```

### Clean Code Verification Checklist

- [x] Single Responsibility: each component has one clear purpose
- [x] Dependency Inversion: `VectorSearchPort` trait in application layer; `VPTreeSearchAdapter` in infrastructure
- [x] Interface Segregation: `VectorSearchPort` exposes only `findKNearest` — nothing about VP-Tree internals
- [x] Domain layer has zero framework imports
- [x] Configuration as code: `normalization.json`, `mcc_risk.json`, `docker-compose.yml` all version-controlled
- [x] Immutable artifacts: reference dataset and VP-Tree index are read-only after construction

## 9. Docker Build Strategy

### 9.1 Multi-Stage Build

```dockerfile
# Stage 1: SBT build + native-image compilation
FROM ghcr.io/graalvm/native-image:22 AS builder
WORKDIR /app
COPY project/ project/
COPY build.sbt .
RUN sbt update                    # cache dependencies
COPY src/ src/
RUN sbt nativeImage

# Stage 2: Pre-process reference data
FROM builder AS preprocessor
COPY resources/ /data/
RUN /app/target/native-image/preprocess \
    --input /data/references.json.gz \
    --output /data/index/

# Stage 3: Minimal runtime image
FROM gcr.io/distroless/static-debian12
COPY --from=builder /app/target/native-image/api /app/api
COPY --from=preprocessor /data/index/ /data/index/
COPY resources/normalization.json /data/
COPY resources/mcc_risk.json /data/
ENTRYPOINT ["/app/api"]
```

### 9.2 Docker Compose

```yaml
services:
  nginx:
    image: nginx:alpine
    ports:
      - "9999:9999"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      api1:
        condition: service_healthy
      api2:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: "0.10"
          memory: "10MB"

  api1:
    image: ghcr.io/gabrielborges/rinha-2026-scala:latest
    volumes:
      - shared-index:/data/index:ro
    healthcheck:
      test: ["CMD", "/app/api", "--health"]
      interval: 1s
      timeout: 2s
      retries: 30
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "170MB"

  api2:
    image: ghcr.io/gabrielborges/rinha-2026-scala:latest
    volumes:
      - shared-index:/data/index:ro
    healthcheck:
      test: ["CMD", "/app/api", "--health"]
      interval: 1s
      timeout: 2s
      retries: 30
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "170MB"

volumes:
  shared-index:
```

## 10. Performance Budget

| Operation | Target | Strategy |
|-----------|--------|----------|
| JSON deserialization | < 200 μs | Streaming parser, no intermediate AST |
| Vectorization (14 dims) | < 5 μs | Inline arithmetic, no allocations |
| VP-Tree KNN (k=5, 3M pts) | < 500 μs | O(log N) traversal, squared distance, early termination |
| JSON serialization | < 50 μs | Pre-formatted template with number insertion |
| Total per request | < 800 μs | Leaves margin for network + nginx overhead |

## 11. Open Questions

- [ ] Validate Float32 vs Float64 precision: does Float32 produce
  identical KNN neighbor ordering to the brute-force Float64 labeling
  on the full 3M dataset? Run a validation pass during build.
- [ ] Benchmark VP-Tree vs Ball-Tree vs brute-force-with-SIMD on the
  actual dataset to confirm VP-Tree wins at 14 dimensions.
- [ ] Test whether `mmap` shared volumes count against container memory
  limits in Docker's default cgroup v2 configuration.
- [ ] Profile GraalVM native-image vs JVM (with G1GC) for this specific
  workload — native-image's lack of PGO may hurt tight loop throughput.
- [ ] Measure startup time on the Mac Mini Late 2014 with the full 3M
  dataset to confirm < 30 s.
