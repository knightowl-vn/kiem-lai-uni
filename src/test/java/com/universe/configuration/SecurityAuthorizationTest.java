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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.novel.application.reader.BookmarkChapterUseCase;
import com.universe.novel.application.reader.IsChapterBookmarkedUseCase;
import com.universe.novel.application.reader.ListUserBookmarkedChaptersUseCase;
import com.universe.novel.application.reader.ListUserReadingHistoryUseCase;
import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
import com.universe.novel.application.reader.ResolveReaderChapterWikiUseCase;
import com.universe.novel.application.reader.UnbookmarkChapterUseCase;
import com.universe.novel.entry.reader.ReaderBookmarkController;
import com.universe.novel.entry.reader.ReaderReadingHistoryController;
import com.universe.novel.entry.reader.ReaderReadingProgressController;
import com.universe.novel.entry.reader.ReaderWikiLookupController;
import com.universe.media.entry.delivery.MediaDeliveryController;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;

@WebMvcTest(controllers = {
        ReaderNovelPageController.class,
        ReaderChapterListFragmentController.class,
        ReaderChapterPageController.class,
        ReaderBookmarkController.class,
        ReaderReadingHistoryController.class,
        ReaderReadingProgressController.class,
        ReaderWikiLookupController.class,
        AdminNovelVolumePageController.class,
        AdminNovelProfilePageController.class,
        AdminNovelProfileCommandController.class,
        MediaDeliveryController.class
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
    private IsChapterBookmarkedUseCase isChapterBookmarkedUseCase;

    @MockBean
    private BookmarkChapterUseCase bookmarkChapterUseCase;

    @MockBean
    private UnbookmarkChapterUseCase unbookmarkChapterUseCase;

    @MockBean
    private ListUserBookmarkedChaptersUseCase listUserBookmarkedChaptersUseCase;

    @MockBean
    private RecordReadingHistoryUseCase recordReadingHistoryUseCase;

    @MockBean
    private ListUserReadingHistoryUseCase listUserReadingHistoryUseCase;

    @MockBean
    private RecordReadingProgressUseCase recordReadingProgressUseCase;

    @MockBean
    private LookupContextualWikiUseCase lookupContextualWikiUseCase;

    @MockBean
    private ResolveReaderChapterWikiUseCase resolveReaderChapterWikiUseCase;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private com.universe.identity.contracts.interfaces.UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private GetMediaAssetContentUseCase getMediaAssetContentUseCase;

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
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập GET /media/assets/{assetId}/content")
    void shouldAllowAnonymousAccessToMediaAssetContentEndpoint() throws Exception {
        UUID assetId = UUID.randomUUID();
        byte[] payload = new byte[]{1, 2, 3};
        GetMediaAssetContentResult result = new GetMediaAssetContentResult(
                new java.io.ByteArrayInputStream(payload),
                payload.length,
                "image/webp",
                "dummyhash"
        );

        when(getMediaAssetContentUseCase.execute(new GetMediaAssetContentQuery(assetId)))
                .thenReturn(result);

        mockMvc.perform(get("/media/assets/" + assetId + "/content"))
                .andExpect(status().isOk());
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

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /novel/bookmarks (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingBookmarksPage() throws Exception {
        mockMvc.perform(get("/novel/bookmarks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /novel/history (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingHistoryPage() throws Exception {
        mockMvc.perform(get("/novel/history"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi ghi nhận progress (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenPostingProgress() throws Exception {
        UUID chapterId = UUID.randomUUID();
        mockMvc.perform(post("/novel/chapters/" + chapterId + "/progress").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi thêm bookmark (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenPostingBookmark() throws Exception {
        UUID chapterId = UUID.randomUUID();
        mockMvc.perform(post("/novel/chapters/" + chapterId + "/bookmark").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi ghi nhận history (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenPostingHistory() throws Exception {
        UUID chapterId = UUID.randomUUID();
        mockMvc.perform(post("/novel/chapters/" + chapterId + "/history").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập (USER) được phép truy cập /novel/bookmarks")
    void shouldAllowAuthenticatedUserToAccessBookmarksPage() throws Exception {
        UUID userId = UUID.randomUUID();
        UserDTO user = new UserDTO(
                userId,
                "reader@universe.local",
                "Reader",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(java.util.Optional.of("reader@universe.local"));
        when(userIdentityContract.findByEmail("reader@universe.local")).thenReturn(java.util.Optional.of(user));
        when(listUserBookmarkedChaptersUseCase.execute(userId)).thenReturn(List.of());

        mockMvc.perform(get("/novel/bookmarks"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập (USER) được phép truy cập /novel/history")
    void shouldAllowAuthenticatedUserToAccessHistoryPage() throws Exception {
        UUID userId = UUID.randomUUID();
        UserDTO user = new UserDTO(
                userId,
                "reader@universe.local",
                "Reader",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(java.util.Optional.of("reader@universe.local"));
        when(userIdentityContract.findByEmail("reader@universe.local")).thenReturn(java.util.Optional.of(user));
        when(listUserReadingHistoryUseCase.execute(userId)).thenReturn(List.of());

        mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh được phép tra cứu Wiki công khai /novel/api/wiki/lookup")
    void shouldAllowAnonymousAccessToPublicWikiLookup() throws Exception {
        when(lookupContextualWikiUseCase.execute("kiem-lai"))
                .thenReturn(new ReaderWikiLookupResult("kiem-lai", false, List.of()));

        mockMvc.perform(get("/novel/api/wiki/lookup").param("q", "kiem-lai"))
                .andExpect(status().isOk());
    }
}
