package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WikiContributionWorkflowEventPersistenceAdapter Unit Tests")
class WikiContributionWorkflowEventPersistenceAdapterTest {

    @Mock
    private SpringDataWikiContributionWorkflowEventJpaRepository repository;

    private WikiContributionWorkflowEventPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WikiContributionWorkflowEventPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Lưu sự kiện quy trình thành JPA entity với đầy đủ trường")
    void shouldSaveEventAsJpaEntity() {
        UUID eventId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();

        WikiContributionWorkflowEvent event = new WikiContributionWorkflowEvent(
                eventId,
                contributionId,
                WikiContributionEventType.REASSIGNED,
                actorId,
                targetId,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REVIEWING,
                1L,
                null,
                "Chuyển giao cho admin khác",
                now
        );

        adapter.save(event);

        ArgumentCaptor<WikiContributionWorkflowEventJpaEntity> captor =
                ArgumentCaptor.forClass(WikiContributionWorkflowEventJpaEntity.class);
        verify(repository).save(captor.capture());

        WikiContributionWorkflowEventJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(eventId.toString());
        assertThat(entity.getContributionId()).isEqualTo(contributionId.toString());
        assertThat(entity.getEventType()).isEqualTo("REASSIGNED");
        assertThat(entity.getActorUserId()).isEqualTo(actorId.toString());
        assertThat(entity.getTargetUserId()).isEqualTo(targetId.toString());
        assertThat(entity.getFromStatus()).isEqualTo("REVIEWING");
        assertThat(entity.getToStatus()).isEqualTo("REVIEWING");
        assertThat(entity.getArticleContentVersion()).isEqualTo(1L);
        assertThat(entity.getResolutionOutcome()).isNull();
        assertThat(entity.getNote()).isEqualTo("Chuyển giao cho admin khác");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Truy vấn sự kiện theo contributionId và khôi phục domain object")
    void shouldFindAndRestoreDomainEvents() {
        UUID eventId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        WikiContributionWorkflowEventJpaEntity entity = new WikiContributionWorkflowEventJpaEntity();
        entity.setId(eventId.toString());
        entity.setContributionId(contributionId.toString());
        entity.setEventType("RESOLVED");
        entity.setActorUserId(actorId.toString());
        entity.setTargetUserId(null);
        entity.setFromStatus("REVIEWING");
        entity.setToStatus("RESOLVED");
        entity.setArticleContentVersion(3L);
        entity.setResolutionOutcome("APPLIED");
        entity.setNote("Đã áp dụng thay đổi thành công");
        entity.setCreatedAt(now);

        when(repository.findByContributionIdOrderByCreatedAtAscIdAsc(contributionId.toString()))
                .thenReturn(List.of(entity));

        List<WikiContributionWorkflowEvent> results = adapter.findByContributionId(contributionId);

        assertThat(results).hasSize(1);
        WikiContributionWorkflowEvent domain = results.get(0);
        assertThat(domain.id()).isEqualTo(eventId);
        assertThat(domain.contributionId()).isEqualTo(contributionId);
        assertThat(domain.eventType()).isEqualTo(WikiContributionEventType.RESOLVED);
        assertThat(domain.actorUserId()).isEqualTo(actorId);
        assertThat(domain.targetUserId()).isNull();
        assertThat(domain.fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(domain.toStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(domain.articleContentVersion()).isEqualTo(3L);
        assertThat(domain.resolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.APPLIED);
        assertThat(domain.note()).isEqualTo("Đã áp dụng thay đổi thành công");
        assertThat(domain.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Trả danh sách rỗng khi contributionId null")
    void shouldReturnEmptyListWhenContributionIdIsNull() {
        List<WikiContributionWorkflowEvent> results = adapter.findByContributionId(null);
        assertThat(results).isEmpty();
        verify(repository, never()).findByContributionIdOrderByCreatedAtAscIdAsc(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Từ chối lưu sự kiện null")
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Sự kiện quy trình không được để trống");
    }
}
