package com.fabrica.controltower.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A warehouse supply item.
 *
 * <p>The inventory value ({@code currentQuantity * unitCost}) is the basis of
 * the ABC curve, while {@code averageDailyConsumption} and
 * {@code replenishmentLeadTimeDays} feed the Reorder Point calculation.</p>
 */
@Entity
@Table(name = "tb_supply_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Builder.Default
    @Column(name = "unit_of_measure", nullable = false, length = 20)
    private String unitOfMeasure = "UN";

    /** Available balance. Never negative (enforced by a database check). */
    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity;

    /** Operational minimum stock: the replenishment trigger. */
    @Column(name = "minimum_stock", nullable = false)
    private Integer minimumStock;

    /** Supplier lead time, in days. */
    @Column(name = "replenishment_lead_time_days", nullable = false)
    private Integer replenishmentLeadTimeDays;

    /** Average demand, in units per day. */
    @Column(name = "average_daily_consumption", nullable = false, precision = 14, scale = 4)
    private BigDecimal averageDailyConsumption;

    @Column(name = "unit_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitCost;

    /** Materialised ABC classification: A, B or C. */
    @Column(name = "abc_category", nullable = false, length = 1)
    private String abcCategory;

    @Column(name = "location", length = 60)
    private String location;

    @Column(name = "supplier", length = 120)
    private String supplier;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    /** Value tied up in this item: quantity x unit cost. */
    public BigDecimal getInventoryValue() {
        if (currentQuantity == null || unitCost == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(currentQuantity).multiply(unitCost);
    }

    /** Days of stock left at the current demand (null when demand is zero). */
    public BigDecimal getDaysOfCoverage() {
        if (averageDailyConsumption == null
                || averageDailyConsumption.compareTo(BigDecimal.ZERO) <= 0
                || currentQuantity == null) {
            return null;
        }
        return BigDecimal.valueOf(currentQuantity)
                .divide(averageDailyConsumption, 2, RoundingMode.HALF_UP);
    }
}