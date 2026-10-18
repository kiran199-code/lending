package com.bank.lending.iam.web;

import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/** Gives every request a correlation id (kept from X-Correlation-Id when it is safe) and the caller's address for the audit trail. */
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) id = Hashing.randomToken(12);
        response.setHeader(HEADER, id);
        RequestContext.set(new RequestContext(id, request.getRemoteAddr()));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
