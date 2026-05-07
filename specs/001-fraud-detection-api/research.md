# Research: Fraud Detection API

**Date**: 2026-05-07
**Status**: Complete

## R1: VP-Tree vs Alternatives for 14D Exact KNN

**Decision**: VP-Tree (Vantage-Point Tree)

**Rationale**: At 14 dimensions with 3M points and k=5, VP-Tree
provides the best tradeoff:

- **Brute Force** O(N×D): 3M × 14 = 42M float ops per query. At
  ~1 GHz effective throughput on 0.45 CPU, this is ~42 ms — too slow
  for sub-ms p99.
- **KD-Tree**: Degrades toward brute force as dimensionality grows.
  Empirical rule: KD-Tree is effective when D < log2(N). Here
  log2(3M) ≈ 21.5 and D=14, so KD-Tree is borderline. Pruning
  efficiency drops significantly above D=10.
- **VP-Tree**: Works in any metric space (no axis-aligned splits).
  Average query touches O(log N) ≈ 22 nodes. With 14D Euclidean
  distance at ~20 ns per computation, that's ~440 ns best case.
  Worst case degrades to ~10% of N for pathological distributions,
  but the reference dataset is well-distributed (normalized [0,1]
  values with no clustering).
- **Ball-Tree**: Similar to VP-Tree but slightly more memory overhead
  (stores radius per node). Performance comparable. VP-Tree is
  simpler to implement as a flat array.
- **HNSW (ANN)**: O(log N) but approximate. Mismatched neighbors
  cause FP/FN which cost 1-3 points each in the scoring formula.
  Risk of crossing the 15% failure threshold is unacceptable. Would
  need recall > 99.9% to be viable, which requires high `ef` values
  that negate the speed advantage.

**Alternatives considered**: All of the above. VP-Tree was selected
for exact results with O(log N) average performance and minimal
memory overhead.

## R2: Float32 vs Float64 Precision

**Decision**: Float32 (single precision)

**Rationale**: The 14-dimensional vectors have values in [-1.0, 1.0].
Float32 provides ~7 decimal digits of precision. The maximum
accumulated squared error over 14 dimensions is bounded by
14 × 2 × epsilon ≈ 14 × 2 × 1.19e-7 ≈ 3.3e-6, which is orders
of magnitude smaller than the distance differences between
neighboring points in a 3M-point space.

The risk of Float32 changing neighbor ordering is negligible for
this dataset, but MUST be validated empirically during the build
step by comparing Float32 KNN results against Float64 brute-force
on a sample of 1000+ queries.

**Memory savings**: 3M × 14 × 4 = 168 MB (Float32) vs. 3M × 14 ×
8 = 336 MB (Float64). Float64 does not fit within the 170 MB
per-instance budget.

## R3: HTTP Framework Selection

**Decision**: http4s with Netty backend

**Rationale**: Evaluated four options:

| Framework | Idiomatic Scala | Overhead | GraalVM native-image |
|-----------|----------------|----------|---------------------|
| http4s + Ember | Excellent | Medium (CE fibers) | Good (CE 3.5+) |
| http4s + Netty | Excellent | Low (Netty event loop) | Good |
| Raw Netty | Poor | Minimal | Excellent |
| Undertow | Poor | Low | Good |

http4s + Netty provides the best balance: idiomatic Scala API with
Netty's battle-tested event loop underneath. The Cats Effect overhead
is acceptable for this workload because the hot path (vectorize +
VP-Tree search) is pure computation with no async I/O.

If profiling reveals CE fiber scheduling as a bottleneck, the fallback
is raw Netty — only the infrastructure/http layer changes (Clean
Architecture boundary at VectorSearchPort remains stable).

## R4: JSON Parsing Strategy

**Decision**: jsoniter-scala with compile-time codec generation

