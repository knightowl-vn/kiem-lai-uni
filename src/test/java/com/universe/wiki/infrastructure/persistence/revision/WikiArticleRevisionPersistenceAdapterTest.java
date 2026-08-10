package com.universe.wiki.infrastructure.persistence.revision;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticleRevisionPersistenceAdapterTest {

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-06T07:30:00Z"
            );

    @Mock
    private SpringDataWikiArticleRevisionJpaRepository
            repository;

    private WikiArticleRevisionPersistenceAdapter
            persistenceAdapter;

    @BeforeEach
    void setUp() {
        persistenceAdapter =
                new WikiArticleRevisionPersistenceAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "Lưu WikiArticleRevision thành JPA entity"
    )
    void shouldSaveRevisionAsJpaEntity() {
        WikiArticleRevision revision =
                createRevision();

        ArgumentCaptor<WikiArticleRevisionJpaEntity>
                entityCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevisionJpaEntity.class
                );

        persistenceAdapter.save(revision);

        verify(repository)
                .save(
                        entityCaptor.capture()
                );

        WikiArticleRevisionJpaEntity entity =
                entityCaptor.getValue();

        assertThat(entity.getId())
                .isEqualTo(
                        REVISION_ID.toString()
                );

        assertThat(entity.getArticleId())
                .isEqualTo(
                        ARTICLE_ID.toString()
                );

        assertThat(entity.getRevisionNumber())
                .isEqualTo(1L);
        
        assertThat(entity.getContentVersion())
        .isEqualTo(1L);

        assertThat(entity.getTitle())
                .isEqualTo("Trần Bình An");

        assertThat(entity.getSlug())
                .isEqualTo("tran-binh-an");

        assertThat(entity.getArticleType())
                .isEqualTo("CHARACTER");

        assertThat(entity.getStatus())
                .isEqualTo("DRAFT");

        assertThat(entity.getChangeType())
                .isEqualTo("CREATE_DRAFT");

        assertThat(entity.getEditedBy())
                .isEqualTo(
                        ADMIN_ID.toString()
                );

        assertThat(entity.getCreatedAt())
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName(
            "Đọc JPA entity và khôi phục WikiArticleRevision"
    )
    void shouldRestoreRevisionFromJpaEntity() {
        WikiArticleRevisionJpaEntity entity =
                createEntity();

        when(
                repository
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID.toString(),
                                1L
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        Optional<WikiArticleRevision> result =
                persistenceAdapter
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                1L
                        );

        assertThat(result)
                .isPresent();

        WikiArticleRevision revision =
                result.orElseThrow();

        assertThat(revision.id())
                .isEqualTo(REVISION_ID);

        assertThat(revision.articleId())
                .isEqualTo(ARTICLE_ID);

        assertThat(revision.revisionNumber())
                .isEqualTo(1L);

        assertThat(revision.articleType())
                .isEqualTo(
                        ArticleType.CHARACTER
                );

        assertThat(revision.status())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.CREATE_DRAFT
                );

        assertThat(revision.editedBy())
                .isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName(
            "Trả Optional rỗng khi article ID null"
    )
    void shouldReturnEmptyWhenArticleIdIsNull() {
        Optional<WikiArticleRevision> result =
                persistenceAdapter
                        .findByArticleIdAndRevisionNumber(
                                null,
                                1L
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Từ chối lưu revision null"
    )
    void shouldRejectNullRevision() {
        assertThatThrownBy(() ->
                persistenceAdapter.save(null)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Wiki article revision không được để trống."
                );

        verify(
                repository,
                never()
        ).save(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private WikiArticleRevision createRevision() {
        return new WikiArticleRevision(
                REVISION_ID,
                ARTICLE_ID,
                1L,
                1L,
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "",
                "",
                ArticleStatus.DRAFT,
                RevisionChangeType.CREATE_DRAFT,
                "Tạo bản nháp đầu tiên",
                ADMIN_ID,
                NOW
        );
    }

    private WikiArticleRevisionJpaEntity createEntity() {
        WikiArticleRevisionJpaEntity entity =
                new WikiArticleRevisionJpaEntity();

        entity.setId(
                REVISION_ID.toString()
        );

        entity.setArticleId(
                ARTICLE_ID.toString()
        );

        entity.setRevisionNumber(1L);
        
        entity.setContentVersion(1L);

        entity.setTitle(
                "Trần Bình An"
        );

        entity.setSlug(
                "tran-binh-an"
        );

        entity.setArticleType(
                "CHARACTER"
        );

        entity.setSummary("");

        entity.setContent("");

        entity.setStatus(
                "DRAFT"
        );

        entity.setChangeType(
                "CREATE_DRAFT"
        );

        entity.setEditSummary(
                "Tạo bản nháp đầu tiên"
        );

        entity.setEditedBy(
                ADMIN_ID.toString()
        );

        entity.setCreatedAt(NOW);

        return entity;
    }
}