# abandoned-cart-recovery

Monorepo for the abandoned cart recovery prototype.

See [load-testing.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/runbooks/load-testing.md) for the local hardening and load-test workflow.

## What This Repo Gives You

- a runnable local abandoned-cart recovery prototype
- Kafka, Postgres, Prometheus, and Grafana wired through Docker Compose
- unit, integration, and E2E test coverage
- load-generation scripts and dashboards for local hardening work

## Prerequisites

You need these tools available locally:

- `git`
- `docker`
- `docker compose`
- `java` (JDK 17)
- `python3`
- `curl`
- `make`

Quick prerequisite check:

```bash
make prereqs
```

If this command reports anything as `missing`, install the missing tool and rerun `make prereqs` until everything is marked `ok`.

Manual version checks if needed:

```bash
git --version
docker --version
docker compose version
java -version
python3 --version
curl --version
make --version
```

## Layout

- `apps/recovery-service`: Kotlin service scaffold
- `docs/`: RFCs, plans, and development log
- `infra/`: local runtime assets
- `ui/`: reserved for future UI work

Test layout:

- `apps/recovery-service/src/test/kotlin`: unit tests
- `apps/recovery-service/src/integrationTest/kotlin`: integration and E2E tests

## Quick Start

Start the local stack:

```bash
make up
```

Verify the service is reachable:

```bash
make health
```

Run the main automated suites:

```bash
make unit
make integration
```

## Common Commands

The simplest entrypoint is:

```bash
make help
```

Useful commands:

```bash
make prereqs
make build
make up
make down
make unit
make integration
```

Load-test entrypoints:

```bash
make load-smooth
make load-burst
make load-hybrid
```

Override the default load arguments when you want a shorter smoke run:

```bash
make load-smooth LOAD_ARGS="--events-per-second 12 --ramp-seconds 2 --sustain-seconds 4 --cooldown-seconds 1 --cart-count 200"
```

## Gradle And Docker Details

Generate the Gradle wrapper:

```bash
docker compose run --rm --profile tools build-tools gradle wrapper
```

Build the recovery service:

```bash
./gradlew build
```

Run unit tests only:

```bash
./gradlew test
```

Run integration and E2E tests only:

```bash
./gradlew integrationTest
```

The build also prepares the local distribution zip used by the Docker image:

```bash
ls apps/recovery-service/build/distributions
```

If your environment restricts writes to the default Gradle user home, run:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew build
```

If you prefer a Compose-managed build environment:

```bash
docker compose --profile tools run --rm build-tools gradle build
```

## Verification

Verify the service is reachable from the host:

```bash
curl -sf http://localhost:8080/health
```

Verify metrics are exposed from the host:

```bash
curl -sf http://localhost:9464/metrics | head -n 10
```

Run the repository integration tests against the local Compose Postgres:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew integrationTest --tests '*Repository*'
```

Run the Kafka contract and wiring tests against the local Compose Kafka broker:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew integrationTest --tests '*Contract*'
```

Run the processor integration test for Kafka-to-Postgres state ingestion:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew integrationTest --tests 'com.abandonedcart.recovery.processor.KafkaStateIngestionIntegrationTest'
```

Useful endpoints:

- `http://localhost:8080/health`
- `http://localhost:9464/metrics`
- `http://localhost:9090`
- `http://localhost:3000` (`admin` / `admin`)

Grafana provisions a starter dashboard pack automatically:

- `Pipeline Overview`
- `Kafka Health`
- `Database Health`
- `Execution Health`
- `Resource View`

## Load Testing

Use the Make targets for the common load profiles:

```bash
make load-smooth
make load-burst
make load-hybrid
```

For deeper tuning workflows and the full set of load-test commands, see [load-testing.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/runbooks/load-testing.md).
