package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.entry.admin.form.CreateScreenEntryForm;
import com.universe.screen.entry.admin.form.EditScreenEntryForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminScreenEntryCommandController Unit Tests")
class AdminScreenEntryCommandControllerTest {

    @Mock
    private CreateScreenEntryUseCase createScreenEntryUseCase;

    @Mock
    private UpdateScreenEntryUseCase updateScreenEntryUseCase;

    @Mock
    private PublishScreenEntryUseCase publishScreenEntryUseCase;

    @Mock
    private ArchiveScreenEntryUseCase archiveScreenEntryUseCase;

    @Mock
    private DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    private AdminScreenEntryCommandController controller;

    private final UUID titleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID foreignTitleId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID entryId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    private ScreenEntryDTO sampleEntry;

    @BeforeEach
    void setUp() {
        controller = new AdminScreenEntryCommandController(
                createScreenEntryUseCase,
                updateScreenEntryUseCase,
                publishScreenEntryUseCase,
                archiveScreenEntryUseCase,
                detachScreenEntryVideoUseCase
        );

        sampleEntry = new ScreenEntryDTO(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Tóm tắt", null, null, 1200L, ScreenPublicationStatus.DRAFT,
                null, now, now
        );
    }

    @Nested
    @DisplayName("createEntry")
    class CreateEntryTests {

        @Test
        @DisplayName("Command: createEntry successfully creates entry and redirects to detail")
        void shouldCreateEntrySuccessfully() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 1", "ep-1", ScreenEntryType.EPISODE, 1, "Tóm tắt", 1200L);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(createScreenEntryUseCase.execute(any(CreateScreenEntryCommand.class))).thenReturn(sampleEntry);

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("Tập 1");
        }

