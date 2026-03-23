# RFC: Abandoned Cart Recovery System

## Status

Draft

## Summary

Build an abandoned cart recovery system that can ingest high-volume cart activity, maintain the latest cart state, schedule recovery attempts, and prevent "ghost" notifications when a cart is purchased or emptied before send time.

The system should be designed for production scale first. A smaller MVP prototype can later implement a narrow vertical slice of this design for local demo purposes.

## Related Documents

- [Abandoned Cart Recovery: Data Contracts and Storage Design](./abandoned-cart-recovery-data-contracts-and-storage.md)

## Goals

- Capture cart state changes in near real time.
- Support anonymous-to-known user identity stitching.
- Schedule configurable recovery sequences such as `30m`, `24h`, and `72h`.
- Enforce a final safety check before sending any recovery message.
- Remain resilient during traffic spikes without duplicate scheduling or duplicate sends.
- Support experimentation on cadence, channel, and template selection.
- Provide telemetry, monitoring, and a controlled rollout path.

## Non-Goals

- Building the full production system in the first implementation pass.
- Solving every downstream CRM or marketing automation use case.
- Designing channel-specific content generation in detail.
- Defining the exact final storage or queue vendor up front.

## Product Assumptions

- Cart activity is emitted as events from the commerce platform.
- A cart can begin anonymous and later become associated with a user identity.
- Recovery messages are transactional re-engagement sends, not broad marketing blasts.
- The Growth team will want to evolve timing and message strategy without changing core scheduler code.

## Requirements

### Functional

- Ingest cart lifecycle events such as add, remove, checkout start, purchase complete, cart emptied, `cart_abandoned`, and identity linked.
- Maintain the latest recovery-relevant cart state.
- Detect when a cart becomes eligible for recovery scheduling.
- Create a recovery sequence from a policy rather than from hardcoded timing logic.
- Re-check current cart state immediately before send.
- Suppress sends for purchased, emptied, or otherwise ineligible carts.
- Avoid duplicate schedules and duplicate sends under retries.

### Operational

- Handle bursty event traffic with at-least-once delivery assumptions.
- Protect critical eligibility-impacting events from head-of-line blocking behind high-volume cart mutation traffic.
- Tolerate consumer retries and downstream notification failures.
- Make scheduling and send decisions traceable.
- Expose health, lag, suppression, and send metrics.
- Support safe rollout by policy, channel, tenant, or traffic slice.

## Proposed Architecture

```text
+-------------------------------+
| Topic: commerce.cart-events   |
+-------------------------------+
                |
                v
+------------------------------------+
| Event Ingestion and Classification |
+------------------------------------+
        |                      |
        v                      v
+---------------------------+  +------------------------------+
| Topic: recovery.cart-     |  | Topic: recovery.cart-       |
| mutations                 |  | state-events                |
+---------------------------+  +------------------------------+
        |                      |
        v                      v
+---------------------------+  +------------------------------+
| Mutation Processor        |  | State Event Processor       |
+---------------------------+  +------------------------------+
          |                    |
          v                    v
+-------------------+   +-------------------------------+
| State Store       |   | Topic: recovery.cart-abandoned|
+-------------------+   +-------------------------------+
                                    |
                                    v
                          +--------------------+
                          | Recovery Scheduler |
                          +--------------------+
                                    |
                                    v
                          +-----------------------+
                          | Recovery Attempt Store|
                          +-----------------------+
                                    |
                                    v
                          +-----------------------+
                          | Recovery Executor     |
                          +-----------------------+
                                    |
                                    v
                          +-----------------------+
                          | Eligibility Evaluator |
                          +-----------------------+
                                    |
                                    v
                          +-----------------------+
                          | Notification Adapter  |
                          +-----------------------+
                                    |
                                    v
                          +-----------------------+
                          | Email / Push / SMS    |
                          +-----------------------+
```

### 1. Event Ingestion Layer

Consumes cart and identity events from the commerce event stream.

Responsibilities:
- Validate and normalize inbound events.
- Route events into explicit downstream topics with dedicated processors.
- Attach idempotency keys and event timestamps.
- Forward normalized events to recovery processing.

### 2. Separate Topics and Processors

The system should not treat all cart events with equal urgency, and this is an explicit design choice rather than an abstract future option.

Approach:
- High-volume mutation events such as `item_added` and `item_removed` should be routed to a dedicated mutations topic with its own processor fleet.
- Critical events such as `purchase_completed`, `cart_emptied`, `cart_abandoned`, identity-link events, and other state-invalidating signals should be routed to a separate state-events topic with its own processor fleet.
- Scheduler-triggering `cart_abandoned` events should flow through a dedicated intermediate topic so scheduling can scale independently from state mutation handling.
- Scheduler and executor decisions should rely on current state plus critical invalidation signals, not on draining every mutation event in perfect order.
- This reduces head-of-line blocking risk during traffic spikes while preserving send-suppression correctness.

