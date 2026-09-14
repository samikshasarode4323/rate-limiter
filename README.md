# Distributed Token-Bucket Rate Limiter

A distributed rate limiter built with Java/Spring Boot and Redis, designed to correctly enforce per-client request limits across multiple horizontally-scaled service instances.

## The Problem

A single-instance rate limiter can keep a counter in memory. That breaks the moment you run more than one instance behind a load balancer: each instance has its own counter, so a client can bypass the intended limit simply by having requests routed to different servers. With 3 instances each allowing 10 requests, a client could get 30 through instead of 10.

The fix is to make all instances check and update one shared counter, in Redis, instead of local memory.

## The Real Problem: A Race Condition

Sharing state in Redis isn't enough on its own. If two instances read the same client's token count at nearly the same time, both can see "1 token left," both decide it's fine to proceed, and both decrement — allowing 2 requests when only 1 token existed.

**Naive approach (broken):**
```
GET tokens         -> 1
if tokens >= 1:
    DECR tokens      -> race window here
```

**Fix:** the check-and-decrement has to happen as a single atomic operation. This is done with a Lua script executed server-side in Redis. Since Redis executes commands (including Lua scripts) one at a time, the script runs to completion before any other client's request can touch the same key — no interleaving is possible.

```lua
-- token_bucket.lua (simplified)
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill')
-- ... compute refill based on elapsed time ...
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'last_refill', ARGV[3])
return {allowed, tokens}
```

## Architecture

```
                     ┌─────────┐
   client requests → │  Nginx  │  (load balancer, round-robin)
                     └────┬────┘
              ┌───────────┼───────────┐
              ▼           ▼           ▼
          ┌───────┐  ┌───────┐  ┌───────┐
          │ app1  │  │ app2  │  │ app3  │   Spring Boot instances
          └───┬───┘  └───┬───┘  └───┬───┘
              └───────────┼───────────┘
                          ▼
                     ┌─────────┐
                     │  Redis  │   shared token-bucket state
                     └─────────┘
```

- **Algorithm:** token bucket (10-token capacity, 1 token/sec refill) — chosen over sliding-window-log for O(1) memory per client and support for controlled bursts.
- **Atomicity:** Lua script, run server-side in Redis, for the check-and-decrement step.
- **Scaling:** 3 Spring Boot instances behind Nginx, all reading/writing the same Redis keyspace.

## Design Decisions Worth Knowing

**Why Lua and not Redis `MULTI`/`EXEC`?**
`MULTI`/`EXEC` queues commands but can't branch on a value read inside the same transaction without `WATCH` (which requires retry logic on conflict). A Lua script runs as one atomic unit and can read, compute, and conditionally write in a single round trip.

**Fail-open vs. fail-closed:**
If Redis is unreachable, the limiter fails **open** — requests are allowed through rather than blocked. Rationale: a rate limiter is a protective layer, not core business logic. Letting its outage cascade into blocking the entire API is a worse outcome than temporarily losing enforcement. This is a deliberate trade-off, not an oversight — a fail-closed variant is a one-line change if a different service's risk profile calls for it.

## Testing

### 1. Correctness — manual trace
Worked through the Lua script's logic by hand against hand-picked timestamps (bursts, gaps, refill-cap behavior) before wiring it into Java, to confirm the algorithm itself was correct independent of any framework code.

### 2. Concurrency — race condition unit test
50 threads fire simultaneously at a single client's bucket (capacity 10). Test asserts **exactly 10** are admitted, regardless of thread interleaving — proving the Lua script's atomicity actually holds under real concurrent access, not just in theory.

### 3. Load test — k6
30 concurrent virtual users, 45s run, each with a distinct client ID (so each simulated user's own token bucket behaves independently and correctly).

| Metric | Result |
|---|---|
| Requests sent | 44,035 |
| Throughput | 587 req/s |
| p95 latency | 70ms |
| p99 latency | 115ms |
| Success rate | 99.99% (44,031 / 44,035 expected responses) |
| Rate-limit violations | 0 |

Redis state was inspected directly after the run (`HGETALL ratelimit:client-N`) to confirm token counts stayed within `[0, capacity]` for every client — the pass/fail check on HTTP status alone doesn't prove this on its own.

### 4. Chaos testing — instance and dependency failure

**Killed an app instance mid-load-test** (`docker compose stop app2` while k6 was running):
- Only 4 of 44,035 requests failed — these were in-flight requests already routed to app2 at the moment it was killed (a `499` in the Nginx logs, i.e., client-side connection drop).
- Nginx detected the dead upstream and routed all subsequent traffic to the remaining 2 instances with no further impact.
- Restarted instance rejoined the pool and resumed serving traffic in under 10 seconds, with Redis state fully consistent throughout.

**Killed Redis mid-traffic:**
- Confirmed the fail-open path: requests returned `200 OK` instead of erroring, with a warning logged for each request made while Redis was unreachable.
- Enforcement resumed normally once Redis was restarted.

## How to Run

```bash
docker compose up --build
```

This starts 3 app instances, Redis, and Nginx. Hit the rate-limited endpoint through Nginx (not the app instances directly):

```bash
curl -X POST http://localhost:8080/api/demo/action -H "X-Client-Id: test1"
```

Send 11+ requests in a row with the same client ID — the 11th onward should return `429`.

### Running the load test

```bash
docker compose exec redis redis-cli FLUSHDB   # clean state before testing
k6 run loadtest/k6-script.js
```

### Running the unit tests

```bash
mvn test
```

## What I'd Do With More Time

- **Active health checks in Nginx** — currently relies on passive failure detection (a connection failing), so a *hung* (not crashed) instance wouldn't be caught as fast as a killed one.
- **Redis high availability** — a single Redis instance is itself a point of failure; production would call for Redis Sentinel or Cluster.
- **Per-client configurable limits** — currently one global capacity/refill rate for all clients; a real system would support tiered limits (e.g., free vs. paid).
- **Second algorithm for comparison** — implement sliding-window-log alongside token bucket and benchmark both under identical load, to make the algorithm choice empirical rather than just reasoned.

## Tech Stack

Java, Spring Boot, Redis (Lua scripting), Docker, Docker Compose, Nginx, k6, JUnit
