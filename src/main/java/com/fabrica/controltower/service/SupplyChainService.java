package com.fabrica.controltower.service;

import com.fabrica.controltower.dto.InventoryTurnoverDTO;
import com.fabrica.controltower.dto.SupplierPerformanceDTO;
import com.fabrica.controltower.dto.WorkOrderFulfilmentDTO;
import com.fabrica.controltower.repository.SupplyRepository;
import com.fabrica.controltower.repository.SupplyRepository.SupplierProjection;
import com.fabrica.controltower.repository.WorkOrderRepository;
import com.fabrica.controltower.repository.WorkOrderRepository.DepartmentFulfilmentProjection;
import com.fabrica.controltower.repository.WorkOrderRepository.FulfilmentProjection;
import com.fabrica.controltower.repository.WorkOrderRepository.OpenAgeProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supply-chain metrics required by the Team 5 brief.
 *
 * <p>Covers the two indicators the dashboard would otherwise be missing:</p>
 * <ul>
 *   <li><b>Giro de estoque</b> — inventory turnover, globally and per ABC class.</li>
 *   <li><b>Tempo médio de atendimento</b> — average fulfilment time of work
 *       orders, globally and per department.</li>
 * </ul>
 *
 * <p>Plus a supplier rollup, which is the view supply managers actually steer
 * purchasing from.</p>
 */
@Slf4j
@Service
public class SupplyChainService {

    private final SupplyRepository supplyRepository;
    private final WorkOrderRepository workOrderRepository;

    /** Length of the turnover analysis window, in days. */
    private final int windowDays;

    /** Service target for closing a work order, in hours. */
    private final double slaTargetHours;

    public SupplyChainService(SupplyRepository supplyRepository,
                              WorkOrderRepository workOrderRepository,
                              @Value("${warehouse.supply-chain.turnover-window-days:30}") int windowDays,
                              @Value("${warehouse.supply-chain.sla-target-hours:48}") double slaTargetHours) {
        this.supplyRepository = supplyRepository;
        this.workOrderRepository = workOrderRepository;
        this.windowDays = windowDays > 0 ? windowDays : 30;
        this.slaTargetHours = slaTargetHours > 0 ? slaTargetHours : 48;
    }

    // ==================================================================
    // Inventory turnover (giro de estoque)
    // ==================================================================

