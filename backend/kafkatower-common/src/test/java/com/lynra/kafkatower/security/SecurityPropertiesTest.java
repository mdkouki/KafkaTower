package com.lynra.kafkatower.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPropertiesTest {

    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
    }

    @Test
    void testSecurityPropertiesDefaultMode() {
        assertEquals("jaas", securityProperties.getMode());
    }

    @Test
    void testSetAndGetMode() {
        securityProperties.setMode("ldap");
        assertEquals("ldap", securityProperties.getMode());
    }

    @Test
    void testSetModeToDifferentValues() {
        String[] modes = {"jaas", "ldap", "oidc"};

        for (String mode : modes) {
            securityProperties.setMode(mode);
            assertEquals(mode, securityProperties.getMode());
        }
    }

    @Test
    void testJaasConfigDefaultNotNull() {
        assertNotNull(securityProperties.getJaas());
    }

    @Test
    void testLdapConfigDefaultNotNull() {
        assertNotNull(securityProperties.getLdap());
    }

    @Test
    void testOidcConfigDefaultNotNull() {
        assertNotNull(securityProperties.getOidc());
    }

    @Test
    void testSetJaasConfig() {
        SecurityProperties.JaasConfig newJaas = new SecurityProperties.JaasConfig();
        securityProperties.setJaas(newJaas);
        assertEquals(newJaas, securityProperties.getJaas());
    }

    @Test
    void testSetLdapConfig() {
        SecurityProperties.LdapConfig newLdap = new SecurityProperties.LdapConfig();
        securityProperties.setLdap(newLdap);
        assertEquals(newLdap, securityProperties.getLdap());
    }

    @Test
    void testSetOidcConfig() {
        SecurityProperties.OidcConfig newOidc = new SecurityProperties.OidcConfig();
        securityProperties.setOidc(newOidc);
        assertEquals(newOidc, securityProperties.getOidc());
    }

    @Test
    void testJaasUserConstructor() {
        SecurityProperties.JaasConfig.User user =
                new SecurityProperties.JaasConfig.User("admin", "password", "ADMIN");

        assertEquals("admin", user.getUsername());
        assertEquals("password", user.getPassword());
        assertEquals("ADMIN", user.getRole());
    }

    @Test
    void testJaasUserDefaultConstructor() {
        SecurityProperties.JaasConfig.User user = new SecurityProperties.JaasConfig.User();
        assertNotNull(user);
    }

    @Test
    void testJaasUserSettersAndGetters() {
        SecurityProperties.JaasConfig.User user = new SecurityProperties.JaasConfig.User();
        user.setUsername("testuser");
        user.setPassword("testpass");
        user.setRole("USER");

        assertEquals("testuser", user.getUsername());
        assertEquals("testpass", user.getPassword());
        assertEquals("USER", user.getRole());
    }

    @Test
    void testJaasConfigSetAndGetUsers() {
        List<SecurityProperties.JaasConfig.User> users = new ArrayList<>();
        users.add(new SecurityProperties.JaasConfig.User("user1", "pass1", "USER"));
        users.add(new SecurityProperties.JaasConfig.User("admin", "pass2", "ADMIN"));

        securityProperties.getJaas().setUsers(users);

        assertEquals(2, securityProperties.getJaas().getUsers().size());
    }

    @Test
    void testLdapConfigGettersSetters() {
        SecurityProperties.LdapConfig ldap = securityProperties.getLdap();
        ldap.setUrl("ldap://localhost:389");
        ldap.setBase("dc=example,dc=com");
        ldap.setUserSearchBase("ou=people");
        ldap.setUserSearchFilter("uid={0}");
        ldap.setGroupSearchBase("ou=groups");
        ldap.setGroupSearchFilter("member={0}");

        assertEquals("ldap://localhost:389", ldap.getUrl());
        assertEquals("dc=example,dc=com", ldap.getBase());
        assertEquals("ou=people", ldap.getUserSearchBase());
        assertEquals("uid={0}", ldap.getUserSearchFilter());
        assertEquals("ou=groups", ldap.getGroupSearchBase());
        assertEquals("member={0}", ldap.getGroupSearchFilter());
    }

    @Test
    void testLdapConfigRoleMappings() {
        SecurityProperties.LdapConfig ldap = securityProperties.getLdap();
        Map<String, String> roleMappings = new HashMap<>();
        roleMappings.put("admin-group", "ROLE_ADMIN");
        roleMappings.put("user-group", "ROLE_USER");

        ldap.setRoleMappings(roleMappings);

        assertEquals(2, ldap.getRoleMappings().size());
        assertEquals("ROLE_ADMIN", ldap.getRoleMappings().get("admin-group"));
    }

    @Test
    void testLdapConfigDefaultEmptyRoleMappings() {
        SecurityProperties.LdapConfig ldap = new SecurityProperties.LdapConfig();
        assertNotNull(ldap.getRoleMappings());
        assertTrue(ldap.getRoleMappings().isEmpty());
    }

    @Test
    void testOidcConfigGettersSetters() {
        SecurityProperties.OidcConfig oidc = securityProperties.getOidc();
        oidc.setIssuerUri("https://auth.example.com");
        oidc.setClientId("client-id");
        oidc.setClientSecret("client-secret");
        oidc.setAdminClaim("realm_access.roles");
        oidc.setAdminGroupValue("admin");
        oidc.setUserGroupValue("user");

        assertEquals("https://auth.example.com", oidc.getIssuerUri());
        assertEquals("client-id", oidc.getClientId());
        assertEquals("client-secret", oidc.getClientSecret());
        assertEquals("realm_access.roles", oidc.getAdminClaim());
        assertEquals("admin", oidc.getAdminGroupValue());
        assertEquals("user", oidc.getUserGroupValue());
    }

    @Test
    void testOidcConfigNullValues() {
        SecurityProperties.OidcConfig oidc = new SecurityProperties.OidcConfig();
        assertNull(oidc.getIssuerUri());
        assertNull(oidc.getClientId());
        assertNull(oidc.getClientSecret());
    }

    @Test
    void testMultipleSecurityPropertiesInstances() {
        SecurityProperties props1 = new SecurityProperties();
        SecurityProperties props2 = new SecurityProperties();

        props1.setMode("ldap");
        props2.setMode("oidc");

        assertEquals("ldap", props1.getMode());
        assertEquals("oidc", props2.getMode());
    }

    @Test
    void testJaasConfigWithMultipleUsers() {
        List<SecurityProperties.JaasConfig.User> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            users.add(new SecurityProperties.JaasConfig.User("user" + i, "pass" + i, "ROLE_" + i));
        }

        securityProperties.getJaas().setUsers(users);

        assertEquals(5, securityProperties.getJaas().getUsers().size());
    }

    @Test
    void testSecurityPropertiesCompleteConfiguration() {
        securityProperties.setMode("ldap");

        SecurityProperties.LdapConfig ldap = securityProperties.getLdap();
        ldap.setUrl("ldap://ldap.example.com:389");
        ldap.setBase("dc=example,dc=com");

        assertEquals("ldap", securityProperties.getMode());
        assertEquals("ldap://ldap.example.com:389", ldap.getUrl());
    }

    @Test
    void testLdapConfigNullValues() {
        SecurityProperties.LdapConfig ldap = new SecurityProperties.LdapConfig();
        assertNull(ldap.getUrl());
        assertNull(ldap.getBase());
        assertNull(ldap.getUserSearchBase());
    }

    @Test
    void testJaasUserNullValues() {
        SecurityProperties.JaasConfig.User user = new SecurityProperties.JaasConfig.User();
        assertNull(user.getUsername());
        assertNull(user.getPassword());
        assertNull(user.getRole());
    }
}
