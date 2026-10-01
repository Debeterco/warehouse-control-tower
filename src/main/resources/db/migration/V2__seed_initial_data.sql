-- =====================================================================
-- Warehouse Control Tower - Initial load (industrial dataset)
-- Bearings, Valves, Sensors, Lubricants and Consumables
--
-- Identifiers are English; the descriptive data stays in the language
-- printed on the shop floor, since the dashboard displays it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- tb_supply_item (explicit ids -> sequences are resynced at the end)
-- ---------------------------------------------------------------------
INSERT INTO tb_supply_item
    (id, code, name, unit_of_measure, current_quantity, minimum_stock,
     replenishment_lead_time_days, average_daily_consumption, unit_cost, abc_category,
     location, supplier, active)
VALUES
-- Bearings -------------------------------------------------------------
(1,  'BRG-6205-2RS',      'Rolamento Rígido de Esferas 6205 2RS',        'UN',  340,  80,  7,  12.5000,   89.90, 'C', 'A-01-02', 'SKF Brasil',            TRUE),
(2,  'BRG-6206-2RS',      'Rolamento Rígido de Esferas 6206 2RS',        'UN',  180,  60,  7,   8.2000,  112.50, 'C', 'A-01-03', 'SKF Brasil',            TRUE),
(3,  'BRG-6308-C3',       'Rolamento de Rolos Cilíndricos 6308 C3',     'UN',   96,  30, 10,   4.1000,  245.00, 'C', 'A-01-04', 'FAG do Brasil',         TRUE),
(4,  'BRG-22210-E',       'Rolamento de Rolos Esféricos 22210 E',       'UN',   22,   8, 21,   1.1000, 1480.00, 'C', 'A-01-05', 'SKF Brasil',            TRUE),
(5,  'BRG-32226',         'Rolamento de Rolos Cônicos 32226',           'UN',    9,   4, 28,   0.3500, 2150.00, 'C', 'A-01-06', 'Timken Brasil',         TRUE),
(6,  'BRG-UCF205',        'Mancal Flutuante com Rolamento UCF205',      'UN',   48,  15, 12,   2.4000,  385.00, 'C', 'A-01-07', 'Timken Brasil',         TRUE),
(7,  'BRG-NJ222-E',       'Rolamento Cilíndrico NJ222 E',                'UN',    0,   3, 30,   0.2800, 1920.00, 'C', 'A-01-08', 'INA/FAG',               TRUE),

-- Valves ---------------------------------------------------------------
(8,  'VLV-SPHERE-2IN',    'Válvula de Esfera 2" Aço Inox 316',           'UN',    5,   3, 25,   0.1500, 1280.00, 'C', 'B-02-01', 'Emerson Electric',      TRUE),
(9,  'VLV-GENIO-1IN',     'Válvula Genio 1" Bronze',                     'UN',   24,  10, 15,   0.9000,  415.00, 'C', 'B-02-02', 'Emerson Electric',      TRUE),
(10, 'VLV-CHECK-4IN',     'Válvula Clapeta 4" Aço Carbono',              'UN',    3,   2, 35,   0.0600, 2480.00, 'C', 'B-02-03', 'Flowserve',             TRUE),
(11, 'VLV-SOLENOID-220V', 'Válvula Solenoide 220V 1/2"',                 'UN',   11,   6, 20,   0.4500,  690.00, 'C', 'B-02-04', 'Smar',                  TRUE),
(12, 'VLV-REGULATOR-3IN', 'Válvula Reguladora de Pressão 3"',            'UN',    1,   1, 45,   0.0300, 3150.00, 'C', 'B-02-05', 'Fisher Controls',       TRUE),
(13, 'VLV-BUTTERFLY-6IN', 'Válvula Borboleta 6" Aço Carbono',            'UN',    5,   2, 30,   0.1200, 1870.00, 'C', 'B-02-06', 'Flowserve',             TRUE),

