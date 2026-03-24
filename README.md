# abandoned-cart-recovery

Monorepo for the abandoned cart recovery prototype.

See [load-testing.md](/Users/ninadsindu/Projects/abandoned-cart-recovery/docs/runbooks/load-testing.md) for the local hardening and load-test workflow.

## Layout

- `apps/recovery-service`: Kotlin service scaffold
- `docs/`: RFCs, plans, and development log
- `infra/`: local runtime assets
- `ui/`: reserved for future UI work

Test layout:

- `apps/recovery-service/src/test/kotlin`: unit tests
- `apps/recovery-service/src/integrationTest/kotlin`: integration and E2E tests

## Local Development

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

Start the local runtime:

```bash
docker compose up --build -d
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