    /**
     * Computes inventory turnover over the analysis window.
     *
     * <p>Average inventory is estimated as the midpoint between the value at
     * the start of the window and the current value. Reconstructing the start
     * value from the ledger keeps the calculation honest under continuous
     * consumption: {@code startValue = currentValue + COGS - inbound}.</p>
     */
    @Transactional(readOnly = true)
    public InventoryTurnoverDTO calculateTurnover() {
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        Map<String, BigDecimal> cogsByClass = new HashMap<>();
        for (Object[] row : supplyRepository.findCogsByAbcClassSince(since)) {
            cogsByClass.put(String.valueOf(row[0]), toBigDecimal(row[1]));
        }

        BigDecimal totalCogs = BigDecimal.ZERO;
        BigDecimal totalInbound = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;

        List<InventoryTurnoverDTO.TurnoverByClassDTO> byClass = new ArrayList<>();

        for (Object[] row : supplyRepository.findValueAndInboundByAbcClass(since)) {
            String abcClass = String.valueOf(row[0]);
            long itemCount = ((Number) row[1]).longValue();
            BigDecimal currentValue = toBigDecimal(row[2]);
            BigDecimal inbound = toBigDecimal(row[3]);
            BigDecimal cogs = cogsByClass.getOrDefault(abcClass, BigDecimal.ZERO);

            BigDecimal inventoryAtStart = currentValue.add(cogs).subtract(inbound);
            BigDecimal averageInventory = average(inventoryAtStart, currentValue);

            byClass.add(new InventoryTurnoverDTO.TurnoverByClassDTO(
                    abcClass,
                    itemCount,
                    round(cogs),
                    round(currentValue),
                    round(averageInventory),
                    turnoverRatio(cogs, averageInventory),
                    daysOfInventory(cogs, averageInventory),
                    BigDecimal.ZERO)); // share filled in below, once the total is known

            totalCogs = totalCogs.add(cogs);
            totalInbound = totalInbound.add(inbound);
            totalCurrentValue = totalCurrentValue.add(currentValue);
        }

        BigDecimal inventoryAtStart = totalCurrentValue.add(totalCogs).subtract(totalInbound);
        BigDecimal averageInventory = average(inventoryAtStart, totalCurrentValue);
        BigDecimal turnover = turnoverRatio(totalCogs, averageInventory);

        // Second pass: now that the total is known, fill in each class's share.
        final BigDecimal catalogueValue = totalCurrentValue;
        List<InventoryTurnoverDTO.TurnoverByClassDTO> withShares = byClass.stream()
                .map(c -> new InventoryTurnoverDTO.TurnoverByClassDTO(
                        c.abcClass(), c.itemCount(), c.cogs(), c.currentValue(),
                        c.averageInventory(), c.turnover(), c.daysOfInventory(),
                        percent(c.currentValue(), catalogueValue)))
                .sorted(Comparator.comparing(InventoryTurnoverDTO.TurnoverByClassDTO::abcClass))
                .toList();

        log.debug("Turnover over {}d: COGS={} avgInventory={} turnover={}x",
                windowDays, round(totalCogs), round(averageInventory), turnover);

        return new InventoryTurnoverDTO(
                windowDays,
                round(totalCogs),
                round(totalInbound),
                round(totalCurrentValue),
                round(inventoryAtStart),
                round(averageInventory),
                turnover,
                annualise(turnover),
                daysOfInventory(totalCogs, averageInventory),
                withShares);
    }

    // ==================================================================
    // Work order fulfilment time (tempo medio de atendimento)
    // ==================================================================

