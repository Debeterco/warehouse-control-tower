package com.fabrica.controltower.scheduler;

import com.fabrica.controltower.dto.SimulationParametersDTO;
import com.fabrica.controltower.entity.StockMovement;
import com.fabrica.controltower.entity.SupplyItem;
import com.fabrica.controltower.entity.WorkOrder;
import com.fabrica.controltower.repository.SupplyRepository;
import com.fabrica.controltower.repository.WorkOrderRepository;
import com.fabrica.controltower.service.AbcCurveService;
import com.fabrica.controltower.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telemetry simulator for the warehouse.
 *
 * <p>Every cycle (5 s by default) it emits events that move data on its own
 * in PostgreSQL: opening work orders, advancing statuses, consuming items and
 * replenishing stock. That is what keeps the Control Tower dashboard moving in
 * real time.</p>
 *
 * <h2>Dynamic scheduling</h2>
 * <p>The cycle is rescheduled after each run through {@link TaskScheduler}
 * instead of {@code @Scheduled(fixedDelayString = ...)}. With
 * {@code @Scheduled} the interval would only be read at startup, and
 * {@code POST /api/v1/simulation/parameters} could not change it without
 * restarting the application.</p>
 *
 * <p>Each unit of work runs in its own transaction
 * ({@link TransactionTemplate}); the scheduler never holds a transaction open
 * between cycles.</p>
 */
@Slf4j
@Component
public class TelemetrySimulatorTask implements DisposableBean {

    private final TaskScheduler scheduler;
    private final TransactionTemplate tx;
    private final StockService stockService;
    private final SupplyRepository supplyRepository;
    private final WorkOrderRepository workOrderRepository;
    private final AbcCurveService abcCurveService;

    private final double workOrderProbability;
    private final double replenishmentProbability;

    // Runtime-mutable settings
    private volatile boolean active;
    private volatile long intervalMs;
    private volatile double intensity;

    private final Object schedulingLock = new Object();
    private volatile ScheduledFuture<?> nextCycle;

    private final AtomicLong cyclesExecuted = new AtomicLong();
    private final AtomicLong workOrdersGenerated = new AtomicLong();
    private final AtomicLong movementsGenerated = new AtomicLong();
    private final AtomicLong replenishmentsGenerated = new AtomicLong();
    private volatile Instant startedAt;

    /** Department labels, kept in pt-BR because they appear on the dashboard. */
    private static final String[] DEPARTMENTS = {
            "Manutenção Mecânica", "Instrumentação", "Utilidades",
            "Processos", "Elétrica", "Civil", "Segurança do Trabalho"
    };

    private static final String[] MAINTENANCE_TYPES = {
            "Troca de rolamentos", "Calibração de instrumento", "Reprogramação de válvula",
            "Substituição de vedação", "Limpeza de filtro", "Reaperto de conexões",
            "Troca de sensor", "Inspeção de vibração", "Alinhamento de acoplamento",
            "Teste de estanqueidade"
    };

    private static final String[] LOCATIONS = {
            "Linha 1", "Linha 2", "Caldeira", "Compressor", "SKID-02",
            "Tanque TK-12", "Painel P-204", "Transportador TC-05", "UTI"
    };

    public TelemetrySimulatorTask(TaskScheduler scheduler,
                                  TransactionTemplate tx,
                                  StockService stockService,
                                  SupplyRepository supplyRepository,
                                  WorkOrderRepository workOrderRepository,
                                  AbcCurveService abcCurveService,
                                  @Value("${warehouse.simulation.interval-ms:5000}") long intervalMs,
                                  @Value("${warehouse.simulation.active:true}") boolean active,
                                  @Value("${warehouse.simulation.intensity:1.0}") double intensity,
                                  @Value("${warehouse.simulation.work-order-probability:0.55}") double workOrderProbability,
                                  @Value("${warehouse.simulation.replenishment-probability:0.22}") double replenishmentProbability) {
        this.scheduler = scheduler;
        this.tx = tx;
        this.stockService = stockService;
        this.supplyRepository = supplyRepository;
        this.workOrderRepository = workOrderRepository;
        this.abcCurveService = abcCurveService;
        this.intervalMs = intervalMs;
        this.active = active;
        this.intensity = intensity;
        this.workOrderProbability = workOrderProbability;
        this.replenishmentProbability = replenishmentProbability;
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        this.startedAt = Instant.now();
        log.info("TelemetrySimulatorTask started | active={} intervalMs={} intensity={}",
                active, intervalMs, intensity);
        reschedule();
    }

    @Override
    public void destroy() {
        synchronized (schedulingLock) {
            if (nextCycle != null) {
                nextCycle.cancel(false);
                nextCycle = null;
            }
        }
        log.info("TelemetrySimulatorTask stopped after {} cycle(s)", cyclesExecuted.get());
    }

