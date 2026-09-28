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

    private final SecurityProperties securityProperties;

    public OidcAuthConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    public InMemoryClientRegistrationRepository clientRegistrationRepository() {
        ClientRegistration clientRegistration = ClientRegistration
                .withRegistrationId("oidc")
                .clientId(securityProperties.getOidc().getClientId())
                .clientSecret(securityProperties.getOidc().getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL,
                        securityProperties.getOidc().getAdminClaim())
                .authorizationUri(securityProperties.getOidc().getIssuerUri() + "/oauth/authorize")
                .tokenUri(securityProperties.getOidc().getIssuerUri() + "/oauth/token")
                .userInfoUri(securityProperties.getOidc().getIssuerUri() + "/oauth/userinfo")
                .jwkSetUri(securityProperties.getOidc().getIssuerUri() + "/.well-known/jwks.json")
                .issuerUri(securityProperties.getOidc().getIssuerUri())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();

        return new InMemoryClientRegistrationRepository(clientRegistration);
    }

    public Collection<SimpleGrantedAuthority> extractRoles(Object claimValue) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        if (claimValue == null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            return authorities;
        }

        if (claimValue instanceof String) {
            String value = (String) claimValue;
            if (value.equals(securityProperties.getOidc().getAdminGroupValue())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            } else if (value.equals(securityProperties.getOidc().getUserGroupValue())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }
        } else if (claimValue instanceof Collection) {
            Collection<?> values = (Collection<?>) claimValue;
            for (Object value : values) {
                String stringValue = String.valueOf(value);
                if (stringValue.equals(securityProperties.getOidc().getAdminGroupValue())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    break;
                } else if (stringValue.equals(securityProperties.getOidc().getUserGroupValue())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                }
            }
        }

        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return authorities;
    }
}
