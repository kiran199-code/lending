package com.bank.lending.payment.web;

import com.bank.lending.payment.dto.EmiPaymentRequest;
import com.bank.lending.payment.dto.EmiTransactionResponse;
import com.bank.lending.payment.dto.ForeclosureRequest;
import com.bank.lending.payment.dto.PartPaymentRequest;
import com.bank.lending.payment.service.EmiPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loan-accounts/{accountId}/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "EMI posting, part-payments, and foreclosure payoff")
public class EmiPaymentController {

    private final EmiPaymentService emiPaymentService;

    @Operation(summary = "Post a scheduled EMI payment (amount must match the exact EMI due)")
    @PostMapping("/emi")
    public EmiTransactionResponse postEmi(
            @PathVariable Long accountId,
            @Valid @RequestBody EmiPaymentRequest request,
            @Parameter(description = "Client-generated key guaranteeing this exact request is only ever applied once")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return EmiTransactionResponse.from(emiPaymentService.postEmiPayment(
                accountId, request.valueDate(), request.amount(), request.channel(), idempotencyKey));
    }

    @Operation(summary = "Post an ad-hoc principal part-payment; regenerates the remaining schedule")
    @PostMapping("/part-payment")
    public EmiTransactionResponse postPartPayment(
            @PathVariable Long accountId,
            @Valid @RequestBody PartPaymentRequest request,
            @Parameter(description = "Client-generated key guaranteeing this exact request is only ever applied once")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return EmiTransactionResponse.from(emiPaymentService.postPartPayment(
                accountId, request.valueDate(), request.amount(), request.channel(), idempotencyKey));
    }

    @Operation(summary = "Foreclose the account: pay off outstanding principal plus accrued interest and close it")
    @PostMapping("/foreclosure")
    public EmiTransactionResponse foreclose(
            @PathVariable Long accountId,
            @Valid @RequestBody ForeclosureRequest request,
            @Parameter(description = "Client-generated key guaranteeing this exact request is only ever applied once")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return EmiTransactionResponse.from(emiPaymentService.foreclose(
                accountId, request.foreclosureDate(), request.channel(), idempotencyKey));
    }

    @Operation(summary = "List payment/foreclosure transaction history for an account, most recent first")
    @GetMapping
    public List<EmiTransactionResponse> history(@PathVariable Long accountId) {
        return emiPaymentService.getTransactionHistory(accountId).stream()
                .map(EmiTransactionResponse::from)
                .toList();
    }
}
