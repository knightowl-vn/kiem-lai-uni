package com.universe.wiki.application.article.alias;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddWikiArticleAliasUseCaseTest {

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Mock
    private WikiArticleAliasRepositoryPort aliasRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private AddWikiArticleAliasUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AddWikiArticleAliasUseCase(
                articleRepositoryPort,
                aliasRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Ném WikiArticleNotFoundException nếu bài viết không tồn tại")
    void shouldThrowWhenArticleNotFound() {
        UUID articleId = UUID.randomUUID();
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new AddWikiArticleAliasCommand(articleId, "Tiểu Phu Tử")))
                .isInstanceOf(WikiArticleNotFoundException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n"})
    @DisplayName("Ném IllegalArgumentException nếu alias rỗng hoặc chỉ có khoảng trắng")
    void shouldThrowWhenAliasIsBlank(String invalidAlias) {
        UUID articleId = UUID.randomUUID();
        WikiArticle article = createMockArticle(articleId);
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> useCase.execute(new AddWikiArticleAliasCommand(articleId, invalidAlias)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Ném IllegalArgumentException nếu alias vượt quá 200 ký tự")
    void shouldThrowWhenAliasExceeds200Characters() {
        UUID articleId = UUID.randomUUID();
        WikiArticle article = createMockArticle(articleId);
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        String longAlias = "a".repeat(201);
        assertThatThrownBy(() -> useCase.execute(new AddWikiArticleAliasCommand(articleId, longAlias)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Thêm mới alias thành công: chuẩn hóa khoảng trắng, tạo id, lưu và trả về DTO")
    void shouldAddAliasSuccessfully() {
        UUID articleId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T21:00:00Z");

        WikiArticle article = createMockArticle(articleId);
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));
        when(aliasRepositoryPort.findByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử")).thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(generatedId);
        when(clockPort.now()).thenReturn(now);

        WikiArticleAliasDTO result = useCase.execute(
                new AddWikiArticleAliasCommand(articleId, "  Tiểu   Phu   Tử  ")
        );

        assertThat(result.id()).isEqualTo(generatedId);
        assertThat(result.articleId()).isEqualTo(articleId);
        assertThat(result.alias()).isEqualTo("Tiểu Phu Tử");
        assertThat(result.normalizedAlias()).isEqualTo("tiểu phu tử");
        assertThat(result.createdAt()).isEqualTo(now);

        verify(aliasRepositoryPort).save(generatedId, articleId, "Tiểu Phu Tử", "tiểu phu tử", now);
    }

    @Test
    @DisplayName("Idempotent: Thêm alias đã tồn tại trên cùng bài viết sẽ trả về DTO hiện có mà không lưu mới")
    void shouldReturnExistingWhenDuplicateOnSameArticle() {
        UUID articleId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        Instant existingTime = Instant.parse("2026-08-26T20:00:00Z");

        WikiArticle article = createMockArticle(articleId);
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        WikiArticleAliasDTO existing = new WikiArticleAliasDTO(
                existingId, articleId, "Tiểu Phu Tử", "tiểu phu tử", existingTime
        );
        when(aliasRepositoryPort.findByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử"))
                .thenReturn(Optional.of(existing));

        WikiArticleAliasDTO result = useCase.execute(
                new AddWikiArticleAliasCommand(articleId, "tiểu   phu tử")
        );

        assertThat(result).isEqualTo(existing);
        verify(aliasRepositoryPort, never()).save(any(), any(), any(), any(), any());
    }

    private WikiArticle createMockArticle(UUID id) {
        return WikiArticle.createDraft(
                id, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, UUID.randomUUID(), Instant.now()
        );
    }
}