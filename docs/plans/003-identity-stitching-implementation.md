# Execution Plan 003: Identity Stitching Implementation

## Status

Completed

## Goal

Close the anonymous-to-known identity stitching gap in the prototype by implementing and verifying a first-class identity-link flow that:

1. consumes identity-link state events
2. merges anonymous and known identity data into recovery state
3. rebinds future recovery attempts to the known user identity
4. preserves idempotency and terminal-state correctness
5. proves the behavior with focused integration and E2E tests

## Non-Goals

- introducing a separate identity service
- cross-cart or cross-session user graph reconciliation beyond the current cart scope
- rewriting the recovery architecture
- changing the experimentation model
- implementing production-grade identity resolution beyond the upstream event contract

## Source Of Truth

Implementation should follow these documents in this order:

1. [abandoned-cart-recovery-system.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-system.md)
2. [abandoned-cart-recovery-data-contracts-and-storage.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-data-contracts-and-storage.md)
3. [001-mvp-prototype-implementation.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/plans/001-mvp-prototype-implementation.md)

## Locked Decisions

- identity stitching remains an upstream-driven state transition, not an inferred local behavior
- identity-link events are treated as critical state events
- correctness is enforced on shared recovery state, not via a separate stitching processor
- rebinding applies to future recovery sends and attempt metadata that have not yet reached a terminal outcome
- keep the first implementation cart-scoped and simple
- use `identity_linked` as the prototype `stateType`
- rebind only `SCHEDULED` attempts in the first implementation
- keep existing scheduled policy and experiment attribution stable after stitching
- prefer `user_id` for downstream frequency-capping identity when known, otherwise fall back to `anonymous_id`
- identity-link events may update identity fields on terminal carts, but must not reopen eligibility or mutate terminal outcome semantics

## Desired End State

After `003`, the prototype should support this flow:

1. cart starts with `anonymous_id` only
2. cart activity persists anonymous recovery state
3. upstream emits an identity-link event that provides `user_id`
4. recovery state is updated to carry both the anonymous and known identity context
5. future scheduling and execution use the known `user_id`
6. already-created non-terminal attempts are rebound to the known `user_id`
7. no duplicate attempts or duplicate sends are created because of the identity transition

## Remaining Minor Questions

- whether the prototype needs a dedicated analytics event for identity rebinding, or whether existing scheduling and execution analytics are sufficient

This is not a blocker for implementation.

## Milestone Overview

| Milestone | Objective | Depends On | Checkpoint |
| --- | --- | --- | --- |
| I1 | Identity event contract and state-write rules | 001 completed | identity-link event shape and merge semantics are coded and documented |
| I2 | Attempt rebinding behavior | I1 | future attempts reflect the known user identity |
| I3 | Focused integration and E2E coverage | I1, I2 | anonymous-to-known flow passes end to end |

## Milestone Status

| Milestone | Status | Notes |
| --- | --- | --- |
| I1 | Completed | `identity_linked` state events update recovery state and remain idempotent on replay. |
| I2 | Completed | Only `SCHEDULED` attempts are rebound to the stitched `user_id`; terminal attempts stay unchanged. |
| I3 | Completed | Kafka-driven processor coverage plus E2E stitched-cart flow now pass under the dedicated `integrationTest` source set. |

## Detailed Task List

### I1. Identity Event Contract And State-Write Rules

Objective:

- Add the smallest useful identity-link handling path.

Expected work:

- define the prototype identity-link event shape using the existing state-event path
- update `CartStateEventProcessor` to recognize identity-link events explicitly
- merge `anonymous_id`, `user_id`, and stitched identity metadata into `cart_recovery_state`
- preserve terminal-state and version-check behavior
- keep replay or duplicate identity-link events idempotent

Definition of done:

- identity-link event handling is explicit in code
- recovery state persists the known user identity after stitching
- duplicate identity-link events do not corrupt state

Required automated test scenarios:

- anonymous cart becomes known after identity-link event
- duplicate identity-link event is idempotent
- identity-link event does not reopen a terminal purchased or deleted cart

Verification checkpoint:

- `./gradlew test --tests '*Processor*'`
- inspect the persisted `cart_recovery_state` row for a stitched cart

### I2. Attempt Rebinding Behavior

Objective:

- Ensure future recovery work uses the stitched identity.

Expected work:

- define which recovery-attempt states are eligible for rebinding
- update attempt repository logic to rebind eligible non-terminal attempts to the known `user_id`
- ensure scheduler and executor read the stitched identity consistently
- keep attempt identity and send idempotency stable

Definition of done:

- future non-terminal attempts are rebound to the stitched `user_id`
- terminal attempts are not rewritten unnecessarily
- rebinding does not create duplicate attempts

Required automated test scenarios:

- scheduled attempt created before stitching is rebound to known `user_id`
- terminal sent or suppressed attempt is not incorrectly mutated
- rebinding preserves attempt uniqueness and send idempotency key stability

Verification checkpoint:

- `./gradlew test --tests '*Repository*'`
- inspect `recovery_attempt` rows before and after identity-link processing

### I3. Focused Integration And E2E Coverage

Objective:

- Prove the stitched identity flow across the real local pipeline.

Expected work:

- add integration coverage for identity-link processing through Kafka to Postgres
- add one E2E scenario:
  - anonymous cart activity
  - abandoned scheduling
  - identity-link event
  - due attempt execution uses the known identity
- assert analytics continuity where relevant

Definition of done:

- the anonymous-to-known flow is proven by tests, not just code inspection
- the prototype now has first-class coverage for the identity-stitching requirement

Required automated test scenarios:

- Kafka-driven identity-link event updates recovery state
- abandoned cart scheduled before stitching still executes with known identity after rebinding
- no duplicate attempts or duplicate sends appear because of stitching

Verification checkpoint:

- `./gradlew build`
- targeted E2E test for stitched identity flow passes

## Execution Principles

- keep the first identity implementation on the existing processor and repository boundaries
- do not introduce a new service or queue unless the current model proves insufficient
- prefer explicit state transitions and focused tests over abstract generalization
- commit and push after each meaningful checkpoint

## Expected Deliverable

At the end of `003`, the repository should contain:

- explicit identity-link handling in the recovery service
- repository support for rebinding future attempts
- focused processor, repository, and E2E tests for stitched identity behavior
- updated plan or RFC notes only if implementation forces a material semantic decision
