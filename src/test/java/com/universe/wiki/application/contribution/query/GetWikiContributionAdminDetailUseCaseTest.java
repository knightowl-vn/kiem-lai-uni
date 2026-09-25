package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Get Wiki Contribution Admin Detail Use Case Tests")
class GetWikiContributionAdminDetailUseCaseTest {

    @Mock
    private WikiContributionRepositoryPort contributionRepository;

    @Mock
    private WikiContributionSourceRepositoryPort sourceRepository;

    private GetWikiContributionAdminDetailUseCase useCase;

    private final Instant now = Instant.parse("2026-09-25T11:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetWikiContributionAdminDetailUseCase(contributionRepository, sourceRepository);
    }

    @Test
    @DisplayName("Returns full detail and sources when contribution exists")
    void shouldReturnDetailWhenContributionExists() {
        UUID contributionId = UUID.randomUUID();
        WikiContribution contribution = WikiContribution.createTextSelection(
                contributionId,
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.WORDING,
                "Lỗi chính tả đoạn này cần sửa",
                "văn bản chọn",
                "tiền tố",
                "hậu tố",
                "#heading",
                now
        );

        WikiContributionSource source1 = WikiContributionSource.reconstitute(
                UUID.randomUUID(),
                contributionId,
                0,
                WikiContributionSourceType.INTERNAL,
                "/wiki/character/tran-binh-an",
                now
        );
        WikiContributionSource source2 = WikiContributionSource.reconstitute(
                UUID.randomUUID(),
                contributionId,
                1,
                WikiContributionSourceType.EXTERNAL,
                "https://example.com/source",
                now
        );

        when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
        when(sourceRepository.findByContributionId(contributionId)).thenReturn(List.of(source1, source2));

        WikiContributionAdminDetail detail = useCase.execute(contributionId);

        assertThat(detail.contributionId()).isEqualTo(contributionId);
        assertThat(detail.articleTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(detail.message()).isEqualTo("Lỗi chính tả đoạn này cần sửa");
        assertThat(detail.selectedText()).isEqualTo("văn bản chọn");
        assertThat(detail.sources()).hasSize(2);
        assertThat(detail.sources().get(0).getUrl()).isEqualTo("/wiki/character/tran-binh-an");
        assertThat(detail.sources().get(1).getUrl()).isEqualTo("https://example.com/source");
    }

    @Test
    @DisplayName("Throws WikiContributionNotFoundException when contribution does not exist")
    void shouldThrowNotFoundWhenContributionDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(contributionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(unknownId))
                .isInstanceOf(WikiContributionNotFoundException.class);
    }
}
