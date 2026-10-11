package com.bank.lending.admin.web;

import com.bank.lending.admin.dto.AdminDtos.ProductRow;
import com.bank.lending.admin.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Products", description = "Loan product master (read-only; maintained through lending-service /api/v1/loan-products)")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @Operation(summary = "All loan products with default terms, limits and number of accounts")
    @GetMapping("/products")
    public List<ProductRow> products() {
        return products.products();
    }
}
