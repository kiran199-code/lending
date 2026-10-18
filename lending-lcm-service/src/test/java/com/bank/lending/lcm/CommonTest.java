package com.bank.lending.lcm;

import com.bank.lending.lcm.common.Actor;
import com.bank.lending.lcm.common.Hashing;
import com.bank.lending.lcm.common.LcmException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonTest {

    @Test
    void humanWorkflowsRequireAnActor() {
        assertThatThrownBy(() -> Actor.required(null)).isInstanceOf(LcmException.class).hasMessageContaining("X-Actor");
        assertThatThrownBy(() -> Actor.required("  ")).isInstanceOf(LcmException.class);
        assertThat(Actor.required(" maker1 ")).isEqualTo("maker1");
    }

    @Test
    void serviceCallsFallBackToTheServiceName() {
        assertThat(Actor.orService(null)).isEqualTo("lending-service");
        assertThat(Actor.orService("batch")).isEqualTo("batch");
    }

    @Test
    void sha256IsStableAndSensitiveToInput() {
        assertThat(Hashing.sha256("a")).isEqualTo(Hashing.sha256("a"));
        assertThat(Hashing.sha256("a")).isNotEqualTo(Hashing.sha256("b"));
        assertThat(Hashing.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
