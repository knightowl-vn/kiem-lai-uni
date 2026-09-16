package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IsWikiArticleSavedUseCase Unit Tests")
class IsWikiArticleSavedUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;

    private IsWikiArticleSavedUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new IsWikiArticleSavedUseCase(wikiSavedArticleRepositoryPort);
    }

    @Test
    @DisplayName("Trả về true khi bài viết đã được lưu")
    void shouldReturnTrueWhenSaved() {
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(true);

        boolean result = useCase.execute(USER_ID, ARTICLE_ID);

        assertThat(result).isTrue();
        verify(wikiSavedArticleRepositoryPort).existsByUserIdAndArticleId(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName("Trả về false khi bài viết chưa được lưu")
    void shouldReturnFalseWhenNotSaved() {
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(false);

        boolean result = useCase.execute(USER_ID, ARTICLE_ID);

        assertThat(result).isFalse();
        verify(wikiSavedArticleRepositoryPort).existsByUserIdAndArticleId(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName("Trả về false khi tham số null mà không gọi repository")
    void shouldReturnFalseForNullParameters() {
        assertThat(useCase.execute(null, ARTICLE_ID)).isFalse();
        assertThat(useCase.execute(USER_ID, null)).isFalse();
        assertThat(useCase.execute(null, null)).isFalse();
    }
}
