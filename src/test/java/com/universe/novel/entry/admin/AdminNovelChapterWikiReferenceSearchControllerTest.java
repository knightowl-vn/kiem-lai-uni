package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.reference.SearchTargetWikiArticlesUseCase;
import com.universe.novel.application.chapter.reference.TargetWikiArticleSearchItemDTO;
import com.universe.novel.application.chapter.reference.TargetWikiArticleSearchResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminNovelChapterWikiReferenceSearchController Unit Tests")
class AdminNovelChapterWikiReferenceSearchControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SearchTargetWikiArticlesUseCase searchTargetWikiArticlesUseCase;

    private AdminNovelChapterWikiReferenceSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterWikiReferenceSearchController(searchTargetWikiArticlesUseCase);
    }

    @Test
    @DisplayName("GET search-targets delegates to SearchTargetWikiArticlesUseCase and returns 200 OK with payload")
    void shouldReturn200WithSearchResultPayload() {
        TargetWikiArticleSearchItemDTO item = new TargetWikiArticleSearchItemDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "CHARACTER",
                "tran-binh-an",
                "Tóm tắt nhân vật",
                null
        );
        TargetWikiArticleSearchResultDTO expectedResult = new TargetWikiArticleSearchResultDTO(
                "Trần Bình An",
                List.of(item)
        );

        when(searchTargetWikiArticlesUseCase.execute("Trần Bình An")).thenReturn(expectedResult);

        ResponseEntity<TargetWikiArticleSearchResultDTO> response =
                controller.searchTargets(CHAPTER_ID, "Trần Bình An");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expectedResult);
        verify(searchTargetWikiArticlesUseCase).execute("Trần Bình An");
    }

    @Test
    @DisplayName("GET search-targets handles null query cleanly")
    void shouldHandleNullQuery() {
        TargetWikiArticleSearchResultDTO expectedResult = TargetWikiArticleSearchResultDTO.empty("");
        when(searchTargetWikiArticlesUseCase.execute(null)).thenReturn(expectedResult);

        ResponseEntity<TargetWikiArticleSearchResultDTO> response =
                controller.searchTargets(CHAPTER_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expectedResult);
        verify(searchTargetWikiArticlesUseCase).execute(null);
    }
}
