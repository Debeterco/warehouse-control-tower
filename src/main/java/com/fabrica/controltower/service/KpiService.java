package com.fabrica.controltower.service;

import com.fabrica.controltower.dto.AbcCurveDTO;
import com.fabrica.controltower.dto.InventoryTurnoverDTO;
import com.fabrica.controltower.dto.KpiSummaryDTO;
import com.fabrica.controltower.dto.WorkOrderFulfilmentDTO;
import com.fabrica.controltower.entity.StockMovement;
import com.fabrica.controltower.entity.SupplyItem;
import com.fabrica.controltower.entity.WorkOrder;
import com.fabrica.controltower.repository.SupplyRepository;
import com.fabrica.controltower.repository.SupplyRepository.SeverityCountProjection;
import com.fabrica.controltower.repository.StockMovementRepository;
import com.fabrica.controltower.repository.WorkOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Aggregates the executive Control Tower dashboard.
 *
 * <p>Each call is a short read-only transaction: the dashboard polls every
 * few seconds, so nothing here may hold a transaction open.</p>
 */
@Slf4j
@Service
public class KpiService {

    private final SupplyRepository supplyRepository;
    private final WorkOrderRepository workOrderRepository;
    private final StockMovementRepository movementRepository;
    private final AbcCurveService abcCurveService;
    private final StockService stockService;
    private final SupplyChainService supplyChainService;

    public KpiService(SupplyRepository supplyRepository,
                      WorkOrderRepository workOrderRepository,
                      StockMovementRepository movementRepository,
                      AbcCurveService abcCurveService,
                      StockService stockService,
                      SupplyChainService supplyChainService) {
        this.supplyRepository = supplyRepository;
        this.workOrderRepository = workOrderRepository;
        this.movementRepository = movementRepository;
        this.abcCurveService = abcCurveService;
        this.stockService = stockService;
        this.supplyChainService = supplyChainService;
    }

    /** Aggregated dashboard metrics (GET /api/v1/dashboard/kpis). */
    @Transactional(readOnly = true)
    public KpiSummaryDTO calculateKpis() {
        // Same parameters StockService uses, so the SQL severity counts and
        // the Java classification can never disagree.
        SeverityCountProjection counts = supplyRepository.countBySeverity(
                stockService.reorderPointMultiplier().doubleValue(),
                stockService.getWarningMultiplier());

        AbcCurveDTO curve = abcCurveService.calculateAbcCurveReadOnly();
        AbcCurveDTO.SummaryByCategoryDTO summary = curve.summaryByCategory();

        LocalDateTime since24h = LocalDateTime.now().minusHours(24);

        BigDecimal dailyConsumption = round(BigDecimal.valueOf(counts.getAverageDailyConsumption()));
        BigDecimal averageCoverage = calculateAverageCoverage(dailyConsumption, counts.getTotal());

        // Giro de estoque and tempo medio de atendimento: the two indicators the
        // Team 5 brief requires the dashboard to monitor.
        InventoryTurnoverDTO turnover = supplyChainService.calculateTurnover();
        WorkOrderFulfilmentDTO fulfilment = supplyChainService.calculateFulfilment();

        return new KpiSummaryDTO(
                counts.getTotal(),
                curve.totalValue(),
                summary.classAItems(),
                summary.classBItems(),
                summary.classCItems(),
                BigDecimal.valueOf(summary.classAPercentage()).setScale(2, RoundingMode.HALF_UP),
                counts.getStockout(),
                counts.getCritical(),
                counts.getWarning(),
                counts.getHealthy(),
                workOrderRepository.countByStatus(WorkOrder.STATUS_OPEN),
                workOrderRepository.countByStatus(WorkOrder.STATUS_IN_PROGRESS),
                workOrderRepository.countByStatus(WorkOrder.STATUS_COMPLETED),
                movementRepository.countByOccurredAtAfter(since24h),
                dailyConsumption,
                averageCoverage,
                calculateHealthIndex(counts),
                turnover.turnover(),
                turnover.daysOfInventory(),
                turnover.cogs(),
                fulfilment.averageFulfilmentHours(),
                fulfilment.slaCompliancePercent(),
                Instant.now());
    }

    /**
     * Average stock coverage in days: total balance divided by daily demand.
     * This is the macro average; per-item coverage lives in
     * {@code StockoutAlertDTO.daysUntilStockout}.
     */
    private BigDecimal calculateAverageCoverage(BigDecimal dailyConsumption, long totalItems) {
        if (dailyConsumption == null
                || dailyConsumption.compareTo(BigDecimal.ZERO) <= 0
                || totalItems == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        Long totalBalance = supplyRepository.sumActiveQuantity();
        if (totalBalance == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(totalBalance).divide(dailyConsumption, 2, RoundingMode.HALF_UP);
    }

    /**
     * Inventory health index, 0-100.
     *
     * <p>Weights a stockout (4) and a critical item (2) against the item
     * count: one emptied item hurts more than ten items under warning.</p>
     */
    private double calculateHealthIndex(SeverityCountProjection counts) {
        long total = counts.getTotal();
        if (total == 0) {
            return 100.0;
        }
        double penalty = counts.getStockout() * 4.0
                + counts.getCritical() * 2.0
                + counts.getWarning() * 0.5;
        double index = (1.0 - penalty / (total * 4.0)) * 100.0;
        return Math.round(Math.max(0.0, Math.min(100.0, index)) * 10.0) / 10.0;
    }

    private BigDecimal round(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Most recent work orders, for the activity feed. */
    @Transactional(readOnly = true)
    public List<WorkOrder> findRecentWorkOrders(int limit) {
        return workOrderRepository.findRecent(PageRequest.of(0, Math.max(1, limit)));
    }

    /** Most recent stock movements, for the activity feed. */
    @Transactional(readOnly = true)
    public List<StockMovement> findRecentMovements(int limit) {
        return movementRepository.findRecent(PageRequest.of(0, Math.max(1, limit)));
    }

    /** Full catalogue, backing the dashboard item table. */
    @Transactional(readOnly = true)
    public List<SupplyItem> listSupplyItems() {
        return supplyRepository.findByActiveTrueOrderByCodeAsc();
    }
}