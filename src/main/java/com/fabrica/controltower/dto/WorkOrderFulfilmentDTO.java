package com.fabrica.controltower.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Work order fulfilment performance
 * (GET /api/v1/work-orders/fulfilment).
 *
 * <p><b>Tempo médio de atendimento</b> — the second indicator the Team 5 brief
 * requires. It measures how long maintenance takes from the moment the order is
 * raised to the moment it is closed, which is the number the floor reports back
 * to supply and logistics.</p>
 *
 * @param completedCount       orders already closed (the measured population)
 * @param averageFulfilmentHours mean hours between createdAt and completedAt
 * @param medianFulfilmentHours  median hours, less sensitive to outliers
 * @param minFulfilmentHours     fastest closed order
 * @param maxFulfilmentHours     slowest closed order
 * @param openCount            orders still OPEN or IN_PROGRESS
 * @param averageOpenAgeHours  mean age of the orders still open
 * @param oldestOpenAgeHours   age of the most overdue open order
 * @param slaCompliancePercent share of closed orders inside the 48h target
 * @param slaTargetHours       the service target used to compute compliance
 * @param byDepartment         the same measures split by requesting department
 */
public record WorkOrderFulfilmentDTO(
        long completedCount,
        BigDecimal averageFulfilmentHours,
        BigDecimal medianFulfilmentHours,
        BigDecimal minFulfilmentHours,
        BigDecimal maxFulfilmentHours,
        long openCount,
        BigDecimal averageOpenAgeHours,
        BigDecimal oldestOpenAgeHours,
        BigDecimal slaCompliancePercent,
        BigDecimal slaTargetHours,
        List<DepartmentFulfilmentDTO> byDepartment
) {

    /**
     * Fulfilment for one department.
     *
     * @param backlogCount orders still open for this department
     */
    public record DepartmentFulfilmentDTO(
            String department,
            long completedCount,
            BigDecimal averageFulfilmentHours,
            long backlogCount,
            BigDecimal slaCompliancePercent
    ) {
    }
}