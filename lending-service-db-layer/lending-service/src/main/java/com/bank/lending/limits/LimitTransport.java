package com.bank.lending.limits;

import java.io.IOException;

/** One HTTP exchange with LCM. Separate from LimitClient so the client's retry and error handling can be tested without a network. */
public interface LimitTransport {

    record Response(int status, String body) {}

    /** @param idempotencyKey null for reads; @param json null for GET */
    Response send(String method, String path, String idempotencyKey, String json) throws IOException;
}
