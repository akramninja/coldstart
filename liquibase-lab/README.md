# ColdStart Liquibase lab

Hands-on incidents for the three ColdStart articles on Liquibase with Spring Boot on Kubernetes.
Separate from [../lab](../lab/), which goes with article 1.

| Folder | Contents |
|---|---|
| [orders-api/](orders-api/) | A small Spring Boot 4.1 service (Java 25, JPA, PostgreSQL 17) whose schema is managed by Liquibase 5.0.3 |
| [incidents/](incidents/) | One folder per incident: a `compose.yaml` you edit and a `README.md` that walks you through it |
| [kubernetes/orders-api/](kubernetes/orders-api/) | Helm chart: the Deployment, and the migration Job as a pre-install/pre-upgrade hook |
| [seed/](seed/) | 10 million orders, loaded once, so migrations take production-like time |
| [load/](load/) | Traffic and the one-line dashboard |

## Incidents

| # | Incident | Article | Status |
|---|---|---|---|
| 1 | [The Friday deploy that never finishes](incidents/1-changelog-lock/): orphaned changelog lock, migration killed by a startup probe | Part 1: Why your Liquibase migration hangs on Kubernetes | ready |
| 2 | A rename in a single release: old pods fail during the rollout, `helm rollback` doesn't help | Part 2: Zero-downtime schema changes with Liquibase | planned |
| 3 | One line in an old changeset: tests green, migration refused in production | Part 3: Liquibase with Maven: changelog structure, checksums and CI | planned |

Each incident runs the same way: start it, try the usual reflex and watch it fail, investigate through
URLs in your browser, fix it, and wait for the dashboard to print `RESOLVED`.

## Prerequisites

- Docker with 4 CPUs and about 4 GB of memory, and about 3 GB of disk for the data.
- A browser. Java and Maven run inside the image build.

## orders-api on its own

The changelog follows part 3's layout:

```text
orders-api/src/main/resources/db/changelog/
├── db.changelog-master.yaml       includeAll changes/, then repeatable/
├── changes/                       one changeset per file, never edited once released
└── repeatable/                    views, runOnChange
```

Every changeset carries a label with the release that ships it (`1.0`, `1.1`...). The incidents deploy a
release by setting `LIQUIBASE_COMMAND_LABEL_FILTER`, which both the service and the migration Job read.

Tests, from `orders-api/`:

```sh
./mvnw test
```

- `ChangelogStructureTests` parses the changelog, no database needed: paths, one changeset per file, labels.
- `StartupProbeTests`: the lab's stand-in for a Kubernetes startup probe.
- `ChangelogTests` applies the whole changelog to PostgreSQL 17 with Testcontainers and validates the JPA
  entities against it. It needs Docker and is skipped without it.

The Maven plugin uses the same changelog and path as the service:

```sh
./mvnw liquibase:status -Dliquibase.url=jdbc:postgresql://localhost:5432/orders -Dliquibase.username=orders -Dliquibase.password=orders
```

## Lab-only pieces, and what they stand in for

- **`StartupProbe`** (`LAB_STARTUP_PROBE_SECONDS`): Docker Compose has no startup probe. The JVM halts itself
  with exit code 137 if it isn't ready in time, and Compose restarts it, like the kubelet restarting a
  container in the same pod.
- **`hostname: orders-api-7d9f8c6b5-x2kqp`**: so `DATABASECHANGELOGLOCK.lockedby` shows a pod name.
- **`db-migrate`** with `RUN_MIGRATION`: the migration Job. Off until you turn it on.
- **pgweb** on http://localhost:8081: SQL in the browser. Local lab only: no password, bound to localhost.
- **`max_parallel_maintenance_workers=0`** on PostgreSQL: single-threaded index builds, so a laptop's idle
  database doesn't hide how long a `CREATE INDEX` takes on a busy one.