        @Test
        @DisplayName("Command: createEntry with binding errors redirects to /new with error message")
        void shouldRedirectOnBindingError() {
            CreateScreenEntryForm form = new CreateScreenEntryForm();
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            bindingResult.addError(new FieldError("form", "type", "invalid", false, null, null, "Loại tập phim/video không hợp lệ."));
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/new");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Loại tập phim/video không hợp lệ.");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            assertThat(redirectAttributes.getFlashAttributes().get(BindingResult.MODEL_KEY_PREFIX + "form")).isSameAs(bindingResult);
            verify(createScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Command: createEntry with blank title redirects to /new with error message and retains form")
        void shouldRedirectWhenTitleIsBlank() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("", "ep-1", ScreenEntryType.EPISODE, 1, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/new");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Tiêu đề Screen entry không được để trống");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            verify(createScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Command: createEntry with blank slug redirects to /new with error message and retains form")
        void shouldRedirectWhenSlugIsBlank() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 1", "  ", ScreenEntryType.EPISODE, 1, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/new");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Slug URL không được để trống");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            verify(createScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Command: createEntry with null type redirects to /new with error message and no use case execution")
        void shouldRedirectWhenTypeIsNull() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 1", "ep-1", null, 1, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/new");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("loại nội dung");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            assertThat(redirectAttributes.getFlashAttributes()).doesNotContainKey("successMessage");
            verify(createScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Command: createEntry with duplicate slug catches ScreenEntrySlugAlreadyExistsException and redirects")
        void shouldRedirectOnDuplicateSlug() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 1", "ep-1", ScreenEntryType.EPISODE, 1, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(createScreenEntryUseCase.execute(any(CreateScreenEntryCommand.class)))
                    .thenThrow(new ScreenEntrySlugAlreadyExistsException("ep-1"));

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/new");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("ep-1");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
        }

        @Test
        @DisplayName("Command: createEntry with non-existent title redirects to /admin/screen/titles")
        void shouldRedirectWhenTitleNotFound() {
            CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 1", "ep-1", ScreenEntryType.EPISODE, 1, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(createScreenEntryUseCase.execute(any(CreateScreenEntryCommand.class)))
                    .thenThrow(new ScreenTitleNotFoundException(titleId));

            String view = controller.createEntry(titleId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        }
    }

    @Nested
    @DisplayName("updateEntry")
    class UpdateEntryTests {

        @Test
        @DisplayName("Command: updateEntry successfully updates and redirects to detail")
        void shouldUpdateEntrySuccessfully() {
            EditScreenEntryForm form = new EditScreenEntryForm("Tập 1 Mới", "ep-1-new", ScreenEntryType.EPISODE, 1, "Mô tả", 1300L, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(updateScreenEntryUseCase.execute(any(UpdateScreenEntryCommand.class))).thenReturn(sampleEntry);

            String view = controller.updateEntry(titleId, entryId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        }

        @Test
        @DisplayName("Command: updateEntry with binding error redirects to /edit with error message")
        void shouldRedirectOnBindingErrorDuringUpdate() {
            EditScreenEntryForm form = new EditScreenEntryForm();
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            bindingResult.addError(new FieldError("form", "number", "abc", false, null, null, "Số tập/thứ tự không hợp lệ."));
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.updateEntry(titleId, entryId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            assertThat(redirectAttributes.getFlashAttributes().get(BindingResult.MODEL_KEY_PREFIX + "form")).isSameAs(bindingResult);
            verify(updateScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Command: updateEntry with duplicate slug redirects to /edit with error message")
        void shouldRedirectOnDuplicateSlugDuringUpdate() {
            EditScreenEntryForm form = new EditScreenEntryForm("Tập 1", "ep-duplicate", ScreenEntryType.EPISODE, 1, null, null, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(updateScreenEntryUseCase.execute(any(UpdateScreenEntryCommand.class)))
                    .thenThrow(new ScreenEntrySlugAlreadyExistsException("ep-duplicate"));

            String view = controller.updateEntry(titleId, entryId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
        }

        @Test
        @DisplayName("Command: updateEntry with null type redirects to /edit with error message and no use case execution")
        void shouldRedirectWhenTypeIsNullDuringUpdate() {
            EditScreenEntryForm form = new EditScreenEntryForm("Tập 1", "ep-1", null, 1, null, null, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.updateEntry(titleId, entryId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("loại nội dung");
            assertThat(redirectAttributes.getFlashAttributes()).doesNotContainKey("successMessage");
            verify(updateScreenEntryUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("CRITICAL TITLE/ENTRY OWNERSHIP: updateEntry with foreign title rejects mutation and redirects to entries list")
        void shouldRejectUpdateWhenEntryBelongsToForeignTitle() {
            EditScreenEntryForm form = new EditScreenEntryForm("Tập 1", "ep-1", ScreenEntryType.EPISODE, 1, null, null, null, null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            // When accessing entryId under foreignTitleId, use case throws ScreenEntryNotFoundException
            when(updateScreenEntryUseCase.execute(any(UpdateScreenEntryCommand.class)))
                    .thenThrow(new ScreenEntryNotFoundException(entryId));

            String view = controller.updateEntry(foreignTitleId, entryId, form, bindingResult, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + foreignTitleId + "/entries");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        }
    }

    @Nested
    @DisplayName("publishEntry")
    class PublishEntryTests {

        @Test
        @DisplayName("Command: publishEntry successfully publishes entry and redirects to detail")
        void shouldPublishEntrySuccessfully() {
            ScreenEntryDTO publishedDto = new ScreenEntryDTO(
                    entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                    "Tóm tắt", null, null, 1200L, ScreenPublicationStatus.PUBLISHED,
                    now, now, now
            );
            when(publishScreenEntryUseCase.execute(titleId, entryId)).thenReturn(publishedDto);

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.publishEntry(titleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
            verify(publishScreenEntryUseCase).execute(titleId, entryId);
        }

        @Test
        @DisplayName("CRITICAL TITLE/ENTRY OWNERSHIP: publishEntry with foreign title rejects mutation and redirects to entries list")
        void shouldRejectPublishWhenEntryBelongsToForeignTitle() {
            when(publishScreenEntryUseCase.execute(foreignTitleId, entryId))
                    .thenThrow(new ScreenEntryNotFoundException(entryId));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.publishEntry(foreignTitleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + foreignTitleId + "/entries");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        }

        @Test
        @DisplayName("Command: publishEntry on invalid state redirects to detail with error message")
        void shouldRedirectToDetailOnIllegalStateDuringPublish() {
            when(publishScreenEntryUseCase.execute(titleId, entryId))
                    .thenThrow(new IllegalStateException("ScreenEntry is already published."));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.publishEntry(titleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("already published");
        }
    }

    @Nested
    @DisplayName("archiveEntry")
    class ArchiveEntryTests {

        @Test
        @DisplayName("Command: archiveEntry successfully archives entry and redirects to detail")
        void shouldArchiveEntrySuccessfully() {
            ScreenEntryDTO archivedDto = new ScreenEntryDTO(
                    entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                    "Tóm tắt", null, null, 1200L, ScreenPublicationStatus.ARCHIVED,
                    now, now, now
            );
            when(archiveScreenEntryUseCase.execute(titleId, entryId)).thenReturn(archivedDto);

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.archiveEntry(titleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
            verify(archiveScreenEntryUseCase).execute(titleId, entryId);
        }

        @Test
        @DisplayName("CRITICAL TITLE/ENTRY OWNERSHIP: archiveEntry with foreign title rejects mutation and redirects to entries list")
        void shouldRejectArchiveWhenEntryBelongsToForeignTitle() {
            when(archiveScreenEntryUseCase.execute(foreignTitleId, entryId))
                    .thenThrow(new ScreenEntryNotFoundException(entryId));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.archiveEntry(foreignTitleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + foreignTitleId + "/entries");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        }

        @Test
        @DisplayName("Command: archiveEntry on invalid state redirects to detail with error message")
        void shouldRedirectToDetailOnIllegalStateDuringArchive() {
            when(archiveScreenEntryUseCase.execute(titleId, entryId))
                    .thenThrow(new IllegalStateException("ScreenEntry is already archived."));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.archiveEntry(titleId, entryId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("already archived");
        }
    }

    @Nested
    @DisplayName("detachVideo")
    class DetachVideoTests {

        @Test
        @DisplayName("Command: detachVideo successfully detaches video and redirects to detail with success message")
        void shouldDetachVideoSuccessfully() {
            UUID currentVideoId = UUID.randomUUID();
            ScreenEntryDTO detached = new ScreenEntryDTO(
                    entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                    "Tóm tắt", null, null, 1200L, ScreenPublicationStatus.DRAFT,
                    null, now, now
            );
            when(detachScreenEntryVideoUseCase.execute(titleId, entryId, currentVideoId)).thenReturn(detached);

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.detachVideo(titleId, entryId, currentVideoId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("gỡ bỏ");
            verify(detachScreenEntryVideoUseCase).execute(titleId, entryId, currentVideoId);
        }

        @Test
        @DisplayName("Command: detachVideo without expectedVideoMediaAssetId redirects with error flash message (E2L1-02)")
        void shouldRedirectWithErrorWhenExpectedIdIsNull() {
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.detachVideo(titleId, entryId, null, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .contains("Không tìm thấy mã video hiện tại");
            verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
        }

        @Test
        @DisplayName("CRITICAL TITLE/ENTRY OWNERSHIP: detachVideo with foreign title redirects to entries list with error")
        void shouldRedirectToEntriesListWhenForeignTitle() {
            UUID currentVideoId = UUID.randomUUID();
            when(detachScreenEntryVideoUseCase.execute(foreignTitleId, entryId, currentVideoId))
                    .thenThrow(new ScreenEntryNotFoundException(entryId));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.detachVideo(foreignTitleId, entryId, currentVideoId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + foreignTitleId + "/entries");
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        }

        @Test
        @DisplayName("Command: detachVideo on conflict or illegal state redirects to detail with error message")
        void shouldRedirectToDetailOnError() {
            UUID currentVideoId = UUID.randomUUID();
            when(detachScreenEntryVideoUseCase.execute(titleId, entryId, currentVideoId))
                    .thenThrow(new IllegalStateException("Không thể gỡ bỏ video khỏi Screen Entry đang ở trạng thái PUBLISHED."));

            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
            String view = controller.detachVideo(titleId, entryId, currentVideoId, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId);
            assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
            assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("PUBLISHED");
        }
    }
}
