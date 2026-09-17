# Incident 1: the Friday deploy that never finishes

**15 minutes, plus a few minutes of setup the first time. You need Docker and a browser.** Nothing to install.

> **Friday, 17:40.** Release 1.1 is tiny: a daily revenue report and one changeset, an index on
> `orders(created_at)`. In staging the migration took eight seconds.
>
> Half an hour later the new pod is still not up. Its logs say `Waiting for changelog lock....`.
>
> You're on call.

Goes with *Why your Liquibase migration hangs on Kubernetes (and where it should really run)*.

---

## 1. Start release 1.0

From this folder, in any terminal:

```sh
docker compose up -d --build
docker compose logs -f traffic
```

The first start builds two images and loads 10 million orders into PostgreSQL. That takes a few
minutes, once. When the `orders` column reaches `10.0M`, production is ready. You get one line every
5 seconds:

```text
17:38:05  answered 50/s of 50  │ errors   0%  │ p99  12 ms  │ orders 10.0M  │ changesets 3  │ created_at index no   │ lock free
```

| Column | Meaning |
|---|---|
| `answered 50/s of 50` | 40 customers per second open an order, 10 place one. How many the service answered |
| `errors` | Responses with a 5xx status |
| `p99` | 99% of answered requests took less than this |
| `orders` | Rows in the `orders` table |
| `changesets` | Rows in `DATABASECHANGELOG`: changesets applied so far |
| `created_at index` | Whether release 1.1's index exists |
| `lock` | Liquibase's lock row in `DATABASECHANGELOGLOCK`, and who holds it |

When orders-api is down, the first columns say `orders-api NOT ANSWERING`. The database columns keep
working: they come from the database, not from the service.

Leave this terminal open. Everything below happens in a second terminal and in your browser.

## 2. Deploy release 1.1

In [compose.yaml](compose.yaml), at the top, the release line:

```yaml
  LIQUIBASE_COMMAND_LABEL_FILTER: "1.0 or 1.1"
```

```sh
docker compose up -d
```

Your clone already contains release 1.1's changeset. This line tells Liquibase to apply it, the way a new
image would in production. The pod in this lab has a **startup probe of 20 seconds**, like
`periodSeconds: 10` and `failureThreshold: 2`: if the service isn't ready by then, the container is killed
and restarted.

Watch the dashboard for two minutes.

**What does the `lock` column say? Is `CREATE INDEX` still running?**

<details>
<summary>What you should see</summary>

For about 20 seconds, `CREATE INDEX running`. Then a restart, and from then on:

```text
orders-api NOT ANSWERING  │ orders 10.0M  │ changesets 3  │ created_at index no   │ LOCK HELD by orders-api-7d9f8c6b5-x2kqp for 2 min 10 s
```

The lock is held, nothing is building the index, and the service never comes back.

</details>

## 3. Do what most teams do first

"The migration is slow, give it more time." In the pod section of [compose.yaml](compose.yaml):

```yaml
      LAB_STARTUP_PROBE_SECONDS: "600"
```

```sh
docker compose up -d
```

Wait two minutes. **Does the index get built now?**

<details>
<summary>What you should see</summary>

No. The pod isn't killed anymore, but it doesn't build anything either. Its logs
(`docker compose logs --tail 20 orders-api`) show it waiting, then giving up:

```text
Waiting for changelog lock....
Waiting for changelog lock....
...
Could not acquire change log lock.  Currently locked by orders-api-7d9f8c6b5-x2kqp (172.18.0.5) since ...
```

