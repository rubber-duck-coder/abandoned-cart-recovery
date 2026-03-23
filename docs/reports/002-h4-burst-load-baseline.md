# H4 Burst Load Baseline

## Scope

This report captures the first mixed-traffic burst validation run for `Execution Plan 002`.

Environment:

- local single-machine Docker Compose stack
- burst profile from `scripts/load/generate_mixed_traffic.py`
- base rate `80 EPS`
- burst multiplier `4`
- effective burst window `320 EPS`
- abandoned events backdated by `24h`

## Run

Command:

```bash
python3 scripts/load/generate_mixed_traffic.py \
  --profile burst \
  --events-per-second 80 \
  --burst-multiplier 4 \
  --cart-count 4000
```

Observed generator output:

- elapsed: `90.34s`
- total events: `20450`
- mutation events: `16339`
- completed events: `1020`
- abandoned events: `3091`

## Observations

Burst behavior:

- peak `recovery-service` consumer lag: `2612`
- mid-burst processed-rate sample: about `192.60 events/s`
- mid-burst attempt-execution sample: about `8.76 attempts/s`

Recovery behavior:

- lag returned to `0` on the first post-run poll
- observed recovery time was less than `1s` after the generator stopped

Storage growth:

- max observed `recovery_attempt` table size over the final 3-minute window: `15450112 bytes`

Post-run status snapshot:

- `DISPATCHED`: `214`
- `SCHEDULED`: `10410`
- `SENT`: `4982`
- `SUPPRESSED`: `9`

Notes:

- The status snapshot is cumulative because the same local stack was reused across earlier runs.
- The large `SCHEDULED` count is expected because each abandoned event still creates later `72h` and `168h` touches that remain in the future.

## Current Read

- The first clear burst-pressure indicator is Kafka consumer lag
- The system tolerated a `320 EPS` burst window with a visible lag spike but no persistent post-burst backlog
- For this local setup, burst recovery currently looks healthy enough that the next useful step is targeted bottleneck analysis and selective tuning rather than more structural changes
