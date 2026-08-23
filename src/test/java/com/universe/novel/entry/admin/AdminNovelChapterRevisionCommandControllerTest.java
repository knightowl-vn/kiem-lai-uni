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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterRevisionCommandController(
                restoreChapterRevisionUseCase,
                userIdentityContract
        );
    }

    @Test
    @DisplayName("Case A: POST restore thành công giải quyết actor, gọi use case, chuyển hướng về Chapter detail và gán flash message")
    void shouldRestoreRevisionSuccessfully() {
        RestoreChapterRevisionForm form = new RestoreChapterRevisionForm();
        form.setExpectedAggregateVersion(3L);
        form.setEditSummary("Khôi phục bản nháp do nhầm lẫn");

        UserDTO user = createUserDTO();

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
