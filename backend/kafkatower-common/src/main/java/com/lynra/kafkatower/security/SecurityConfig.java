package com.lynra.kafkatower.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class SecurityConfig {

    private static final String[] CSRF_IGNORED_PATHS = {
            "/api/chat", "/api/registry/**",
            "/api/feedback", "/api/auth/login", "/api/auth/logout",
            "/api/admin/**"
    };

    private final SecurityProperties securityProperties;
    private final Optional<LdapAuthenticationProvider> ldapAuthenticationProvider;

    public SecurityConfig(SecurityProperties securityProperties,
                          Optional<LdapAuthenticationProvider> ldapAuthenticationProvider) {
        this.securityProperties = securityProperties;
        this.ldapAuthenticationProvider = ldapAuthenticationProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiTokenFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("API")
                )
                .addFilterBefore(new ApiTokenFilter(securityProperties.getApiClients()),
                        UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "jaas", matchIfMissing = true)
    public UserDetailsService userDetailsService() {
        List<UserDetails> users = new ArrayList<>();

        if (securityProperties.getJaas().getUsers() != null) {
            for (SecurityProperties.JaasConfig.User user : securityProperties.getJaas().getUsers()) {
                UserDetails userDetails = User.builder()
                        .username(user.getUsername())
                        .password(user.getPassword())
                        .roles(user.getRole())
                        .build();
                users.add(userDetails);
            }
        }

        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "jaas", matchIfMissing = true)
    public SecurityFilterChain filterChainJaasLdap(HttpSecurity http, UserDetailsService userDetailsService) throws Exception {
        DaoAuthenticationProvider daoAuthProvider = new DaoAuthenticationProvider(userDetailsService);
        daoAuthProvider.setPasswordEncoder(passwordEncoder());

        http.authenticationProvider(daoAuthProvider);

        return configureJaasLdapSecurity(http);
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "ldap")
    public SecurityFilterChain filterChainLdap(HttpSecurity http) throws Exception {
        if (ldapAuthenticationProvider.isPresent()) {
            http.authenticationProvider(ldapAuthenticationProvider.get());
        }
        return configureJaasLdapSecurity(http);
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
    public SecurityFilterChain filterChainOidc(HttpSecurity http) throws Exception {
        return configureOidcSecurity(http);
    }

    private void configureSharedAuthorization(HttpSecurity http, String... permitAllPaths) throws Exception {
        http
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(permitAllPaths).permitAll();
                    auth.requestMatchers("/api/registry/**").hasAnyRole("ADMIN", "USER");
                    auth.requestMatchers("/api/groups/**").hasAnyRole("ADMIN", "USER");
                    auth.requestMatchers("/api/chat").hasAnyRole("ADMIN", "USER");
                    auth.requestMatchers("/api/analytics/**").hasRole("ADMIN");
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.POST, "/api/feedback").hasAnyRole("ADMIN", "USER");
                    auth.requestMatchers(HttpMethod.GET, "/api/feedback").hasRole("ADMIN");
                    auth.requestMatchers("/api/auth/me").authenticated();
                    auth.requestMatchers("/h2-console/**").hasRole("ADMIN");
                    auth.requestMatchers("/mcp/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::handleAuthenticationException)
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                handleAccessDenied(request, response, accessDeniedException)
                        )
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(CSRF_IGNORED_PATHS)
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );
    }

    private SecurityFilterChain configureJaasLdapSecurity(HttpSecurity http) throws Exception {
        configureSharedAuthorization(http,
                "/", "/login", "/api/auth/login", "/api/auth/logout", "/api/auth/config",
                "/static/**", "/assets/**", "/index.html");

        http
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/h2-console/**", "/login", "/logout")
                );

        return http.build();
    }

    private SecurityFilterChain configureOidcSecurity(HttpSecurity http) throws Exception {
        configureSharedAuthorization(http,
                "/", "/index.html", "/login", "/oauth2/**", "/api/auth/login", "/api/auth/logout",
                "/api/auth/config", "/static/**", "/assets/**");

        http.oauth2Login(oauth -> {
        });

        return http.build();
    }

    private void handleAuthenticationException(HttpServletRequest request, HttpServletResponse response,
                                               org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        if (isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Unauthorized\"}");
        } else {
            response.sendRedirect("/");
        }
    }

    private void handleAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                    org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException {
        if (isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Forbidden\"}");
        } else {
            response.sendRedirect("/");
        }
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }
}
