package com.universe.configuration.performance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PerformanceInstrumentationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PerformanceInstrumentationConfiguration.class);

    @Test
    void instrumentationIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("performanceDataSourceBeanPostProcessor");
            assertThat(context).doesNotHaveBean("performanceServerTimingFilterRegistration");
        });
    }

    @Test
    void propertyAloneDoesNotEnableInstrumentationOutsideThePerfProfile() {
        contextRunner
                .withPropertyValues("kiemlai.performance.instrumentation.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("performanceDataSourceBeanPostProcessor");
                    assertThat(context).doesNotHaveBean("performanceServerTimingFilterRegistration");
                });
    }

    @Test
    void instrumentationIsRegisteredOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("perf"))
                .withPropertyValues("kiemlai.performance.instrumentation.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("performanceDataSourceBeanPostProcessor");
                    assertThat(context).hasBean("performanceServerTimingFilterRegistration");

                    FilterRegistrationBean<?> registration = context.getBean(
                            "performanceServerTimingFilterRegistration",
                            FilterRegistrationBean.class
                    );
                    assertThat(registration.getFilter()).isInstanceOf(PerformanceServerTimingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);
                    assertThat(registration.isAsyncSupported()).isTrue();
                });
    }

    @Test
    void enabledInstrumentationWrapsTheApplicationDataSource() {
        DataSource delegate = mock(DataSource.class);

        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("perf"))
                .withPropertyValues("kiemlai.performance.instrumentation.enabled=true")
                .withBean("dataSource", DataSource.class, () -> delegate)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataSource.class))
                            .isInstanceOf(PerformanceDataSource.class)
                            .isNotSameAs(delegate);
                });
    }
}
