package com.fabrica.controltower.service;

import com.fabrica.controltower.dto.StockoutAlertDTO;
import com.fabrica.controltower.entity.SupplyItem;
import com.fabrica.controltower.entity.StockMovement;
import com.fabrica.controltower.entity.WorkOrder;
import com.fabrica.controltower.repository.SupplyRepository;
import com.fabrica.controltower.repository.StockMovementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stock rules: reorder point, safety stock, severity and movement recording.
 *
 * <h2>Formulas</h2>
 * <pre>
 *   SafetyStock  = averageDailyConsumption x replenishmentLeadTimeDays x safetyFactor
 *   ReorderPoint = (averageDailyConsumption x replenishmentLeadTimeDays) + SafetyStock
 *   DaysCoverage = currentQuantity / averageDailyConsumption
 * </pre>
 *
 * <p>Alert {@code message} strings are produced in Portuguese on purpose:
 * they are rendered directly on the dashboard presented to the floor team.</p>
 */
@Slf4j
@Service
public class StockService {

    private final SupplyRepository supplyRepository;
    private final StockMovementRepository movementRepository;

    /** Safety factor applied on top of lead-time demand. */
    private final double safetyFactor;

    /** Reorder-point multiplier that raises the WARNING level. */
    private final double warningMultiplier;

    public StockService(SupplyRepository supplyRepository,
                        StockMovementRepository movementRepository,
                        @Value("${warehouse.stock.safety-factor:0.50}") double safetyFactor,
                        @Value("${warehouse.stock.warning-multiplier:1.25}") double warningMultiplier) {
        this.supplyRepository = supplyRepository;
        this.movementRepository = movementRepository;
        this.safetyFactor = safetyFactor;
        this.warningMultiplier = warningMultiplier;
    }

    /** Stock severity level, most to least severe. */
    public enum Severity {
        STOCKOUT(0),
        CRITICAL(1),
        WARNING(2),
        HEALTHY(3);

        private final int rank;

        Severity(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }
    }

    // ------------------------------------------------------------------
    // Calculations
    // ------------------------------------------------------------------

    /** Average daily consumption, null-safe. */
    public BigDecimal dailyConsumption(SupplyItem item) {
        return item.getAverageDailyConsumption() == null
                ? BigDecimal.ZERO
                : item.getAverageDailyConsumption();
    }

    /** Lead time in days, null-safe. */
    public int leadTime(SupplyItem item) {
        return item.getReplenishmentLeadTimeDays() == null ? 0 : item.getReplenishmentLeadTimeDays();
    }

