package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.TargetWikiArticleNotPublishedException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.application.ports.PublishedWikiArticlePort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
import com.universe.shared.id.IdGeneratorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterWikiReference Application Use Cases Tests")
class ChapterWikiReferenceApplicationUseCasesTest {

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ChapterWikiReferenceRepositoryPort referenceRepositoryPort;

    @Mock
    private PublishedWikiArticlePort publishedWikiArticlePort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    private BindChapterWideWikiReferenceUseCase bindChapterWideUseCase;
    private BindOccurrenceSpecificWikiReferenceUseCase bindOccurrenceSpecificUseCase;
    private RemoveChapterWikiReferenceUseCase removeUseCase;
    private ListChapterWikiReferencesUseCase listUseCase;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID GENERATED_REF_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private Chapter dummyChapter;
    private PublishedWikiArticleSummary articleSummary1;
    private PublishedWikiArticleSummary articleSummary2;

    @BeforeEach
    void setUp() {
        bindChapterWideUseCase = new BindChapterWideWikiReferenceUseCase(
                chapterRepositoryPort, referenceRepositoryPort, publishedWikiArticlePort, idGeneratorPort);
        bindOccurrenceSpecificUseCase = new BindOccurrenceSpecificWikiReferenceUseCase(
                chapterRepositoryPort, referenceRepositoryPort, publishedWikiArticlePort, idGeneratorPort);
        removeUseCase = new RemoveChapterWikiReferenceUseCase(referenceRepositoryPort);
        listUseCase = new ListChapterWikiReferencesUseCase(chapterRepositoryPort, referenceRepositoryPort, publishedWikiArticlePort);

        dummyChapter = Chapter.rehydrate(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương 1: Khởi Đầu",
                new Slug("chuong-1-khoi-dau"),
                "Tóm tắt",
                "Nội dung chương có chứa Trần Bình An...",
                ChapterStatus.PUBLISHED,
                ACTOR_ID,
                ACTOR_ID,
                ACTOR_ID,
                null,
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"),
                null,
                1L,
                3L // contentVersion = 3
        );

        articleSummary1 = new PublishedWikiArticleSummary(
                ARTICLE_1_ID, "Trần Bình An", "tran-binh-an", "CHARACTER");
        articleSummary2 = new PublishedWikiArticleSummary(
                ARTICLE_2_ID, "Nhất Khí Hóa Tam Thanh", "nhat-khi-hoa-tam-thanh", "TECHNIQUE");
    }

    @Nested
    @DisplayName("Bind CHAPTER_WIDE Reference")
    class BindChapterWideTests {

