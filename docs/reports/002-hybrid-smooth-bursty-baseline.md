# Hybrid Smooth Plus Bursty Baseline

## Scope

This report captures the first `smooth-bursty` validation run for the hardening plan.

Profile:

- baseline rate: `80 EPS`
- sustain window: `90s`
- recurring burst window: `15s`
- burst cadence: every `30s`
- burst multiplier: `4`
- effective burst rate: `320 EPS`

Command:

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

Observed generator output:

- elapsed: `120.47s`
- total events: `15650`
- mutation events: `12507`
- completed events: `785`
- abandoned events: `2358`

Observed system behavior:

- first burst window started at second `61`
- second burst window started at second `91`
- peak `recovery-service` consumer lag over the run window: `2210`
- lag returned to `0` after the run

Post-run snapshot:

- `DISPATCHED`: `905`
- `SCHEDULED`: `14052`
- `SENT`: `6112`
- `SUPPRESSED`: `9`

Storage signal:

- max observed `recovery_attempt` table size over the last 4 minutes: `20529152 bytes`

## Current Read

- the hybrid shape behaves like an ecommerce “steady traffic plus periodic drop” scenario
- recurring burst windows create visible lag spikes
- the backlog still drains cleanly after the run ends
- this profile is a useful middle ground between the earlier pure smooth and pure burst validations
