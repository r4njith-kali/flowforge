package io.flowforge.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.security.AudienceValidator;
import io.flowforge.controlplane.security.KeycloakRoleConverter;
import io.flowforge.controlplane.security.SubjectValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${flowforge.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience),
                new SubjectValidator()));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/jobs", "/api/v1/jobs/**")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/jobs/*/cancel")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityProblem(
                                        objectMapper,
                                        request.getRequestURI(),
                                        response,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED",
                                        "Authentication is required to access this resource"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityProblem(
                                        objectMapper,
                                        request.getRequestURI(),
                                        response,
                                        HttpStatus.FORBIDDEN,
                                        "FORBIDDEN",
                                        "You do not have permission to access this resource")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityProblem(
                                        objectMapper,
                                        request.getRequestURI(),
                                        response,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED",
                                        "Authentication is required to access this resource"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityProblem(
                                        objectMapper,
                                        request.getRequestURI(),
                                        response,
                                        HttpStatus.FORBIDDEN,
                                        "FORBIDDEN",
                                        "You do not have permission to access this resource")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=()")))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${flowforge.security.cors.allowed-origin}") String allowedOrigin) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Location", "WWW-Authenticate"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/health/**", configuration);
        return source;
    }

    private static void writeSecurityProblem(
            ObjectMapper objectMapper,
            String path,
            jakarta.servlet.http.HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail) throws java.io.IOException {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(java.net.URI.create(path));
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