-- Sensors and instrumentation -----------------------------------------
(14, 'SNS-PRESSURE-0-10BAR','Sensor de Pressão 0-10 bar 4-20mA',          'UN',   18,   8, 14,   0.8500,  780.00, 'C', 'C-03-01', 'WIKA Brasil',           TRUE),
(15, 'SNS-TEMPERATURE-PT100','Sensor de Temperatura PT100 0-400°C',      'UN',   42,  15, 10,   2.1000,  465.00, 'C', 'C-03-02', 'WIKA Brasil',           TRUE),
(16, 'SNS-LEVEL-RADAR',   'Sensor de Nível por Radar 10 m',              'UN',    1,   1, 45,   0.0200, 12400.00,'C', 'C-03-03', 'VEGA Brasil',           TRUE),
(17, 'SNS-FLOW-MAG-3IN',  'Sensor de Fluxo Eletromagnético 3"',          'UN',    2,   1, 40,   0.0500, 9850.00, 'C', 'C-03-04', 'Endress+Hauser',         TRUE),
(18, 'SNS-VIBRATION-IEPE', 'Sensor de Vibração IEPE 100mV/g',             'UN',    7,   3, 18,   0.2200, 3290.00, 'C', 'C-03-05', 'IFM Electronic',        TRUE),
(19, 'SNS-PHOTOCELL-M18', 'Célula Fotelétrica M18 2 m',                  'UN',  130,  40,  8,   5.5000,  210.00, 'C', 'C-03-06', 'Sick AG',               TRUE),
(20, 'THP-TYPE-J',        'Termopar Tipo J 300mm Inox',                 'UN',  260,  80,  7,   9.8000,   78.50, 'C', 'C-03-07', 'JUMO Brasil',           TRUE),

-- Lubricants -----------------------------------------------------------
(21, 'LUB-ISO-VG68-20L',   'Óleo Mineral Isomfax VG68 20 L',             'UN',   52,  20, 10,   2.6000,  465.00, 'C', 'D-04-01', 'Shell Lubrificantes',   TRUE),
(22, 'LUB-GREASE-EP2-1KG', 'Graxa EP2 Lítio Complexo 1 kg',               'UN',   88,  35,  7,   4.1000,  132.00, 'C', 'D-04-02', 'Shell Lubrificantes',   TRUE),
(23, 'LUB-HYDRAULIC-HV68', 'Óleo Hidráulico HV68 20 L',                  'UN',   17,  12, 12,   0.9500,  598.00, 'C', 'D-04-03', 'Mobil',                 TRUE),
(24, 'LUB-ANTIFRICTION-220','Óleo Antifricção 220 1 L',                  'UN',   64,  25,  6,   3.2000,   96.00, 'C', 'D-04-04', 'Petrobras Lubrificantes',TRUE),
(25, 'LUB-GREASE-HITEMP',  'Graxa Alta Temperatura 1 kg',                 'UN',   19,  14, 15,   0.7500,  245.00, 'C', 'D-04-05', 'Shell Lubrificantes',   TRUE),

-- Electrical parts and consumables ------------------------------------
(26, 'CON-BNC-MALE',       'Conector Torneira BNC Macho',                 'UN',  420, 150,  5,  18.5000,   38.90, 'C', 'E-05-01', 'Radiall',               TRUE),
(27, 'CBL-FLEX-2X1.5',     'Cordão Cabo Flexível 2x1,5mm Preto',         'MT',  780, 250,  5,  32.0000,   12.80, 'C', 'E-05-02', 'Prysmian',              TRUE),
(28, 'CTR-CONTACTOR-25A',  'Contator Tripolar 25A 220V',                  'UN',   26,  10, 14,   1.2000,  289.00, 'C', 'E-05-03', 'Schneider Electric',    TRUE),
(29, 'FST-BOLT-M8X40',     'Parafuso Sextavado M8x40 Zincado',           'UN', 4200,1500,  4, 165.0000,    1.25, 'C', 'E-05-05', 'Ciserne',               TRUE),
(30, 'FLT-AIR-COMP-320',   'Filtro de Ar para Compressor 320',            'UN',   15,   8,  9,   0.7200,  385.00, 'C', 'E-05-06', 'Atlas Copco',           TRUE);

-- ---------------------------------------------------------------------
-- tb_work_order
-- ---------------------------------------------------------------------
INSERT INTO tb_work_order
    (id, order_code, requesting_department, status, created_at, completed_at, description)
