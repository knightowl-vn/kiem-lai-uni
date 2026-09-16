package com.universe.interaction.infrastructure.eligibility;

import com.universe.interaction.domain.CommentTarget;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentTargetEligibilityAdapter Unit Tests")
class CommentTargetEligibilityAdapterTest {

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    private CommentTargetEligibilityAdapter adapter;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        adapter = new CommentTargetEligibilityAdapter(readerChapterAccessQueryPort);
    }

    @Test
    @DisplayName("Should reject null dependency in constructor")
    void shouldRejectNullDependency() {
        assertThatThrownBy(() -> new CommentTargetEligibilityAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReaderChapterAccessQueryPort cannot be null.");
    }

    @Test
    @DisplayName("Should return true when NOVEL_CHAPTER target is published and readable")
    void shouldReturnTrueWhenNovelChapterIsPublished() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));

        boolean result = adapter.isEligible(target);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false when NOVEL_CHAPTER target is not found or unpublished")
    void shouldReturnFalseWhenNovelChapterIsUnpublished() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        boolean result = adapter.isEligible(target);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when target is null")
    void shouldReturnFalseWhenTargetIsNull() {
        boolean result = adapter.isEligible(null);

        assertThat(result).isFalse();
        verifyNoInteractions(readerChapterAccessQueryPort);
    }

    @Test
    @DisplayName("Should return false without querying Novel when target is WIKI_ARTICLE (deferred to MS-05E6)")
    void shouldReturnFalseWhenTargetIsWikiArticle() {
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        boolean result = adapter.isEligible(target);

        assertThat(result).isFalse();
        verifyNoInteractions(readerChapterAccessQueryPort);
    }
}
