package com.universe.wiki.domain.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiContribution Domain Tests")
class WikiContributionTest {

    private static final UUID CONTRIBUTION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private static final String VALID_TYPE = "CHARACTER";
    private static final String VALID_TITLE = "Trần Bình An";
    private static final String VALID_SLUG = "tran-binh-an";
    private static final long VALID_CONTENT_VERSION = 1L;
    private static final String VALID_MESSAGE = "Đoạn văn này cần bổ sung thêm thông tin về cảnh giới tu vi.";

    @Nested
    @DisplayName("General Contribution Factory (createGeneral)")
    class GeneralContributionTests {

        @Test
        @DisplayName("Tạo đóng góp GENERAL hợp lệ: status khởi tạo là NEW, version là 0, anchor fields là null")
        void shouldCreateValidGeneralContribution() {
            WikiContribution contribution = WikiContribution.createGeneral(
                    CONTRIBUTION_ID,
                    ARTICLE_ID,
                    VALID_TYPE,
                    VALID_TITLE,
                    VALID_SLUG,
                    VALID_CONTENT_VERSION,
                    USER_ID,
                    WikiContributionType.MISSING_INFORMATION,
                    VALID_MESSAGE,
                    NOW
            );

            assertThat(contribution.getId()).isEqualTo(CONTRIBUTION_ID);
            assertThat(contribution.getArticleId()).isEqualTo(ARTICLE_ID);
            assertThat(contribution.getArticleTypeSnapshot()).isEqualTo(VALID_TYPE);
            assertThat(contribution.getArticleTitleSnapshot()).isEqualTo(VALID_TITLE);
            assertThat(contribution.getArticleSlugSnapshot()).isEqualTo(VALID_SLUG);
            assertThat(contribution.getArticleContentVersion()).isEqualTo(VALID_CONTENT_VERSION);
            assertThat(contribution.getSubmittedByUserId()).isEqualTo(USER_ID);
            assertThat(contribution.getContextType()).isEqualTo(WikiContributionContextType.GENERAL);
            assertThat(contribution.getContributionType()).isEqualTo(WikiContributionType.MISSING_INFORMATION);
            assertThat(contribution.getMessage()).isEqualTo(VALID_MESSAGE);
            assertThat(contribution.getSelectedText()).isNull();
            assertThat(contribution.getSelectedPrefix()).isNull();
            assertThat(contribution.getSelectedSuffix()).isNull();
            assertThat(contribution.getSelectedHeadingAnchor()).isNull();
            assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.NEW);
            assertThat(contribution.getVersion()).isZero();
            assertThat(contribution.getCreatedAt()).isEqualTo(NOW);
            assertThat(contribution.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Reconstitute đóng góp GENERAL chứa anchor non-blank phải ném ngoại lệ")
        void shouldRejectGeneralContributionWithAnchors() {
            assertThatThrownBy(() -> WikiContribution.reconstitute(
                    CONTRIBUTION_ID,
                    ARTICLE_ID,
                    VALID_TYPE,
                    VALID_TITLE,
                    VALID_SLUG,
                    VALID_CONTENT_VERSION,
                    USER_ID,
                    WikiContributionContextType.GENERAL,
                    WikiContributionType.OTHER,
                    VALID_MESSAGE,
                    "Có trích dẫn",
                    null,
                    null,
                    null,
                    WikiContributionStatus.NEW,
                    0L,
                    NOW,
                    NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Đóng góp dạng tổng quan không được chứa thông tin trích dẫn");
        }
    }

    @Nested
    @DisplayName("Text Selection Contribution Factory (createTextSelection)")
    class TextSelectionContributionTests {

        @Test
        @DisplayName("Tạo đóng góp TEXT_SELECTION hợp lệ đầy đủ neo: chuẩn hóa khoảng trắng và lưu trữ chính xác")
        void shouldCreateValidTextSelectionContributionWithAllAnchors() {
            WikiContribution contribution = WikiContribution.createTextSelection(
                    CONTRIBUTION_ID,
                    ARTICLE_ID,
                    VALID_TYPE,
                    VALID_TITLE,
                    VALID_SLUG,
                    VALID_CONTENT_VERSION,
                    USER_ID,
                    WikiContributionType.WORDING,
                    VALID_MESSAGE,
                    "  Trần   Bình   An   rời khỏi   bùn đất.  ",
                    "  Đoạn trước:   ",
                    "   Đoạn sau...  ",
                    "  heading-xuat-than  ",
                    NOW
            );

            assertThat(contribution.getContextType()).isEqualTo(WikiContributionContextType.TEXT_SELECTION);
            assertThat(contribution.getSelectedText()).isEqualTo("Trần Bình An rời khỏi bùn đất.");
            assertThat(contribution.getSelectedPrefix()).isEqualTo("Đoạn trước:");
            assertThat(contribution.getSelectedSuffix()).isEqualTo("Đoạn sau...");
            assertThat(contribution.getSelectedHeadingAnchor()).isEqualTo("heading-xuat-than");
            assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.NEW);
            assertThat(contribution.getVersion()).isZero();
        }

        @Test
        @DisplayName("Tạo đóng góp TEXT_SELECTION với chỉ selectedText (các trường prefix/suffix/heading rỗng được chuẩn hóa về null)")
        void shouldNormalizeEmptyOptionalAnchorsToNull() {
            WikiContribution contribution = WikiContribution.createTextSelection(
                    CONTRIBUTION_ID,
                    ARTICLE_ID,
                    VALID_TYPE,
                    VALID_TITLE,
                    VALID_SLUG,
                    VALID_CONTENT_VERSION,
                    USER_ID,
                    WikiContributionType.WORDING,
                    VALID_MESSAGE,
                    "Câu chữ cần sửa.",
                    "   ",
                    "",
                    null,
                    NOW
            );

            assertThat(contribution.getSelectedText()).isEqualTo("Câu chữ cần sửa.");
            assertThat(contribution.getSelectedPrefix()).isNull();
            assertThat(contribution.getSelectedSuffix()).isNull();
            assertThat(contribution.getSelectedHeadingAnchor()).isNull();
        }

        @Test
        @DisplayName("Ném ngoại lệ khi TEXT_SELECTION thiếu selectedText (null hoặc chỉ khoảng trắng)")
        void shouldRejectTextSelectionWithoutSelectedText() {
            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, null, null, null, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Đoạn văn bản trích dẫn không được để trống");

            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, "    \n\t   ", null, null, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Đoạn văn bản trích dẫn không được để trống");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi selectedText vượt quá 1000 ký tự")
        void shouldRejectOversizedSelectedText() {
            String exactly1000 = "a".repeat(1000);
            WikiContribution valid = WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, exactly1000, null, null, null, NOW
            );
            assertThat(valid.getSelectedText()).hasSize(1000);

            String oversized = "a".repeat(1001);
            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, oversized, null, null, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1000 ký tự");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi selectedPrefix hoặc selectedSuffix vượt quá 100 ký tự")
        void shouldRejectOversizedPrefixOrSuffix() {
            String oversized101 = "p".repeat(101);
            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, "valid text", oversized101, null, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tiền tố trích dẫn không được vượt quá 100 ký tự");

            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, "valid text", null, oversized101, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hậu tố trích dẫn không được vượt quá 100 ký tự");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi selectedHeadingAnchor vượt quá 255 ký tự")
        void shouldRejectOversizedHeadingAnchor() {
            String oversized256 = "h".repeat(256);
            assertThatThrownBy(() -> WikiContribution.createTextSelection(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.WORDING, VALID_MESSAGE, "valid text", null, null, oversized256, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("neo tiêu đề không được vượt quá 255 ký tự");
        }
    }

    @Nested
    @DisplayName("Message Validation & Invariants")
    class MessageValidationTests {

        @Test
        @DisplayName("Kiểm tra biên độ dài message: 19 ký tự (lỗi), 20 ký tự (hợp lệ), 5000 ký tự (hợp lệ), 5001 ký tự (lỗi)")
        void shouldEnforceMessageLengthBoundaries() {
            String len19 = "1234567890123456789";
            assertThat(len19).hasSize(19);
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, len19, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("từ 20 đến 5000 ký tự");

            String len20 = "12345678901234567890";
            assertThat(len20).hasSize(20);
            WikiContribution atMin = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, len20, NOW
            );
            assertThat(atMin.getMessage()).isEqualTo(len20);

            String len5000 = "x".repeat(5000);
            WikiContribution atMax = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, len5000, NOW
            );
            assertThat(atMax.getMessage()).hasSize(5000);

            String len5001 = "x".repeat(5001);
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, len5001, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("từ 20 đến 5000 ký tự");
        }

        @Test
        @DisplayName("Message null hoặc rỗng hoặc chỉ toàn khoảng trắng phải ném ngoại lệ")
        void shouldRejectNullOrBlankMessage() {
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được để trống");

            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, "                  ", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("từ 20 đến 5000 ký tự");
        }

        @Test
        @DisplayName("Bảo toàn định dạng ngắt dòng (newlines) và thụt lề bên trong message; chỉ trim đầu và cuối")
        void shouldPreserveInternalNewlinesAndWhitespaceInMessage() {
            String multilineMessage = "  Dòng 1: Cần đính chính thông tin.\n\n  Dòng 2: Tham khảo chương 1500.\n    Dòng 3: Thụt lề 4 space.  ";
            WikiContribution contribution = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.INCORRECT_INFORMATION, multilineMessage, NOW
            );

            String expected = "Dòng 1: Cần đính chính thông tin.\n\n  Dòng 2: Tham khảo chương 1500.\n    Dòng 3: Thụt lề 4 space.";
            assertThat(contribution.getMessage()).isEqualTo(expected);
            assertThat(contribution.getMessage()).contains("\n\n  Dòng 2:");
        }
    }

