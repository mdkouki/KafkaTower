package com.lynra.kafkatower.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.util.StringUtils;

import java.util.Map;

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
            contextSource.setBaseEnvironmentProperties(Map.of(
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

        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(authenticator,
                useGroupSearch ? groupPopulator(contextSource) : LdapAuthorities.fromUserAttribute(
                        securityProperties.getLdap().getUserRoleAttribute()));

        provider.setAuthoritiesMapper(LdapAuthorities.mapper(
                securityProperties.getLdap().getRoleMappings(),
                securityProperties.getLdap().getDefaultRole(),
                useGroupSearch));
        return provider;
    }

    private DefaultLdapAuthoritiesPopulator groupPopulator(LdapContextSource contextSource) {
        DefaultLdapAuthoritiesPopulator groupPopulator = new DefaultLdapAuthoritiesPopulator(
                contextSource, securityProperties.getLdap().getGroupSearchBase());
        if (StringUtils.hasText(securityProperties.getLdap().getGroupSearchFilter())) {
            groupPopulator.setGroupSearchFilter(securityProperties.getLdap().getGroupSearchFilter());
        }
        groupPopulator.setGroupRoleAttribute("cn");
        groupPopulator.setRolePrefix(LdapAuthorities.ROLE_PREFIX);
        return groupPopulator;
    }
}
