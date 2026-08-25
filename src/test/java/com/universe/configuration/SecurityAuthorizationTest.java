package com.universe.configuration;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.application.profile.UpdateNovelProfileUseCase;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.application.reader.GetReaderChapterListUseCase;
import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;
import com.universe.novel.entry.admin.AdminNovelProfileCommandController;
import com.universe.novel.entry.admin.AdminNovelProfilePageController;
import com.universe.novel.entry.admin.AdminNovelVolumePageController;
import com.universe.novel.entry.reader.PublicNovelExceptionHandler;
import com.universe.novel.entry.reader.ReaderChapterListFragmentController;
import com.universe.novel.entry.reader.ReaderChapterPageController;
import com.universe.novel.entry.reader.ReaderNovelPageController;
import com.universe.shared.security.AuthenticatedEmailResolver;

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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        ReaderNovelPageController.class,
        ReaderChapterListFragmentController.class,
        ReaderChapterPageController.class,
        AdminNovelVolumePageController.class,
        AdminNovelProfilePageController.class,
        AdminNovelProfileCommandController.class
})
@Import({
        SecurityBeanConfig.class,
        PublicNovelExceptionHandler.class
})
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private GetReaderNovelLandingUseCase getReaderNovelLandingUseCase;

    @MockBean
    private GetReaderChapterListUseCase getReaderChapterListUseCase;

    @MockBean
    private GetReaderChapterDetailUseCase getReaderChapterDetailUseCase;

    @MockBean
    private GetVolumeListUseCase getVolumeListUseCase;

    @MockBean
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    @MockBean
    private GetNovelProfileUseCase getNovelProfileUseCase;

    @MockBean
    private UpdateNovelProfileUseCase updateNovelProfileUseCase;

    @MockBean
    private com.universe.novel.application.reader.GetContinueReadingUseCase getContinueReadingUseCase;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private com.universe.identity.contracts.interfaces.UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập trang /novel")
    void shouldAllowAnonymousAccessToNovelLandingPage() throws Exception {
        ReaderNovelOverviewDTO novelOverview = new ReaderNovelOverviewDTO(
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả Kiếm Lai",
                null,
                "ONGOING"
        );

        when(getReaderNovelLandingUseCase.execute())
                .thenReturn(new ReaderNovelLandingDTO(novelOverview, List.of()));

        mockMvc.perform(get("/novel"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập endpoint /novel/** (fragment danh sách chương)")
    void shouldAllowAnonymousAccessToNovelVolumeChaptersEndpoint() throws Exception {
        UUID volumeId = UUID.randomUUID();

        when(getReaderChapterListUseCase.execute(volumeId))
                .thenReturn(List.of());

        mockMvc.perform(get("/novel/volumes/" + volumeId + "/chapters"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập trang đọc chương /novel/chapters/{slug}")
    void shouldAllowAnonymousAccessToNovelChapterReadingPage() throws Exception {
        String slug = "chuong-1-khoi-dau";

        ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(
                UUID.randomUUID(),
                "Quyển 1",
                "quyen-1",
                1
        );

        ReaderChapterDetailDTO chapter = new ReaderChapterDetailDTO(
                UUID.randomUUID(),
                1,
                "Khởi Đầu",
                slug,
                "<p>Nội dung chương</p>",
                volume,
                null,
                null,
                java.util.List.of()
        );

        when(getReaderChapterDetailUseCase.execute(slug))
                .thenReturn(chapter);

        mockMvc.perform(get("/novel/chapters/" + slug))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh truy cập chương không tồn tại nhận được HTTP 404 NOT_FOUND")
    void shouldReturn404ForAnonymousWhenChapterNotFound() throws Exception {
        when(getReaderChapterDetailUseCase.execute("chuong-khong-ton-tai"))
                .thenThrow(new ChapterNotFoundException("chuong-khong-ton-tai"));

        mockMvc.perform(get("/novel/chapters/chuong-khong-ton-tai"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /admin/novel/profile (chuyển hướng sang /login)")
    void shouldRedirectAnonymousUserWhenAccessingNovelProfile() throws Exception {
        mockMvc.perform(get("/admin/novel/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng với role USER bị từ chối truy cập /admin/novel/profile (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToNovelProfileForRegularUser() throws Exception {
        mockMvc.perform(get("/admin/novel/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên có role ADMIN được phép truy cập /admin/novel/profile")
    void shouldAllowAccessToNovelProfileForAdmin() throws Exception {
        NovelProfileDTO profile = new NovelProfileDTO(
                UUID.randomUUID(),
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                null,
                "ONGOING",
                Instant.now(),
                Instant.now()
        );

        when(getNovelProfileUseCase.execute())
                .thenReturn(profile);

        mockMvc.perform(get("/admin/novel/profile"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /admin/novel/volumes (chuyển hướng sang /login)")
    void shouldRedirectAnonymousUserWhenAccessingAdminRoute() throws Exception {
        mockMvc.perform(get("/admin/novel/volumes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng đã đăng nhập với role USER bị từ chối truy cập /admin/novel/volumes (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminRouteForRegularUser() throws Exception {
        mockMvc.perform(get("/admin/novel/volumes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên có role ADMIN được phép truy cập /admin/novel/volumes")
    void shouldAllowAccessToAdminRouteForAdmin() throws Exception {
        when(getVolumeListUseCase.execute())
                .thenReturn(List.of());

        mockMvc.perform(get("/admin/novel/volumes"))
                .andExpect(status().isOk());
    }
}
