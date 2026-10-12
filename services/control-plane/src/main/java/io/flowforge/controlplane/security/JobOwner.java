package io.flowforge.controlplane.security;

import org.springframework.security.oauth2.jwt.Jwt;

public record JobOwner(String issuer, String subject) {

    public JobOwner {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A validated issuer and subject are required");
        }
    }

    public static JobOwner from(Jwt jwt) {
        return new JobOwner(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
