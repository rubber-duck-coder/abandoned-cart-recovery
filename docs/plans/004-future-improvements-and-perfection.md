# Execution Plan 004: Future Improvements And Perfection

## Status

Proposed

## Goal

Capture the remaining non-blocking improvements that would move the abandoned cart recovery system from a strong take-home prototype to a more production-complete and operationally polished system.

This plan is intentionally a follow-up backlog, not part of the accepted MVP scope in `001`, the local hardening scope in `002`, or the identity-stitching scope in `003`.

## Why This Exists

The current repository already delivers the core outcome:

- approved RFCs
- runnable local prototype
- local load and observability validation
- cart-scoped anonymous-to-known identity stitching

What remains are improvements that increase confidence, observability depth, operational robustness, and production realism.

## Improvement Themes

### P1. Analytics And Experimentation Depth

Purpose:

- make experiment analysis cleaner and more explicit

Candidate improvements:

- emit a distinct `policy_selected` analytics event instead of relying only on attribution fields on `attempt_scheduled`
- add clearer analytics events for identity rebinding if Growth or Analytics needs that visibility
- strengthen analytics payload assertions so all key attribution fields are covered on every emitted event type
- define a tighter experiment-analysis contract for downstream consumers

Success criteria:

- experiment assignment, policy selection, scheduling, suppression, and send outcomes are all explicitly reconstructable from analytics events alone

### P2. End-To-End Replay And Idempotency Confidence

Purpose:

- prove more of the duplicate-delivery and replay story at full-flow level

Candidate improvements:

- add a replay-focused E2E test where duplicate upstream events are injected across the same cart journey
- prove no duplicate attempts or sends occur under repeated `cart_abandoned` or due-attempt replay conditions
- add stronger assertions around send idempotency key stability after rebinding and retries

Success criteria:

- replay scenarios are covered by dedicated full-flow tests rather than only by repository or scheduler behavior

### P3. Failure And Edge-Case Test Coverage

Purpose:

- turn implicit behavior into explicit regression protection

Candidate improvements:

- add dedicated malformed-event tests for mutation, state, and abandoned-cart payloads
- add expired-lease recovery tests for dispatch and execution reclaim paths
- add more explicit out-of-order event tests across the full pipeline
- add stronger failure-path tests for analytics publication and provider failure handling

Success criteria:

- the main operational edge cases are covered by named tests rather than being inferred from implementation behavior

### P4. Test Environment Isolation

Purpose:

- reduce accidental coupling between integration tests and the long-running local Docker stack

Candidate improvements:

- isolate integration tests from the shared local Postgres database
- use dedicated test database names or ephemeral infrastructure per suite
- make background dispatcher behavior impossible unless a test explicitly enables it

Success criteria:

- integration tests are deterministic whether or not the local Compose stack is already running

### P5. Production-Readiness Hardening

Purpose:

- close the gap between prototype-grade mocks and production-grade operational posture

Candidate improvements:

- replace mock experimentation, frequency-cap, and notification integrations with interface-compatible service adapters
- add stronger rollout controls, feature flags, and operational kill switches
- define alerting thresholds on lag, failures, and stuck attempts
- add recovery tooling for poison messages and stuck leases
- document operational runbooks for incident response and rollback

Success criteria:

- the system has a credible path from local prototype to staged rollout with explicit operational safeguards

## Suggested Execution Order

| Priority | Improvement Area | Why First |
| --- | --- | --- |
| 1 | P2 Replay And Idempotency Confidence | Highest confidence return for correctness-sensitive recovery behavior |
| 2 | P3 Failure And Edge-Case Test Coverage | Best next step for regression protection |
| 3 | P1 Analytics And Experimentation Depth | Improves Growth usability and analysis clarity |
| 4 | P4 Test Environment Isolation | Improves developer velocity and determinism |
| 5 | P5 Production-Readiness Hardening | Important, but broader and more open-ended than the validation gaps above |

## Non-Goals

- changing the accepted MVP scope retroactively
- replacing the current architecture without evidence
- prematurely introducing multi-region, multi-cluster, or distributed-sharding complexity

## Exit Condition

This plan is complete when the team decides which of these improvements are worth implementing next and breaks the selected items into concrete execution plans.
