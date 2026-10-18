package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.Hashing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single-use recovery codes: {@code XXXXX-XXXXX} from an alphabet without look-alike characters. */
public final class RecoveryCodes {
    public static final int COUNT = 8;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private RecoveryCodes() {}

    public static List<String> generate() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                if (j == 5) sb.append('-');
                sb.append(ALPHABET.charAt(Hashing.randomInt(ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    public static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    public static boolean looksLikeRecoveryCode(String submitted) {
        String n = normalise(submitted);
        return n.length() == 11 && n.charAt(5) == '-';
    }

    public static String hash(String code) {
        return Hashing.sha256Hex("recovery:" + normalise(code));
    }
}
