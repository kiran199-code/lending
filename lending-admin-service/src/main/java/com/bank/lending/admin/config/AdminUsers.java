package com.bank.lending.admin.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The console logins. {@code admin} is the original one and is always present. The three operations users exist only when
 * their password is set (an empty password switches the user off), so nothing is created by accident.
 */
public final class AdminUsers {

    public record UserSpec(String username, String password, String role) {}

    private AdminUsers() {}

    public static List<UserSpec> build(String adminUser, String adminPassword,
                                       String batchUser, String batchPassword,
                                       String loanUser, String loanPassword,
                                       String limitsUser, String limitsPassword) {
        List<UserSpec> users = new ArrayList<>();
        users.add(new UserSpec(adminUser, adminPassword, AccessPolicy.ADMIN));
        add(users, batchUser, batchPassword, AccessPolicy.BATCH_OPS);
        add(users, loanUser, loanPassword, AccessPolicy.LOAN_OPS);
        add(users, limitsUser, limitsPassword, AccessPolicy.LIMITS_OPS);
        Set<String> seen = new HashSet<>();
        for (UserSpec u : users) {
            if (!seen.add(u.username())) {
                throw new IllegalStateException("Console user name '" + u.username() + "' is used twice; give each login its own name");
            }
        }
        return users;
    }

    private static void add(List<UserSpec> users, String name, String password, String role) {
        if (password == null || password.isBlank()) {
            return;
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("A password is set for the " + role + " login but its user name is empty");
        }
        users.add(new UserSpec(name.trim(), password, role));
    }
}
