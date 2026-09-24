package com.universe.wiki.domain.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiContributionSource Domain Tests")
class WikiContributionSourceTest {

    private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTRIBUTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Nested
    @DisplayName("Source Order Invariants (0..4)")
    class SourceOrderTests {

        @Test
        @DisplayName("sourceOrder = 0 (cận dưới) được chấp nhận")
        void shouldAcceptOrderZero() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "https://example.com/source-0", NOW
            );
            assertThat(source.getSourceOrder()).isZero();
        }

        @Test
        @DisplayName("sourceOrder = 4 (cận trên) được chấp nhận")
        void shouldAcceptOrderFour() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 4, "https://example.com/source-4", NOW
            );
            assertThat(source.getSourceOrder()).isEqualTo(4);
        }

        @Test
        @DisplayName("sourceOrder = -1 bị từ chối")
        void shouldRejectNegativeOrder() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, -1, "https://example.com", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("từ 0 đến 4");
        }

        @Test
        @DisplayName("sourceOrder = 5 bị từ chối")
        void shouldRejectOrderGreaterThanFour() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 5, "https://example.com", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("từ 0 đến 4");
        }
    }

    @Nested
    @DisplayName("Identity and Timestamp Validations")
    class IdentityValidationTests {

        @Test
        @DisplayName("id null bị từ chối")
        void shouldRejectNullId() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    null, CONTRIBUTION_ID, 0, "https://example.com", NOW
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID nguồn tham khảo");
        }

        @Test
        @DisplayName("contributionId null bị từ chối")
        void shouldRejectNullContributionId() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, null, 0, "https://example.com", NOW
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID đóng góp");
        }

        @Test
        @DisplayName("now null bị từ chối")
        void shouldRejectNullTimestamp() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "https://example.com", null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Thời gian tạo");
        }
    }

    @Nested
    @DisplayName("URL Length & Blank Validations")
    class UrlLengthTests {

        @Test
        @DisplayName("URL null bị từ chối")
        void shouldRejectNullUrl() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, null, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được để trống");
        }

        @Test
        @DisplayName("URL rỗng hoặc chỉ có khoảng trắng bị từ chối")
        void shouldRejectBlankUrl() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "   ", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được để trống");
        }

        @Test
        @DisplayName("Khoảng trắng đầu và cuối URL được tự động trim")
        void shouldTrimOuterWhitespace() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "   https://example.com/trimmed   ", NOW
            );
            assertThat(source.getUrl()).isEqualTo("https://example.com/trimmed");
        }

        @Test
        @DisplayName("URL hợp lệ có độ dài đúng 2000 ký tự được chấp nhận")
        void shouldAcceptUrlWithExact2000Characters() {
            String base = "https://example.com/path/";
            int remaining = 2000 - base.length();
            String exact2000 = base + "a".repeat(remaining);
            assertThat(exact2000).hasSize(2000);

            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, exact2000, NOW
            );
            assertThat(source.getUrl()).isEqualTo(exact2000);
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
        }

        @Test
        @DisplayName("URL có độ dài 2001 ký tự bị từ chối")
        void shouldRejectUrlWith2001Characters() {
            String base = "https://example.com/path/";
            int remaining = 2001 - base.length();
            String oversized2001 = base + "a".repeat(remaining);
            assertThat(oversized2001).hasSize(2001);

            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, oversized2001, NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 2000 ký tự");
        }
    }

    @Nested
    @DisplayName("External URL Policy")
    class ExternalUrlPolicyTests {

        @Test
        @DisplayName("URL HTTPS tuyệt đối hợp lệ được phân loại là EXTERNAL")
        void shouldClassifyValidHttpsUrlAsExternal() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "https://example.com/path", NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
            assertThat(source.getUrl()).isEqualTo("https://example.com/path");
        }

        @Test
        @DisplayName("URL HTTP tuyệt đối hợp lệ được phân loại là EXTERNAL")
        void shouldClassifyValidHttpUrlAsExternal() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "http://example.com/path", NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
            assertThat(source.getUrl()).isEqualTo("http://example.com/path");
        }

        @Test
        @DisplayName("URL EXTERNAL giữ nguyên query và fragment")
        void shouldPreserveQueryAndFragmentForExternalUrl() {
            String fullUrl = "https://example.com/path?q=1&param=test#section";
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, fullUrl, NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
            assertThat(source.getUrl()).isEqualTo(fullUrl);
        }

        @Test
        @DisplayName("Từ chối các giao thức nguy hiểm: javascript:")
        void shouldRejectJavascriptScheme() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "javascript:alert(1)", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Giao thức URL không được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối các giao thức nguy hiểm: data:")
        void shouldRejectDataScheme() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "data:text/html,<html></html>", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Giao thức URL không được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối các giao thức: file:")
        void shouldRejectFileScheme() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "file:///etc/passwd", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Giao thức URL không được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối các giao thức: ftp:")
        void shouldRejectFtpScheme() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "ftp://example.com/resource", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Giao thức URL không được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối các giao thức: mailto:")
        void shouldRejectMailtoScheme() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "mailto:author@example.com", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Giao thức URL không được hỗ trợ");
        }

        @Test
        @DisplayName("URL tuyệt đối thiếu host bị từ chối")
        void shouldRejectAbsoluteUrlWithoutHost() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "http://", NOW
            )).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "https:///path", NOW
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Internal KiemLai URL Policy & Traversal Safety")
    class InternalUrlPolicyTests {

        @Test
        @DisplayName("Đường dẫn Wiki nội bộ hợp lệ được phân loại là INTERNAL")
        void shouldClassifyWikiPathAsInternal() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/wiki/character/tran-binh-an", NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(source.getUrl()).isEqualTo("/wiki/character/tran-binh-an");
        }

        @Test
        @DisplayName("Đường dẫn Novel nội bộ hợp lệ được phân loại là INTERNAL")
        void shouldClassifyNovelPathAsInternal() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/novel/chapters/chuong-1", NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(source.getUrl()).isEqualTo("/novel/chapters/chuong-1");
        }

        @Test
        @DisplayName("Đường dẫn gốc /wiki và /novel được chấp nhận là INTERNAL")
        void shouldAcceptRootWikiAndNovelPaths() {
            WikiContributionSource s1 = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/wiki", NOW
            );
            assertThat(s1.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(s1.getUrl()).isEqualTo("/wiki");

            WikiContributionSource s2 = WikiContributionSource.create(
                    UUID.randomUUID(), CONTRIBUTION_ID, 1, "/novel", NOW
            );
            assertThat(s2.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(s2.getUrl()).isEqualTo("/novel");
        }

        @Test
        @DisplayName("Đường dẫn nội bộ bảo toàn query và fragment anchor")
        void shouldPreserveQueryAndFragmentForInternalUrl() {
            String fullInternal = "/wiki/character/tran-binh-an?tab=history#tieu-su";
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, fullInternal, NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(source.getUrl()).isEqualTo(fullInternal);
        }

        @Test
        @DisplayName("Chuẩn hóa an toàn dot-segments bên trong phạm vi cho phép: /wiki/a/../b thành /wiki/b")
        void shouldSafelyNormalizeInternalPath() {
            WikiContributionSource source = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/wiki/a/../b#section", NOW
            );
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(source.getUrl()).isEqualTo("/wiki/b#section");
        }

        @Test
        @DisplayName("Từ chối path traversal thoát khỏi tiền tố cho phép: /wiki/../admin")
        void shouldRejectTraversalEscapingWikiPrefix() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/wiki/../admin", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không thuộc phạm vi được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối path traversal thoát khỏi tiền tố cho phép: /novel/../../admin")
        void shouldRejectTraversalEscapingNovelPrefix() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/novel/../../admin", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không thuộc phạm vi được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối encoded traversal: /wiki/%2e%2e/admin")
        void shouldRejectEncodedPathTraversal() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/wiki/%2e%2e/admin", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không thuộc phạm vi được hỗ trợ");
        }

        @Test
        @DisplayName("Từ chối đường dẫn scheme-relative: //evil.example/wiki/character/tran-binh-an")
        void shouldRejectSchemeRelativeUrl() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "//evil.example/wiki/character/tran-binh-an", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scheme-relative");
        }

        @Test
        @DisplayName("Từ chối mẹo đường dẫn chứa dấu gạch chéo ngược (backslash)")
        void shouldRejectBackslashRouteTricks() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "\\wiki\\something", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chứa ký tự không hợp lệ");
        }

        @Test
        @DisplayName("Từ chối đường dẫn gốc không thuộc phạm vi cho phép: /admin/users")
        void shouldRejectUnsupportedRootRelativePath() {
            assertThatThrownBy(() -> WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "/admin/users", NOW
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không thuộc phạm vi được hỗ trợ");
        }
    }

    @Nested
    @DisplayName("Reconstitution and Equality Tests")
    class ReconstitutionTests {

        @Test
        @DisplayName("Reconstitute khôi phục đầy đủ trạng thái mà không thay đổi giá trị đã lưu")
        void shouldReconstitutePersistedState() {
            WikiContributionSource source = WikiContributionSource.reconstitute(
                    SOURCE_ID,
                    CONTRIBUTION_ID,
                    2,
                    WikiContributionSourceType.INTERNAL,
                    "/wiki/test",
                    NOW
            );

            assertThat(source.getId()).isEqualTo(SOURCE_ID);
            assertThat(source.getContributionId()).isEqualTo(CONTRIBUTION_ID);
            assertThat(source.getSourceOrder()).isEqualTo(2);
            assertThat(source.getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(source.getUrl()).isEqualTo("/wiki/test");
            assertThat(source.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Equals và HashCode dựa trên ID nguồn tham khảo")
        void shouldFollowEqualsAndHashCodeContract() {
            WikiContributionSource s1 = WikiContributionSource.create(
                    SOURCE_ID, CONTRIBUTION_ID, 0, "https://example.com/1", NOW
            );
            WikiContributionSource s2 = WikiContributionSource.create(
                    SOURCE_ID, UUID.randomUUID(), 3, "https://example.com/diff", NOW
            );
            WikiContributionSource s3 = WikiContributionSource.create(
                    UUID.randomUUID(), CONTRIBUTION_ID, 0, "https://example.com/1", NOW
            );

            assertThat(s1).isEqualTo(s2);
            assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
            assertThat(s1).isNotEqualTo(s3);
        }
    }
}