The lab waits 1 minute for the lock (Liquibase's default is 5). More time for the probe changes nothing,
because the pod isn't slow: it's waiting for a lock.

</details>

Put `"20"` back and run `docker compose up -d`. **Change one thing at a time.**

## 4. Investigate

The service is down, so its API can't help. The database can. Open these in your browser: they run
read-only queries through pgweb, a SQL client that ships with the lab.

| Question | URL |
|---|---|
| Who holds the lock, since when? | http://localhost:8081/api/query?query=SELECT%20*%20FROM%20databasechangeloglock |
| Is any migration still running? | http://localhost:8081/api/query?query=SELECT%20pid,%20state,%20now()%20-%20query_start%20AS%20running_for,%20left(query,%2060)%20AS%20query%20FROM%20pg_stat_activity%20WHERE%20datname%20%3D%20%27orders%27%20AND%20state%20%3C%3E%20%27idle%27%20AND%20pid%20%3C%3E%20pg_backend_pid() |
| Which changesets were applied? | http://localhost:8081/api/query?query=SELECT%20id,%20labels,%20dateexecuted%20FROM%20databasechangelog%20ORDER%20BY%20orderexecuted |

And in a terminal, from this folder, the "pod" itself:

```sh
docker compose ps orders-api
```

**Three questions:**

1. `lockedby` names `orders-api-7d9f8c6b5-x2kqp`, and that "pod" still exists. Does that mean the process that took the lock is still alive?
2. Compare `lockgranted` with the `STATUS` column of `docker compose ps`. What happened to the process that took the lock?
3. Why didn't PostgreSQL release the lock when that process died?

<details>
<summary>Answer 1</summary>

No. When the kubelet restarts a container after a failed probe, it restarts it **in the same pod**: same
name, same IP. The lab does the same. So `kubectl get pod` finds the pod, and the lock still isn't held by
anything alive. The name alone doesn't tell you. What does is the time.

</details>

<details>
<summary>Answer 2</summary>

`docker compose ps` shows `Up 8 seconds` or `Restarting (137)`. 137 is the exit code of a SIGKILL. The
container started **after** `lockgranted`, so the JVM that took the lock was killed, and the query on
`pg_stat_activity` confirms nothing is building an index. On Kubernetes, `kubectl describe pod` gives the
same evidence: `Last State: Terminated`, `Exit Code: 137`, and a `Restart Count` above zero.

The logs of the kill, from `docker compose logs orders-api`:

```text
Startup probe failed: not ready after 20 s. Killing the container (exit 137), as the kubelet would.
```

</details>

<details>
<summary>Answer 3</summary>

Because Liquibase's lock isn't a database lock. It's a **row**: `locked = true`, committed before the first
changeset. Liquibase sets it back to `false` when it finishes. A killed process never finishes.

PostgreSQL did roll back the half-built index (DDL is transactional there), which is why `created_at index`
still says `no`. Only the row was left behind.

</details>

## 5. Fix it

<details>
<summary>Step 1: release the lock</summary>

Only because both checks agree: the holder is dead, and nothing is running. Open http://localhost:8081,
go to the **Query** tab, and run:

```sql
UPDATE databasechangeloglock SET locked = false, lockgranted = NULL, lockedby = NULL WHERE id = 1;
```

Watch the dashboard for a minute. **Fixed?**

</details>

<details>
<summary>Why is the lock back?</summary>

The next attempt took the lock, started the index, and was killed at 20 seconds, like the first one. You
released the symptom. The cause is still there: a migration that takes longer than the probe allows,
running **inside** the pod the probe watches.

</details>

<details>
<summary>Step 2: move the migration out of the pod</summary>

Run Liquibase in the migration Job, once, before the service starts, and turn it off in the service.
In [compose.yaml](compose.yaml), the pod:

```yaml
      LAB_STARTUP_PROBE_SECONDS: "20"
      SPRING_LIQUIBASE_ENABLED: "false"
```

and the Job:

```yaml
      RUN_MIGRATION: "true"
```

Stop the crash-looping pod first, so it can't grab the lock again:

```sh
docker compose stop orders-api
```

Release the lock (step 1's `UPDATE`), then:

```sh
docker compose up -d
```

This command waits for the Job: it has no startup probe, only its own time to finish. The index builds,
the Job exits, and only then does orders-api start, with nothing left to migrate. The Job's output is in
`docker compose logs db-migrate`.

</details>

**You're done when the dashboard prints `RESOLVED`**: release 1.1's index exists, the lock is free, and every
customer is answered for a full minute.

```text
17:52:40  answered 50/s of 50  │ errors   0%  │ p99  11 ms  │ orders 10.0M  │ changesets 4  │ created_at index yes  │ lock free  RESOLVED: release 1.1 serving for 60 s
```

## 6. What to remember

- **`Waiting for changelog lock` means someone took the lock, not that someone is working.** Check whether the holder is alive.
- **A pod name in `lockedby` proves nothing.** Restarted containers keep the pod's name. Compare `lockgranted` with the last restart, and look for running DDL.
- **Releasing the lock treats the symptom.** If the migration runs inside a pod that a probe can kill, it will happen again.
- **Migrations belong in a Job**, once per release, with Liquibase turned off in the service.

With one replica, a startup probe long enough for your slowest migration also works: try
`LAB_STARTUP_PROBE_SECONDS: "600"` with the lock released. It stops working once you have three replicas
racing for the same lock.

**On your cluster:** run `SELECT locked, lockgranted, lockedby FROM databasechangeloglock;` in every
environment, and compare your startup probe's `periodSeconds × failureThreshold` with your slowest migration.
The same setup for Kubernetes is in [kubernetes/orders-api](../../kubernetes/orders-api/): the Job is a Helm
pre-upgrade hook.

## Start over

Undo your changes to [compose.yaml](compose.yaml) (`git checkout compose.yaml` in a clone). Release 1.1's
index is already in the database, so to replay the incident, drop it in the pgweb **Query** tab:

```sql
DROP INDEX IF EXISTS orders_created_at_idx;
DELETE FROM databasechangelog WHERE id = '20260911-1740-orders-created-at-idx';
```

Then:

```sh
docker compose up -d
```

`docker compose down` stops everything and keeps the 10 million orders. `docker compose down -v` deletes them too.
