package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.chapter.reference.BindChapterWideWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.BindChapterWideWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.BindOccurrenceSpecificWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.BindOccurrenceSpecificWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceItemDTO;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceStatus;
import com.universe.novel.application.chapter.reference.RemoveChapterWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.RemoveChapterWikiReferenceUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.TargetWikiArticleNotPublishedException;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
import com.universe.novel.entry.admin.form.BindChapterWideWikiReferenceForm;
import com.universe.novel.entry.admin.form.BindOccurrenceSpecificWikiReferenceForm;
import com.universe.shared.security.AuthenticatedEmailResolver;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminNovelChapterWikiReferenceCommandController Unit Tests")
class AdminNovelChapterWikiReferenceCommandControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REFERENCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ADMIN_EMAIL = "admin@universe.local";

    @Mock
    private BindChapterWideWikiReferenceUseCase bindChapterWideWikiReferenceUseCase;

    @Mock
    private BindOccurrenceSpecificWikiReferenceUseCase bindOccurrenceSpecificWikiReferenceUseCase;

    @Mock
    private RemoveChapterWikiReferenceUseCase removeChapterWikiReferenceUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @Mock
    private Authentication authentication;

    private AdminNovelChapterWikiReferenceCommandController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterWikiReferenceCommandController(
                bindChapterWideWikiReferenceUseCase,
                bindOccurrenceSpecificWikiReferenceUseCase,
                removeChapterWikiReferenceUseCase,
                userIdentityContract,
                authenticatedEmailResolver
        );
    }

    private void mockAuthenticatedAdmin() {
        when(authenticatedEmailResolver.require(authentication)).thenReturn(ADMIN_EMAIL);
        UserDTO adminUser = new UserDTO(
                ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "ACTIVE", "ADMIN", Instant.now()
        );
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUser));
    }

    @Test
    @DisplayName("Bind Chapter-wide: delegates correct command with authenticated actor and redirects with success message")
    void shouldBindChapterWideSuccessfully() {
        mockAuthenticatedAdmin();

        BindChapterWideWikiReferenceForm form = new BindChapterWideWikiReferenceForm();
        form.setTerm("Trần Bình An");
        form.setWikiArticleId(ARTICLE_ID);

        ChapterWikiReferenceItemDTO resultDTO = new ChapterWikiReferenceItemDTO(
                REFERENCE_ID, CHAPTER_ID, "Trần Bình An", "trần bình an",
                ChapterWikiReferenceScope.CHAPTER_WIDE, 0, null, null, 1L,
                ARTICLE_ID, ChapterWikiReferenceStatus.ACTIVE, null, ADMIN_ID, ADMIN_ID,
                Instant.now(), Instant.now()
        );
        when(bindChapterWideWikiReferenceUseCase.execute(any())).thenReturn(resultDTO);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.bindChapterWide(CHAPTER_ID, form, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã liên kết thuật ngữ \"Trần Bình An\" với bài viết Wiki trên toàn chương.");

        ArgumentCaptor<BindChapterWideWikiReferenceCommand> captor =
                ArgumentCaptor.forClass(BindChapterWideWikiReferenceCommand.class);
        verify(bindChapterWideWikiReferenceUseCase).execute(captor.capture());

        BindChapterWideWikiReferenceCommand command = captor.getValue();
        assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(command.term()).isEqualTo("Trần Bình An");
        assertThat(command.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(command.actorId()).isEqualTo(ADMIN_ID); // Resolved from authentication, never request body
    }

    @Test
    @DisplayName("Bind Occurrence-specific: delegates correct command with occurrenceIndex and contextSnippet")
    void shouldBindOccurrenceSpecificSuccessfully() {
        mockAuthenticatedAdmin();

        BindOccurrenceSpecificWikiReferenceForm form = new BindOccurrenceSpecificWikiReferenceForm();
        form.setTerm("Đạo Đầu");
        form.setOccurrenceIndex(2);
        form.setContextSnippet("ngữ cảnh xuất hiện thứ hai");
        form.setWikiArticleId(ARTICLE_ID);

        ChapterWikiReferenceItemDTO resultDTO = new ChapterWikiReferenceItemDTO(
                REFERENCE_ID, CHAPTER_ID, "Đạo Đầu", "đạo đầu",
                ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC, 2, "ngữ cảnh xuất hiện thứ hai", 1L, 1L,
                ARTICLE_ID, ChapterWikiReferenceStatus.ACTIVE, null, ADMIN_ID, ADMIN_ID,
                Instant.now(), Instant.now()
        );
        when(bindOccurrenceSpecificWikiReferenceUseCase.execute(any())).thenReturn(resultDTO);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.bindOccurrenceSpecific(CHAPTER_ID, form, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã liên kết thuật ngữ \"Đạo Đầu\" (vị trí #2) với bài viết Wiki.");

        ArgumentCaptor<BindOccurrenceSpecificWikiReferenceCommand> captor =
                ArgumentCaptor.forClass(BindOccurrenceSpecificWikiReferenceCommand.class);
        verify(bindOccurrenceSpecificWikiReferenceUseCase).execute(captor.capture());

        BindOccurrenceSpecificWikiReferenceCommand command = captor.getValue();
        assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(command.term()).isEqualTo("Đạo Đầu");
        assertThat(command.occurrenceIndex()).isEqualTo(2);
        assertThat(command.contextSnippet()).isEqualTo("ngữ cảnh xuất hiện thứ hai");
        assertThat(command.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(command.actorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("Bind Occurrence-specific: rejects invalid occurrenceIndex (< 1) safely without calling use case")
    void shouldRejectInvalidOccurrenceIndexSafely() {
        mockAuthenticatedAdmin();

        BindOccurrenceSpecificWikiReferenceForm form = new BindOccurrenceSpecificWikiReferenceForm();
        form.setTerm("Đạo Đầu");
        form.setOccurrenceIndex(0);
        form.setWikiArticleId(ARTICLE_ID);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.bindOccurrenceSpecific(CHAPTER_ID, form, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Chỉ số xuất hiện phải lớn hơn hoặc bằng 1.");
        verify(bindOccurrenceSpecificWikiReferenceUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Bind: handles unpublished or missing target Wiki article gracefully with error flash message")
    void shouldHandleUnpublishedTargetGracefully() {
        mockAuthenticatedAdmin();

        BindChapterWideWikiReferenceForm form = new BindChapterWideWikiReferenceForm();
        form.setTerm("Thuật ngữ");
        form.setWikiArticleId(ARTICLE_ID);

        when(bindChapterWideWikiReferenceUseCase.execute(any()))
                .thenThrow(new TargetWikiArticleNotPublishedException(ARTICLE_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.bindChapterWide(CHAPTER_ID, form, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .asString()
                .contains("chưa được xuất bản");
    }

    @Test
    @DisplayName("Remove: delegates correct command and redirects with success message when removed")
    void shouldRemoveReferenceSuccessfully() {
        mockAuthenticatedAdmin();

        when(removeChapterWikiReferenceUseCase.execute(any())).thenReturn(true);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.removeReference(CHAPTER_ID, REFERENCE_ID, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã xóa liên kết Wiki thành công.");

        ArgumentCaptor<RemoveChapterWikiReferenceCommand> captor =
                ArgumentCaptor.forClass(RemoveChapterWikiReferenceCommand.class);
        verify(removeChapterWikiReferenceUseCase).execute(captor.capture());

        RemoveChapterWikiReferenceCommand command = captor.getValue();
        assertThat(command.referenceId()).isEqualTo(REFERENCE_ID);
        assertThat(command.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(command.actorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("Remove: sets error flash message when reference was not found")
    void shouldHandleRemoveWhenNotFound() {
        mockAuthenticatedAdmin();

        when(removeChapterWikiReferenceUseCase.execute(any())).thenReturn(false);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.removeReference(CHAPTER_ID, REFERENCE_ID, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Liên kết Wiki không tồn tại hoặc đã bị xóa trước đó.");
    }

    @Test
    @DisplayName("Actor resolution failure: propagates IllegalStateException and does not swallow into flash error")
    void shouldPropagateIllegalStateExceptionWhenActorResolutionFails() {
        when(authenticatedEmailResolver.require(authentication)).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

        BindChapterWideWikiReferenceForm form = new BindChapterWideWikiReferenceForm();
        form.setTerm("Trần Bình An");
        form.setWikiArticleId(ARTICLE_ID);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.bindChapterWide(CHAPTER_ID, form, authentication, redirectAttributes)
        ).isInstanceOf(IllegalStateException.class);
    }
}
