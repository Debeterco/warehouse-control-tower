-- =====================================================================
-- Warehouse Control Tower - Schema
-- Team 5 - Supply Chain Logistics
-- =====================================================================

-- ---------------------------------------------------------------------
-- tb_supply_item : warehouse catalogue
-- ---------------------------------------------------------------------
CREATE TABLE tb_supply_item (
    id                             BIGSERIAL       PRIMARY KEY,
    code                           VARCHAR(50)     NOT NULL UNIQUE,
    name                           VARCHAR(255)    NOT NULL,
    unit_of_measure                VARCHAR(20)     NOT NULL DEFAULT 'UN',
    -- available balance
    current_quantity               INTEGER         NOT NULL DEFAULT 0,
    -- operational minimum stock (replenishment trigger)
    minimum_stock                  INTEGER         NOT NULL DEFAULT 0,
    -- supplier lead time, in days
    replenishment_lead_time_days   INTEGER         NOT NULL DEFAULT 7,
    -- observed average daily demand. Input to the Reorder Point formula.
    average_daily_consumption      NUMERIC(14,4)   NOT NULL DEFAULT 0,
    unit_cost                      NUMERIC(15,2)   NOT NULL DEFAULT 0,
    -- materialised ABC classification (A/B/C), rewritten by AbcCurveService
    abc_category                   VARCHAR(1)      NOT NULL DEFAULT 'C',
    location                       VARCHAR(60),
    supplier                       VARCHAR(120),
    active                         BOOLEAN         NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_item_current_qty_non_negative CHECK (current_quantity >= 0),
    CONSTRAINT ck_item_minimum_stock              CHECK (minimum_stock >= 0),
    CONSTRAINT ck_item_abc_category               CHECK (abc_category IN ('A', 'B', 'C'))
);

COMMENT ON TABLE  tb_supply_item                        IS 'Warehouse supply item catalogue';
COMMENT ON COLUMN tb_supply_item.average_daily_consumption IS 'Observed average demand (units/day) used by the Reorder Point formula';
COMMENT ON COLUMN tb_supply_item.abc_category          IS 'ABC classification by inventory value (materialised by the ABC job)';

CREATE INDEX idx_item_active        ON tb_supply_item (active);
CREATE INDEX idx_item_abc_category  ON tb_supply_item (abc_category);
CREATE INDEX idx_item_value         ON tb_supply_item ((current_quantity * unit_cost) DESC);

-- ---------------------------------------------------------------------
-- tb_work_order : maintenance orders that consume warehouse items
-- ---------------------------------------------------------------------
CREATE TABLE tb_work_order (
    id                    BIGSERIAL     PRIMARY KEY,
    order_code            VARCHAR(50)   NOT NULL UNIQUE,
    requesting_department VARCHAR(100)  NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMP,
    description           VARCHAR(255),

    CONSTRAINT ck_wo_status_valid CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_wo_completed_at  CHECK (completed_at IS NULL OR completed_at >= created_at)
);

COMMENT ON TABLE  tb_work_order            IS 'Maintenance work orders consuming warehouse items';
COMMENT ON COLUMN tb_work_order.status     IS 'OPEN | IN_PROGRESS | COMPLETED | CANCELLED';

CREATE INDEX idx_wo_status   ON tb_work_order (status);
CREATE INDEX idx_wo_created  ON tb_work_order (created_at DESC);

-- ---------------------------------------------------------------------
-- tb_stock_movement : immutable ledger of stock in/out
-- ---------------------------------------------------------------------
CREATE TABLE tb_stock_movement (
    id                BIGSERIAL     PRIMARY KEY,
    supply_item_id    BIGINT        NOT NULL,
    work_order_id     BIGINT,
    movement_type     VARCHAR(20)   NOT NULL,
    quantity          INTEGER       NOT NULL,
    occurred_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resulting_balance INTEGER       NOT NULL DEFAULT 0,
    origin            VARCHAR(30)   NOT NULL DEFAULT 'MANUAL',

    CONSTRAINT fk_movement_supply_item
        FOREIGN KEY (supply_item_id)
        REFERENCES tb_supply_item (id),

    CONSTRAINT fk_movement_work_order
        FOREIGN KEY (work_order_id)
        REFERENCES tb_work_order (id),

    CONSTRAINT ck_movement_type_valid
        CHECK (movement_type IN ('INBOUND', 'OUTBOUND', 'ADJUSTMENT', 'INVENTORY')),
    CONSTRAINT ck_movement_qty_positive CHECK (quantity > 0)
);

COMMENT ON TABLE  tb_stock_movement         IS 'Immutable stock movement ledger';
COMMENT ON COLUMN tb_stock_movement.origin  IS 'SIMULATOR | MANUAL | AUTO_REPLENISHMENT';

CREATE INDEX idx_movement_supply_item ON tb_stock_movement (supply_item_id);
CREATE INDEX idx_movement_occurred_at ON tb_stock_movement (occurred_at DESC);
CREATE INDEX idx_movement_work_order  ON tb_stock_movement (work_order_id);
-- partial index: speeds up average-consumption lookups (OUTBOUND only)
CREATE INDEX idx_movement_type_time ON tb_stock_movement (movement_type, occurred_at DESC)
    WHERE movement_type = 'OUTBOUND';