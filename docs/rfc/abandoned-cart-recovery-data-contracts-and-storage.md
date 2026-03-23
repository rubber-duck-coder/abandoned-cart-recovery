# RFC: Abandoned Cart Recovery Data Contracts and Storage Design

## Status

Draft

## Purpose

This document is a companion to [Abandoned Cart Recovery System](./abandoned-cart-recovery-system.md).

The main RFC focuses on system architecture, operational design, and rollout strategy. This companion document captures the lower-level details that should evolve separately:

- event contracts
- state store shape
- recovery attempt storage model
- idempotency keys
- indexing and query patterns
- retention and archival considerations

## Why Separate This Document

- The main RFC should stay readable at the architecture and decision level.
- Storage and contract details will likely change more frequently during implementation planning.
- This split allows the execution plan and MVP design to reference concrete schemas later without overloading the primary RFC.

## Scope

This document should eventually define:

- inbound event contract expectations
- explicit topic names and processor responsibilities
- normalized internal recovery event shape
- `cart_recovery_state` logical schema
- `recovery_attempt` logical schema
- identity stitching representation
- status enums and suppression-reason taxonomy
- idempotency and uniqueness constraints
- storage access patterns for scheduler and executor

## Initial Modeling Directions

### Processing Lanes

This document should later define how the architecture-level topic split is implemented.

Topics to capture:
- event classification rules
- exact topic or queue names
- which processor fleet owns each topic
- delivery guarantees expected for critical invalidation events
- lag and replay expectations per lane
- how lane outputs converge into the state processor

### Current Topic Map

Current design choice:

- Incoming topic: `commerce.cart-events`
- Intermediate topic for high-volume cart mutations: `recovery.cart-mutations`
- Intermediate topic for critical cart state transitions: `recovery.cart-state-events`
- Intermediate topic for scheduler input: `recovery.cart-abandoned`
- Due-attempt work queue or topic for executor input: `recovery.recovery-attempts`

Associated processors:

- `CartEventIngressProcessor`
  - consumes `commerce.cart-events`
  - normalizes events
  - routes them to `recovery.cart-mutations` or `recovery.cart-state-events`

- `CartMutationProcessor`
  - consumes `recovery.cart-mutations`
  - writes active-cart mutations directly into shared recovery state
  - uses per-cart conditional updates or version checks to avoid stale overwrites
  - treats late mutation events as no-op writes when cart state is already terminal

- `CartStateEventProcessor`
  - consumes `recovery.cart-state-events`
  - writes purchase, delete or empty-cart, identity, and other critical state transitions directly into shared recovery state
  - emits `recovery.cart-abandoned` when abandonment criteria are met

- `RecoveryScheduler`
  - consumes `recovery.cart-abandoned`
  - creates recovery attempts
  - enqueues due work into `recovery.recovery-attempts`

- `RecoveryExecutor`
  - consumes `recovery.recovery-attempts`
  - performs final eligibility checks and notification sends

### Cart Recovery State

The state model should prioritize latest eligibility-relevant state over replaying the entire event history during execution.

Behavioral rule:
- if `item_added` or `item_removed` arrives after the cart is already in a non-active terminal state such as purchased or deleted, the state write should become a no-op because the mutation is no longer relevant to notification eligibility
- ordering and conflict control should be enforced per `cart_id`, not through a single globally shared state processor

Likely fields:
- `cart_id`
- anonymous identifier
- `user_id`
- merchant or tenant identifier
- cart contents snapshot
- cart lifecycle status
- abandonment status
- last cart mutation timestamp
- last critical event timestamp
- active recovery policy reference
- stitched identity metadata
- record version or update sequence

### Recovery Attempt

The attempt model should represent each scheduled touch independently.

Likely fields:
- `attempt_id`
- `cart_id`
- touch index
- policy id and policy version
- scheduled timestamp
- execution timestamp
- channel
- template key
- status
- suppression reason
- send idempotency key
- provider response metadata

### Eligibility Evaluation Model

This document should later define the implementation-facing contract for extensible pre-send checks.

Likely fields and concerns:
- evaluator input shape
- ordered rule execution
- suppression reason taxonomy
- distinction between terminal suppression and retryable dependency failure
- future checks such as inventory, opt-out, merchant pause, or fraud state

## Contract Design Principles

- External event shape and internal normalized event shape should be explicitly separated.
- Eligibility-impacting fields should be versioned carefully.
- Critical terminal events should be representable even if mutation events are delayed.
- Contract evolution should prefer additive changes.
- Topic names and processor boundaries are explicit design choices, while vendor-specific transport implementation remains an implementation detail.

## Open Questions

- What is the canonical event id and ordering guarantee from upstream systems?
- Should state versioning be event-based, sequence-based, or timestamp-based?
- Which fields must be denormalized into the attempt record for auditability?
- How long should attempts and suppression records be retained?
- What exact uniqueness constraint should prevent duplicate scheduling for the same touch?
