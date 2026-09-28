package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private SecurityProperties securityProperties;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private AuthController authController;

    private AuthTestData testData;

    record LoginRequestFixture(String username, String password) {
        static LoginRequestFixture valid() {
            return new LoginRequestFixture("testuser", "password123");
        }

        static LoginRequestFixture admin() {
            return new LoginRequestFixture("admin", "password123");
        }

        static LoginRequestFixture empty() {
            return new LoginRequestFixture("", "");
        }

        AuthController.LoginRequest toLoginRequest() {
            AuthController.LoginRequest request = new AuthController.LoginRequest();
            request.setUsername(username);
            request.setPassword(password);
            return request;
        }
    }

    record AuthFixture(String username, String mode, String role, Collection<GrantedAuthority> authorities) {
        static AuthFixture userLocal() {
            return new AuthFixture("testuser", "LOCAL", "USER",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
        }

        static AuthFixture adminLdap() {
            return new AuthFixture("admin", "LDAP", "ADMIN",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        }

        static AuthFixture userOidc() {
            return new AuthFixture("testuser", "OIDC", "USER",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
        }

        static AuthFixture multiAuth() {
            return new AuthFixture("testuser", "LOCAL", "ADMIN",
                    List.of(
                            new SimpleGrantedAuthority("ROLE_USER"),
                            new SimpleGrantedAuthority("ROLE_VIEWER"),
                            new SimpleGrantedAuthority("ROLE_ADMIN")
                    ));
        }
    }

    static class AuthTestData {
        LoginRequestFixture validLogin() {
            return LoginRequestFixture.valid();
        }

        LoginRequestFixture adminLogin() {
            return LoginRequestFixture.admin();
        }

        LoginRequestFixture emptyLogin() {
            return LoginRequestFixture.empty();
        }

        AuthFixture userLocal() {
            return AuthFixture.userLocal();
        }

        AuthFixture adminLdap() {
            return AuthFixture.adminLdap();
        }

        AuthFixture userOidc() {
            return AuthFixture.userOidc();
        }

        AuthFixture multiAuth() {
            return AuthFixture.multiAuth();
        }
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testData = new AuthTestData();
    }

    @Test
    void testLoginSuccess() {
        LoginRequestFixture loginFixture = testData.validLogin();
        AuthFixture authFixture = testData.userLocal();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) body.get("success"));
        assertEquals("testuser", body.get("username"));
        assertEquals("USER", body.get("role"));
    }

    @Test
    void testLoginSuccessWithAdminRole() {
        LoginRequestFixture loginFixture = testData.adminLogin();
        AuthFixture authFixture = testData.adminLdap();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) body.get("success"));
        assertEquals("ADMIN", body.get("role"));
        assertEquals("LDAP", body.get("authMode"));
    }

    @Test
    void testLoginFailureInvalidCredentials() {
        LoginRequestFixture loginFixture = testData.validLogin();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse((Boolean) body.get("success"));
        assertEquals("Invalid username or password", body.get("error"));
    }

    @Test
    void testLoginWithEmptyUsername() {
        LoginRequestFixture loginFixture = testData.emptyLogin();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testLoginWithEmptyPassword() {
        LoginRequestFixture loginFixture = new LoginRequestFixture("testuser", "");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testLoginWithNullUsername() {
        LoginRequestFixture loginFixture = new LoginRequestFixture(null, "password");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testGetCurrentUserSuccess() {
        AuthFixture authFixture = testData.userOidc();

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.getCurrentUser(authentication);

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("testuser", body.get("username"));
        assertEquals("OIDC", body.get("authMode"));
    }

    @Test
    void testGetCurrentUserUnauthenticated() {
        ResponseEntity<Map<String, Object>> response = authController.getCurrentUser(null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testGetCurrentUserNotAuthenticated() {
        when(authentication.isAuthenticated()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = authController.getCurrentUser(authentication);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testGetCurrentUserWithAdminRole() {
        AuthFixture authFixture = testData.adminLdap();

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.getCurrentUser(authentication);

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ADMIN", body.get("role"));
    }

    @Test
    void testLogoutWithSession() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);

        when(request.getSession(false)).thenReturn(session);

        ResponseEntity<Void> response = authController.logout(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(session, times(1)).invalidate();
    }

    @Test
    void testLogoutWithoutSession() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<Void> response = authController.logout(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetAuthConfig() {
        when(securityProperties.getMode()).thenReturn("LDAP");

        ResponseEntity<Map<String, String>> response = authController.getAuthConfig();

        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("LDAP", body.get("mode"));
    }

    @Test
    void testGetAuthConfigWithDifferentModes() {
        String[] modes = {"LOCAL", "LDAP", "OIDC"};

        for (String mode : modes) {
            when(securityProperties.getMode()).thenReturn(mode);

            ResponseEntity<Map<String, String>> response = authController.getAuthConfig();
            Map<String, String> body = response.getBody();

            assertNotNull(body);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(mode, body.get("mode"));
        }
    }

    @Test
    void testLoginRequestGettersSetters() {
        LoginRequestFixture fixture = testData.validLogin();
        AuthController.LoginRequest request = fixture.toLoginRequest();

        assertEquals("testuser", request.getUsername());
        assertEquals("password123", request.getPassword());
    }

    @Test
    void testLoginWithMultipleAuthorities() {
        LoginRequestFixture loginFixture = testData.validLogin();
        AuthFixture authFixture = testData.multiAuth();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ADMIN", body.get("role"));
    }

    @Test
    void testLogoutClearsSecurityContext() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);

        authController.logout(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testLoginResponseContainsAllFields() {
        LoginRequestFixture loginFixture = testData.validLogin();
        AuthFixture authFixture = testData.userLocal();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getName()).thenReturn(authFixture.username());
        doReturn(authFixture.authorities()).when(authentication).getAuthorities();
        when(securityProperties.getMode()).thenReturn(authFixture.mode());

        ResponseEntity<Map<String, Object>> response = authController.login(loginFixture.toLoginRequest());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("success"));
        assertTrue(body.containsKey("username"));
        assertTrue(body.containsKey("authMode"));
        assertTrue(body.containsKey("role"));
    }
}