        @Test
        @DisplayName("Should create new CHAPTER_WIDE reference when target article is PUBLISHED and no binding exists")
        void shouldCreateNewChapterWideReference() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));
            when(idGeneratorPort.generate()).thenReturn(GENERATED_REF_ID);
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 0)).thenReturn(Optional.empty());
            when(referenceRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            BindChapterWideWikiReferenceCommand command = new BindChapterWideWikiReferenceCommand(
                    CHAPTER_ID,
                    "  Trần   Bình An  ",
                    ARTICLE_1_ID,
                    ACTOR_ID
            );

            ChapterWikiReferenceItemDTO result = bindChapterWideUseCase.execute(command);

            assertThat(result.id()).isEqualTo(GENERATED_REF_ID);
            assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
            assertThat(result.term()).isEqualTo("Trần Bình An");
            assertThat(result.normalizedTerm()).isEqualTo("trần bình an");
            assertThat(result.referenceScope()).isEqualTo(ChapterWikiReferenceScope.CHAPTER_WIDE);
            assertThat(result.occurrenceIndex()).isEqualTo(0);
            assertThat(result.boundContentVersion()).isNull();
            assertThat(result.currentChapterContentVersion()).isEqualTo(3L);
            assertThat(result.wikiArticleId()).isEqualTo(ARTICLE_1_ID);
            assertThat(result.status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);

            ArgumentCaptor<ChapterWikiReference> captor = ArgumentCaptor.forClass(ChapterWikiReference.class);
            verify(referenceRepositoryPort).save(captor.capture());
            ChapterWikiReference saved = captor.getValue();
            assertThat(saved.getOccurrenceIndex()).isEqualTo(0);
            assertThat(saved.getBoundContentVersion()).isNull();
        }

        @Test
        @DisplayName("Should reject CHAPTER_WIDE binding when target Wiki article is not published or nonexistent")
        void shouldRejectChapterWideWhenTargetWikiArticleNotPublished() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.empty());

            BindChapterWideWikiReferenceCommand command = new BindChapterWideWikiReferenceCommand(
                    CHAPTER_ID,
                    "Trần Bình An",
                    ARTICLE_1_ID,
                    ACTOR_ID
            );

            assertThatThrownBy(() -> bindChapterWideUseCase.execute(command))
                    .isInstanceOf(TargetWikiArticleNotPublishedException.class);

            verify(referenceRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Should update existing CHAPTER_WIDE reference target and preserve original id/creation metadata")
        void shouldUpdateExistingChapterWideReference() {
            Instant createdAt = Instant.parse("2026-08-27T01:00:00Z");
            UUID originalRefId = UUID.randomUUID();
            UUID originalCreator = UUID.randomUUID();
            ChapterWikiReference existing = ChapterWikiReference.createChapterWide(
                    originalRefId,
                    CHAPTER_ID,
                    "Trần Bình An",
                    ARTICLE_1_ID,
                    originalCreator,
                    createdAt
            );

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 0)).thenReturn(Optional.of(existing));
            when(referenceRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            BindChapterWideWikiReferenceCommand command = new BindChapterWideWikiReferenceCommand(
                    CHAPTER_ID,
                    "trần bình an",
                    ARTICLE_2_ID,
                    ACTOR_ID
            );

            ChapterWikiReferenceItemDTO result = bindChapterWideUseCase.execute(command);

            assertThat(result.id()).isEqualTo(originalRefId);
            assertThat(result.wikiArticleId()).isEqualTo(ARTICLE_2_ID);
            assertThat(result.createdBy()).isEqualTo(originalCreator);
            assertThat(result.createdAt()).isEqualTo(createdAt);
            assertThat(result.updatedBy()).isEqualTo(ACTOR_ID);
            verify(idGeneratorPort, never()).generate();
        }

        @Test
        @DisplayName("Should reject rebinding existing CHAPTER_WIDE reference when new target is unpublished and leave existing unchanged")
        void shouldRejectRebindingExistingChapterWideWhenNewTargetWikiArticleNotPublished() {
            Instant createdAt = Instant.parse("2026-08-27T01:00:00Z");
            UUID originalRefId = UUID.randomUUID();
            UUID originalCreator = UUID.randomUUID();
            ChapterWikiReference existing = ChapterWikiReference.createChapterWide(
                    originalRefId,
                    CHAPTER_ID,
                    "Trần Bình An",
                    ARTICLE_1_ID,
                    originalCreator,
                    createdAt
            );

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.empty());

            BindChapterWideWikiReferenceCommand command = new BindChapterWideWikiReferenceCommand(
                    CHAPTER_ID,
                    "Trần Bình An",
                    ARTICLE_2_ID,
                    ACTOR_ID
            );

            assertThatThrownBy(() -> bindChapterWideUseCase.execute(command))
                    .isInstanceOf(TargetWikiArticleNotPublishedException.class);

            assertThat(existing.getWikiArticleId()).isEqualTo(ARTICLE_1_ID);
            verify(referenceRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Should throw ChapterNotFoundException when chapter does not exist")
        void shouldThrowChapterNotFound() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

            BindChapterWideWikiReferenceCommand command = new BindChapterWideWikiReferenceCommand(
                    CHAPTER_ID,
                    "Trần Bình An",
                    ARTICLE_1_ID,
                    ACTOR_ID
            );

            assertThatThrownBy(() -> bindChapterWideUseCase.execute(command))
                    .isInstanceOf(ChapterNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Bind OCCURRENCE_SPECIFIC Reference")
    class BindOccurrenceSpecificTests {

        @Test
        @DisplayName("Should create new OCCURRENCE_SPECIFIC reference deriving boundContentVersion from current chapter")
        void shouldCreateOccurrenceSpecificDerivingChapterContentVersion() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));
            when(idGeneratorPort.generate()).thenReturn(GENERATED_REF_ID);
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "nhất khí hóa tam thanh", 2)).thenReturn(Optional.empty());
            when(referenceRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            BindOccurrenceSpecificWikiReferenceCommand command = new BindOccurrenceSpecificWikiReferenceCommand(
                    CHAPTER_ID,
                    "Nhất Khí Hóa Tam Thanh",
                    2,
                    "ngữ cảnh xung quanh",
                    ARTICLE_1_ID,
                    ACTOR_ID
            );

            ChapterWikiReferenceItemDTO result = bindOccurrenceSpecificUseCase.execute(command);

            assertThat(result.id()).isEqualTo(GENERATED_REF_ID);
            assertThat(result.referenceScope()).isEqualTo(ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC);
            assertThat(result.occurrenceIndex()).isEqualTo(2);
            assertThat(result.boundContentVersion()).isEqualTo(3L); // derived from dummyChapter (contentVersion=3)
            assertThat(result.contextSnippet()).isEqualTo("ngữ cảnh xung quanh");
            assertThat(result.status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should reject OCCURRENCE_SPECIFIC binding when target Wiki article is not published")
        void shouldRejectOccurrenceSpecificWhenTargetWikiArticleNotPublished() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.empty());

            BindOccurrenceSpecificWikiReferenceCommand command = new BindOccurrenceSpecificWikiReferenceCommand(
                    CHAPTER_ID,
                    "Nhất Khí Hóa Tam Thanh",
                    1,
                    "ngữ cảnh",
                    ARTICLE_1_ID,
                    ACTOR_ID
            );

            assertThatThrownBy(() -> bindOccurrenceSpecificUseCase.execute(command))
                    .isInstanceOf(TargetWikiArticleNotPublishedException.class);

            verify(referenceRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Should update existing OCCURRENCE_SPECIFIC reference target and context and rebind contentVersion")
        void shouldUpdateExistingOccurrenceSpecificReference() {
            Instant createdAt = Instant.parse("2026-08-27T01:00:00Z");
            UUID originalRefId = UUID.randomUUID();
            ChapterWikiReference existing = ChapterWikiReference.createOccurrenceSpecific(
                    originalRefId,
                    CHAPTER_ID,
                    "Thuật ngữ",
                    1,
                    "ngữ cảnh cũ",
                    2L, // old contentVersion
                    ARTICLE_1_ID,
                    ACTOR_ID,
                    createdAt
            );

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 1)).thenReturn(Optional.of(existing));
            when(referenceRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            BindOccurrenceSpecificWikiReferenceCommand command = new BindOccurrenceSpecificWikiReferenceCommand(
                    CHAPTER_ID,
                    "thuật ngữ",
                    1,
                    "ngữ cảnh mới",
                    ARTICLE_2_ID,
                    ACTOR_ID
            );

            ChapterWikiReferenceItemDTO result = bindOccurrenceSpecificUseCase.execute(command);

            assertThat(result.id()).isEqualTo(originalRefId);
            assertThat(result.wikiArticleId()).isEqualTo(ARTICLE_2_ID);
            assertThat(result.contextSnippet()).isEqualTo("ngữ cảnh mới");
            assertThat(result.boundContentVersion()).isEqualTo(3L); // re-anchored to current chapter contentVersion (3)
            assertThat(result.status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should reject rebinding existing OCCURRENCE_SPECIFIC reference when new target is unpublished and leave existing unchanged")
        void shouldRejectRebindingExistingOccurrenceSpecificWhenNewTargetWikiArticleNotPublished() {
            Instant createdAt = Instant.parse("2026-08-27T01:00:00Z");
            UUID originalRefId = UUID.randomUUID();
            UUID originalCreator = UUID.randomUUID();
            ChapterWikiReference existing = ChapterWikiReference.createOccurrenceSpecific(
                    originalRefId,
                    CHAPTER_ID,
                    "Thuật ngữ",
                    1,
                    "ngữ cảnh cũ",
                    2L,
                    ARTICLE_1_ID,
                    originalCreator,
                    createdAt
            );

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.empty());

            BindOccurrenceSpecificWikiReferenceCommand command = new BindOccurrenceSpecificWikiReferenceCommand(
                    CHAPTER_ID,
                    "thuật ngữ",
                    1,
                    "ngữ cảnh mới",
                    ARTICLE_2_ID,
                    ACTOR_ID
            );

            assertThatThrownBy(() -> bindOccurrenceSpecificUseCase.execute(command))
                    .isInstanceOf(TargetWikiArticleNotPublishedException.class);

            assertThat(existing.getWikiArticleId()).isEqualTo(ARTICLE_1_ID);
            assertThat(existing.getContextSnippet()).isEqualTo("ngữ cảnh cũ");
            assertThat(existing.getBoundContentVersion()).isEqualTo(2L);
            verify(referenceRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Should reject invalid occurrence index < 1")
        void shouldRejectInvalidOccurrenceIndex() {
            assertThatThrownBy(() -> new BindOccurrenceSpecificWikiReferenceCommand(
                    CHAPTER_ID,
                    "Thuật ngữ",
                    0,
                    "ngữ cảnh",
                    ARTICLE_1_ID,
                    ACTOR_ID
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Chỉ số xuất hiện");
        }
    }

    @Nested
    @DisplayName("List References and Stale Detection")
    class ListAndStaleDetectionTests {

        @Test
        @DisplayName("Should correctly classify ACTIVE vs STALE references based on current Chapter.contentVersion")
        void shouldClassifyActiveVsStaleReferences() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter)); // contentVersion = 3
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2));

            Instant now = Instant.now();

            // 1. Chapter-wide reference (ALWAYS ACTIVE)
            ChapterWikiReference chapterWide = ChapterWikiReference.createChapterWide(
                    UUID.randomUUID(), CHAPTER_ID, "Bảo Bình Châu", ARTICLE_1_ID, ACTOR_ID, now);

            // 2. Occurrence-specific matching current contentVersion (3 == 3 -> ACTIVE)
            ChapterWikiReference occActive = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", 1, "snip 1", 3L, ARTICLE_1_ID, ACTOR_ID, now);

            // 3. Occurrence-specific bound to older contentVersion (1 != 3 -> STALE)
            ChapterWikiReference occStale = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", 2, "snip 2", 1L, ARTICLE_2_ID, ACTOR_ID, now);

            when(referenceRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(List.of(chapterWide, occActive, occStale));

            ChapterWikiReferenceListPageDTO page = listUseCase.execute(CHAPTER_ID);

            assertThat(page.chapterId()).isEqualTo(CHAPTER_ID);
            assertThat(page.currentContentVersion()).isEqualTo(3L);
            assertThat(page.totalCount()).isEqualTo(3);
            assertThat(page.activeCount()).isEqualTo(2);
            assertThat(page.staleCount()).isEqualTo(1);

            List<ChapterWikiReferenceItemDTO> items = page.references();
            assertThat(items.get(0).status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
            assertThat(items.get(1).status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
            assertThat(items.get(2).status()).isEqualTo(ChapterWikiReferenceStatus.STALE);
        }

        @Test
        @DisplayName("Should resolve published Wiki target and deduplicate lookups when multiple references share the same articleId")
        void shouldResolvePublishedWikiTargetAndDeduplicateLookups() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));

            Instant now = Instant.now();

            // Two references sharing ARTICLE_1_ID
            ChapterWikiReference ref1 = ChapterWikiReference.createChapterWide(
                    UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", ARTICLE_1_ID, ACTOR_ID, now);
            ChapterWikiReference ref2 = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", 1, "snip", 3L, ARTICLE_1_ID, ACTOR_ID, now);

            when(referenceRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(ref1, ref2));

            ChapterWikiReferenceListPageDTO page = listUseCase.execute(CHAPTER_ID);

            assertThat(page.references()).hasSize(2);

            ChapterWikiReferenceItemDTO item1 = page.references().get(0);
            assertThat(item1.wikiArticle()).isNotNull();
            assertThat(item1.wikiArticle().title()).isEqualTo(articleSummary1.title());
            assertThat(item1.wikiArticle().articleType()).isEqualTo(articleSummary1.articleType());
            assertThat(item1.wikiArticle().slug()).isEqualTo(articleSummary1.slug());

            ChapterWikiReferenceItemDTO item2 = page.references().get(1);
            assertThat(item2.wikiArticle()).isNotNull();
            assertThat(item2.wikiArticle().title()).isEqualTo(articleSummary1.title());

            // Verified deduplicated lookup: exactly 1 call for ARTICLE_1_ID across multiple references
            org.mockito.Mockito.verify(publishedWikiArticlePort, org.mockito.Mockito.times(1))
                    .findPublishedById(ARTICLE_1_ID);
        }

        @Test
        @DisplayName("Should handle missing or unpublished target Wiki article gracefully without throwing exception")
        void shouldHandleMissingOrUnpublishedTargetGracefully() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(dummyChapter));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.empty());

            Instant now = Instant.now();
            ChapterWikiReference ref = ChapterWikiReference.createChapterWide(
                    UUID.randomUUID(), CHAPTER_ID, "Bí cảnh", ARTICLE_1_ID, ACTOR_ID, now);

            when(referenceRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(List.of(ref));

            ChapterWikiReferenceListPageDTO page = listUseCase.execute(CHAPTER_ID);

            assertThat(page.references()).hasSize(1);
            ChapterWikiReferenceItemDTO item = page.references().get(0);
            assertThat(item.wikiArticle()).isNull();
        }
    }

    @Nested
    @DisplayName("Remove Reference")
    class RemoveReferenceTests {

        @Test
        @DisplayName("Should remove reference and return true when reference exists")
        void shouldRemoveReferenceWhenExists() {
            UUID refId = UUID.randomUUID();
            ChapterWikiReference ref = ChapterWikiReference.createChapterWide(
                    refId, CHAPTER_ID, "Thuật ngữ", ARTICLE_1_ID, ACTOR_ID, Instant.now());

            when(referenceRepositoryPort.findById(refId)).thenReturn(Optional.of(ref));

            boolean result = removeUseCase.execute(new RemoveChapterWikiReferenceCommand(refId, CHAPTER_ID, ACTOR_ID));

            assertThat(result).isTrue();
            verify(referenceRepositoryPort).delete(ref);
        }

        @Test
        @DisplayName("Should return false idempotently when reference does not exist")
        void shouldReturnFalseWhenReferenceNotFound() {
            UUID refId = UUID.randomUUID();
            when(referenceRepositoryPort.findById(refId)).thenReturn(Optional.empty());

            boolean result = removeUseCase.execute(new RemoveChapterWikiReferenceCommand(refId, CHAPTER_ID, ACTOR_ID));

            assertThat(result).isFalse();
            verify(referenceRepositoryPort, never()).delete(any());
        }

        @Test
        @DisplayName("Should return false and NOT delete when existing reference belongs to a different chapterId")
        void shouldNotDeleteWhenChapterIdDoesNotMatch() {
            UUID refId = UUID.randomUUID();
            UUID otherChapterId = UUID.randomUUID();
            ChapterWikiReference ref = ChapterWikiReference.createChapterWide(
                    refId, otherChapterId, "Thuật ngữ", ARTICLE_1_ID, ACTOR_ID, Instant.now());

            when(referenceRepositoryPort.findById(refId)).thenReturn(Optional.of(ref));

            // Attempting to remove otherChapterId's reference via CHAPTER_ID URL
            boolean result = removeUseCase.execute(new RemoveChapterWikiReferenceCommand(refId, CHAPTER_ID, ACTOR_ID));

            assertThat(result).isFalse();
            verify(referenceRepositoryPort, never()).delete(any());
        }
    }
}
