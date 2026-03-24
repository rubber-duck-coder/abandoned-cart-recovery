# Solution Overview

This repository contains a local, end-to-end abandoned cart recovery prototype designed around a scalable event-driven architecture.

## What The System Does

- consumes cart mutation and cart state events from Kafka
- persists the latest recovery state in Postgres
- consumes upstream `cart_abandoned` events
- resolves recovery policy and experiment assignment
- schedules recovery attempts
- dispatches due attempts for execution
- performs a final eligibility and frequency-cap check before send
- sends through mocked notification integrations
- emits analytics events for experiment analysis and operational visibility

## Main Runtime Components

- `apps/recovery-service`
  The main Kotlin service that owns ingestion, scheduling, dispatch, execution, and analytics publication.
- `docker-compose.yml`
  Local runtime for Kafka, Postgres, Prometheus, Grafana, and the recovery service.
- `scripts/load/generate_mixed_traffic.py`
  Synthetic traffic generator for smooth, burst, and smooth-bursty load profiles.

## Key Implementation Boundaries

- `processor/`
  Handles cart mutation, cart state, and identity-link processing.
- `repository/`
  Encapsulates Postgres state, attempt scheduling, claiming, and outcome updates.
- `scheduler/`
  Creates recovery attempts from upstream abandonment events.
- `dispatcher/`
  Claims due attempts from Postgres and pushes executable work to Kafka.
- `executor/`
  Re-checks state, applies eligibility and frequency-cap logic, and performs the mock send.
- `policy/` and `experiment/`
  Keep policy selection and experiment attribution modular.
- `telemetry/`
  Exposes Prometheus-compatible operational metrics.

## What Is Proven In The Repo

- unit tests for policy and contract behavior
- integration and E2E coverage for ingestion, scheduling, execution, suppression, analytics, and identity stitching
- local dashboards and reports for smooth, burst, and hybrid load behavior
- append-only dev log showing the AI-assisted implementation and refinement path

## Configurability And Extensibility

- Schedule windows:
  recovery touches are policy-driven, so timings like `24h`, `72h`, and `7d` can be changed by updating policy configuration rather than rewriting the scheduler.
- Experiments:
  experiment evaluation is isolated behind the policy and experiment boundary, so cadence, template family, and variant attribution can evolve without changing the core ingestion or execution flow.
- Channels:
  the system already models channel-specific attempts such as push, SMS, and email; new channel mixes or channel ordering can be introduced through policy changes and adapter extensions.
- Future growth:
  the current architecture leaves room for richer eligibility checks, real provider integrations, stronger experimentation hooks, ML-driven policy selection, and more production-grade operational controls without changing the core event-driven shape.

## Best Entry Points

- Architecture:
  [abandoned-cart-recovery-system.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-system.md)
- MVP execution scope:
  [001-mvp-prototype-implementation.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/plans/001-mvp-prototype-implementation.md)
- Hardening and load validation:
  [002-hardening-and-load-validation.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/plans/002-hardening-and-load-validation.md)
- Identity stitching:
  [003-identity-stitching-implementation.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/plans/003-identity-stitching-implementation.md)
- AI audit trail:
  [docs/dev-log.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/dev-log.md)
