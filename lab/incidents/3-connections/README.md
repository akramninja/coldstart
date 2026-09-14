# Incident 3: orders time out at lunch, and the CPU is idle

**10 minutes. You need Docker and a browser.** Nothing to install, no script to paste.

> **12:30, lunch peak.** Customers can't open their orders. Pages hang, then time out.
> The CPU graph is calm. The database is fine. Nobody deployed anything today.
>
> You're on call.

---

## 1. Start the incident

From this folder, in any terminal:

```sh
docker compose up -d --build
docker compose logs -f traffic
```

The first start builds the service and fills the database: a few minutes. Until it's ready, the
dashboard says `order-service is not answering`. Then you get one line every 5 seconds:

```text
15:14:33  answered  97/s of 150  │ errors   0%  │ p99   7.2 s  │ CPU 0.26 of 2 cores  │ DB pool 10/10 in use, 190 waiting
```

| Column | Meaning |
|---|---|
| `answered 97/s of 150` | 150 customers per second open an order. The service finishes 97. The others wait, then give up after 10 s |
| `errors` | Responses with a 5xx status |
| `p99` | 99% of answered requests took less than this |
| `CPU` | Cores the pod actually uses, out of its limit |
| `DB pool` | Database connections in use, out of the pool size, and requests waiting for one |

Leave this terminal open. Everything below happens in a second terminal and in your browser.

## 2. Do what most teams do first

Slow service, so add CPU. In [compose.yaml](compose.yaml), in the pod section:

```yaml
          cpus: "4"
```

```sh
docker compose up -d
```

Only order-service restarts, and the traffic keeps coming. Watch the dashboard for a minute.

**Did `answered` go up? What did the CPU do?**

<details>
<summary>What you should see</summary>

Nothing changes. Still about 97 answered out of 150, and the pool is still 10/10 with 190 waiting.
The pod now has 4 cores and uses a fraction of one. CPU was never the limit.

</details>

Put `cpus: "2"` back and run `docker compose up -d`. **Change one thing at a time.**

## 3. Investigate

Open these in your browser. They're the service's own API, the same data your monitoring scrapes in
production.

| Question | URL |
|---|---|
| How many connections are in use? | http://localhost:9090/actuator/metrics/hikaricp.connections.active |
| How many requests wait for one? | http://localhost:9090/actuator/metrics/hikaricp.connections.pending |
| How long did the slowest wait? (`MAX`, seconds) | http://localhost:9090/actuator/metrics/hikaricp.connections.acquire |
| What settings does the pod really run with? | http://localhost:9090/actuator/info |

Real output during the incident, trimmed:

```json
{"description":"Pending threads","measurements":[{"statistic":"VALUE","value":190.0}],"name":"hikaricp.connections.pending"}
{"description":"Connection acquire time","measurements":[{"statistic":"COUNT","value":5820.0},{"statistic":"TOTAL_TIME","value":11097.38},{"statistic":"MAX","value":13.84}],"name":"hikaricp.connections.acquire"}
```

A request spent almost 14 seconds just waiting for a database connection. The database itself answers
in about a millisecond. So what holds the 10 connections?

**The code.** [OrderController](../../order-service/src/main/java/dev/coldstart/orders/order/OrderController.java)
reads the order, **then** calls the carrier API, which takes 100 ms:

```java
OrderView order = reader.read(id);                                                     // database
return order.withShipping(shippingQuotes.quote(order.country(), order.itemCount()));  // 100 ms HTTP call
```

**Two questions:**

1. The pool has 10 connections. If each request keeps its connection for the whole 100 ms carrier call, how many requests per second can the pod serve at most? Compare with the dashboard.
2. In `/actuator/info`, find `openInView`. What does it do to the connection after `reader.read(id)` returns?

<details>
<summary>Answer 1</summary>

10 connections ÷ 0.1 s = **100 requests per second at most**, whatever the CPU. The dashboard shows 97.
The math matches: the pool is the limit.

</details>

<details>
<summary>Answer 2</summary>

`spring.jpa.open-in-view` is `true`, Spring Boot's default. The persistence context, and the connection
under it, stay bound to the request until the response is written. So the connection is held during
the carrier call, doing nothing. Spring Boot even warned about it at startup, in the logs
(`docker compose logs order-service`):

```text
WARN ... spring.jpa.open-in-view is enabled by default. Therefore, database queries may be performed during view rendering.
```

</details>

## 4. Fix it

Try your own fix first. In the pod's `environment`, a Spring Boot property `spring.jpa.open-in-view`
becomes `SPRING_JPA_OPEN_IN_VIEW`.

<details>
<summary>Step 1: turn it off</summary>

```yaml
    environment:
      JAVA_OPTS_APPEND: -XX:MaxRAMPercentage=70
      SPRING_JPA_OPEN_IN_VIEW: "false"
```

```sh
docker compose up -d
```

The pool is free now, but look at `errors`. Find out which exception with the API:
http://localhost:9090/actuator/metrics/http.server.requests?tag=status:500

</details>

<details>
<summary>Why 100% errors?</summary>

`LazyInitializationException`. The code loaded the order, then read its customer, lines and products
**after** the query, lazily, one query each. That only worked because open-in-view kept the session
open. Turning it off shows you the hidden queries you were already running.

The real fix is in the code: load everything the response needs in one query, inside a read-only
transaction. It's already written in
[OrderReader.ProjectionReader](../../order-service/src/main/java/dev/coldstart/orders/order/OrderReader.java):

```java
@Transactional(readOnly = true)
public OrderView read(long id) {
    return orders.findWithDetailsById(id).map(OrderView::from).orElseThrow(() -> notFound(id));
}
```

</details>

<details>
<summary>Step 2: switch to the fixed code</summary>

The image contains both versions. This lab switch stands in for deploying the code change:

```yaml
    environment:
      JAVA_OPTS_APPEND: -XX:MaxRAMPercentage=70
      SPRING_JPA_OPEN_IN_VIEW: "false"
      COLDSTART_ORDERS_READ_MODEL: projection
```

```sh
docker compose up -d
```

</details>

**You're done when the dashboard prints `RESOLVED`**: a full minute with every customer answered, no
errors, nobody waiting for a connection, and p99 under 500 ms. Real run:

```text
15:20:31  answered 150/s of 150  │ errors   0%  │ p99  112 ms  │ CPU 0.30 of 2 cores  │ DB pool 0/10 in use,   0 waiting  RESOLVED
```

Same pod, same 2 CPUs, same 10 connections: from 97 to 150 answered per second, from 7 s to 112 ms.

## 5. What to remember

- **Pool full, CPU idle:** the pod is short of connections, not CPU. More CPU or more memory changes nothing.
- **Max throughput = pool size ÷ time each request holds a connection.** A slow remote call inside that window divides it.
- **Never hold a database connection across a remote call.** With Spring Boot, that starts with `spring.jpa.open-in-view=false`.

**On your cluster:** look for `hikaricp_connections_pending` above zero while database CPU is low, and
for the `spring.jpa.open-in-view is enabled by default` warning in your pod logs.

## Start over

Undo your changes to [compose.yaml](compose.yaml) (`git checkout compose.yaml` in a clone), then:

```sh
docker compose up -d
```

`docker compose down` stops everything and keeps the database. `docker compose down -v` deletes it too.
