package com.universe.wiki.entry.admin;

import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiContributionPageControllerTest {

    @Mock
    private AdminWikiContributionCoordinator coordinator;

    private AdminWikiContributionPageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminWikiContributionPageController(coordinator);
    }

    @Test
    @DisplayName("Constructor enforces non-null coordinator")
    void constructorEnforcesNonNull() {
        assertThatThrownBy(() -> new AdminWikiContributionPageController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AdminWikiContributionCoordinator cannot be null");
    }

    @Test
    @DisplayName("Default request: status defaults to NEW, page 0, size 20, sets no-cache headers")
    void shouldHandleDefaultRequest() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminWikiContributionQueuePageDTO mockPage = AdminWikiContributionQueuePageDTO.empty(0, 20);
        when(coordinator.getInboxPage(any())).thenReturn(mockPage);
        when(coordinator.getNewContributionCount()).thenReturn(5L);

        String view = controller.inboxPage(
                null,
                null,
                null,
                0,
                20,
                model,
                response
        );

        assertThat(view).isEqualTo("admin/wiki/contributions");

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());

        WikiContributionAdminFilter captured = captor.getValue();
        assertThat(captured.status()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(captured.contributionType()).isNull();
        assertThat(captured.keyword()).isNull();
        assertThat(captured.page()).isEqualTo(0);
        assertThat(captured.size()).isEqualTo(20);

        // Model attributes verification
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Quản lý đóng góp Wiki");
        assertThat(model.getAttribute("activeMenu")).isEqualTo("wiki-contributions");
        assertThat(model.getAttribute("contributionPage")).isSameAs(mockPage);
        assertThat(model.getAttribute("items")).isEqualTo(mockPage.items());
        assertThat(model.getAttribute("newContributionCount")).isEqualTo(5L);
        assertThat(model.getAttribute("selectedStatus")).isEqualTo("NEW");
        assertThat(model.getAttribute("selectedType")).isNull();
        assertThat(model.getAttribute("keyword")).isEqualTo("");
        assertThat(model.getAttribute("page")).isEqualTo(0);
        assertThat(model.getAttribute("pageSize")).isEqualTo(20);
        assertThat(model.getAttribute("statuses")).isEqualTo(WikiContributionStatus.values());
        assertThat(model.getAttribute("contributionTypes")).isEqualTo(WikiContributionType.values());

        // No-cache headers
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    @DisplayName("Status filtering: 'ALL' status passes null filter to query all statuses")
    void shouldFilterByStatusAll() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(coordinator.getInboxPage(any())).thenReturn(AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(coordinator.getNewContributionCount()).thenReturn(0L);

        controller.inboxPage("ALL", null, null, 0, 20, model, response);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());

        assertThat(captor.getValue().status()).isNull();
        assertThat(model.getAttribute("selectedStatus")).isEqualTo("ALL");
    }

    @Test
    @DisplayName("Status filtering: explicit status 'REVIEWING' passes REVIEWING to query")
    void shouldFilterBySpecificStatus() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(coordinator.getInboxPage(any())).thenReturn(AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(coordinator.getNewContributionCount()).thenReturn(3L);

        controller.inboxPage("REVIEWING", null, null, 0, 20, model, response);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());

        assertThat(captor.getValue().status()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(model.getAttribute("selectedStatus")).isEqualTo("REVIEWING");
    }

    @Test
    @DisplayName("Invalid status string: fails safe to NEW without crashing")
    void shouldFailSafeOnInvalidStatus() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(coordinator.getInboxPage(any())).thenReturn(AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(coordinator.getNewContributionCount()).thenReturn(0L);

        controller.inboxPage("INVALID_STATUS_XYZ", null, null, 0, 20, model, response);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());

        assertThat(captor.getValue().status()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(model.getAttribute("selectedStatus")).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ContributionType filtering: valid type passes enum, invalid falls back to null")
    void shouldFilterByContributionType() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(coordinator.getInboxPage(any())).thenReturn(AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(coordinator.getNewContributionCount()).thenReturn(0L);

        // 1. Valid type
        controller.inboxPage(null, "INCORRECT_INFORMATION", null, 0, 20, model, response);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());
        assertThat(captor.getValue().contributionType()).isEqualTo(WikiContributionType.INCORRECT_INFORMATION);
        assertThat(model.getAttribute("selectedType")).isEqualTo(WikiContributionType.INCORRECT_INFORMATION);

        // 2. Invalid type
        controller.inboxPage(null, "INVALID_TYPE_123", null, 0, 20, model, response);
        verify(coordinator, org.mockito.Mockito.times(2)).getInboxPage(captor.capture());
        assertThat(captor.getValue().contributionType()).isNull();
    }

    @Test
    @DisplayName("Keyword filtering: trims keyword and normalizes blank to null")
    void shouldNormalizeKeyword() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(coordinator.getInboxPage(any())).thenReturn(AdminWikiContributionQueuePageDTO.empty(0, 20));
        when(coordinator.getNewContributionCount()).thenReturn(0L);

        // Non-empty
        controller.inboxPage(null, null, "  Trần Bình An  ", 0, 20, model, response);
        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(coordinator).getInboxPage(captor.capture());
        assertThat(captor.getValue().keyword()).isEqualTo("Trần Bình An");
        assertThat(model.getAttribute("keyword")).isEqualTo("Trần Bình An");

        // Blank
        controller.inboxPage(null, null, "   ", 0, 20, model, response);
        verify(coordinator, org.mockito.Mockito.times(2)).getInboxPage(captor.capture());
        assertThat(captor.getValue().keyword()).isNull();
        assertThat(model.getAttribute("keyword")).isEqualTo("");
    }
}
