package com.bank.lending.accounting.engine;

import com.bank.lending.accounting.model.Defs.RuleDef;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import com.bank.lending.accounting.model.PostingException;
import com.bank.lending.accounting.model.PostingException.Reason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Evaluates the rules of a template against an event's amount tags and context. Pure: no I/O. */
public final class TemplateEvaluator {

    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

    private TemplateEvaluator() {
    }

    public record Evaluated(RuleDef rule, BigDecimal amount) {
    }

    /** Parsed expressions are cached by source text; configuration is small and the cache is keyed by text only. */
    static Expr parse(String text) {
        try {
            return CACHE.computeIfAbsent(text, Expr::parse);
        } catch (Expr.ExprException e) {
            throw new PostingException(Reason.EXPRESSION_ERROR, e.getMessage(), e);
        }
    }

    /**
     * @param declaredTags the amount tags the event permits; a declared tag the caller did not supply is zero
     * @param context      non-numeric inputs such as {@code npa_stage}, {@code product_code}, {@code is_backdated}
     * @param precision    decimal places of the transaction currency; rule amounts are rounded to it
     * @return the lines to post, in {@code line_seq} order, with zero-amount lines left out
     */
    public static List<Evaluated> evaluate(TemplateDef template, Map<String, BigDecimal> tags, Set<String> declaredTags,
                                           Map<String, Object> context, int precision) {
        Expr.Scope scope = name -> {
            if (declaredTags.contains(name)) {
                return tags.getOrDefault(name, BigDecimal.ZERO);
            }
            return context.get(name);
        };
        List<Evaluated> out = new ArrayList<>();
        List<RuleDef> rules = new ArrayList<>(template.rules());
        rules.sort(Comparator.comparingInt(RuleDef::lineSeq));
        for (RuleDef rule : rules) {
            try {
                if (rule.condition() != null && !rule.condition().isBlank()
                        && !parse(rule.condition()).evalBoolean(scope)) {
                    continue;
                }
                BigDecimal amount = parse(rule.amountSource()).evalNumber(scope).setScale(precision, RoundingMode.HALF_UP);
                if (amount.signum() < 0) {
                    throw new PostingException(Reason.NEGATIVE_AMOUNT, "Rule " + rule.lineSeq() + " of template "
                            + template.templateId() + " produced a negative amount " + amount);
                }
                if (amount.signum() > 0) {
                    out.add(new Evaluated(rule, amount));
                }
            } catch (Expr.ExprException e) {
                throw new PostingException(Reason.EXPRESSION_ERROR, "Rule " + rule.lineSeq() + " of template "
                        + template.templateId() + ": " + e.getMessage(), e);
            }
        }
        return out;
    }
}
