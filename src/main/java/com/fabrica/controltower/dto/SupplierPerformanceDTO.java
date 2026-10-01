package com.fabrica.controltower.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplier performance rollup (GET /api/v1/supplies/supplier-performance).
 *
 * <p>The Team 5 brief targets supply and logistics managers, whose daily
 * question is "which supplier is holding up my stock". Lead time and exposure
 * per supplier turn the ABC curve into a purchasing decision.</p>
 *
 * @param totalValue             inventory value across all suppliers
 * @param suppliers              suppliers sorted by value, descending
 */
public record SupplierPerformanceDTO(
        BigDecimal totalValue,
        List<SupplierPerformanceItemDTO> suppliers
) {

    /**
     * One supplier.
     *
     * @param valueSharePercent     share of total inventory value
     * @param itemsAtRisk           items below their reorder point
     * @param criticalItems         items at risk as a percentage of the supplier's items
     * @param averageLeadTimeDays   average supplier lead time
     * @param riskLevel             LOW | MEDIUM | HIGH
     */
    public record SupplierPerformanceItemDTO(
            String supplier,
            long itemCount,
            BigDecimal totalValue,
            BigDecimal valueSharePercent,
            long itemsAtRisk,
            BigDecimal criticalItems,
            BigDecimal averageLeadTimeDays,
            String riskLevel
    ) {
    }
}