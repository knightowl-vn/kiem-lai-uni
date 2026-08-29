package com.universe.novel.entry.admin;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceListPageDTO;
import com.universe.novel.application.chapter.reference.ListChapterWikiReferencesUseCase;
import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminNovelChapterWikiReferencePageController.class)
@Import({SecurityBeanConfig.class, AdminNovelChapterWikiReferenceSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminNovelChapterWikiReferenceSecurityIntegrationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";

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
    private GetChapterDetailUseCase getChapterDetailUseCase;

    @MockBean
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    @MockBean
    private ListChapterWikiReferencesUseCase listChapterWikiReferencesUseCase;

    @MockBean
    private NovelMarkdownRenderer novelMarkdownRenderer;

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
        regularUserEntity.setEmail(USER_EMAIL);
        regularUserEntity.setStatus("ACTIVE");
        regularUserEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(regularUserEntity));
    }

    @Test
    @DisplayName("Security: Anonymous GET /admin/novel/chapters/{id}/wiki-references redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references", CHAPTER_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER GET /admin/novel/chapters/{id}/wiki-references is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references", CHAPTER_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN GET /admin/novel/chapters/{id}/wiki-references returns 200 OK and renders template")
    void shouldAllowAdminAccess() throws Exception {
        ChapterDTO chapter = new ChapterDTO(
                CHAPTER_ID, VOLUME_ID, 1, "Khởi Đầu", "chuong-1", "Tóm tắt", "Nội dung",
                "PUBLISHED", ADMIN_ID, ADMIN_ID, ADMIN_ID, null,
                Instant.now(), Instant.now(), Instant.now(), null, 1L, 1L
        );
        VolumeDTO volume = new VolumeDTO(
                VOLUME_ID, "Quyển 1", "quyen-1", "Tóm tắt", 1, "PUBLISHED",
                ADMIN_ID, ADMIN_ID, ADMIN_ID, null,
                Instant.now(), Instant.now(), Instant.now(), null, 1L
        );
        ChapterWikiReferenceListPageDTO pageResult = new ChapterWikiReferenceListPageDTO(
                CHAPTER_ID, "Khởi Đầu", 1, 1L, List.of(), 0, 0, 0
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(listChapterWikiReferencesUseCase.execute(CHAPTER_ID)).thenReturn(pageResult);
        when(novelMarkdownRenderer.renderToHtml(chapter.content())).thenReturn("<p>Nội dung</p>");

        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references", CHAPTER_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/novel/chapter-wiki-references"))
                .andExpect(model().attributeExists("chapter", "volume", "pageResult", "referenceItems", "contentHtml"));
    }
}
