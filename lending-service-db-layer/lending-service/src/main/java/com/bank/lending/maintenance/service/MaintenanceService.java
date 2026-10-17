package com.bank.lending.maintenance.service;

import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.FeatureDisabledException;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.maintenance.dto.BaseProductsResponse;
import com.bank.lending.maintenance.dto.ClearDataResponse;
import com.bank.lending.maintenance.dto.MaintenanceStatusResponse;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.LoanProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Start-from-scratch operations for non-production environments.
 *
 * <p>{@link #clearAllData} empties the accounts, their schedules, accruals, payments, mortgages, the
 * ledger outbox, the idempotency log and the loan product master. It leaves the business date, the
 * holiday calendar, the benchmark rates and the batch service's own tables alone. It is refused unless
 * {@code lending.maintenance.reset-enabled} is true, the caller types the confirmation word, and end of
 * day is not running. {@link #createBaseProducts} (re)creates the standard product set and never changes
 * a product that already exists.
 */
@Slf4j
@Service
public class MaintenanceService {

    public static final String CONFIRMATION = "RESET";

    /** Every table the clear empties, in the order they are reported. */
    static final List<String> TABLES = List.of(
            "loan_account", "amortization_schedule", "interest_accrual", "emi_transaction",
            "loan_demand", "payment_allocation", "penal_interest",
            "mortgage", "mortgage_valuation_history", "ledger_outbox", "processed_request", "loan_product");

    private final JdbcTemplate jdbc;
    private final BusinessDateService businessDate;
    private final LoanProductService productService;
    private final LoanProductRepository productRepository;
    private final boolean resetEnabled;

    public MaintenanceService(JdbcTemplate jdbc, BusinessDateService businessDate,
                              LoanProductService productService, LoanProductRepository productRepository,
                              @Value("${lending.maintenance.reset-enabled:false}") boolean resetEnabled) {
        this.jdbc = jdbc;
        this.businessDate = businessDate;
        this.productService = productService;
        this.productRepository = productRepository;
        this.resetEnabled = resetEnabled;
    }

    @Transactional(readOnly = true)
    public MaintenanceStatusResponse status() {
        List<MaintenanceStatusResponse.BaseProductStatus> base = BaseProducts.all().stream()
                .map(r -> new MaintenanceStatusResponse.BaseProductStatus(
                        r.productCode(), r.productName(), productRepository.existsByProductCode(r.productCode())))
                .toList();
        return new MaintenanceStatusResponse(resetEnabled, businessDate.state().status(), rowCounts(), base);
    }

    @Transactional
    public ClearDataResponse clearAllData(String confirm, String actor) {
        if (!resetEnabled) {
            throw new FeatureDisabledException(
                    "Clearing data is switched off on this lending-service. Set LENDING_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (!CONFIRMATION.equals(confirm)) {
            throw new IllegalArgumentException("Confirmation missing: send {\"confirm\": \"" + CONFIRMATION + "\"}");
        }
        if (businessDate.state().status() == BusinessDateStatus.EOD_RUNNING) {
            throw new InvalidAccountStateException(
                    "End of day is running; wait for it to finish (or fail) before clearing data");
        }
        Map<String, Long> before = rowCounts();
        // One statement naming every referencing table, so no CASCADE is needed. RESTART IDENTITY puts the
        // product and account ids back to 1.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY");
        long total = before.values().stream().mapToLong(Long::longValue).sum();
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        log.warn("DATA CLEARED by {}: {} rows removed {}", who, total, before);
        return new ClearDataResponse(before, total, who, OffsetDateTime.now(),
                "All accounts and products were removed. Create the base products to start again.");
    }

    public BaseProductsResponse createBaseProducts() {
        List<String> created = new ArrayList<>();
        List<String> existed = new ArrayList<>();
        for (LoanProductRequest request : BaseProducts.all()) {
            if (productRepository.existsByProductCode(request.productCode())) {
                existed.add(request.productCode());
            } else {
                productService.create(request);
                created.add(request.productCode());
            }
        }
        String message = created.isEmpty()
                ? "All base products already exist; nothing was changed."
                : "Created " + created.size() + " base product" + (created.size() == 1 ? "" : "s")
                + (existed.isEmpty() ? "." : "; " + existed.size() + " already existed and were left as they are.");
        return new BaseProductsResponse(created, existed, message);
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }
}
