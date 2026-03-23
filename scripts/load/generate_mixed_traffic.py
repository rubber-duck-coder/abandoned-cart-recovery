#!/usr/bin/env python3

import argparse
import json
import math
import random
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, TextIO


ROOT = Path(__file__).resolve().parents[2]


@dataclass
class CartState:
    cart_id: str
    tenant_id: str
    anonymous_id: str
    user_id: str
    version: int = 0
    terminal: bool = False
    mutation_count: int = 0


class TopicProducer:
    def __init__(self, topic: str) -> None:
        self.topic = topic
        self.process = subprocess.Popen(
            [
                "docker",
                "compose",
                "exec",
                "-T",
                "kafka",
                "kafka-console-producer",
                "--bootstrap-server",
                "kafka:9092",
                "--topic",
                topic,
                "--property",
                "parse.key=true",
                "--property",
                "key.separator=\t",
            ],
            cwd=ROOT,
            stdin=subprocess.PIPE,
            text=True,
        )
        if self.process.stdin is None:
            raise RuntimeError(f"Failed to open stdin for topic producer {topic}")

    def publish(self, key: str, payload: Dict[str, object]) -> None:
        line = f"{key}\t{json.dumps(payload, separators=(',', ':'))}\n"
        self.stdin.write(line)

    @property
    def stdin(self) -> TextIO:
        if self.process.stdin is None:
            raise RuntimeError(f"Topic producer stdin closed for {self.topic}")
        return self.process.stdin

    def flush(self) -> None:
        self.stdin.flush()

    def close(self) -> None:
        if self.process.stdin is not None:
            self.process.stdin.close()
        return_code = self.process.wait(timeout=10)
        if return_code != 0:
            raise RuntimeError(f"kafka-console-producer for topic {self.topic} exited with code {return_code}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate mixed Kafka traffic for the recovery pipeline.")
    parser.add_argument("--profile", choices=["smooth", "burst", "smooth-bursty"], default="smooth")
    parser.add_argument("--events-per-second", type=int, default=40)
    parser.add_argument("--burst-multiplier", type=float, default=4.0)
    parser.add_argument("--ramp-seconds", type=int, default=30)
    parser.add_argument("--sustain-seconds", type=int, default=60)
    parser.add_argument("--burst-seconds", type=int, default=60)
    parser.add_argument("--burst-period-seconds", type=int, default=180)
    parser.add_argument("--cooldown-seconds", type=int, default=0)
    parser.add_argument("--cart-count", type=int, default=1000)
    parser.add_argument("--tenant-id", default="tenant-1")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--mutation-ratio", type=float, default=0.80)
    parser.add_argument("--state-ratio", type=float, default=0.20)
    parser.add_argument("--completed-share", type=float, default=0.25)
    parser.add_argument("--mutation-add-share", type=float, default=0.70)
    parser.add_argument("--abandoned-backdate-hours", type=float, default=24.0)
    parser.add_argument("--commerce-topic", default="commerce.cart-events")
    parser.add_argument("--abandoned-topic", default="recovery.cart-abandoned")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    if not math.isclose(args.mutation_ratio + args.state_ratio, 1.0, rel_tol=1e-6, abs_tol=1e-6):
        raise ValueError("mutation-ratio and state-ratio must sum to 1.0")
    if not 0.0 <= args.completed_share <= 1.0:
        raise ValueError("completed-share must be between 0 and 1")
    if not 0.0 <= args.mutation_add_share <= 1.0:
        raise ValueError("mutation-add-share must be between 0 and 1")
    if args.burst_period_seconds <= 0:
        raise ValueError("burst-period-seconds must be greater than 0")


def rate_for_second(second_index: int, args: argparse.Namespace) -> int:
    if second_index < args.ramp_seconds:
        progress = (second_index + 1) / max(args.ramp_seconds, 1)
        return max(1, math.ceil(args.events_per_second * progress))
    if args.profile == "smooth":
        if second_index < args.ramp_seconds + args.sustain_seconds:
            return args.events_per_second
    if args.profile == "burst":
        if second_index < args.ramp_seconds + args.burst_seconds:
            return max(1, math.ceil(args.events_per_second * args.burst_multiplier))
    if args.profile == "smooth-bursty":
        active_elapsed = second_index - args.ramp_seconds
        if active_elapsed < args.sustain_seconds:
            burst_rate = max(1, math.ceil(args.events_per_second * args.burst_multiplier))
            if in_periodic_burst_window(active_elapsed, args):
                return burst_rate
            return args.events_per_second
    if second_index < args.ramp_seconds + args.active_duration + args.cooldown_seconds:
        return max(1, math.ceil(args.events_per_second * 0.25))
    return 0


def in_periodic_burst_window(active_elapsed: int, args: argparse.Namespace) -> bool:
    if active_elapsed < args.burst_period_seconds:
        return False
    elapsed_since_first_burst = active_elapsed - args.burst_period_seconds
    return (elapsed_since_first_burst % args.burst_period_seconds) < args.burst_seconds


def build_cart_pool(args: argparse.Namespace) -> List[CartState]:
    carts = []
    for index in range(args.cart_count):
        cart_id = f"load-cart-{index:05d}"
        carts.append(
            CartState(
                cart_id=cart_id,
                tenant_id=args.tenant_id,
                anonymous_id=f"anon-{index:05d}",
                user_id=f"user-{index:05d}",
            ),
        )
    return carts


def choose_mutation_cart(carts: List[CartState], rng: random.Random) -> CartState:
    active = [cart for cart in carts if not cart.terminal]
    if not active:
        chosen = rng.choice(carts)
        chosen.terminal = False
        chosen.version = 0
        chosen.mutation_count = 0
        return chosen
    return rng.choice(active)


