package com.bank.lending.iam.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes prompt=login on /oauth2/authorize mean what it says (the library does not force a new sign-in by itself).
 * A person who needs a fresh second factor for a sensitive action is sent back through the sign-in page; the sign-in
 * stamps the session and the repeated authorize request, which carries the stamp, is let through exactly once.
 */
public class ReauthFilter extends OncePerRequestFilter {
    public static final String SIGNED_IN_AT = "iam.signedInAt";
    static final long FRESH_MILLIS = 120_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().endsWith("/oauth2/authorize") && wantsLogin(request.getParameter("prompt"))) {
            HttpSession session = request.getSession(false);
            Object at = session == null ? null : session.getAttribute(SIGNED_IN_AT);
            boolean fresh = at instanceof Long t && System.currentTimeMillis() - t <= FRESH_MILLIS;
            if (fresh) {
                session.removeAttribute(SIGNED_IN_AT);   // one use
            } else {
                SecurityContextHolder.setContext(new SecurityContextImpl());
                if (session != null) session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }

    static boolean wantsLogin(String prompt) {
        if (prompt == null) return false;
        for (String p : prompt.trim().split("\\s+")) if ("login".equals(p)) return true;
        return false;
    }
}
