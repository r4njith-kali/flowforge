package io.flowforge.controlplane.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Set<String> PERMITTED_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccessClaim = jwt.getClaims().get("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(PERMITTED_ROLES::contains)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}
