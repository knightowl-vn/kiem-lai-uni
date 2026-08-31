package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceItemDTO;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceListPageDTO;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceStatus;
import com.universe.novel.application.chapter.reference.ListChapterWikiReferencesUseCase;
import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminNovelChapterWikiReferencePageController Unit Tests")
class AdminNovelChapterWikiReferencePageControllerTest {

    private static final UUID VOLUME_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_1_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ARTICLE_3_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

    @Mock
    private GetChapterDetailUseCase getChapterDetailUseCase;

    @Mock
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    @Mock
    private ListChapterWikiReferencesUseCase listChapterWikiReferencesUseCase;

    @Mock
    private NovelMarkdownRenderer novelMarkdownRenderer;

    private AdminNovelChapterWikiReferencePageController controller;

    private ChapterDTO chapterDTO;
    private VolumeDTO volumeDTO;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterWikiReferencePageController(
                getChapterDetailUseCase,
                getVolumeDetailUseCase,
                listChapterWikiReferencesUseCase,
                novelMarkdownRenderer
        );

        chapterDTO = new ChapterDTO(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Khởi Đầu",
                "chuong-1-khoi-dau",
                "Tóm tắt chương 1",
                "# Chương 1\nNội dung chương 1",
                "PUBLISHED",
                ACTOR_ID,
                ACTOR_ID,
                ACTOR_ID,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L,
                3L
        );

        volumeDTO = new VolumeDTO(
                VOLUME_ID,
                "Quyển 1: Lung Trung Điểu",
                "quyen-1-lung-trung-dieu",
                "Tóm tắt quyển 1",
                1,
                "PUBLISHED",
                ACTOR_ID,
                ACTOR_ID,
                ACTOR_ID,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L
        );
    }

    @Test
    @DisplayName("Renders wiki references page with application-enriched pageResult without controller port dependency")
    void shouldRenderWikiReferencesPageWithEnrichedPageResult() {
        PublishedWikiArticleSummary article1 = new PublishedWikiArticleSummary(
                ARTICLE_1_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Nhân vật chính"
        );
        PublishedWikiArticleSummary article2 = new PublishedWikiArticleSummary(
                ARTICLE_2_ID, "Đạo Đầu", "dao-dau", "CONCEPT", "Khái niệm Đạo Đầu"
        );

        ChapterWikiReferenceItemDTO item1 = new ChapterWikiReferenceItemDTO(
                UUID.randomUUID(), CHAPTER_ID, "Trần Bình An", "trần bình an",
                ChapterWikiReferenceScope.CHAPTER_WIDE, 0, null, null, 3L,
                ARTICLE_1_ID, ChapterWikiReferenceStatus.ACTIVE, article1, ACTOR_ID, ACTOR_ID, NOW, NOW
        );

        ChapterWikiReferenceItemDTO item2 = new ChapterWikiReferenceItemDTO(
                UUID.randomUUID(), CHAPTER_ID, "Đạo Đầu", "đạo đầu",
                ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC, 1, "ngữ cảnh 1", 3L, 3L,
                ARTICLE_2_ID, ChapterWikiReferenceStatus.ACTIVE, article2, ACTOR_ID, ACTOR_ID, NOW, NOW
        );

        ChapterWikiReferenceItemDTO item3 = new ChapterWikiReferenceItemDTO(
                UUID.randomUUID(), CHAPTER_ID, "Kiếm Khí", "kiếm khí",
                ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC, 2, "ngữ cảnh 2", 1L, 3L,
                ARTICLE_3_ID, ChapterWikiReferenceStatus.STALE, null, ACTOR_ID, ACTOR_ID, NOW, NOW
        );

        ChapterWikiReferenceListPageDTO pageResult = new ChapterWikiReferenceListPageDTO(
                CHAPTER_ID, "Khởi Đầu", 1, 3L,
                List.of(item1, item2, item3), 3, 2, 1
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDTO);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volumeDTO);
        when(listChapterWikiReferencesUseCase.execute(CHAPTER_ID)).thenReturn(pageResult);
        when(novelMarkdownRenderer.renderToHtml(chapterDTO.content())).thenReturn("<h1>Chương 1</h1><p>Nội dung chương 1</p>");

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listWikiReferencesPage(CHAPTER_ID, model, response);

        assertThat(view).isEqualTo("admin/novel/chapter-wiki-references");
        assertThat(model.get("chapter")).isEqualTo(chapterDTO);
        assertThat(model.get("volume")).isEqualTo(volumeDTO);
        assertThat(model.get("pageResult")).isEqualTo(pageResult);
        assertThat(model.get("contentHtml")).isEqualTo("<h1>Chương 1</h1><p>Nội dung chương 1</p>");
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý liên kết Wiki");
        assertThat(model.get("activeMenu")).isEqualTo("novel");

        @SuppressWarnings("unchecked")
        List<AdminChapterWikiReferenceViewItem> actualViewItems =
                (List<AdminChapterWikiReferenceViewItem>) model.get("referenceItems");
        assertThat(actualViewItems).hasSize(3);

        // Item 1: Chapter wide, active, available article
        AdminChapterWikiReferenceViewItem actualItem1 = actualViewItems.get(0);
        assertThat(actualItem1.term()).isEqualTo("Trần Bình An");
        assertThat(actualItem1.referenceScope()).isEqualTo(ChapterWikiReferenceScope.CHAPTER_WIDE);
        assertThat(actualItem1.status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
        assertThat(actualItem1.isWikiArticleAvailable()).isTrue();
        assertThat(actualItem1.targetTitle()).isEqualTo("Trần Bình An");
        assertThat(actualItem1.targetArticleType()).isEqualTo("CHARACTER");

        // Item 2: Occurrence specific, active, available article
        AdminChapterWikiReferenceViewItem actualItem2 = actualViewItems.get(1);
        assertThat(actualItem2.term()).isEqualTo("Đạo Đầu");
        assertThat(actualItem2.referenceScope()).isEqualTo(ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC);
        assertThat(actualItem2.occurrenceIndex()).isEqualTo(1);
        assertThat(actualItem2.status()).isEqualTo(ChapterWikiReferenceStatus.ACTIVE);
        assertThat(actualItem2.isWikiArticleAvailable()).isTrue();

        // Item 3: Occurrence specific, stale, unavailable article
        AdminChapterWikiReferenceViewItem actualItem3 = actualViewItems.get(2);
        assertThat(actualItem3.term()).isEqualTo("Kiếm Khí");
        assertThat(actualItem3.status()).isEqualTo(ChapterWikiReferenceStatus.STALE);
        assertThat(actualItem3.isWikiArticleAvailable()).isFalse();
        assertThat(actualItem3.targetTitle()).isEqualTo("Bài viết không khả dụng");
        assertThat(actualItem3.targetArticleType()).isEqualTo("—");

        // Cache control headers
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("Propagates ChapterNotFoundException when Chapter does not exist")
    void shouldPropagateChapterNotFoundException() {
        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.listWikiReferencesPage(CHAPTER_ID, model, response))
                .isInstanceOf(ChapterNotFoundException.class);
    }
}
