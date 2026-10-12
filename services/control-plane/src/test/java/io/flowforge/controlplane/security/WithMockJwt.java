package io.flowforge.controlplane.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockJwt.Factory.class)
public @interface WithMockJwt {

    String subject() default "operator-1";

    String role() default "OPERATOR";

    final class Factory implements WithSecurityContextFactory<WithMockJwt> {

        @Override
        public SecurityContext createSecurityContext(WithMockJwt annotation) {
            Instant now = Instant.now();
            Jwt jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer("https://identity.flowforge.test/realms/flowforge")
                    .subject(annotation.subject())
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claim("realm_access", Map.of("roles", List.of(annotation.role())))
                    .build();
            List<GrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_" + annotation.role()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
            return context;
        }
    }
}
