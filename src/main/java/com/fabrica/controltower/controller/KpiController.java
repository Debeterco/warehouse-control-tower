package com.fabrica.controltower.controller;

import com.fabrica.controltower.dto.KpiSummaryDTO;
import com.fabrica.controltower.service.KpiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Executive Control Tower dashboard endpoints. */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
public class KpiController {

    private final KpiService kpiService;

    public KpiController(KpiService kpiService) {
        this.kpiService = kpiService;
    }

    /**
     * Consolidated warehouse metrics.
     *
     * <p>Response is not cacheable: the dashboard polls every few seconds and
     * the figures must reflect live telemetry.</p>
     */
    @GetMapping("/kpis")
    public ResponseEntity<KpiSummaryDTO> getKpis() {
        KpiSummaryDTO kpis = kpiService.calculateKpis();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Computed-At", String.valueOf(kpis.computedAt()))
                .body(kpis);
    }
}