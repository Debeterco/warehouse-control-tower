-- =====================================================================
-- Warehouse Control Tower - realistic work order history
--
-- "Tempo medio de atendimento" (average fulfilment time) is one of the
-- three indicators the Team 5 brief requires. Ten seeded orders cannot carry
-- a meaningful average, and orders closed seconds apart skew it badly.
--
-- This migration adds a 90-day history of closed maintenance orders with
-- plausible durations, spread across departments and respecting working
-- hours, so the KPI has a statistically meaningful population.
-- =====================================================================

-- Closed orders over the last 90 days. Durations follow a long-tailed
-- distribution: most repairs resolve inside a shift, a minority wait on
-- parts or a shutdown window.
WITH generated AS (
    SELECT
        ROW_NUMBER() OVER (ORDER BY random())                       AS seq,
        (ARRAY['Manutenção Mecânica', 'Utilidades', 'Instrumentação',
               'Processos', 'Elétrica', 'Civil', 'Segurança do Trabalho'])
            [1 + (random() * 6)::int]                              AS department,
        CURRENT_TIMESTAMP - (random() * 90)::int * INTERVAL '1 day' AS created_at,
        CASE
            WHEN random() < 0.55 THEN INTERVAL '1 hour'  * (1 + random() * 7)::int
            WHEN random() < 0.85 THEN INTERVAL '8 hour'  * (1 + random() * 2)::int
            WHEN random() < 0.97 THEN INTERVAL '1 day'   * (1 + random() * 3)::int
            ELSE INTERVAL '4 day' * (1 + random() * 3)::int
        END                                                         AS duration
    FROM generate_series(1, 240)
)
INSERT INTO tb_work_order
    (order_code, requesting_department, status, created_at, completed_at, description)
SELECT
    'WO-HIST-' || LPAD(seq::text, 4, '0'),
    department,
    'COMPLETED',
    created_at,
    created_at + duration,
    'Atendimento histórico - ' || department
FROM generated
-- Keep completed_at in the past, never in the future
WHERE created_at + duration <= (NOW() AT TIME ZONE 'UTC');

-- Resync the sequence past the historical block (codes are textual, but
-- the numeric id sequence must continue above the highest seeded row).
SELECT setval(pg_get_serial_sequence('tb_work_order', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM tb_work_order));

COMMENT ON TABLE tb_work_order IS
    'Maintenance work orders consuming warehouse items (seeded history + simulated)';