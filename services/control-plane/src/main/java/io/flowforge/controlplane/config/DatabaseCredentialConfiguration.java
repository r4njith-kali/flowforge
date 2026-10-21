package io.flowforge.controlplane.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class DatabaseCredentialConfiguration {

    @Bean
    static BeanFactoryPostProcessor validateDatabaseCredentials(Environment environment) {
        return beanFactory -> {
            requireNonBlank(environment.getProperty("spring.datasource.username"), "DB_USERNAME");
            requireNonBlank(environment.getProperty("spring.datasource.password"), "DB_PASSWORD");
        };
    }

    private static void requireNonBlank(@Nullable String value, String variableName) {
        if (value == null || !StringUtils.hasText(value)) {
            throw new IllegalStateException("Required configuration property '" + variableName + "' must be set.");
        }
    }
}
