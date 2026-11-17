package com.erp.ticketing.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup fail-fast: the context must refuse to start without the settings needed to accept
 * identity tokens, and must name the missing variables without echoing any configured value.
 */
class RequiredSecurityConfigValidatorTest {

    // Placeholders, not real key material: the validator only checks presence.
    private static final String KEY = "app.security.jwt.public-key=placeholder-public-key";
    private static final String SECRET = "app.security.internal-service-secret=placeholder-secret-value";
    private static final String URL = "app.security.revocation.identity-url=http://identity-service:8081";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RequiredSecurityConfigValidator.class);

    @Test
    void failsToStartAndNamesEveryMissingVariable() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_PUBLIC_KEY")
                    .hasMessageContaining("INTERNAL_SERVICE_SECRET")
                    .hasMessageContaining("IDENTITY_SERVICE_URL");
        });
    }

    @Test
    void namesOnlyTheMissingVariableAndNeverAConfiguredValue() {
        runner.withPropertyValues(KEY, SECRET).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("IDENTITY_SERVICE_URL")
                    .hasMessageNotContaining("JWT_PUBLIC_KEY")
                    .hasMessageNotContaining("placeholder-secret-value")
                    .hasMessageNotContaining("placeholder-public-key");
        });
    }

    @Test
    void blankValuesCountAsMissing() {
        runner.withPropertyValues("app.security.jwt.public-key=  ", SECRET, URL).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("JWT_PUBLIC_KEY");
        });
    }

    @Test
    void startsWhenAllRequiredVariablesAreSet() {
        runner.withPropertyValues(KEY, SECRET, URL).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devAndTestProfilesCanSwitchTheCheckOff() {
        runner.withPropertyValues("app.security.startup-check.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
