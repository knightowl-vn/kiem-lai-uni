package com.universe.novel.entry.admin;

import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.application.profile.NovelCoverUpload;
import com.universe.novel.application.profile.UpdateNovelProfileCommand;
import com.universe.novel.application.profile.UpdateNovelProfileUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.entry.admin.form.EditNovelProfileForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelProfileCommandControllerTest {

    private static final UUID PROFILE_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-08-22T17:30:00Z");

    @Mock
    private GetNovelProfileUseCase
            getNovelProfileUseCase;

    @Mock
    private UpdateNovelProfileUseCase
            updateNovelProfileUseCase;

    private AdminNovelProfileCommandController
            controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelProfileCommandController(
                getNovelProfileUseCase,
                updateNovelProfileUseCase
        );
    }

    @Test
    @DisplayName("POST /admin/novel/profile không có file ảnh mới -> coverUpload là null, flash success message và redirect")
    void shouldUpdateProfileSuccessfullyWithoutNewCoverFile() {
        EditNovelProfileForm form = new EditNovelProfileForm();
        form.setTitle("Kiếm Lai (Tái Bản)");
        form.setAuthor("Phong Hỏa Hí Chư Hầu");
        form.setDescription("Mô tả mới");
        form.setStatus("COMPLETED");

        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateProfile(form, model, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/profile");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Cập nhật hồ sơ Novel thành công.");

        ArgumentCaptor<UpdateNovelProfileCommand> captor =
                ArgumentCaptor.forClass(UpdateNovelProfileCommand.class);
        verify(updateNovelProfileUseCase).execute(captor.capture());

        UpdateNovelProfileCommand command = captor.getValue();
        assertThat(command.title()).isEqualTo("Kiếm Lai (Tái Bản)");
        assertThat(command.author()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(command.description()).isEqualTo("Mô tả mới");
        assertThat(command.status()).isEqualTo("COMPLETED");
        assertThat(command.coverUpload()).isNull();
    }

    @Test
    @DisplayName("POST /admin/novel/profile có file ảnh mới -> chuyển đổi MultipartFile sang NovelCoverUpload streaming và gọi usecase")
    void shouldConvertMultipartFileToNovelCoverUploadWhenFileProvided() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "coverImageFile",
                "cover.png",
                "image/png",
                new byte[]{1, 2, 3, 4}
        );

        EditNovelProfileForm form = new EditNovelProfileForm();
        form.setTitle("Kiếm Lai");
        form.setAuthor("Phong Hỏa");
        form.setDescription("Mô tả");
        form.setStatus("ONGOING");
        form.setCoverImageFile(mockFile);

        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateProfile(form, model, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/profile");

        ArgumentCaptor<UpdateNovelProfileCommand> captor =
                ArgumentCaptor.forClass(UpdateNovelProfileCommand.class);
        verify(updateNovelProfileUseCase).execute(captor.capture());

        UpdateNovelProfileCommand command = captor.getValue();
        assertThat(command.coverUpload()).isNotNull();
        NovelCoverUpload upload = command.coverUpload();
        assertThat(upload.originalFilename()).isEqualTo("cover.png");
        assertThat(upload.contentType()).isEqualTo("image/png");
        assertThat(upload.sizeBytes()).isEqualTo(4L);
        assertThat(upload.content()).isNotNull();
    }

    @Test
    @DisplayName("POST /admin/novel/profile có MultipartFile rỗng -> coverUpload là null")
    void shouldTreatEmptyMultipartFileAsNullCoverUpload() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "coverImageFile",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        EditNovelProfileForm form = new EditNovelProfileForm();
        form.setTitle("Kiếm Lai");
        form.setAuthor("Phong Hỏa");
        form.setDescription("Mô tả");
        form.setStatus("ONGOING");
        form.setCoverImageFile(emptyFile);

        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateProfile(form, model, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/profile");

        ArgumentCaptor<UpdateNovelProfileCommand> captor =
                ArgumentCaptor.forClass(UpdateNovelProfileCommand.class);
        verify(updateNovelProfileUseCase).execute(captor.capture());

        assertThat(captor.getValue().coverUpload()).isNull();
    }

    @Test
    @DisplayName("POST /admin/novel/profile khi có lỗi validation trả về view admin/novel/profile và giữ lại form input")
    void shouldRenderFormWithErrorsWhenValidationFails() {
        EditNovelProfileForm form = new EditNovelProfileForm();
        form.setTitle("");
        form.setAuthor("Phong Hỏa");
        form.setDescription("Mô tả");
        form.setStatus("ONGOING");

        when(updateNovelProfileUseCase.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Tiêu đề tiểu thuyết không được để trống."));

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả",
                null,
                "ONGOING",
                CREATED_AT,
                UPDATED_AT
        );
        when(getNovelProfileUseCase.execute()).thenReturn(existingProfile);

        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateProfile(form, model, redirectAttributes);

        assertThat(view).isEqualTo("admin/novel/profile");
        assertThat(model.getAttribute("errorMessage"))
                .isEqualTo("Tiêu đề tiểu thuyết không được để trống.");
        assertThat(model.getAttribute("form")).isEqualTo(form);
        assertThat(model.getAttribute("profile")).isEqualTo(existingProfile);
        assertThat(model.getAttribute("activeSubMenu")).isEqualTo("profile");
    }
}