VALUES
(1,  'WO-2026-0001', 'Manutenção Mecânica',  'COMPLETED',    CURRENT_TIMESTAMP - INTERVAL '12 days', CURRENT_TIMESTAMP - INTERVAL '11 days', 'Troca de rolamentos do REDUTOR principal - Linha 1'),
(2,  'WO-2026-0002', 'Utilidades',           'COMPLETED',    CURRENT_TIMESTAMP - INTERVAL '10 days', CURRENT_TIMESTAMP - INTERVAL '10 days', 'Reprogramação de válvula solenoide SKID-02'),
(3,  'WO-2026-0003', 'Instrumentação',       'COMPLETED',    CURRENT_TIMESTAMP - INTERVAL '8 days',  CURRENT_TIMESTAMP - INTERVAL '7 days',  'Calibração de transmissores de pressão - Caldeira'),
(4,  'WO-2026-0004', 'Manutenção Mecânica',  'IN_PROGRESS',  CURRENT_TIMESTAMP - INTERVAL '4 days',  NULL, 'Substituição de mancal UCF205 - Transportador TC-05'),
(5,  'WO-2026-0005', 'Processos',            'IN_PROGRESS',  CURRENT_TIMESTAMP - INTERVAL '2 days',  NULL, 'Ajuste de válvula reguladora de pressão - Vessel R-101'),
(6,  'WO-2026-0006', 'Elétrica',             'OPEN',         CURRENT_TIMESTAMP - INTERVAL '1 day',   NULL, 'Troca de contator do painel de bombas P-204'),
(7,  'WO-2026-0007', 'Manutenção Mecânica',  'OPEN',         CURRENT_TIMESTAMP - INTERVAL '6 hours', NULL, 'Inspeção de vibração no compressor CP-02'),
(8,  'WO-2026-0008', 'Instrumentação',       'OPEN',         CURRENT_TIMESTAMP - INTERVAL '2 hours', NULL, 'Instalação de sensor de nível radar no tanque TK-12'),
(9,  'WO-2026-0009', 'Utilidades',           'OPEN',         CURRENT_TIMESTAMP - INTERVAL '45 minutes', NULL, 'Troca de filtro de ar do compressor 320'),
(10, 'WO-2026-0010', 'Processos',            'CANCELLED',    CURRENT_TIMESTAMP - INTERVAL '3 days',  NULL, 'Interrompida - parada programada da área');

-- ---------------------------------------------------------------------
-- tb_stock_movement : OUTBOUND history over the last 30 days.
-- Each movement covers ~3 days of average consumption, giving the
-- dashboard a real ledger for consumption KPIs and auditing.
--
-- The historical balance (resulting_balance) is rebuilt with a window
-- function: current balance + amount consumed up to that timestamp.
-- ---------------------------------------------------------------------
WITH movements AS (
    SELECT
        i.id                                                 AS supply_item_id,
        d.occurred_at                                        AS occurred_at,
        GREATEST(1, ROUND(i.average_daily_consumption * 3))::INTEGER AS quantity
    FROM tb_supply_item i
    CROSS JOIN generate_series(
        CURRENT_TIMESTAMP - INTERVAL '30 days',
        CURRENT_TIMESTAMP,
        INTERVAL '3 days'
    ) AS d(occurred_at)
    WHERE i.active = TRUE
      AND i.average_daily_consumption >= 0.1
)
INSERT INTO tb_stock_movement
    (supply_item_id, work_order_id, movement_type, quantity, occurred_at, resulting_balance, origin)
SELECT
    m.supply_item_id,
    (SELECT wo.id FROM tb_work_order wo ORDER BY random() LIMIT 1),
    'OUTBOUND',
    m.quantity,
    m.occurred_at,
    (i.current_quantity
        + SUM(m.quantity) OVER (PARTITION BY m.supply_item_id ORDER BY m.occurred_at))::INTEGER,
    'SEED'
FROM movements m
JOIN tb_supply_item i ON i.id = m.supply_item_id
ORDER BY m.occurred_at;

-- ---------------------------------------------------------------------
-- Resync sequences (the ids above were explicit)
-- ---------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('tb_supply_item', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM tb_supply_item));
SELECT setval(pg_get_serial_sequence('tb_work_order', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM tb_work_order));
SELECT setval(pg_get_serial_sequence('tb_stock_movement', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM tb_stock_movement));