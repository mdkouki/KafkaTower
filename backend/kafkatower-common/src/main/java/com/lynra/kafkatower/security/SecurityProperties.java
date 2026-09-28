package com.lynra.kafkatower.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private String mode = "jaas"; // jaas | ldap | oidc
    private List<ApiClient> apiClients = new ArrayList<>();

    private JaasConfig jaas = new JaasConfig();
    private LdapConfig ldap = new LdapConfig();
    private OidcConfig oidc = new OidcConfig();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<ApiClient> getApiClients() {
        return apiClients;
    }

    public void setApiClients(List<ApiClient> apiClients) {
        this.apiClients = apiClients;
    }

    public JaasConfig getJaas() {
        return jaas;
    }

    public void setJaas(JaasConfig jaas) {
        this.jaas = jaas;
    }

    public LdapConfig getLdap() {
        return ldap;
    }

    public void setLdap(LdapConfig ldap) {
        this.ldap = ldap;
    }

    public OidcConfig getOidc() {
        return oidc;
    }

    public void setOidc(OidcConfig oidc) {
        this.oidc = oidc;
    }

    public static class JaasConfig {
        private List<User> users;

        public List<User> getUsers() {
            return users;
        }

        public void setUsers(List<User> users) {
            this.users = users;
        }

        public static class User {
            private String username;
            private String password;
            private String role;

            public User() {
            }

            public User(String username, String password, String role) {
                this.username = username;
                this.password = password;
                this.role = role;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getRole() {
                return role;
            }

            public void setRole(String role) {
                this.role = role;
            }
        }
    }

    public static class LdapConfig {
        private String url;
        private String base;
        private String userDn;
        private String password;
        private String userSearchBase;
        private String userSearchFilter;
        private String groupSearchBase;
        private String groupSearchFilter;
        private String userRoleAttribute = "cudgroup";
        private boolean sslNoVerify = false;
        private String defaultRole;
        private Map<String, String> roleMappings = new HashMap<>();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getBase() {
            return base;
        }

        public void setBase(String base) {
            this.base = base;
        }

        public void setUserDn(String userDn) {
            this.userDn = userDn;
        }

        public String getUserDn() {
            return userDn;
        }

        public void setPassword(String password) { this.password = password; }

        public String getPassword() { return password; }

        public String getUserSearchBase() {
            return userSearchBase;
        }

        public void setUserSearchBase(String userSearchBase) {
            this.userSearchBase = userSearchBase;
        }

        public String getUserSearchFilter() {
            return userSearchFilter;
        }

        public void setUserSearchFilter(String userSearchFilter) {
            this.userSearchFilter = userSearchFilter;
        }

        public String getGroupSearchBase() {
            return groupSearchBase;
        }

        public void setGroupSearchBase(String groupSearchBase) {
            this.groupSearchBase = groupSearchBase;
        }

        public String getGroupSearchFilter() {
            return groupSearchFilter;
        }

        public void setGroupSearchFilter(String groupSearchFilter) {
            this.groupSearchFilter = groupSearchFilter;
        }

        public String getUserRoleAttribute() {
            return userRoleAttribute;
        }

        public void setUserRoleAttribute(String userRoleAttribute) {
            this.userRoleAttribute = userRoleAttribute;
        }

        public boolean isSslNoVerify() {
            return sslNoVerify;
        }

        public void setSslNoVerify(boolean sslNoVerify) {
            this.sslNoVerify = sslNoVerify;
        }

        public String getDefaultRole() {
            return defaultRole;
        }

        public void setDefaultRole(String defaultRole) {
            this.defaultRole = defaultRole;
        }

        public Map<String, String> getRoleMappings() {
            return roleMappings;
        }

        public void setRoleMappings(Map<String, String> roleMappings) {
            this.roleMappings = roleMappings;
        }


    }

    public static class OidcConfig {
        private String issuerUri;
        private String clientId;
        private String clientSecret;
        private String adminClaim;
        private String adminGroupValue;
        private String userGroupValue;

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getAdminClaim() {
            return adminClaim;
        }

        public void setAdminClaim(String adminClaim) {
            this.adminClaim = adminClaim;
        }

        public String getAdminGroupValue() {
            return adminGroupValue;
        }

        public void setAdminGroupValue(String adminGroupValue) {
            this.adminGroupValue = adminGroupValue;
        }

        public String getUserGroupValue() {
            return userGroupValue;
        }

        public void setUserGroupValue(String userGroupValue) {
            this.userGroupValue = userGroupValue;
        }
    }

    public static class ApiClient {
        private String name;
        private String token;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
