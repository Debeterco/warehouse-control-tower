package com.fabrica.controltower.repository;

import com.fabrica.controltower.entity.SupplyItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Supply item repository.
 *
 * <p>Balance mutations run as atomic native statements
 * ({@code SET current_quantity = current_quantity - :q}) so the
 * {@code TelemetrySimulatorTask} and a REST-driven consumption can never
 * overwrite each other when they touch the same item at the same time.</p>
 */
@Repository
public interface SupplyRepository extends JpaRepository<SupplyItem, Long> {

    Optional<SupplyItem> findByCodeIgnoreCase(String code);

    List<SupplyItem> findByActiveTrueOrderByCodeAsc();

    /**
     * Base of the ABC curve: every active item, highest inventory value first.
     * Valuation is computed in SQL so both the ordering and the classification
     * come from the database.
     */
    @Query("""
            SELECT i FROM SupplyItem i
            WHERE i.active = TRUE
            ORDER BY (i.currentQuantity * i.unitCost) DESC, i.code ASC
            """)
    List<SupplyItem> findAllActiveOrderedByValue();

    /**
     * Items at risk of stocking out: already zeroed, below the reorder point,
     * or inside the warning band.
     *
     * @param alertMultiplier reorder-point multiplier (1 + safety factor) scaled
     *                       by the warning threshold
     */
    @Query("""
            SELECT i FROM SupplyItem i
            WHERE i.active = TRUE
              AND i.averageDailyConsumption > :zero
              AND i.currentQuantity <= (i.averageDailyConsumption * i.replenishmentLeadTimeDays * :alertMultiplier)
            ORDER BY i.currentQuantity ASC, i.unitCost DESC
            """)
    List<SupplyItem> findItemsAtRiskOfStockout(@Param("alertMultiplier") BigDecimal alertMultiplier,
                                                @Param("zero") BigDecimal zero);

    /** KPI rollup by severity, computed in a single pass in the database. */
    @Query(value = """
            SELECT
                COUNT(*)                                                              AS total,
                COALESCE(SUM(average_daily_consumption), 0)                            AS averageDailyConsumption,
                COUNT(*) FILTER (WHERE current_quantity <= 0)                         AS stockout,
                COUNT(*) FILTER (WHERE current_quantity > 0
                                   AND current_quantity < average_daily_consumption * replenishment_lead_time_days * :reorderPoint)      AS critical,
                COUNT(*) FILTER (WHERE current_quantity >= average_daily_consumption * replenishment_lead_time_days * :reorderPoint
                                   AND current_quantity < average_daily_consumption * replenishment_lead_time_days * :reorderPoint * :warningMultiplier) AS warning,
                COUNT(*) FILTER (WHERE current_quantity >= average_daily_consumption * replenishment_lead_time_days * :reorderPoint * :warningMultiplier) AS healthy
            FROM tb_supply_item
            WHERE active = TRUE
            """, nativeQuery = true)
    SeverityCountProjection countBySeverity(
            @Param("reorderPoint") double reorderPoint,
            @Param("warningMultiplier") double warningMultiplier);

    @Query("SELECT COALESCE(SUM(i.currentQuantity * i.unitCost), 0) FROM SupplyItem i WHERE i.active = TRUE")
    BigDecimal sumActiveInventoryValue();

    @Query("SELECT COALESCE(SUM(i.currentQuantity), 0) FROM SupplyItem i WHERE i.active = TRUE")
    Long sumActiveQuantity();

