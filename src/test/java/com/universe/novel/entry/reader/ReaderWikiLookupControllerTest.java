package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.ChapterWikiReferenceResolutionSource;
import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderChapterWikiResolutionResult;
import com.universe.novel.application.reader.ReaderWikiLookupItem;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.novel.application.reader.ResolveReaderChapterWikiQuery;
import com.universe.novel.application.reader.ResolveReaderChapterWikiUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderWikiLookupController Unit Tests")
class ReaderWikiLookupControllerTest {

    @Mock
    private LookupContextualWikiUseCase lookupContextualWikiUseCase;

    @Mock
    private ResolveReaderChapterWikiUseCase resolveReaderChapterWikiUseCase;

    private ReaderWikiLookupController controller;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        controller = new ReaderWikiLookupController(
                lookupContextualWikiUseCase,
                resolveReaderChapterWikiUseCase
        );
    }

    @Test
    @DisplayName("Should preserve existing global lookup behavior when only query is provided")
    void shouldPreserveExistingGlobalLookupBehaviorWhenOnlyQueryIsProvided() {
        ReaderWikiLookupResult globalResult = new ReaderWikiLookupResult(
                "Trần Bình An",
                true,
                List.of(new ReaderWikiLookupItem(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính",
                        "Tiểu Bình An"
                ))
        );

        when(lookupContextualWikiUseCase.execute("Trần Bình An")).thenReturn(globalResult);

        ResponseEntity<ReaderChapterWikiResolutionResult> response =
                controller.lookup("Trần Bình An", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReaderChapterWikiResolutionResult body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.query()).isEqualTo("Trần Bình An");
        assertThat(body.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
        assertThat(body.hasExactMatch()).isTrue();
        assertThat(body.items()).hasSize(1);
        assertThat(body.items().get(0).id()).isEqualTo(ARTICLE_ID);
        assertThat(body.items().get(0).matchedAlias()).isEqualTo("Tiểu Bình An"); // Alias preserved for global lookup
        assertThat(body.occurrenceIndex()).isNull();

        verify(lookupContextualWikiUseCase).execute("Trần Bình An");
    }

    @Test
    @DisplayName("Should resolve occurrence binding when chapterId and valid occurrence are provided")
    void shouldResolveOccurrenceBindingWhenChapterIdAndOccurrenceProvided() {
        ReaderChapterWikiResolutionResult expectedResult = new ReaderChapterWikiResolutionResult(
                "Trần Bình An",
                ChapterWikiReferenceResolutionSource.OCCURRENCE_BINDING,
                true,
                List.of(new ReaderWikiLookupItem(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính"
                )),
                2
        );

        when(resolveReaderChapterWikiUseCase.execute(new ResolveReaderChapterWikiQuery(
                CHAPTER_ID, "Trần Bình An", 2))).thenReturn(expectedResult);

        ResponseEntity<ReaderChapterWikiResolutionResult> response =
                controller.lookup("Trần Bình An", CHAPTER_ID, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expectedResult);
        assertThat(response.getBody().source()).isEqualTo(ChapterWikiReferenceResolutionSource.OCCURRENCE_BINDING);
        assertThat(response.getBody().occurrenceIndex()).isEqualTo(2);
        assertThat(response.getBody().items().get(0).matchedAlias()).isNull();
    }

    @Test
    @DisplayName("Should resolve chapter-wide binding when chapterId is provided without occurrence")
    void shouldResolveChapterWideBindingWhenChapterIdProvidedWithoutOccurrence() {
        ReaderChapterWikiResolutionResult expectedResult = new ReaderChapterWikiResolutionResult(
                "Trần Bình An",
                ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING,
                true,
                List.of(new ReaderWikiLookupItem(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính"
                )),
                null
        );

        when(resolveReaderChapterWikiUseCase.execute(new ResolveReaderChapterWikiQuery(
                CHAPTER_ID, "Trần Bình An", null))).thenReturn(expectedResult);

        ResponseEntity<ReaderChapterWikiResolutionResult> response =
                controller.lookup("Trần Bình An", CHAPTER_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expectedResult);
        assertThat(response.getBody().source()).isEqualTo(ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING);
        assertThat(response.getBody().occurrenceIndex()).isNull();
    }

    @Test
    @DisplayName("Should sanitize invalid occurrence index <= 0 to null and not expose occurrence binding")
    void shouldSanitizeInvalidOccurrenceToNull() {
        ReaderChapterWikiResolutionResult expectedResult = new ReaderChapterWikiResolutionResult(
                "Thuật Ngữ",
                ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING,
                true,
                List.of(),
                null
        );

        when(resolveReaderChapterWikiUseCase.execute(new ResolveReaderChapterWikiQuery(
                CHAPTER_ID, "Thuật Ngữ", null))).thenReturn(expectedResult);

        ResponseEntity<ReaderChapterWikiResolutionResult> response =
                controller.lookup("Thuật Ngữ", CHAPTER_ID, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ResolveReaderChapterWikiQuery> captor =
                ArgumentCaptor.forClass(ResolveReaderChapterWikiQuery.class);
        verify(resolveReaderChapterWikiUseCase).execute(captor.capture());
        assertThat(captor.getValue().occurrenceIndex()).isNull();
    }

    @Test
    @DisplayName("Should handle null query gracefully and return 200 OK")
    void shouldHandleNullQuery() {
        ReaderWikiLookupResult emptyResult = new ReaderWikiLookupResult("", false, List.of());
        when(lookupContextualWikiUseCase.execute(null)).thenReturn(emptyResult);

        ResponseEntity<ReaderChapterWikiResolutionResult> response = controller.lookup(null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query()).isEqualTo("");
        assertThat(response.getBody().source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
        assertThat(response.getBody().hasExactMatch()).isFalse();
        assertThat(response.getBody().items()).isEmpty();
    }
}