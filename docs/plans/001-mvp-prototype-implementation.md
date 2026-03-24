# Execution Plan 001: MVP Prototype Implementation

## Status

Completed

## Milestone Status

| Milestone | Status | Notes |
| --- | --- | --- |
| M1 | Completed | Monorepo scaffold, Docker Compose runtime, health endpoint, and metrics exposure are implemented and host-verifiable. |
| M2 | Completed | Postgres schema, migrations, repository layer, and repository integration coverage are implemented. |
| M3 | Completed | JSON contracts, Kafka topic bootstrap, producer/consumer wiring, and contract tests are implemented. |
| M4 | Completed | Mutation and state-event ingestion update shared recovery state correctly, including terminal-state protection. |
| M5 | Completed | Deterministic mock experimentation and policy resolution are implemented with unit coverage. |
| M6 | Completed | Abandoned-cart scheduling, idempotent attempt creation, and scheduling analytics are implemented. |
| M7 | Completed | Due-attempt dispatch, Kafka execution handoff, eligibility checks, frequency-cap suppression, provider failure handling, and mock send behavior are implemented. |
| M8 | Completed | Scheduling and execution analytics are emitted and asserted in integration tests. Policy attribution is carried on `attempt_scheduled` rather than as a separate `policy_selected` event type. |
| M9 | Completed | Dedicated E2E tests cover a happy-path send flow and a suppression-before-dispatch flow. A replay-focused full-flow E2E assertion is deferred, but the accepted MVP scope is implemented and verified. |

## Deferred Follow-Ups

- no distinct `policy_selected` analytics event is emitted today; policy attribution is present on `attempt_scheduled` instead
- there is no dedicated full-flow replay E2E test proving duplicate upstream events do not lead to duplicate sends end to end
- some planned edge-case checks are implemented in behavior but not yet called out by dedicated tests, especially malformed-event handling and expired-lease recovery assertions

These are improvement candidates, not MVP-scope blockers for the take-home deliverable.

## Goal

Build a runnable local prototype of the abandoned cart recovery system that demonstrates the approved end-to-end flow:

1. consume cart mutation and cart state events from Kafka
2. maintain recovery state in Postgres
3. consume upstream `cart_abandoned`
4. resolve policy and experiment assignment
5. create recovery attempts
6. execute due attempts with eligibility and frequency-cap checks
7. send through a mock notification adapter
8. emit analytics events for experiment analysis
9. verify the flow with an end-to-end local test

## Non-Goals

- production-grade deployment automation
- multi-region or cross-database sharding implementation
- real email, push, or SMS provider integrations
- real experimentation platform integration
- real frequency-capping system implementation
- protobuf contracts for the prototype
- building ML-based policy selection

## Source Of Truth

Implementation should follow these documents in this order:

1. [abandoned-cart-recovery-system.md](../rfc/abandoned-cart-recovery-system.md)
2. [abandoned-cart-recovery-data-contracts-and-storage.md](../rfc/abandoned-cart-recovery-data-contracts-and-storage.md)
3. [abandoned-cart-recovery-technology-choices.md](../rfc/abandoned-cart-recovery-technology-choices.md)
4. [Case study - Staff Engineer for Growth.pdf](../requirements/Case%20study%20-%20Staff%20Engineer%20for%20Growth.pdf)

Prototype-specific override:

- Use JSON for topic payloads and local debugging instead of Protobuf.

## Locked Decisions

- Language: Kotlin
- DI: Guice
- Queue: Kafka
- Primary store: Postgres
- Internal prototype contracts: JSON
- Repository layout: monorepo
- External sync integrations: REST + JSON where needed
- Upstream owns abandonment detection and emits `cart_abandoned`
- Ordering is not guaranteed globally; correctness must tolerate out-of-order delivery
- Processors write directly to shared recovery state; there is no shared downstream state processor

## Repository Shape

The prototype should start in a monorepo layout so additional services or a future UI can be added without restructuring the repository.

Initial target shape:

- `apps/recovery-service` for the main Kotlin service
- `apps/mock-notification-service` for local notification send simulation if a separate process is useful
- `apps/mock-frequency-cap-service` for local cap-check simulation if a separate process is useful
- `ui/` reserved for future operator or demo UI work
- `docs/`
- `infra/` for Docker Compose, local env files, and related runtime assets if needed

