package com.fabrica.controltower.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Telemetry simulation settings (POST /api/v1/simulation/parameters).
 *
 * <p>Every field is optional: anything omitted keeps its current value,
 * which allows tuning a single setting per call.</p>
 *
 * @param active     turns the event generator on or off
 * @param intervalMs delay between cycles, in milliseconds (500-60000)
 * @param intensity  event multiplier per cycle (0.1-5.0)
 */
public record SimulationParametersDTO(
        Boolean active,
        @Min(value = 500, message = "intervalMs minimum is 500 ms")
        @Max(value = 60_000, message = "intervalMs maximum is 60000 ms")
        Integer intervalMs,
        @DecimalMin(value = "0.1", message = "intensity minimum is 0.1")
        @DecimalMax(value = "5.0", message = "intensity maximum is 5.0")
        Double intensity
) {
}