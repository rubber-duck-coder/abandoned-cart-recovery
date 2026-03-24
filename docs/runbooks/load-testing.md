# Load Testing Runbook

## Purpose

This runbook covers the first local load-testing workflow for `002-hardening-and-load-validation.md`.

## Prerequisites

Start the local stack:

```bash
docker compose up --build -d
```

Confirm the core surfaces are up:

```bash
curl -sf http://localhost:8080/health
curl -sf http://localhost:9464/metrics | head -n 20
curl -sf http://localhost:9090/-/healthy
```

Open Grafana:

- `http://localhost:3000`
- username: `admin`
- password: `admin`

## Starter Mixed Load

The generator uses the locked starter defaults from `002`:

- `80%` mutation events
- `20%` cart state change events
  - `25%` completed or purchased
  - `75%` abandoned
- `30s` ramp
- `1m` steady sustain for `smooth`
- `1m` burst window for `burst`
- recurring burst windows can be layered on top of smooth traffic with `smooth-bursty`

The generator also backdates abandoned events by `24h` by default so the current `24h` first touch becomes due immediately and flows into dispatch plus execution locally.

## Smooth Mixed Profile

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 40
```

## Burst Mixed Profile

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile burst \
  --events-per-second 40 \
  --burst-multiplier 4
```

## Smooth Plus Bursty Profile

Use this for “steady baseline traffic plus periodic deal drops” behavior:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth-bursty \
  --events-per-second 80 \
  --sustain-seconds 600 \
  --burst-seconds 60 \
  --burst-period-seconds 180 \
  --burst-multiplier 4
```

This keeps a smooth `80 EPS` baseline, then adds a `60s` burst every `180s`.

## Fast Iteration Smoke Run

Use this before a longer run:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 12 \
  --ramp-seconds 2 \
  --sustain-seconds 4 \
  --cooldown-seconds 1 \
  --cart-count 200
```

## Useful Knobs

- `--events-per-second`
- `--burst-multiplier`
- `--ramp-seconds`
- `--sustain-seconds`
- `--burst-seconds`
- `--burst-period-seconds`
- `--cooldown-seconds`
- `--cart-count`
- `--abandoned-backdate-hours`
- `--seed`

Set `--abandoned-backdate-hours 0` if you want to isolate scheduling without making the first touch due immediately.

## What To Watch

Grafana dashboards:

- `Pipeline Overview`
- `System Overview`
- `Kafka Health`
- `Database Health`
- `Execution Health`
- `Resource View`

Prometheus / metrics signals:

- `recovery_kafka_events_processed_total`
- `recovery_state_writes_total`
- `recovery_attempts_scheduled_total`
- `recovery_attempts_dispatched_total`
- `recovery_attempts_executed_total`
- `recovery_repository_operation_duration_ms`
- `recovery_db_table_size_bytes`
- `recovery_runtime_heap_used_bytes`
- Kafka exporter lag metrics such as `kafka_consumergroup_lag`

## Validated Local Scenarios

These are the currently validated baseline commands:

Smooth baseline:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 80 \
  --cart-count 2000
```

Higher smooth baseline:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 160 \
  --cart-count 4000
```

Burst baseline:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile burst \
  --events-per-second 80 \
  --burst-multiplier 4 \
  --cart-count 4000
```

Hybrid smooth plus bursty baseline:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth-bursty \
  --events-per-second 80 \
  --sustain-seconds 90 \
  --burst-seconds 15 \
  --burst-period-seconds 30 \
  --burst-multiplier 4 \
  --cart-count 4000
```

## Current Read

- highest validated stable smooth-load rate so far: `160 EPS`
- earliest pressure indicator: Kafka consumer lag
- current tested burst and hybrid scenarios still drained backlog after input stopped
- next recommended hardening move: increase Kafka-side processing parallelism and then rerun the same scenarios

Summary reports:

- [002-hardening-summary.md](../reports/002-hardening-summary.md)
- [002-h5-bottleneck-analysis.md](../reports/002-h5-bottleneck-analysis.md)

## Notes

- The generator publishes mutations and completed state events to `commerce.cart-events`.
- It publishes abandoned events to `recovery.cart-abandoned`.
- The runtime service now runs a periodic dispatcher loop, so due attempts are picked up automatically during local load runs.