    /** (Re)schedules the next cycle using the current interval. */
    private void reschedule() {
        synchronized (schedulingLock) {
            if (nextCycle != null) {
                nextCycle.cancel(false);
                nextCycle = null;
            }
            if (!active) {
                log.debug("Simulation paused; no cycle scheduled.");
                return;
            }
            long delay = Math.max(500L, intervalMs);
            nextCycle = scheduler.schedule(
                    this::executeCycle,
                    new Date(System.currentTimeMillis() + delay));
        }
    }

    // ==================================================================
    // Cycle
    // ==================================================================

    /** Runs one full telemetry cycle. Never throws. */
    public void executeCycle() {
        // A cycle already handed to the scheduler cannot be interrupted
        // mid-transaction, but it can be stopped before it does any work:
        // pausing cancels the pending timer, and this guard catches the case
        // where the pause lands in the window between firing and starting.
        if (!active) {
            log.debug("Ciclo descartado: simulacao pausada.");
            return;
        }

        long cycle = cyclesExecuted.incrementAndGet();
        try {
            int events = 0;
            if (chanceScaled(workOrderProbability)) {
                events += generateWorkOrder();
            }
            events += advanceWorkOrders();
            events += consumeRandomItems();

            if (chanceScaled(replenishmentProbability)) {
                events += replenishCriticalItems();
            }

            // Inventory value changes on every consumption, so the ABC curve
            // is recomputed periodically rather than each cycle to avoid
            // multiplying writes against the database.
            if (cycle % 10 == 0) {
                abcCurveService.calculateAbcCurve();
                log.debug("ABC curve recomputed on cycle {}", cycle);
            }

            if (events > 0) {
                log.debug("Cycle {} generated {} event(s)", cycle, events);
            }
        } catch (Exception e) {
            // A failing cycle must never take the scheduling down.
            log.error("Telemetry simulation failed on cycle {}", cycle, e);
        } finally {
            reschedule();
        }
    }

    // ------------------------------------------------------------------
    // Event generators
    // ------------------------------------------------------------------

    /**
     * Opens a work order.
     *
     * <p>The creation timestamp is backdated by a few hours. Without this, a
     * cycle opens and the next one closes the order within seconds, and the
     * "tempo médio de atendimento" collapses to minutes — which is both
     * statistically meaningless and visibly wrong to a supply manager.
     * Backdating keeps the demo moving while producing realistic lead times.</p>
     */
    private int generateWorkOrder() {
        Integer result = tx.execute(status -> {
            WorkOrder order = workOrderRepository.save(WorkOrder.builder()
                    .orderCode(nextOrderCode())
                    .requestingDepartment(random(DEPARTMENTS))
                    .status(WorkOrder.STATUS_OPEN)
                    .createdAt(LocalDateTime.now().minusMinutes(randomBackdatedAgeMinutes()))
                    .description(random(MAINTENANCE_TYPES) + " - " + random(LOCATIONS))
                    .build());
            workOrdersGenerated.incrementAndGet();
            log.info("New work order: {} ({})", order.getOrderCode(), order.getRequestingDepartment());
            return 1;
        });
        return result == null ? 0 : result;
    }

