package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
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

    @Mock
    private WikiContributionWorkflowEventRepositoryPort workflowEventRepository;

    @Mock
    private com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort creditRepository;

    private GetWikiContributionAdminDetailUseCase useCase;

    private final Instant now = Instant.parse("2026-09-25T11:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetWikiContributionAdminDetailUseCase(
                contributionRepository,
                sourceRepository,
                workflowEventRepository,
                creditRepository
        );
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
        when(workflowEventRepository.findByContributionId(contributionId)).thenReturn(List.of());

        WikiContributionAdminDetail detail = useCase.execute(contributionId);

        assertThat(detail.contributionId()).isEqualTo(contributionId);
        assertThat(detail.articleTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(detail.message()).isEqualTo("Lỗi chính tả đoạn này cần sửa");
        assertThat(detail.selectedText()).isEqualTo("văn bản chọn");
        assertThat(detail.sources()).hasSize(2);
        assertThat(detail.sources().get(0).getUrl()).isEqualTo("/wiki/character/tran-binh-an");
        assertThat(detail.sources().get(1).getUrl()).isEqualTo("https://example.com/source");
        assertThat(detail.events()).isEmpty();
        assertThat(detail.credit()).isNull();
    }

    @Test
    @DisplayName("Returns full detail and workflow events when present")
    void shouldIncludeWorkflowEventsWhenPresent() {
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
                "Lỗi chính tả đoạn này cần được chỉnh sửa lại",
                "văn bản",
                null,
                null,
                null,
                now
        );

        UUID actorId = UUID.randomUUID();
        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.createReviewStarted(
                UUID.randomUUID(),
                contributionId,
                actorId,
                1L,
                now
        );

        when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
        when(sourceRepository.findByContributionId(contributionId)).thenReturn(List.of());
        when(workflowEventRepository.findByContributionId(contributionId)).thenReturn(List.of(event));

        WikiContributionAdminDetail detail = useCase.execute(contributionId);

        assertThat(detail.events()).hasSize(1);
        assertThat(detail.events().get(0).getEventType()).isEqualTo(WikiContributionEventType.REVIEW_STARTED);
        assertThat(detail.events().get(0).getActorUserId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("Throws WikiContributionNotFoundException when contribution does not exist")
    void shouldThrowNotFoundWhenContributionDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(contributionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(unknownId))
                .isInstanceOf(WikiContributionNotFoundException.class);
    }

    @Test
    @DisplayName("Maps ACTIVE credit correctly when present")
    void shouldMapActiveCreditWhenPresent() {
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
                "Lỗi chính tả đoạn này cần được chỉnh sửa lại",
                "văn bản",
                null,
                null,
                null,
                now
        );

        UUID creditId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        com.universe.wiki.domain.credit.WikiContributionCredit credit =
                com.universe.wiki.domain.credit.WikiContributionCredit.createActive(
                        creditId,
                        contributionId,
                        adminId,
                        now,
                        "Đóng góp rất chuẩn xác"
                );

        when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
        when(sourceRepository.findByContributionId(contributionId)).thenReturn(List.of());
        when(workflowEventRepository.findByContributionId(contributionId)).thenReturn(List.of());
        when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));

        WikiContributionAdminDetail detail = useCase.execute(contributionId);

        assertThat(detail.credit()).isNotNull();
        assertThat(detail.credit().status()).isEqualTo(com.universe.wiki.domain.credit.CreditStatus.ACTIVE);
        assertThat(detail.credit().creditedByUserId()).isEqualTo(adminId);
        assertThat(detail.credit().creditedAt()).isEqualTo(now);
        assertThat(detail.credit().creditNote()).isEqualTo("Đóng góp rất chuẩn xác");
        assertThat(detail.credit().revokedByUserId()).isNull();
        assertThat(detail.credit().revokedAt()).isNull();
        assertThat(detail.credit().revocationReason()).isNull();
    }

    @Test
    @DisplayName("Maps REVOKED credit correctly when present")
    void shouldMapRevokedCreditWhenPresent() {
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
                "Lỗi chính tả đoạn này cần được chỉnh sửa lại",
                "văn bản",
                null,
                null,
                null,
                now
        );

        UUID creditId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID superAdminId = UUID.randomUUID();
        Instant revokedAt = now.plusSeconds(3600);
        com.universe.wiki.domain.credit.WikiContributionCredit credit =
                com.universe.wiki.domain.credit.WikiContributionCredit.reconstitute(
                        creditId,
                        contributionId,
                        com.universe.wiki.domain.credit.CreditStatus.REVOKED,
                        adminId,
                        now,
                        "Ghi chú ban đầu",
                        superAdminId,
                        revokedAt,
                        "Phát hiện nội dung vi phạm bản quyền sau đó"
                );

        when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
        when(sourceRepository.findByContributionId(contributionId)).thenReturn(List.of());
        when(workflowEventRepository.findByContributionId(contributionId)).thenReturn(List.of());
        when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));

        WikiContributionAdminDetail detail = useCase.execute(contributionId);

        assertThat(detail.credit()).isNotNull();
        assertThat(detail.credit().status()).isEqualTo(com.universe.wiki.domain.credit.CreditStatus.REVOKED);
        assertThat(detail.credit().creditedByUserId()).isEqualTo(adminId);
        assertThat(detail.credit().creditedAt()).isEqualTo(now);
        assertThat(detail.credit().creditNote()).isEqualTo("Ghi chú ban đầu");
        assertThat(detail.credit().revokedByUserId()).isEqualTo(superAdminId);
        assertThat(detail.credit().revokedAt()).isEqualTo(revokedAt);
        assertThat(detail.credit().revocationReason()).isEqualTo("Phát hiện nội dung vi phạm bản quyền sau đó");
    }
}
