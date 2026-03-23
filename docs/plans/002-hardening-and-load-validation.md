# Execution Plan 002: Hardening And Load Validation

## Status

Completed

## Goal

Harden the abandoned cart recovery prototype so we can:

1. generate realistic Kafka load against the consumer pipeline
2. measure smooth sustained traffic and bursty traffic behavior
3. observe queueing, lag, throughput, latency, and resource utilization under load
4. identify the first real bottlenecks in Kafka consumption, Postgres writes, dispatching, and execution
5. leave behind repeatable load-test scenarios, dashboards, and a tuning workflow

## Non-Goals

- multi-region hardening
- production auto-scaling automation
- formal SLO enforcement or alert paging
- replacing the current prototype architecture
- introducing real external notification or cap providers
- benchmarking every possible component in isolation before system-level tests

## Source Of Truth

This plan should follow these documents in this order:

1. [abandoned-cart-recovery-system.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-system.md)
2. [abandoned-cart-recovery-data-contracts-and-storage.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-data-contracts-and-storage.md)
3. [abandoned-cart-recovery-technology-choices.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/rfc/abandoned-cart-recovery-technology-choices.md)
4. [001-mvp-prototype-implementation.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/plans/001-mvp-prototype-implementation.md)

## Planning Assumptions

- The current MVP pipeline is the baseline under test.
- Kafka remains the event backbone.
- Postgres remains the primary state and attempt store.
- Prometheus-compatible metrics remain the primary metrics sink.
- Grafana is the preferred local dashboard surface.
- The load generator can be a simple script-based tool rather than a dedicated app.
- Kafka lag visibility should come primarily from Kafka or exporter metrics rather than custom in-app lag instrumentation.
- Local single-machine load validation is sufficient for this exercise.
- We should prefer the smallest hardening changes that expose or remove real bottlenecks.

## Hardening Questions To Answer

- How much sustained event throughput can a single local stack handle before lag grows continuously?
- What happens to consumer lag, DB latency, and end-to-end recovery latency during sudden bursts?
- Which stage saturates first:
  Kafka consumer loop, state writes, scheduling, due-attempt dispatch, or execution?
- How quickly does the system recover after a burst ends?
- Which metrics are most useful for spotting backpressure and failure early?
- Which tuning knobs actually matter in this architecture?

## Success Criteria

- We can run at least one sustained load profile and one burst profile locally with repeatable commands.
- We have dashboards or saved panels showing:
  - Kafka topic lag
  - consumer throughput
  - state-write rate
  - attempt scheduling rate
  - dispatch rate
  - execution outcome rate
  - DB latency/error rate
  - app CPU and memory
- We can state the first observed bottleneck with supporting metrics.
- We can document at least one improvement or tuning decision informed by load data.

## Proposed Repository Additions

- `scripts/load/`
  Simple script-based synthetic traffic generator for Kafka topics.
- `infra/grafana/`
  Local dashboard provisioning and datasource setup.
- `docs/runbooks/load-testing.md`
  Commands, scenarios, and how to read the dashboards.
- `docs/reports/`
  Optional captured benchmark summaries if we want to preserve notable runs.

## Load Model

The primary profiles should be mixed-traffic profiles that resemble production behavior:

- Smooth mixed load
  Continuous mutation traffic plus periodic `cart_abandoned` traffic and due-attempt execution.
- Bursty mixed load
  Spike in mutations followed by elevated abandoned-cart and execution traffic.
- Smooth plus bursty mixed load
  Steady background traffic with recurring burst windows, similar to promotional drops or new-fashion launches every few minutes.

Starter event mix for both primary profiles:

- `80%` mutation events
- `20%` cart state change events
  - `25%` completed or purchased events
  - `75%` abandoned events

Optional secondary profiles:

- Smooth mutation-only load
  Useful as a diagnostic baseline if we want to isolate state-write behavior.
- Bursty mutation-only load
  Useful as a diagnostic baseline if we want to isolate mutation-ingestion bottlenecks.

The first iteration should keep load shapes simple:

- one or two tenants
- deterministic cart-id generation
- stable mix percentages
- explicit ramp, sustain, and cool-down windows
- periodic burst cadence for hybrid scenarios

## Starter Scenario Defaults

These are the initial defaults for fast local iteration and should remain configurable:

- event mix
  - `80%` mutation events
  - `20%` cart state change events
    - `25%` completed or purchased events
    - `75%` abandoned events
- smooth profile timing
  - ramp: `30s`
  - sustain: `1m`
- burst profile timing
  - ramp: `30s`
  - burst: `1m`

The first hardening runs should use these defaults before we expand duration or intensity.

## Primary Metrics

Application metrics:

- events consumed per topic
- events processed per topic
- processing latency per stage
- state-write success and no-op counts
- attempts scheduled
- attempts dispatched
- attempts sent
- attempts suppressed
- attempts failed
- dispatcher claim batch size
- execution claim success/failure

Kafka metrics:

- consumer lag by topic and partition
- consumer poll rate
- records consumed rate
- producer publish rate
- publish failures

Primary source:

- Kafka broker or exporter metrics, not custom lag calculation inside the app

Database metrics:

- query latency by repository operation
- write rate
- table and row-size growth visibility for the main Postgres tables
- lock wait / contention indicators if available
- connection pool usage

Runtime metrics:

- CPU
- memory
- GC pauses
- thread counts

Business-flow metrics:

- abandoned to scheduled latency
- scheduled to dispatched latency
- dispatched to terminal outcome latency

