package com.universe.configuration;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.View;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.universe.media.application.asset.GetMediaAssetContentMetadataResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.variant.GetMediaImageVariantContentQuery;
import com.universe.media.application.variant.GetMediaImageVariantContentUseCase;

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
        MediaDeliveryController.class,
        com.universe.novel.entry.reader.PublicNovelManagedVoiceCatalogController.class,
        com.universe.novel.entry.reader.PublicNovelChapterNarrationPlaybackController.class,
        com.universe.wiki.entry.web.SavedWikiArticleController.class,
        com.universe.wiki.entry.web.PublicWikiController.class,
        com.universe.wiki.entry.web.PublicWikiContextualLookupController.class,
        com.universe.interaction.entry.admin.AdminCommentReportQueueController.class,
        com.universe.interaction.entry.admin.AdminCommentReportDetailController.class,
        com.universe.interaction.entry.admin.AdminCommentReportModerationController.class,
        com.universe.wiki.entry.admin.AdminWikiContributionPageController.class
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

    @MockBean
    private GetMediaImageVariantContentUseCase getMediaImageVariantContentUseCase;


    @MockBean
    private com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase;

    @MockBean
    private com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackUseCase preparePublicChapterPlaybackUseCase;

    @MockBean
    private com.universe.novel.application.narration.GetPublicManagedVoiceCatalogUseCase getPublicManagedVoiceCatalogUseCase;

    @MockBean
    private com.universe.wiki.application.saved.SaveWikiArticleUseCase saveWikiArticleUseCase;

    @MockBean
    private com.universe.wiki.application.saved.UnsaveWikiArticleUseCase unsaveWikiArticleUseCase;

    @MockBean
    private com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

    @MockBean
    private com.universe.wiki.application.article.query.published.GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

    @MockBean
    private com.universe.wiki.entry.web.support.ArticleTypePathMapper articleTypePathMapper;

    @MockBean
    private com.universe.wiki.application.article.render.WikiMarkdownRenderer wikiMarkdownRenderer;

    @MockBean
    private com.universe.wiki.application.saved.ListSavedWikiArticlesUseCase listSavedWikiArticlesUseCase;

    @MockBean
    private com.universe.wiki.application.saved.IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

    @MockBean
    private com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;

    @MockBean
    private com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

    @MockBean
    private com.universe.wiki.contracts.interfaces.WikiContextualLookupContract wikiContextualLookupContract;

    @MockBean
    private com.universe.interaction.entry.admin.AdminCommentReportQueueCoordinator adminCommentReportQueueCoordinator;

    @MockBean
    private com.universe.interaction.entry.admin.AdminCommentReportDetailCoordinator adminCommentReportDetailCoordinator;

    @MockBean
    private com.universe.interaction.entry.admin.AdminCommentReportContextNavigationCoordinator adminCommentReportContextNavigationCoordinator;

    @MockBean
    private com.universe.interaction.application.mutation.ResolveCommentReportUseCase resolveCommentReportUseCase;

    @MockBean
    private com.universe.wiki.entry.admin.AdminWikiContributionCoordinator adminWikiContributionCoordinator;

    @MockBean
    private ThymeleafViewResolver thymeleafViewResolver;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());

        View noOpView = (model, request, response) -> {};
        when(thymeleafViewResolver.resolveViewName(any(), any())).thenReturn(noOpView);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập GET /media/assets/{assetId}/content")
    void shouldAllowAnonymousAccessToMediaAssetContentEndpoint() throws Exception {
        UUID assetId = UUID.randomUUID();
        byte[] payload = new byte[]{1, 2, 3};
        GetMediaAssetContentMetadataResult metadata = new GetMediaAssetContentMetadataResult(
                assetId,
                1,
                payload.length,
                "image/webp",
                "dummyhash"
        );

        when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(assetId)))
                .thenReturn(metadata);
        when(getMediaAssetContentUseCase.open(metadata))
                .thenReturn(new java.io.ByteArrayInputStream(payload));

        mockMvc.perform(get("/media/assets/" + assetId + "/content"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous users can access HEAD /media/assets/{assetId}/content without opening binary storage")
    void shouldAllowAnonymousHeadAccessToMediaAssetContentEndpoint() throws Exception {
        UUID assetId = UUID.randomUUID();
        GetMediaAssetContentMetadataResult metadata = new GetMediaAssetContentMetadataResult(
                assetId,
                1,
                3,
                "audio/mpeg",
                "dummyhash"
        );
        when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(assetId)))
                .thenReturn(metadata);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.HEAD,
                        "/media/assets/{assetId}/content",
                        assetId
                ))
                .andExpect(status().isOk());

        verify(getMediaAssetContentUseCase, never()).open(any());
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh (anonymous) có thể truy cập GET /media/assets/{assetId}/variants/{variantKey}")
    void shouldAllowAnonymousAccessToMediaImageVariantEndpoint() throws Exception {
        UUID assetId = UUID.randomUUID();
        byte[] payload = new byte[]{4, 5, 6};
        GetMediaAssetContentResult result = new GetMediaAssetContentResult(
                new java.io.ByteArrayInputStream(payload),
                payload.length,
                "image/webp",
                "dummyhash"
        );

        when(getMediaImageVariantContentUseCase.execute(new GetMediaImageVariantContentQuery(assetId, "w300")))
                .thenReturn(result);

        mockMvc.perform(get("/media/assets/" + assetId + "/variants/w300"))
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
                1L,
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
        when(listUserBookmarkedChaptersUseCase.execute(userId)).thenReturn(List.of());

        mockMvc.perform(get("/novel/bookmarks").with(requestIdentity(userId)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập (USER) được phép truy cập /novel/history")
    void shouldAllowAuthenticatedUserToAccessHistoryPage() throws Exception {
        UUID userId = UUID.randomUUID();
        when(listUserReadingHistoryUseCase.execute(userId)).thenReturn(List.of());

        mockMvc.perform(get("/novel/history").with(requestIdentity(userId)))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor requestIdentity(UUID userId) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                "reader@universe.local",
                "Reader",
                null,
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    private RequestPostProcessor requestIdentity(UUID userId, UserRole role) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                role == UserRole.SUPER_ADMIN ? "superadmin@universe.local" : "admin@universe.local",
                role == UserRole.SUPER_ADMIN ? "SuperAdmin" : "Admin",
                null,
                UserStatus.ACTIVE,
                role
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
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

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập POST /segments/{segmentId}/prepare đã bị thu hồi (chuyển hướng sang /login)")
    void shouldDenyAnonymousAccessToLegacySegmentPrepareEndpoint() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        String voiceKey = "kiemlai-male-01";

        mockMvc.perform(post("/api/novel/chapters/" + chapterId + "/narration/segments/" + segmentId + "/prepare")
                        .with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + voiceKey + "\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous Reader can access POST chapter narration prepare with CSRF")
    void shouldAllowAnonymousAccessToChapterNarrationPrepare() throws Exception {
        UUID chapterId = UUID.randomUUID();
        String voiceKey = "kiemlai-male-01";
        when(preparePublicChapterPlaybackUseCase.execute(any(
                com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackCommand.class
        ))).thenReturn(new com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackResult(
                chapterId,
                voiceKey,
                com.universe.novel.application.narration.ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED
        ));

        mockMvc.perform(post("/api/novel/chapters/" + chapterId + "/narration/prepare")
                        .with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + voiceKey + "\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous Reader can access GET chapter narration playback metadata")
    void shouldAllowAnonymousAccessToNarrationPlaybackMetadata() throws Exception {
        UUID chapterId = UUID.randomUUID();
        com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO result =
                new com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO(
                        chapterId,
                        null,
                        com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability.MISSING,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of()
                );
        when(getPublicPlaybackUseCase.execute(any(
                com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackQuery.class
        ))).thenReturn(result);

        mockMvc.perform(get("/api/novel/chapters/" + chapterId + "/narration/playback"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous Reader can access the public Managed voice catalog")
    void shouldAllowAnonymousAccessToManagedVoiceCatalog() throws Exception {
        when(getPublicManagedVoiceCatalogUseCase.execute(any(
                com.universe.novel.application.narration.GetPublicManagedVoiceCatalogQuery.class
        ))).thenReturn(new com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO(List.of()));

        mockMvc.perform(get("/api/novel/narration/voices"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập POST /playback alias không hợp lệ")
    void shouldRedirectAnonymousWhenAccessingInvalidPlaybackAlias() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();

        mockMvc.perform(post("/api/novel/chapters/" + chapterId + "/narration/segments/" + segmentId + "/playback")
                        .with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"kiemlai-male-01\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh được phép truy cập danh sách Wiki công khai /wiki")
    void shouldAllowAnonymousAccessToPublicWiki() throws Exception {
        when(listPublishedArticlesUseCase.execute(any()))
                .thenReturn(new com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO(
                        List.of(), 0, 20, 0, 0, true, true
                ));

        mockMvc.perform(get("/wiki"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh được phép truy cập chi tiết bài viết Wiki công khai /wiki/{type}/{slug}")
    void shouldAllowAnonymousAccessToPublicWikiArticleDetail() throws Exception {
        when(articleTypePathMapper.fromPath("character"))
                .thenReturn(com.universe.wiki.domain.article.ArticleType.CHARACTER);
        when(getPublishedArticleUseCase.execute(any()))
                .thenReturn(new com.universe.wiki.contracts.dto.PublishedWikiArticleDTO(
                        UUID.randomUUID(),
                        "Trần Bình An",
                        "tran-binh-an",
                        "CHARACTER",
                        "Tóm tắt",
                        "Nội dung",
                        Instant.now(),
                        Instant.now(),
                        1L
                ));
        when(wikiMarkdownRenderer.render(any()))
                .thenReturn(new com.universe.wiki.application.article.render.RenderedWikiContent("html", List.of()));
        when(articleTypePathMapper.toPath(any()))
                .thenReturn("character");

        mockMvc.perform(get("/wiki/character/tran-binh-an"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh được phép tra cứu Wiki công khai GET /wiki/contextual-lookup")
    void shouldAllowAnonymousAccessToPublicWikiContextualLookup() throws Exception {
        when(wikiContextualLookupContract.lookupByTitle("kiem-lai"))
                .thenReturn(new com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO(
                        "kiem-lai",
                        false,
                        List.of()
                ));

        mockMvc.perform(get("/wiki/contextual-lookup").param("q", "kiem-lai"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi lưu bài viết Wiki POST /wiki/articles/{articleId}/save (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenPostingSaveWikiArticle() throws Exception {
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(post("/wiki/articles/" + articleId + "/save").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi bỏ lưu bài viết Wiki DELETE /wiki/articles/{articleId}/save (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenDeletingSaveWikiArticle() throws Exception {
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(delete("/wiki/articles/" + articleId + "/save").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("POST /wiki/articles/{articleId}/save không có CSRF bị chuyển hướng sang /access-denied")
    void shouldRedirectToAccessDeniedWhenPostingSaveWikiArticleWithoutCsrf() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(post("/wiki/articles/" + articleId + "/save")
                        .with(requestIdentity(userId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("DELETE /wiki/articles/{articleId}/save không có CSRF bị chuyển hướng sang /access-denied")
    void shouldRedirectToAccessDeniedWhenDeletingSaveWikiArticleWithoutCsrf() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(delete("/wiki/articles/" + articleId + "/save")
                        .with(requestIdentity(userId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập lưu bài viết Wiki hợp lệ với CSRF trả về 204 No Content")
    void shouldAllowAuthenticatedUserToSaveWikiArticleWithCsrf() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(post("/wiki/articles/" + articleId + "/save")
                        .with(csrf())
                        .with(requestIdentity(userId)))
                .andExpect(status().isNoContent());

        verify(saveWikiArticleUseCase).execute(new com.universe.wiki.application.saved.SaveWikiArticleCommand(userId, articleId));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập bỏ lưu bài viết Wiki hợp lệ với CSRF trả về 204 No Content")
    void shouldAllowAuthenticatedUserToUnsaveWikiArticleWithCsrf() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        mockMvc.perform(delete("/wiki/articles/" + articleId + "/save")
                        .with(csrf())
                        .with(requestIdentity(userId)))
                .andExpect(status().isNoContent());

        verify(unsaveWikiArticleUseCase).execute(new com.universe.wiki.application.saved.UnsaveWikiArticleCommand(userId, articleId));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /wiki/saved (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingWikiSavedPage() throws Exception {
        mockMvc.perform(get("/wiki/saved"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "reader@universe.local", roles = "USER")
    @DisplayName("Người dùng đã đăng nhập được phép truy cập /wiki/saved")
    void shouldAllowAuthenticatedUserToAccessWikiSavedPage() throws Exception {
        UUID userId = UUID.randomUUID();
        when(listSavedWikiArticlesUseCase.execute(userId, 0, 20))
                .thenReturn(new com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO(
                        List.of(), 0, 20, 0, 0, true, true
                ));

        mockMvc.perform(get("/wiki/saved").with(requestIdentity(userId)))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /admin/comments/reports (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingAdminCommentReportQueue() throws Exception {
        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng role USER bị từ chối truy cập /admin/comments/reports (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminCommentReportQueueForUser() throws Exception {
        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên role ADMIN được phép truy cập /admin/comments/reports")
    void shouldAllowAccessToAdminCommentReportQueueForAdmin() throws Exception {
        when(adminCommentReportQueueCoordinator.getReportQueue(any()))
                .thenReturn(com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("Quản trị viên role SUPER_ADMIN được phép truy cập /admin/comments/reports")
    void shouldAllowAccessToAdminCommentReportQueueForSuperAdmin() throws Exception {
        when(adminCommentReportQueueCoordinator.getReportQueue(any()))
                .thenReturn(com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /admin/comments/reports/{reportId} (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingAdminCommentReportDetail() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng role USER bị từ chối truy cập /admin/comments/reports/{reportId} (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminCommentReportDetailForUser() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên role ADMIN được phép truy cập /admin/comments/reports/{reportId}")
    void shouldAllowAccessToAdminCommentReportDetailForAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(adminCommentReportDetailCoordinator.getDetail(reportId))
                .thenReturn(org.mockito.Mockito.mock(com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO.class));

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("Quản trị viên role SUPER_ADMIN được phép truy cập /admin/comments/reports/{reportId}")
    void shouldAllowAccessToAdminCommentReportDetailForSuperAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(adminCommentReportDetailCoordinator.getDetail(reportId))
                .thenReturn(org.mockito.Mockito.mock(com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO.class));

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi truy cập /admin/comments/reports/{reportId}/context (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingAdminCommentReportContext() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(get("/admin/comments/reports/" + reportId + "/context"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng role USER bị từ chối truy cập /admin/comments/reports/{reportId}/context (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminCommentReportContextForUser() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(get("/admin/comments/reports/" + reportId + "/context"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên role ADMIN được phép truy cập /admin/comments/reports/{reportId}/context")
    void shouldAllowAccessToAdminCommentReportContextForAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(adminCommentReportContextNavigationCoordinator.resolveNavigation(reportId))
                .thenReturn(com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO.unavailable(reportId));

        mockMvc.perform(get("/admin/comments/reports/" + reportId + "/context"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/comments/reports/" + reportId));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("Quản trị viên role SUPER_ADMIN được phép truy cập /admin/comments/reports/{reportId}/context")
    void shouldAllowAccessToAdminCommentReportContextForSuperAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(adminCommentReportContextNavigationCoordinator.resolveNavigation(reportId))
                .thenReturn(com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO.unavailable(reportId));

        mockMvc.perform(get("/admin/comments/reports/" + reportId + "/context"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/comments/reports/" + reportId));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chặn khi thực hiện POST /admin/comments/reports/{reportId}/resolve (chuyển hướng sang /login)")
    void shouldRedirectAnonymousWhenAccessingAdminCommentReportResolve() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(post("/admin/comments/reports/" + reportId + "/resolve")
                        .with(csrf())
                        .param("action", "DELETE_COMMENT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng role USER bị từ chối thực hiện POST /admin/comments/reports/{reportId}/resolve (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminCommentReportResolveForUser() throws Exception {
        UUID reportId = UUID.randomUUID();
        mockMvc.perform(post("/admin/comments/reports/" + reportId + "/resolve")
                        .with(csrf())
                        .param("action", "DELETE_COMMENT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên role ADMIN được phép thực hiện POST /admin/comments/reports/{reportId}/resolve")
    void shouldAllowAccessToAdminCommentReportResolveForAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();

        mockMvc.perform(post("/admin/comments/reports/" + reportId + "/resolve")
                        .with(csrf())
                        .with(requestIdentity(moderatorId, UserRole.ADMIN))
                        .param("action", "DELETE_COMMENT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/comments/reports/" + reportId));

        verify(resolveCommentReportUseCase).execute(any());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("Quản trị viên role SUPER_ADMIN được phép thực hiện POST /admin/comments/reports/{reportId}/resolve")
    void shouldAllowAccessToAdminCommentReportResolveForSuperAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();

        mockMvc.perform(post("/admin/comments/reports/" + reportId + "/resolve")
                        .with(csrf())
                        .with(requestIdentity(moderatorId, UserRole.SUPER_ADMIN))
                        .param("action", "NO_ACTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/comments/reports/" + reportId));

        verify(resolveCommentReportUseCase).execute(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Khách ẩn danh bị chuyển hướng sang /login khi truy cập /admin/wiki/contributions")
    void shouldRedirectAnonymousUserWhenAccessingAdminWikiContributions() throws Exception {
        mockMvc.perform(get("/admin/wiki/contributions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Người dùng với role USER bị từ chối truy cập /admin/wiki/contributions (chuyển hướng sang /access-denied)")
    void shouldDenyAccessToAdminWikiContributionsForRegularUser() throws Exception {
        mockMvc.perform(get("/admin/wiki/contributions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quản trị viên role ADMIN được phép truy cập GET /admin/wiki/contributions")
    void shouldAllowAccessToAdminWikiContributionsForAdmin() throws Exception {
        when(adminWikiContributionCoordinator.getInboxPage(any()))
                .thenReturn(com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(adminWikiContributionCoordinator.getNewContributionCount()).thenReturn(0L);

        mockMvc.perform(get("/admin/wiki/contributions"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("Quản trị viên role SUPER_ADMIN được phép truy cập GET /admin/wiki/contributions")
    void shouldAllowAccessToAdminWikiContributionsForSuperAdmin() throws Exception {
        when(adminWikiContributionCoordinator.getInboxPage(any()))
                .thenReturn(com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(adminWikiContributionCoordinator.getNewContributionCount()).thenReturn(0L);

        mockMvc.perform(get("/admin/wiki/contributions"))
                .andExpect(status().isOk());
    }
}
