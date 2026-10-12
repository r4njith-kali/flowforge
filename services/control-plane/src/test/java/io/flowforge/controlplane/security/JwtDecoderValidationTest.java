package io.flowforge.controlplane.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.flowforge.controlplane.config.SecurityConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderValidationTest {

    private static final String ISSUER = "https://identity.flowforge.test/realms/flowforge";
    private static final String AUDIENCE = "flowforge-api";
    private static final Instant NOW = Instant.now();
    private static KeyPair trustedKey;
    private static KeyPair otherKey;
    private static HttpServer jwksServer;
    private static JwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        trustedKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        otherKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic())
                .keyID("test-key")
                .build();
        byte[] jwks = new JWKSet(publicJwk).toString().getBytes(StandardCharsets.UTF_8);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        jwksServer.start();
        decoder = new SecurityConfiguration().jwtDecoder(
                "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks",
                ISSUER,
                AUDIENCE);
    }

    @AfterAll
    static void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void acceptsSignedTokenWithExpectedIssuerAudienceAndLifetime() throws Exception {
        Jwt jwt = decoder.decode(token(trustedKey, ISSUER, AUDIENCE, NOW.plusSeconds(300)));

        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        assertInvalid(token(otherKey, ISSUER, AUDIENCE, NOW.plusSeconds(300)));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertInvalid(token(trustedKey, ISSUER, AUDIENCE, NOW.minusSeconds(1)));
    }

    @Test
    void rejectsIncorrectIssuer() throws Exception {
        assertInvalid(token(trustedKey, "https://attacker.invalid/realm", AUDIENCE, NOW.plusSeconds(300)));
    }

    @Test
    void rejectsIncorrectAudience() throws Exception {
        assertInvalid(token(trustedKey, ISSUER, "another-api", NOW.plusSeconds(300)));
    }

    @Test
    void rejectsMissingSubject() throws Exception {
        assertInvalid(token(trustedKey, ISSUER, AUDIENCE, NOW.plusSeconds(300), null));
    }

    @Test
    void rejectsBlankSubject() throws Exception {
        assertInvalid(token(trustedKey, ISSUER, AUDIENCE, NOW.plusSeconds(300), " "));
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static String token(
            KeyPair signingKey,
            String issuer,
            String audience,
            Instant expiresAt) throws JOSEException {
        return token(signingKey, issuer, audience, expiresAt, "user-1");
    }

    private static String token(
            KeyPair signingKey,
            String issuer,
            String audience,
            Instant expiresAt,
            String subject) throws JOSEException {
        Instant issuedAt = NOW.minusSeconds(1);
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(issuer);
        if (subject != null) {
            claimsBuilder.subject(subject);
        }
        JWTClaimsSet claims = claimsBuilder
                .audience(List.of(audience))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(
                        signingKey == trustedKey ? "test-key" : "other-key").build(),
                claims);
        signed.sign(new RSASSASigner((RSAPrivateKey) signingKey.getPrivate()));
        return signed.serialize();
    }
}
