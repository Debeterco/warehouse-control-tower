package com.fabrica.controltower.controller;

import com.fabrica.controltower.dto.AbcCurveDTO;
import com.fabrica.controltower.dto.InventoryTurnoverDTO;
import com.fabrica.controltower.dto.StockoutAlertDTO;
import com.fabrica.controltower.dto.SupplierPerformanceDTO;
import com.fabrica.controltower.entity.SupplyItem;
import com.fabrica.controltower.service.AbcCurveService;
import com.fabrica.controltower.service.KpiService;
import com.fabrica.controltower.service.StockService;
import com.fabrica.controltower.service.SupplyChainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Analytical queries over supply items: ABC curve and stockout alerts.
 *
 * <p>Both recompute from the current database state on every call — these are
 * cheap reads (one pass over the catalogue) and it is what keeps the
 * dashboard consistent with the simulation in flight.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/supplies")
public class SupplyController {

    private final AbcCurveService abcCurveService;
    private final StockService stockService;
    private final KpiService kpiService;
    private final SupplyChainService supplyChainService;

    public SupplyController(AbcCurveService abcCurveService,
                            StockService stockService,
                            KpiService kpiService,
                            SupplyChainService supplyChainService) {
        this.abcCurveService = abcCurveService;
        this.stockService = stockService;
        this.kpiService = kpiService;
        this.supplyChainService = supplyChainService;
    }

    /**
     * ABC / Pareto curve of the warehouse.
     *
     * <p>Sorts by inventory value, classifies into A/B/C on the cumulative
     * share and materialises the result in {@code tb_supply_item}.</p>
     */
    @GetMapping("/abc-curve")
    public ResponseEntity<AbcCurveDTO> getAbcCurve() {
        AbcCurveDTO curve = abcCurveService.calculateAbcCurve();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Total-Count", String.valueOf(curve.items().size()))
                .body(curve);
    }

    /**
     * Stockout alerts, most critical first.
     *
     * @param severity optional filter: STOCKOUT, CRITICAL or WARNING
     */
    @GetMapping("/stockout-alerts")
    public ResponseEntity<List<StockoutAlertDTO>> getStockoutAlerts(
            @RequestParam(required = false) String severity) {

        List<StockoutAlertDTO> alerts = stockService.listStockoutAlerts();

        if (severity != null && !severity.isBlank()) {
            String target = severity.trim().toUpperCase();
            alerts = alerts.stream()
                    .filter(a -> a.severity().equals(target))
                    .toList();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Total-Count", String.valueOf(alerts.size()))
                .body(alerts);
    }

    /** Full catalogue, backing the dashboard item table. */
    @GetMapping
    public ResponseEntity<List<SupplyItem>> listSupplyItems() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(kpiService.listSupplyItems());
    }

    /**
     * Recalculates the reorder point and severity of a single item.
     *
     * <p>Returns 404 when the item does not exist.</p>
     */
    @GetMapping("/{id}/reorder-point")
    public ResponseEntity<StockoutAlertDTO> getReorderPoint(@PathVariable Long id) {
        return stockService.findAlert(id)
                .map(alert -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(alert))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Inventory turnover (giro de estoque).
     *
     * <p>Returns the ratio, the COGS and average inventory behind it, and the
     * split per ABC class.</p>
     */
    @GetMapping("/turnover")
    public ResponseEntity<InventoryTurnoverDTO> getTurnover() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(supplyChainService.calculateTurnover());
    }

    /**
     * Supplier performance rollup.
     *
     * <p>Value carried, lead time and how much of each supplier's catalogue is
     * currently below its reorder point.</p>
     */
    @GetMapping("/supplier-performance")
    public ResponseEntity<SupplierPerformanceDTO> getSupplierPerformance() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(supplyChainService.calculateSupplierPerformance());
    }
}