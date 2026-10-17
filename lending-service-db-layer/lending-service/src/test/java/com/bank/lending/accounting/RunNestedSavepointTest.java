package com.bank.lending.accounting;

import com.bank.lending.accounting.service.AccountingEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A non-blocking event that fails must undo only its own writes. The application's transaction manager is JPA, which
 * cannot create nested transactions ("JpaDialect does not support savepoints"), so the engine takes a JDBC savepoint
 * on the connection of the caller's transaction. This pins that behaviour with a recording connection.
 */
class RunNestedSavepointTest {

    private final List<String> calls = new ArrayList<>();
    private final Savepoint savepoint = (Savepoint) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Savepoint.class}, (p, m, a) -> m.getName().equals("toString") ? "sp" : null);
    private boolean rollbackFails;

    private final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (p, m, a) -> {
                calls.add(m.getName());
                if (m.getName().equals("setSavepoint")) {
                    return savepoint;
                }
                if (m.getName().equals("rollback") && rollbackFails) {
                    throw new SQLException("rollback failed");
                }
                return null;
            });

    private final JdbcTemplate jdbc = new JdbcTemplate() {
        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            try {
                return action.doInConnection(connection);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private class Probe extends AccountingEventService {
        Probe() {
            super(null, null, null, null, null, new ObjectMapper(), jdbc);
        }

        <T> T nested(Supplier<T> work) {
            return runNested(work);
        }
    }

    @Test
    void successReleasesTheSavepointAndReturnsTheResult() {
        String result = new Probe().nested(() -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(calls).containsExactly("setSavepoint", "releaseSavepoint");
    }

    @Test
    void failureRollsBackToTheSavepointAndRethrowsTheOriginalException() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> new Probe().nested(() -> { throw boom; })).isSameAs(boom);

        assertThat(calls).containsExactly("setSavepoint", "rollback");
    }

    @Test
    void aFailedRollbackDoesNotHideTheOriginalError() {
        rollbackFails = true;
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> new Probe().nested(() -> { throw boom; })).isSameAs(boom);

        assertThat(boom.getSuppressed().length).isEqualTo(1);
    }
}