Explicit topic names and processor responsibilities are captured in the companion doc:
- [Topic map, contracts, and storage details](./abandoned-cart-recovery-data-contracts-and-storage.md)

### 3. Direct Recovery State Writes

The processor fleets should write directly to the shared recovery state store rather than reconverging through one shared processor.

Responsibilities:
- `Mutation Processor` applies cart mutations directly into recovery state.
- `State Event Processor` applies purchase, delete, empty-cart, identity, and other critical state transitions directly into recovery state.
- `State Event Processor` emits `cart_abandoned` after a successful state transition when abandonment criteria are met.
- Late `item_added` and `item_removed` events become no-op writes once the cart is already in a terminal non-active state such as purchased or deleted.
- Correctness is enforced with per-cart idempotency, conditional updates, and version checks in the state store.

Implementation detail for state-write contracts and versioning should live in the companion doc:
- [State-write, contract, and storage details](./abandoned-cart-recovery-data-contracts-and-storage.md)

### 4. Recovery Policy Service

Provides the recovery sequence definition.

Responsibilities:
- Return the policy for a cart or user context.
- Keep cadence, channel, and template selection in configuration.
- Start with one default policy in v1 while leaving room for future urgency tiers.

Detailed policy versioning and persistence should be captured in the companion doc:
- [Policy, contract, and storage details](./abandoned-cart-recovery-data-contracts-and-storage.md)

### 5. Recovery Scheduler

Schedules future recovery attempts based on policy.

Responsibilities:
- Create attempt records for each scheduled touch.
- Ensure schedule creation is idempotent.
- Track attempt status such as `scheduled`, `suppressed`, `sent`, `failed`, or `cancelled`.

Storage shape, uniqueness constraints, and attempt lifecycle fields should be defined in:
- [Recovery attempt schema details](./abandoned-cart-recovery-data-contracts-and-storage.md)

### 6. Recovery Executor

Processes due attempts and performs the final safety interlock.

Responsibilities:
- Load the current cart state at execution time.
- Run an extensible eligibility evaluation before send.
- Suppress the attempt if the cart is no longer eligible.
- Call the notification provider only after the final eligibility check passes.
- Record the final outcome with an idempotent send key.

### 7. Eligibility Evaluator

The executor should not hardcode a single pair of checks forever.

Responsibilities:
- Evaluate ordered eligibility rules before every send.
- Start with core checks such as purchased-cart suppression and empty-cart suppression.
- Leave room for future checks such as out-of-stock detection, user opt-out state, merchant-level send pause, or fraud-related suppression.
- Return both a boolean decision and a structured suppression reason.

The rule contract, suppression taxonomy, and state inputs for these checks should be specified in:
- [Eligibility and suppression modeling details](./abandoned-cart-recovery-data-contracts-and-storage.md)

### 8. Notification Adapter

Thin integration layer over email, push, or SMS providers.

Responsibilities:
- Accept channel-agnostic send requests.
- Handle provider retries and response mapping.
- Keep provider specifics out of scheduler and state logic.

## Data Model Boundaries

At the architecture level, the system needs:

- a latest-state record for each cart
- a persisted record for each scheduled recovery attempt
- stable idempotency keys for schedule creation and send execution
- enough audit metadata to explain why a send happened or was suppressed

The detailed logical schema, indexing, and contract design are intentionally split into the companion doc:
- [Data contracts and storage design](./abandoned-cart-recovery-data-contracts-and-storage.md)

## End-to-End Flow

```text
Topic: commerce.cart-events
    |
    v
Ingestion and Classification
    |
    +--> Topic: recovery.cart-mutations ------> Mutation Processor ------+
    |                                                                    |
    |                                                                    v
    |                                                             State Store
    |
    +--> Topic: recovery.cart-state-events --> State Event Processor ----+
                                                                         |
                                                                         +--> Topic: recovery.cart-abandoned
                                                                                      |
                                                                                      v
                                                                             Recovery Scheduler
                                                                                      |
                                                                                      v
                                                                             Recovery Attempt Store
                                                                                      |
                                                                                  due attempt
                                                                                      |
                                                                                      v
                                                                             Recovery Executor
                                                                                      |
                                                                                      v
                                                                            Eligibility Evaluator
                                                                                      |
                                                                         eligible ----+---- suppressed
                                                                                      |
                                                                                      v
                                                                            Notification Adapter
```

1. Cart events are ingested and normalized.
2. Ingestion routes each event into the correct dedicated topic and processor path.
3. Mutation and state-event processors update the latest cart recovery state directly.
4. When abandonment criteria are met, the state-event processor emits a `cart_abandoned` event to the scheduler path.
5. When an attempt becomes due, the executor performs a final state read.
6. The eligibility evaluator applies ordered pre-send checks.
7. If the cart is still eligible, the executor sends through the notification adapter.
8. If the cart was purchased, emptied, out of stock, or otherwise invalidated, the executor suppresses the attempt.

