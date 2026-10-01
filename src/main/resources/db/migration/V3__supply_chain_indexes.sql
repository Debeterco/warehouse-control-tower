-- =====================================================================
-- Warehouse Control Tower - performance indexes
--
-- Supports the supply-chain metrics added for the Team 5 brief:
--   * average fulfilment time of work orders (tempo medio de atendimento)
--   * inventory turnover by ABC class (giro de estoque)
--
-- PostgreSQL partial indexes keep these cheap as the ledger grows.
-- =====================================================================

-- Fulfilment time is only measurable once an order is closed.
CREATE INDEX idx_wo_completed_at ON tb_work_order (completed_at)
    WHERE status = 'COMPLETED' AND completed_at IS NOT NULL;

-- Supports "open orders by age" and the supervisor backlog view.
CREATE INDEX idx_wo_status_created ON tb_work_order (status, created_at);

-- Turnover needs outbound value grouped by item; the existing partial index
-- on (movement_type, occurred_at) already covers the window scan, but adding
-- supply_item_id first lets the planner join straight into tb_supply_item.
CREATE INDEX idx_movement_item_type_time ON tb_stock_movement (supply_item_id, movement_type, occurred_at DESC)
    WHERE movement_type = 'OUTBOUND';

-- Supplier rollups aggregate by supplier across active items.
CREATE INDEX idx_item_supplier_active ON tb_supply_item (supplier)
    WHERE active = TRUE AND supplier IS NOT NULL;