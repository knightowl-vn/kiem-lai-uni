package com.universe.configuration.performance;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("perf")
@ConditionalOnProperty(
        prefix = "kiemlai.performance.instrumentation",
        name = "enabled",
        havingValue = "true"
)
public class PerformanceInstrumentationConfiguration {

    @Bean
    static BeanPostProcessor performanceDataSourceBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName)
                        && bean instanceof DataSource dataSource
                        && !(dataSource instanceof PerformanceDataSource)) {
                    return new PerformanceDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    @Bean
    FilterRegistrationBean<PerformanceServerTimingFilter> performanceServerTimingFilterRegistration() {
        FilterRegistrationBean<PerformanceServerTimingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PerformanceServerTimingFilter());
        registration.setName("performanceServerTimingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        return registration;
    }
}
