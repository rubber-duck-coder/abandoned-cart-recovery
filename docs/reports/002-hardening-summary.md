# 002 Hardening Summary

## Scope

This is the current handoff summary for `Execution Plan 002`.

It captures:

- the validated local load scenarios
- the key observed system behavior
- the first bottleneck conclusion
- the next recommended hardening moves

## Validated Runs

| Scenario | Command Shape | Key Result |
| --- | --- | --- |
| Smooth mixed load | `--profile smooth --events-per-second 80` | stable, lag bounded, drained cleanly |
| Smooth mixed load | `--profile smooth --events-per-second 160` | still stable locally, higher lag but still drained cleanly |
| Bursty mixed load | `--profile burst --events-per-second 80 --burst-multiplier 4` | visible queue buildup, recovery in less than `1s` after input stopped |
| Smooth plus bursty mixed load | `--profile smooth-bursty --events-per-second 80 --burst-seconds 15 --burst-period-seconds 30 --burst-multiplier 4` | recurring lag spikes, no persistent backlog after the run |

Detailed baseline reports:

- [002-h3-smooth-load-baseline.md](./002-h3-smooth-load-baseline.md)
- [002-h4-burst-load-baseline.md](./002-h4-burst-load-baseline.md)
- [002-hybrid-smooth-bursty-baseline.md](./002-hybrid-smooth-bursty-baseline.md)
- [002-h5-bottleneck-analysis.md](./002-h5-bottleneck-analysis.md)

## Main Findings

- Highest validated stable smooth-load rate so far is `160 EPS` on the local single-machine stack.
- The clearest early pressure signal is Kafka consumer lag.
- The tested smooth, burst, and hybrid profiles all drained backlog after input stopped.
- We did not yet see a persistent execution backlog or lasting Postgres saturation in the current local setup.

## Current Bottleneck Read

The first likely scaling ceiling is Kafka-side processing parallelism, not the database.

Why:

- lag grows first across all tested shapes
- backlog still drains cleanly after input stops
- there is no strong evidence yet of a durable DB-side bottleneck

## Recommended Next Hardening Moves

1. increase topic partitions and match them with parallel consumer workers
2. re-run the same smooth, burst, and hybrid profiles for before/after comparison
3. isolate benchmark runs more cleanly by resetting or snapshotting the local database between scenarios
4. add longer-duration hybrid runs once the first parallelism changes are in place

## Dashboard Entry Points

- `System Overview`
- `Pipeline Overview`
- `Kafka Health`
- `Database Health`
- `Execution Health`
- `Resource View`

## Current Status

`Execution Plan 002` is far enough along that a fresh session can:

1. bring up the stack
2. run the validated load profiles
3. watch the recommended dashboards
4. understand the first bottleneck conclusion

The next useful work is not more blind benchmarking. It is targeted parallelism tuning followed by the same repeatable scenarios.
