package com.ratelimiter.filter;
import com.ratelimiter.core.TokenBucketLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketLimiter limiter;

    public RateLimitFilter(TokenBucketLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws IOException, ServletException {
        System.out.println("RATE LIMIT FILTER: " + request.getMethod() + " " + request.getRequestURI());
        String clientId = request.getHeader("X-Client-Id");
        if (clientId == null) clientId = "anonymous";

        if (limiter.isAllowed(clientId)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.getWriter().write("Rate limit exceeded");
        }
    }
}
