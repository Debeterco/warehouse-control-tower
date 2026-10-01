package com.fabrica.controltower.controller;

import com.fabrica.controltower.dto.WorkOrderFulfilmentDTO;
import com.fabrica.controltower.service.SupplyChainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Work order performance endpoints.
 *
 * <p>Focuses on {@code tempo medio de atendimento}, one of the three
 * indicators the Team 5 brief requires the dashboard to monitor.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

    private final SupplyChainService supplyChainService;

    public WorkOrderController(SupplyChainService supplyChainService) {
        this.supplyChainService = supplyChainService;
    }

    /**
     * Average fulfilment time of work orders.
     *
     * <p>Returns the mean, median and spread of the hours between order
     * creation and completion, plus the open backlog and the service-level
     * compliance rate.</p>
     */
    @GetMapping("/fulfilment")
    public ResponseEntity<WorkOrderFulfilmentDTO> getFulfilment() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(supplyChainService.calculateFulfilment());
    }
}