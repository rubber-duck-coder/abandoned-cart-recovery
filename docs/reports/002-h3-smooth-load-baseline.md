# H3 Smooth Load Baseline

## Scope

This report captures the first sustained mixed-traffic baseline runs for `Execution Plan 002`.

Environment:

- local single-machine Docker Compose stack
- default dispatcher loop enabled
- mixed traffic generator from `scripts/load/generate_mixed_traffic.py`
- abandoned events backdated by `24h` so the first push touch becomes due immediately

## Run 1

Command:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 80 \
  --cart-count 2000
```

Observed generator output:

- elapsed: `90.32s`
- total events: `6050`
- mutation events: `4870`
- completed events: `315`
- abandoned events: `865`

Observed system behavior:

- peak `recovery-service` consumer lag: `646`
- lag returned to `0` immediately after the run
- sustain-phase processed rate sample: about `179.45 events/s`
- sustain-phase attempt scheduling rate sample: about `29.13 attempts/s`
- sustain-phase attempt execution rate sample: about `8.85 attempts/s`

Assessment:

- stable for the current local setup
- backlog remained bounded and drained without intervention

## Run 2

Command:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile smooth \
  --events-per-second 160 \
  --cart-count 4000
```

Observed generator output:

- elapsed: `90.33s`
- total events: `12090`
- mutation events: `9680`
- completed events: `621`
- abandoned events: `1789`

Observed system behavior:

- peak `recovery-service` consumer lag: `1317`
- lag returned to `0` immediately after the run
- ramp / sustain processed-rate samples reached about `263.64 events/s`
- sustain-phase attempt scheduling rate sample: about `41.40 attempts/s`
- sustain-phase attempt execution rate sample: about `13.35 attempts/s`

Assessment:

- still stable for the current local setup
- lag rose materially above the `80 EPS` run, but it remained bounded and drained cleanly after input stopped

## Resource And Storage Signals

Post-run observed values:

- `cart_recovery_state` estimated rows: `1859`
- `recovery_attempt` estimated rows are cumulative across runs because the same local stack was reused
- max observed `recovery_attempt` table size over the final 3-minute window: `7634944 bytes`
- max observed heap over the final 3-minute window: `39505592 bytes`

Notes:

- CPU signal from `recovery_runtime_process_cpu_load_ratio` was not especially informative in this first pass and should not be treated as a precise utilization ceiling.
- Because the same local stack was reused across runs, DB row counts after the second run are cumulative rather than isolated per scenario.

## Current Read

- Highest validated stable smooth-load rate so far: `160 EPS`
- First visible pressure signal: Kafka consumer lag growth
- Current conclusion: the system is not yet showing an unstable sustained bottleneck at `160 EPS`, but Kafka lag is the earliest and clearest pressure indicator to watch as we step further up or move into burst validation
