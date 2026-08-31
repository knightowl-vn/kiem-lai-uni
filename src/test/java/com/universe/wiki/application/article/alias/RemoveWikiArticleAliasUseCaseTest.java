package com.universe.wiki.application.article.alias;

import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoveWikiArticleAliasUseCaseTest {

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Mock
    private WikiArticleAliasRepositoryPort aliasRepositoryPort;

    private RemoveWikiArticleAliasUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RemoveWikiArticleAliasUseCase(articleRepositoryPort, aliasRepositoryPort);
    }

    @Test
    @DisplayName("Ném WikiArticleNotFoundException nếu bài viết không tồn tại")
    void shouldThrowWhenArticleNotFound() {
        UUID articleId = UUID.randomUUID();
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new RemoveWikiArticleAliasCommand(articleId, "Tiểu Phu Tử")))
                .isInstanceOf(WikiArticleNotFoundException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n"})
    @DisplayName("Không gọi delete nếu alias truyền vào là null hoặc blank")
    void shouldDoNothingWhenAliasIsBlank(String invalidAlias) {
        UUID articleId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                articleId, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, UUID.randomUUID(), Instant.now()
        );
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        useCase.execute(new RemoveWikiArticleAliasCommand(articleId, invalidAlias));

        verify(aliasRepositoryPort, never()).deleteByArticleIdAndNormalizedAlias(any(), any());
    }

    @Test
    @DisplayName("Xóa alias theo articleId và normalizedAlias chuẩn hóa")
    void shouldDeleteAliasSuccessfully() {
        UUID articleId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                articleId, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, UUID.randomUUID(), Instant.now()
        );
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        useCase.execute(new RemoveWikiArticleAliasCommand(articleId, "  Tiểu   Phu   Tử  "));

        verify(aliasRepositoryPort).deleteByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử");
    }
}