**Rationale**: The request schema is fixed (never changes at runtime).
jsoniter-scala generates codecs at compile time via macros — no
reflection, no intermediate AST, no runtime overhead. Benchmarks show
it parses JSON 2-5x faster than circe and comparable to hand-written
jackson-core streaming parsers.

Key advantages over alternatives:

- **vs circe**: No `Json` intermediate AST allocation, no implicit
  derivation overhead. circe creates ~3-5 objects per field during
  decoding; jsoniter-scala writes directly to fields.
- **vs jackson-core manual**: Comparable performance but much less
  boilerplate. jsoniter-scala derives the codec from case class
  definition.
- **vs play-json**: Slower, allocates intermediate JsValue objects.

GraalVM native-image compatibility: jsoniter-scala works with
native-image because codecs are generated at compile time (no
runtime reflection).

## R5: Memory-Mapped Shared Index

**Decision**: Pre-build binary index at Docker build time, share via
Docker volume, mmap from both instances.

**Rationale**: The 3M × 14 Float32 vectors alone consume 168 MB. Two
copies (one per instance) = 336 MB, exceeding the 350 MB budget before
accounting for VP-Tree metadata, JVM/native overhead, or HTTP buffers.

Docker's memory limit (`deploy.resources.limits.memory`) tracks RSS
(Resident Set Size) per container. Memory-mapped file pages that are
backed by a file (not anonymous) are shared at the kernel level. When
both containers mmap the same file from a shared Docker volume:

- The kernel loads one copy of each page into the page cache.
- Both containers' page tables point to the same physical pages.
- Docker's cgroup memory controller counts file-backed pages toward
  the container's limit **only if** the kernel charges them (behavior
  depends on cgroup v1 vs v2 and `memory.oom_control` settings).

**Risk**: On some Docker configurations (cgroup v2 with strict
accounting), file-backed pages DO count toward the container limit.
Mitigation: set the container memory limit to 170 MB and test on
the actual hardware. If mmap pages are counted, fall back to
quantization (Float16 → 84 MB per instance) or a shared sidecar
container.

**Alternative**: Pre-build the index directly into the Docker image
(baked into the filesystem layer). Both containers share the same
image layer in Docker's storage driver. Read-only access means the
pages are shared via the overlay filesystem.

## R6: GraalVM Native Image vs JVM

**Decision**: Start with JVM (OpenJDK 21 + G1GC), evaluate
native-image as optimization.

**Rationale**: Native-image provides faster startup (~50 ms vs 2-5 s)
and lower baseline memory (~10 MB vs 30-50 MB), but introduces
build complexity and potential compatibility issues:

- http4s + Cats Effect on native-image requires specific
  `--initialize-at-build-time` configurations.
- jsoniter-scala is native-image friendly (no reflection).
- VP-Tree with flat arrays and `Unsafe` access may require
  additional native-image configuration.

The pragmatic approach: implement and validate on JVM first. Once the
core logic is correct and performance-baselined, add native-image as
an optimization. JVM with G1GC and proper tuning (`-XX:MaxGCPauseMillis=1`,
`-Xms` = `-Xmx`, `-XX:+AlwaysPreTouch`) can achieve sub-ms p99 for
compute-bound workloads.

## R7: Startup Optimization

**Decision**: Pre-build VP-Tree binary index during Docker build,
mmap at runtime.

**Rationale**: Parsing 284 MB of JSON (3M records) at startup takes
10-30 seconds depending on hardware. Building the VP-Tree
(O(N log N) distance computations) adds another 20-60 seconds.
Total cold start: 30-90 seconds — exceeds the 30-second target.

Pre-processing pipeline:
1. Docker build stage runs a Scala preprocessor that:
   - Decompresses `references.json.gz`
   - Parses all 3M records
   - Builds the VP-Tree
   - Serializes vectors + labels + tree structure to binary files
2. Runtime image contains only the binary files.
3. At startup, the API mmaps the binary files (~10 ms) and marks
   `/ready` as available.

This reduces startup from 30-90 seconds to < 1 second.
