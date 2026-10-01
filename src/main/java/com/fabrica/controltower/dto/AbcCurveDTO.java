package com.fabrica.controltower.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * ABC / Pareto curve (GET /api/v1/supplies/abc-curve).
 *
 * <p>Items arrive sorted by descending inventory value; the
 * {@code cumulativePercentage} field drives the Pareto line in ECharts.</p>
 *
 * @param totalValue        value summed across every item
 * @param items             items sorted by descending value
 * @param summaryByCategory item count per class
 * @param generatedAt       instant the curve was calculated
 */
public record AbcCurveDTO(
        BigDecimal totalValue,
        List<ItemAbcCurveDTO> items,
        SummaryByCategoryDTO summaryByCategory,
        Instant generatedAt
) {

    /**
     * One item on the curve.
     *
     * @param cumulativePercentage share of the total value accumulated so far (0-100)
     * @param abcClass              A, B or C
     */
    public record ItemAbcCurveDTO(
            Long id,
            String code,
            String name,
            Integer currentQuantity,
            BigDecimal unitCost,
            BigDecimal inventoryValue,
            Double cumulativePercentage,
            String abcClass
    ) {
    }

    /**
     * Per-class rollup.
     *
     * @param classAPercentage percentage of the total value held by class A
     */
    public record SummaryByCategoryDTO(
            long classAItems,
            long classBItems,
            long classCItems,
            BigDecimal classAValue,
            BigDecimal classBValue,
            BigDecimal classCValue,
            Double classAPercentage,
            Double classBPercentage,
            Double classCPercentage
    ) {
    }
}