## Prototype Scope

The prototype should support these topics:

- `commerce.cart-events`
- `recovery.cart-mutations`
- `recovery.cart-state-events`
- `recovery.cart-abandoned`
- `recovery.recovery-attempts`
- `recovery.analytics-events`

The prototype should support these state transitions:

- active cart mutations update `cart_recovery_state`
- purchase or delete transitions make later mutation writes a no-op
- abandoned-cart event triggers attempt creation
- due-attempt dispatcher claims ready rows in Postgres and publishes executable work to Kafka
- due attempt execution either sends, suppresses, or fails with a recorded reason

The prototype should support this initial recovery policy example:

- 24h push
- 72h sms
- 7d email

## Delivery Strategy

Work in ordered milestones. Each milestone must end with a runnable checkpoint before moving on.

Implementation should optimize for the fastest possible proof of wiring first, then add structure and correctness in layers.

## Execution Principles

- Prefer the thinnest working vertical slice over broad upfront build-out.
- Verify each new hop as soon as it exists, even if the first payloads are simple strings or minimal JSON and the first proof is just logs plus a persisted row.
- Introduce richer contracts, stricter schemas, and more complete validation only after the end-to-end wiring is working.
- Keep each milestone small enough that a bad direction can be corrected with minimal rollback.
- Commit and push after each meaningful checkpoint so progress stays inspectable and reversible.

## Milestone Overview

| Milestone | Objective | Depends On | Checkpoint |
| --- | --- | --- | --- |
| M1 | Project scaffolding and local runtime | none | full MVP stack boots locally via Docker Compose |
| M2 | Postgres schema and repository layer | M1 | migrations apply and repository tests pass |
| M3 | JSON contracts and Kafka wiring | M1 | topics and consumers start cleanly |
| M4 | State ingestion and direct state writes | M2, M3 | mutation and state events update DB correctly |
| M5 | Policy resolution and experiment attribution | M2 | abandoned cart resolves policy and persists assignment |
| M6 | Scheduler and attempt creation | M4, M5 | abandoned event creates attempts idempotently |
| M7 | Executor, eligibility, caps, and mock send | M4, M6 | due attempt is sent or suppressed correctly |
| M8 | Analytics emission | M6, M7 | analytics events emitted for schedule and execution outcomes |
| M9 | End-to-end local test path | M1-M8 | full flow passes locally |

## Detailed Task List

### M1. Project Scaffolding

Objective:

- Create the monorepo skeleton and full local Docker Compose workflow for the prototype.

Expected work:

- create monorepo directory layout for application and future UI expansion
- create Gradle project structure under the service app directory
- add Kotlin application entrypoint
- add Guice module wiring
- add config loading for Kafka, Postgres, topic names, and mock integration endpoints
- add structured logging
- add metrics instrumentation bootstrap and metrics export endpoint wiring
- add a minimal health or startup readiness path
- add local `docker compose` for the full MVP runtime:
  - recovery service
  - Kafka and required broker dependencies
  - Postgres
  - mock dependencies if they run as separate processes
  - metrics stack components required for local verification
- prove the service can boot in compose and emit a simple startup log line

Likely files:

- `build.gradle.kts`
- `settings.gradle.kts`
- `apps/recovery-service/...`
- `infra/...`
- `docker-compose.yml`
- `README.md` or local run notes if needed

Definition of done:

- repo layout supports adding more apps later without moving the recovery service
- full MVP dependency graph can be started with one `docker compose` command
- service boots locally with config validation inside the compose-based workflow
- dependency injection graph is valid
- local metrics endpoint is reachable in the compose environment

Required automated test scenarios:

- build passes from the repo root
- service starts and exposes health
- service exposes metrics
- compose boot path works from a clean local runtime

Verification checkpoint:

- `./gradlew build`
- `docker compose up --build -d`
- verify service health and logs from the compose stack
- verify metrics scrape target is reachable locally

Commit checkpoint:

- commit and push once the monorepo skeleton, compose stack, and service boot path are stable

### M2. Postgres Schema And Repository Layer

Objective:

- Materialize the approved storage model in Postgres and create the repository abstractions used by processors.

Expected work:

