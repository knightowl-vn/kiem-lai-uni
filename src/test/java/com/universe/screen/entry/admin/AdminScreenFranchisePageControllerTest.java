package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.dto.ScreenTitleSummaryDTO;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
import com.universe.screen.entry.admin.form.CreateScreenFranchiseForm;
import com.universe.screen.entry.admin.form.EditScreenFranchiseForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScreenFranchisePageControllerTest {

    @Mock
    private GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;

    @Mock
    private GetScreenFranchiseDetailUseCase getScreenFranchiseDetailUseCase;

    @Mock
    private GetScreenFranchiseMembersUseCase getScreenFranchiseMembersUseCase;

    @Mock
    private GetAvailableScreenTitlesUseCase getAvailableScreenTitlesUseCase;

    private AdminScreenFranchisePageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminScreenFranchisePageController(
                getScreenFranchiseListUseCase,
                getScreenFranchiseDetailUseCase,
                getScreenFranchiseMembersUseCase,
                getAvailableScreenTitlesUseCase
        );
    }

    @Test
    @DisplayName("PageController: listPage populates franchises and returns view")
    void shouldRenderListPageWithFranchises() {
        List<ScreenFranchiseDTO> mockList = List.of(
                new ScreenFranchiseDTO(UUID.randomUUID(), "Kiếm Lai", "kiem-lai", 2)
        );
        when(getScreenFranchiseListUseCase.execute()).thenReturn(mockList);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listPage(model, response);

        assertThat(view).isEqualTo("admin/screen/franchises");
        assertThat(model.get("franchises")).isEqualTo(mockList);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý Screen Franchise");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
        assertThat(model.get("activeSubMenu")).isEqualTo("franchises");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("PageController: createPage prepares form and returns view")
    void shouldRenderCreatePage() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.createPage(model, response);

        assertThat(view).isEqualTo("admin/screen/franchise-create");
        assertThat(model.get("form")).isInstanceOf(CreateScreenFranchiseForm.class);
        assertThat(model.get("pageTitle")).isEqualTo("Tạo Franchise");
        assertThat(model.get("activeMenu")).isEqualTo("screen");
    }

    @Test
    @DisplayName("PageController: editPage loads franchise and returns edit view")
    void shouldRenderEditPageWithExistingData() {
        UUID id = UUID.randomUUID();
        ScreenFranchiseDTO dto = new ScreenFranchiseDTO(id, "Kiếm Lai", "kiem-lai", 0);
        when(getScreenFranchiseDetailUseCase.execute(id)).thenReturn(dto);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.editPage(id, model, response);

        assertThat(view).isEqualTo("admin/screen/franchise-edit");
        assertThat(model.get("franchise")).isEqualTo(dto);
        EditScreenFranchiseForm form = (EditScreenFranchiseForm) model.get("form");
        assertThat(form.getName()).isEqualTo("Kiếm Lai");
        assertThat(form.getSlug()).isEqualTo("kiem-lai");
    }

    @Test
    @DisplayName("PageController: membershipsPage loads franchise, members, and availableTitles")
    void shouldRenderMembershipsPage() {
        UUID id = UUID.randomUUID();
        ScreenFranchiseDTO franchise = new ScreenFranchiseDTO(id, "Kiếm Lai", "kiem-lai", 1);
        List<ScreenFranchiseMemberDTO> members = List.of(
                new ScreenFranchiseMemberDTO(UUID.randomUUID(), id, UUID.randomUUID(), "Title 1", "title-1", 0, null, null, null)
        );
        List<ScreenTitleSummaryDTO> availableTitles = List.of(
                new ScreenTitleSummaryDTO(UUID.randomUUID(), "Title 2", "title-2", null, null, null)
        );

        when(getScreenFranchiseDetailUseCase.execute(id)).thenReturn(franchise);
        when(getScreenFranchiseMembersUseCase.execute(id)).thenReturn(members);
        when(getAvailableScreenTitlesUseCase.execute(id)).thenReturn(availableTitles);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.membershipsPage(id, model, response);

        assertThat(view).isEqualTo("admin/screen/franchise-memberships");
        assertThat(model.get("franchise")).isEqualTo(franchise);
        assertThat(model.get("members")).isEqualTo(members);
        assertThat(model.get("availableTitles")).isEqualTo(availableTitles);
        assertThat(model.get("form")).isNotNull();
    }

    @Test
    @DisplayName("PageController: detailRedirect redirects to memberships page")
    void shouldRedirectToMemberships() {
        UUID id = UUID.randomUUID();
        String redirect = controller.detailRedirect(id);
        assertThat(redirect).isEqualTo("redirect:/admin/screen/franchises/" + id + "/memberships");
    }

    @Test
    @DisplayName("PageController: editPage redirects to list with errorMessage when franchise not found")
    void shouldRedirectToListWhenFranchiseNotFoundOnEditPage() {
        UUID id = UUID.randomUUID();
        when(getScreenFranchiseDetailUseCase.execute(id)).thenThrow(new ScreenFranchiseNotFoundException(id));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("PageController: membershipsPage redirects to list with errorMessage when franchise not found")
    void shouldRedirectToListWhenFranchiseNotFoundOnMembershipsPage() {
        UUID id = UUID.randomUUID();
        when(getScreenFranchiseDetailUseCase.execute(id)).thenThrow(new ScreenFranchiseNotFoundException(id));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.membershipsPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }
}
