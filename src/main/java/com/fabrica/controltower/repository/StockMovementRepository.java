package com.fabrica.controltower.repository;

import com.fabrica.controltower.entity.StockMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Repository for the stock movement ledger. */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    long countByOccurredAtAfter(LocalDateTime instant);

    long countByMovementTypeAndOccurredAtAfter(String movementType, LocalDateTime instant);

    @Query("""
            SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m
            WHERE m.movementType = :type
              AND m.occurredAt >= :since
              AND m.supplyItem.id = :supplyItemId
            """)
    long sumQuantityBySupplyItem(@Param("supplyItemId") Long supplyItemId,
                                 @Param("type") String movementType,
                                 @Param("since") LocalDateTime since);

    /**
     * Real consumption per item within the analysis window, taken straight
     * from history. Used to recompute average demand as telemetry runs.
     */
    @Query("""
            SELECT m.supplyItem.id, COALESCE(SUM(m.quantity), 0)
            FROM StockMovement m
            WHERE m.movementType = 'OUTBOUND'
              AND m.occurredAt >= :since
            GROUP BY m.supplyItem.id
            """)
    List<Object[]> mapConsumptionBySupplyItemSince(@Param("since") LocalDateTime since);

    @Query("""
            SELECT m FROM StockMovement m
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<StockMovement> findRecent(Pageable pageable);

    /** Movements for one item, powering the detail trail in the UI. */
    @Query("""
            SELECT m FROM StockMovement m
            WHERE m.supplyItem.id = :supplyItemId
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<StockMovement> findBySupplyItem(@Param("supplyItemId") Long supplyItemId, Pageable pageable);
}