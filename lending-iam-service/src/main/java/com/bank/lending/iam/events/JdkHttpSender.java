package com.bank.lending.iam.events;

import com.bank.lending.iam.domain.UrlPolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** java.net.http client. It does not follow redirects (a redirect could point at an internal address). */
public class JdkHttpSender implements HttpSender {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final boolean allowHttp;

    public JdkHttpSender(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    @Override
    public Response post(String url, String body, Map<String, String> headers, Duration timeout) {
        try {
            URI uri = URI.create(url);
            if (!allowHttp && UrlPolicy.resolvesToBlocked(uri.getHost())) return new Response(0, "address is not allowed");
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            HttpResponse<Void> r = client.send(b.build(), HttpResponse.BodyHandlers.discarding());
            return new Response(r.statusCode(), r.statusCode() >= 200 && r.statusCode() < 300 ? null : "HTTP " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "interrupted");
        } catch (Exception e) {
            return new Response(0, e.getClass().getSimpleName());
        }
    }
}
