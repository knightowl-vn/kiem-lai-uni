package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private com.universe.screen.application.usecase.GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @Mock
    private com.universe.screen.application.ports.ScreenImageMediaPort screenImageMediaPort;

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
                archiveScreenTitleUseCase,
                getScreenTitleDetailUseCase,
                screenImageMediaPort
        );
    }

    // --- CREATE TITLE ---

    @Test
    @DisplayName("Command: createTitle successfully creates title and ignores client-submitted media UUIDs")
    void shouldCreateTitleSuccessfully() {
        UUID forgedPosterId = UUID.randomUUID();
        UUID forgedBackdropId = UUID.randomUUID();

        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai Phần 1",
                "kiem-lai-season-1",
                "The Great Ruler",
                "Tóm tắt...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                forgedPosterId.toString(),
                forgedBackdropId.toString()
        );

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );

        org.mockito.ArgumentCaptor<CreateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateScreenTitleCommand.class);
        when(createScreenTitleUseCase.execute(captor.capture())).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("Kiếm Lai Phần 1");
        assertThat(captor.getValue().slug()).isNull();
        assertThat(captor.getValue().posterMediaAssetId()).isNull();
        assertThat(captor.getValue().backdropMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Command: createTitle passes totalEpisodeCount to command for SERIES")
    void shouldPassTotalEpisodeCountOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai Phần 1");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setTotalEpisodeCount(24);

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId, "kiem-lai-phan-1", "Kiếm Lai Phần 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now(), 24
        );

        org.mockito.ArgumentCaptor<CreateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateScreenTitleCommand.class);
        when(createScreenTitleUseCase.execute(captor.capture())).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().totalEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("Command: createTitle rejects totalEpisodeCount for FEATURE")
    void shouldRejectTotalEpisodeCountForFeatureOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai Movie");
        form.setStructure(ScreenStructure.FEATURE);
        form.setPresentation(ScreenPresentation.LIVE_ACTION);
        form.setTotalEpisodeCount(1);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains("FEATURE");
    }

    @Test
    @DisplayName("Command: createTitle rejects zero or negative totalEpisodeCount for SERIES")
    void shouldRejectNonPositiveTotalEpisodeCountOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai S1");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setTotalEpisodeCount(0);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains("số nguyên dương");
    }

    @Test
    @DisplayName("Command: createTitle validates totalEpisodeCount BEFORE any media upload")
    void shouldValidateTotalEpisodeCountBeforeMediaUploadOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai Pre-Upload Validation");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);
        form.setTotalEpisodeCount(-5);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains("số nguyên dương");
        verify(screenImageMediaPort, org.mockito.Mockito.never())
                .uploadPoster(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        verify(screenImageMediaPort, org.mockito.Mockito.never())
                .uploadBackdrop(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
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
    @DisplayName("Command: createTitle with blank slug succeeds and relies on automatic slug generation")
    void shouldSucceedWhenSlugIsBlankAndRelyOnAutomaticGeneration() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai Phần 1",
                "   ",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-phan-1",
                "Kiếm Lai Phần 1",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
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
    }

    @Test
    @DisplayName("Command: createTitle uploads poster and backdrop files and binds returned media UUIDs")
    void shouldUploadPosterAndBackdropFilesOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        org.springframework.mock.web.MockMultipartFile backdropFile = new org.springframework.mock.web.MockMultipartFile(
                "backdropFile", "backdrop.png", "image/png", new byte[]{4, 5, 6}
        );

        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);
        form.setBackdropFile(backdropFile);

        UUID uploadedPosterId = UUID.randomUUID();
        UUID uploadedBackdropId = UUID.randomUUID();

        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), org.mockito.ArgumentMatchers.eq("image/jpeg"), org.mockito.ArgumentMatchers.eq("poster.jpg"), org.mockito.ArgumentMatchers.eq("Kiếm Lai")))
                .thenReturn(uploadedPosterId);
        when(screenImageMediaPort.uploadBackdrop(any(), any(Long.class), org.mockito.ArgumentMatchers.eq("image/png"), org.mockito.ArgumentMatchers.eq("backdrop.png"), org.mockito.ArgumentMatchers.eq("Kiếm Lai")))
                .thenReturn(uploadedBackdropId);

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                uploadedPosterId, uploadedBackdropId,
                ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );

        org.mockito.ArgumentCaptor<CreateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateScreenTitleCommand.class);
        when(createScreenTitleUseCase.execute(captor.capture())).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().slug()).isNull();
        assertThat(captor.getValue().posterMediaAssetId()).isEqualTo(uploadedPosterId);
        assertThat(captor.getValue().backdropMediaAssetId()).isEqualTo(uploadedBackdropId);
    }

    @Test
    @DisplayName("Command: createTitle compensates newly uploaded images when use case persistence fails")
    void shouldCompensateUploadedImagesWhenPersistenceFails() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        RuntimeException failure = new ScreenTitleSlugAlreadyExistsException("kiem-lai");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(failure);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, failure);
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
    @DisplayName("Command: createTitle with malformed poster UUID string ignores it and creates title safely")
    void shouldIgnoreMalformedPosterUuidStringOnCreate() {
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

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );

        org.mockito.ArgumentCaptor<CreateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateScreenTitleCommand.class);
        when(createScreenTitleUseCase.execute(captor.capture())).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().slug()).isNull();
        assertThat(captor.getValue().posterMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Command: createTitle compensates poster when poster upload succeeds but backdrop upload fails")
    void shouldCompensatePosterWhenBackdropUploadFails() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        org.springframework.mock.web.MockMultipartFile backdropFile = new org.springframework.mock.web.MockMultipartFile(
                "backdropFile", "backdrop.png", "image/png", new byte[]{4, 5, 6}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);
        form.setBackdropFile(backdropFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        RuntimeException backdropError = new RuntimeException("Backdrop storage network error");
        when(screenImageMediaPort.uploadBackdrop(any(), any(Long.class), any(), any(), any()))
                .thenThrow(backdropError);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, backdropError);
    }

    @Test
    @DisplayName("Command: createTitle compensates uploaded images when database throws DataIntegrityViolationException")
    void shouldCompensateImagesOnDataIntegrityViolationException() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        org.springframework.dao.DataIntegrityViolationException dbError =
                new org.springframework.dao.DataIntegrityViolationException("Duplicate key constraint violated");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(dbError);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, dbError);
    }

    @Test
    @DisplayName("Command: createTitle handles compensation failure gracefully when media cleanup throws")
    void shouldHandleCompensationFailureGracefullyOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        RuntimeException dbError = new RuntimeException("DB crash");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(dbError);

        org.mockito.Mockito.doThrow(new RuntimeException("Compensation network error"))
                .when(screenImageMediaPort).compensateUploadedImage(org.mockito.ArgumentMatchers.eq(uploadedPosterId), any());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
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
                null,
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
                null,
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
    @DisplayName("Command: updateTitle rejects conflicting remove and upload actions for poster")
    void shouldRejectConflictingRemoveAndUploadOnUpdate() {
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Kiếm Lai", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setRemovePoster(true);
        form.setPosterFile(new org.springframework.mock.web.MockMultipartFile("posterFile", "p.jpg", "image/jpeg", new byte[]{1}));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("vừa xóa poster vừa tải lên poster mới");
    }

    @Test
    @DisplayName("Command: updateTitle preserves existing poster, backdrop, and slug when no changes are requested")
    void shouldPreserveExistingMediaAndSlugOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai-original-slug", "Old Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Updated Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().slug()).isEqualTo("kiem-lai-original-slug");
        assertThat(captor.getValue().posterMediaAssetId()).isEqualTo(posterId);
        assertThat(captor.getValue().backdropMediaAssetId()).isEqualTo(backdropId);
    }

    @Test
    @DisplayName("Command: updateTitle clears poster reference when removePoster is true")
    void shouldClearPosterWhenRemovePosterIsTrue() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setRemovePoster(true);

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().posterMediaAssetId()).isNull();
        assertThat(captor.getValue().backdropMediaAssetId()).isEqualTo(backdropId);
    }

    @Test
    @DisplayName("Command: updateTitle clears backdrop reference when removeBackdrop is true")
    void shouldClearBackdropWhenRemoveBackdropIsTrue() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setRemoveBackdrop(true);

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().posterMediaAssetId()).isEqualTo(posterId);
        assertThat(captor.getValue().backdropMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Command: updateTitle ignores forged media UUID parameter and preserves trusted persisted asset ID")
    void shouldIgnoreForgedMediaUuidOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        UUID forgedId = UUID.randomUUID();
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Updated Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                forgedId.toString(), forgedId.toString()
        );

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().posterMediaAssetId()).isEqualTo(posterId);
        assertThat(captor.getValue().backdropMediaAssetId()).isEqualTo(backdropId);
    }

    @Test
    @DisplayName("Command: updateTitle uploads replacement poster and binds newly uploaded ID")
    void shouldUploadReplacementPosterOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().posterMediaAssetId()).isEqualTo(newPosterId);
        assertThat(captor.getValue().backdropMediaAssetId()).isEqualTo(backdropId);
    }

    @Test
    @DisplayName("Command: updateTitle never compensates pre-existing images when update persistence fails")
    void shouldNeverCompensatePreExistingImagesOnUpdateFailure() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        org.springframework.dao.DataIntegrityViolationException dbError =
                new org.springframework.dao.DataIntegrityViolationException("Database constraint error");
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenThrow(dbError);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        org.mockito.Mockito.verifyNoInteractions(screenImageMediaPort);
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

    // =========================================================================
    // PERSISTENCE BOUNDARY & COMPENSATION TESTS (FIX 1, FIX 2, FIX 3)
    // =========================================================================

    // --- FIX 1: POST-PERSISTENCE FAILURE DOES NOT TRIGGER COMPENSATION ---

    @Test
    @DisplayName("Fix 1: createTitle never compensates newly linked media when RedirectAttributes fails after persistence")
    void shouldNotCompensateMediaWhenRedirectAttributesFailsAfterCreatePersistence() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                uploadedPosterId, null, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenReturn(createdDto);

        RedirectAttributes throwingRedirectAttributes = new RedirectAttributesModelMap() {
            @Override
            public RedirectAttributes addFlashAttribute(String attributeName, Object attributeValue) {
                throw new RuntimeException("Flash attribute serialization error");
            }
        };

        String view = controller.createTitle(form, throwingRedirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        verify(screenImageMediaPort, never()).compensateUploadedImage(any(), any());
    }

    @Test
    @DisplayName("Fix 1: updateTitle never compensates newly linked media when RedirectAttributes fails after persistence")
    void shouldNotCompensateMediaWhenRedirectAttributesFailsAfterUpdatePersistence() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", "kiem-lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        ScreenTitleDTO updatedDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                newPosterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenReturn(updatedDto);

        RedirectAttributes throwingRedirectAttributes = new RedirectAttributesModelMap() {
            @Override
            public RedirectAttributes addFlashAttribute(String attributeName, Object attributeValue) {
                throw new RuntimeException("Flash attribute serialization error");
            }
        };

        String view = controller.updateTitle(titleId, form, throwingRedirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        verify(screenImageMediaPort, never()).compensateUploadedImage(any(), any());
    }

    // --- FIX 2: SAFE ERROR HANDLING AND AUTHORIZATION ---

    @Test
    @DisplayName("Fix 2: createTitle masks unexpected database exception diagnostics and logs server-side")
    void shouldMaskDatabaseExceptionDiagnosticsOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new DataIntegrityViolationException("INTERNAL DB SENSITIVE: constraint fk_user_sec fails on schema root_pass"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL DB SENSITIVE");
        assertThat(errorMessage).doesNotContain("root_pass");
    }

    @Test
    @DisplayName("Fix 2: updateTitle masks unexpected database exception diagnostics and logs server-side")
    void shouldMaskDatabaseExceptionDiagnosticsOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class)))
                .thenThrow(new DataIntegrityViolationException("INTERNAL SENSITIVE: connection leak in pool master_db_credentials"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL SENSITIVE");
        assertThat(errorMessage).doesNotContain("master_db_credentials");
    }

    @Test
    @DisplayName("Fix 2: createTitle rethrows AccessDeniedException and compensates newly uploaded media")
    void shouldRethrowAccessDeniedExceptionOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        AccessDeniedException authEx = new AccessDeniedException("Access is denied to user");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(authEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        assertThrows(AccessDeniedException.class, () -> controller.createTitle(form, redirectAttributes));

        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, authEx);
    }

    @Test
    @DisplayName("Fix 2: updateTitle rethrows AccessDeniedException and compensates newly uploaded media")
    void shouldRethrowAccessDeniedExceptionOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        AccessDeniedException authEx = new AccessDeniedException("Access is denied to user");
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenThrow(authEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        assertThrows(AccessDeniedException.class, () -> controller.updateTitle(titleId, form, redirectAttributes));

        verify(screenImageMediaPort).compensateUploadedImage(newPosterId, authEx);
    }

    @Test
    @DisplayName("Fix 2: publishTitle rethrows AccessDeniedException directly")
    void shouldRethrowAccessDeniedExceptionOnPublish() {
        when(publishScreenTitleUseCase.execute(titleId))
                .thenThrow(new AccessDeniedException("Forbidden action"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        assertThrows(AccessDeniedException.class, () -> controller.publishTitle(titleId, redirectAttributes));
    }

    @Test
    @DisplayName("Fix 2: archiveTitle rethrows AccessDeniedException directly")
    void shouldRethrowAccessDeniedExceptionOnArchive() {
        when(archiveScreenTitleUseCase.execute(titleId))
                .thenThrow(new AccessDeniedException("Forbidden action"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        assertThrows(AccessDeniedException.class, () -> controller.archiveTitle(titleId, redirectAttributes));
    }

    @Test
    @DisplayName("Fix 2: createTitle preserves primary exception context via addSuppressed when compensation throws")
    void shouldAttachSuppressedExceptionWhenCompensationFailsOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        RuntimeException primaryEx = new RuntimeException("Primary persistence failure");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(primaryEx);

        RuntimeException compEx = new RuntimeException("Compensation network failure");
        doThrow(compEx).when(screenImageMediaPort).compensateUploadedImage(eq(uploadedPosterId), eq(primaryEx));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(primaryEx.getSuppressed()).contains(compEx);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Fix 2: updateTitle preserves primary exception context via addSuppressed when compensation throws")
    void shouldAttachSuppressedExceptionWhenCompensationFailsOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        RuntimeException primaryEx = new RuntimeException("Primary update failure");
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenThrow(primaryEx);

        RuntimeException compEx = new RuntimeException("Compensation network failure");
        doThrow(compEx).when(screenImageMediaPort).compensateUploadedImage(eq(newPosterId), eq(primaryEx));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(primaryEx.getSuppressed()).contains(compEx);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    // --- FIX 3: ENFORCE AUTOMATIC SLUG AT WEB BOUNDARY ---

    @Test
    @DisplayName("Fix 3: createTitle ignores client-submitted forged slug and forces null to let use case generate slug")
    void shouldIgnoreForgedSlugParameterOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm(
                "Kiếm Lai Phần 1",
                "attacker-forged-slug-12345",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        ScreenTitleDTO createdDto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-phan-1",
                "Kiếm Lai Phần 1",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );

        org.mockito.ArgumentCaptor<CreateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateScreenTitleCommand.class);
        when(createScreenTitleUseCase.execute(captor.capture())).thenReturn(createdDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().slug()).isNull();
    }

    @Test
    @DisplayName("Fix 3: updateTitle preserves trusted persisted slug even when form submits a forged slug and renamed title")
    void shouldPreservePersistedSlugAndIgnoreForgedSlugOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId,
                "canonical-persisted-slug",
                "Tên Cũ",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Tên Mới Hoàn Toàn",
                "forged-tampered-slug",
                null,
                null,
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null
        );

        org.mockito.ArgumentCaptor<UpdateScreenTitleCommand> captor = org.mockito.ArgumentCaptor.forClass(UpdateScreenTitleCommand.class);
        when(updateScreenTitleUseCase.execute(captor.capture())).thenReturn(existingDto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(captor.getValue().title()).isEqualTo("Tên Mới Hoàn Toàn");
        assertThat(captor.getValue().slug()).isEqualTo("canonical-persisted-slug");
    }

    // --- SR-01: SAFE ERROR HANDLING & ILLEGALSTATEEXCEPTION MASKING ---

    @Test
    @DisplayName("SR-01: createTitle masks IllegalStateException diagnostic from Media/upload in flash message")
    void shouldMaskIllegalStateExceptionFromMediaUploadOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cloudinary API secret invalid: secret_xyz_123456"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("Cloudinary");
        assertThat(errorMessage).doesNotContain("secret_xyz_123456");
    }

    @Test
    @DisplayName("SR-01: createTitle masks IllegalStateException diagnostic from UseCase and compensates media")
    void shouldMaskIllegalStateExceptionFromUseCaseOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        IllegalStateException infrastructureEx = new IllegalStateException("Database lock table exhausted: deadlock at node-3");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(infrastructureEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("Database lock table");
        assertThat(errorMessage).doesNotContain("deadlock at node-3");
        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, infrastructureEx);
    }

    @Test
    @DisplayName("SR-01: updateTitle masks IllegalStateException diagnostic from Media/upload in flash message")
    void shouldMaskIllegalStateExceptionFromMediaUploadOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenThrow(new IllegalStateException("Storage provider B2 connection pool exhausted: cluster_b2_east"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("Storage provider");
        assertThat(errorMessage).doesNotContain("cluster_b2_east");
    }

    @Test
    @DisplayName("SR-01: updateTitle masks IllegalStateException diagnostic from UseCase and compensates newly uploaded media")
    void shouldMaskIllegalStateExceptionFromUseCaseOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        IllegalStateException infrastructureEx = new IllegalStateException("Hibernate transaction timeout: connection leak detected");
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenThrow(infrastructureEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("Hibernate transaction timeout");
        assertThat(errorMessage).doesNotContain("connection leak detected");
        verify(screenImageMediaPort).compensateUploadedImage(newPosterId, infrastructureEx);
    }

    // =========================================================================
    // RESIDUAL FINDING SR-01A & SR-01B TESTS
    // =========================================================================

    // --- SR-01A: UNTRUSTED IllegalArgumentException MASKING & LOCAL VALIDATION ---

    @Test
    @DisplayName("SR-01A: createTitle masks IllegalArgumentException diagnostic from Media upload in flash message")
    void shouldMaskIllegalArgumentExceptionFromMediaUploadOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        IllegalArgumentException downstreamEx = new IllegalArgumentException("INTERNAL_STORAGE_DIAGNOSTIC: invalid chunk hash in b2 cluster");
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenThrow(downstreamEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL_STORAGE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("b2 cluster");
    }

    @Test
    @DisplayName("SR-01A: createTitle masks IllegalArgumentException diagnostic from UseCase and compensates newly uploaded media")
    void shouldMaskIllegalArgumentExceptionFromUseCaseOnCreate() {
        org.springframework.mock.web.MockMultipartFile posterFile = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "poster.jpg", "image/jpeg", new byte[]{1, 2, 3}
        );
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);
        form.setPosterFile(posterFile);

        UUID uploadedPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(uploadedPosterId);

        IllegalArgumentException downstreamEx = new IllegalArgumentException("INTERNAL_DATABASE_DIAGNOSTIC: table lock timeout on tbl_screen_title");
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenThrow(downstreamEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL_DATABASE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("tbl_screen_title");
        verify(screenImageMediaPort).compensateUploadedImage(uploadedPosterId, downstreamEx);
    }

    @Test
    @DisplayName("SR-01A: updateTitle masks IllegalArgumentException diagnostic from Media upload in flash message")
    void shouldMaskIllegalArgumentExceptionFromMediaUploadOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        IllegalArgumentException downstreamEx = new IllegalArgumentException("INTERNAL_STORAGE_DIAGNOSTIC: invalid chunk hash in b2 cluster");
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenThrow(downstreamEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL_STORAGE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("b2 cluster");
    }

    @Test
    @DisplayName("SR-01A: updateTitle masks IllegalArgumentException diagnostic from UseCase and compensates newly uploaded media")
    void shouldMaskIllegalArgumentExceptionFromUseCaseOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );
        form.setPosterFile(newPoster);

        UUID newPosterId = UUID.randomUUID();
        when(screenImageMediaPort.uploadPoster(any(), any(Long.class), any(), any(), any()))
                .thenReturn(newPosterId);

        IllegalArgumentException downstreamEx = new IllegalArgumentException("INTERNAL_DATABASE_DIAGNOSTIC: foreign key cascade violation");
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenThrow(downstreamEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
        assertThat(errorMessage).doesNotContain("INTERNAL_DATABASE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("foreign key cascade");
        verify(screenImageMediaPort).compensateUploadedImage(newPosterId, downstreamEx);
    }

    @Test
    @DisplayName("SR-01A: createTitle preserves actionable messages for expected local form validation errors")
    void shouldPreserveActionableMessagesForLocalFormValidationOnCreate() {
        // Blank title
        CreateScreenTitleForm blankTitleForm = new CreateScreenTitleForm();
        blankTitleForm.setTitle("   ");
        blankTitleForm.setStructure(ScreenStructure.SERIES);
        blankTitleForm.setPresentation(ScreenPresentation.ANIMATION_3D);

        RedirectAttributesModelMap ra1 = new RedirectAttributesModelMap();
        String v1 = controller.createTitle(blankTitleForm, ra1);
        assertThat(v1).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra1.getFlashAttributes().get("errorMessage")).isEqualTo("Tiêu đề Screen không được để trống.");

        // Null structure
        CreateScreenTitleForm nullStructureForm = new CreateScreenTitleForm();
        nullStructureForm.setTitle("Kiếm Lai");
        nullStructureForm.setStructure(null);
        nullStructureForm.setPresentation(ScreenPresentation.ANIMATION_3D);

        RedirectAttributesModelMap ra2 = new RedirectAttributesModelMap();
        String v2 = controller.createTitle(nullStructureForm, ra2);
        assertThat(v2).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra2.getFlashAttributes().get("errorMessage")).isEqualTo("Vui lòng chọn cấu trúc (Structure: SERIES hoặc FEATURE).");

        // Null presentation
        CreateScreenTitleForm nullPresentationForm = new CreateScreenTitleForm();
        nullPresentationForm.setTitle("Kiếm Lai");
        nullPresentationForm.setStructure(ScreenStructure.SERIES);
        nullPresentationForm.setPresentation(null);

        RedirectAttributesModelMap ra3 = new RedirectAttributesModelMap();
        String v3 = controller.createTitle(nullPresentationForm, ra3);
        assertThat(v3).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra3.getFlashAttributes().get("errorMessage")).isEqualTo("Vui lòng chọn phong cách thể hiện (Presentation: ANIMATION_3D hoặc LIVE_ACTION).");

        // Unsupported image format
        CreateScreenTitleForm badFormatForm = new CreateScreenTitleForm();
        badFormatForm.setTitle("Kiếm Lai");
        badFormatForm.setStructure(ScreenStructure.SERIES);
        badFormatForm.setPresentation(ScreenPresentation.ANIMATION_3D);
        badFormatForm.setPosterFile(new org.springframework.mock.web.MockMultipartFile("posterFile", "bad.gif", "image/gif", new byte[]{1, 2}));

        RedirectAttributesModelMap ra4 = new RedirectAttributesModelMap();
        String v4 = controller.createTitle(badFormatForm, ra4);
        assertThat(v4).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra4.getFlashAttributes().get("errorMessage")).isEqualTo("Định dạng Poster không được hỗ trợ. Chỉ chấp nhận JPG, PNG hoặc WebP.");
    }

    @Test
    @DisplayName("SR-01A: createTitle preserves established ScreenTitleSlugAlreadyExistsException message")
    void shouldPreserveDuplicateSlugDomainValidationMessageOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);

        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new ScreenTitleSlugAlreadyExistsException("kiem-lai"));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("Slug screen title \"kiem-lai\" đã tồn tại trên hệ thống.");
    }

    @Test
    @DisplayName("SR-01A: createTitle preserves established ScreenMediaAssetNotFoundException message")
    void shouldPreserveMediaNotFoundDomainValidationMessageOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);

        UUID missingId = UUID.randomUUID();
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new ScreenMediaAssetNotFoundException(missingId));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("Media asset [" + missingId + "] không tồn tại trên hệ thống hoặc đã bị xóa.");
    }

    @Test
    @DisplayName("SR-01A: createTitle preserves established InvalidScreenMediaAssetTypeException message")
    void shouldPreserveInvalidMediaTypeDomainValidationMessageOnCreate() {
        CreateScreenTitleForm form = new CreateScreenTitleForm();
        form.setTitle("Kiếm Lai");
        form.setStructure(ScreenStructure.SERIES);
        form.setPresentation(ScreenPresentation.ANIMATION_3D);

        UUID invalidTypeId = UUID.randomUUID();
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class)))
                .thenThrow(new InvalidScreenMediaAssetTypeException(invalidTypeId, "VIDEO"));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.createTitle(form, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/new");
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("Media asset [" + invalidTypeId + "] không hợp lệ. Yêu cầu loại IMAGE, loại hiện tại: VIDEO.");
    }

    // --- SR-01B: PUBLISH & ARCHIVE LIFECYCLE MASKING AND DOMAIN REJECTIONS ---

    @Test
    @DisplayName("SR-01B: publishTitle masks unexpected infrastructure IllegalStateException in flash message")
    void shouldMaskIllegalStateExceptionFromInfrastructureOnPublish() {
        IllegalStateException infraEx = new IllegalStateException("INTERNAL_DATABASE_DIAGNOSTIC: connection pool exhausted at node-7");
        when(publishScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi xuất bản Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_DATABASE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("node-7");
    }

    @Test
    @DisplayName("SR-01B: publishTitle masks unexpected infrastructure IllegalArgumentException in flash message")
    void shouldMaskIllegalArgumentExceptionFromInfrastructureOnPublish() {
        IllegalArgumentException infraEx = new IllegalArgumentException("INTERNAL_DATABASE_DIAGNOSTIC: syntax error in publication timestamp binding");
        when(publishScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi xuất bản Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_DATABASE_DIAGNOSTIC");
    }

    @Test
    @DisplayName("SR-01B: archiveTitle masks unexpected infrastructure IllegalStateException in flash message")
    void shouldMaskIllegalStateExceptionFromInfrastructureOnArchive() {
        IllegalStateException infraEx = new IllegalStateException("INTERNAL_PROVIDER_DIAGNOSTIC: timeout in archive cluster");
        when(archiveScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi lưu trữ Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_PROVIDER_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("archive cluster");
    }

    @Test
    @DisplayName("SR-01B: archiveTitle masks unexpected infrastructure IllegalArgumentException in flash message")
    void shouldMaskIllegalArgumentExceptionFromInfrastructureOnArchive() {
        IllegalArgumentException infraEx = new IllegalArgumentException("INTERNAL_PROVIDER_DIAGNOSTIC: invalid state parameter");
        when(archiveScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi lưu trữ Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_PROVIDER_DIAGNOSTIC");
    }

    @Test
    @DisplayName("SR-01B: publishTitle preserves legitimate 'already published' lifecycle rejection message")
    void shouldPreserveAlreadyPublishedLifecycleRejectionOnPublish() {
        when(publishScreenTitleUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("ScreenTitle is already published."));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("ScreenTitle is already published.");
    }

    @Test
    @DisplayName("SR-01B: publishTitle preserves legitimate 'archived cannot be published' lifecycle rejection message")
    void shouldPreserveArchivedCannotBePublishedLifecycleRejectionOnPublish() {
        when(publishScreenTitleUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("Archived ScreenTitle cannot be published."));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("Archived ScreenTitle cannot be published.");
    }

    @Test
    @DisplayName("SR-01B: archiveTitle preserves legitimate 'already archived' lifecycle rejection message")
    void shouldPreserveAlreadyArchivedLifecycleRejectionOnArchive() {
        when(archiveScreenTitleUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("ScreenTitle is already archived."));

        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, ra);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat((String) ra.getFlashAttributes().get("errorMessage")).isEqualTo("ScreenTitle is already archived.");
    }

    @Test
    @DisplayName("SR-01B: publishTitle masks adversarial substring injection containing 'already published'")
    void shouldMaskPublishAdversarialSubstringInjection() {
        IllegalStateException infraEx = new IllegalStateException(
                "INTERNAL_DATABASE_DIAGNOSTIC: connection failed; already published; host=db-internal:3306"
        );
        when(publishScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.publishTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi xuất bản Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_DATABASE_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("host=");
        assertThat(errorMessage).doesNotContain("db-internal");
    }

    @Test
    @DisplayName("SR-01B: archiveTitle masks adversarial substring injection containing 'already archived'")
    void shouldMaskArchiveAdversarialSubstringInjection() {
        IllegalStateException infraEx = new IllegalStateException(
                "INTERNAL_PROVIDER_DIAGNOSTIC: already archived; storage endpoint=internal-node"
        );
        when(archiveScreenTitleUseCase.execute(titleId)).thenThrow(infraEx);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.archiveTitle(titleId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Đã xảy ra lỗi hệ thống khi lưu trữ Screen Title.");
        assertThat(errorMessage).doesNotContain("INTERNAL_PROVIDER_DIAGNOSTIC");
        assertThat(errorMessage).doesNotContain("storage endpoint");
        assertThat(errorMessage).doesNotContain("internal-node");
    }

    @Test
    @DisplayName("UpdateTitle: preserves actionable message when totalEpisodeCount is rejected by UseCase with InvalidScreenTitleTotalEpisodeCountException")
    void shouldPreserveActionableMessageWhenTotalEpisodeCountInvalidOnUpdate() {
        ScreenTitleDTO existingDto = new ScreenTitleDTO(
                titleId, "kiem-lai", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(existingDto);

        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 5
        );

        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class)))
                .thenThrow(new InvalidScreenTitleTotalEpisodeCountException(5, 10));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).contains("Tổng số tập dự kiến (5) không thể nhỏ hơn số tập mới nhất đã xuất bản (10).");
    }

    @Test
    @DisplayName("Command: updateTitle validates totalEpisodeCount BEFORE any media upload")
    void shouldValidateTotalEpisodeCountBeforeMediaUploadOnUpdate() {
        org.springframework.mock.web.MockMultipartFile newPoster = new org.springframework.mock.web.MockMultipartFile(
                "posterFile", "new-poster.jpg", "image/jpeg", new byte[]{9, 9}
        );
        EditScreenTitleForm form = new EditScreenTitleForm(
                "Title", null, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, -10
        );
        form.setPosterFile(newPoster);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateTitle(titleId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/edit");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains("số nguyên dương");
        verify(screenImageMediaPort, org.mockito.Mockito.never())
                .uploadPoster(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        verify(screenImageMediaPort, org.mockito.Mockito.never())
                .uploadBackdrop(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }
}
