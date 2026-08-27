package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionCommand;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionAlreadyCurrentException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.entry.admin.form.RestoreChapterRevisionForm;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminNovelChapterRevisionCommandControllerTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ACTOR_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String ADMIN_EMAIL =
            "admin@universe.local";

    private static final Instant NOW =
            Instant.parse("2026-08-20T10:00:00Z");

    @Mock
    private RestoreChapterRevisionUseCase restoreChapterRevisionUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private Authentication authentication;

    private AdminNovelChapterRevisionCommandController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterRevisionCommandController(
                restoreChapterRevisionUseCase,
                userIdentityContract,
                new AuthenticatedEmailResolver()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Case A: POST restore thành công giải quyết actor, gọi use case, chuyển hướng về Chapter detail và gán flash message")
    void shouldRestoreRevisionSuccessfully() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);
        form.setEditSummary("Khôi phục bản nháp do nhầm lẫn");

        UserDTO user = createUserDTO();

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        ChapterDTO restoredChapter = createChapterDTO();
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenReturn(restoredChapter);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã khôi phục nội dung từ phiên bản #2.");

        ArgumentCaptor<RestoreChapterRevisionCommand> captor =
                ArgumentCaptor.forClass(RestoreChapterRevisionCommand.class);
        verify(restoreChapterRevisionUseCase).execute(captor.capture());

        RestoreChapterRevisionCommand executedCommand = captor.getValue();
        assertThat(executedCommand.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(executedCommand.sourceRevisionNumber()).isEqualTo(2L);
        assertThat(executedCommand.expectedAggregateVersion()).isEqualTo(3L);
        assertThat(executedCommand.actorId()).isEqualTo(ACTOR_ID);
        assertThat(executedCommand.editSummary()).isEqualTo("Khôi phục bản nháp do nhầm lẫn");
    }

    @Test
    @DisplayName("Case B: POST restore với blank editSummary được phép và chuyển null/blank xuống use case")
    void shouldAllowBlankEditSummary() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(1L);
        form.setEditSummary(null);

        UserDTO user = createUserDTO();

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        ChapterDTO restoredChapter = createChapterDTO();
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenReturn(restoredChapter);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                1L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã khôi phục nội dung từ phiên bản #1.");

        ArgumentCaptor<RestoreChapterRevisionCommand> captor =
                ArgumentCaptor.forClass(RestoreChapterRevisionCommand.class);
        verify(restoreChapterRevisionUseCase).execute(captor.capture());
        assertThat(captor.getValue().editSummary()).isNull();
    }

    @Test
    @DisplayName("Case G1: Xử lý ChapterRevisionAlreadyCurrentException bằng cách redirect về trang revision detail với errorMessage")
    void shouldHandleAlreadyCurrentException() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(1L);

        UserDTO user = createUserDTO();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenThrow(new ChapterRevisionAlreadyCurrentException(CHAPTER_ID, 1L));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                1L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/1");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .toString().contains("không cần khôi phục");
    }

    @Test
    @DisplayName("Case G2: Xử lý ChapterRevisionNotFoundException bằng cách redirect về trang revision detail với errorMessage")
    void shouldHandleRevisionNotFoundException() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(1L);

        UserDTO user = createUserDTO();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenThrow(new ChapterRevisionNotFoundException(CHAPTER_ID, 99L));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                99L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/99");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy phiên bản 99 của chương: " + CHAPTER_ID);
    }

    @Test
    @DisplayName("Case G3: Xử lý ConcurrentModificationException khi có xung đột phiên bản")
    void shouldHandleConcurrentModificationException() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(1L);

        UserDTO user = createUserDTO();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenThrow(new ConcurrentModificationException("Dữ liệu đã bị thay đổi bởi người dùng khác."));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                1L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/1");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Dữ liệu đã bị thay đổi bởi người dùng khác.");
    }

    @Test
    @DisplayName("Xử lý thiếu expectedAggregateVersion trong form")
    void shouldHandleMissingExpectedAggregateVersion() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm(); // expectedAggregateVersion is null

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                1L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/1");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Expected aggregate version không được để trống.");
    }

    @Test
    @DisplayName("MockMvc: POST /admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore định tuyến và redirect chính xác")
    void shouldPerformPostRestoreThroughMockMvcRoute() throws Exception {
        UserDTO user = createUserDTO();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        ChapterDTO restoredChapter = createChapterDTO();
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenReturn(restoredChapter);

        mockMvc.perform(
                post("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 2L)
                        .principal(authentication)
                        .param("expectedAggregateVersion", "1")
                        .param("editSummary", "Khôi phục qua UI")
        )
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID))
                .andExpect(flash().attribute("successMessage", "Đã khôi phục nội dung từ phiên bản #2."));
    }

    @Test
    @DisplayName("MockMvc: GET /admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore bị từ chối 405 Method Not Allowed (không thể mutate qua GET)")
    void shouldRejectGetOnRestoreEndpointWithMethodNotAllowed() throws Exception {
        mockMvc.perform(
                get("/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore", CHAPTER_ID, 2L)
        )
                .andExpect(status().isMethodNotAllowed());
    }

    /*
     * ===================================================== ACTOR RESOLUTION TESTS
     * =====================================================
     */

    @Test
    @DisplayName("Restore Revision thành công khi Admin đăng nhập bằng OAuth2 (Google) với subject ID số và email attribute")
    void shouldRestoreRevisionWhenAuthenticatedViaOAuth2() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);
        form.setEditSummary("Khôi phục OAuth2");

        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("email")).thenReturn(ADMIN_EMAIL);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(oauth2User);
        lenient().when(authentication.getName()).thenReturn("104829374019283746152");

        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createUserDTO()));
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenReturn(createChapterDTO());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã khôi phục nội dung từ phiên bản #2.");

        verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
        ArgumentCaptor<RestoreChapterRevisionCommand> captor =
                ArgumentCaptor.forClass(RestoreChapterRevisionCommand.class);
        verify(restoreChapterRevisionUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("Restore Revision thành công khi Admin đăng nhập bằng form login chuẩn với email principal")
    void shouldRestoreRevisionWhenAuthenticatedViaFormLogin() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);
        form.setEditSummary("Khôi phục Form Login");

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);
        when(authentication.getPrincipal()).thenReturn(ADMIN_EMAIL);

        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createUserDTO()));
        when(restoreChapterRevisionUseCase.execute(any(RestoreChapterRevisionCommand.class)))
                .thenReturn(createChapterDTO());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã khôi phục nội dung từ phiên bản #2.");

        verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Từ chối Restore Revision khi Authentication là null")
    void shouldRejectRestoreWhenAuthenticationIsNull() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                null,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/2");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không xác định được người dùng đang đăng nhập.");

        verify(userIdentityContract, never()).findByEmail(any());
        verify(restoreChapterRevisionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Từ chối Restore Revision khi Authentication là AnonymousAuthenticationToken")
    void shouldRejectRestoreWhenAuthenticationIsAnonymous() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);

        Authentication anonymousAuth = mock(AnonymousAuthenticationToken.class);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                anonymousAuth,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/2");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không xác định được người dùng đang đăng nhập.");

        verify(userIdentityContract, never()).findByEmail(any());
        verify(restoreChapterRevisionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Từ chối Restore Revision khi Authentication chưa được xác thực (isAuthenticated = false)")
    void shouldRejectRestoreWhenAuthenticationIsNotAuthenticated() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);

        when(authentication.isAuthenticated()).thenReturn(false);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/2");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không xác định được người dùng đang đăng nhập.");

        verify(userIdentityContract, never()).findByEmail(any());
        verify(restoreChapterRevisionUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Từ chối Restore Revision khi OAuth2 principal không có email attribute")
    void shouldRejectRestoreWhenOAuth2UserHasNoEmailAttribute() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);

        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("email")).thenReturn(null);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(oauth2User);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restoreRevision(
                CHAPTER_ID,
                2L,
                form,
                authentication,
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/revisions/2");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không xác định được người dùng đang đăng nhập.");

        verify(userIdentityContract, never()).findByEmail(any());
        verify(restoreChapterRevisionUseCase, never()).execute(any());
    }

    private ChapterDTO createChapterDTO() {
        return new ChapterDTO(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                "chuong-mot",
                "Tóm tắt",
                "Nội dung",
                "DRAFT",
                ACTOR_ID,
                ACTOR_ID,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                2L,
                2L
        );
    }

    private UserDTO createUserDTO() {
        return new UserDTO(
                ACTOR_ID,
                ADMIN_EMAIL,
                "Admin User",
                null,
                "ACTIVE",
                "ADMIN",
                NOW
        );
    }
}
