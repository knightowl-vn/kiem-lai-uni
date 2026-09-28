package com.universe.novel.application.locator;

import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort;
import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort.PublishedChapterLocatorRecord;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocatePublishedChaptersUseCaseTest {

    @Mock
    private PublishedChapterLocatorQueryPort queryPort;

    private LocatePublishedChaptersUseCase useCase;

    private static final UUID ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ID_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ID_4 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        useCase = new LocatePublishedChaptersUseCase(queryPort);
    }

    @Test
    @DisplayName("Locates exact published chapter number when query is a pure number")
    void shouldLocateExactChapterByPureNumber() {
        PublishedChapterLocatorRecord chapter123 = new PublishedChapterLocatorRecord(
                ID_1, 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        when(queryPort.findPublishedByChapterNumber(123)).thenReturn(Optional.of(chapter123));
        when(queryPort.findPublishedByTitleKeyword(eq("123"), eq("123"), eq(20))).thenReturn(List.of());

        NovelChapterLocatorResultDTO result = useCase.locate("123", 20);

        assertThat(result.query()).isEqualTo("123");
        assertThat(result.matchedChapterNumber()).isEqualTo(123);
        assertThat(result.items()).hasSize(1);
        NovelChapterLocatorItemDTO item = result.items().get(0);
        assertThat(item.id()).isEqualTo(ID_1);
        assertThat(item.chapterNumber()).isEqualTo(123);
        assertThat(item.title()).isEqualTo("Khởi Đầu");
        assertThat(item.slug()).isEqualTo("quyen-1-chuong-123");
        assertThat(item.volumeSortOrder()).isEqualTo(1);
        assertThat(item.volumeTitle()).isEqualTo("Quyển 1");

        verify(queryPort).findPublishedByChapterNumber(123);
        verify(queryPort).findPublishedByTitleKeyword("123", "123", 20);
    }

    @Test
    @DisplayName("Locates chapter number with Vietnamese prefix 'chương 123'")
    void shouldLocateChapterWithVietnamesePrefix() {
        PublishedChapterLocatorRecord chapter123 = new PublishedChapterLocatorRecord(
                ID_1, 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        when(queryPort.findPublishedByChapterNumber(123)).thenReturn(Optional.of(chapter123));
        when(queryPort.findPublishedByTitleKeyword(eq("chương 123"), eq("chương 123"), eq(20))).thenReturn(List.of());

        NovelChapterLocatorResultDTO result = useCase.locate("chương 123", 20);

        assertThat(result.matchedChapterNumber()).isEqualTo(123);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).chapterNumber()).isEqualTo(123);
    }

    @Test
    @DisplayName("Locates chapter number with accentless prefix 'chuong 123'")
    void shouldLocateChapterWithAccentlessPrefix() {
        PublishedChapterLocatorRecord chapter123 = new PublishedChapterLocatorRecord(
                ID_1, 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        when(queryPort.findPublishedByChapterNumber(123)).thenReturn(Optional.of(chapter123));
        when(queryPort.findPublishedByTitleKeyword(eq("chuong 123"), eq("chuong 123"), eq(20))).thenReturn(List.of());

        NovelChapterLocatorResultDTO result = useCase.locate("chuong 123", 20);

        assertThat(result.matchedChapterNumber()).isEqualTo(123);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("Locates chapter number with English prefix 'chapter 123'")
    void shouldLocateChapterWithEnglishPrefix() {
        PublishedChapterLocatorRecord chapter123 = new PublishedChapterLocatorRecord(
                ID_1, 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        when(queryPort.findPublishedByChapterNumber(123)).thenReturn(Optional.of(chapter123));
        when(queryPort.findPublishedByTitleKeyword(eq("chapter 123"), eq("chapter 123"), eq(20))).thenReturn(List.of());

        NovelChapterLocatorResultDTO result = useCase.locate("chapter 123", 20);

        assertThat(result.matchedChapterNumber()).isEqualTo(123);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("Exact number match ranks before title matches and deduplicates by ID")
    void shouldRankExactNumberBeforeTitleAndDeduplicate() {
        PublishedChapterLocatorRecord chapter123 = new PublishedChapterLocatorRecord(
                ID_1, 123, "123 Con Hạc", "quyen-1-chuong-123", 1, "Quyển 1"
        );
        PublishedChapterLocatorRecord chapter50 = new PublishedChapterLocatorRecord(
                ID_2, 50, "123 Bí Kíp", "quyen-1-chuong-50", 1, "Quyển 1"
        );

        when(queryPort.findPublishedByChapterNumber(123)).thenReturn(Optional.of(chapter123));
        when(queryPort.findPublishedByTitleKeyword(eq("123"), eq("123"), eq(20)))
                .thenReturn(List.of(chapter123, chapter50));

        NovelChapterLocatorResultDTO result = useCase.locate("123", 20);

        assertThat(result.matchedChapterNumber()).isEqualTo(123);
        assertThat(result.items()).hasSize(2);
        // Chapter 123 should be first (Rank 0 from exact number match, despite matching title)
        assertThat(result.items().get(0).chapterNumber()).isEqualTo(123);
        assertThat(result.items().get(1).chapterNumber()).isEqualTo(50);
    }

    @Test
    @DisplayName("Ranks title search results deterministically: exact > prefix > contains, tie-break by chapterNumber ASC")
    void shouldRankTitleSearchResultsDeterministically() {
        PublishedChapterLocatorRecord exactTitle = new PublishedChapterLocatorRecord(
                ID_1, 20, "Đại Đạo", "quyen-1-chuong-20", 1, "Quyển 1"
        );
        PublishedChapterLocatorRecord prefixTitleLowNum = new PublishedChapterLocatorRecord(
                ID_2, 5, "Đại Đạo Triều Thiên", "quyen-1-chuong-5", 1, "Quyển 1"
        );
        PublishedChapterLocatorRecord prefixTitleHighNum = new PublishedChapterLocatorRecord(
                ID_3, 10, "Đại Đạo Vô Song", "quyen-1-chuong-10", 1, "Quyển 1"
        );
        PublishedChapterLocatorRecord containsTitle = new PublishedChapterLocatorRecord(
                ID_4, 1, "Vấn Đại Đạo", "quyen-1-chuong-1", 1, "Quyển 1"
        );

        when(queryPort.findPublishedByTitleKeyword(eq("dại dạo"), eq("dại dạo"), eq(20)))
                .thenReturn(List.of(exactTitle, prefixTitleLowNum, prefixTitleHighNum, containsTitle));

        NovelChapterLocatorResultDTO result = useCase.locate("đại đạo", 20);

        assertThat(result.matchedChapterNumber()).isNull();
        assertThat(result.items()).hasSize(4);
        // 1st: Exact match (Chapter 20) -> Rank 1
        assertThat(result.items().get(0).id()).isEqualTo(ID_1);
        assertThat(result.items().get(0).chapterNumber()).isEqualTo(20);

        // 2nd: Prefix match with lower chapterNumber (Chapter 5) -> Rank 2
        assertThat(result.items().get(1).id()).isEqualTo(ID_2);
        assertThat(result.items().get(1).chapterNumber()).isEqualTo(5);

        // 3rd: Prefix match with higher chapterNumber (Chapter 10) -> Rank 2
        assertThat(result.items().get(2).id()).isEqualTo(ID_3);
        assertThat(result.items().get(2).chapterNumber()).isEqualTo(10);

        // 4th: Contains match (Chapter 1) -> Rank 3
        assertThat(result.items().get(3).id()).isEqualTo(ID_4);
        assertThat(result.items().get(3).chapterNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Matches accentless and d/đ queries seamlessly")
    void shouldMatchAccentlessAndDQueries() {
        PublishedChapterLocatorRecord chapter = new PublishedChapterLocatorRecord(
                ID_1, 1, "Đại Đạo Triều Thiên", "quyen-1-chuong-1", 1, "Quyển 1"
        );
        when(queryPort.findPublishedByTitleKeyword(eq("dai dao"), eq("dai dao"), eq(20)))
                .thenReturn(List.of(chapter));

        NovelChapterLocatorResultDTO result = useCase.locate("dai dao", 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("Đại Đạo Triều Thiên");
    }

    @Test
    @DisplayName("Passes unescaped keyword for exact and escaped keyword for LIKE")
    void shouldPassUnescapedAndEscapedParameters() {
        when(queryPort.findPublishedByTitleKeyword(eq("100%"), eq("100\\%"), eq(20)))
                .thenReturn(List.of());

        useCase.locate("100%", 20);

        verify(queryPort).findPublishedByTitleKeyword("100%", "100\\%", 20);
    }

    @Test
    @DisplayName("Clamps limit correctly: <=0 defaults to 20, >20 clamps to 20, positive within range is respected")
    void shouldClampLimit() {
        PublishedChapterLocatorRecord ch1 = new PublishedChapterLocatorRecord(ID_1, 1, "C1", "s1", 1, "V1");
        PublishedChapterLocatorRecord ch2 = new PublishedChapterLocatorRecord(ID_2, 2, "C2", "s2", 1, "V1");
        PublishedChapterLocatorRecord ch3 = new PublishedChapterLocatorRecord(ID_3, 3, "C3", "s3", 1, "V1");

        when(queryPort.findPublishedByTitleKeyword(eq("C"), eq("C"), eq(2)))
                .thenReturn(List.of(ch1, ch2));

        when(queryPort.findPublishedByTitleKeyword(eq("C"), eq("C"), eq(20)))
                .thenReturn(List.of(ch1, ch2, ch3));

        NovelChapterLocatorResultDTO resultLimit2 = useCase.locate("C", 2);
        assertThat(resultLimit2.items()).hasSize(2);

        NovelChapterLocatorResultDTO resultLimitZero = useCase.locate("C", 0);
        assertThat(resultLimitZero.items()).hasSize(3);

        NovelChapterLocatorResultDTO resultLimitOverMax = useCase.locate("C", 50);
        assertThat(resultLimitOverMax.items()).hasSize(3);
    }

    @Test
    @DisplayName("Returns empty result immediately for null, empty or blank query without querying port")
    void shouldReturnEmptyForBlankQuery() {
        NovelChapterLocatorResultDTO nullResult = useCase.locate(null, 20);
        assertThat(nullResult.query()).isEmpty();
        assertThat(nullResult.items()).isEmpty();

        NovelChapterLocatorResultDTO blankResult = useCase.locate("   ", 20);
        assertThat(blankResult.query()).isEmpty();
        assertThat(blankResult.items()).isEmpty();

        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Returns empty items when matching chapter is unpublished or not found (fail closed)")
    void shouldReturnEmptyWhenNoPublishedChapterMatches() {
        when(queryPort.findPublishedByChapterNumber(999)).thenReturn(Optional.empty());
        when(queryPort.findPublishedByTitleKeyword(eq("999"), eq("999"), eq(20))).thenReturn(List.of());

        NovelChapterLocatorResultDTO result = useCase.locate("999", 20);

        assertThat(result.matchedChapterNumber()).isEqualTo(999);
        assertThat(result.items()).isEmpty();
    }
}
