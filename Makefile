SHELL := /bin/sh

.PHONY: help prereqs build unit integration up down health load-smooth load-burst load-hybrid

GRADLE := GRADLE_USER_HOME=.gradle-home ./gradlew
LOAD := python3 scripts/load/generate_mixed_traffic.py

help:
	@echo "Common commands:"
	@echo "  make prereqs       Check required local tools"
	@echo "  make build         Build the recovery service"
	@echo "  make unit          Run unit tests"
	@echo "  make integration   Run integration and E2E tests"
	@echo "  make up            Start the local Docker Compose stack"
	@echo "  make down          Stop the local Docker Compose stack"
	@echo "  make health        Check health and metrics endpoints"
	@echo "  make load-smooth   Run the smooth mixed load profile"
	@echo "  make load-burst    Run the burst mixed load profile"
	@echo "  make load-hybrid   Run the smooth-bursty mixed load profile"
	@echo ""
	@echo "Optional override:"
	@echo "  make load-smooth LOAD_ARGS='--events-per-second 12 --ramp-seconds 2 --sustain-seconds 4 --cooldown-seconds 1 --cart-count 200'"

prereqs:
	@missing=0; \
	for cmd in git docker java python3 curl make; do \
	  if command -v $$cmd >/dev/null 2>&1; then \
	    printf "ok    %s -> %s\n" "$$cmd" "$$(command -v $$cmd)"; \
	  else \
	    printf "missing %s\n" "$$cmd"; \
	    missing=1; \
	  fi; \
	done; \
	if docker compose version >/dev/null 2>&1; then \
	  printf "ok    docker compose\n"; \
	else \
	  printf "missing docker compose\n"; \
	  missing=1; \
	fi; \
	if [ $$missing -ne 0 ]; then \
	  echo ""; \
	  echo "Install the missing tools, then rerun 'make prereqs' until everything is marked ok."; \
	  exit 1; \
	fi

build:
	@$(GRADLE) build

unit:
	@$(GRADLE) test

integration:
	@$(GRADLE) integrationTest

up:
	@docker compose up --build -d

down:
	@docker compose down

health:
	@curl -sf http://localhost:8080/health
	@echo ""
	@curl -sf http://localhost:9464/metrics >/dev/null
	@echo "metrics ok"

load-smooth:
	@$(LOAD) --profile smooth $(LOAD_ARGS)

load-burst:
	@$(LOAD) --profile burst $(LOAD_ARGS)

load-hybrid:
	@$(LOAD) --profile smooth-bursty $(LOAD_ARGS)
