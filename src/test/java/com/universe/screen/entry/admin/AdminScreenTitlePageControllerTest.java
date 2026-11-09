package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleListUseCase;
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
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScreenTitlePageControllerTest {

    @Mock
    private GetScreenTitleListUseCase getScreenTitleListUseCase;

    @Mock
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    private AdminScreenTitlePageController controller;

    private final UUID titleId = UUID.randomUUID();
    private final UUID posterId = UUID.randomUUID();
    private final UUID backdropId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminScreenTitlePageController(
                getScreenTitleListUseCase,
                getScreenTitleDetailUseCase
        );
    }

    @Test
    @DisplayName("PageController: listPage populates titles and returns view with no-cache headers")
    void shouldRenderListPageWithTitles() {
        List<ScreenTitleDTO> mockList = List.of(
                new ScreenTitleDTO(
                        titleId,
                        "kiem-lai-season-1",
                        "Kiếm Lai Phần 1",
                        "The Great Ruler",
                        "Tóm tắt phim...",
                        ScreenStructure.SERIES,
                        ScreenPresentation.ANIMATION_3D,
                        posterId,
                        backdropId,
                        ScreenPublicationStatus.DRAFT,
                        null,
                        Instant.now(),
                        Instant.now()
                )
        );
        when(getScreenTitleListUseCase.execute()).thenReturn(mockList);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listPage(model, response);

        assertThat(view).isEqualTo("admin/screen/titles");
        assertThat(model.get("titles")).isEqualTo(mockList);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý Screen Title");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
        assertThat(model.get("activeSubMenu")).isEqualTo("titles");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("PageController: createPage prepares form, enum options, and returns create view")
    void shouldRenderCreatePage() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.createPage(model, response);

        assertThat(view).isEqualTo("admin/screen/title-create");
        assertThat(model.get("form")).isInstanceOf(CreateScreenTitleForm.class);
        assertThat(model.get("structures")).isEqualTo(ScreenStructure.values());
        assertThat(model.get("presentations")).isEqualTo(ScreenPresentation.values());
        assertThat(model.get("pageTitle")).isEqualTo("Tạo Screen Title");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
        assertThat(model.get("activeSubMenu")).isEqualTo("titles");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("PageController: detailPage loads title and returns detail view")
    void shouldRenderDetailPageWithExistingData() {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(dto);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.detailPage(titleId, model, response);

        assertThat(view).isEqualTo("admin/screen/title-detail");
        assertThat(model.get("title")).isEqualTo(dto);
        assertThat(model.get("pageTitle")).isEqualTo("Chi tiết Screen Title");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
        assertThat(model.get("activeSubMenu")).isEqualTo("titles");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("PageController: editPage loads title and populates edit form")
    void shouldRenderEditPageWithExistingData() {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(dto);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.editPage(titleId, model, response);

        assertThat(view).isEqualTo("admin/screen/title-edit");
        assertThat(model.get("title")).isEqualTo(dto);
        EditScreenTitleForm form = (EditScreenTitleForm) model.get("form");
        assertThat(form).isNotNull();
        assertThat(form.getTitle()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(form.getSlug()).isEqualTo("kiem-lai-season-1");
        assertThat(form.getOriginalTitle()).isEqualTo("The Great Ruler");
        assertThat(form.getSynopsis()).isEqualTo("Tóm tắt phim...");
        assertThat(form.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(form.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(form.getPosterMediaAssetId()).isEqualTo(posterId.toString());
        assertThat(form.getBackdropMediaAssetId()).isEqualTo(backdropId.toString());

        assertThat(model.get("structures")).isEqualTo(ScreenStructure.values());
        assertThat(model.get("presentations")).isEqualTo(ScreenPresentation.values());
        assertThat(model.get("pageTitle")).isEqualTo("Chỉnh sửa Screen Title");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
        assertThat(model.get("activeSubMenu")).isEqualTo("titles");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("PageController: detailPage redirects to list with errorMessage when title not found")
    void shouldRedirectToListWhenTitleNotFoundOnDetailPage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.detailPage(titleId, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("PageController: editPage redirects to list with errorMessage when title not found")
    void shouldRedirectToListWhenTitleNotFoundOnEditPage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(titleId, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("UX02-C2 PageController: editPage formats default Intro and Outro timestamps as MM:SS and HH:MM:SS")
    void shouldPopulateFormattedDefaultTimelineOnEditPage() {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                24,
                null,
                2024,
                "Trung Quốc",
                null,
                null,
                null,
                0L,
                105L,
                3665L
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(dto);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.editPage(titleId, model, response);

        assertThat(view).isEqualTo("admin/screen/title-edit");
        EditScreenTitleForm form = (EditScreenTitleForm) model.get("form");
        assertThat(form.getDefaultIntroStart()).isEqualTo("00:00");
        assertThat(form.getDefaultIntroEnd()).isEqualTo("01:45");
        assertThat(form.getDefaultOutroStart()).isEqualTo("01:01:05");
    }

    @Test
    @DisplayName("UX02-C2 PageController: editPage retains flashed EditScreenTitleForm on validation retry")
    void shouldRetainFlashedFormOnEditPageWhenValidationRetryOccurs() {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                titleId,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(dto);

        EditScreenTitleForm flashedForm = new EditScreenTitleForm();
        flashedForm.setTitle("Kiếm Lai Đang Sửa");
        flashedForm.setStructure(ScreenStructure.SERIES);
        flashedForm.setPresentation(ScreenPresentation.ANIMATION_3D);
        flashedForm.setDefaultIntroStart("01:30");
        flashedForm.setDefaultIntroEnd("00:45");

        ExtendedModelMap model = new ExtendedModelMap();
        model.put("form", flashedForm);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.editPage(titleId, model, response);

        assertThat(view).isEqualTo("admin/screen/title-edit");
        EditScreenTitleForm resolvedForm = (EditScreenTitleForm) model.get("form");
        assertThat(resolvedForm).isSameAs(flashedForm);
        assertThat(resolvedForm.getDefaultIntroStart()).isEqualTo("01:30");
        assertThat(resolvedForm.getDefaultIntroEnd()).isEqualTo("00:45");
        assertThat(resolvedForm.getPosterMediaAssetId()).isEqualTo(posterId.toString());
        assertThat(resolvedForm.getBackdropMediaAssetId()).isEqualTo(backdropId.toString());
    }
}
