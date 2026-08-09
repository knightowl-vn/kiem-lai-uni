package com.universe.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;

@Configuration
public class SecurityBeanConfig {

    private static final int REMEMBER_ME_VALIDITY_SECONDS =
            14 * 24 * 60 * 60;

    private final GoogleOAuthSuccessHandler
            googleOAuthSuccessHandler;

    private final AccountStatusFilter
            accountStatusFilter;

    private final CustomAuthenticationFailureHandler
            authenticationFailureHandler;

    private final String rememberMeKey;

    private final boolean secureCookie;

    public SecurityBeanConfig(
            GoogleOAuthSuccessHandler googleOAuthSuccessHandler,
            AccountStatusFilter accountStatusFilter,
            CustomAuthenticationFailureHandler authenticationFailureHandler,

            @Value("${security.remember-me.key}")
            String rememberMeKey,

            @Value("${security.remember-me.secure-cookie:false}")
            boolean secureCookie
    ) {
        this.googleOAuthSuccessHandler =
                googleOAuthSuccessHandler;

        this.accountStatusFilter =
                accountStatusFilter;

        this.authenticationFailureHandler =
                authenticationFailureHandler;

        this.rememberMeKey =
                rememberMeKey;

        this.secureCookie =
                secureCookie;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/home",
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/reset-password",

                                "/oauth2/**",
                                "/login/oauth2/**",

                                "/api/auth/register",
                                "/access-denied",

                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/error",
                                "/wiki",
                                "/wiki/**"
                                
                        )
                        .permitAll()

                        .requestMatchers("/admin/**")
                        .hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        .anyRequest()
                        .authenticated()
                )

                /*
                 * Kiểm tra trạng thái tài khoản trong database
                 * ở mỗi request đã đăng nhập.
                 */
                .addFilterBefore(
                        accountStatusFilter,
                        AuthorizationFilter.class
                )

                .exceptionHandling(exception -> exception
                        .accessDeniedHandler(
                                (request, response, exceptionThrown) ->
                                        response.sendRedirect(
                                                request.getContextPath()
                                                        + "/access-denied"
                                        )
                        )
                )

                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/api/auth/register"
                        )
                )

                /*
                 * Đăng nhập bằng email và mật khẩu.
                 *
                 * Failure handler sẽ phân biệt:
                 * - sai mật khẩu
                 * - tài khoản bị khóa
                 * - tài khoản chưa kích hoạt
                 */
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/home", false)
                        .failureHandler(
                                authenticationFailureHandler
                        )
                        .permitAll()
                )

                .rememberMe(remember -> remember
                        .key(rememberMeKey)
                        .rememberMeParameter("remember-me")
                        .rememberMeCookieName("remember-me")
                        .tokenValiditySeconds(
                                REMEMBER_ME_VALIDITY_SECONDS
                        )
                        .useSecureCookie(secureCookie)
                        .alwaysRemember(false)
                )

                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(
                                googleOAuthSuccessHandler
                        )
                        .failureUrl("/login?oauthError")
                )

                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(
                                "JSESSIONID",
                                "remember-me"
                        )
                        .permitAll()
                );

        return http.build();
    }
}