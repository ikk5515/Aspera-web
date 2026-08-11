package com.aspera.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;

    public LoginRateLimitFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String loginPath = request.getContextPath() + "/login";
        return !HttpMethod.POST.matches(request.getMethod()) || !loginPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (loginAttemptService.isBlocked(request.getRemoteAddr(), request.getParameter("username"))) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
