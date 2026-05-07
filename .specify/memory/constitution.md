<!--
Sync Impact Report
- Version change: 0.0.0 → 1.0.0
- Modified principles: N/A (initial constitution)
- Added sections:
  - I. Clean Architecture
  - II. Clean Code
  - III. Scala Best Practices
  - IV. Git Discipline
  - Infrastructure & Performance Constraints
  - Development Workflow
  - Governance
- Removed sections: N/A
- Templates requiring updates:
  - .specify/templates/plan-template.md ✅ no updates needed (generic)
  - .specify/templates/spec-template.md ✅ no updates needed (generic)
  - .specify/templates/tasks-template.md ✅ no updates needed (generic)
- Follow-up TODOs: none
-->

# Rinha de Backend 2026 (Scala) Constitution

## Core Principles

### I. Clean Architecture

All code MUST follow Clean Architecture boundaries with strict
dependency inversion:

- **Domain layer** (entities, value objects, domain services) MUST NOT
  depend on any framework, library, or infrastructure detail.
- **Use cases / application layer** orchestrate domain logic and define
  port interfaces (traits). They MUST NOT reference concrete
  implementations of I/O, HTTP, or persistence.
- **Infrastructure / adapter layer** implements ports defined by the
  application layer. Framework-specific code (HTTP routes, JSON codecs,
  database clients) lives exclusively here.
- Dependencies MUST point inward: infrastructure → application → domain.
  Violations are treated as blocking defects.
- Cross-cutting concerns (logging, metrics, configuration) MUST be
  injected via constructor parameters or type-class evidence, never
  imported as global singletons.

### II. Clean Code

Every source file MUST be readable, intentional, and minimal:

- Functions and methods MUST do one thing, at one level of abstraction.
- Names MUST reveal intent. Abbreviations are forbidden unless they are
  ubiquitous domain terms (e.g., `mcc`, `tx`).
- Dead code, commented-out code, and TODO comments without a tracking
  reference MUST NOT be merged.
- Duplication MUST be eliminated through well-named abstractions only
  when the duplicated logic shares the same reason to change. Premature
  abstraction is as harmful as duplication.
- Code comments MUST explain *why*, never *what*. If the *what* is not
  obvious from the code, the code MUST be rewritten.
- Every public API (trait method, case class, sealed trait hierarchy)
  MUST have a ScalaDoc comment describing its contract and invariants.
- Maximum cyclomatic complexity per method: 10. Methods exceeding this
  MUST be decomposed.

### III. Scala Best Practices

Idiomatic, performant, and type-safe Scala:

- Prefer immutable data structures and pure functions. Mutable state is
  permitted only inside performance-critical hot paths, and MUST be
  encapsulated behind a pure interface.
- Use `sealed trait` / `enum` hierarchies for domain sum types. Pattern
  matches on sealed types MUST be exhaustive.
- Prefer `Option`, `Either`, and `Try` over null and unchecked
  exceptions. Throwing exceptions is reserved for truly unrecoverable
  situations.
- Use `case class` for value objects and DTOs. Provide `apply` /
  smart constructors that validate invariants at creation time.
- Leverage the type system to make illegal states unrepresentable
  (refined types, opaque types, newtypes, phantom types where
  appropriate).
- Follow the principle of least power: prefer `val` over `var`,
  `Seq` over mutable buffer, `for-comprehension` over manual
  `flatMap` chains when readability improves.
- Compilation warnings MUST be treated as errors (`-Xfatal-warnings`
  or equivalent). Suppress only with an explicit annotation and a
  justifying comment.
- Formatting MUST be enforced by scalafmt with the project
  `.scalafmt.conf`. No manual formatting debates.

### IV. Git Discipline

Atomic, traceable, and safe version control:

- **NEVER execute `git add .`**. Every staged file MUST be added
  explicitly by path (e.g., `git add src/domain/Transaction.scala`).
  This prevents accidental commits of secrets, build artifacts, or
  unrelated changes.
- Commits MUST be atomic: one logical change per commit. A commit
  MUST NOT mix refactoring with feature work or bug fixes.
- Always add signed-off-by with: e.g `git commit -s`
- Commit messages MUST follow Conventional Commits format:
  `type(scope): description` (e.g., `feat(vectorizer): add cosine
  distance calculation`). Body MUST explain *why*, not *what*.
- Feature branches MUST be short-lived and rebased on `main` before
  merge. Merge commits are acceptable only for PR merges.
- Force-push to `main` or `submission` is FORBIDDEN.
- The `.gitignore` MUST be maintained to exclude build outputs
  (`target/`), IDE files (`.idea/`, `.vscode/`, `.bsp/`), environment
  files (`.env`), and OS artifacts (`.DS_Store`).
- Secrets, credentials, and API keys MUST NEVER be committed. If
  detected in history, the branch MUST be cleaned before merge.
- Tags on `main` MUST use semver format: `vMAJOR.MINOR.PATCH`.

## Infrastructure & Performance Constraints

Project-specific constraints derived from the Rinha de Backend 2026
rules that MUST be respected by all implementation decisions:

- Total resource budget: **1 CPU + 350 MB RAM** across all containers.
- Minimum topology: 1 load balancer + 2 API instances (round-robin).
- The load balancer MUST NOT contain business logic.
- API MUST listen on port **9999**.
- Docker images MUST target `linux-amd64`.
- Network mode MUST be `bridge`. `host` and `privileged` are forbidden.
- The 3,000,000-row reference dataset (`references.json.gz`) SHOULD be
  pre-processed at build time or container startup, never on every
  request.
- Latency target: p99 < 10 ms (each 10x improvement = +1000 points).
- Detection accuracy directly impacts score; false negatives weigh more
  than false positives, and HTTP errors weigh most.

## Development Workflow

- All code MUST compile without warnings before commit.
- Integration tests MUST cover the `POST /fraud-score` and `GET /ready`
  contracts.
- The project MUST be buildable and testable with a single `sbt test`
  command (or equivalent).
- Docker builds MUST use multi-stage builds to minimize image size.
- The `submission` branch MUST contain only the files required to run
  the test: `docker-compose.yml` and pre-built images.
- Performance profiling SHOULD be done with realistic data volumes
  (full 3M reference set) before final submission.
- Code review (self or peer) MUST verify Clean Architecture boundary
  compliance before merge.

## Governance

This constitution is the authoritative source of engineering standards
for the Rinha de Backend 2026 Scala project. All implementation
decisions, code reviews, and automation MUST verify compliance with
these principles.

- Amendments require: (1) documented rationale, (2) version bump per
  semver rules below, and (3) propagation to dependent templates.
- Version policy:
  - MAJOR: principle removed or fundamentally redefined.
  - MINOR: new principle or section added, or material expansion.
  - PATCH: wording clarification, typo fix, non-semantic refinement.
- Any conflict between this constitution and other project documents
  MUST be resolved in favor of this constitution.

**Version**: 1.0.0 | **Ratified**: 2026-05-07 | **Last Amended**: 2026-05-07
