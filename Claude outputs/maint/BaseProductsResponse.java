package com.bank.lending.maintenance.dto;

import java.util.List;

/** Outcome of creating the base product set: which products were created and which already existed. */
public record BaseProductsResponse(
        List<String> created,
        List<String> alreadyExisted,
        String message
) {
}