- start with the smallest useful schema needed to persist recovery state and attempts
- add migrations for `cart_recovery_state`
- add migrations for `recovery_attempt`
- add indexes and uniqueness constraints from the implementation RFC
- implement repositories for state reads, conditional updates, attempt creation, and attempt status updates
- encode stale mutation no-op behavior in repository update paths

Likely files:

- `src/main/resources/db/migration/...`
- `src/main/kotlin/.../repository/...`
- `src/test/kotlin/.../repository/...`

Definition of done:

- migrations apply cleanly on empty local database
- repository methods cover core create, update, lookup, and idempotency paths
- terminal cart states reject irrelevant late mutation updates as no-op writes

Required automated test scenarios:

- state row insert and fetch
- stale version update rejected
- terminal cart rewrite rejected
- recovery attempt scheduling is idempotent
- recovery attempt execution outcome persists

Verification checkpoint:

- `./gradlew test --tests '*Repository*'`
- inspect schema with `psql` or migration tooling

Commit checkpoint:

- commit and push once migrations and repository tests are passing

### M3. JSON Contracts And Kafka Wiring

Objective:

- Prove Kafka wiring first, then layer in debuggable JSON payloads for the prototype topics.

Expected work:

- start with the simplest possible producer and consumer path, even if the first message body is a plain string or minimal JSON used only to validate connectivity
- verify keys, topic routing, logging, and consumer invocation before introducing richer event models
- define JSON payload classes for:
  - cart mutation events
  - cart state events
  - cart abandoned events
  - resolved policy metadata
  - due recovery attempts
  - analytics events
- create topic configuration
- add Kafka consumer and producer wrappers
- standardize message keys on `cart_id`
- document sample payloads for local debugging

Likely files:

- `src/main/kotlin/.../contract/...`
- `src/main/kotlin/.../kafka/...`
- `docs/examples/...` if sample payloads are stored

Definition of done:

- a minimal message can be produced and consumed end to end with clear logs
- consumers can deserialize valid JSON payloads
- producers can publish test messages with consistent keys
- bad payloads fail clearly and are observable

Required automated test scenarios:

- JSON round-trip for core event payloads
- topic bootstrap creates required topics
- producer and consumer round-trip a minimal valid message
- malformed or unexpected JSON is surfaced clearly without silent success

Verification checkpoint:

- local publish and consume smoke test using the simplest valid message shape
- `./gradlew test --tests '*Contract*'`
- local publish and consume smoke test against Kafka

Commit checkpoint:

- commit and push once Kafka wiring is proven and the initial JSON shapes are stable enough for downstream work

### M4. State Ingestion And Direct State Writes

Objective:

- Implement the two independent ingestion paths that write directly to shared recovery state.

Expected work:

- start with a minimal happy-path processor that consumes a message, logs it, and writes a simple state row
- only then extend to richer state transitions and stale-event handling
- implement `CartMutationProcessor`
- implement `CartStateEventProcessor`
- route messages from `commerce.cart-events` into mutation or state-event handling
- apply conditional updates by current state and event type
- ensure purchase and delete transitions move carts into terminal non-active states
- ensure late mutation events are persisted as no-op outcomes when the cart is already terminal

Likely files:

- `src/main/kotlin/.../processor/CartMutationProcessor.kt`
- `src/main/kotlin/.../processor/CartStateEventProcessor.kt`
- `src/test/kotlin/.../processor/...`

Definition of done:

- active-cart mutations update recovery state
- purchase and delete events close the cart for recovery purposes
- out-of-order late mutations do not re-open or corrupt terminal cart state

Required automated test scenarios:

- mutation on active cart writes `ACTIVE` state
- state event transitions cart to `PURCHASED`
- state event transitions cart to `DELETED`
- `cart_abandoned` state event marks abandonment correctly once that path is wired
- late mutation after terminal purchase is a no-op
- late mutation after delete is a no-op
- malformed or unknown event type is ignored or rejected in an observable way
- out-of-order delivery does not regress state version or reopen terminal carts

Verification checkpoint:

- processor smoke test that proves consume -> log -> DB write
- processor tests for:
  - add/remove on active cart
  - purchase before late add
  - delete before late remove
  - malformed event handling
  - out-of-order delivery handling

Commit checkpoint:

- commit and push once both ingestion paths can update shared state correctly

### M5. Policy Resolution And Experiment Attribution

Objective:

- Resolve recovery policy and experiment assignment before scheduling.

Expected work:

