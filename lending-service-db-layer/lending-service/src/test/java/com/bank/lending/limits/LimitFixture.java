package com.bank.lending.limits;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Wires the limits classes around a scripted LCM and an in-memory queue. */
class LimitFixture {

    final ScriptedTransport lcm = new ScriptedTransport();
    final FakeLimitOutbox outbox = new FakeLimitOutbox();
    final LimitSettings settings;
    final LimitClient client;
    final LimitOutboxService service;
    final LimitGateway gateway;

    LimitFixture(String mode) {
        this(mode, false, 2, 3);
    }

    LimitFixture(String mode, boolean failOpen, int retries, int maxAttempts) {
        settings = new LimitSettings(mode, "http://lcm.test", "u", "p", 100, 100, retries, failOpen, 30, 50, maxAttempts, 5);
        client = new LimitClient(lcm, settings);
        service = new LimitOutboxService(outbox, client, settings);
        service.setExecutor(Runnable::run); // deliver inline so tests are deterministic
        gateway = new LimitGateway(settings, client, outbox, service);
    }

    static final LimitGateway.Subject LINKED = new LimitGateway.Subject(7L, "SANC-7", "INR", "ONLINE");
    static final LimitGateway.Subject UNLINKED = new LimitGateway.Subject(8L, null, "INR", "ONLINE");

    /** Runs {@code body} as if inside a transaction and then finishes it the way Spring would. */
    void inTransaction(boolean commit, Runnable body) {
        TransactionSynchronizationManager.initSynchronization();
        boolean finished = false;
        try {
            body.run();
        } catch (RuntimeException e) {
            finish(false);
            finished = true;
            throw e;
        } finally {
            if (!finished && TransactionSynchronizationManager.isSynchronizationActive()) {
                finish(commit);
            }
        }
    }

    private static void finish(boolean commit) {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        if (commit) {
            syncs.forEach(TransactionSynchronization::afterCommit);
        }
        syncs.forEach(s -> s.afterCompletion(commit ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
