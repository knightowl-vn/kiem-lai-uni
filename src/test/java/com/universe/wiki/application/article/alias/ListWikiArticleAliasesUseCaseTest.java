package com.universe.wiki.application.article.alias;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListWikiArticleAliasesUseCaseTest {

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Mock
    private WikiArticleAliasRepositoryPort aliasRepositoryPort;

    private ListWikiArticleAliasesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListWikiArticleAliasesUseCase(articleRepositoryPort, aliasRepositoryPort);
    }

    @Test
    @DisplayName("Ném WikiArticleNotFoundException nếu bài viết không tồn tại")
    void shouldThrowWhenArticleNotFound() {
        UUID articleId = UUID.randomUUID();
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new ListWikiArticleAliasesQuery(articleId)))
                .isInstanceOf(WikiArticleNotFoundException.class);
    }

    @Test
    @DisplayName("Trả về danh sách alias của bài viết")
    void shouldReturnAliasesWhenArticleExists() {
        UUID articleId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                articleId, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, UUID.randomUUID(), Instant.now()
        );
        when(articleRepositoryPort.findById(articleId)).thenReturn(Optional.of(article));

        List<WikiArticleAliasDTO> aliases = List.of(
                new WikiArticleAliasDTO(UUID.randomUUID(), articleId, "Tiểu Phu Tử", "tiểu phu tử", Instant.now()),
                new WikiArticleAliasDTO(UUID.randomUUID(), articleId, "Trần Tiên Sinh", "trần tiên sinh", Instant.now())
        );
        when(aliasRepositoryPort.listByArticleId(articleId)).thenReturn(aliases);

        List<WikiArticleAliasDTO> result = useCase.execute(new ListWikiArticleAliasesQuery(articleId));

        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(aliases);
        verify(aliasRepositoryPort).listByArticleId(articleId);
    }
}