    /**
     * Average fulfilment time of work orders.
     *
     * <p>Only CLOSED orders are measured — an open order has no fulfilment time
     * yet, so mixing the two would understate the real lead time.</p>
     */
    @Transactional(readOnly = true)
    public WorkOrderFulfilmentDTO calculateFulfilment() {
        FulfilmentProjection stats = workOrderRepository.findFulfilmentStatistics(slaTargetHours);
        OpenAgeProjection open = workOrderRepository.findOpenOrderAge();

        long completed = stats.getCompletedCount();
        BigDecimal slaPercent = completed > 0
                ? percent(BigDecimal.valueOf(stats.getWithinSla()), BigDecimal.valueOf(completed))
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        Map<String, Long> backlog = new HashMap<>();
        for (Object[] row : workOrderRepository.findBacklogByDepartment()) {
            backlog.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        List<WorkOrderFulfilmentDTO.DepartmentFulfilmentDTO> byDepartment =
                workOrderRepository.findFulfilmentByDepartment(slaTargetHours).stream()
                        .map(projection -> toDepartmentFulfilment(projection, backlog))
                        .sorted(Comparator.comparing(
                                WorkOrderFulfilmentDTO.DepartmentFulfilmentDTO::averageFulfilmentHours,
                                Comparator.reverseOrder()))
                        .toList();

        log.debug("Fulfilment: {} closed, avg {}h, median {}h, SLA {}%",
                completed, stats.getAverageHours(), stats.getMedianHours(), slaPercent);

        return new WorkOrderFulfilmentDTO(
                completed,
                round(stats.getAverageHours()),
                round(stats.getMedianHours()),
                round(stats.getMinHours()),
                round(stats.getMaxHours()),
                open.getOpenCount(),
                round(open.getAverageAgeHours()),
                round(open.getOldestAgeHours()),
                slaPercent,
                BigDecimal.valueOf(slaTargetHours).setScale(1, RoundingMode.HALF_UP),
                byDepartment);
    }

    private WorkOrderFulfilmentDTO.DepartmentFulfilmentDTO toDepartmentFulfilment(
            DepartmentFulfilmentProjection projection, Map<String, Long> backlog) {

        long count = projection.getCompletedCount();
        BigDecimal sla = count > 0
                ? percent(BigDecimal.valueOf(projection.getWithinSla()), BigDecimal.valueOf(count))
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        return new WorkOrderFulfilmentDTO.DepartmentFulfilmentDTO(
                projection.getDepartment(),
                count,
                round(projection.getAverageHours()),
                backlog.getOrDefault(projection.getDepartment(), 0L),
                sla);
    }

    // ==================================================================
    // Supplier performance
    // ==================================================================

    /**
     * Supplier rollup for the supply managers.
     *
     * <p>Risk level weights the share of the supplier's items that are in
     * trouble: a supplier carrying 10% of warehouse value with 40% of its items
     * critical is a bigger problem than one carrying 40% with nothing at
     * risk.</p>
     */
    @Transactional(readOnly = true)
    public SupplierPerformanceDTO calculateSupplierPerformance() {
        List<SupplierProjection> rows = supplyRepository.findSupplierPerformance();
        BigDecimal totalValue = rows.stream()
                .map(SupplierProjection::getTotalValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SupplierPerformanceDTO.SupplierPerformanceItemDTO> suppliers = rows.stream()
                .map(r -> {
                    long atRisk = r.getStockouts() + r.getCritical();
                    BigDecimal criticalShare = r.getItemCount() > 0
                            ? percent(BigDecimal.valueOf(atRisk), BigDecimal.valueOf(r.getItemCount()))
                            : BigDecimal.ZERO;

                    return new SupplierPerformanceDTO.SupplierPerformanceItemDTO(
                            r.getSupplier(),
                            r.getItemCount(),
                            round(r.getTotalValue()),
                            percent(r.getTotalValue(), totalValue),
                            atRisk,
                            criticalShare,
                            round(r.getAverageLeadTimeDays()),
                            classifyRisk(criticalShare, r.getAverageLeadTimeDays()));
                })
                .toList();

        return new SupplierPerformanceDTO(round(totalValue), suppliers);
    }

    /**
     * HIGH when a large share of the supplier's items is in trouble, or when
     * its lead time runs long; MEDIUM when either is moderate; LOW otherwise.
     */
    private String classifyRisk(BigDecimal criticalShare, BigDecimal averageLeadTime) {
        double share = criticalShare.doubleValue();
        double leadTime = averageLeadTime.doubleValue();

        if (share >= 50.0 || leadTime >= 35.0) {
            return "HIGH";
        }
        if (share >= 25.0 || leadTime >= 21.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    // ==================================================================
    // Numeric helpers
    // ==================================================================

    private BigDecimal turnoverRatio(BigDecimal cogs, BigDecimal averageInventory) {
        if (averageInventory == null || averageInventory.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return cogs.divide(averageInventory, 2, RoundingMode.HALF_UP);
    }

    /** Days of inventory on hand: how long the current stock lasts at window velocity. */
    private BigDecimal daysOfInventory(BigDecimal cogs, BigDecimal averageInventory) {
        BigDecimal turnover = turnoverRatio(cogs, averageInventory);
        if (turnover.compareTo(BigDecimal.ZERO) <= 0) {
            // Nothing moved in the window: coverage is unbounded, report the window itself.
            return BigDecimal.valueOf(windowDays);
        }
        return BigDecimal.valueOf(windowDays)
                .divide(turnover, 1, RoundingMode.HALF_UP);
    }

    /** Projects the window turnover to a full year. */
    private BigDecimal annualise(BigDecimal turnover) {
        if (turnover == null || turnover.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return turnover
                .multiply(BigDecimal.valueOf(365.0 / windowDays))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal average(BigDecimal a, BigDecimal b) {
        return a.add(b)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal round(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }
}