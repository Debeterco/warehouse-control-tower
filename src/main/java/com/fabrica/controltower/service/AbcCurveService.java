package com.fabrica.controltower.service;

import com.fabrica.controltower.dto.AbcCurveDTO;
import com.fabrica.controltower.entity.SupplyItem;
import com.fabrica.controltower.repository.SupplyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ABC / Pareto curve for the warehouse.
 *
 * <h2>Rule</h2>
 * <p>Items are sorted by inventory value
 * ({@code currentQuantity x unitCost}) and classified according to the
 * cumulative share of total value:</p>
 * <ul>
 *   <li><b>A</b> — from the first item until it crosses {@code classALimit}
 *       (80% of value). The item that crosses the limit joins class A.</li>
 *   <li><b>B</b> — accumulating until {@code classBLimit} (95% of value).</li>
 *   <li><b>C</b> — the remaining tail.</li>
 * </ul>
 *
 * <p>Handled edge cases: zero total value (everything lands in C) and an
 * empty catalogue.</p>
 */
@Slf4j
@Service
public class AbcCurveService {

    private final SupplyRepository supplyRepository;

    private final double classALimit;
    private final double classBLimit;

    public AbcCurveService(SupplyRepository supplyRepository,
                           @Value("${warehouse.stock.abc-curve.class-a-limit:0.80}") double classALimit,
                           @Value("${warehouse.stock.abc-curve.class-b-limit:0.95}") double classBLimit) {
        this.supplyRepository = supplyRepository;
        this.classALimit = normalise(classALimit, 0.80);
        this.classBLimit = normalise(classBLimit, 0.95);
    }

    private static double normalise(double value, double fallback) {
        if (value <= 0 || value > 1 || Double.isNaN(value)) {
            log.warn("Invalid ABC limit ({}). Falling back to {}.", value, fallback);
            return fallback;
        }
        return value;
    }

    /**
     * Calculates the ABC curve and materialises the class in
     * {@code tb_supply_item}.
     *
     * @return the full curve, already sorted by descending value
     */
    @Transactional
    public AbcCurveDTO calculateAbcCurve() {
        return calculateAbcCurve(true);
    }

    /** ABC curve without writing to the database (used by read-only paths). */
    @Transactional(readOnly = true)
    public AbcCurveDTO calculateAbcCurveReadOnly() {
        return calculateAbcCurve(false);
    }

    private AbcCurveDTO calculateAbcCurve(boolean persist) {
        // Ordering by value happens in the database (index on current_quantity * unit_cost).
        List<SupplyItem> items = supplyRepository.findAllActiveOrderedByValue();

        // The total must be known BEFORE the loop: the cumulative percentage is
        // measured against the catalogue total, not against the amount read so far.
        BigDecimal totalValue = items.stream()
                .map(SupplyItem::getInventoryValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AbcCurveDTO.ItemAbcCurveDTO> curve = new ArrayList<>(items.size());
        BigDecimal classAValue = BigDecimal.ZERO;
        BigDecimal classBValue = BigDecimal.ZERO;
        BigDecimal classCValue = BigDecimal.ZERO;
        long classACount = 0;
        long classBCount = 0;
        long classCCount = 0;

        BigDecimal cumulative = BigDecimal.ZERO;

        for (SupplyItem item : items) {
            BigDecimal inventoryValue = item.getInventoryValue();
            cumulative = cumulative.add(inventoryValue);

            double cumulativePercentage = percentageOf(cumulative, totalValue);
            String abcClass = classify(cumulativePercentage);

            switch (abcClass) {
                case "A" -> {
                    classACount++;
                    classAValue = classAValue.add(inventoryValue);
                }
                case "B" -> {
                    classBCount++;
                    classBValue = classBValue.add(inventoryValue);
                }
                default -> {
                    classCCount++;
                    classCValue = classCValue.add(inventoryValue);
                }
            }

            if (persist) {
                supplyRepository.updateAbcCategory(item.getId(), abcClass);
            }

            curve.add(new AbcCurveDTO.ItemAbcCurveDTO(
                    item.getId(),
                    item.getCode(),
                    item.getName(),
                    item.getCurrentQuantity(),
                    item.getUnitCost(),
                    inventoryValue,
                    cumulativePercentage,
                    abcClass));
        }

        AbcCurveDTO.SummaryByCategoryDTO summary = new AbcCurveDTO.SummaryByCategoryDTO(
                classACount, classBCount, classCCount,
                classAValue, classBValue, classCValue,
                shareOf(classAValue, totalValue),
                shareOf(classBValue, totalValue),
                shareOf(classCValue, totalValue));

        log.debug("ABC curve: {} items | A={} ({}%) B={} C={}",
                curve.size(), classACount, summary.classAPercentage(), classBCount, classCCount);

        return new AbcCurveDTO(
                totalValue.setScale(2, RoundingMode.HALF_UP),
                List.copyOf(curve),
                summary,
                Instant.now());
    }

    /**
     * Cumulative share of the total, on a 0-100 scale.
     *
     * <p>With a zero total (a catalogue holding no value) it returns 0 so the
     * chart axis keeps rendering.</p>
     */
    private double percentageOf(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** Individual share of a single class over the total. */
    private Double shareOf(BigDecimal part, BigDecimal total) {
        return percentageOf(part, total);
    }

    /**
     * Maps the cumulative share to an ABC class.
     *
     * <p>The 80/95 rule: A runs until it crosses 80% (the crossing item
     * included), B until 95%, and C is the tail.</p>
     */
    private String classify(double cumulativePercentage) {
        if (cumulativePercentage <= 0.0) {
            return "C";
        }
        if (cumulativePercentage <= classALimit * 100.0) {
            return "A";
        }
        if (cumulativePercentage <= classBLimit * 100.0) {
            return "B";
        }
        return "C";
    }
}