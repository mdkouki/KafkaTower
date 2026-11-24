package com.lynra.kafkatower.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    private final SecurityProperties properties = new SecurityProperties();
    private final SecurityConfig config = new SecurityConfig(properties, Optional.empty());

    @Test
    void configuredJaasUsersBecomeUsersWithTheirRole() {
        properties.getJaas().setUsers(List.of(new SecurityProperties.JaasConfig.User("alice", "pw", "ADMIN")));

        UserDetails alice = config.userDetailsService().loadUserByUsername("alice");

        assertThat(alice.getPassword()).isEqualTo("pw");
        assertThat(alice.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void noConfiguredUsersYieldsAnEmptyUserStore() {
        properties.getJaas().setUsers(null);
        UserDetailsService service = config.userDetailsService();

        assertThatThrownBy(() -> service.loadUserByUsername("alice")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void passwordEncoderKeepsConfiguredPasswordsAsPlainText() {
        assertThat(config.passwordEncoder().matches("pw", "pw")).isTrue();
    }
}
