package com.bank.lending.statement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-accounts")
@RequiredArgsConstructor
@Tag(name = "Loan Statement",
        description = "Customer-facing, downloadable loan statement: account details, repayment schedule and payments received")
public class LoanStatementController {

    private final LoanStatementService statementService;

    @Operation(summary = "Download the loan statement (PDF) by account ID",
            description = "A customer-ready PDF with the account details, key figures, the full repayment schedule "
                    + "(paid, upcoming and overdue) and every payment received. Internal identifiers and internal "
                    + "status codes are not shown. In Swagger, use the Download file link under the response.")
    @ApiResponse(responseCode = "200", description = "The statement as a PDF file",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/{accountId}/statement", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statementById(@PathVariable Long accountId) {
        return asDownload(statementService.pdfByAccountId(accountId));
    }

    @Operation(summary = "Download the loan statement (PDF) by account number",
            description = "Same statement as above, looked up by the account number the customer knows, "
                    + "for example PL01261010220407.")
    @ApiResponse(responseCode = "200", description = "The statement as a PDF file",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/by-account-number/{accountNumber}/statement", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statementByAccountNumber(@PathVariable String accountNumber) {
        return asDownload(statementService.pdfByAccountNumber(accountNumber));
    }

    private static ResponseEntity<byte[]> asDownload(GeneratedStatement statement) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(statement.fileName()).build().toString())
                // Personal financial data: never let a browser or proxy keep a copy.
                .cacheControl(CacheControl.noStore())
                .contentLength(statement.content().length)
                .body(statement.content());
    }
}
