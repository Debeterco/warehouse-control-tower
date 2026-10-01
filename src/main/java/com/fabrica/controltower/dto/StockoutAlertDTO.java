package com.fabrica.controltower.dto;

import java.math.BigDecimal;

/**
 * Stockout alert (GET /api/v1/supplies/stockout-alerts).
 *
 * <p>Sorted by criticality (STOCKOUT &gt; CRITICAL &gt; WARNING) and, within a
 * level, by the shortest time to stockout.</p>
 *
 * <p>The {@code message} field is intentionally written in Portuguese: it is
 * rendered verbatim on the dashboard shown to the floor team.</p>
 *
 * @param id                        item id
 * @param code                      item code
 * @param name                      item description
 * @param currentQuantity           current balance
 * @param minimumStock              operational minimum stock
 * @param reorderPoint              (averageDailyConsumption x leadTime) + safetyStock
 * @param safetyStock               safety stock
 * @param averageDailyConsumption   average demand per day
 * @param replenishmentLeadTimeDays supplier lead time
 * @param daysUntilStockout         remaining coverage at the current demand (null if unused)
 * @param quantityToRequest         amount missing to reach the reorder point
 * @param valueAtRisk               inventory value tied up in the item
 * @param severity                  STOCKOUT | CRITICAL | WARNING
 * @param message                   ready-to-display text for the UI
 */
public record StockoutAlertDTO(
        Long id,
        String code,
        String name,
        Integer currentQuantity,
        Integer minimumStock,
        BigDecimal reorderPoint,
        BigDecimal safetyStock,
        BigDecimal averageDailyConsumption,
        Integer replenishmentLeadTimeDays,
        BigDecimal daysUntilStockout,
        Integer quantityToRequest,
        BigDecimal valueAtRisk,
        String severity,
        String message
) {
}