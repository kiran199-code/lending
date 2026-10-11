package com.bank.lending.product.repository;

import com.bank.lending.product.domain.LoanProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    Optional<LoanProduct> findByProductCode(String productCode);

    boolean existsByProductCode(String productCode);

    List<LoanProduct> findByStatusOrderByProductCode(LoanProduct.Status status);

    List<LoanProduct> findAllByOrderByProductCode();
}
