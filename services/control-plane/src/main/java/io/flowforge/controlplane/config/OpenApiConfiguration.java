package io.flowforge.controlplane.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI flowForgeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FlowForge Control Plane API")
                .version("v1")
                .description("REST API for creating and managing durable workflow jobs."));
    }
}
