package com.universe.wiki.infrastructure.persistence.revision;

import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;
import com.universe.wiki.contracts.dto
.WikiArticleRevisionDetailDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticleRevisionQueryAdapterTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
            );

    @Mock
    private SpringDataWikiArticleRevisionJpaRepository
            repository;

    private WikiArticleRevisionQueryAdapter
            queryAdapter;

    @BeforeEach
    void setUp() {
        queryAdapter =
                new WikiArticleRevisionQueryAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "Lấy lịch sử revision theo thứ tự mới nhất trước"
    )
    void shouldFindRevisionPageByArticleId() {
        WikiArticleRevisionJpaEntity entity =
                createEntity();

        Pageable pageable =
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Direction.DESC,
                                "revisionNumber"
                        )
                );

        PageImpl<WikiArticleRevisionJpaEntity>
                entityPage =
                new PageImpl<>(
                        List.of(entity),
                        pageable,
                        1L
                );

        when(
                repository.findByArticleId(
                        ARTICLE_ID.toString(),
                        pageable
                )
        ).thenReturn(
                entityPage
        );

        WikiArticleRevisionPageDTO result =
                queryAdapter.findPageByArticleId(
                        ARTICLE_ID,
                        0,
                        20
                );

        assertThat(result.items())
                .hasSize(1);

        assertThat(result.items().get(0).id())
                .isEqualTo(
                        REVISION_ID
                );

        assertThat(
                result.items()
                        .get(0)
                        .articleId()
        )
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(
                result.items()
                        .get(0)
                        .revisionNumber()
        )
                .isEqualTo(4L);

        assertThat(
                result.items()
                        .get(0)
                        .changeType()
        )
                .isEqualTo(
                        "UPDATE_PUBLISHED"
                );

        assertThat(result.totalElements())
                .isEqualTo(1L);

        assertThat(result.totalPages())
                .isEqualTo(1);

        verify(repository)
                .findByArticleId(
                        ARTICLE_ID.toString(),
                        pageable
                );
    }

    @Test
    @DisplayName(
            "Trả trang rỗng khi bài chưa có revision"
    )
    void shouldReturnEmptyRevisionPage() {
        Pageable pageable =
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Direction.DESC,
                                "revisionNumber"
                        )
                );

        PageImpl<WikiArticleRevisionJpaEntity>
                emptyPage =
                new PageImpl<>(
                        List.of(),
                        pageable,
                        0L
                );

        when(
                repository.findByArticleId(
                        ARTICLE_ID.toString(),
                        pageable
                )
        ).thenReturn(
                emptyPage
        );

        WikiArticleRevisionPageDTO result =
                queryAdapter.findPageByArticleId(
                        ARTICLE_ID,
                        0,
                        20
                );

        assertThat(result.items())
                .isEmpty();

        assertThat(result.totalElements())
                .isZero();

        assertThat(result.totalPages())
                .isZero();

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();
    }

    @Test
    @DisplayName(
            "Không gọi repository khi article ID null"
    )
    void shouldReturnEmptyWhenArticleIdIsNull() {
        WikiArticleRevisionPageDTO result =
                queryAdapter.findPageByArticleId(
                        null,
                        0,
                        20
                );

        assertThat(result.items())
                .isEmpty();

        assertThat(result.totalElements())
                .isZero();

        verify(
                repository,
                never()
        ).findByArticleId(
                anyString(),
                any(Pageable.class)
        );
    }

    private WikiArticleRevisionJpaEntity
            createEntity() {

        WikiArticleRevisionJpaEntity entity =
                new WikiArticleRevisionJpaEntity();

        entity.setId(
                REVISION_ID.toString()
        );

        entity.setArticleId(
                ARTICLE_ID.toString()
        );

        entity.setRevisionNumber(4L);

        entity.setTitle(
                "Trần Bình An"
        );

        entity.setSlug(
                "tran-binh-an"
        );

        entity.setArticleType(
                "CHARACTER"
        );

        entity.setSummary(
                "Tóm tắt mới."
        );

        entity.setContent(
                "Nội dung mới."
        );

        entity.setStatus(
                "PUBLISHED"
        );

        entity.setChangeType(
                "UPDATE_PUBLISHED"
        );

        entity.setEditSummary(
                "Bổ sung dữ kiện chương 150"
        );

        entity.setEditedBy(
                ADMIN_ID.toString()
        );

        entity.setCreatedAt(
                CREATED_AT
        );

        return entity;
    }
    @Test
    @DisplayName(
            "Lấy chi tiết revision và ánh xạ đầy đủ snapshot"
    )
    void shouldFindRevisionDetail() {
        WikiArticleRevisionJpaEntity entity =
                createEntity();

        when(
                repository
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID.toString(),
                                4L
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        Optional<WikiArticleRevisionDetailDTO> result =
                queryAdapter
                        .findDetailByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                4L
                        );

        assertThat(result)
                .isPresent();

        WikiArticleRevisionDetailDTO revision =
                result.orElseThrow();

        assertThat(revision.id())
                .isEqualTo(
                        REVISION_ID
                );

        assertThat(revision.articleId())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(revision.revisionNumber())
                .isEqualTo(4L);

        assertThat(revision.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(revision.slug())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(revision.articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(revision.summary())
                .isEqualTo(
                        "Tóm tắt mới."
                );

        assertThat(revision.content())
                .isEqualTo(
                        "Nội dung mới."
                );

        assertThat(revision.status())
                .isEqualTo(
                        "PUBLISHED"
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        "UPDATE_PUBLISHED"
                );

        assertThat(revision.editedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(revision.createdAt())
                .isEqualTo(
                        CREATED_AT
                );

        verify(repository)
                .findByArticleIdAndRevisionNumber(
                        ARTICLE_ID.toString(),
                        4L
                );
    }
    @Test
    @DisplayName(
            "Trả Optional rỗng khi không tìm thấy revision detail"
    )
    void shouldReturnEmptyWhenRevisionDetailDoesNotExist() {
        when(
                repository
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID.toString(),
                                4L
                        )
        ).thenReturn(
                Optional.empty()
        );

        Optional<WikiArticleRevisionDetailDTO> result =
                queryAdapter
                        .findDetailByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                4L
                        );

        assertThat(result)
                .isEmpty();
    }
    @Test
    @DisplayName(
            "Không gọi repository khi tham số revision detail không hợp lệ"
    )
    void shouldReturnEmptyForInvalidRevisionDetailParameters() {
        Optional<WikiArticleRevisionDetailDTO> nullIdResult =
                queryAdapter
                        .findDetailByArticleIdAndRevisionNumber(
                                null,
                                4L
                        );

        Optional<WikiArticleRevisionDetailDTO> invalidNumberResult =
                queryAdapter
                        .findDetailByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                0L
                        );

        assertThat(nullIdResult)
                .isEmpty();

        assertThat(invalidNumberResult)
                .isEmpty();

        verify(
                repository,
                never()
        ).findByArticleIdAndRevisionNumber(
                anyString(),
                anyLong()
        );
    }
}