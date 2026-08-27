package com.universe.novel.entry.admin;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionCommand;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminNovelChapterRevisionCommandController.class)
@Import({SecurityBeanConfig.class, AdminNovelChapterRevisionSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminNovelChapterRevisionSecurityIntegrationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String ADMIN_EMAIL = "admin@universe.local";

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler();
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(userRepository);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private RestoreChapterRevisionUseCase restoreChapterRevisionUseCase;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(ADMIN_ID.toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        UserJpaEntity regularUserEntity = new UserJpaEntity();
        regularUserEntity.setId(UUID.randomUUID().toString());
        regularUserEntity.setEmail("user@universe.local");
        regularUserEntity.setStatus("ACTIVE");
        regularUserEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        when(springDataUserJpaRepository.findByEmail("user@universe.local")).thenReturn(Optional.of(regularUserEntity));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: POST restore với Admin authenticated + CSRF token hợp lệ được phép thực thi")
    void shouldAllowRestoreWhenAdminWithValidCsrf() throws Exception {
        UserDTO user = new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "ACTIVE", "ADMIN", Instant.now());
        when(authenticatedEmailResolver.require(any())).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        ChapterDTO restored = new ChapterDTO(
                CHAPTER_ID, VOLUME_ID, 1, "Chương 1", "chuong-1", "Tóm tắt", "Nội dung",
                "DRAFT", ADMIN_ID, ADMIN_ID, null, null, Instant.now(), Instant.now(), null, null, 2L, 2L
        );
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class))).thenReturn(restored);

        mockMvc.perform(
                post("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 1L)
                        .with(csrf())
                        .param("expectedAggregateVersion", "1")
                        .param("editSummary", "Khôi phục test")
        )
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: POST restore thiếu CSRF token bị AccessDeniedHandler từ chối và chuyển hướng /access-denied")
    void shouldRejectRestoreWhenMissingCsrf() throws Exception {
        mockMvc.perform(
                post("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 1L)
                        .param("expectedAggregateVersion", "1")
        )
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: Request ẩn danh unauthenticated bị chuyển hướng về trang /login")
    void shouldRedirectToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(
                post("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 1L)
                        .with(csrf())
                        .param("expectedAggregateVersion", "1")
        )
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "user@universe.local", roles = {"USER"})
    @DisplayName("Security: Request từ user không có quyền ADMIN bị chuyển hướng /access-denied")
    void shouldDenyAccessWhenUserHasNoAdminRole() throws Exception {
        mockMvc.perform(
                post("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 1L)
                        .with(csrf())
                        .param("expectedAggregateVersion", "1")
        )
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }
}