## Abandonment and Safety Model

The key principle is:

Schedule optimistically, validate conservatively.

Implications:
- We may schedule attempts as soon as the cart qualifies as abandoned.
- We never trust scheduled state alone at send time.
- Purchase completion and cart-empty events override pending recovery attempts.
- Late mutation events after a cart is already purchased or deleted should be persisted as no-op state transitions rather than reopening eligibility.
- The send path must be idempotent so duplicate execution does not create duplicate notifications.

## Identity Stitching

The design should treat identity stitching as a state merge problem.

Approach:
- Keep recovery state keyed primarily by `cart_id`.
- Allow anonymous sessions to later attach to a `user_id`.
- Rebind future sends and policy selection when identity becomes known.
- Ensure stitching does not create duplicate attempts for the same cart sequence.

Detailed identity-link event handling and merge semantics should live in:
- [Identity, contract, and storage details](./abandoned-cart-recovery-data-contracts-and-storage.md)

## Scalability and Reliability Considerations

- Assume at-least-once delivery from the event backbone.
- Use separate topics and separate processor fleets for high-volume mutations, critical state events, and abandoned-cart scheduling.
- Give critical topics dedicated capacity, tighter lag alerts, and stronger replay guarantees.
- Avoid a single shared recovery state processor in the middle of the pipeline.
- Enforce ordering and correctness per `cart_id`, not across the full system.
- Use idempotency keys for both scheduling and sending.
- Decouple ingestion from execution with asynchronous queues or scheduled jobs.
- Prefer latest-state reads for final send decisions rather than relying on stale scheduled payloads.
- Partition processing by cart or tenant key to reduce hot-spotting and improve ordering where possible.

## Telemetry and Monitoring

The production design should include first-class observability.

### Core Metrics

- inbound event rate
- event processing lag
- scheduler backlog
- due attempt execution lag
- attempts scheduled
- attempts suppressed by reason
- attempts sent by channel
- duplicate prevention hits
- notification provider failure rate

### Logging and Tracing

- Structured logs for each state transition and send decision.
- Correlation ids linking event ingestion, state updates, scheduled attempts, and send outcomes.
- Auditability for why a send occurred or was suppressed.

### Alerts

- Event consumer lag above threshold.
- Scheduler or executor backlog growth.
- Sudden drop in sends after normal scheduling volume.
- Spike in suppression mismatches or provider failures.

## Rollout Strategy

Rollout should be staged rather than big bang.

1. Shadow mode
   - ingest and schedule internally without sending
   - validate suppression and timing logic against real traffic
2. Internal test slice
   - enable sends for a small controlled segment
3. Limited production rollout
   - ramp by tenant, geography, or traffic percentage
4. Broad rollout
   - expand after lag, suppression correctness, and send health are stable

Each rollout stage should have clear success metrics and rollback criteria.

## MVP Direction

The MVP should not attempt to implement the entire production target.

Recommended MVP scope for local demo:
- accept a `CartAbandoned` event
- persist latest cart state
- schedule a small recovery sequence
- re-check current state before send
- call a mocked notification API
- demonstrate idempotent scheduling and idempotent send suppression

This keeps the demo narrow while still proving the hardest correctness path.

## Delivery Milestones

The estimates below assume one engineer is orchestrating implementation work performed by coding agents in parallel. Calendar time can compress through parallelism, but review, integration, and validation still bottleneck on the engineer.

| Milestone | Level of Effort (Eng weeks) | External Dependencies |
| --- | --- | --- |
| RFC and design alignment | 1.0 to 1.5 | Product definition on abandonment semantics, channel scope, and experimentation goals |
| Core architecture and data-contract design | 1.5 to 2.0 | Access to source event definitions, identity semantics, and storage or queue platform guidance |
| MVP prototype for local demo | 1.5 to 2.5 | Mockable notification provider contract and agreed local runtime setup |
| Production hardening and observability | 2.0 to 3.0 | Monitoring stack conventions, deployment environment, alerting destination, and load-test assumptions |
| Controlled rollout readiness | 1.0 to 1.5 | Real provider credentials, rollout policy owner, and production traffic segmentation strategy |

## Estimated Long Poles

- identity stitching correctness
- exact abandonment semantics
- suppression correctness under race conditions
- queueing and storage choices for high-scale traffic
- notification provider integration and channel readiness

## Open Questions

- What exact inactivity rule defines abandonment in v1?
- Should policy selection eventually depend on cart value, user intent, or merchant tier?
- What is the source of truth for purchase completion timing?
- How much ordering can the upstream event stream guarantee?
- Which channels are in scope for the MVP versus production target?
