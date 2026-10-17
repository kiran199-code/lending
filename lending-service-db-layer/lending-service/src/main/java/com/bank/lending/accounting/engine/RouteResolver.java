package com.bank.lending.accounting.engine;

import com.bank.lending.accounting.model.Defs.RouteDef;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves an accounting role to a GL account for a ledger. A route matches when each of its optional dimensions
 * (branch, product, NPA stage, currency) is either empty or equal to the request. The most specific route wins; ties
 * go to the higher priority, then to the later effective-from date.
 *
 * <p>A route that names a branch outranks every route that does not (so a branch can have its own GL accounts), then
 * product outranks stage, which outranks currency: branch+product+stage+currency, ..., product+stage+currency,
 * product+stage, product+currency, product, stage+currency, stage, currency, generic.
 */
public final class RouteResolver {

    private RouteResolver() {
    }

    public static Optional<RouteDef> resolve(List<RouteDef> routes, int ledgerId, String role, String product,
                                             String stage, String currency, LocalDate date) {
        return resolve(routes, ledgerId, role, product, stage, currency, null, date);
    }

    public static Optional<RouteDef> resolve(List<RouteDef> routes, int ledgerId, String role, String product,
                                             String stage, String currency, String branch, LocalDate date) {
        return routes.stream()
                .filter(r -> r.ledgerId() == ledgerId && r.roleCode().equals(role) && r.effectiveOn(date))
                .filter(r -> r.productCode() == null || r.productCode().equals(product))
                .filter(r -> r.npaStage() == null || r.npaStage().equals(stage))
                .filter(r -> r.currency() == null || r.currency().equals(currency))
                .filter(r -> r.branchCode() == null || r.branchCode().equals(branch))
                .max(Comparator.comparingInt(RouteResolver::specificity)
                        .thenComparingInt(RouteDef::priority)
                        .thenComparing(r -> r.effectiveFrom() == null ? LocalDate.MIN : r.effectiveFrom()));
    }

    /** Branch outranks product, which outranks stage, which outranks currency (8, 4, 2, 1): the order in the class comment. */
    static int specificity(RouteDef r) {
        return (r.branchCode() != null ? 8 : 0) + (r.productCode() != null ? 4 : 0) + (r.npaStage() != null ? 2 : 0) + (r.currency() != null ? 1 : 0);
    }
}
