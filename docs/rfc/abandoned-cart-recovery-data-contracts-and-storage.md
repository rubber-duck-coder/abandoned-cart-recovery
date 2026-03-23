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
- experimentation-platform integration contract
- normalized internal recovery event shape
- `cart_recovery_state` logical schema
- `recovery_attempt` logical schema
- identity stitching representation
- status enums and suppression-reason taxonomy
- idempotency and uniqueness constraints
- storage access patterns for scheduler and executor
- frequency-capping integration contract
- analytics event contract for experiment analysis

## Initial Modeling Directions

### Topic Topology

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
  - consumes upstream `cart_abandoned` events as part of the state-event stream
  - emits validated internal scheduler input into `recovery.cart-abandoned`

- `RecoveryScheduler`
  - consumes `recovery.cart-abandoned`
  - consumes the policy-selection result including stable experiment assignment
  - creates recovery attempts
  - emits structured scheduling analytics
  - enqueues due work into `recovery.recovery-attempts`

- `RecoveryExecutor`
  - consumes `recovery.recovery-attempts`
  - performs final eligibility checks, frequency-cap checks, and notification sends
  - emits structured send, suppress, cap, and failure analytics

### Upstream Assumptions Captured in Contracts

- The inactivity logic that produces `cart_abandoned` is owned upstream and is out of scope here.
- The purchase-complete source of truth is a successful checkout event that carries a terminal cart-to-order identifier such as `order_id`, `shipment_id`, or `delivery_id`.
- Event delivery is at-least-once and can arrive out of order.

### Experimentation Integration Model

This design assumes an external experimentation platform that can evaluate active experiments by `experiment_id` or `experiment_name`.

Design decisions:
- experiment evaluation happens in the Recovery Policy Service boundary
- experiment assignment must be stable before schedule creation
- scheduler and executor consume persisted experiment metadata and must not re-evaluate the experiment mid-flight

Minimum returned fields from experiment evaluation:
- `experiment_id`
- `experiment_name`
- `variant_id`
- `policy_id`
- `policy_version`
- selected cadence and channel sequence
- selected template family
- any experiment-controlled knobs such as cap thresholds or suppression flags

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
- `experiment_id`
- `experiment_name`
- `variant_id`
- policy id and policy version
- scheduled timestamp
- execution timestamp
- channel
- template key
- status
- suppression reason
- send idempotency key
- frequency-cap decision
- provider response metadata

### Eligibility Evaluation Model

This document should later define the implementation-facing contract for extensible pre-send checks.

Likely fields and concerns:
- evaluator input shape
- ordered rule execution
- suppression reason taxonomy
- distinction between terminal suppression and retryable dependency failure
- future checks such as inventory, opt-out, merchant pause, or fraud state

### Frequency Capping Integration Model

This document should define the implementation-facing contract for frequency capping before notification send.

Likely fields and concerns:
- user or delivery identity used for capping
- channel-level and campaign-level capping inputs
- allow or suppress decision
- structured suppression reason for observability
- replay-safe behavior when duplicate work reaches the executor

### Analytics Event Model

This design requires structured analytics events to flow into a downstream analytics pipeline for experiment analysis.

Minimum emission points:
- policy selected
- recovery attempt scheduled
- recovery attempt suppressed
- recovery attempt sent
- recovery attempt failed
- frequency cap suppressed

Minimum payload fields:
- `cart_id`
- `user_id` when known
- `attempt_id` when applicable
- `experiment_id`
- `experiment_name`
- `variant_id`
- `policy_id`
- `policy_version`
- `channel`
- `template_key`
- `event_type`
- `event_timestamp`
- suppression or failure reason when applicable

Ownership:
- Recovery Policy Service emits or returns policy-selection analytics context
- Recovery Scheduler emits scheduling analytics
- Recovery Executor emits send, suppress, cap, and failure analytics

### Experiment Auditability Requirements

Attempt and decision records should be sufficient to answer:
- which experiment the cart or user was in
- which variant was assigned
- which policy and policy version were selected
- which channel and template were chosen
- why the attempt was sent or suppressed
- which analytics events should exist for downstream experiment analysis

## Contract Design Principles

- External event shape and internal normalized event shape should be explicitly separated.
- Eligibility-impacting fields should be versioned carefully.
- Critical terminal events should be representable even if mutation events are delayed.
- Contract evolution should prefer additive changes.
- Topic names and processor boundaries are explicit design choices, while vendor-specific transport implementation remains an implementation detail.

## Resolved Design Defaults

- Canonical event identity should come from the upstream event id, with per-topic replay tolerance built around that id.
- State versioning should use a per-cart monotonic `state_version` updated on accepted writes.
- Attempt records should denormalize the fields needed for auditability: `cart_id`, `user_id` when known, `experiment_id`, `experiment_name`, `variant_id`, `policy_id`, `policy_version`, channel, template key, scheduled time, execution time, suppression reason, frequency-cap result, and provider result.
- Attempt and suppression records should default to a 90-day retention window unless product or compliance requirements override it later.
- Duplicate scheduling should be prevented with a deterministic attempt identity derived from `cart_id`, `policy_version`, `touch_index`, and scheduled timestamp.
