# H5 Bottleneck Analysis

## Scope

This report consolidates the first local load-validation findings for `Execution Plan 002` and converts them into the first hardening conclusion.

Inputs used:

- [002-h3-smooth-load-baseline.md](./002-h3-smooth-load-baseline.md)
- [002-h4-burst-load-baseline.md](./002-h4-burst-load-baseline.md)
- [002-hybrid-smooth-bursty-baseline.md](./002-hybrid-smooth-bursty-baseline.md)

## Summary Of Measured Runs

| Scenario | Rate Shape | Total Events | Peak Consumer Lag | Recovery After Input Stops |
| --- | --- | ---: | ---: | --- |
| Smooth | `80 EPS` | `6050` | `646` | drained to `0` immediately |
| Smooth | `160 EPS` | `12090` | `1317` | drained to `0` immediately |
| Burst | `80 EPS` base, `320 EPS` burst | `20450` | `2612` | drained to `0` in less than `1s` |
| Smooth plus bursty | `80 EPS` base, recurring `320 EPS` burst windows | `15650` | `2210` | drained to `0` after the run |

## First Hardening Conclusion

The first observed pressure point is Kafka consumer lag, not persistent Postgres saturation.

Why:

- Lag was the earliest signal to rise across smooth, burst, and hybrid scenarios.
- The system still drained backlog to `0` after every tested run.
- We did not yet observe a lasting execution backlog or a persistent DB write stall after input stopped.
- The current implementation uses a single local service instance with limited effective Kafka processing parallelism, so consumer-side ingestion and processing throughput is the most plausible first-order ceiling.

## What This Means

The next scaling step should focus on Kafka-side parallelism and workload partitioning before deeper DB optimization.

Most useful next hardening moves:

1. increase topic partitions for the hot ingestion paths and match them with parallel consumer workers
2. separate hot-path mutation/state ingestion from slower downstream execution paths if lag starts coupling unrelated traffic
3. keep dispatcher batch and poll tuning as a secondary lever for due-attempt latency, not the primary throughput lever
4. reset or isolate the local database between benchmark runs when cleaner storage-growth comparisons are needed

## Tuning Decision For This Iteration

No runtime tuning change was applied in this pass.

Reason:

- the current data already supports a clear architectural conclusion
- the local system still recovers cleanly after all tested runs
- the next meaningful tuning step should be parallel-consumer oriented rather than an arbitrary config tweak

This still satisfies `H5` because the plan allows a load-backed architectural conclusion in place of an immediate tuning change.

## Current Confidence

- The prototype handles the current local smooth, burst, and hybrid profiles without leaving a persistent backlog.
- Kafka lag should be treated as the primary leading indicator during future scale-up work.
- The next hardening phase should target controlled increases in processing parallelism and then re-run the same scenarios for before/after comparison.