    /**
     * Atomically debits stock without clamping: the debit only applies when
     * the balance is sufficient.
     *
     * @return 1 if applied, 0 if the balance was insufficient
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tb_supply_item
               SET current_quantity = current_quantity - :quantity
             WHERE id = :id
               AND current_quantity >= :quantity
            """, nativeQuery = true)
    int debitStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tb_supply_item SET current_quantity = current_quantity + :quantity WHERE id = :id",
            nativeQuery = true)
    int creditStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** Persists the ABC class calculated by {@code AbcCurveService}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tb_supply_item SET abc_category = :category WHERE id = :id", nativeQuery = true)
    int updateAbcCategory(@Param("id") Long id, @Param("category") String category);

    // ==================================================================
    // Inventory turnover (giro de estoque)
    // ==================================================================

    /**
     * Value of stock leaving the warehouse in the window, per ABC class.
     * This is the COGS numerator of the turnover ratio.
     */
    @Query(value = """
            SELECT i.abc_category                          AS abcClass,
                   COALESCE(SUM(m.quantity * i.unit_cost), 0) AS cogs
            FROM tb_stock_movement m
            JOIN tb_supply_item i ON i.id = m.supply_item_id
            WHERE m.movement_type = 'OUTBOUND'
              AND m.occurred_at >= :since
              AND i.active = TRUE
            GROUP BY i.abc_category
            """, nativeQuery = true)
    List<Object[]> findCogsByAbcClassSince(@Param("since") LocalDateTime since);

    /**
     * Inventory value per ABC class, plus the inbound value received in the window.
     *
     * <p>Two CTEs rather than one join: joining movements onto items multiplies
     * the item rows, which would inflate both {@code COUNT(*)} and the
     * current-value sum.</p>
     */
    @Query(value = """
            WITH item_value AS (
                SELECT abc_category,
                       COUNT(*)                                     AS itemCount,
                       SUM(current_quantity * unit_cost)            AS currentValue
                FROM tb_supply_item
                WHERE active = TRUE
                GROUP BY abc_category
            ),
            inbound AS (
                SELECT i.abc_category,
                       SUM(m.quantity * i.unit_cost) AS inboundValue
                FROM tb_stock_movement m
                JOIN tb_supply_item i ON i.id = m.supply_item_id
                WHERE m.movement_type = 'INBOUND'
                  AND m.occurred_at >= :since
                  AND i.active = TRUE
                GROUP BY i.abc_category
            )
            SELECT v.abc_category                             AS abcClass,
                   v.itemCount                                 AS itemCount,
                   v.currentValue                              AS currentValue,
                   COALESCE(n.inboundValue, 0)                 AS inboundValue
            FROM item_value v
            LEFT JOIN inbound n ON n.abc_category = v.abc_category
            ORDER BY v.abc_category
            """, nativeQuery = true)
    List<Object[]> findValueAndInboundByAbcClass(@Param("since") LocalDateTime since);

    // ==================================================================
    // Supplier performance
    // ==================================================================

    /**
     * Per-supplier rollup: how much value each one carries and how much of it
     * is already below the reorder point.
     *
     * <p>Uses the same 1.5x lead-time-demand threshold as
     * {@code StockService.classify} so the risk count cannot drift from the
     * severity shown elsewhere on the dashboard.</p>
     */
    @Query(value = """
            SELECT i.supplier                                                AS supplier,
                   COUNT(*)                                                  AS itemCount,
                   COALESCE(SUM(i.current_quantity * i.unit_cost), 0)       AS totalValue,
                   COUNT(*) FILTER (WHERE i.current_quantity <= 0)           AS stockouts,
                   COUNT(*) FILTER (WHERE i.current_quantity > 0
                                        AND i.current_quantity < i.average_daily_consumption
                                                                  * i.replenishment_lead_time_days * 1.5) AS critical,
                   COALESCE(AVG(i.replenishment_lead_time_days), 0)         AS averageLeadTimeDays,
                   MIN(i.replenishment_lead_time_days)                      AS minLeadTimeDays,
                   MAX(i.replenishment_lead_time_days)                      AS maxLeadTimeDays
            FROM tb_supply_item i
            WHERE i.active = TRUE
              AND i.supplier IS NOT NULL
            GROUP BY i.supplier
            ORDER BY totalValue DESC
            """, nativeQuery = true)
    List<SupplierProjection> findSupplierPerformance();

    /** Supplier rollup projection. */
    interface SupplierProjection {
        String getSupplier();

        long getItemCount();

        BigDecimal getTotalValue();

        long getStockouts();

        long getCritical();

        BigDecimal getAverageLeadTimeDays();

        int getMinLeadTimeDays();

        int getMaxLeadTimeDays();
    }

    /** Severity count projection. */
    interface SeverityCountProjection {
        long getTotal();

        double getAverageDailyConsumption();

        long getStockout();

        long getCritical();

        long getWarning();

        long getHealthy();
    }
}