package com.universe.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.BrowserNavigationRequestMatcher;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.FormLoginAuthenticationSuccessHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.OAuth2AuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.OAuth2ReturnToStore;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
import com.universe.identity.infrastructure.security.StateCorrelationOAuth2AuthorizationRequestRepository;

@Configuration
public class SecurityBeanConfig {

    private static final int REMEMBER_ME_VALIDITY_SECONDS =
            14 * 24 * 60 * 60;

    private final AccountStatusFilter
            accountStatusFilter;

    private final String rememberMeKey;

    private final boolean secureCookie;

    public SecurityBeanConfig(
            AccountStatusFilter accountStatusFilter,

            @Value("${security.remember-me.key}")
            String rememberMeKey,

            @Value("${security.remember-me.secure-cookie:false}")
            boolean secureCookie
    ) {
        this.accountStatusFilter =
                accountStatusFilter;

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
    public SafeReturnToValidator safeReturnToValidator() {
        return new SafeReturnToValidator();
    }

    @Bean
    public RequestMatcher browserNavigationRequestMatcher() {
        return new BrowserNavigationRequestMatcher();
    }

    @Bean
    public RequestCache requestCache(RequestMatcher browserNavigationRequestMatcher) {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(browserNavigationRequestMatcher);
        return requestCache;
    }

    @Bean
    public OAuth2ReturnToStore oAuth2ReturnToStore() {
        return new OAuth2ReturnToStore();
    }

    @Bean
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest> oAuth2AuthorizationRequestRepository(
            OAuth2ReturnToStore oAuth2ReturnToStore,
            SafeReturnToValidator safeReturnToValidator
    ) {
        return new StateCorrelationOAuth2AuthorizationRequestRepository(
                oAuth2ReturnToStore,
                safeReturnToValidator
        );
    }

    @Bean
    public OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler(
            OAuth2ReturnToStore oAuth2ReturnToStore,
            SafeReturnToValidator safeReturnToValidator
    ) {
        return new OAuth2AuthenticationFailureHandler(
                oAuth2ReturnToStore,
                safeReturnToValidator
        );
    }

    @Bean
    public FormLoginAuthenticationSuccessHandler formLoginAuthenticationSuccessHandler(
            RequestCache requestCache,
            SafeReturnToValidator safeReturnToValidator
    ) {
        return new FormLoginAuthenticationSuccessHandler(requestCache, safeReturnToValidator);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RequestCache requestCache,
            FormLoginAuthenticationSuccessHandler formLoginAuthenticationSuccessHandler,
            CustomAuthenticationFailureHandler authenticationFailureHandler,
            GoogleOAuthSuccessHandler googleOAuthSuccessHandler,
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> oAuth2AuthorizationRequestRepository,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler
    ) throws Exception {

        http
                .requestCache(cache -> cache
                        .requestCache(requestCache)
                )
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
                                "/error"
                        )
                        .permitAll()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.HEAD,
                                "/media/assets/*/content"
                        )
                        .permitAll()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.GET,
                                "/media/assets/*/content",
                                "/media/assets/*/variants/*",
                                "/api/novel/narration/voices",
                                "/api/novel/chapters/*/narration/playback",
                                "/api/novel/chapters/*/comments",
                                "/api/novel/chapters/*/comments/feed",
                                "/api/novel/chapters/*/comments/*/thread",
                                "/api/novel/chapters/*/comments/*/revisions",
                                "/api/novel/chapters/*/comments/indicators",
                                "/api/novel/chapters/*/comments/blocks/*"
                        )
                        .permitAll()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,
                                "/api/novel/chapters/*/narration/prepare"
                        )
                        .permitAll()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,
                                "/api/novel/chapters/*/comments",
                                "/api/novel/chapters/*/comments/inline",
                                "/api/novel/chapters/*/comments/*/replies"
                        )
                        .authenticated()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.PATCH,
                                "/api/novel/chapters/*/comments/*"
                        )
                        .authenticated()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.DELETE,
                                "/api/novel/chapters/*/comments/*"
                        )
                        .authenticated()

                        .requestMatchers(
                                "/novel/bookmarks",
                                "/novel/bookmarks/**",
                                "/novel/history",
                                "/novel/history/**",
                                "/novel/chapters/*/bookmark",
                                "/novel/chapters/*/history",
                                "/novel/chapters/*/progress"
                        )
                        .authenticated()

                        .requestMatchers(
                                "/novel",
                                "/novel/**"
                        )
                        .permitAll()

                        .requestMatchers(
                                "/wiki/articles/*/save",
                                "/wiki/saved",
                                "/wiki/saved/**"
                        )
                        .authenticated()

                        .requestMatchers(
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
                        .successHandler(
                                formLoginAuthenticationSuccessHandler
                        )
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
                        .authorizationEndpoint(auth -> auth
                                .authorizationRequestRepository(
                                        oAuth2AuthorizationRequestRepository
                                )
                        )
                        .successHandler(
                                googleOAuthSuccessHandler
                        )
                        .failureHandler(
                                oAuth2AuthenticationFailureHandler
                        )
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
