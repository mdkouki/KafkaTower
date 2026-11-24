package com.lynra.kafkatower.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Answers API requests with a JSON error and sends browser navigations back to the SPA root. */
class ApiAwareErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String API_PATH_PREFIX = "/api/";
    private static final String SPA_ROOT = "/";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        respond(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        respond(request, response, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
    }

    private void respond(HttpServletRequest request, HttpServletResponse response, int status, String error)
            throws IOException {
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            response.sendRedirect(SPA_ROOT);
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + error + "\"}");
    }
}
