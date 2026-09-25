package com.universe.wiki.application.contribution.workflow;

import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Wiki Contribution Workflow Use Case Tests")
class AdminWikiContributionWorkflowUseCaseTest {

    @Mock
    private WikiContributionRepositoryPort contributionRepository;

    @Mock
    private WikiArticleRepositoryPort articleRepository;

    @Mock
    private ClockPort clockPort;

    private AdminWikiContributionWorkflowUseCase useCase;

    private final Instant now = Instant.parse("2026-09-25T11:00:00Z");
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new AdminWikiContributionWorkflowUseCase(
                contributionRepository,
                articleRepository,
                clockPort
        );
    }

    private WikiContribution createSampleContribution() {
        return WikiContribution.createGeneral(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.INCORRECT_INFORMATION,
                "Đóng góp thông tin cập nhật nhân vật này",
                now.minusSeconds(300)
        );
    }

    @Test
    @DisplayName("Review action transitions NEW to REVIEWING and persists")
    void shouldExecuteReviewWorkflow() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.review(new ReviewWikiContributionCommand(id, actorId, 0L));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(result.getUpdatedAt()).isEqualTo(now);
        verify(contributionRepository).save(contribution);
    }

    @Test
    @DisplayName("Review action with mismatched expectedVersion throws WikiContributionStaleMutationException")
    void shouldThrowStaleExceptionWhenExpectedVersionMismatches() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));

        assertThatThrownBy(() -> useCase.review(new ReviewWikiContributionCommand(id, actorId, 99L)))
                .isInstanceOf(WikiContributionStaleMutationException.class);
    }

    @Test
    @DisplayName("Resolve action captures current article version and persists RESOLVED")
    void shouldExecuteResolveWorkflowWithArticleVersion() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        WikiArticle mockArticle = mock(WikiArticle.class);
        when(mockArticle.getContentVersion()).thenReturn(3L);

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(articleRepository.findById(contribution.getArticleId())).thenReturn(Optional.of(mockArticle));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, "Đã cập nhật bài viết theo thông tin chính xác."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(result.getResolutionNote()).isEqualTo("Đã cập nhật bài viết theo thông tin chính xác.");
        assertThat(result.getResolvedByUserId()).isEqualTo(actorId);
        assertThat(result.getResolvedAt()).isEqualTo(now);
        assertThat(result.getResolvedArticleContentVersion()).isEqualTo(3L);
        verify(contributionRepository).save(contribution);
    }

    @Test
    @DisplayName("Resolve action succeeds with null article version when article is missing")
    void shouldExecuteResolveWorkflowWhenArticleNotFound() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(articleRepository.findById(contribution.getArticleId())).thenReturn(Optional.empty());
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.resolve(new ResolveWikiContributionCommand(
                id, actorId, 0L, "Bài viết đã xóa nhưng vẫn ghi nhận giải quyết đóng góp."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(result.getResolvedArticleContentVersion()).isNull();
    }

    @Test
    @DisplayName("Reject action transitions to REJECTED with null resolvedArticleContentVersion")
    void shouldExecuteRejectWorkflow() {
        WikiContribution contribution = createSampleContribution();
        UUID id = contribution.getId();

        when(clockPort.now()).thenReturn(now);
        when(contributionRepository.findById(id)).thenReturn(Optional.of(contribution));
        when(contributionRepository.save(any(WikiContribution.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiContribution result = useCase.reject(new RejectWikiContributionCommand(
                id, actorId, 0L, "Từ chối đóng góp do nội dung không chính xác."
        ));

        assertThat(result.getStatus()).isEqualTo(WikiContributionStatus.REJECTED);
        assertThat(result.getResolutionNote()).isEqualTo("Từ chối đóng góp do nội dung không chính xác.");
        assertThat(result.getResolvedByUserId()).isEqualTo(actorId);
        assertThat(result.getResolvedAt()).isEqualTo(now);
        assertThat(result.getResolvedArticleContentVersion()).isNull();
        verify(contributionRepository).save(contribution);
    }

    @Test
    @DisplayName("Workflow throws WikiContributionNotFoundException when contribution does not exist")
    void shouldThrowNotFoundWhenContributionDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(contributionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.review(new ReviewWikiContributionCommand(unknownId, actorId, 0L)))
                .isInstanceOf(WikiContributionNotFoundException.class);
        assertThatThrownBy(() -> useCase.resolve(new ResolveWikiContributionCommand(unknownId, actorId, 0L, "Ghi chú hợp lệ")))
                .isInstanceOf(WikiContributionNotFoundException.class);
        assertThatThrownBy(() -> useCase.reject(new RejectWikiContributionCommand(unknownId, actorId, 0L, "Ghi chú hợp lệ")))
                .isInstanceOf(WikiContributionNotFoundException.class);
    }
}
