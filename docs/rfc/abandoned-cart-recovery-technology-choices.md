# RFC: Abandoned Cart Recovery Technology Choices

## Status

Draft

## Purpose

This document records the primary technology choices for the abandoned cart recovery system and explains why they are the most pragmatic fit for this design and for the engineer profile on the project.

## Decision Summary

| Area | Choice | Why |
| --- | --- | --- |
| Service language | Kotlin | Best fit for team comfort, strong typing, good concurrency model, and good backend ecosystem |
| Dependency injection | Guice | Lighter and more explicit than Spring for a small focused service |
| Event backbone | Kafka | Strong fit for ordered partitioned event processing, replay, scaling, and the engineer's experience |
| Primary transactional store | Postgres | Pragmatic operational choice for relational state, idempotency, and scheduler or attempt tracking |
| Wide-column store | Not chosen initially | Cassandra adds operational cost that is not justified yet for the take-home scope |
| Async message contracts | Protobuf | Strong schema discipline, versioning support, compact payloads |
| External API style | REST + JSON when needed | Simpler for platform integrations and operational tooling |
| Internal synchronous RPC | Avoid initially | No clear need yet; do not introduce gRPC without a concrete synchronous boundary |
| Analytics emission | Structured events into analytics pipeline | Required for experiment analysis and safer than relying on logs |

## Rationale

### Kotlin

Kotlin is the default implementation language because:
- it is the strongest fit for the engineer's strengths
- it supports clean domain modeling for state transitions and event handling
- it is a good long-term fit for a production backend

Python is intentionally not the primary choice because this system has enough concurrency, contract rigor, and correctness-sensitive state transitions to benefit from a stronger typed runtime.

### Guice Over Spring

Guice is the pragmatic choice because:
- the service does not need the full Spring ecosystem
- dependency wiring can stay explicit and lightweight
- startup and operational complexity stay lower

### Kafka

Kafka is the right transport because:
- the architecture already relies on partitioned ordered processing per `cart_id`
- replay is useful for debugging and backfills
- separate topics map naturally to the scaling and HOL-avoidance design
- the engineer is already comfortable with Kafka

### Postgres

Postgres is the initial primary store because:
- `cart_recovery_state` and `recovery_attempt` are transactional, relational records
- idempotency constraints and conditional writes are straightforward
- auditability and queryability are strong
- it is faster to justify and easier to operate than Cassandra for this scope

### Why Not Cassandra Initially

Cassandra is not the default choice because:
- it adds operational and schema complexity
- it complicates transactional correctness for attempts, suppression, and idempotency
- the current design can scale meaningfully with Kafka partitioning plus Postgres before needing this jump

Cassandra can remain a future option if write scale, retention volume, or multi-region topology clearly demand it.

### Protobuf for Async Contracts

Protobuf is the default for service-owned async topics because:
- versioning is cleaner
- payloads are compact
- contracts are explicit and easy to reason about

### REST and JSON for External Platform Boundaries

Where the system needs synchronous integration with external platforms such as experimentation or frequency capping, REST with JSON is the pragmatic default because:
- many platform teams already expose REST APIs
- it is easy to debug operationally
- it avoids introducing gRPC unless we clearly need it

### No gRPC by Default

gRPC is intentionally not chosen as a default because the current architecture is event-driven, not RPC-driven.

If a future internal low-latency synchronous service boundary appears, gRPC can be reconsidered then.

## Concrete Technology Decisions

### Core Service Stack

- Kotlin
- Guice
- Kafka client libraries
- Postgres driver and migration tooling
- Protobuf for internal topic schemas

### Storage Layout

- Postgres for `cart_recovery_state`
- Postgres for `recovery_attempt`
- Postgres for idempotency and audit metadata associated with recovery processing

### Event Topics

- Kafka topic: `commerce.cart-events`
- Kafka topic: `recovery.cart-mutations`
- Kafka topic: `recovery.cart-state-events`
- Kafka topic: `recovery.cart-abandoned`
- Kafka topic: `recovery.recovery-attempts`
- analytics pipeline topic or sink: `analytics.recovery-events`

### Contract Choices

- Protobuf for recovery-owned Kafka topics
- JSON for external REST integrations
- analytics event shape may be flattened JSON if that improves downstream analytics ingestion, even if operational topics use Protobuf

## Scaling Posture

### Partitioning

- Kafka partition key: `cart_id`
- correctness and relative ordering guarantee target: per `cart_id`, not global

### Database Scaling

- start with a single Postgres primary plus read replicas as needed
- keep `cart_recovery_state` unsharded initially
- use time-based partitioning for high-growth tables like `recovery_attempt` if write volume warrants it
- reserve logical or physical sharding for a later scale threshold, likely by tenant or by hashed `cart_id`

### Sharding Decision

Do not start with application-level sharding.

Reason:
- it is unnecessary complexity for the current scope
- Kafka partitioning already provides the first layer of horizontal scale
- Postgres plus partitioning is the simpler initial path

