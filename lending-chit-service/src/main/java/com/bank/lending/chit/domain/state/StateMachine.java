package com.bank.lending.chit.domain.state;

import com.bank.lending.chit.common.ChitException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A table of allowed transitions for one status enum. Transitions are explicit; anything not listed is refused with
 * ILLEGAL_TRANSITION. Callers also audit every change (the audit row is written by the service, Phase 2).
 */
public final class StateMachine<S extends Enum<S>> {

    private final String entity;
    private final Class<S> type;
    private final Map<S, Set<S>> allowed;

    private StateMachine(String entity, Class<S> type, Map<S, Set<S>> allowed) {
        this.entity = entity;
        this.type = type;
        this.allowed = allowed;
    }

    public static <S extends Enum<S>> Builder<S> of(String entity, Class<S> type) {
        return new Builder<>(entity, type);
    }

    public boolean canMove(S from, S to) {
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws ILLEGAL_TRANSITION unless {@code from -> to} is listed. */
    public void require(S from, S to) {
        if (!canMove(from, to)) {
            throw new ChitException("ILLEGAL_TRANSITION",
                    entity + " cannot move from " + from + " to " + to);
        }
    }

    public Set<S> next(S from) {
        Set<S> set = allowed.getOrDefault(from, Set.of());
        return set.isEmpty() ? set : EnumSet.copyOf(set);
    }

    /** A status with no way out. */
    public boolean isTerminal(S status) {
        return allowed.getOrDefault(status, Set.of()).isEmpty();
    }

    public Class<S> statusType() {
        return type;
    }

    public static final class Builder<S extends Enum<S>> {
        private final String entity;
        private final Class<S> type;
        private final Map<S, Set<S>> allowed;

        private Builder(String entity, Class<S> type) {
            this.entity = entity;
            this.type = type;
            this.allowed = new EnumMap<>(type);
        }

        @SafeVarargs
        public final Builder<S> from(S from, S... to) {
            allowed.computeIfAbsent(from, k -> EnumSet.noneOf(type)).addAll(java.util.Arrays.asList(to));
            return this;
        }

        public StateMachine<S> build() {
            return new StateMachine<>(entity, type, allowed);
        }
    }
}
