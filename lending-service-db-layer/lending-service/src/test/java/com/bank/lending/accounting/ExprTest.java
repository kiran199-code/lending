package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.Expr;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExprTest {

    private static Expr.Scope scope(Map<String, Object> m) {
        return m::get;
    }

    private static BigDecimal num(String text, Map<String, Object> vars) {
        return Expr.parse(text).evalNumber(scope(vars));
    }

    @Test
    void arithmeticFollowsPrecedence() {
        assertThat(num("2 + 3 * 4", Map.of())).isEqualByComparingTo("14");
        assertThat(num("(2 + 3) * 4", Map.of())).isEqualByComparingTo("20");
        assertThat(num("10 - 4 - 3", Map.of())).isEqualByComparingTo("3");
        assertThat(num("-5 + 8", Map.of())).isEqualByComparingTo("3");
        assertThat(num("10 / 4", Map.of())).isEqualByComparingTo("2.5");
    }

    @Test
    void identifiersAndFunctions() {
        Map<String, Object> v = Map.of("PAYMENT_AMT", new BigDecimal("100.00"), "PENAL_PAID", new BigDecimal("30.00"),
                "INTEREST_PAID", new BigDecimal("80.00"));
        assertThat(num("max(PAYMENT_AMT - PENAL_PAID - INTEREST_PAID, 0)", v)).isEqualByComparingTo("0");
        assertThat(num("min(PAYMENT_AMT, PENAL_PAID)", v)).isEqualByComparingTo("30");
        assertThat(num("abs(PENAL_PAID - PAYMENT_AMT)", v)).isEqualByComparingTo("70");
        assertThat(num("MAX(1, 2, 3)", v)).isEqualByComparingTo("3");
    }

    @Test
    void conditionsOverStringsNumbersAndBooleans() {
        Map<String, Object> v = Map.of("npa_stage", "NPA_SUBSTANDARD", "is_backdated", true, "X", new BigDecimal("5"));
        assertThat(Expr.parse("npa_stage IN ('NPA_SUBSTANDARD','NPA_LOSS')").evalBoolean(scope(v))).isTrue();
        assertThat(Expr.parse("npa_stage NOT IN ('ACTIVE')").evalBoolean(scope(v))).isTrue();
        assertThat(Expr.parse("npa_stage = 'ACTIVE'").evalBoolean(scope(v))).isFalse();
        assertThat(Expr.parse("is_backdated AND X > 3").evalBoolean(scope(v))).isTrue();
        assertThat(Expr.parse("NOT is_backdated OR X >= 5").evalBoolean(scope(v))).isTrue();
        assertThat(Expr.parse("X <> 5").evalBoolean(scope(v))).isFalse();
        assertThat(Expr.parse("X == 5.00").evalBoolean(scope(v))).isTrue();
    }

    @Test
    void unknownIdentifierIsNullSoComparisonsAreFalseButNotErrors() {
        assertThat(Expr.parse("missing = 'A'").evalBoolean(scope(Map.of()))).isFalse();
        assertThat(Expr.parse("missing > 1").evalBoolean(scope(Map.of()))).isFalse();
        assertThat(Expr.parse("missing IN ('A')").evalBoolean(scope(Map.of()))).isFalse();
    }

    @Test
    void shortCircuitDoesNotEvaluateTheRightSide() {
        assertThat(Expr.parse("FALSE AND (1 / 0 > 1)").evalBoolean(scope(Map.of()))).isFalse();
        assertThat(Expr.parse("TRUE OR (1 / 0 > 1)").evalBoolean(scope(Map.of()))).isTrue();
    }

    @Test
    void identifiersListedInOrderOfAppearanceWithoutFunctionNames() {
        Expr e = Expr.parse("max(A - B, 0) + C * A");
        assertThat(e.identifiers().stream().toList()).containsExactly("A", "B", "C");
    }

    @Test
    void rejectsSyntaxErrorsAndUnknownFunctions() {
        assertThatThrownBy(() -> Expr.parse("1 +")).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("(1 + 2")).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("1 2")).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("round(1)")).isInstanceOf(Expr.ExprException.class)
                .hasMessageContaining("Unknown function");
        assertThatThrownBy(() -> Expr.parse("'abc")).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("  ")).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("1; DROP TABLE x")).isInstanceOf(Expr.ExprException.class);
    }

    @Test
    void rejectsTypeErrorsAtEvaluation() {
        assertThatThrownBy(() -> num("'a' + 1", Map.of())).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> Expr.parse("1 + 2").evalBoolean(scope(Map.of()))).isInstanceOf(Expr.ExprException.class);
        assertThatThrownBy(() -> num("1 / 0", Map.of())).isInstanceOf(Expr.ExprException.class)
                .hasMessageContaining("Division by zero");
        assertThatThrownBy(() -> num("A + 1", Map.of())).isInstanceOf(Expr.ExprException.class);
    }
}
