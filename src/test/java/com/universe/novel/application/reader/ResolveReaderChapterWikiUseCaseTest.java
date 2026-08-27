package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.application.ports.PublishedWikiArticlePort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.novel.domain.reference.ChapterWikiReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveReaderChapterWikiUseCase Unit Tests")
class ResolveReaderChapterWikiUseCaseTest {

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private VolumeRepositoryPort volumeRepositoryPort;

    @Mock
    private ChapterWikiReferenceRepositoryPort referenceRepositoryPort;

    @Mock
    private PublishedWikiArticlePort publishedWikiArticlePort;

    @Mock
    private LookupContextualWikiUseCase lookupContextualWikiUseCase;

    @InjectMocks
    private ResolveReaderChapterWikiUseCase resolveUseCase;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private Chapter publishedChapter;
    private Volume publishedVolume;
    private PublishedWikiArticleSummary articleSummary1;
    private PublishedWikiArticleSummary articleSummary2;

    @BeforeEach
    void setUp() {
        publishedChapter = Chapter.rehydrate(
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

        publishedVolume = Volume.rehydrate(
                VOLUME_ID,
                "Quyển 1: Lung Trung Điểu",
                new Slug("quyen-1-lung-trung-dieu"),
                "Tóm tắt quyển 1",
                1,
                VolumeStatus.PUBLISHED,
                ACTOR_ID,
                ACTOR_ID,
                ACTOR_ID,
                null,
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"),
                null,
                1L
        );

        articleSummary1 = new PublishedWikiArticleSummary(
                ARTICLE_1_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Nhân vật chính");
        articleSummary2 = new PublishedWikiArticleSummary(
                ARTICLE_2_ID, "Kiếm Khí Trường Thành", "kiem-khi-truong-thanh", "LOCATION", "Địa danh Kiếm Khí");
    }

    @Nested
    @DisplayName("Priority 1: Occurrence-Specific Resolution")
    class Priority1OccurrenceTests {

        @Test
        @DisplayName("Should resolve active OCCURRENCE_BINDING with matchedAlias=null when boundContentVersion matches current chapter contentVersion")
        void shouldResolveActiveOccurrenceBinding() {
            Instant now = Instant.now();
            UUID refId = UUID.randomUUID();
            ChapterWikiReference occRef = ChapterWikiReference.createOccurrenceSpecific(
                    refId, CHAPTER_ID, "Trần Bình An", 1, "ngữ cảnh", 3L, ARTICLE_1_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 1)).thenReturn(Optional.of(occRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));

            ResolveReaderChapterWikiQuery query = new ResolveReaderChapterWikiQuery(
                    CHAPTER_ID, "  Trần   Bình An  ", 1);

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(query);

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.OCCURRENCE_BINDING);
            assertThat(result.hasExactMatch()).isTrue();
            assertThat(result.occurrenceIndex()).isEqualTo(1);
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).id()).isEqualTo(ARTICLE_1_ID);
            assertThat(result.items().get(0).title()).isEqualTo("Trần Bình An");
            assertThat(result.items().get(0).matchedAlias()).isNull(); // bound items do NOT overload matchedAlias
            verify(lookupContextualWikiUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Should resolve different Wiki articles for multiple identical terms based on occurrenceIndex")
        void shouldResolveMultipleIdenticalTermsByOccurrenceIndex() {
            Instant now = Instant.now();
            UUID ref1Id = UUID.randomUUID();
            UUID ref2Id = UUID.randomUUID();
            ChapterWikiReference occRef1 = ChapterWikiReference.createOccurrenceSpecific(
                    ref1Id, CHAPTER_ID, "Đạo Đầu", 1, "ngữ cảnh 1", 3L, ARTICLE_1_ID, ACTOR_ID, now);
            ChapterWikiReference occRef2 = ChapterWikiReference.createOccurrenceSpecific(
                    ref2Id, CHAPTER_ID, "Đạo Đầu", 2, "ngữ cảnh 2", 3L, ARTICLE_2_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "đạo đầu", 1)).thenReturn(Optional.of(occRef1));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "đạo đầu", 2)).thenReturn(Optional.of(occRef2));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2));

            ReaderChapterWikiResolutionResult res1 = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Đạo Đầu", 1));
            ReaderChapterWikiResolutionResult res2 = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Đạo Đầu", 2));

            assertThat(res1.items().get(0).id()).isEqualTo(ARTICLE_1_ID);
            assertThat(res1.items().get(0).matchedAlias()).isNull();
            assertThat(res2.items().get(0).id()).isEqualTo(ARTICLE_2_ID);
            assertThat(res2.items().get(0).matchedAlias()).isNull();
        }
    }

    @Nested
    @DisplayName("Priority 2: Fallback to Chapter-Wide Resolution")
    class Priority2ChapterWideTests {

        @Test
        @DisplayName("Should fall back to CHAPTER_WIDE_BINDING when occurrence binding is STALE")
        void shouldFallBackToChapterWideWhenOccurrenceIsStale() {
            Instant now = Instant.now();
            UUID wideRefId = UUID.randomUUID();
            ChapterWikiReference staleOccRef = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", 1, "ngữ cảnh", 1L, ARTICLE_1_ID, ACTOR_ID, now); // contentVersion 1 != 3
            ChapterWikiReference wideRef = ChapterWikiReference.createChapterWide(
                    wideRefId, CHAPTER_ID, "Trần Bình An", ARTICLE_2_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 1)).thenReturn(Optional.of(staleOccRef));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 0)).thenReturn(Optional.of(wideRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Trần Bình An", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING);
            assertThat(result.items().get(0).id()).isEqualTo(ARTICLE_2_ID);
            assertThat(result.items().get(0).matchedAlias()).isNull();
            verify(lookupContextualWikiUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Should fall back to CHAPTER_WIDE_BINDING when active occurrence target article is unpublished/missing")
        void shouldFallBackToChapterWideWhenActiveOccurrenceTargetIsUnpublished() {
            Instant now = Instant.now();
            UUID wideRefId = UUID.randomUUID();
            // Active occurrence binding (contentVersion = 3L matches chapter)
            ChapterWikiReference activeOccRef = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Thuật Ngữ", 1, "ngữ cảnh", 3L, ARTICLE_1_ID, ACTOR_ID, now);
            // Chapter-wide binding
            ChapterWikiReference wideRef = ChapterWikiReference.createChapterWide(
                    wideRefId, CHAPTER_ID, "Thuật Ngữ", ARTICLE_2_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 1)).thenReturn(Optional.of(activeOccRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.empty()); // Occurrence target unpublished!

            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 0)).thenReturn(Optional.of(wideRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_2_ID)).thenReturn(Optional.of(articleSummary2)); // Chapter-wide target published

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Thuật Ngữ", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING);
            assertThat(result.items().get(0).id()).isEqualTo(ARTICLE_2_ID);
            assertThat(result.items().get(0).matchedAlias()).isNull();
            verify(lookupContextualWikiUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Should resolve CHAPTER_WIDE_BINDING directly when occurrenceIndex is null")
        void shouldResolveChapterWideWhenOccurrenceIndexIsNull() {
            Instant now = Instant.now();
            UUID wideRefId = UUID.randomUUID();
            ChapterWikiReference wideRef = ChapterWikiReference.createChapterWide(
                    wideRefId, CHAPTER_ID, "Trần Bình An", ARTICLE_1_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "trần bình an", 0)).thenReturn(Optional.of(wideRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.of(articleSummary1));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Trần Bình An", null));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING);
            assertThat(result.items().get(0).id()).isEqualTo(ARTICLE_1_ID);
            assertThat(result.items().get(0).matchedAlias()).isNull();
        }
    }

    @Nested
    @DisplayName("Priority 3: Fallback to Global Lookup")
    class Priority3GlobalLookupTests {

        @Test
        @DisplayName("Should fall back to GLOBAL_LOOKUP when stale occurrence and no chapter-wide binding exists")
        void shouldFallBackToGlobalLookupWhenStaleOccurrenceAndNoChapterWide() {
            Instant now = Instant.now();
            ChapterWikiReference staleOccRef = ChapterWikiReference.createOccurrenceSpecific(
                    UUID.randomUUID(), CHAPTER_ID, "Thuật Ngữ", 1, "ngữ cảnh", 1L, ARTICLE_1_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 1)).thenReturn(Optional.of(staleOccRef));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 0)).thenReturn(Optional.empty());

            ReaderWikiLookupItem globalItem = new ReaderWikiLookupItem(
                    ARTICLE_2_ID, "Thuật Ngữ Toàn Cục", "CONCEPT", "thuat-ngu", "Tóm tắt", "Alias Khớp");
            when(lookupContextualWikiUseCase.execute("Thuật Ngữ"))
                    .thenReturn(new ReaderWikiLookupResult("Thuật Ngữ", true, List.of(globalItem)));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Thuật Ngữ", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).title()).isEqualTo("Thuật Ngữ Toàn Cục");
            assertThat(result.items().get(0).matchedAlias()).isEqualTo("Alias Khớp"); // Global alias preserved
        }

        @Test
        @DisplayName("Should fall back to GLOBAL_LOOKUP when bound target article is now unpublished or missing")
        void shouldFallBackToGlobalLookupWhenBoundTargetIsUnpublished() {
            Instant now = Instant.now();
            ChapterWikiReference wideRef = ChapterWikiReference.createChapterWide(
                    UUID.randomUUID(), CHAPTER_ID, "Thuật Ngữ", ARTICLE_1_ID, ACTOR_ID, now);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(publishedVolume));
            when(referenceRepositoryPort.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                    CHAPTER_ID, "thuật ngữ", 0)).thenReturn(Optional.of(wideRef));
            when(publishedWikiArticlePort.findPublishedById(ARTICLE_1_ID)).thenReturn(Optional.empty()); // Unpublished

            ReaderWikiLookupItem globalItem = new ReaderWikiLookupItem(
                    ARTICLE_2_ID, "Thuật Ngữ Thay Thế", "CONCEPT", "thuat-ngu", "Tóm tắt");
            when(lookupContextualWikiUseCase.execute("Thuật Ngữ"))
                    .thenReturn(new ReaderWikiLookupResult("Thuật Ngữ", false, List.of(globalItem)));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Thuật Ngữ", null));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).title()).isEqualTo("Thuật Ngữ Thay Thế");
        }

        @Test
        @DisplayName("Should fall back directly to GLOBAL_LOOKUP when chapter is not found or null")
        void shouldFallBackToGlobalLookupWhenChapterIdIsNull() {
            when(lookupContextualWikiUseCase.execute("Trần Bình An"))
                    .thenReturn(new ReaderWikiLookupResult("Trần Bình An", true, List.of()));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(null, "Trần Bình An", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
            verify(referenceRepositoryPort, never()).findByChapterIdAndNormalizedTermAndOccurrenceIndex(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("Public Readability Protection")
    class PublicReadabilityTests {

        @Test
        @DisplayName("Should NOT expose chapter bindings when Chapter is DRAFT, falling through to global lookup")
        void shouldNotExposeBindingsWhenChapterIsDraft() {
            Chapter draftChapter = Chapter.rehydrate(
                    CHAPTER_ID, VOLUME_ID, 1, "Chương Draft", new Slug("chuong-draft"),
                    "Tóm tắt", "Nội dung", ChapterStatus.DRAFT, ACTOR_ID, ACTOR_ID,
                    null, null, Instant.now(), Instant.now(), null, null, 1L, 1L);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(draftChapter));
            when(lookupContextualWikiUseCase.execute("Trần Bình An"))
                    .thenReturn(new ReaderWikiLookupResult("Trần Bình An", false, List.of()));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Trần Bình An", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
            verify(referenceRepositoryPort, never()).findByChapterIdAndNormalizedTermAndOccurrenceIndex(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should NOT expose chapter bindings when parent Volume is DRAFT, falling through to global lookup")
        void shouldNotExposeBindingsWhenVolumeIsDraft() {
            Volume draftVolume = Volume.rehydrate(
                    VOLUME_ID, "Quyển Draft", new Slug("quyen-draft"),
                    "Tóm tắt", 1, VolumeStatus.DRAFT, ACTOR_ID, ACTOR_ID,
                    null, null, Instant.now(), Instant.now(), null, null, 1L);

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(publishedChapter));
            when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(draftVolume));
            when(lookupContextualWikiUseCase.execute("Trần Bình An"))
                    .thenReturn(new ReaderWikiLookupResult("Trần Bình An", false, List.of()));

            ReaderChapterWikiResolutionResult result = resolveUseCase.execute(
                    new ResolveReaderChapterWikiQuery(CHAPTER_ID, "Trần Bình An", 1));

            assertThat(result.source()).isEqualTo(ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP);
            verify(referenceRepositoryPort, never()).findByChapterIdAndNormalizedTermAndOccurrenceIndex(any(), any(), anyInt());
        }
    }
}