def choose_state_cart(carts: List[CartState], rng: random.Random) -> CartState:
    candidates = [cart for cart in carts if not cart.terminal and cart.mutation_count > 0]
    if candidates:
        return rng.choice(candidates)
    return choose_mutation_cart(carts, rng)


def next_mutation_event(cart: CartState, rng: random.Random, args: argparse.Namespace) -> Dict[str, object]:
    cart.version += 1
    cart.mutation_count += 1
    mutation_type = "item_added" if rng.random() < args.mutation_add_share else "item_removed"
    occurred_at = datetime.now(timezone.utc).isoformat()
    return {
        "cartId": cart.cart_id,
        "tenantId": cart.tenant_id,
        "mutationType": mutation_type,
        "occurredAt": occurred_at,
        "attributes": {
            "anonymousId": cart.anonymous_id,
            "userId": cart.user_id,
            "stateVersion": str(cart.version),
            "cartSnapshotJson": json.dumps(
                {
                    "items": [f"sku-{cart.version % 10:02d}"],
                    "lastMutationType": mutation_type,
                },
                separators=(",", ":"),
            ),
        },
    }


def next_completed_event(cart: CartState) -> Dict[str, object]:
    cart.version += 1
    cart.terminal = True
    occurred_at = datetime.now(timezone.utc).isoformat()
    return {
        "cartId": cart.cart_id,
        "tenantId": cart.tenant_id,
        "stateType": "purchase_completed",
        "occurredAt": occurred_at,
        "terminalReference": str(cart.version),
    }


def next_abandoned_event(cart: CartState, args: argparse.Namespace) -> Dict[str, object]:
    now = datetime.now(timezone.utc)
    abandoned_at = now - timedelta(hours=args.abandoned_backdate_hours)
    return {
        "cartId": cart.cart_id,
        "tenantId": cart.tenant_id,
        "anonymousId": cart.anonymous_id,
        "userId": cart.user_id,
        "abandonedAt": abandoned_at.isoformat(),
    }


def publish_batch(
    count: int,
    carts: List[CartState],
    args: argparse.Namespace,
    rng: random.Random,
    commerce_producer: Optional[TopicProducer],
    abandoned_producer: Optional[TopicProducer],
    stats: Dict[str, int],
) -> None:
    for _ in range(count):
        choice = rng.random()
        if choice < args.mutation_ratio:
            cart = choose_mutation_cart(carts, rng)
            event = next_mutation_event(cart, rng, args)
            if commerce_producer is not None:
                commerce_producer.publish(cart.cart_id, event)
            stats["mutation"] += 1
            continue

        state_roll = rng.random()
        if state_roll < args.completed_share:
            cart = choose_state_cart(carts, rng)
            event = next_completed_event(cart)
            if commerce_producer is not None:
                commerce_producer.publish(cart.cart_id, event)
            stats["completed"] += 1
        else:
            cart = choose_state_cart(carts, rng)
            event = next_abandoned_event(cart, args)
            if abandoned_producer is not None:
                abandoned_producer.publish(cart.cart_id, event)
            stats["abandoned"] += 1


def main() -> int:
    args = parse_args()
    validate_args(args)
    args.active_duration = args.sustain_seconds if args.profile in ("smooth", "smooth-bursty") else args.burst_seconds

    rng = random.Random(args.seed)
    carts = build_cart_pool(args)
    total_duration = args.ramp_seconds + args.active_duration + args.cooldown_seconds
    summary = {
        "profile": args.profile,
        "events_per_second": args.events_per_second,
        "ramp_seconds": args.ramp_seconds,
        "active_seconds": args.active_duration,
        "burst_seconds": args.burst_seconds,
        "burst_period_seconds": args.burst_period_seconds,
        "cooldown_seconds": args.cooldown_seconds,
        "abandoned_backdate_hours": args.abandoned_backdate_hours,
        "cart_count": args.cart_count,
        "commerce_topic": args.commerce_topic,
        "abandoned_topic": args.abandoned_topic,
        "dry_run": args.dry_run,
    }
    print(json.dumps({"config": summary}, indent=2))

    commerce_producer = None if args.dry_run else TopicProducer(args.commerce_topic)
    abandoned_producer = None if args.dry_run else TopicProducer(args.abandoned_topic)
    stats = {"mutation": 0, "completed": 0, "abandoned": 0}

    try:
        start = time.monotonic()
        for second_index in range(total_duration):
            rate = rate_for_second(second_index, args)
            second_started = time.monotonic()
            publish_batch(
                count=rate,
                carts=carts,
                args=args,
                rng=rng,
                commerce_producer=commerce_producer,
                abandoned_producer=abandoned_producer,
                stats=stats,
            )
            if commerce_producer is not None:
                commerce_producer.flush()
            if abandoned_producer is not None:
                abandoned_producer.flush()
            elapsed = time.monotonic() - second_started
            sleep_for = max(0.0, 1.0 - elapsed)
            print(
                json.dumps(
                    {
                        "second": second_index + 1,
                        "target_rate": rate,
                        "stats": stats,
                    }
                ),
                flush=True,
            )
            if sleep_for > 0:
                time.sleep(sleep_for)
        total_elapsed = time.monotonic() - start
        print(
            json.dumps(
                {
                    "result": {
                        "elapsed_seconds": round(total_elapsed, 2),
                        "stats": stats,
                        "total_events": sum(stats.values()),
                    }
                },
                indent=2,
            )
        )
        return 0
    finally:
        if commerce_producer is not None:
            commerce_producer.close()
        if abandoned_producer is not None:
            abandoned_producer.close()


if __name__ == "__main__":
    sys.exit(main())
