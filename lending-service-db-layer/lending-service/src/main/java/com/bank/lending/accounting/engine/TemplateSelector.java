package com.bank.lending.accounting.engine;

import com.bank.lending.accounting.model.Defs.TemplateDef;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Picks the template for an event: the product-specific one effective on the date, else the default one. */
public final class TemplateSelector {

    private TemplateSelector() {
    }

    public static Optional<TemplateDef> select(List<TemplateDef> templates, String productCode, LocalDate date) {
        if (templates == null) {
            return Optional.empty();
        }
        Optional<TemplateDef> specific = productCode == null ? Optional.empty() : templates.stream()
                .filter(t -> productCode.equals(t.productCode()) && t.effectiveOn(date))
                .max(Comparator.comparingInt(TemplateDef::version));
        if (specific.isPresent()) {
            return specific;
        }
        return templates.stream()
                .filter(t -> t.productCode() == null && t.effectiveOn(date))
                .max(Comparator.comparingInt(TemplateDef::version));
    }
}