- implement `RecoveryPolicyService`
- add mocked experimentation client
- resolve stable `experiment_id`, `experiment_name`, `variant_id`, `policy_id`, and `policy_version`
- support initial waterfall policy `24h push`, `72h sms`, `7d email`
- persist resolved attribution with attempt creation inputs

Likely files:

- `src/main/kotlin/.../policy/...`
- `src/main/kotlin/.../experiment/...`
- `src/test/kotlin/.../policy/...`

Definition of done:

- abandoned cart gets a deterministic resolved policy
- experiment metadata is available downstream without re-evaluation

Required automated test scenarios:

- repeated evaluation of the same cart resolves the same experiment assignment
- policy selection returns expected waterfall touches
- resolved experiment and policy metadata are persisted for downstream use
- unknown experiment configuration fails clearly

Verification checkpoint:

- `./gradlew test --tests '*Policy*'`
- deterministic assignment test for repeated evaluation of the same cart/user

Commit checkpoint:

- commit and push once policy resolution is deterministic and attribution is persisted

### M6. Scheduler And Attempt Creation

Objective:

- Create idempotent recovery attempts from upstream `cart_abandoned`.

Expected work:

- consume `recovery.cart-abandoned`
- load current cart state
- resolve policy and experiment assignment
- create one or more `recovery_attempt` rows
- emit `attempt_scheduled` analytics

Likely files:

- `src/main/kotlin/.../scheduler/...`
- `src/test/kotlin/.../scheduler/...`

Definition of done:

- a valid abandoned cart creates the expected attempts once
- replaying the same abandoned event does not duplicate attempts

Required automated test scenarios:

- initial abandoned event creates expected attempts
- duplicate abandoned event replay does not create duplicates
- scheduler persists experiment attribution on created attempts
- scheduler emits `attempt_scheduled` analytics
- out-of-order abandoned event handling does not schedule against an ineligible terminal cart

Verification checkpoint:

- scheduler tests for:
  - initial schedule creation
  - duplicate abandoned event replay
  - scheduling with experiment attribution present

Commit checkpoint:

- commit and push once abandoned events create attempts idempotently

### M7. Executor, Eligibility, Frequency Cap, And Mock Send

Objective:

- Dispatch and execute due attempts safely and record final outcome.

Expected work:

- implement a `DueAttemptDispatcher`
- poll Postgres for rows with `status = 'SCHEDULED'` and `scheduled_at <= now()`
- claim due rows atomically with lease semantics before execution handoff
- publish claimed due work to `recovery.recovery-attempts`
- implement executor consumption from `recovery.recovery-attempts`
- reload latest cart state before send
- add `EligibilityEvaluator`
- integrate mocked frequency-cap client before send
- integrate mock notification adapter
- handle stale leased rows so crashed dispatch or execution work can be retried
- update attempt status to `sent`, `suppressed`, or `failed`
- store suppression and failure reasons

Likely files:

- `src/main/kotlin/.../dispatcher/...`
- `src/main/kotlin/.../executor/...`
- `src/main/kotlin/.../eligibility/...`
- `src/main/kotlin/.../frequencycap/...`
- `src/main/kotlin/.../notification/...`
- `src/test/kotlin/.../executor/...`

Definition of done:

- due attempts are claimed once for a given lease window before Kafka handoff
- claimed work is published to `recovery.recovery-attempts`
- purchased or deleted carts are suppressed
- frequency-cap suppressions are recorded
- eligible attempts call mock notification send exactly once
- retries do not create duplicate sends

Required automated test scenarios:

- due dispatcher claims only ready scheduled attempts
- due dispatcher does not double-claim the same attempt
- due dispatcher publishes claimed work to Kafka
- expired leases can be recovered for retry
- happy-path send succeeds
- purchased cart is suppressed before send
- deleted cart is suppressed before send
- frequency-cap denial suppresses send
- provider failure marks attempt failed
- replay or retry does not create duplicate sends
- eligibility hooks are extensible enough to add future checks such as out-of-stock

Verification checkpoint:

- executor tests for:
  - send path
  - suppression after purchase
  - suppression by frequency cap
  - mock provider failure

Commit checkpoint:

- commit and push once send and suppression outcomes are both working reliably

### M8. Analytics Emission

Objective:

- Emit structured analytics events for experiment analysis and operational visibility.

Expected work:

