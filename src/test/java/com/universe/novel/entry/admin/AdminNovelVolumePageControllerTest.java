package com.universe.novel.entry.admin;

import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.entry.admin.form.CreateVolumeForm;
import com.universe.novel.entry.admin.form.EditVolumeForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelVolumePageControllerTest {

    @Mock
    private GetVolumeListUseCase
            getVolumeListUseCase;
    
    @Mock
    private GetVolumeDetailUseCase
            getVolumeDetailUseCase;

    private AdminNovelVolumePageController
            controller;

    @BeforeEach
    void setUp() {

    	controller =
    	        new AdminNovelVolumePageController(
    	                getVolumeListUseCase,
    	                getVolumeDetailUseCase
    	        );
    }

    @Test
    @DisplayName(
            "Hiển thị trang danh sách Volume"
    )
    void shouldShowVolumeListPage() {

        List<VolumeDTO> expectedVolumes =
                List.of();

        when(
                getVolumeListUseCase.execute()
        ).thenReturn(
                expectedVolumes
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.listPage(
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/volumes"
        );

        assertThat(
                model.getAttribute(
                        "volumes"
                )
        ).isEqualTo(
                expectedVolumes
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Quản lý tiểu thuyết"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );

        verify(
                getVolumeListUseCase
        ).execute();

        assertThat(
                response.getHeader(
                        "Cache-Control"
                )
        ).isEqualTo(
                "no-store, no-cache, must-revalidate, max-age=0"
        );

        assertThat(
                response.getHeader(
                        "Pragma"
                )
        ).isEqualTo(
                "no-cache"
        );

        assertThat(
                response.getDateHeader(
                        "Expires"
                )
        ).isEqualTo(
                0L
        );
    }
    
    @Test
    @DisplayName(
            "Hiển thị trang chi tiết Volume"
    )
    void shouldShowVolumeDetailPage() {

        UUID volumeId =
                UUID.randomUUID();

        Instant now =
                Instant.parse(
                        "2026-08-19T10:00:00Z"
                );

        VolumeDTO volume =
                new VolumeDTO(
                        volumeId,
                        "Volume 1",
                        "volume-1",
                        "Volume mở đầu của truyện.",
                        1,
                        "DRAFT",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        now,
                        now,
                        null,
                        null,
                        1L
                );

        when(
                getVolumeDetailUseCase.execute(
                        volumeId
                )
        ).thenReturn(
                volume
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.detailPage(
                        volumeId,
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/volume-detail"
        );

        assertThat(
                model.getAttribute(
                        "volume"
                )
        ).isEqualTo(
                volume
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Chi tiết Volume"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );

        verify(
                getVolumeDetailUseCase
        ).execute(
                volumeId
        );

        assertNoCacheHeaders(
                response
        );
    }
    @Test
    @DisplayName(
            "Hiển thị trang tạo Volume"
    )
    void shouldShowCreateVolumePage() {

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.createPage(
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/volume-create"
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isInstanceOf(
                CreateVolumeForm.class
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Tạo Volume"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );
    }
    
    @Test
    @DisplayName(
            "Hiển thị trang chỉnh sửa Volume Draft"
    )
    void shouldShowEditDraftVolumePage() {

        UUID volumeId =
                UUID.randomUUID();

        Instant now =
                Instant.parse(
                        "2026-08-19T10:00:00Z"
                );

        VolumeDTO volume =
                new VolumeDTO(
                        volumeId,
                        "Quyển Một - Lung Trung Tước",
                        "quyen-1",
                        "Mô tả Volume.",
                        1,
                        "DRAFT",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        now,
                        now,
                        null,
                        null,
                        1L
                );

        when(
                getVolumeDetailUseCase.execute(
                        volumeId
                )
        ).thenReturn(
                volume
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        volumeId,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/volume-edit"
        );

        assertThat(
                model.getAttribute(
                        "volume"
                )
        ).isEqualTo(
                volume
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isInstanceOf(
                EditVolumeForm.class
        );

        EditVolumeForm form =
                (EditVolumeForm)
                        model.getAttribute(
                                "form"
                        );

        assertThat(
                form.getTitle()
        ).isEqualTo(
                volume.title()
        );

        assertThat(
                form.getDescription()
        ).isEqualTo(
                volume.description()
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Chỉnh sửa Volume"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );

        assertNoCacheHeaders(
                response
        );
    }

    @Test
    @DisplayName(
            "Redirect chi tiết khi mở trang chỉnh sửa Volume PUBLISHED"
    )
    void shouldRedirectEditPageWhenPublished() {

        UUID volumeId =
                UUID.randomUUID();

        Instant now =
                Instant.parse(
                        "2026-08-19T10:00:00Z"
                );

        VolumeDTO volume =
                new VolumeDTO(
                        volumeId,
                        "Quyển Một",
                        "quyen-1",
                        "Mô tả Volume.",
                        1,
                        "PUBLISHED",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        now,
                        now,
                        now,
                        null,
                        2L
                );

        when(
                getVolumeDetailUseCase.execute(
                        volumeId
                )
        ).thenReturn(
                volume
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        volumeId,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + volumeId
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Volume không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isNull();

        assertNoCacheHeaders(
                response
        );
    }

    @Test
    @DisplayName(
            "Redirect chi tiết khi mở trang chỉnh sửa Volume ARCHIVED"
    )
    void shouldRedirectEditPageWhenArchived() {

        UUID volumeId =
                UUID.randomUUID();

        Instant now =
                Instant.parse(
                        "2026-08-19T10:00:00Z"
                );

        VolumeDTO volume =
                new VolumeDTO(
                        volumeId,
                        "Quyển Một",
                        "quyen-1",
                        "Mô tả Volume.",
                        1,
                        "ARCHIVED",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        now,
                        now,
                        null,
                        now,
                        2L
                );

        when(
                getVolumeDetailUseCase.execute(
                        volumeId
                )
        ).thenReturn(
                volume
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        volumeId,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/volumes/"
                        + volumeId
        );

        assertThat(
                redirectAttributes.getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Volume không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isNull();

        assertNoCacheHeaders(
                response
        );
    }

    private void assertNoCacheHeaders(
            MockHttpServletResponse response
    ) {
        assertThat(
                response.getHeader(
                        "Cache-Control"
                )
        ).isEqualTo(
                "no-store, no-cache, must-revalidate, max-age=0"
        );

        assertThat(
                response.getHeader(
                        "Pragma"
                )
        ).isEqualTo(
                "no-cache"
        );

        assertThat(
                response.getDateHeader(
                        "Expires"
                )
        ).isEqualTo(
                0L
        );
    }
}