package com.fabrica.controltower.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inventory turnover (GET /api/v1/supplies/turnover).
 *
 * <p><b>Giro de estoque</b> — one of the three indicators the Team 5 brief
 * requires. It answers "how many times did the warehouse turn its stock over
 * during the window", which raw balance alone cannot express.</p>
 *
 * <h2>Formulas</h2>
 * <pre>
 *   COGS              = value of OUTBOUND movements in the window
 *   InventoryAtStart  = currentValue + COGS - inboundValue
 *   AverageInventory  = (InventoryAtStart + currentValue) / 2
 *   Turnover          = COGS / AverageInventory
 *   DaysOfInventory   = windowDays / Turnover
 *   AnnualisedTurnover= Turnover * (365 / windowDays)
 * </pre>
 *
 * @param windowDays             length of the analysed window, in days
 * @param cogs                   cost of goods sold in the window
 * @param inboundValue           value received in the window
 * @param currentValue           inventory value right now
 * @param inventoryAtWindowStart estimated inventory value at the start
 * @param averageInventory       average inventory value over the window
 * @param turnover               turnover ratio (x) within the window
 * @param annualisedTurnover     turnover projected to 365 days
 * @param daysOfInventory        days of inventory on hand (DIO)
 * @param turnoverByClass        turnover split per ABC class
 */
public record InventoryTurnoverDTO(
        int windowDays,
        BigDecimal cogs,
        BigDecimal inboundValue,
        BigDecimal currentValue,
        BigDecimal inventoryAtWindowStart,
        BigDecimal averageInventory,
        BigDecimal turnover,
        BigDecimal annualisedTurnover,
        BigDecimal daysOfInventory,
        List<TurnoverByClassDTO> turnoverByClass
) {

    /**
     * Turnover restricted to one ABC class.
     *
     * <p>Class A usually shows the highest turnover: the manager spends the
     * money that generates the velocity. Class C is the opposite case — money
     * parked with little movement.</p>
     *
     * @param itemCount          items in the class
     * @param classSharePercent  share of total inventory value, in percent
     */
    public record TurnoverByClassDTO(
            String abcClass,
            long itemCount,
            BigDecimal cogs,
            BigDecimal currentValue,
            BigDecimal averageInventory,
            BigDecimal turnover,
            BigDecimal daysOfInventory,
            BigDecimal classSharePercent
    ) {
    }
}