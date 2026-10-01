package com.fabrica.controltower.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Executive dashboard summary (GET /api/v1/dashboard/kpis).
 *
 * @param totalSupplyItems        active items in the catalogue
 * @param totalInventoryValue     total inventory valuation (quantity x unit cost)
 * @param classAItems             items in ABC class A
 * @param classBItems             items in ABC class B
 * @param classCItems             items in ABC class C
 * @param classAValuePercentage   share of total value held by class A (the 80/20 rule)
 * @param stockoutItems           items with a zeroed balance
 * @param criticalItems           items below their reorder point
 * @param warningItems            items above the reorder point but heading to a stockout
 * @param healthyItems            items with safe coverage
 * @param openWorkOrders          work orders in OPEN status
 * @param inProgressWorkOrders    work orders in IN_PROGRESS status
 * @param completedWorkOrders     completed work orders
 * @param movementsLast24h         movements recorded in the last 24 hours
 * @param averageDailyConsumption aggregate demand (units/day)
 * @param averageCoverageDays     average stock coverage, in days
 * @param inventoryHealthIndex    0-100: 100 means no item at risk
 * @param inventoryTurnover       giro de estoque: times stock turned over in the window
 * @param daysOfInventory         days of inventory on hand (the inverse of turnover)
 * @param cogsLast30Days          cost of goods sold in the analysis window
 * @param averageFulfilmentHours  tempo medio de atendimento of a work order, in hours
 * @param workOrdersCompleted     work orders closed inside the service target (%)
 * @param computedAt              instant the metrics were calculated
 */
public record KpiSummaryDTO(
        long totalSupplyItems,
        BigDecimal totalInventoryValue,
        long classAItems,
        long classBItems,
        long classCItems,
        BigDecimal classAValuePercentage,
        long stockoutItems,
        long criticalItems,
        long warningItems,
        long healthyItems,
        long openWorkOrders,
        long inProgressWorkOrders,
        long completedWorkOrders,
        long movementsLast24h,
        BigDecimal averageDailyConsumption,
        BigDecimal averageCoverageDays,
        double inventoryHealthIndex,
        BigDecimal inventoryTurnover,
        BigDecimal daysOfInventory,
        BigDecimal cogsLast30Days,
        BigDecimal averageFulfilmentHours,
        BigDecimal workOrdersCompletedPercent,
        Instant computedAt
) {
}