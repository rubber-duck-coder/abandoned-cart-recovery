# RFC: Abandoned Cart Recovery System

## Status

Draft

## Summary

Build an abandoned cart recovery system that can ingest high-volume cart activity, maintain the latest cart state, schedule recovery attempts, and prevent "ghost" notifications when a cart is purchased or emptied before send time.

The system should be designed for production scale first. A smaller MVP prototype can later implement a narrow vertical slice of this design for local demo purposes.

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

- Ingest cart lifecycle events such as add, remove, checkout start, purchase complete, cart emptied, and identity linked.
- Maintain the latest recovery-relevant cart state.
- Detect when a cart becomes eligible for recovery scheduling.
- Create a recovery sequence from a policy rather than from hardcoded timing logic.
- Re-check current cart state immediately before send.
- Suppress sends for purchased, emptied, or otherwise ineligible carts.
- Avoid duplicate schedules and duplicate sends under retries.

### Operational

- Handle bursty event traffic with at-least-once delivery assumptions.
- Tolerate consumer retries and downstream notification failures.
- Make scheduling and send decisions traceable.
- Expose health, lag, suppression, and send metrics.
- Support safe rollout by policy, channel, tenant, or traffic slice.

## Proposed Architecture

### 1. Event Ingestion Layer

Consumes cart and identity events from the commerce event stream.

Responsibilities:
- Validate and normalize inbound events.
- Attach idempotency keys and event timestamps.
- Forward normalized events to recovery processing.

### 2. Recovery State Processor

Updates the latest cart recovery state for each cart.

Responsibilities:
- Maintain latest cart contents, ownership, checkout status, and recovery eligibility.
- Merge anonymous and known identities when stitching events arrive.
- Detect state transitions that should create, update, or cancel recovery attempts.

### 3. Recovery Policy Service

Provides the recovery sequence definition.

Responsibilities:
- Return the policy for a cart or user context.
- Keep cadence, channel, and template selection in configuration.
- Start with one default policy in v1 while leaving room for future urgency tiers.

### 4. Recovery Scheduler

Schedules future recovery attempts based on policy.

Responsibilities:
- Create attempt records for each scheduled touch.
- Ensure schedule creation is idempotent.
- Track attempt status such as `scheduled`, `suppressed`, `sent`, `failed`, or `cancelled`.

### 5. Recovery Executor

Processes due attempts and performs the final safety interlock.

Responsibilities:
- Load the current cart state at execution time.
- Suppress the attempt if the cart is no longer eligible.
- Call the notification provider only after the final eligibility check passes.
- Record the final outcome with an idempotent send key.

### 6. Notification Adapter

Thin integration layer over email, push, or SMS providers.

Responsibilities:
- Accept channel-agnostic send requests.
- Handle provider retries and response mapping.
- Keep provider specifics out of scheduler and state logic.

## Core Data Model

At a high level the system needs:

- `cart_recovery_state`
  - `cart_id`
  - `user_id` or anonymous identifier
  - current cart snapshot
  - lifecycle status
  - last meaningful cart event
  - last purchase event, if any
  - recovery policy reference
  - updated timestamp

- `recovery_attempt`
  - stable attempt id
  - `cart_id`
  - touch number
  - scheduled time
  - channel
  - template key
  - status
  - suppression reason or send result

## End-to-End Flow

1. Cart events are ingested and normalized.
2. State processor updates the latest cart recovery state.
3. When abandonment criteria are met, the scheduler creates recovery attempts from the active policy.
4. When an attempt becomes due, the executor performs a final state read.
5. If the cart is still eligible, the executor sends through the notification adapter.
6. If the cart was purchased, emptied, or otherwise invalidated, the executor suppresses the attempt.

## Abandonment and Safety Model

The key principle is:

Schedule optimistically, validate conservatively.

Implications:
- We may schedule attempts as soon as the cart qualifies as abandoned.
- We never trust scheduled state alone at send time.
- Purchase completion and cart-empty events override pending recovery attempts.
- The send path must be idempotent so duplicate execution does not create duplicate notifications.

## Identity Stitching

The design should treat identity stitching as a state merge problem.

Approach:
- Keep recovery state keyed primarily by `cart_id`.
- Allow anonymous sessions to later attach to a `user_id`.
- Rebind future sends and policy selection when identity becomes known.
- Ensure stitching does not create duplicate attempts for the same cart sequence.

## Scalability and Reliability Considerations

- Assume at-least-once delivery from the event backbone.
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

### Milestone 1: RFC and design alignment

- finalize architecture, boundaries, and policy model

### Milestone 2: MVP prototype

- deliver a local end-to-end flow with mocked providers

### Milestone 3: Production hardening plan

- define storage, queue, deployment, observability, and rollout details

### Milestone 4: Controlled rollout

- enable real traffic in phases with monitoring and rollback controls

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
