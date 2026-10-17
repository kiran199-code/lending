package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.TemplateSelector;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSelectorTest {

    private static final LocalDate OLD = LocalDate.of(2000, 1, 1);

    private static TemplateDef t(long id, String product, int version, LocalDate from, LocalDate to) {
        return new TemplateDef(id, "FORECLOSURE", product, version, from, to, List.of());
    }

    @Test
    void productSpecificBeatsDefaultAndNewestVersionInForceWins() {
        List<TemplateDef> all = List.of(
                t(1, null, 1, OLD, null),
                t(2, "HL", 1, OLD, LocalDate.of(2026, 9, 30)),
                t(3, "HL", 2, LocalDate.of(2026, 10, 1), null));
        assertThat(TemplateSelector.select(all, "HL", LocalDate.of(2026, 9, 15)).orElseThrow().templateId()).isEqualTo(2L);
        assertThat(TemplateSelector.select(all, "HL", LocalDate.of(2026, 10, 15)).orElseThrow().templateId()).isEqualTo(3L);
        assertThat(TemplateSelector.select(all, "PL", LocalDate.of(2026, 10, 15)).orElseThrow().templateId()).isEqualTo(1L);
    }

    @Test
    void nothingEffectiveGivesEmpty() {
        List<TemplateDef> all = List.of(t(1, null, 1, LocalDate.of(2027, 1, 1), null));
        assertThat(TemplateSelector.select(all, "HL", LocalDate.of(2026, 10, 15)).isPresent()).isFalse();
        assertThat(TemplateSelector.select(null, "HL", LocalDate.of(2026, 10, 15)).isPresent()).isFalse();
    }
}
