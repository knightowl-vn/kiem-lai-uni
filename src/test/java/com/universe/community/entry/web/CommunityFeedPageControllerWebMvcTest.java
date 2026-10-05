package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetAuthorPendingCommunityPostsUseCase;
import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.application.usecase.GetCommunitySettingsUseCase;
import com.universe.community.contracts.dto.AuthorPendingCommunityPostDTO;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = CommunityFeedPageController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("CommunityFeedPageController WebMvc Real Spring Slice Tests")
class CommunityFeedPageControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;

    @MockBean
    private GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;

    @MockBean
    private GetCommunitySettingsUseCase getCommunitySettingsUseCase;

    @MockBean
    private GetAuthorPendingCommunityPostsUseCase getAuthorPendingCommunityPostsUseCase;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());

        when(getCommunitySettingsUseCase.execute())
                .thenReturn(CommunitySettings.defaultSettings());
        when(getAuthorPendingCommunityPostsUseCase.execute(any()))
                .thenReturn(List.of());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community as anonymous guest -> 200 OK with default NEWEST feed and activeNav=community")
    void shouldRenderCommunityFeedPageForAnonymousGuest() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Tác giả", "tac_gia", null, "Hello Community",
                null, null, 0,
                5L, 2L, 7L, now, now
        );
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(item), "next-cursor-token", 20, true
        );

        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of(item)))
                .andExpect(model().attribute("nextCursor", "next-cursor-token"))
                .andExpect(model().attribute("hasNext", true))
                .andExpect(model().attribute("returnTo", "/community"))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community\" class=\"btn btn-primary btn-sm\">Đăng nhập</a>")))
                .andExpect(content().string(containsString("href=\"/register\" class=\"btn btn-outline-secondary btn-sm ms-2\">Đăng ký</a>")))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community\"")))
                .andExpect(content().string(not(containsString("href=\"/login\" class=\"btn btn-primary btn-sm\""))))
                .andExpect(content().string(not(containsString("name=\"current-user-id\""))))
                .andExpect(content().string(not(containsString("data-reaction-current"))))
                .andExpect(content().string(not(containsString("kl-reaction-widget"))))
                .andExpect(content().string(containsString("post-metric--login-link")))
                .andExpect(content().string(not(containsString("post-actions-dropdown"))))
                .andExpect(content().string(not(containsString("data-action=\"edit-post\""))));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithMockUser(username = "user@universe.com")
    @DisplayName("GET /community as authenticated user -> 200 OK with currentUser populated and owner edit affordance rendered")
    void shouldRenderCommunityFeedPageForAuthenticatedUser() throws Exception {
        UUID currentUserId = UUID.randomUUID();
        CurrentUserView currentUser = new CurrentUserView(
                currentUserId.toString(),
                "user@universe.com",
                "Đạo Hữu",
                "https://cdn.example.com/me.png",
                "dao_huu",
                "Bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of("user@universe.com"));
        when(currentUserQueryPort.findByEmail("user@universe.com")).thenReturn(Optional.of(currentUser));

        UUID ownerPostId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        CommunityPostFeedItemDTO ownerItem = new CommunityPostFeedItemDTO(
                ownerPostId, currentUserId, "Đạo Hữu", "dao_huu", null, "Owner caption",
                null, null, 0,
                1L, 0L, 1L, now, now, "LIKE"
        );
        UUID otherPostId = UUID.randomUUID();
        CommunityPostFeedItemDTO otherItem = new CommunityPostFeedItemDTO(
                otherPostId, UUID.randomUUID(), "Other Author", "other_author", null, "Other caption",
                null, null, 0,
                2L, 0L, 2L, now, now
        );

        CommunityNewestFeedResponseDTO feed = new CommunityNewestFeedResponseDTO(
                List.of(ownerItem, otherItem), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(feed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("currentUser", currentUser))
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of(ownerItem, otherItem)))
                .andExpect(model().attribute("hasNext", false))
                .andExpect(content().string(containsString("<meta name=\"current-user-id\" content=\"" + currentUserId + "\">")))
                .andExpect(content().string(containsString("data-current-user-id=\"" + currentUserId + "\"")))
                .andExpect(content().string(containsString("data-reaction-current=\"LIKE\"")))
                .andExpect(content().string(containsString("data-action=\"edit-post\" data-post-id=\"" + ownerPostId + "\"")))
                .andExpect(content().string(containsString("Chỉnh sửa bài viết")))
                .andExpect(content().string(not(containsString("data-action=\"edit-post\" data-post-id=\"" + otherPostId + "\""))));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=FEATURED -> 200 OK with FEATURED feed and page metadata")
    void shouldRenderFeaturedFeed() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Bình An", "binh_an", "https://cdn.example.com/ba.jpg", "Top Featured Post",
                null, null, 0,
                50L, 20L, 70L, now, now
        );
        CommunityFeaturedFeedResponseDTO featuredFeed = new CommunityFeaturedFeedResponseDTO(
                List.of(item), 0, 20, 25L, 2, true
        );

        when(getCommunityFeaturedFeedUseCase.execute(eq(0), eq(20))).thenReturn(featuredFeed);

        mockMvc.perform(get("/community").param("feed", "FEATURED"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("selectedFeed", "FEATURED"))
                .andExpect(model().attribute("activeNav", "community"))
                .andExpect(model().attribute("items", List.of(item)))
                .andExpect(model().attribute("nextPage", 1))
                .andExpect(model().attribute("hasNext", true))
                .andExpect(model().attribute("returnTo", "/community?feed=FEATURED"))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community?feed%3DFEATURED\" class=\"btn btn-primary btn-sm\">Đăng nhập</a>")))
                .andExpect(content().string(containsString("href=\"/register\" class=\"btn btn-outline-secondary btn-sm ms-2\">Đăng ký</a>")))
                .andExpect(content().string(not(containsString("href=\"/login\" class=\"btn btn-primary btn-sm\""))))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community?feed%3DFEATURED\"")));

        verify(getCommunityFeaturedFeedUseCase).execute(0, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=featured (case-insensitive) & page=1 & size=10 -> 200 OK")
    void shouldAcceptCaseInsensitiveFeaturedFeedWithCustomPageAndSize() throws Exception {
        CommunityFeaturedFeedResponseDTO featuredFeed = new CommunityFeaturedFeedResponseDTO(
                List.of(), 1, 10, 5L, 1, false
        );
        when(getCommunityFeaturedFeedUseCase.execute(eq(1), eq(10))).thenReturn(featuredFeed);

        mockMvc.perform(get("/community")
                        .param("feed", "featured")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "FEATURED"))
                .andExpect(model().attribute("nextPage", org.hamcrest.Matchers.nullValue()))
                .andExpect(model().attribute("hasNext", false));

        verify(getCommunityFeaturedFeedUseCase).execute(1, 10);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=NEWEST&cursor=xxx&size=10 -> 200 OK")
    void shouldAcceptNewestFeedWithCustomCursorAndSize() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), "next-token", 10, true
        );
        when(getCommunityNewestFeedUseCase.execute(eq("custom-cursor"), eq(10))).thenReturn(newestFeed);

        mockMvc.perform(get("/community")
                        .param("feed", "NEWEST")
                        .param("cursor", "custom-cursor")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"))
                .andExpect(model().attribute("nextCursor", "next-token"));

        verify(getCommunityNewestFeedUseCase).execute("custom-cursor", 10);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=FEATURED&cursor=xxx -> 400 Bad Request")
    void shouldRejectCursorOnFeaturedFeed() throws Exception {
        mockMvc.perform(get("/community")
                        .param("feed", "FEATURED")
                        .param("cursor", "some-cursor"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=NEWEST&page=1 -> 400 Bad Request")
    void shouldRejectPageOnNewestFeed() throws Exception {
        mockMvc.perform(get("/community")
                        .param("feed", "NEWEST")
                        .param("page", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=INVALID -> 400 Bad Request")
    void shouldRejectInvalidFeedSelector() throws Exception {
        mockMvc.perform(get("/community").param("feed", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=not-a-feed -> 400 Bad Request")
    void shouldRejectNotAFeedSelector() throws Exception {
        mockMvc.perform(get("/community").param("feed", "not-a-feed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=newest (lowercase) -> 200 OK with NEWEST feed")
    void shouldAcceptCaseInsensitiveNewestFeed() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").param("feed", "newest"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=   (blank) -> 200 OK defaulting to NEWEST feed")
    void shouldAcceptBlankFeedParamAsNewest() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").param("feed", "   "))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFeed", "NEWEST"));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community as guest with empty feed -> page-level guest CTA still renders returnTo=/community with zero post cards")
    void shouldRenderPageLevelGuestCtaWithReturnToEvenWhenFeedIsEmpty() throws Exception {
        CommunityNewestFeedResponseDTO emptyFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(emptyFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("returnTo", "/community"))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community\" class=\"btn btn-primary btn-sm\">Đăng nhập</a>")))
                .andExpect(content().string(containsString("href=\"/register\" class=\"btn btn-outline-secondary btn-sm ms-2\">Đăng ký</a>")))
                .andExpect(content().string(not(containsString("href=\"/login\" class=\"btn btn-primary btn-sm\""))))
                .andExpect(content().string(not(containsString("post-metric--login-link"))));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community?feed=NEWEST as guest -> page-level guest CTA renders returnTo=/community?feed%3DNEWEST")
    void shouldRenderPageLevelGuestCtaWithExplicitNewestFeedQueryParam() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").param("feed", "NEWEST"))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attribute("returnTo", "/community?feed=NEWEST"))
                .andExpect(content().string(containsString("href=\"/login?returnTo=/community?feed%3DNEWEST\" class=\"btn btn-primary btn-sm\">Đăng nhập</a>")))
                .andExpect(content().string(containsString("href=\"/register\" class=\"btn btn-outline-secondary btn-sm ms-2\">Đăng ký</a>")));

        verify(getCommunityNewestFeedUseCase).execute(null, 20);
    }

    private RequestPostProcessor authenticatedIdentity(UUID userId) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                "author@universe.local",
                "Tác giả",
                "/author.png",
                "tac_gia",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    @Test
    @WithMockUser(username = "author@universe.local")
    @DisplayName("GET /community as authenticated author with pending posts -> renders own-pending section with status chips and no permalink/reactions")
    void shouldRenderAuthorOwnPendingPostsSectionWhenAuthenticatedUserHasPendingPosts() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID newPostId = UUID.randomUUID();
        UUID reReviewPostId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-04T12:00:00Z");

        AuthorPendingCommunityPostDTO pendingPost1 = new AuthorPendingCommunityPostDTO(
                newPostId, authorId, "Tác giả", "tac_gia", "/author.png",
                "Draft caption awaiting initial approval", null, null,
                now, now, null, "PENDING_REVIEW", 0
        );

        AuthorPendingCommunityPostDTO pendingPost2 = new AuthorPendingCommunityPostDTO(
                reReviewPostId, authorId, "Tác giả", "tac_gia", "/author.png",
                "Old public caption", "Edited candidate caption awaiting moderation",
                null, null,
                now.minusSeconds(7200), now, now.minusSeconds(3600), "PUBLISHED", 1
        );

        when(getAuthorPendingCommunityPostsUseCase.execute(eq(authorId)))
                .thenReturn(List.of(pendingPost1, pendingPost2));

        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20), eq(authorId))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").with(authenticatedIdentity(authorId)))
                .andExpect(status().isOk())
                .andExpect(view().name("community/index"))
                .andExpect(model().attributeExists("ownPendingPosts"))
                // Own pending section present
                .andExpect(content().string(containsString("id=\"communityOwnPendingSection\"")))
                .andExpect(content().string(containsString("Bài viết đang chờ duyệt")))
                // Collapsible tray trigger and count badge (2 pending posts)
                .andExpect(content().string(containsString("id=\"communityOwnPendingTrigger\"")))
                .andExpect(content().string(containsString("aria-expanded=\"false\"")))
                .andExpect(content().string(containsString("aria-controls=\"communityOwnPendingList\"")))
                .andExpect(content().string(containsString("id=\"communityOwnPendingCount\"")))
                .andExpect(content().string(containsString(">2</span>")))
                .andExpect(content().string(containsString("id=\"communityOwnPendingList\" class=\"community-pending-list d-flex flex-column gap-3 mt-3\" hidden")))
                // Both cards present
                .andExpect(content().string(containsString("Draft caption awaiting initial approval")))
                .andExpect(content().string(containsString("Edited candidate caption awaiting moderation")))
                // Status in footer
                .andExpect(content().string(containsString("post-footer")))
                .andExpect(content().string(containsString("post-pending-status")))
                .andExpect(content().string(containsString("⏳ Đang chờ duyệt")))
                .andExpect(content().string(containsString("⏳ Đang chờ duyệt chỉnh sửa")))
                .andExpect(content().string(not(containsString("post-pending-badge-group"))))
                // No permalink anchor on pending caption
                .andExpect(content().string(not(containsString("href=\"/community/posts/" + newPostId + "\""))))
                .andExpect(content().string(not(containsString("href=\"/community/posts/" + reReviewPostId + "\""))))
                // No reaction or comment controls for pending posts
                .andExpect(content().string(not(containsString("data-reaction-target-id=\"" + newPostId + "\""))))
                .andExpect(content().string(not(containsString("data-action=\"toggle-comments\" data-post-id=\"" + newPostId + "\""))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /community as guest -> does NOT render own pending posts section")
    void shouldNotRenderOwnPendingPostsSectionForAnonymousGuest() throws Exception {
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"communityOwnPendingSection\""))))
                .andExpect(content().string(not(containsString("Bài viết đang chờ duyệt"))));
    }

    @Test
    @WithMockUser(username = "author@universe.local")
    @DisplayName("GET /community as authenticated author without pending posts -> does NOT render own pending posts section")
    void shouldNotRenderOwnPendingPostsSectionWhenAuthorHasNoPendingPosts() throws Exception {
        UUID authorId = UUID.randomUUID();
        when(getAuthorPendingCommunityPostsUseCase.execute(eq(authorId))).thenReturn(List.of());

        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(), null, 20, false
        );
        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20), eq(authorId))).thenReturn(newestFeed);

        mockMvc.perform(get("/community").with(authenticatedIdentity(authorId)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"communityOwnPendingSection\""))))
                .andExpect(content().string(not(containsString("Bài viết đang chờ duyệt"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Public post card renders initial approval timestamp datetime, NOT submission createdAt")
    void shouldRenderPublicPostCardWithInitialApprovalTimeNotSubmissionTime() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant submittedAt = Instant.parse("2026-10-04T15:00:00Z");
        Instant approvedAt = Instant.parse("2026-10-04T16:20:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, "Tác giả", "tac_gia", null, "Post approved after pending review",
                null, null, 0,
                0L, 0L, 0L, submittedAt, approvedAt, null, approvedAt
        );
        CommunityNewestFeedResponseDTO newestFeed = new CommunityNewestFeedResponseDTO(
                List.of(item), null, 20, false
        );

        when(getCommunityNewestFeedUseCase.execute(eq(null), eq(20))).thenReturn(newestFeed);

        mockMvc.perform(get("/community"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("datetime=\"2026-10-04T16:20:00Z\"")))
                .andExpect(content().string(not(containsString("datetime=\"2026-10-04T15:00:00Z\""))));
    }
}
