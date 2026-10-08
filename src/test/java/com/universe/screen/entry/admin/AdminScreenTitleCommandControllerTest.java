package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.CreateScreenTitleUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.form.CreateScreenTitleForm;
import com.universe.screen.entry.admin.form.EditScreenTitleForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScreenTitleCommandControllerTest {

    @Mock
    private CreateScreenTitleUseCase createScreenTitleUseCase;

    @Mock
    private UpdateScreenTitleUseCase updateScreenTitleUseCase;

    @Mock
    private PublishScreenTitleUseCase publishScreenTitleUseCase;

    @Mock
    private ArchiveScreenTitleUseCase archiveScreenTitleUseCase;

    private AdminScreenTitleCommandController controller;

    private final UUID titleId = UUID.randomUUID();
    private final UUID posterId = UUID.randomUUID();
    private final UUID backdropId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminScreenTitleCommandController(
                createScreenTitleUseCase,
                updateScreenTitleUseCase,
                publishScreenTitleUseCase,
                archiveScreenTitleUseCase
        );
    }

    // --- CREATE TITLE ---

    @Test
    @DisplayName("Command: createTitle successfully creates title and redirects to detail view")
    void shouldCreateTitleSuccessfully() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai Phần 1",
                "kiem-lai-season-1",
                "The Great Ruler",
                "Tóm tắt...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId.toString(),
                backdropId.toString()
        );

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("Kiếm Lai Phần 1");
    }

    @Test
    @DisplayName("Command: createTitle with blank title redirects to /new with error message")
    void shouldRedirectToNewWhenTitleIsBlank() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "",
                "kiem-lai-season-1",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Tiêu đề Screen không được để trống");
    }

    @Test
    @DisplayName("Command: createTitle with blank slug redirects to /new with error message")
    void shouldRedirectToNewWhenSlugIsBlank() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "   ",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Slug URL không được để trống");
    }

    @Test
    @DisplayName("Command: createTitle with null structure redirects to /new with error message")
    void shouldRedirectToNewWhenStructureIsNull() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                null,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Structure");
    }

    @Test
    @DisplayName("Command: createTitle with invalid poster UUID redirects to /new with error message")
    void shouldRedirectToNewWhenPosterUuidIsMalformed() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                "not-a-valid-uuid",
                null
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("định dạng UUID");
    }

    @Test
    @DisplayName("Command: createTitle with duplicate slug catches ScreenTitleSlugAlreadyExistsException and redirects")
    void shouldRedirectToNewOnDuplicateSlug() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new ScreenTitleSlugAlreadyExistsException("kiem-lai"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("kiem-lai");
    }

    @Test
    @DisplayName("Command: createTitle with non-existent media catches ScreenMediaAssetNotFoundException")
    void shouldRedirectToNewOnMissingMediaAsset() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId.toString(),
                null
        );

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new ScreenMediaAssetNotFoundException(posterId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: createTitle with non-IMAGE media catches InvalidScreenMediaAssetTypeException")
    void shouldRedirectToNewOnInvalidMediaType() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId.toString(),
                null
        );

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new InvalidScreenMediaAssetTypeException(posterId, "VIDEO"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    // --- UPDATE TITLE ---

    @Test
    @DisplayName("Command: updateTitle successfully updates and redirects to detail view")
    void shouldUpdateTitleSuccessfully() {
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Kiếm Lai Phần 1 (Cập nhật)",
                "kiem-lai-season-1",
                "The Great Ruler",
                "Tóm tắt mới",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        ScreenTitleDTO updatedDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1 (Cập nhật)",
                "The Great Ruler",
                "Tóm tắt mới",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );

        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenReturn(updatedDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
    }

    @Test
    @DisplayName("Command: updateTitle with validation error redirects to /edit")
    void shouldRedirectToEditOnValidationError() {
        EditScreenTitleForm form = new EditScreenTitleForm(
                "",
                "kiem-lai-season-1",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: updateTitle with non-existent title redirects to /titles")
    void shouldRedirectToListWhenTitleNotFoundOnUpdate() {
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class)))
                .thenThrow(new ScreenTitleNotFoundException(titleId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: updateTitle with duplicate slug redirects to /edit")
    void shouldRedirectToEditOnDuplicateSlugDuringUpdate() {
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Kiếm Lai",
                "kiem-lai-duplicate",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class)))
                .thenThrow(new ScreenTitleSlugAlreadyExistsException("kiem-lai-duplicate"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    // --- PUBLISH TITLE ---

    @Test
    @DisplayName("Command: publishTitle successfully publishes draft title and redirects to detail")
    void shouldPublishTitleSuccessfully() {
        ScreenTitleDTO publishedDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai",
                "Kiếm Lai",
                null,
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );

        when(publishScreenTitleUseCase.execute(titleId)).thenReturn(publishedDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        verify(publishScreenTitleUseCase).execute(titleId);
    }

    @Test
    @DisplayName("Command: publishTitle when title not found redirects to list")
    void shouldRedirectToListWhenTitleNotFoundOnPublish() {
        when(publishScreenTitleUseCase.execute(titleId))
                .thenThrow(new ScreenTitleNotFoundException(titleId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: publishTitle with IllegalStateException redirects to detail with error")
    void shouldRedirectToDetailOnIllegalStateDuringPublish() {
        when(publishScreenTitleUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("Only DRAFT titles can be published."));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Only DRAFT titles can be published.");
    }

    // --- ARCHIVE TITLE ---

    @Test
    @DisplayName("Command: archiveTitle successfully archives title and redirects to detail")
    void shouldArchiveTitleSuccessfully() {
        ScreenTitleDTO archivedDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai",
                "Kiếm Lai",
                null,
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.ARCHIVED,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );

        when(archiveScreenTitleUseCase.execute(titleId)).thenReturn(archivedDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        verify(archiveScreenTitleUseCase).execute(titleId);
    }

    @Test
    @DisplayName("Command: archiveTitle when title not found redirects to list")
    void shouldRedirectToListWhenTitleNotFoundOnArchive() {
        when(archiveScreenTitleUseCase.execute(titleId))
                .thenThrow(new ScreenTitleNotFoundException(titleId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: archiveTitle with IllegalStateException redirects to detail with error")
    void shouldRedirectToDetailOnIllegalStateDuringArchive() {
        when(archiveScreenTitleUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("Title is already archived."));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Title is already archived.");
    }
}
