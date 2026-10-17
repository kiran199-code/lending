package com.bank.lending.refdata;

import com.bank.lending.accounting.model.ConfigConflictException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.refdata.ReferenceDtos.BranchRequest;
import com.bank.lending.refdata.ReferenceDtos.BranchView;
import com.bank.lending.refdata.ReferenceDtos.CurrencyRequest;
import com.bank.lending.refdata.ReferenceDtos.CurrencyView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: which currency and branch a new account gets, and what may be added. */
class ReferenceDataServiceTest {

    private final ReferenceDataRepository repo = Mockito.mock(ReferenceDataRepository.class);
    private final ReferenceDataService service = new ReferenceDataService(repo, "INR", "ONLINE", false);

    private void currency(String code, boolean active) {
        when(repo.currency(code, "INR")).thenReturn(Optional.of(new CurrencyView(code, code + " name", 2, active, "INR".equals(code))));
    }

    private void branch(String code, boolean active) {
        when(repo.branch(code, "ONLINE")).thenReturn(Optional.of(new BranchView(code, code + " branch", active, "ONLINE".equals(code))));
    }

    @Test
    void anAccountWithNoCurrencyOrBranchGetsTheDefaults() {
        currency("INR", true);
        branch("ONLINE", true);
        assertThat(service.resolveCurrency(null)).isEqualTo("INR");
        assertThat(service.resolveCurrency("  ")).isEqualTo("INR");
        assertThat(service.resolveBranch(null)).isEqualTo("ONLINE");
    }

    @Test
    void codesAreTrimmedAndUpperCased() {
        currency("INR", true);
        branch("MUM01", true);
        assertThat(service.resolveCurrency(" inr ")).isEqualTo("INR");
        assertThat(service.resolveBranch("mum01")).isEqualTo("MUM01");
    }

    @Test
    void anUnknownOrInactiveCurrencyOrBranchIsRefused() {
        when(repo.currency("XYZ", "INR")).thenReturn(Optional.empty());
        currency("USD", false);
        when(repo.branch("NOPE", "ONLINE")).thenReturn(Optional.empty());
        branch("CLOSED", false);
        assertThatThrownBy(() -> service.resolveCurrency("XYZ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown currency XYZ");
        assertThatThrownBy(() -> service.resolveCurrency("USD")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not active");
        assertThatThrownBy(() -> service.resolveBranch("NOPE")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown branch NOPE");
        assertThatThrownBy(() -> service.resolveBranch("CLOSED")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not active");
    }

    @Test
    void aNewCurrencyIsCreatedInactiveAndCannotBeActivatedWhileSingleCurrency() {
        when(repo.currency("USD", "INR")).thenReturn(Optional.empty()).thenReturn(Optional.of(new CurrencyView("USD", "US Dollar", 2, false, false)));
        CurrencyView v = service.addCurrency(new CurrencyRequest("usd", "US Dollar", null, null));
        assertThat(v.active()).isFalse();
        verify(repo).insertCurrency("USD", "US Dollar", 2, false);

        assertThatThrownBy(() -> service.addCurrency(new CurrencyRequest("EUR", "Euro", 2, true)))
                .isInstanceOf(ConfigConflictException.class).hasMessageContaining("multi-currency");
        currency("USD", false);
        assertThatThrownBy(() -> service.setCurrencyActive("USD", true)).isInstanceOf(ConfigConflictException.class);
    }

    @Test
    void withForeignCurrencyAccountingEnabledACurrencyCanBeActivated() {
        ReferenceDataService multi = new ReferenceDataService(repo, "INR", "ONLINE", true);
        currency("USD", false);
        multi.setCurrencyActive("USD", true);
        verify(repo).updateCurrency("USD", null, null, true);
    }

    @Test
    void theBaseCurrencyAndTheDefaultBranchCannotBeSwitchedOff() {
        currency("INR", true);
        branch("ONLINE", true);
        assertThatThrownBy(() -> service.setCurrencyActive("INR", false)).isInstanceOf(ConfigConflictException.class).hasMessageContaining("base currency");
        assertThatThrownBy(() -> service.updateBranch("ONLINE", null, false)).isInstanceOf(ConfigConflictException.class).hasMessageContaining("default branch");
        verify(repo, never()).updateCurrency(anyString(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        verify(repo, never()).updateBranch(anyString(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    void addingAnExistingOrMalformedCodeIsRefused() {
        branch("ONLINE", true);
        assertThatThrownBy(() -> service.addBranch(new BranchRequest("online", "Online again", true))).isInstanceOf(ConfigConflictException.class);
        assertThatThrownBy(() -> service.addBranch(new BranchRequest("BAD CODE", "x", true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCurrency(new CurrencyRequest("US", "x", 2, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addCurrency(new CurrencyRequest("USD", "x", 9, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateBranch("GONE", "x", true)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aNewBranchIsActiveUnlessSaidOtherwise() {
        when(repo.branch("MUM01", "ONLINE")).thenReturn(Optional.empty()).thenReturn(Optional.of(new BranchView("MUM01", "Mumbai", true, false)));
        BranchView b = service.addBranch(new BranchRequest("mum01", " Mumbai ", null));
        assertThat(b.active()).isTrue();
        verify(repo).insertBranch("MUM01", "Mumbai", true);
    }
}