    /**
     * Age, in minutes, given to a freshly created work order.
     * Distribution follows a long tail: most repairs inside a shift, some
     * waiting on parts for days.
     */
    private long randomBackdatedAgeMinutes() {
        long roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 45) {
            return 45 + ThreadLocalRandom.current().nextInt(8 * 60);          // 45 min - 8 h
        }
        if (roll < 85) {
            return 8 * 60 + ThreadLocalRandom.current().nextInt(14 * 60);      // 8 h - 22 h
        }
        return 22 * 60 + ThreadLocalRandom.current().nextInt(3 * 24 * 60);     // 22 h - 4 days
    }

    /** Moves work orders through OPEN -> IN_PROGRESS -> COMPLETED. */
    private int advanceWorkOrders() {
        Integer result = tx.execute(status -> {
            List<WorkOrder> open = workOrderRepository.findOpen();
            if (open.isEmpty()) {
                return 0;
            }
            int transitions = 0;
            LocalDateTime now = LocalDateTime.now();

            for (WorkOrder order : open) {
                String newStatus;
                if (WorkOrder.STATUS_OPEN.equals(order.getStatus())) {
                    if (!chance(0.40)) {
                        continue;
                    }
                    newStatus = WorkOrder.STATUS_IN_PROGRESS;
                } else if (chance(0.25)) {
                    newStatus = WorkOrder.STATUS_COMPLETED;
                } else {
                    continue;
                }

                workOrderRepository.transitionStatus(
                        order.getId(), newStatus,
                        WorkOrder.STATUS_COMPLETED.equals(newStatus), now);
                transitions++;
            }
            return transitions;
        });
        return result == null ? 0 : result;
    }

    /** Withdraws items from stock, sometimes against an open work order. */
    private int consumeRandomItems() {
        Integer result = tx.execute(status -> {
            List<SupplyItem> items = supplyRepository.findAllActiveOrderedByValue();
            if (items.isEmpty()) {
                return 0;
            }
            int howMany = 1 + ThreadLocalRandom.current().nextInt(
                    (int) Math.min(4, 1 + Math.ceil(intensity)));
            AtomicInteger movements = new AtomicInteger();

            for (int i = 0; i < howMany; i++) {
                SupplyItem item = items.get(ThreadLocalRandom.current().nextInt(items.size()));
                int quantity = calculateConsumptionQuantity(item);

                WorkOrder order = workOrderRepository.findOpen().stream()
                        .findFirst()
                        .orElse(null);

                stockService.debitAvailable(item, order, quantity, StockMovement.ORIGIN_SIMULATOR)
                        .ifPresent(m -> {
                            movements.incrementAndGet();
                            movementsGenerated.incrementAndGet();
                        });
            }
            return movements.get();
        });
        return result == null ? 0 : result;
    }

    /**
     * Withdrawal size for a cycle, scaled by each item's own average demand
     * and by the configured intensity.
     */
    private int calculateConsumptionQuantity(SupplyItem item) {
        BigDecimal consumption = item.getAverageDailyConsumption() == null
                ? BigDecimal.ONE
                : item.getAverageDailyConsumption();
        double factor = ThreadLocalRandom.current().nextDouble(0.8, 3.0) * intensity;
        BigDecimal raw = consumption.multiply(BigDecimal.valueOf(factor));
        int quantity = raw.setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(1, quantity);
    }

    /** Replenishes items that fell below their minimum stock. */
    private int replenishCriticalItems() {
        int restocked = stockService.replenishCriticalItems(
                (int) Math.max(1, Math.min(3, Math.round(intensity))),
                StockMovement.ORIGIN_AUTO_REPLENISHMENT);
        if (restocked > 0) {
            replenishmentsGenerated.addAndGet(restocked);
            log.debug("Auto replenished {} item(s)", restocked);
        }
        return restocked;
    }

    // ==================================================================
    // Runtime settings
    // ==================================================================

    /**
     * Applies partial updates: omitted fields keep their current value.
     * Reschedules whenever the interval changes.
     *
     * @return the settings in effect after the update
     */
    public SimulationParametersDTO updateParameters(SimulationParametersDTO input) {
        boolean intervalChanged = false;
        if (input.active() != null && input.active() != this.active) {
            this.active = input.active();
            log.info("Telemetry simulation {}", active ? "RESUMED" : "PAUSED");
        }
        if (input.intensity() != null) {
            this.intensity = input.intensity();
            log.info("Simulation intensity set to {}", intensity);
        }
        if (input.intervalMs() != null && input.intervalMs() != this.intervalMs) {
            this.intervalMs = input.intervalMs();
            intervalChanged = true;
            log.info("Simulation interval set to {} ms", intervalMs);
        }

        // Any explicit change to the on/off switch needs a reschedule, in BOTH
        // directions. The previous condition only fired on `active == true`,
        // so pausing left the pending timer armed: the scheduled cycle still
        // ran, generated events, and only then noticed the pause in its
        // finally block.
        boolean activeToggled = input.active() != null;
        if (intervalChanged || activeToggled) {
            reschedule();
        }
        return currentParameters();
    }

    public SimulationParametersDTO currentParameters() {
        return new SimulationParametersDTO(active, (int) intervalMs, intensity);
    }

    /** Simulator counters, shown in the dashboard footer. */
    public SimulatorStatistics currentStatistics() {
        return new SimulatorStatistics(
                active,
                intervalMs,
                intensity,
                cyclesExecuted.get(),
                workOrdersGenerated.get(),
                movementsGenerated.get(),
                replenishmentsGenerated.get(),
                startedAt == null ? null : Duration.between(startedAt, Instant.now()).getSeconds());
    }

    /**
     * Simulator counters.
     *
     * @param uptimeSeconds elapsed time since the simulation started
     */
    public record SimulatorStatistics(
            boolean active,
            long intervalMs,
            double intensity,
            long cyclesExecuted,
            long workOrdersGenerated,
            long movementsGenerated,
            long replenishmentsGenerated,
            Long uptimeSeconds
    ) {
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Readable sequential code derived from the highest id in use
     * (e.g. {@code WO-2026-0011}).
     */
    private String nextOrderCode() {
        long nextId = workOrderRepository.findMaxId() + 1;
        return String.format(Locale.ROOT, "WO-%d-%04d", Year.now().getValue(), nextId);
    }

    /** Plain probability test, unaffected by the configured intensity. */
    private boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    /** Probability for the events that respond to the intensity slider. */
    private boolean chanceScaled(double baseProbability) {
        return chance(Math.min(1.0, baseProbability * intensity));
    }

    private static String random(String[] values) {
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }
}