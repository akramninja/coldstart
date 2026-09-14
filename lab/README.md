# ColdStart lab

Reproducible before/after measurements for ColdStart articles, on a realistic Spring Boot service.

| Folder | Contents |
|---|---|
| [order-service/](order-service/) | The Spring Boot service under test |
| [scenarios/](scenarios/) | One env file per before/after condition |
| [load/](load/) | k6 load profiles |
| [monitoring/](monitoring/) | Prometheus and the Grafana dashboard |
| [production/](production/) | A configuration baseline and alert rules to take away |

## Prerequisites

- Docker with **8 CPUs** and about 6 GB of memory. Cores are split: service 0-3, PostgreSQL 4-5, tools 6-7.
- Nothing else: Java and Maven run inside the image build.

## Article 1: 5 Spring Boot defaults that break under Kubernetes limits

| Default | Before | After | k6 profile | Watch in Grafana |
|---|---|---|---|---|
| 1. Heap at 25% of the pod | `d1-heap-before` | `d1-heap-after` | `heap` | heap used vs max, GC pause/s, restarts |
| 2. 200 Tomcat threads | `d2-threads-before` | `d2-threads-after` (+ `d2-threads-capped`) | `threads` | CPU vs limit, throttled %, busy threads |
| 3. open-in-view | `d3-osiv-before` | `d3-osiv-after` (+ `d3-osiv-control`) | `osiv` | Hikari active/pending, acquire p99 |
| 4. Unbounded `@Async` queue | `d4-async-before` | `d4-async-after` | `async` | queued tasks, confirmations/s, live data |
| 5. Cache that never evicts | `d5-cache-before` | `d5-cache-after` | `cache` | cache entries, live data after GC |

Every scenario pins the heap at `MaxRAMPercentage=70` except Default 1's before, so each one changes a single default.

### Run one scenario

From this folder, in any terminal. Pass the same `--env-file` to every command.

```sh
# 1. Start the stack under the scenario's conditions. The first start seeds PostgreSQL
#    (100k customers, 200k products, 200k orders) and logs "Seeded ... in N s".
docker compose --env-file scenarios/d3-osiv-before.env up -d --build

# 2. Evidence: the defaults the running beans actually have.
#    Open http://localhost:9090/actuator/info in a browser, under "defaults".

# 3. Load. The summary prints p99, failed requests and dropped iterations per step.
docker compose --env-file scenarios/d3-osiv-before.env run --rm --no-deps k6 run -e PROFILE=osiv /scripts/defaults.js

# 4. The after condition: only order-service is recreated, data and metrics are kept.
docker compose --env-file scenarios/d3-osiv-after.env up -d --force-recreate order-service
docker compose --env-file scenarios/d3-osiv-after.env run --rm --no-deps k6 run -e PROFILE=osiv /scripts/defaults.js
```

- **Grafana:** http://localhost:3000, dashboard *order-service: 5 Spring Boot defaults*, one row per default
- **Prometheus:** http://localhost:9091

Tune a run with `-e STEPS=200,400,600 -e STEP_SECONDS=90 -e MAX_VUS=2000`.
The step-by-step measurement guide lives next to the article: `articles/01-spring-boot-defaults-extra-pods/guide.md`.

### Before publishing a number

- **Load levels are starting points.** Adjust `STEPS` until the before condition shows its failure mode, then run the after condition with the same steps.
- **Repeat each run 3 times.** One run shows a direction, not a ratio.
- **Recreate order-service between runs**, so heap, caches and JIT start cold every time.

### Reset

```sh
docker compose --env-file scenarios/d3-osiv-before.env down        # keeps the seeded database
docker compose --env-file scenarios/d3-osiv-before.env down -v     # also deletes data and metrics
```
