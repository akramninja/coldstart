-- Production-sized data for the Liquibase incidents: 200,000 customers and :orders orders.
-- Runs once (a few minutes), then every later start says "Already seeded".
--
-- created_at is random, not in insertion order, as in a table that gets updates, imports and
-- late-arriving rows. That's what makes an index on it take real time to build.

SELECT NOT EXISTS (SELECT 1 FROM customers) AS needs_seed \gset

\if :needs_seed
  \echo Seeding 200000 customers and :orders orders. This takes a few minutes, once.
  BEGIN;
  INSERT INTO customers (id, email, full_name)
  SELECT g, 'customer' || g || '@example.com', 'Customer ' || g
  FROM generate_series(1, 200000) AS g;

  INSERT INTO orders (customer_id, status, total, created_at)
  SELECT 1 + floor(random() * 200000)::bigint,
         'PLACED',
         round((5 + random() * 495)::numeric, 2),
         timestamptz '2025-01-01 00:00:00+00' + random() * interval '600 days'
  FROM generate_series(1, :orders);
  COMMIT;
  ANALYZE customers;
  ANALYZE orders;
  \echo Seeded.
\else
  \echo Already seeded.
\endif
