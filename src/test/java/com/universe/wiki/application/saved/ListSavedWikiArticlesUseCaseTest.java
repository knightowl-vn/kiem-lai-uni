package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticlesQueryPort;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListSavedWikiArticlesUseCase Unit Tests")
class ListSavedWikiArticlesUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private WikiSavedArticlesQueryPort queryPort;

    private ListSavedWikiArticlesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListSavedWikiArticlesUseCase(queryPort);
    }

    @Test
    @DisplayName("Thành công: truy vấn phân trang với page và size hợp lệ")
    void shouldReturnPageWhenValidParameters() {
        SavedWikiArticlePageDTO expectedPage = new SavedWikiArticlePageDTO(
                List.of(),
                0,
                20,
                0L,
                0,
                true,
                true
        );
        when(queryPort.findSavedArticles(USER_ID, 0, 20)).thenReturn(expectedPage);

        SavedWikiArticlePageDTO result = useCase.execute(USER_ID, 0, 20);

        assertThat(result).isSameAs(expectedPage);
        verify(queryPort).findSavedArticles(USER_ID, 0, 20);
    }

    @Test
    @DisplayName("Thành công: sử dụng phương thức rút gọn (overload) với DEFAULT_PAGE_SIZE = 20")
    void shouldDelegateWithDefaultPageSizeWhenUsingOverload() {
        SavedWikiArticlePageDTO expectedPage = new SavedWikiArticlePageDTO(
                List.of(),
                1,
                20,
                25L,
                2,
                false,
                true
        );
        when(queryPort.findSavedArticles(USER_ID, 1, ListSavedWikiArticlesUseCase.DEFAULT_PAGE_SIZE))
                .thenReturn(expectedPage);

        SavedWikiArticlePageDTO result = useCase.execute(USER_ID, 1);

        assertThat(result).isSameAs(expectedPage);
        verify(queryPort).findSavedArticles(USER_ID, 1, 20);
    }

    @Test
    @DisplayName("Thất bại: ném NullPointerException khi userId là null")
    void shouldThrowWhenUserIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, 0, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi page < 0")
    void shouldThrowWhenPageIsNegative() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page không được nhỏ hơn 0");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi size < 1")
    void shouldThrowWhenSizeIsLessThanOne() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi size > 100")
    void shouldThrowWhenSizeExceedsMaximum() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được vượt quá 100");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném NullPointerException khi queryPort null trong constructor")
    void shouldThrowWhenPortIsNullInConstructor() {
        assertThatThrownBy(() -> new ListSavedWikiArticlesUseCase(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiSavedArticlesQueryPort");
    }
}
