package io.flowforge.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseCredentialConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseCredentialConfiguration.class);

    @Test
    void rejectsMissingDatabaseUsername() {
        contextRunner.withPropertyValues("spring.datasource.password=test-password")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnresolvedDatabaseUsernamePlaceholder() {
        contextRunner.withPropertyValues(
                        "spring.datasource.username=${DB_USERNAME}",
                        "spring.datasource.password=test-password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DB_USERNAME");
                });
    }

    @Test
    void rejectsBlankDatabaseUsername() {
        contextRunner.withPropertyValues(
                        "spring.datasource.username= ",
                        "spring.datasource.password=test-password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Required configuration property 'DB_USERNAME' must be set.");
                });
    }

    @Test
    void rejectsBlankDatabasePassword() {
        contextRunner.withPropertyValues(
                        "spring.datasource.username=test-user",
                        "spring.datasource.password= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DB_PASSWORD");
                });
    }

    @Test
    void rejectsMissingDatabasePassword() {
        contextRunner.withPropertyValues("spring.datasource.username=test-user")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnresolvedDatabasePasswordPlaceholder() {
        contextRunner.withPropertyValues(
                        "spring.datasource.username=test-user",
                        "spring.datasource.password=${DB_PASSWORD}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DB_PASSWORD");
                });
    }

    @Test
    void acceptsConfiguredDatabaseCredentials() {
        contextRunner.withPropertyValues(
                        "spring.datasource.username=test-user",
                        "spring.datasource.password=test-password")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
