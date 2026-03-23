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
- processing-lane classification and transport model
- normalized internal recovery event shape
- `cart_recovery_state` logical schema
- `recovery_attempt` logical schema
- identity stitching representation
- status enums and suppression-reason taxonomy
- idempotency and uniqueness constraints
- storage access patterns for scheduler and executor

## Initial Modeling Directions

### Processing Lanes

This document should later define how the architecture-level mutation lane and critical-event lane are implemented.

Topics to capture:
- event classification rules
- whether isolation is realized with separate queues, separate topics, or another transport abstraction
- delivery guarantees expected for critical invalidation events
- lag and replay expectations per lane
- how lane outputs converge into the state processor

### Cart Recovery State

The state model should prioritize latest eligibility-relevant state over replaying the entire event history during execution.

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
- Queue or topic choices should remain implementation details unless a concrete platform decision materially changes the architecture.

## Open Questions

- What is the canonical event id and ordering guarantee from upstream systems?
- Should state versioning be event-based, sequence-based, or timestamp-based?
- Which fields must be denormalized into the attempt record for auditability?
- How long should attempts and suppression records be retained?
- What exact uniqueness constraint should prevent duplicate scheduling for the same touch?
