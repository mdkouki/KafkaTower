package com.lynra.kafkatower.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OidcAuthConfigTest {

    private final SecurityProperties properties = new SecurityProperties();
    private final OidcAuthConfig config = new OidcAuthConfig(properties);

    OidcAuthConfigTest() {
        properties.getOidc().setAdminGroupValue("kafka-admins");
        properties.getOidc().setUserGroupValue("kafka-users");
    }

    private List<String> roles(Object claim) {
        return config.extractRoles(claim).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void missingClaimDefaultsToUser() {
        assertThat(roles(null)).containsExactly("ROLE_USER");
    }

    @Test
    void stringClaimMatchingTheAdminGroupGrantsAdmin() {
        assertThat(roles("kafka-admins")).containsExactly("ROLE_ADMIN");
    }

    @Test
    void stringClaimMatchingTheUserGroupGrantsUser() {
        assertThat(roles("kafka-users")).containsExactly("ROLE_USER");
    }

    @Test
    void unknownStringClaimFallsBackToUser() {
        assertThat(roles("somebody-else")).containsExactly("ROLE_USER");
    }

    @Test
    void collectionContainingTheAdminGroupGrantsAdmin() {
        assertThat(roles(List.of("other", "kafka-admins", "kafka-users"))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void collectionContainingOnlyTheUserGroupGrantsUser() {
        assertThat(roles(List.of("other", "kafka-users"))).containsExactly("ROLE_USER");
    }

    @Test
    void collectionWithoutKnownGroupsFallsBackToUser() {
        assertThat(roles(List.of("other"))).containsExactly("ROLE_USER");
    }

    @Test
    void claimOfUnsupportedTypeFallsBackToUser() {
        assertThat(roles(42)).containsExactly("ROLE_USER");
    }

    @Test
    void registrationPointsAtTheConfiguredIssuer() {
        properties.getOidc().setIssuerUri("https://idp.example.com");
        properties.getOidc().setClientId("kafkatower");
        properties.getOidc().setClientSecret("secret");
        properties.getOidc().setAdminClaim("groups");

        ClientRegistration registration = config.clientRegistrationRepository().findByRegistrationId("oidc");

        assertThat(registration.getClientId()).isEqualTo("kafkatower");
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("https://idp.example.com/oauth/token");
        assertThat(registration.getScopes()).contains("openid", "groups");
    }
}
