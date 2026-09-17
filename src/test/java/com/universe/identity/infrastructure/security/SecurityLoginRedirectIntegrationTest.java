package com.universe.identity.infrastructure.security;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.entry.web.IdentityPageController;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.application.reader.IsChapterBookmarkedUseCase;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;
import com.universe.novel.entry.admin.AdminNovelVolumePageController;
import com.universe.novel.entry.reader.PublicNovelExceptionHandler;
import com.universe.novel.entry.reader.ReaderChapterPageController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {
        IdentityPageController.class,
        ReaderChapterPageController.class,
        AdminNovelVolumePageController.class
})
@Import({
        SecurityBeanConfig.class,
        CustomAuthenticationFailureHandler.class,
        PublicNovelExceptionHandler.class
})
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
class SecurityLoginRedirectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private GetReaderChapterDetailUseCase getReaderChapterDetailUseCase;

    @MockBean
    private IsChapterBookmarkedUseCase isChapterBookmarkedUseCase;

    @MockBean
    private GetVolumeListUseCase getVolumeListUseCase;

    @MockBean
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    @MockBean
    private com.universe.identity.application.ports.CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private com.universe.shared.security.AuthenticatedEmailResolver authenticatedEmailResolver;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());

        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(
                User.withUsername("user@example.com")
                        .password(passwordEncoder.encode("correct-password"))
                        .roles("USER")
                        .build()
        );
    }

    @Test
    @DisplayName("anonymous chapter page renders discussion login link with canonical returnTo")
    void anonymousChapterPageRendersLoginLinkWithReturnTo() throws Exception {
        ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(UUID.randomUUID(), "Quyển 1", "quyen-1", 1);
        ReaderChapterDetailDTO chapter = new ReaderChapterDetailDTO(
                UUID.randomUUID(),
                1,
                "Khởi đầu",
                "quyen-1-chuong-1",
                "<p>Nội dung</p>",
                1L,
                volume,
                null,
                null,
                List.of()
        );
        when(getReaderChapterDetailUseCase.execute("quyen-1-chuong-1")).thenReturn(chapter);

        mockMvc.perform(get("/novel/chapters/quyen-1-chuong-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login?returnTo=/novel/chapters/quyen-1-chuong-1\"")));
    }

    @Test
    @DisplayName("GET /login with returnTo renders hidden returnTo input in form")
    void loginPagePreservesReturnToInForm() throws Exception {
        mockMvc.perform(get("/login").param("returnTo", "/novel/chapters/quyen-1-chuong-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<input type=\"hidden\" name=\"returnTo\" value=\"/novel/chapters/quyen-1-chuong-1\">")));
    }

    @Test
    @DisplayName("successful form login with valid returnTo redirects to the chapter")
    void successfulLoginWithReturnToRedirectsToChapter() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .param("returnTo", "/novel/chapters/quyen-1-chuong-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel/chapters/quyen-1-chuong-1"));
    }

    @Test
    @DisplayName("failed form login preserves valid returnTo in error redirect")
    void failedLoginPreservesReturnTo() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "wrong-password")
                        .param("returnTo", "/novel/chapters/quyen-1-chuong-1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1"));
    }

    @Test
    @DisplayName("direct form login without returnTo redirects to /home")
    void directLoginWithoutReturnToRedirectsToHome() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/home"));
    }

    @Test
    @DisplayName("unauthenticated .well-known probe does not become SavedRequest and cannot hijack next login")
    void wellKnownProbeCannotHijackNextLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // 1. Chrome DevTools background request occurs in session
        mockMvc.perform(get("/.well-known/appspecific/com.chrome.devtools.json").session(session))
                .andExpect(status().is3xxRedirection()); // Redirected to login by exception translation

        // 2. User logs in with form login without returnTo
        mockMvc.perform(post("/login")
                        .session(session)
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/home")); // Does NOT redirect to .well-known!
    }

    @Test
    @DisplayName("legitimate protected route SavedRequest is preserved when no returnTo is present")
    void legitimateProtectedSavedRequestIsPreserved() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // 1. Unauthenticated user navigates to protected admin route
        mockMvc.perform(get("/admin/novel/volumes").session(session).header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        // 2. User logs in
        mockMvc.perform(post("/login")
                        .session(session)
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://localhost/admin/novel/volumes*"));
    }

    @Test
    @DisplayName("SafeReturnToValidator and FormLoginAuthenticationSuccessHandler have single bean definitions")
    void verifySingleBeanOwnership() {
        assertThat(applicationContext.getBeansOfType(SafeReturnToValidator.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(FormLoginAuthenticationSuccessHandler.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OAuth2ReturnToStore.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(StateCorrelationOAuth2AuthorizationRequestRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OAuth2AuthenticationFailureHandler.class)).hasSize(1);
    }

    @Test
    @DisplayName("successful form login with valid wiki returnTo redirects to the wiki article")
    void successfulLoginWithWikiReturnToRedirectsToWiki() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .param("returnTo", "/wiki/character/tran-binh-an")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/character/tran-binh-an"));
    }

    @Test
    @DisplayName("failed form login preserves valid wiki returnTo in error redirect")
    void failedLoginPreservesWikiReturnTo() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "wrong-password")
                        .param("returnTo", "/wiki/character/tran-binh-an")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error&returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an"));
    }

    @Test
    @DisplayName("GET /login with complex returnTo renders register link preserving exact return target")
    void loginPagePreservesComplexReturnToInRegisterLink() throws Exception {
        String complexTarget = "/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1&intent=reply";
        mockMvc.perform(get("/login").param("returnTo", complexTarget))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/register?returnTo=/novel/chapters/quyen-1-chuong-10?discussionBlock%3Dblk-1%26intent%3Dreply\"")));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/register\"")));
    }

    @Test
    @DisplayName("GET /register with complex returnTo renders login link preserving exact return target")
    void registerPagePreservesComplexReturnToInLoginLink() throws Exception {
        String complexTarget = "/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1&intent=reply";
        mockMvc.perform(get("/register").param("returnTo", complexTarget))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login?returnTo=/novel/chapters/quyen-1-chuong-10?discussionBlock%3Dblk-1%26intent%3Dreply\"")));

        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login\"")));
    }

    @Test
    @DisplayName("successful form login with complex nested returnTo preserves all parameters in redirect")
    void successfulLoginWithComplexNestedReturnToRedirectsExactly() throws Exception {
        String complexTarget = "/novel/chapters/quyen-1-chuong-10"
                + "?discussionBlock=blk-a1b2c3d4e5f67890-3"
                + "&threadId=11111111-1111-1111-1111-111111111111"
                + "&replyTo=22222222-2222-2222-2222-222222222222"
                + "&intent=reply";

        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "correct-password")
                        .param("returnTo", complexTarget)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(complexTarget));
    }

    @Test
    @DisplayName("failed form login preserves complex nested returnTo properly encoded in error redirect")
    void failedLoginPreservesComplexNestedReturnTo() throws Exception {
        String complexTarget = "/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1&intent=reply";

        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "wrong-password")
                        .param("returnTo", complexTarget)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-10%3FdiscussionBlock%3Dblk-1%26intent%3Dreply"));
    }

    @Test
    @DisplayName("GET /login with returnTo renders Google OAuth link preserving returnTo")
    void loginPagePreservesReturnToInGoogleOAuthLink() throws Exception {
        String target = "/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1";
        mockMvc.perform(get("/login").param("returnTo", target))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google?returnTo=/novel/chapters/quyen-1-chuong-10?discussionBlock%3Dblk-1\"")));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")));
    }

    @Test
    @DisplayName("GET /register with returnTo renders Google OAuth link preserving returnTo")
    void registerPagePreservesReturnToInGoogleOAuthLink() throws Exception {
        String target = "/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1";
        mockMvc.perform(get("/register").param("returnTo", target))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google?returnTo=/novel/chapters/quyen-1-chuong-10?discussionBlock%3Dblk-1\"")));

        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")));
    }
}
