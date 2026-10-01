package com.fabrica.controltower.repository;

import com.fabrica.controltower.entity.WorkOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Repository for maintenance work orders. */
@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    long countByStatus(String status);

    @Query("""
            SELECT w FROM WorkOrder w
            ORDER BY w.createdAt DESC
            """)
    List<WorkOrder> findRecent(Pageable pageable);

    @Query("""
            SELECT w FROM WorkOrder w
            WHERE w.status IN (com.fabrica.controltower.entity.WorkOrder.STATUS_OPEN,
                               com.fabrica.controltower.entity.WorkOrder.STATUS_IN_PROGRESS)
            ORDER BY w.createdAt ASC
            """)
    List<WorkOrder> findOpen();

    @Query("SELECT COALESCE(MAX(w.id), 0) FROM WorkOrder w")
    Long findMaxId();

    // ==================================================================
    // Fulfilment time (tempo medio de atendimento)
    // ==================================================================

    /**
     * Distribution of the hours between order creation and completion.
     *
     * <p>{@code percentile_cont} gives the median alongside the mean, because a
     * single long outage drags the average far from what a technician actually
     * experiences.</p>
     */
    @Query(value = """
            SELECT COUNT(*)                                                        AS completedCount,
                   COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - created_at)) / 3600.0), 0) AS averageHours,
                   COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at)) / 3600.0), 0) AS medianHours,
                   COALESCE(MIN(EXTRACT(EPOCH FROM (completed_at - created_at)) / 3600.0), 0) AS minHours,
                   COALESCE(MAX(EXTRACT(EPOCH FROM (completed_at - created_at)) / 3600.0), 0) AS maxHours,
                   COUNT(*) FILTER (WHERE completed_at - created_at <= make_interval(hours => CAST(:slaHours AS int))) AS withinSla
            FROM tb_work_order
            WHERE status = 'COMPLETED'
              AND completed_at IS NOT NULL
            """, nativeQuery = true)
    FulfilmentProjection findFulfilmentStatistics(@Param("slaHours") double slaHours);

    /**
     * Mean and maximum age of the orders still open, in hours.
     *
     * <p>{@code AT TIME ZONE 'UTC'} matters here: Hibernate writes timestamps
     * in UTC ({@code hibernate.jdbc.time_zone=UTC}) while {@code NOW()} returns
     * the server's local time. Comparing them directly yields a negative age
     * whenever the server is behind UTC, as it is in Brazil (UTC-3).</p>
     */
    @Query(value = """
            SELECT COUNT(*)                                                                          AS openCount,
                   COALESCE(AVG(EXTRACT(EPOCH FROM ((NOW() AT TIME ZONE 'UTC') - created_at)) / 3600.0), 0) AS averageAgeHours,
                   COALESCE(MAX(EXTRACT(EPOCH FROM ((NOW() AT TIME ZONE 'UTC') - created_at)) / 3600.0), 0) AS oldestAgeHours
            FROM tb_work_order
            WHERE status IN ('OPEN', 'IN_PROGRESS')
            """, nativeQuery = true)
    OpenAgeProjection findOpenOrderAge();

    /**
     * Fulfilment split by requesting department, so the manager can see which
     * area is the bottleneck rather than only the plant-wide average.
     */
    @Query(value = """
            SELECT requesting_department                                        AS department,
                   COUNT(*)                                                    AS completedCount,
                   COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - created_at)) / 3600.0), 0) AS averageHours,
                   COUNT(*) FILTER (WHERE completed_at - created_at <= make_interval(hours => CAST(:slaHours AS int))) AS withinSla
            FROM tb_work_order
            WHERE status = 'COMPLETED'
              AND completed_at IS NOT NULL
            GROUP BY requesting_department
            HAVING COUNT(*) > 0
            ORDER BY averageHours DESC
            """, nativeQuery = true)
    List<DepartmentFulfilmentProjection> findFulfilmentByDepartment(@Param("slaHours") double slaHours);

    /** Open order backlog per department. */
    @Query(value = """
            SELECT requesting_department AS department,
                   COUNT(*)             AS backlogCount
            FROM tb_work_order
            WHERE status IN ('OPEN', 'IN_PROGRESS')
            GROUP BY requesting_department
            """, nativeQuery = true)
    List<Object[]> findBacklogByDepartment();

    /** Fulfilment statistics projection. */
    interface FulfilmentProjection {
        long getCompletedCount();

        BigDecimal getAverageHours();

        BigDecimal getMedianHours();

        BigDecimal getMinHours();

        BigDecimal getMaxHours();

        long getWithinSla();
    }

    /** Open order age projection. */
    interface OpenAgeProjection {
        long getOpenCount();

        BigDecimal getAverageAgeHours();

        BigDecimal getOldestAgeHours();
    }

    /** Per-department fulfilment projection. */
    interface DepartmentFulfilmentProjection {
        String getDepartment();

        long getCompletedCount();

        BigDecimal getAverageHours();

        long getWithinSla();
    }

    /** Advances open work orders through the maintenance workflow. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tb_work_order
               SET status = :newStatus,
                   completed_at = CASE WHEN :complete THEN :now ELSE completed_at END
             WHERE id = :id
            """, nativeQuery = true)
    int transitionStatus(@Param("id") Long id,
                         @Param("newStatus") String newStatus,
                         @Param("complete") boolean complete,
                         @Param("now") LocalDateTime now);
}