    /** Safety stock: the buffer covering demand during the lead time. */
    public BigDecimal calculateSafetyStock(SupplyItem item) {
        return dailyConsumption(item)
                .multiply(BigDecimal.valueOf(leadTime(item)))
                .multiply(BigDecimal.valueOf(safetyFactor))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Reorder Point: when to place the next purchase.
     *
     * <p>It is the demand over the lead time plus the safety stock — the level
     * at which stock must be replenished so the item cannot run out before the
     * supplier delivers.</p>
     */
    public BigDecimal calculateReorderPoint(SupplyItem item) {
        BigDecimal leadTimeDemand = dailyConsumption(item).multiply(BigDecimal.valueOf(leadTime(item)));
        return leadTimeDemand
                .add(calculateSafetyStock(item))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Classifies an item by its balance relative to the reorder point. */
    public Severity classify(SupplyItem item) {
        int balance = item.getCurrentQuantity() == null ? 0 : item.getCurrentQuantity();
        if (balance <= 0) {
            return Severity.STOCKOUT;
        }
        BigDecimal reorderPoint = calculateReorderPoint(item);
        if (BigDecimal.valueOf(balance).compareTo(reorderPoint) < 0) {
            return Severity.CRITICAL;
        }
        BigDecimal warningThreshold = reorderPoint.multiply(BigDecimal.valueOf(warningMultiplier));
        if (BigDecimal.valueOf(balance).compareTo(warningThreshold) < 0) {
            return Severity.WARNING;
        }
        return Severity.HEALTHY;
    }

    /** Reorder-point multiplier equivalent to 1 + safety factor. */
    public BigDecimal reorderPointMultiplier() {
        return BigDecimal.valueOf(1.0 + safetyFactor).setScale(4, RoundingMode.HALF_UP);
    }

    public double getWarningMultiplier() {
        return warningMultiplier;
    }

    // ------------------------------------------------------------------
    // Alerts
    // ------------------------------------------------------------------

    /**
     * Stockout alerts, most critical first.
     *
     * <p>Sorted by severity and, within a level, by coverage in days — the item
     * that runs out first heads the queue.</p>
     */
    @Transactional(readOnly = true)
    public List<StockoutAlertDTO> listStockoutAlerts() {
        List<SupplyItem> candidates = supplyRepository.findItemsAtRiskOfStockout(
                reorderPointMultiplier().multiply(BigDecimal.valueOf(warningMultiplier)),
                BigDecimal.ZERO);

        return candidates.stream()
                .filter(i -> classify(i) != Severity.HEALTHY)
                .map(this::buildAlert)
                .sorted(Comparator
                        .comparingInt((StockoutAlertDTO a) ->
                                Severity.valueOf(a.severity()).getRank())
                        .thenComparing(StockoutAlertDTO::daysUntilStockout,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Alert for a single item, with values recalculated right now. */
    @Transactional(readOnly = true)
    public Optional<StockoutAlertDTO> findAlert(Long supplyItemId) {
        return supplyRepository.findById(supplyItemId).map(this::buildAlert);
    }

    /** Builds the alert for an item using the values already calculated. */
    public StockoutAlertDTO buildAlert(SupplyItem item) {
        BigDecimal reorderPoint = calculateReorderPoint(item);
        BigDecimal safety = calculateSafetyStock(item);
        BigDecimal coverage = item.getDaysOfCoverage();
        Severity severity = classify(item);

        int balance = item.getCurrentQuantity() == null ? 0 : item.getCurrentQuantity();
        int minimumStock = item.getMinimumStock() == null ? 0 : item.getMinimumStock();
        int toRequest = Math.max(0, reorderPoint.setScale(0, RoundingMode.CEILING).intValueExact() - balance);

        return new StockoutAlertDTO(
                item.getId(),
                item.getCode(),
                item.getName(),
                balance,
                minimumStock,
                reorderPoint,
                safety,
                dailyConsumption(item),
                leadTime(item),
                coverage,
                toRequest,
                item.getInventoryValue(),
                severity.name(),
                buildMessage(item, severity, balance, reorderPoint, coverage));
    }

    /**
     * Builds the operator-facing sentence for an alert, in pt-BR.
     * Presentation text, deliberately not part of the English domain model.
     */
    private String buildMessage(SupplyItem item, Severity severity, int balance,
                                BigDecimal reorderPoint, BigDecimal coverage) {
        String code = item.getCode();
        return switch (severity) {
            case STOCKOUT -> String.format(Locale.of("pt-BR"),
                    "%s está com estoque zerado.", code);
            case CRITICAL -> coverage != null
                    ? String.format(Locale.of("pt-BR"),
                    "%s abaixo do ponto de reposição: restam %s dia(s) de cobertura.", code, coverage)
                    : String.format(Locale.of("pt-BR"),
                    "%s abaixo do ponto de reposição (%s un).", code, reorderPoint.stripTrailingZeros().toPlainString());
            case WARNING -> String.format(Locale.of("pt-BR"),
                    "%s a caminho da ruptura: %s un acima do ponto de reposição.", code, balance);
            default -> String.format(Locale.of("pt-BR"), "%s está saudável.", code);
        };
    }

    // ------------------------------------------------------------------
    // Movements
    // ------------------------------------------------------------------

    /**
     * Debits stock exactly and records the movement.
     *
     * @return the recorded movement, or {@link Optional#empty()} when the
     *         requested quantity was not available
     */
    @Transactional
    public Optional<StockMovement> debit(SupplyItem item, WorkOrder workOrder,
                                         int quantity, String origin) {
        if (quantity <= 0) {
            return Optional.empty();
        }
        if (supplyRepository.debitStock(item.getId(), quantity) == 0) {
            log.debug("Debit rejected for insufficient stock: {} x{}", item.getCode(), quantity);
            return Optional.empty();
        }
        return Optional.of(record(item.getId(), workOrder, StockMovement.TYPE_OUTBOUND,
                quantity, origin));
    }

    /**
     * Debits whatever is available: used by the telemetry simulator to model
     * real consumption, zeroing the balance when the withdrawal empties stock.
     *
     * @return the recorded movement, or empty when the item was already zeroed
     */
    @Transactional
    public Optional<StockMovement> debitAvailable(SupplyItem item, WorkOrder workOrder,
                                                 int quantity, String origin) {
        // Re-read: the passed object may carry a stale balance if another
        // simulation cycle already moved the same item.
        SupplyItem current = supplyRepository.findById(item.getId())
                .orElseThrow(() -> new IllegalStateException("Supply item " + item.getId() + " not found"));
        int balance = current.getCurrentQuantity() == null ? 0 : current.getCurrentQuantity();
        int effective = Math.min(balance, quantity);
        if (effective <= 0) {
            return Optional.empty();
        }
        return debit(current, workOrder, effective, origin);
    }

    /** Credits stock (replenishment receipt) and records the inbound entry. */
    @Transactional
    public Optional<StockMovement> credit(SupplyItem item, int quantity, String origin) {
        if (quantity <= 0) {
            return Optional.empty();
        }
        supplyRepository.creditStock(item.getId(), quantity);
        return Optional.of(record(item.getId(), null, StockMovement.TYPE_INBOUND,
                quantity, origin));
    }

    private StockMovement record(Long supplyItemId, WorkOrder workOrder, String type,
                                 int quantity, String origin) {
        // Debit/credit queries clear the persistence context, so re-reading
        // returns the balance actually committed to the database.
        SupplyItem currentBalance = supplyRepository.findById(supplyItemId)
                .orElseThrow(() -> new IllegalStateException("Supply item " + supplyItemId + " not found"));

        return movementRepository.save(StockMovement.builder()
                .supplyItem(currentBalance)
                .workOrder(workOrder)
                .movementType(type)
                .quantity(quantity)
                .occurredAt(LocalDateTime.now())
                .resultingBalance(currentBalance.getCurrentQuantity())
                .origin(origin)
                .build());
    }

    /**
     * Automatically replenishes items that fell below their minimum stock.
     *
     * @param maxItems cap on how many items to replenish per call
     * @return how many items were restocked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int replenishCriticalItems(int maxItems, String origin) {
        List<SupplyItem> critical = supplyRepository.findAllActiveOrderedByValue().stream()
                .filter(i -> {
                    int balance = i.getCurrentQuantity() == null ? 0 : i.getCurrentQuantity();
                    int minimum = i.getMinimumStock() == null ? 0 : i.getMinimumStock();
                    return balance < minimum;
                })
                .limit(maxItems)
                .toList();

        for (SupplyItem item : critical) {
            int minimum = item.getMinimumStock();
            int balance = item.getCurrentQuantity();
            // Restock up to 1.5x the minimum, widened by the lead time
            int target = Math.max(minimum + 1,
                    (int) Math.ceil(minimum * (1.5 + leadTime(item) / 20.0)));
            int quantity = Math.max(1, target - balance);
            credit(item, quantity, origin);
            log.debug("Auto replenishment: {} +{} (balance {} -> target {})",
                    item.getCode(), quantity, balance, target);
        }
        return critical.size();
    }
}