    @Nested
    @DisplayName("Article Snapshot & Version Validations")
    class SnapshotValidationTests {

        @Test
        @DisplayName("Ném ngoại lệ khi articleTypeSnapshot rỗng hoặc quá 30 ký tự")
        void shouldValidateArticleTypeSnapshot() {
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, "   ", VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Loại bài viết snapshot không được để trống");

            String exactly30 = "t".repeat(30);
            WikiContribution valid = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, exactly30, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            );
            assertThat(valid.getArticleTypeSnapshot()).hasSize(30);

            String oversized31 = "t".repeat(31);
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, oversized31, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 30 ký tự");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi articleTitleSnapshot rỗng hoặc quá 200 ký tự")
        void shouldValidateArticleTitleSnapshot() {
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, null, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tiêu đề bài viết snapshot không được để trống");

            String oversized201 = "t".repeat(201);
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, oversized201, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 200 ký tự");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi articleSlugSnapshot rỗng hoặc quá 180 ký tự")
        void shouldValidateArticleSlugSnapshot() {
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, "   ", VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Slug bài viết snapshot không được để trống");

            String exactly180 = "s".repeat(180);
            WikiContribution valid = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, exactly180, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            );
            assertThat(valid.getArticleSlugSnapshot()).hasSize(180);

            String oversized181 = "s".repeat(181);
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, oversized181, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 180 ký tự");
        }

        @Test
        @DisplayName("Ném ngoại lệ khi articleContentVersion nhỏ hơn 1")
        void shouldRejectContentVersionLessThanOne() {
            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, 0L,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lớn hơn hoặc bằng 1");

            assertThatThrownBy(() -> WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, -5L,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lớn hơn hoặc bằng 1");
        }
    }

    @Nested
    @DisplayName("Reconstitution, Lifecycle & Identity Tests")
    class ReconstitutionTests {

        @Test
        @DisplayName("Reconstitute khôi phục đầy đủ trạng thái và version từ persistence")
        void shouldReconstitutePersistedState() {
            Instant created = Instant.parse("2026-09-20T10:00:00Z");
            Instant updated = Instant.parse("2026-09-24T12:00:00Z");

            WikiContribution contribution = WikiContribution.reconstitute(
                    CONTRIBUTION_ID,
                    ARTICLE_ID,
                    VALID_TYPE,
                    VALID_TITLE,
                    VALID_SLUG,
                    VALID_CONTENT_VERSION,
                    USER_ID,
                    WikiContributionContextType.GENERAL,
                    WikiContributionType.SOURCE_REFERENCE,
                    VALID_MESSAGE,
                    null,
                    null,
                    null,
                    null,
                    WikiContributionStatus.REVIEWING,
                    5L,
                    created,
                    updated
            );

            assertThat(contribution.getStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
            assertThat(contribution.getVersion()).isEqualTo(5L);
            assertThat(contribution.getCreatedAt()).isEqualTo(created);
            assertThat(contribution.getUpdatedAt()).isEqualTo(updated);
        }

        @Test
        @DisplayName("Equals và HashCode dựa trên ID duy nhất của Aggregate")
        void shouldFollowEqualsAndHashCodeContract() {
            WikiContribution c1 = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            );
            WikiContribution c2 = WikiContribution.createGeneral(
                    CONTRIBUTION_ID, ARTICLE_ID, "OTHER", "Other Title", "other-title", 2L,
                    UUID.randomUUID(), WikiContributionType.WORDING, "Khác nội dung nhưng cùng ID...", NOW
            );
            WikiContribution c3 = WikiContribution.createGeneral(
                    UUID.randomUUID(), ARTICLE_ID, VALID_TYPE, VALID_TITLE, VALID_SLUG, VALID_CONTENT_VERSION,
                    USER_ID, WikiContributionType.OTHER, VALID_MESSAGE, NOW
            );

            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
            assertThat(c1).isNotEqualTo(c3);
        }
    }
}