- implement analytics event publisher
- emit events for:
  - policy selected
  - attempt scheduled
  - attempt suppressed
  - attempt sent
  - attempt failed
  - frequency cap suppressed
- include experiment and policy attribution fields from the RFC

Likely files:

- `src/main/kotlin/.../analytics/...`
- `src/test/kotlin/.../analytics/...`

Definition of done:

- scheduling and execution paths emit analyzable structured events
- emitted payloads contain enough fields for experiment attribution

Required automated test scenarios:

- `policy_selected` payload contains experiment and policy attribution
- `attempt_scheduled` payload contains attempt metadata
- `attempt_sent` payload contains channel and template metadata
- `attempt_suppressed` payload contains suppression reason
- `attempt_failed` payload contains failure reason
- analytics publisher can emit to Kafka topic successfully

Verification checkpoint:

- analytics tests for payload completeness
- local Kafka smoke test for emitted analytics topic messages

Commit checkpoint:

- commit and push once analytics events are emitted consistently from schedule and execution paths

### M9. End-To-End Local Test

Objective:

- Prove the prototype works from input event to final send or suppression outcome.

Expected work:

- create end-to-end test harness using local Kafka and Postgres
- cover happy path:
  - ingest cart activity
  - ingest abandoned event
  - create attempts
  - execute due attempt
  - emit analytics
- cover suppression path:
  - ingest cart activity
  - ingest abandoned event
  - ingest purchase before due execution
  - suppress attempt
  - emit suppression analytics

Likely files:

- `src/test/kotlin/.../e2e/...`
- `src/test/resources/...`

Definition of done:

- local end-to-end suite passes reproducibly
- expected DB rows and analytics events match the scenario

Required automated test scenarios:

- happy path from cart activity to scheduled attempt to mock send
- suppression path where purchase arrives before execution
- analytics continuity across schedule and execution
- experiment attribution continuity across the full flow
- replay of upstream events does not create duplicate attempts or sends

Verification checkpoint:

- `docker compose up -d`
- `./gradlew test --tests '*E2E*'`

Commit checkpoint:

- commit and push once the full local end-to-end test is reproducible

## Execution Notes

### Suggested Package Boundaries

- `app`
- `config`
- `contract`
- `kafka`
- `repository`
- `processor`
- `policy`
- `experiment`
- `scheduler`
- `executor`
- `eligibility`
- `frequencycap`
- `notification`
- `analytics`

### Suggested Build Order

1. M1
2. M2 and M3
3. M4
4. M5 and M6
5. M7 and M8
6. M9

### Iteration Pattern Within A Milestone

1. make the smallest change that proves the next hop works
2. verify it immediately with logs, a DB row, or a simple automated test
3. harden the shape with better contracts, validation, and edge-case handling
4. commit and push before starting the next meaningful slice

### Parallel Work Opportunities

These can run in parallel once prerequisites are in place:

- M2 repository work and M3 JSON/Kafka contract work after scaffolding
- M5 policy/experiment work and M4 ingestion work after repositories exist
- M7 executor work and M8 analytics publisher work after attempts exist

### Decision Checkpoints

Stop and review before implementation continues if any of these change:

- topic names
- Postgres table shape
- JSON payload contract shape
- experiment attribution fields
- frequency-cap integration contract

## Handoff Notes For A Fresh Session

When resuming in a new session:

1. read `AGENTS.md`
2. read the tail of `docs/dev-log.md`
3. read the three RFC docs in `docs/rfc`
4. open this execution plan
5. check `git status --short`
6. continue from the first incomplete milestone

Before starting a milestone:

- append a new planning entry to `docs/dev-log.md`
- state the milestone number and exact files expected to change

Before ending a milestone:

- run the milestone checkpoint
- append a new outcome entry to `docs/dev-log.md`
- note what remains incomplete

## Prototype Exit Criteria

The prototype is complete when all of the following are true:

- full MVP stack boots locally with `docker compose`
- Postgres schema is applied
- Kafka consumers and producers run locally
- cart state updates correctly from input events
- abandoned events create attempts idempotently
- executor performs final eligibility validation before send
- frequency-cap integration can suppress sends
- mock notification sends are recorded once
- analytics events are emitted with experiment attribution
- metrics are exposed and scrapeable in the local environment
- end-to-end local test covers send and suppression paths
