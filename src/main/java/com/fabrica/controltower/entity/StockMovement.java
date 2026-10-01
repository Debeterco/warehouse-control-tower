package com.fabrica.controltower.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Immutable stock movement ledger entry.
 *
 * <p>Every balance change on a {@link SupplyItem} writes a row here, which
 * makes it possible to reconstruct history and audit the ABC curve.</p>
 */
@Entity
@Table(name = "tb_stock_movement")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovement {

    public static final String TYPE_INBOUND = "INBOUND";
    public static final String TYPE_OUTBOUND = "OUTBOUND";
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    public static final String TYPE_INVENTORY = "INVENTORY";

    public static final String ORIGIN_SIMULATOR = "SIMULATOR";
    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_AUTO_REPLENISHMENT = "AUTO_REPLENISHMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supply_item_id", nullable = false)
    private SupplyItem supplyItem;

    /** Work order that triggered the consumption. Null for inbound stock. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private WorkOrder workOrder;

    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** Item balance immediately after this entry was recorded. */
    @Column(name = "resulting_balance", nullable = false)
    private Integer resultingBalance;

    @Builder.Default
    @Column(name = "origin", nullable = false, length = 30)
    private String origin = ORIGIN_MANUAL;
}