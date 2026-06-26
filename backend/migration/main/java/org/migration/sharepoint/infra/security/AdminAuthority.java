package org.migration.sharepoint.infra.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class AdminAuthority {

    static final String ROLE_ADMIN = "ROLE_ADMIN";

    private AdminAuthority() {}

    static boolean hasAdminRole(List<String> roles) {
        return roles != null
                && roles.stream().map(AdminAuthority::normalizeRole).anyMatch(ROLE_ADMIN::equals);
    }

    static List<SimpleGrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of(new SimpleGrantedAuthority(ROLE_ADMIN));
        }

        return roles.stream()
                .map(AdminAuthority::normalizeRole)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }

        String trimmedRole = role.trim();
        return trimmedRole.startsWith("ROLE_") ? trimmedRole : "ROLE_" + trimmedRole;
    }
}