## Benchmarking and Capacity Planning

Public benchmark numbers are useful as reference points, not guarantees. The actual supported scale of this design depends on:
- event size
- replication factor
- partition count
- state-write amplification
- experiment and cap-service latency
- analytics emission cost

For this design, the likely first bottleneck is not Kafka ingress. It is more likely to be one of:
- Postgres write throughput on `cart_recovery_state` and `recovery_attempt`
- synchronous external checks such as experimentation or frequency capping
- executor fan-out to notification providers

### Kafka Benchmarks To Use

Use Kafka’s own producer and consumer perf tools plus cluster-level replay and lag testing:
- `kafka-producer-perf-test.sh`
- `kafka-consumer-perf-test.sh`
- sustained consumer lag tests under replication and replay

Primary metrics:
- MB/s in and out
- messages/s
- producer latency p50, p95, p99
- consumer lag
- broker disk utilization
- broker network utilization
- partition skew

Reference points from public Kafka benchmarks:
- Confluent’s published Apache Kafka benchmark reports peak stable throughput of `605 MB/s` on a three-broker cluster with `100 partitions`, `1 KB` messages, and replication factor `3`
- the same benchmark reports `p99` end-to-end latency of about `5 ms` at `200 MB/s` load
- Kafka’s official site describes production scale targets up to `thousand brokers`, `trillions of messages per day`, and `hundreds of thousands of partitions`

Inference for this design:
- at `1 KB` average event size, `605 MB/s` is on the order of `~600k messages/s` cluster-wide
- our recovery system is therefore unlikely to hit Kafka first unless upstream event volume is extremely large or partitioning is badly skewed

### Postgres Benchmarks To Use

Use PostgreSQL’s official `pgbench` plus custom scripts that mirror our real write patterns:
- built-in `select-only`
- built-in `simple-update`
- custom state-write script for `cart_recovery_state`
- custom attempt-scheduling script for `recovery_attempt`

Primary metrics:
- TPS
- p50, p95, p99 latency
- WAL write and sync time
- lock wait time
- index bloat
- replication lag if replicas are present

Reference points from public PostgreSQL-compatible benchmarks:
- PostgreSQL’s official `pgbench` is the standard benchmark tool for repeated transactional and custom SQL workloads
- an AWS RDS for PostgreSQL benchmark with `pgbench` reported about `13,397 TPS` and `4.5 ms` average latency in the tested configuration
- an AWS PostgreSQL read-only `pgbench` benchmark with PgBouncer reported about `26,203 TPS` in the tested configuration

Inference for this design:
- the system should be sized assuming Postgres is the first hard throughput constraint
- state writes and attempt writes need to stay well below the sustained TPS envelope validated in our own environment

### End-to-End Benchmarks To Use

Component benchmarks are not enough. We also need end-to-end scenarios:
- burst of upstream `cart_abandoned` events
- mixed load of mutation events plus purchase invalidations
- scheduler backlog catch-up
- executor replay after partial failure
- experiment-heavy traffic with analytics emission enabled

Primary metrics:
- abandoned events accepted per second
- attempts scheduled per second
- executor completions per second
- time from `cart_abandoned` to scheduled attempt creation
- time from due attempt to final send or suppress decision
- percentage of attempts suppressed by reason
- analytics event delivery success and lag

### Capacity Planning Rule of Thumb

Use this ordering when sizing the system:
1. size Kafka partitions and brokers for upstream event ingress
2. size Postgres for direct state writes and attempt creation TPS
3. size executor fleet for due-attempt bursts and downstream RPC latency
4. verify analytics emission does not become a hidden tail-latency amplifier

### Sources

- Apache Kafka official overview and scale claims: https://kafka.apache.org/
- Confluent Apache Kafka benchmark: https://developer.confluent.io/learn/kafka-performance/
- PostgreSQL `pgbench` documentation: https://www.postgresql.org/docs/current/pgbench.html
- AWS RDS for PostgreSQL benchmark with `pgbench`: https://aws.amazon.com/blogs/database/benchmark-amazon-rds-for-postgresql-with-dedicated-log-volumes/
- AWS PostgreSQL read-only `pgbench` benchmark via PgBouncer: https://aws.amazon.com/blogs/database/fast-switchovers-with-pgbouncer-on-amazon-rds-multi-az-deployments-with-two-readable-standbys-for-postgresql/

## Tradeoffs

- Postgres is simpler and stronger for correctness, but may need later partitioning or sharding if scale grows aggressively.
- Kafka adds operational complexity relative to a simpler queue, but it is justified by replay, partitioning, and the event-driven architecture.
- Using both Protobuf and JSON increases contract surface slightly, but keeps internal and external interfaces pragmatic for their consumers.

## What This Enables

- strong alignment with the engineer's implementation strengths
- a believable production-ready architecture
- a practical path to build the MVP without fighting the toolchain
- a clear story for future scale if the system grows beyond initial assumptions
