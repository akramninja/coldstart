# order-service

The workload behind ColdStart article 1, *5 Spring Boot defaults that break under Kubernetes limits*.

A small, realistic Spring Boot service: a product catalog, orders in PostgreSQL, a CPU-bound pricing engine and
asynchronous confirmation e-mails. Each of the five defaults has a code path that triggers it. The code never changes
between before and after: only the scenario file does.

Java 25, Spring Boot 4.1.1 (Spring MVC on Tomcat), Spring Data JPA, Flyway, PostgreSQL 17, Caffeine, Actuator, Prometheus.

## Build and test

```sh
./mvnw clean package
```

Tests run on H2: no Docker, no PostgreSQL. To run the scenarios, see [../README.md](../README.md).

## Endpoints

| Endpoint | What it does | Default |
|---|---|---|
| `GET /api/products/{sku}` | `@Cacheable("products")` catalog lookup, e.g. `SKU-0000042` | 1 heap, 5 cache |
| `POST /api/quotes` | Prices a cart: every line against 10,000 promotion rules, no I/O (~1.7 ms of CPU for 20 lines) | 2 threads vs CPU |
| `GET /api/orders/{id}` | Reads an order, then calls a carrier API (simulated, 100 ms) | 3 open-in-view |
| `POST /api/orders` | Commits an order, then sends a ~15 KB confirmation with `@Async` (simulated provider, 200 ms) | 4 async queue |

Actuator is on port 9090: `health`, `info`, `metrics`, `prometheus`, `caches`.

```sh
curl -s localhost:8080/api/products/SKU-0000042
curl -s localhost:8080/api/orders/1
curl -s -X POST localhost:8080/api/quotes -H 'Content-Type: application/json' \
  -d '{"tier":"GOLD","country":"FR","lines":[{"sku":"SKU-0000001","category":"audio","unitPrice":"149.90","quantity":3}]}'
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":7,"country":"DE","lines":[{"sku":"SKU-0000010","quantity":2}]}'
```

## Evidence for every run

Once ready, the service logs the defaults the running JVM and beans actually have
([`EffectiveDefaults`](src/main/java/dev/coldstart/orders/lab/EffectiveDefaults.java)). Keep it with every result.

```text
 DEFAULTS IN EFFECT (read from the running JVM and Spring beans)
 1 maxHeap                  : 3942 MiB (4133486592 bytes)
 1 MaxRAMPercentage         : 25.0 (DEFAULT)
 ...
 2 tomcat.maxThreads        : 200
 3 openInView               : true
 3 hikari.maximumPoolSize   : 10
 4 task core/max/queue      : 8 / 2147483647 / 2147483647
 5 cacheManager             : CaffeineCacheManager
```

## What the tests prove

| Test | Proves |
|---|---|
| `OrderServiceIntegrationTests` | Spring Boot 4.1.1 defaults as the beans have them: 200 Tomcat threads, open-in-view on, Hikari 10, executor 8 / unbounded / unbounded; every metric the lab graphs is published |
| `OpenInViewConnectionTests$DefaultOpenInView` | Open-in-view on: the request holds its connection during the carrier call (`[1, 1, 1, 1, 1]`) |
| `OpenInViewConnectionTests$OpenInViewOnWithProjection` | The fetch-join query alone doesn't help: still `[1, 1, 1, 1, 1]` |
| `OpenInViewConnectionTests$OpenInViewOffWithProjection` | Open-in-view off + fetch join: the connection is back in the pool (`[0, 0, 0, 0, 0]`) |
| `OpenInViewConnectionTests$OpenInViewOffWithLazyEntities` | Turning it off without fixing the code: `LazyInitializationException` |
| `ExecutorAndCacheScenarioTests$BoundedExecutor` | A bounded queue rejects, and the confirmation is `DEFERRED` instead of held in the heap |
| `ExecutorAndCacheScenarioTests$SimpleCache` | The simple cache is a plain `ConcurrentHashMap` that keeps every entry |
| `ExecutorAndCacheScenarioTests$CaffeineWithoutSpec` | Caffeine without a spec never evicts either (`UnboundedLocalCache`) |
| `ConfirmationEmailTests` | A typical confirmation weighs about 15 KB |

## Design choices

- **Defaults stay defaults.** `application.yaml` sets none of the five. Scenario files change them from outside.
- **Deterministic data.** Every customer, product and order is a pure function of its id ([`Dataset`](src/main/java/dev/coldstart/orders/lab/Dataset.java)), so runs and k6 agree on the data. Seeding happens before the web server starts.
- **Remote calls are sleeps.** The carrier and the e-mail provider block the thread exactly as a socket read would, without a second service or network noise.
- **The CPU path touches no database.** `POST /api/quotes` measures CPU limits, not PostgreSQL.
- **Container metrics from the inside.** CPU throttling and memory come from cgroup v2 files (`cgroup_*`), the same values cAdvisor publishes on Kubernetes.
- **No heap flag in the image.** It would hide Default 1. `ExitOnOutOfMemoryError` turns heap exhaustion into a restart, as on Kubernetes.
