package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.ports.WikiContributionAdminQueryPort;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetWikiContributionAdminInboxUseCaseTest {

    @Mock
    private WikiContributionAdminQueryPort queryPort;

    private GetWikiContributionAdminInboxUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetWikiContributionAdminInboxUseCase(queryPort);
    }

    @Test
    @DisplayName("Constructor enforces non-null queryPort")
    void constructorEnforcesNonNull() {
        assertThatThrownBy(() -> new GetWikiContributionAdminInboxUseCase(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiContributionAdminQueryPort cannot be null");
    }

    @Test
    @DisplayName("getInboxPage sanitizes pagination bounds: negative page to 0, size > 50 to 50")
    void shouldSanitizePaginationBounds() {
        WikiContributionAdminPage expectedPage = WikiContributionAdminPage.empty(0, 50);
        when(queryPort.findAdminInboxPage(any())).thenReturn(expectedPage);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                WikiContributionType.INCORRECT_INFORMATION,
                "  kiem lai  ",
                -5,
                100
        );

        WikiContributionAdminPage result = useCase.getInboxPage(filter);

        assertThat(result).isSameAs(expectedPage);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(queryPort).findAdminInboxPage(captor.capture());

        WikiContributionAdminFilter captured = captor.getValue();
        assertThat(captured.page()).isEqualTo(0);
        assertThat(captured.size()).isEqualTo(50);
        assertThat(captured.status()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(captured.contributionType()).isEqualTo(WikiContributionType.INCORRECT_INFORMATION);
        assertThat(captured.keyword()).isEqualTo("kiem lai");
    }

    @Test
    @DisplayName("getInboxPage recovers from out-of-range page (requested >= totalPages) with exactly one re-query to last valid page")
    void shouldRecoverFromOutOfRangePageWithSingleReQuery() {
        // Initial query with page=10 returns empty items but totalPages=3
        WikiContributionAdminPage emptyOutOfRangePage = new WikiContributionAdminPage(
                List.of(),
                10,
                20,
                55L,
                3,
                false,
                true
        );

        // Fallback query with page=2 (totalPages - 1) returns items
        WikiContributionAdminPage validFinalPage = new WikiContributionAdminPage(
                List.of(), // mocked items
                2,
                20,
                55L,
                3,
                false,
                true
        );

        when(queryPort.findAdminInboxPage(any()))
                .thenReturn(emptyOutOfRangePage)
                .thenReturn(validFinalPage);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                10,
                20
        );

        WikiContributionAdminPage result = useCase.getInboxPage(filter);

        assertThat(result).isSameAs(validFinalPage);

        ArgumentCaptor<WikiContributionAdminFilter> captor = ArgumentCaptor.forClass(WikiContributionAdminFilter.class);
        verify(queryPort, times(2)).findAdminInboxPage(captor.capture());

        List<WikiContributionAdminFilter> calls = captor.getAllValues();
        assertThat(calls.get(0).page()).isEqualTo(10);
        assertThat(calls.get(1).page()).isEqualTo(2); // totalPages (3) - 1
    }

    @Test
    @DisplayName("getInboxPage does NOT re-query when totalElements is genuinely 0")
    void shouldNotReQueryWhenGenuinelyEmpty() {
        WikiContributionAdminPage genuinelyEmptyPage = new WikiContributionAdminPage(
                List.of(),
                0,
                20,
                0L,
                0,
                true,
                true
        );

        when(queryPort.findAdminInboxPage(any())).thenReturn(genuinelyEmptyPage);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                20
        );

        WikiContributionAdminPage result = useCase.getInboxPage(filter);

        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
        verify(queryPort, times(1)).findAdminInboxPage(any());
    }

    @Test
    @DisplayName("getInboxPage enforces non-null filter")
    void shouldEnforceNonNullFilter() {
        assertThatThrownBy(() -> useCase.getInboxPage(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Filter cannot be null");
    }

    @Test
    @DisplayName("getCountByStatus delegates to queryPort and returns count")
    void shouldDelegateCountByStatus() {
        when(queryPort.countByStatus(WikiContributionStatus.NEW)).thenReturn(12L);

        long count = useCase.getCountByStatus(WikiContributionStatus.NEW);

        assertThat(count).isEqualTo(12L);
        verify(queryPort).countByStatus(WikiContributionStatus.NEW);
    }

    @Test
    @DisplayName("getCountByStatus enforces non-null status")
    void shouldEnforceNonNullStatusInCount() {
        assertThatThrownBy(() -> useCase.getCountByStatus(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Status cannot be null");
    }
}
