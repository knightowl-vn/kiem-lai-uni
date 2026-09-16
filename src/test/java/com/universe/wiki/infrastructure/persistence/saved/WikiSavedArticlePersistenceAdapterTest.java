package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.wiki.application.exceptions.DuplicateWikiSavedArticleException;
import com.universe.wiki.domain.saved.UserSavedWikiArticle;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WikiSavedArticlePersistenceAdapter Unit Tests")
class WikiSavedArticlePersistenceAdapterTest {

    private static final UUID SAVED_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private SpringDataWikiSavedArticleJpaRepository repository;

    private WikiSavedArticlePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WikiSavedArticlePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Lưu thành công thực thể bài viết đã lưu")
    void shouldSaveEntitySuccessfully() {
        UserSavedWikiArticle savedArticle = UserSavedWikiArticle.create(SAVED_ID, USER_ID, ARTICLE_ID, NOW);

        adapter.save(savedArticle);

        ArgumentCaptor<WikiSavedArticleJpaEntity> captor = ArgumentCaptor.forClass(WikiSavedArticleJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        WikiSavedArticleJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(SAVED_ID.toString());
        assertThat(entity.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(entity.getArticleId()).isEqualTo(ARTICLE_ID.toString());
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Chuyển đổi DataIntegrityViolationException thành DuplicateWikiSavedArticleException khi vi phạm uq_wiki_saved_articles_user_article")
    void shouldTranslateDuplicateConstraintViolation() {
        UserSavedWikiArticle savedArticle = UserSavedWikiArticle.create(SAVED_ID, USER_ID, ARTICLE_ID, NOW);

        SQLException sqlException = new SQLException(
                "Duplicate entry '...' for key 'uq_wiki_saved_articles_user_article'",
                "23000",
                1062
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry",
                sqlException,
                "uq_wiki_saved_articles_user_article"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(savedArticle))
                .isInstanceOf(DuplicateWikiSavedArticleException.class)
                .hasCause(dive);
    }

    @Test
    @DisplayName("Không nuốt và ném lại DataIntegrityViolationException không liên quan (ví dụ FK failure)")
    void shouldNotTranslateUnrelatedDataIntegrityViolation() {
        UserSavedWikiArticle savedArticle = UserSavedWikiArticle.create(SAVED_ID, USER_ID, ARTICLE_ID, NOW);

        SQLException sqlException = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails",
                "23000",
                1452
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "FK violation",
                sqlException,
                "fk_wiki_saved_articles_article"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(savedArticle))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateWikiSavedArticleException.class);
    }

    @Test
    @DisplayName("Kiểm tra tồn tại existsByUserIdAndArticleId")
    void shouldCheckExistsByUserIdAndArticleId() {
        when(repository.existsByUserIdAndArticleId(USER_ID.toString(), ARTICLE_ID.toString())).thenReturn(true);

        assertThat(adapter.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).isTrue();
        assertThat(adapter.existsByUserIdAndArticleId(null, ARTICLE_ID)).isFalse();
        assertThat(adapter.existsByUserIdAndArticleId(USER_ID, null)).isFalse();
    }

    @Test
    @DisplayName("Xóa bản ghi deleteByUserIdAndArticleId")
    void shouldDeleteByUserIdAndArticleId() {
        when(repository.deleteByUserIdAndArticleId(USER_ID.toString(), ARTICLE_ID.toString())).thenReturn(1);

        assertThat(adapter.deleteByUserIdAndArticleId(USER_ID, ARTICLE_ID)).isTrue();
        assertThat(adapter.deleteByUserIdAndArticleId(null, ARTICLE_ID)).isFalse();
        assertThat(adapter.deleteByUserIdAndArticleId(USER_ID, null)).isFalse();
    }
}
