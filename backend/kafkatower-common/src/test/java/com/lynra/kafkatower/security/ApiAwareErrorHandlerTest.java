package com.lynra.kafkatower.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAwareErrorHandlerTest {

    private final ApiAwareErrorHandler handler = new ApiAwareErrorHandler();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void unauthenticatedApiRequestGetsJson401() throws Exception {
        handler.commence(new MockHttpServletRequest("GET", "/api/topics"), response, new BadCredentialsException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Unauthorized\"}");
    }

    @Test
    void forbiddenApiRequestGetsJson403() throws Exception {
        handler.handle(new MockHttpServletRequest("GET", "/api/admin/x"), response, new AccessDeniedException("x"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Forbidden\"}");
    }

    @Test
    void unauthenticatedBrowserNavigationIsRedirectedToTheRoot() throws Exception {
        handler.commence(new MockHttpServletRequest("GET", "/settings"), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void forbiddenBrowserNavigationIsRedirectedToTheRoot() throws Exception {
        handler.handle(new MockHttpServletRequest("GET", "/h2-console"), response, new AccessDeniedException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }
}
