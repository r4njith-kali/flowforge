package io.flowforge.controlplane.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

    @Test
    void mapsOnlyKnownRealmRoles() {
        Jwt jwt = token(Map.of("realm_access", Map.of(
                "roles", List.of("ADMIN", "OPERATOR", "offline_access", "VIEWER"))));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
    }

    @Test
    void missingOrMalformedRoleClaimsGrantNoAuthorities() {
        assertThat(converter.convert(token(Map.of()))).isEmpty();
        assertThat(converter.convert(token(Map.of("realm_access", "ADMIN")))).isEmpty();
        assertThat(converter.convert(token(Map.of(
                "realm_access",
                Map.of("roles", List.of("ADMIN", 17, "SUPERUSER"))))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    private static Jwt token(Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("https://identity.flowforge.test/realms/flowforge")
                .subject("user-1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claims(values -> values.putAll(claims))
                .build();
    }
}
