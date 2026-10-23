package com.bank.lending.chit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChitSettings {
    private final int maxEventPage;
    private final boolean makerCheckerEnforce;

    public ChitSettings(@Value("${chit.events.max-page-size:500}") int maxEventPage,
                        @Value("${chit.maker-checker.enforce:true}") boolean makerCheckerEnforce) {
        this.maxEventPage = maxEventPage;
        this.makerCheckerEnforce = makerCheckerEnforce;
    }

    public int maxEventPage() {
        return maxEventPage;
    }

    /** When false, a maker may also be the checker (single-person demos). The database CHECKs still apply unless relaxed in a later migration. */
    public boolean makerCheckerEnforce() {
        return makerCheckerEnforce;
    }
}
