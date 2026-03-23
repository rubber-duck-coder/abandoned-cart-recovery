# RFC: Abandoned Cart Recovery Data Contracts and Storage Design

## Status

Draft

## Purpose

This document is a companion to [Abandoned Cart Recovery System](./abandoned-cart-recovery-system.md).

It should be read together with:
- [Abandoned Cart Recovery: Technology Choices](./abandoned-cart-recovery-technology-choices.md)

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

## Concrete Implementation Decisions

- Event backbone: Kafka
- Primary transactional store: Postgres
- Internal async contracts: Protobuf
- External synchronous platform integrations: REST + JSON
- Partitioning key for event processing: `cart_id`
- Initial sharding posture: no application-level sharding

## Topic and Contract Choices

### Topic Topology

This design uses explicit Kafka topics.

### Topic List

- `commerce.cart-events`
  - upstream ingress topic
- `recovery.cart-mutations`
  - high-volume mutation topic for `item_added` and `item_removed`
- `recovery.cart-state-events`
  - critical state transition topic for purchase, empty-cart, identity, and upstream `cart_abandoned`
- `recovery.cart-abandoned`
  - internal scheduler input topic after validation
- `recovery.recovery-attempts`
  - executor work topic for due attempts
- `analytics.recovery-events`
  - analytics pipeline topic or sink for experiment analysis

### Partitioning

- Kafka partition key: `cart_id`
- Target ordering guarantee: per `cart_id`
- No attempt is made to enforce global ordering across carts

### Contract Format

- Use Protobuf for all recovery-owned Kafka topics
- Use flattened JSON for external REST APIs where needed
- Analytics events may be emitted as flattened JSON if that is the easiest ingestion shape for downstream analytics consumers

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

### Proposed Postgres Table: `cart_recovery_state`

Primary key:
- `cart_id`

Suggested columns:
- `cart_id`
- `tenant_id`
- `anonymous_id`
- `user_id`
- `cart_status`
- `abandonment_status`
- `policy_id`
- `policy_version`
- `last_cart_mutation_at`
- `last_critical_event_at`
- `last_purchase_at`
- `state_version`
- `cart_snapshot_json`
- `stitched_identity_json`
- `updated_at`
- `created_at`

Suggested indexes:
- primary key on `cart_id`
- index on `tenant_id, updated_at`
- index on `user_id` where `user_id` is not null

Write behavior:
- mutation and state-event processors update this table directly
- updates are guarded by `state_version` or equivalent conditional update logic
- stale mutation writes against terminal cart state become no-ops

Example DDL direction:

```sql
create table cart_recovery_state (
  cart_id text primary key,
  tenant_id text not null,
  anonymous_id text,
  user_id text,
  cart_status text not null,
  abandonment_status text not null,
  policy_id text,
  policy_version integer,
  last_cart_mutation_at timestamptz,
  last_critical_event_at timestamptz,
  last_purchase_at timestamptz,
  state_version bigint not null,
  cart_snapshot_json jsonb not null,
  stitched_identity_json jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index idx_cart_recovery_state_tenant_updated
  on cart_recovery_state (tenant_id, updated_at desc);

create index idx_cart_recovery_state_user
  on cart_recovery_state (user_id)
  where user_id is not null;
```

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

### Proposed Postgres Table: `recovery_attempt`

Primary key:
- `attempt_id`

Deterministic uniqueness:
- unique constraint on `cart_id`, `policy_version`, `touch_index`, `scheduled_at`

Suggested columns:
- `attempt_id`
- `cart_id`
- `tenant_id`
- `user_id`
- `experiment_id`
- `experiment_name`
- `variant_id`
- `policy_id`
- `policy_version`
- `touch_index`
- `scheduled_at`
- `executed_at`
- `channel`
- `template_key`
- `status`
- `suppression_reason`
- `send_idempotency_key`
- `frequency_cap_result`
- `provider_result_json`
- `created_at`
- `updated_at`

Suggested indexes:
- primary key on `attempt_id`
- unique index for duplicate scheduling prevention
- index on `status, scheduled_at`
- index on `experiment_id, variant_id, scheduled_at`
- index on `cart_id, scheduled_at`

Partitioning posture:
- start unpartitioned if volume is modest
- move to time-based partitioning on `scheduled_at` when attempt volume justifies it

Example DDL direction:

