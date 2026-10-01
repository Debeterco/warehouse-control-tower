package com.fabrica.controltower.controller;

import com.fabrica.controltower.dto.SimulationParametersDTO;
import com.fabrica.controltower.scheduler.TelemetrySimulatorTask;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telemetry simulation control.
 *
 * <p>Settings apply at runtime and take effect on the next cycle — including
 * the interval, which is rescheduled immediately.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/simulation")
public class SimulationController {

    private final TelemetrySimulatorTask simulator;

    public SimulationController(TelemetrySimulatorTask simulator) {
        this.simulator = simulator;
    }

    /**
     * Updates the simulation settings.
     *
     * <p>Every field is optional; omitted ones keep their current value. Bounds
     * are enforced by Bean Validation (interval 500-60000 ms, intensity
     * 0.1-5.0).</p>
     */
    @PostMapping("/parameters")
    public ResponseEntity<SimulationParametersDTO> updateParameters(
            @Valid @RequestBody SimulationParametersDTO parameters) {

        SimulationParametersDTO current = simulator.updateParameters(parameters);
        log.info("Simulation parameters updated: {}", current);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(current);
    }

    /** Current settings and counters from the simulator. */
    @GetMapping("/parameters")
    public ResponseEntity<TelemetrySimulatorTask.SimulatorStatistics> getParameters() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(simulator.currentStatistics());
    }
}