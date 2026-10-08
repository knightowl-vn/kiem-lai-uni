package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenEntryListUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminScreenEntryPageController Unit Tests")
class AdminScreenEntryPageControllerTest {

    @Mock
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @Mock
    private GetScreenEntryListUseCase getScreenEntryListUseCase;

    @Mock
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    private AdminScreenEntryPageController controller;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    private ScreenTitleDTO sampleTitle;
    private ScreenEntryDTO sampleEntry;

    @BeforeEach
    void setUp() {
        controller = new AdminScreenEntryPageController(
                getScreenTitleDetailUseCase,
                getScreenEntryListUseCase,
                getScreenEntryDetailUseCase
        );

        sampleTitle = new ScreenTitleDTO(
                titleId, "kiem-lai", "Kiếm Lai", null, "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.DRAFT, null, now, now
        );

        sampleEntry = new ScreenEntryDTO(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Synopsis", null, null, 1200L, ScreenPublicationStatus.DRAFT,
                null, now, now
        );
    }

    @Test
    @DisplayName("Page: listPage renders entries template with title and entries")
    void shouldRenderListPage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);
        when(getScreenEntryListUseCase.execute(titleId)).thenReturn(List.of(sampleEntry));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.listPage(titleId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("admin/screen/entries");
        assertThat(model.getAttribute("title")).isEqualTo(sampleTitle);
        assertThat(model.getAttribute("entries")).isEqualTo(List.of(sampleEntry));
        assertThat(model.getAttribute("activeMenu")).isEqualTo("screen");
        assertThat(model.getAttribute("activeSubMenu")).isEqualTo("titles");
    }

    @Test
    @DisplayName("Page: listPage redirects to /admin/screen/titles when title not found")
    void shouldRedirectWhenTitleNotFoundOnList() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.listPage(titleId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Page: createPage renders entry-create template with compatible types")
    void shouldRenderCreatePage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createPage(titleId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("admin/screen/entry-create");
        assertThat(model.getAttribute("title")).isEqualTo(sampleTitle);
        assertThat(model.getAttribute("form")).isNotNull();
        assertThat(model.getAttribute("types")).isNotNull();
    }

    @Test
    @DisplayName("Page: createPage redirects to /admin/screen/titles when title not found")
    void shouldRedirectWhenTitleNotFoundOnCreate() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.createPage(titleId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Page: detailPage renders entry-detail template with entry details")
    void shouldRenderDetailPage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenReturn(sampleEntry);

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.detailPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("admin/screen/entry-detail");
        assertThat(model.getAttribute("title")).isEqualTo(sampleTitle);
        assertThat(model.getAttribute("entry")).isEqualTo(sampleEntry);
    }

    @Test
    @DisplayName("Page: detailPage redirects to /admin/screen/titles when title not found")
    void shouldRedirectWhenTitleNotFoundOnDetail() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.detailPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Page: detailPage redirects to entries list when entry not found")
    void shouldRedirectWhenEntryNotFoundOnDetail() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenThrow(new ScreenEntryNotFoundException(entryId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.detailPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Page: editPage renders entry-edit template with populated form")
    void shouldRenderEditPage() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenReturn(sampleEntry);

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("admin/screen/entry-edit");
        assertThat(model.getAttribute("title")).isEqualTo(sampleTitle);
        assertThat(model.getAttribute("entry")).isEqualTo(sampleEntry);
        assertThat(model.getAttribute("form")).isNotNull();
    }

    @Test
    @DisplayName("Page: editPage redirects to /admin/screen/titles when title not found")
    void shouldRedirectWhenTitleNotFoundOnEdit() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenThrow(new ScreenTitleNotFoundException(titleId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Page: editPage redirects to entries list when entry not found")
    void shouldRedirectWhenEntryNotFoundOnEdit() {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenThrow(new ScreenEntryNotFoundException(entryId));

        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(titleId, entryId, model, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/titles/" + titleId + "/entries");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }
}
