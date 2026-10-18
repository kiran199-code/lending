package com.bank.lending.iam.events;

import java.time.Duration;
import java.util.Map;

/** Sends one webhook request. The JDK implementation is used in production; tests use a fake. */
public interface HttpSender {
    record Response(int status, String error) {
        public boolean ok() { return error == null && status >= 200 && status < 300; }
    }

    Response post(String url, String body, Map<String, String> headers, Duration timeout);
}
