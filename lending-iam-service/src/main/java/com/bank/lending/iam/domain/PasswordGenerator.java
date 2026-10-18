package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.Hashing;

/** Generates temporary passwords that meet the policy (16 characters, four classes, no look-alike characters). */
public final class PasswordGenerator {
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "#$%&*+-=?@^_";

    private PasswordGenerator() {}

    public static String temporary() {
        while (true) {
            char[] c = new char[16];
            String all = LOWER + UPPER + DIGIT + SYMBOL;
            c[0] = pick(LOWER);
            c[1] = pick(UPPER);
            c[2] = pick(DIGIT);
            c[3] = pick(SYMBOL);
            for (int i = 4; i < c.length; i++) c[i] = pick(all);
            for (int i = c.length - 1; i > 0; i--) { // Fisher-Yates
                int j = Hashing.randomInt(i + 1);
                char t = c[i]; c[i] = c[j]; c[j] = t;
            }
            String s = new String(c);
            if (!PasswordPolicy.isCommon(s)) return s;
        }
    }

    private static char pick(String set) {
        return set.charAt(Hashing.randomInt(set.length()));
    }
}
