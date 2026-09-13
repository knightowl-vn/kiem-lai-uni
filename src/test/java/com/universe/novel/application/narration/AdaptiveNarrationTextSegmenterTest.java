package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.novel.infrastructure.markdown.CommonMarkChapterNarrationBlockExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptiveNarrationTextSegmenterTest {
    @Test
    void plansRetainDistinctOrdinalsForPackedDuplicateBlocks() {
        var plans = segmenter.plan("Same paragraph.\n\nSame paragraph.\n\nLast paragraph.");
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).sourceBlockIndexes()).containsExactly(0, 1, 2);
        assertThat(plans.get(0).segment()).isEqualTo(NarrationTextSegment.of(0,
                "Same paragraph.\n\nSame paragraph.\n\nLast paragraph."));
        assertThat(segmenter.segment("Same paragraph.\n\nSame paragraph.\n\nLast paragraph."))
                .containsExactly(plans.get(0).segment());
    }

    @Test
    void oversizedBlockPlansKeepSameOrdinalAcrossSegments() {
        String sentence = "a".repeat(398) + ".";
        String markdown = String.join(" ", java.util.Collections.nCopies(5, sentence));
        var plans = segmenter.plan(markdown);
        assertThat(plans).hasSize(3);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.sourceBlockIndexes()).containsExactly(0));
        assertThat(segmenter.segment(markdown)).containsExactly(
                NarrationTextSegment.of(0, sentence + " " + sentence),
                NarrationTextSegment.of(1, sentence + " " + sentence),
                NarrationTextSegment.of(2, sentence));
    }

    @Test
    void provenancePreservesSoftThresholdAndBlockDelimiters() {
        String first = "a".repeat(500);
        String second = "b".repeat(450);
        String third = "c".repeat(100);
        var plans = segmenter.plan(first + "\n\n" + second + "\n\n" + third);
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).segment()).isEqualTo(NarrationTextSegment.of(0, first + "\n\n" + second));
        assertThat(plans.get(0).sourceBlockIndexes()).containsExactly(0, 1);
        assertThat(plans.get(1).segment()).isEqualTo(NarrationTextSegment.of(1, third));
        assertThat(plans.get(1).sourceBlockIndexes()).containsExactly(2);
    }

    private AdaptiveNarrationTextSegmenter segmenter;

    @BeforeEach
    void setUp() {
        CommonMarkChapterNarrationBlockExtractor extractor = new CommonMarkChapterNarrationBlockExtractor();
        segmenter = new AdaptiveNarrationTextSegmenter(extractor);
    }

    @Test
    @DisplayName("1. Blank or formatting-only content produces no segments")
    void blankContentProducesNoSegments() {
        assertThat(segmenter.segment(null)).isEmpty();
        assertThat(segmenter.segment("")).isEmpty();
        assertThat(segmenter.segment("   \n\t  \n   ")).isEmpty();
        assertThat(segmenter.segment("---\n\n***\n\n___")).isEmpty();
        assertThat(segmenter.segment("<script>alert('xss')</script>")).isEmpty();
    }

    @Test
    @DisplayName("2. Same input produces completely identical segments, indexes, texts, and hashes")
    void sameInputProducesIdenticalSegmentsAndHashes() {
        String markdown = """
                # Tiết 1: Đào Hoa Sơn

                Trần Bình An mang theo chiếc hộp gỗ nhỏ, chậm rãi bước từng bước lên con đường núi quanh co.
                Gió thổi qua rặng trúc tạo nên những âm thanh xào xạc quen thuộc.

                "Ngươi thật sự quyết định rời đi sao?" — Một giọng nói trầm thấp vang lên từ phía sau gốc tùng cổ thụ.

                Trần Bình An dừng bước, quay đầu mỉm cười: "Tiền bối, thiên hạ rộng lớn, vãn bối muốn đi xem một chút."
                """;

        List<NarrationTextSegment> firstRun = segmenter.segment(markdown);
        List<NarrationTextSegment> secondRun = segmenter.segment(markdown);

        assertThat(firstRun).isNotEmpty();
        assertThat(firstRun).hasSameSizeAs(secondRun);

        for (int i = 0; i < firstRun.size(); i++) {
            NarrationTextSegment s1 = firstRun.get(i);
            NarrationTextSegment s2 = secondRun.get(i);

            assertThat(s1.index()).isEqualTo(s2.index()).isEqualTo(i);
            assertThat(s1.text()).isEqualTo(s2.text());
            assertThat(s1.characterCount()).isEqualTo(s2.characterCount()).isEqualTo(s1.text().length());
            assertThat(s1.contentHash()).isEqualTo(s2.contentHash()).matches("^[0-9a-f]{64}$");
        }
    }

    @Test
    @DisplayName("3. Ordinary paragraphs are adaptively packed into target window (600-900 chars)")
    void ordinaryParagraphsArePackedAdaptively() {
        String p1 = "Trần Bình An đứng trên đỉnh núi nhìn xuống trấn Đào Hoa bên dưới. "
                + "Ánh tà dương trải dài trên những mái ngói rêu phong cổ kính của thôn xóm yên bình.";
        String p2 = "Hắn nhớ lại những ngày tháng còn thơ ấu lang thang khắp các ngõ hẻm. "
                + "Khi đó hắn chỉ là một hài đồng gầy gò, chưa từng nghĩ mình sẽ bước vào tiên lộ.";
        String p3 = "Một cơn gió lạnh thổi qua làm bay tà áo bào xanh giản dị của thiếu niên. "
                + "Thanh kiếm sau lưng khẽ rung lên một tiếng ngân vang thanh thoát tựa như tiếng rồng ngâm.";
        String p4 = "Xa xa, dòng sông Ly Châu uốn lượn như một dải lụa bạc lấp lánh dưới ánh chiều tà. "
                + "Những chiếc thuyền đánh cá nhỏ bé đang hối hả chèo về bến đỗ trước khi màn đêm buông xuống.";
        String p5 = "Hắn khẽ thở dài một tiếng, thu lại ánh mắt rồi tiếp tục cất bước đi về phía trước. "
                + "Con đường phía trước còn rất dài, hắn biết mình không thể dừng lại ở nơi này quá lâu.";
        String p6 = "Bóng hình thiếu niên dần khuất sau khúc quanh của dãy núi Đào Hoa mờ sương. "
                + "Chỉ còn lại tiếng gió ngàn xào xạc như lời tiễn biệt của quê hương dành cho người ra đi.";
        String p7 = "Bước chân của hắn tuy chậm rãi nhưng vô cùng vững vàng trên con đường đá dốc. "
                + "Từng nhịp thở đều đặn hòa cùng linh khí đất trời tạo nên một cảm giác an định lạ thường.";
        String p8 = "Trong tâm trí hắn hiện lên những lời căn dặn ân cần của sư phụ trước ngày xuất sơn. "
                + "Đạo tâm phải vững như bàn thạch, kiếm ý phải sắc như sương tuyết mùa đông.";
        String p9 = "Đêm dần buông xuống trên vạn dặm non sông, ánh sao đầu tiên bắt đầu lấp lánh trên nền trời. "
                + "Thiếu niên dừng chân bên một gò đất cao, chuẩn bị nhóm lửa dựng trại qua đêm.";

        String chapter = String.join("\n\n", List.of(p1, p2, p3, p4, p5, p6, p7, p8, p9));

        List<NarrationTextSegment> segments = segmenter.segment(chapter);

        assertThat(segments).hasSize(2);

        NarrationTextSegment seg0 = segments.get(0);
        assertThat(seg0.index()).isEqualTo(0);
        assertThat(seg0.characterCount()).isBetween(600, 950);
        assertThat(seg0.text()).contains(p1).contains(p2).contains(p3).contains(p4).contains(p5);

        NarrationTextSegment seg1 = segments.get(1);
        assertThat(seg1.index()).isEqualTo(1);
        assertThat(seg1.characterCount()).isBetween(600, 950);
        assertThat(seg1.text()).contains(p6).contains(p7).contains(p8).contains(p9);
    }

    @Test
    @DisplayName("4. Normal paragraph followed by an oversized split block preserves paragraph boundary (\\n\\n)")
    void normalParagraphFollowedByOversizedBlockPreservesParagraphBoundary() {
        String normalPara = "Đây là đoạn văn mở đầu bình thường ngắn gọn của chương truyện.";

        // Oversized paragraph (>1500 chars) consisting of 4 long sentences
        String s1 = "Câu thứ nhất trong đoạn văn quá khổ mô tả khung cảnh núi non hùng vĩ trải dài ngút ngàn về phía chân trời xa xôi nơi mây mù bao phủ quanh năm suốt tháng.";
        String s2 = "Câu thứ hai tiếp tục khắc họa bóng hình thiếu niên kiên cường bước đi giữa gió tuyết lạnh giá không hề chùn bước trước bất kỳ gian nan thử thách nào trên đường.";
        String s3 = "Câu thứ ba ghi lại tiếng kiếm ngân vang xé toạc không gian tĩnh mịch của màn đêm u tối khi ánh trăng rằm chiếu rọi qua từng tán lá thông cổ thụ già cỗi.";
        String s4 = "Câu thứ tư kết thúc đoạn văn dài với lời thề son sắt của người tu đạo quyết tâm tìm kiếm chân lý tối thượng của đại đạo thiên địa muôn đời.";
        String oversizedPara = (s1 + " " + s2 + " " + s3 + " " + s4 + " ").repeat(3).trim();
        assertThat(oversizedPara.length()).isGreaterThan(1500);

        String markdown = normalPara + "\n\n" + oversizedPara;

        List<NarrationTextSegment> segments = segmenter.segment(markdown);

        assertThat(segments).isNotEmpty();
        NarrationTextSegment seg0 = segments.get(0);

        // Verify normal paragraph and first sentence of oversized block are separated by "\n\n"
        assertThat(seg0.text()).startsWith(normalPara + "\n\n" + s1);
    }

    @Test
    @DisplayName("5. Sentences within the same oversized block use sentence continuation spacing (' ')")
    void sentencesWithinSameOversizedBlockUseSentenceContinuationSpacing() {
        String s1 = "Câu thứ nhất trong đoạn văn quá khổ mô tả dòng sông Ly Châu mùa nước lũ chảy xiết cuồn cuộn.";
        String s2 = "Câu thứ hai miêu tả chiếc thuyền câu bé nhỏ chao đảo giữa những con sóng bạc đầu hung dữ.";
        String s3 = "Câu thứ ba kể về người lái đò già dặn kinh nghiệm bình tĩnh điều khiển tay chèo vượt qua ghềnh thác hiểm trở.";
        String s4 = "Câu thứ tư khắc họa nụ cười rạng rỡ khi con thuyền cập bến an toàn trong ánh hoàng hôn tuyệt đẹp.";
        String oversizedPara = (s1 + " " + s2 + " " + s3 + " " + s4 + " ").repeat(4).trim();
        assertThat(oversizedPara.length()).isGreaterThan(1500);

        List<NarrationTextSegment> segments = segmenter.segment(oversizedPara);

        assertThat(segments).isNotEmpty();
        // Sentences within the same block are joined with a single space, not "\n\n"
        assertThat(segments.get(0).text()).contains(s1 + " " + s2);
        assertThat(segments.get(0).text()).doesNotContain(s1 + "\n\n" + s2);
    }

    @Test
    @DisplayName("6. Two consecutive oversized blocks preserve their block boundary (\\n\\n)")
    void twoConsecutiveOversizedBlocksPreserveBlockBoundary() {
        // Custom segmenter with valid proportional thresholds: targetMin=200, targetMax=400, softMax=500, hardThreshold=600
        AdaptiveNarrationTextSegmenter customSegmenter = new AdaptiveNarrationTextSegmenter(
                new CommonMarkChapterNarrationBlockExtractor(),
                200, 400, 500, 600
        );

        // Block 1 is ~700 chars (>600 threshold)
        String s1 = "Câu một của khối thứ nhất rất dài và chi tiết mô tả cảnh quan thiên nhiên tráng lệ.";
        String s2 = "Câu hai của khối thứ nhất tiếp tục miêu tả cảnh vật xung quanh một cách cẩn thận và sinh động.";
        String s3 = "Câu ba của khối thứ nhất kết thúc phần một với những cảm xúc sâu lắng lắng đọng trong lòng người đọc.";
        String block1 = (s1 + " " + s2 + " " + s3 + " ").repeat(3).trim();
        assertThat(block1.length()).isGreaterThan(600);

        // Block 2 is ~700 chars (>600 threshold)
        String s4 = "Câu một của khối thứ hai bắt đầu với khung cảnh mới mẻ hoàn toàn khác biệt.";
        String s5 = "Câu hai của khối thứ hai tiếp diễn diễn biến câu chuyện ly kỳ hấp dẫn người đọc theo từng trang viết.";
        String s6 = "Câu ba của khối thứ hai khép lại toàn bộ diễn biến với cái kết đầy bất ngờ và kịch tính.";
        String block2 = (s4 + " " + s5 + " " + s6 + " ").repeat(3).trim();
        assertThat(block2.length()).isGreaterThan(600);

        String markdown = block1 + "\n\n" + block2;

        List<NarrationTextSegment> segments = customSegmenter.segment(markdown);

        assertThat(segments).isNotEmpty();
        String fullText = String.join("\n\n---\n\n", segments.stream().map(NarrationTextSegment::text).toList());

        // Sentences within block 1 joined with space
        assertThat(fullText).contains(s1 + " " + s2);
        // Sentences within block 2 joined with space
        assertThat(fullText).contains(s4 + " " + s5);
        // Boundary between block 1 and block 2 has "\n\n" (paragraph boundary)
        assertThat(fullText).contains(s3 + "\n\n" + s4);
    }

    @Test
    @DisplayName("7. NarrationTextSegment rejects invalid states and mismatched content hashes")
    void narrationTextSegmentValidations() {
        assertThatThrownBy(() -> new NarrationTextSegment(-1, "Text", 4, NarrationTextSegment.computeSha256("Text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment index must be non-negative.");

        assertThatThrownBy(() -> new NarrationTextSegment(0, null, 0, "hash"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new NarrationTextSegment(0, "Text", 4, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new NarrationTextSegment(0, "Text", 10, NarrationTextSegment.computeSha256("Text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Character count must match text length.");

        // Mismatched contentHash
        assertThatThrownBy(() -> new NarrationTextSegment(0, "Text", 4, "bad0000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content hash does not match SHA-256 digest of segment text.");
    }

    @Test
    @DisplayName("8. Valid factory-created segment is accepted with correct character count and hash")
    void validFactoryCreatedSegmentIsAccepted() {
        String text = "Trần Bình An cất bước lên đường.";
        NarrationTextSegment seg = NarrationTextSegment.of(3, text);

        assertThat(seg.index()).isEqualTo(3);
        assertThat(seg.text()).isEqualTo(text);
        assertThat(seg.characterCount()).isEqualTo(text.length());
        assertThat(seg.contentHash()).isEqualTo(NarrationTextSegment.computeSha256(text));
    }

    @Test
    @DisplayName("9. No normal sentence is cut mid-sentence, and decimal numbers are not falsely split")
    void noSentenceCutMidSentenceAndDecimalsPreserved() {
        String paragraphWithDecimals = """
                Số bạc mà Trần Bình An kiếm được là 1.500.000 đồng tiền cổ.
                Hệ số tinh luyện thanh kiếm đạt 3.14 lần so với mức thông thường của thợ rèn trấn Đào Hoa.
                Sau đó hắn tiếp tục cuộc hành trình lên núi.
                """;

        List<String> sentences = AdaptiveNarrationTextSegmenter.splitSentences(paragraphWithDecimals);

        assertThat(sentences).hasSize(3);
        assertThat(sentences.get(0)).isEqualTo("Số bạc mà Trần Bình An kiếm được là 1.500.000 đồng tiền cổ.");
        assertThat(sentences.get(1)).isEqualTo("Hệ số tinh luyện thanh kiếm đạt 3.14 lần so với mức thông thường của thợ rèn trấn Đào Hoa.");
        assertThat(sentences.get(2)).isEqualTo("Sau đó hắn tiếp tục cuộc hành trình lên núi.");
    }

    @Test
    @DisplayName("10. Pathological single sentence exceeding threshold is kept intact rather than cut mid-sentence")
    void pathologicalOversizedSentenceKeptIntact() {
        String singleHugeSentence = "Trần Bình An bước đi " + "và bước đi tiếp tục đi mãi không dừng ".repeat(50);
        assertThat(singleHugeSentence.length()).isGreaterThan(1500);

        List<NarrationTextSegment> segments = segmenter.segment(singleHugeSentence);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).characterCount()).isEqualTo(singleHugeSentence.trim().length());
        assertThat(segments.get(0).text()).isEqualTo(singleHugeSentence.trim());
    }

    @Test
    @DisplayName("11. Vietnamese punctuation, tone marks, quotes, and ellipsis remain meaningful")
    void vietnamesePunctuationAndDialogueRemainsMeaningful() {
        String markdown = """
                “Hắn đã nói gì với ngươi?” — Giọng nói sắc lạnh vang lên.

                ‘Chẳng nói gì cả…’ — Thiếu niên lắc đầu đáp.

                «Vậy thì ngươi có thể đi rồi!»
                """;

        List<NarrationTextSegment> segments = segmenter.segment(markdown);

        assertThat(segments).hasSize(1);
        String text = segments.get(0).text();

        assertThat(text).contains("“Hắn đã nói gì với ngươi?” — Giọng nói sắc lạnh vang lên.");
        assertThat(text).contains("‘Chẳng nói gì cả…’ — Thiếu niên lắc đầu đáp.");
        assertThat(text).contains("«Vậy thì ngươi có thể đi rồi!»");
    }

    @Test
    @DisplayName("12. Synthetic ~20,000-character chapter produces a variable number of reasonable segments")
    void syntheticLargeChapterProducesVariableSegments() {
        StringBuilder chapterBuilder = new StringBuilder();
        chapterBuilder.append("# Chương 100: Đại Đạo Triều Thiên\n\n");

        int paraIndex = 1;
        while (chapterBuilder.length() < 20_000) {
            int repeatCount = (paraIndex % 5) + 1;
            StringBuilder para = new StringBuilder();
            for (int s = 0; s < repeatCount; s++) {
                para.append("Trần Bình An nắm chặt thanh kiếm trong tay, nhìn về phía chân trời xa xăm nơi mây đen đang cuồn cuộn kéo tới. ");
                para.append("Mỗi bước đi trên con đường tu đạo này đều là sự tôi luyện sinh tử gian nan khôn cùng! ");
            }
            chapterBuilder.append(para.toString().trim()).append("\n\n");
            paraIndex++;
        }

        String chapterContent = chapterBuilder.toString();
        assertThat(chapterContent.length()).isGreaterThanOrEqualTo(20_000);

        List<NarrationTextSegment> segments = segmenter.segment(chapterContent);

        assertThat(segments.size()).isBetween(20, 35);

        for (int i = 0; i < segments.size(); i++) {
            NarrationTextSegment seg = segments.get(i);
            assertThat(seg.index()).isEqualTo(i);
            assertThat(seg.characterCount()).isGreaterThan(0);
            assertThat(seg.characterCount()).isEqualTo(seg.text().length());
            assertThat(seg.contentHash()).matches("^[0-9a-f]{64}$");
        }
    }
}
