package com.bank.lending.admin.web;

import com.bank.lending.admin.dto.AdminDtos.ProductInput;
import com.bank.lending.admin.dto.AdminDtos.ProductsResponse;
import com.bank.lending.admin.repository.ProductRepository;
import com.bank.lending.admin.service.LendingProductClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Products", description = "Loan product master: view, create and edit (saved through lending-service)")
public class ProductController {

    private final ProductRepository products;
    private final LendingProductClient client;

    public ProductController(ProductRepository products, LendingProductClient client) {
        this.products = products;
        this.client = client;
    }

    @Operation(summary = "All loan products with default terms, limits and number of accounts")
    @GetMapping("/products")
    public ProductsResponse products() {
        return new ProductsResponse(products.products(), client.enabled(),
                client.enabled() ? null : "View only: set LENDING_SERVICE_URL on the admin service to allow editing.");
    }

    @Operation(summary = "Create a product (validated and saved by lending-service)")
    @PostMapping("/products")
    public Map<String, String> create(@RequestBody ProductInput input) {
        client.create(input);
        return Map.of("message", "Product created. It can be used for accounts opened from now on.");
    }

    @Operation(summary = "Replace a product's parameters (validated and saved by lending-service)",
            description = "Applies to accounts opened afterwards and to a floating rate reset of an individual account. "
                    + "Existing accounts are not changed. expectedVersion guards against concurrent edits (409).")
    @PutMapping("/products/{productCode}")
    public Map<String, String> update(@PathVariable String productCode, @RequestBody ProductInput input) {
        client.update(productCode, input);
        return Map.of("message", "Product saved. Existing accounts keep their own terms; new accounts use the new parameters.");
    }
}
