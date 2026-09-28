package com.lynra.kafkatower.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "ldap")
public class LdapAuthConfig {

    private final SecurityProperties securityProperties;

    public LdapAuthConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    public LdapContextSource contextSource() {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(securityProperties.getLdap().getUrl());
        contextSource.setUserDn(securityProperties.getLdap().getUserDn());
        contextSource.setPassword(securityProperties.getLdap().getPassword());
        contextSource.setBase(securityProperties.getLdap().getBase());
        contextSource.setPooled(false);

        if (securityProperties.getLdap().isSslNoVerify()) {
            contextSource.setBaseEnvironmentProperties(java.util.Map.of(
                    "java.naming.ldap.factory.socket", TrustAllSslSocketFactory.class.getName()
            ));
        }

        contextSource.afterPropertiesSet();
        return contextSource;
    }

    @Bean
    public LdapAuthenticationProvider ldapAuthenticationProvider(LdapContextSource contextSource) {
        FilterBasedLdapUserSearch userSearch = new FilterBasedLdapUserSearch(
                securityProperties.getLdap().getUserSearchBase(),
                securityProperties.getLdap().getUserSearchFilter(),
                contextSource
        );

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(userSearch);

        boolean useGroupSearch = StringUtils.hasText(securityProperties.getLdap().getGroupSearchBase());

        LdapAuthenticationProvider provider;
        if (useGroupSearch) {
            DefaultLdapAuthoritiesPopulator groupPopulator = new DefaultLdapAuthoritiesPopulator(
                    contextSource, securityProperties.getLdap().getGroupSearchBase()
            );
            if (StringUtils.hasText(securityProperties.getLdap().getGroupSearchFilter())) {
                groupPopulator.setGroupSearchFilter(securityProperties.getLdap().getGroupSearchFilter());
            }
            groupPopulator.setGroupRoleAttribute("cn");
            groupPopulator.setRolePrefix("ROLE_");
            provider = new LdapAuthenticationProvider(authenticator, groupPopulator);
        } else {
            String roleAttr = securityProperties.getLdap().getUserRoleAttribute();
            provider = new LdapAuthenticationProvider(authenticator,
                    (userData, username) -> {
                        String[] values = userData.getStringAttributes(roleAttr);
                        if (values == null) return Collections.emptyList();
                        return Arrays.stream(values)
                                .map(v -> new SimpleGrantedAuthority("ROLE_" + v.toUpperCase()))
                                .collect(Collectors.toList());
                    });
        }

        provider.setAuthoritiesMapper(buildAuthoritiesMapper(useGroupSearch));
        return provider;
    }

    private GrantedAuthoritiesMapper buildAuthoritiesMapper(boolean useGroupSearch) {
        Map<String, String> roleMappings = securityProperties.getLdap().getRoleMappings();
        Map<String, String> valueToRole = new HashMap<>();
        for (Map.Entry<String, String> entry : roleMappings.entrySet()) {
            // group search: match against the CN extracted from the group DN (e.g. "KAFKA-ADMINS")
            // attribute mode: match against the raw attribute value (e.g. "KAFKA_ADMIN")
            String key = useGroupSearch
                    ? extractCn(entry.getValue()).toUpperCase()
                    : entry.getValue().toUpperCase();
            valueToRole.put(key, entry.getKey());
        }

        String defaultRole = securityProperties.getLdap().getDefaultRole();

        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>();
            if (StringUtils.hasText(defaultRole)) {
                mapped.add(new SimpleGrantedAuthority("ROLE_" + defaultRole.toUpperCase()));
            }
            for (GrantedAuthority authority : authorities) {
                String auth = authority.getAuthority();
                String groupName = auth.startsWith("ROLE_") ? auth.substring(5) : auth;
                String roleName = valueToRole.get(groupName);
                mapped.add(roleName != null
                        ? new SimpleGrantedAuthority("ROLE_" + roleName)
                        : authority);
            }
            return mapped;
        };
    }

    private String extractCn(String dn) {
        if (dn == null) return "";
        String[] parts = dn.split(",");
        for (String part : parts) {
            if (part.trim().startsWith("cn=")) {
                return part.trim().substring(3);
            }
        }
        return "";
    }
}
