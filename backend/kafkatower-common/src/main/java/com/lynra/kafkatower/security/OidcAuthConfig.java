package com.lynra.kafkatower.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "oidc")
public class OidcAuthConfig {

    private static final SimpleGrantedAuthority ADMIN_AUTHORITY = new SimpleGrantedAuthority("ROLE_ADMIN");
    private static final SimpleGrantedAuthority USER_AUTHORITY = new SimpleGrantedAuthority("ROLE_USER");

    private final SecurityProperties securityProperties;

    public OidcAuthConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    public InMemoryClientRegistrationRepository clientRegistrationRepository() {
        SecurityProperties.OidcConfig oidc = securityProperties.getOidc();
        ClientRegistration clientRegistration = ClientRegistration
                .withRegistrationId("oidc")
                .clientId(oidc.getClientId())
                .clientSecret(oidc.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL, oidc.getAdminClaim())
                .authorizationUri(oidc.getIssuerUri() + "/oauth/authorize")
                .tokenUri(oidc.getIssuerUri() + "/oauth/token")
                .userInfoUri(oidc.getIssuerUri() + "/oauth/userinfo")
                .jwkSetUri(oidc.getIssuerUri() + "/.well-known/jwks.json")
                .issuerUri(oidc.getIssuerUri())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();

        return new InMemoryClientRegistrationRepository(clientRegistration);
    }

    public Collection<SimpleGrantedAuthority> extractRoles(Object claimValue) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        if (claimValue instanceof String group) {
            addRoleForGroup(authorities, group);
        } else if (claimValue instanceof Collection<?> groups) {
            for (Object group : groups) {
                addRoleForGroup(authorities, String.valueOf(group));
                if (authorities.contains(ADMIN_AUTHORITY)) {
                    break;
                }
            }
        }

        if (authorities.isEmpty()) {
            authorities.add(USER_AUTHORITY);
        }
        return authorities;
    }

    private void addRoleForGroup(List<SimpleGrantedAuthority> authorities, String group) {
        if (group.equals(securityProperties.getOidc().getAdminGroupValue())) {
            authorities.add(ADMIN_AUTHORITY);
        } else if (group.equals(securityProperties.getOidc().getUserGroupValue())) {
            authorities.add(USER_AUTHORITY);
        }
    }
}
