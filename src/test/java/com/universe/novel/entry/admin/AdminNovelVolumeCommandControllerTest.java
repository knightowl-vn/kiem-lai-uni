package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.novel.application.exceptions.VolumeHasPublishedChaptersException;
import com.universe.novel.application.volume.ArchiveVolumeCommand;
import com.universe.novel.application.volume.ArchiveVolumeUseCase;
import com.universe.novel.application.volume.CreateVolumeCommand;
import com.universe.novel.application.volume.CreateVolumeUseCase;
import com.universe.novel.application.volume.PublishVolumeCommand;
import com.universe.novel.application.volume.PublishVolumeUseCase;
import com.universe.novel.application.volume.RestoreVolumeCommand;
import com.universe.novel.application.volume.RestoreVolumeUseCase;
import com.universe.novel.application.volume.UpdateDraftVolumeCommand;
import com.universe.novel.application.volume.UpdateDraftVolumeUseCase;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.entry.admin.form.CreateVolumeForm;
import com.universe.novel.entry.admin.form.EditVolumeForm;

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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelVolumeCommandControllerTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final String ADMIN_EMAIL =
            "admin@example.com";

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-19T10:00:00Z"
            );

    @Mock
    private CreateVolumeUseCase
            createVolumeUseCase;

    @Mock
    private UpdateDraftVolumeUseCase
            updateDraftVolumeUseCase;

    @Mock
    private PublishVolumeUseCase
            publishVolumeUseCase;

    @Mock
    private ArchiveVolumeUseCase
            archiveVolumeUseCase;

    @Mock
    private RestoreVolumeUseCase
            restoreVolumeUseCase;

    @Mock
    private UserIdentityContract
            userIdentityContract;

    @Mock
    private Authentication
            authentication;

    private AdminNovelVolumeCommandController
            controller;

    @BeforeEach
    void setUp() {
        controller =
                new AdminNovelVolumeCommandController(
                        createVolumeUseCase,
                        updateDraftVolumeUseCase,
                        publishVolumeUseCase,
                        archiveVolumeUseCase,
                        restoreVolumeUseCase,
                        userIdentityContract
                );
    }

    @Test
    @DisplayName(
            "Create gọi use case không kèm slug và redirect chi tiết"
    )
    void shouldCreateVolumeWithServerGeneratedSlugContract() {
        stubCurrentActor();

        CreateVolumeForm form =
                new CreateVolumeForm();

        form.setTitle(
                "  Kiếm Lai - Tập 1  "
        );

        form.setDescription(
                "  Tập mở đầu.  "
        );

        form.setSortOrder(
                1
        );

        when(
                createVolumeUseCase.execute(
                        new CreateVolumeCommand(
                                "Kiếm Lai - Tập 1",
                                "Tập mở đầu.",
                                1,
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                volumeDto(
                        "DRAFT",
                        "quyen-1"
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.createVolume(
                        form,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã tạo Volume \"Kiếm Lai - Tập 1\"."
        );

        ArgumentCaptor<CreateVolumeCommand> commandCaptor =
                ArgumentCaptor.forClass(
                        CreateVolumeCommand.class
                );

        verify(
                createVolumeUseCase
        ).execute(
                commandCaptor.capture()
        );

        assertThat(
                commandCaptor.getValue().sortOrder()
        ).isEqualTo(
                1
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );
    }

    @Test
    @DisplayName(
            "Create validation thất bại thì flash errorMessage"
    )
    void shouldFlashErrorWhenCreateValidationFails() {
        CreateVolumeForm form =
                new CreateVolumeForm();

        form.setTitle(
                "   "
        );

        form.setSortOrder(
                1
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.createVolume(
                        form,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/new"
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Tên Volume không được để trống."
        );

        verify(
                createVolumeUseCase,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName(
            "Update Draft chuẩn hóa title/description và không truyền slug"
    )
    void shouldUpdateDraftWithoutPassingSlug() {
        stubCurrentActor();

        EditVolumeForm form =
                new EditVolumeForm();

        form.setTitle(
                "  Tên mới  "
        );

        form.setDescription(
                "  Mô tả mới  "
        );

        when(
                updateDraftVolumeUseCase.execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "Tên mới",
                                "Mô tả mới",
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                volumeDto(
                        "DRAFT",
                        "quyen-1"
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.updateDraftVolume(
                        VOLUME_ID,
                        form,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã cập nhật Volume \"Kiếm Lai - Tập 1\"."
        );

        verify(
                updateDraftVolumeUseCase
        ).execute(
                new UpdateDraftVolumeCommand(
                        VOLUME_ID,
                        "Tên mới",
                        "Mô tả mới",
                        ADMIN_ID
                )
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );
    }

    @Test
    @DisplayName(
            "Update Draft thất bại thì flash errorMessage"
    )
    void shouldFlashErrorWhenUpdateDraftFails() {
        stubCurrentActor();

        EditVolumeForm form =
                new EditVolumeForm();

        form.setTitle(
                "Tên mới"
        );

        when(
                updateDraftVolumeUseCase.execute(
                        new UpdateDraftVolumeCommand(
                                VOLUME_ID,
                                "Tên mới",
                                "",
                                ADMIN_ID
                        )
                )
        ).thenThrow(
                new IllegalStateException(
                        "Chỉ được cập nhật nội dung khi tập còn là bản nháp."
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.updateDraftVolume(
                        VOLUME_ID,
                        form,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
                        + "/edit"
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Chỉ được cập nhật nội dung khi tập còn là bản nháp."
        );
    }

    @Test
    @DisplayName(
            "Publish thành công rồi redirect về chi tiết"
    )
    void shouldPublishVolume() {
        stubCurrentActor();

        when(
                publishVolumeUseCase.execute(
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                volumeDto(
                        "PUBLISHED",
                        "quyen-1"
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.publishVolume(
                        VOLUME_ID,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã xuất bản Volume \"Kiếm Lai - Tập 1\"."
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );
    }

    @Test
    @DisplayName(
            "Publish thất bại thì flash errorMessage"
    )
    void shouldFlashErrorWhenPublishFails() {
        stubCurrentActor();

        when(
                publishVolumeUseCase.execute(
                        new PublishVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenThrow(
                new IllegalStateException(
                        "Chỉ tập ở trạng thái DRAFT mới được xuất bản."
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.publishVolume(
                VOLUME_ID,
                authentication,
                redirectAttributes
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Không thể xuất bản Volume. Chỉ tập ở trạng thái DRAFT mới được xuất bản."
        );
    }

    @Test
    @DisplayName(
            "Archive thành công rồi redirect về chi tiết"
    )
    void shouldArchiveVolume() {
        stubCurrentActor();

        when(
                archiveVolumeUseCase.execute(
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                volumeDto(
                        "ARCHIVED",
                        "quyen-1"
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.archiveVolume(
                        VOLUME_ID,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã lưu trữ Volume \"Kiếm Lai - Tập 1\"."
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );
    }

    @Test
    @DisplayName(
            "Archive bị từ chối khi còn Chapter đã xuất bản"
    )
    void shouldFlashErrorWhenArchiveHasPublishedChapters() {
        stubCurrentActor();

        when(
                archiveVolumeUseCase.execute(
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenThrow(
                new VolumeHasPublishedChaptersException(
                        VOLUME_ID
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.archiveVolume(
                VOLUME_ID,
                authentication,
                redirectAttributes
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Không thể lưu trữ tập vì vẫn còn chương đã xuất bản: "
                        + VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Archive lifecycle không hợp lệ thì flash errorMessage"
    )
    void shouldFlashErrorWhenArchiveLifecycleIsInvalid() {
        stubCurrentActor();

        when(
                archiveVolumeUseCase.execute(
                        new ArchiveVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenThrow(
                new IllegalStateException(
                        "Tập đã ở trạng thái ARCHIVED."
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.archiveVolume(
                VOLUME_ID,
                authentication,
                redirectAttributes
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Tập đã ở trạng thái ARCHIVED."
        );
    }

    @Test
    @DisplayName(
            "Restore thành công rồi redirect về chi tiết"
    )
    void shouldRestoreVolumeAndRedirectToDetail() {
        stubCurrentActor();

        when(
                restoreVolumeUseCase.execute(
                        new RestoreVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                volumeDto(
                        "DRAFT",
                        "quyen-1"
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.restoreVolume(
                        VOLUME_ID,
                        authentication,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + VOLUME_ID
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã khôi phục Volume \"Kiếm Lai - Tập 1\" về bản nháp."
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );
    }

    @Test
    @DisplayName(
            "Restore thất bại thì flash errorMessage"
    )
    void shouldFlashErrorWhenRestoreFails() {
        stubCurrentActor();

        when(
                restoreVolumeUseCase.execute(
                        new RestoreVolumeCommand(
                                VOLUME_ID,
                                ADMIN_ID
                        )
                )
        ).thenThrow(
                new IllegalStateException(
                        "Chỉ tập ở trạng thái ARCHIVED mới được khôi phục về bản nháp."
                )
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.restoreVolume(
                VOLUME_ID,
                authentication,
                redirectAttributes
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Chỉ tập ở trạng thái ARCHIVED mới được khôi phục về bản nháp."
        );
    }

    private void stubCurrentActor() {
        when(
                authentication.getName()
        ).thenReturn(
                ADMIN_EMAIL
        );

        when(
                userIdentityContract.findByEmail(
                        ADMIN_EMAIL
                )
        ).thenReturn(
                Optional.of(
                        new UserDTO(
                                ADMIN_ID,
                                ADMIN_EMAIL,
                                "Admin",
                                null,
                                "ACTIVE",
                                "ADMIN",
                                NOW
                        )
                )
        );
    }

    private VolumeDTO volumeDto(
            String status,
            String slug
    ) {
        return new VolumeDTO(
                VOLUME_ID,
                "Kiếm Lai - Tập 1",
                slug,
                "Tập đầu tiên.",
                1,
                status,
                ADMIN_ID,
                ADMIN_ID,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                2L
        );
    }
}