## Dashboards

The first dashboard pack should include:

- Pipeline Overview
  End-to-end rates and outcome counts.
- Kafka Health
  Kafka metrics, topic lag, consumer throughput, and producer throughput.
- Database Health
  Connection pool usage, repository latency, write rate, and table or row-size growth visibility.
- Execution Health
  Dispatch rate, throughput by terminal outcome, suppression reasons, and failure or error reasons.
- Resource View
  CPU, memory, and GC.

Required first-pass visibility across the dashboard pack:

- Kafka metrics and lag
- end-to-end throughput
- DB row-size or storage-growth visibility
- resource utilization
- failure and error rate

## Tuning Knobs To Expose

- Kafka consumer poll batch settings
- number of consumer threads or worker concurrency
- dispatcher batch size
- dispatcher poll interval
- execution lease duration
- Postgres connection pool size
- producer flush / send settings where relevant

## Milestone Overview

| Milestone | Objective | Depends On | Checkpoint |
| --- | --- | --- | --- |
| H1 | Observability baseline | 001 current state | core metrics and dashboards render locally |
| H2 | Load generator scaffold | H1 | synthetic Kafka load can be generated repeatably |
| H3 | Smooth-load validation | H1, H2 | sustained load run produces usable throughput and lag graphs |
| H4 | Burst-load validation | H1, H2 | burst run shows queueing, recovery, and bottleneck behavior |
| H5 | Bottleneck analysis and tuning | H3, H4 | at least one measured improvement or tuning recommendation |
| H6 | Hardening report and runbook | H1-H5 | repeatable commands, dashboards, and findings are documented |

## Detailed Task List

### H1. Observability Baseline

Current status:

- completed locally

Objective:

- Ensure the current pipeline exposes enough telemetry to understand load behavior.

Expected work:

- audit current metrics and add missing counters/timers where load analysis would otherwise be blind
- provision Grafana locally
- configure Prometheus datasource and starter dashboards
- make sure Kafka, DB, dispatcher, and executor metrics are visible in one place

Definition of done:

- local dashboards load without manual ad hoc setup
- core pipeline metrics can be observed during normal operation

Required validation:

- dashboards render locally
- metrics for scheduling, dispatch, execution, and outcomes move during a small smoke run

### H2. Load Generator Scaffold

Current status:

- completed locally

Objective:

- Create a repeatable load generator for Kafka event traffic.

Expected work:

- add a simple script-based load generator under `scripts/load/`
- support fixed-rate and bursty profiles
- support mixed traffic modes first, with mutation-only modes as optional secondary profiles
- support configurable event mix percentages and run durations, with the starter defaults above
- emit a run summary with sent counts and timings

Definition of done:

- one command can start a smooth mixed-traffic profile
- one command can start a burst mixed-traffic profile
- generated events use stable and analyzable cart ids

Required validation:

- load generator publishes the expected event volume to Kafka
- the consumer app visibly processes the generated load

### H3. Smooth-Load Validation

Current status:

- completed locally

Objective:

- Measure steady-state behavior under sustained traffic.

Expected work:

- run mixed smooth load
- capture throughput, lag, DB latency, and resource usage
- identify whether lag stabilizes or grows continuously

Definition of done:

- at least one sustained mixed-traffic run is captured and summarized
- we know the highest stable local sustained rate for the current configuration

Required validation:

- exported dashboard screenshots or documented observations
- recorded throughput and lag numbers for each scenario

### H4. Burst-Load Validation

Current status:

- completed locally

Objective:

- Measure system behavior during sudden high-traffic bursts and recovery.

Expected work:

- run bursty mixed traffic
- measure queue buildup, backlog drain time, and post-burst recovery
- note whether any stage remains saturated after burst ends

Definition of done:

- at least one bursty mixed-traffic scenario is captured and summarized
- backlog growth and recovery time are known

Required validation:

- dashboard evidence of burst and recovery
- documented recovery time and first bottleneck stage

### H5. Bottleneck Analysis And Tuning

Current status:

- completed locally
- completed with a load-backed architectural conclusion rather than a runtime tuning change
- first observed pressure point is Kafka consumer lag, not persistent Postgres saturation

Objective:

- Convert observed load behavior into concrete tuning actions.

Expected work:

- identify first-order bottlenecks
- test a small number of tuning changes
- compare before/after for at least one meaningful metric
- document tradeoffs

Definition of done:

- at least one tuning change or architectural conclusion is backed by load data

Required validation:

- before/after comparison for throughput, lag, or latency

### H6. Hardening Report And Runbook

Current status:

- completed locally

Objective:

- Make the load-testing and hardening work repeatable for future sessions.

Expected work:

- write a runbook with commands, profiles, and expected outputs
- summarize stable rate, burst behavior, bottlenecks, and recommended next hardening steps
- link dashboards and key metrics

Definition of done:

- a fresh session can rerun the load tests and understand the findings quickly

Required validation:

- runbook commands are complete
- dashboard and metrics references are documented

## Recommended First Iteration

Start with these narrow steps:

1. Add Grafana and a minimal dashboard pack.
2. Add a simple script-based load generator that can produce:
   - steady mixed traffic
   - burst mixed traffic
   - optional mutation-only diagnostic traffic
3. Run one smooth profile and one burst profile.
4. Write down the first bottleneck before tuning anything.

## Locked Decisions For This Plan

- Use a simple script-based load generator.
- Use Kafka or exporter metrics as the primary lag source.
- Keep this plan scoped to local single-machine load validation.