```sql
create table recovery_attempt (
  attempt_id text primary key,
  cart_id text not null,
  tenant_id text not null,
  user_id text,
  experiment_id text,
  experiment_name text,
  variant_id text,
  policy_id text not null,
  policy_version integer not null,
  touch_index integer not null,
  scheduled_at timestamptz not null,
  executed_at timestamptz,
  channel text not null,
  template_key text not null,
  status text not null,
  suppression_reason text,
  send_idempotency_key text not null,
  frequency_cap_result text,
  provider_result_json jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index uq_recovery_attempt_schedule
  on recovery_attempt (cart_id, policy_version, touch_index, scheduled_at);

create index idx_recovery_attempt_status_scheduled
  on recovery_attempt (status, scheduled_at);

create index idx_recovery_attempt_experiment_variant
  on recovery_attempt (experiment_id, variant_id, scheduled_at);

create index idx_recovery_attempt_cart_scheduled
  on recovery_attempt (cart_id, scheduled_at);
```

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

## Contract Skeletons

These are implementation-facing contract directions, not final generated files.

### Protobuf: `RecoveryCartMutationEvent`

```proto
message RecoveryCartMutationEvent {
  string event_id = 1;
  string tenant_id = 2;
  string cart_id = 3;
  string anonymous_id = 4;
  string user_id = 5;
  string mutation_type = 6; // ITEM_ADDED | ITEM_REMOVED
  google.protobuf.Timestamp event_time = 7;
  int64 upstream_sequence = 8;
  bytes cart_snapshot = 9;
}
```

### Protobuf: `RecoveryCartStateEvent`

```proto
message RecoveryCartStateEvent {
  string event_id = 1;
  string tenant_id = 2;
  string cart_id = 3;
  string user_id = 4;
  string state_event_type = 5; // PURCHASE_COMPLETED | CART_EMPTIED | IDENTITY_LINKED | CART_ABANDONED
  google.protobuf.Timestamp event_time = 6;
  string order_id = 7;
  string shipment_id = 8;
  string delivery_id = 9;
}
```

### Protobuf: `RecoveryPolicyResolved`

```proto
message RecoveryPolicyResolved {
  string cart_id = 1;
  string policy_id = 2;
  int32 policy_version = 3;
  string experiment_id = 4;
  string experiment_name = 5;
  string variant_id = 6;
  repeated PolicyTouch touches = 7;
}

message PolicyTouch {
  int32 touch_index = 1;
  string channel = 2;
  string template_key = 3;
  google.protobuf.Duration delay = 4;
}
```

### Protobuf: `RecoveryAttemptDue`

```proto
message RecoveryAttemptDue {
  string attempt_id = 1;
  string cart_id = 2;
  string experiment_id = 3;
  string experiment_name = 4;
  string variant_id = 5;
  string policy_id = 6;
  int32 policy_version = 7;
  int32 touch_index = 8;
  string channel = 9;
  string template_key = 10;
  google.protobuf.Timestamp scheduled_at = 11;
  string send_idempotency_key = 12;
}
```

### JSON Analytics Event

```json
{
  "event_type": "recovery_attempt_sent",
  "event_timestamp": "2026-03-23T12:00:00Z",
  "tenant_id": "tenant-123",
  "cart_id": "cart-123",
  "user_id": "user-456",
  "attempt_id": "attempt-789",
  "experiment_id": "exp-10",
  "experiment_name": "channel_waterfall_v1",
  "variant_id": "variant-b",
  "policy_id": "policy-waterfall-default",
  "policy_version": 3,
  "channel": "push",
  "template_key": "cart_push_v2",
  "status": "sent",
  "suppression_reason": null
}
```

## Database Choice and Rationale

### Why Postgres

Postgres is the chosen primary store because:
- the data is strongly relational
- idempotency and uniqueness constraints are important
- write correctness matters more than extreme write throughput at this stage
- analytics-friendly querying and operational debugging are valuable

### Why Not Cassandra Initially

Cassandra is not selected for the initial design because:
- it increases operational complexity
- it is a worse fit for uniqueness and transactional attempt tracking
- the current architecture already gets substantial horizontal scale from Kafka partitioning

It remains a future option if scale or multi-region requirements later justify the tradeoff.

## Sharding and Scaling Posture

### Initial Posture

- no application-level sharding
- use Kafka partitioning for horizontal compute scale
- use Postgres as a single logical primary store

### Near-Term Scale Strategy

- add Kafka partitions as throughput grows
- scale processor fleets horizontally by partition ownership
- add Postgres read replicas where useful
- partition `recovery_attempt` by time once size or maintenance patterns justify it

### Future Sharding Trigger

Only consider logical or physical database sharding if one of these becomes true:
- single-primary Postgres write throughput becomes the bottleneck
- tenant isolation requirements demand hard data separation
- table growth and maintenance become unmanageable with partitioning alone

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
- Kafka remains the default queueing and replay backbone for this system.
- Postgres remains the default source of truth for state and attempts until a concrete scale trigger justifies a wider-store alternative.
