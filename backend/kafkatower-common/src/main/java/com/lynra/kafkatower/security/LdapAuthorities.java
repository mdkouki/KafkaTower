package com.lynra.kafkatower.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class LdapAuthorities {

    static final String ROLE_PREFIX = "ROLE_";
    private static final String CN_PREFIX = "cn=";

    private LdapAuthorities() {
    }

    static LdapAuthoritiesPopulator fromUserAttribute(String roleAttribute) {
        return (userData, username) -> {
            String[] values = userData.getStringAttributes(roleAttribute);
            if (values == null) return Collections.emptyList();
            return Arrays.stream(values)
                    .<GrantedAuthority>map(value -> new SimpleGrantedAuthority(ROLE_PREFIX + value.toUpperCase()))
                    .toList();
        };
    }

    /**
     * @param roleMappings  application role (key) to LDAP group DN or attribute value (value)
     * @param matchGroupCn  true when authorities come from a group search (match the CN of the
     *                      group DN), false when they come from a user attribute (match the raw value)
     */
    static GrantedAuthoritiesMapper mapper(Map<String, String> roleMappings, String defaultRole, boolean matchGroupCn) {
        Map<String, String> roleByLdapValue = new HashMap<>();
        for (Map.Entry<String, String> entry : roleMappings.entrySet()) {
            String ldapValue = matchGroupCn ? extractCn(entry.getValue()) : entry.getValue();
            roleByLdapValue.put(ldapValue.toUpperCase(), entry.getKey());
        }

        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>();
            if (StringUtils.hasText(defaultRole)) {
                mapped.add(new SimpleGrantedAuthority(ROLE_PREFIX + defaultRole.toUpperCase()));
            }
            for (GrantedAuthority authority : authorities) {
                String ldapValue = authority.getAuthority().replaceFirst("^" + ROLE_PREFIX, "");
                String role = roleByLdapValue.get(ldapValue);
                mapped.add(role != null ? new SimpleGrantedAuthority(ROLE_PREFIX + role) : authority);
            }
            return mapped;
        };
    }

    static String extractCn(String dn) {
        if (dn == null) return "";
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(CN_PREFIX)) {
                return trimmed.substring(CN_PREFIX.length());
            }
        }
        return "";
    }
}
