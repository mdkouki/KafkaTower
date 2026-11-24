package com.lynra.kafkatower.security;

import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LdapAuthoritiesTest {

    private static List<GrantedAuthority> authorities(String... names) {
        return Arrays.stream(names).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    private static List<String> names(Collection<? extends GrantedAuthority> granted) {
        return granted.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void extractCnReadsTheCommonNameOfADn() {
        assertThat(LdapAuthorities.extractCn("ou=groups, cn=KAFKA-ADMINS,dc=corp")).isEqualTo("KAFKA-ADMINS");
    }

    @Test
    void extractCnIsEmptyWithoutACnOrDn() {
        assertThat(LdapAuthorities.extractCn("ou=groups,dc=corp")).isEmpty();
        assertThat(LdapAuthorities.extractCn(null)).isEmpty();
    }

    @Test
    void userAttributeValuesBecomeUpperCasedRoles() {
        DirContextOperations user = mock(DirContextOperations.class);
        when(user.getStringAttributes("cudgroup")).thenReturn(new String[]{"kafka_admin", "dev"});

        var granted = LdapAuthorities.fromUserAttribute("cudgroup").getGrantedAuthorities(user, "alice");

        assertThat(names(granted)).containsExactly("ROLE_KAFKA_ADMIN", "ROLE_DEV");
    }

    @Test
    void missingUserAttributeYieldsNoRoles() {
        DirContextOperations user = mock(DirContextOperations.class);

        assertThat(LdapAuthorities.fromUserAttribute("cudgroup").getGrantedAuthorities(user, "alice")).isEmpty();
    }

    @Test
    void attributeValuesAreMappedToApplicationRolesCaseInsensitively() {
        var mapper = LdapAuthorities.mapper(Map.of("ADMIN", "kafka_admin"), null, false);

        assertThat(names(mapper.mapAuthorities(authorities("ROLE_KAFKA_ADMIN")))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void groupCnsAreMappedToApplicationRolesWhenGroupSearchIsUsed() {
        var mapper = LdapAuthorities.mapper(Map.of("ADMIN", "cn=Kafka-Admins,ou=groups,dc=corp"), null, true);

        assertThat(names(mapper.mapAuthorities(authorities("ROLE_KAFKA-ADMINS")))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unmappedAuthoritiesPassThroughUnchanged() {
        var mapper = LdapAuthorities.mapper(Map.of("ADMIN", "kafka_admin"), null, false);

        assertThat(names(mapper.mapAuthorities(authorities("ROLE_OTHER", "PLAIN")))).containsExactlyInAnyOrder("ROLE_OTHER", "PLAIN");
    }

    @Test
    void defaultRoleIsAlwaysGranted() {
        var mapper = LdapAuthorities.mapper(Map.of(), "user", false);

        assertThat(names(mapper.mapAuthorities(authorities()))).containsExactly("ROLE_USER");
    }

    @Test
    void blankDefaultRoleGrantsNothing() {
        var mapper = LdapAuthorities.mapper(Map.of(), " ", false);

        assertThat(mapper.mapAuthorities(authorities())).isEmpty();
    }
}
