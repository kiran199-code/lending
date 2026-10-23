package com.bank.lending.chit.ledger;

/** Which side of the journal a line sits on. The code is what the database stores ('D' or 'C'). */
public enum Side {
    DEBIT('D'),
    CREDIT('C');

    private final char code;

    Side(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public static Side ofCode(char c) {
        return switch (c) {
            case 'D' -> DEBIT;
            case 'C' -> CREDIT;
            default -> throw new IllegalArgumentException("Unknown side code: " + c);
        };
    }
}
