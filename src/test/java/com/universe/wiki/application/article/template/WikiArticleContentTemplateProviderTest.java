package com.universe.wiki.application.article.template;

import com.universe.wiki.domain.article.ArticleType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiArticleContentTemplateProviderTest {

    private WikiArticleContentTemplateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WikiArticleContentTemplateProvider();
    }

    /*
     * =====================================================
     * COMMON
     * =====================================================
     */

    @Test
    @DisplayName("Mọi ArticleType đều có content template")
    void shouldProvideTemplateForEveryArticleType() {
        for (ArticleType articleType : ArticleType.values()) {

            String template = provider.getTemplate(articleType);

            assertThat(template)
                    .as("Template của %s không được rỗng", articleType)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Template không sử dụng H1 vì H1 là tiêu đề bài Wiki")
    void shouldNotContainLevelOneHeading() {
        for (ArticleType articleType : ArticleType.values()) {

            String template = provider.getTemplate(articleType);

            boolean containsLevelOneHeading =
                    template.lines()
                            .map(String::stripLeading)
                            .anyMatch(line ->
                                    line.startsWith("# ")
                            );

            assertThat(containsLevelOneHeading)
                    .as(
                            "Template của %s không được chứa heading H1",
                            articleType
                    )
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Từ chối ArticleType null")
    void shouldRejectNullArticleType() {
        assertThatThrownBy(() ->
                provider.getTemplate(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "Loại bài Wiki không được để trống."
                );
    }

    /*
     * =====================================================
     * CHARACTER
     * =====================================================
     */

    @Test
    @DisplayName("Template CHARACTER có cấu trúc dành cho nhân vật")
    void shouldProvideCharacterTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.CHARACTER
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Xuất thân và bối cảnh"
                )
                .contains(
                        "### Xuất thân đặc biệt / Tiền kiếp"
                )
                .contains(
                        "## Tính cách và đạo tâm"
                )
                .contains(
                        "### Đạo tâm"
                )
                .contains(
                        "## Tu hành và năng lực"
                )
                .contains(
                        "### Cảnh giới"
                )
                .contains(
                        "### Công pháp"
                )
                .contains(
                        "### Năng lực đặc thù"
                )
                .contains(
                        "## Vũ khí và bảo vật"
                )
                .contains(
                        "### Bản mệnh vật"
                )
                .contains(
                        "### Phi kiếm / Bội kiếm"
                )
                .contains(
                        "## Quan hệ nhân vật"
                )
                .contains(
                        "## Nhân quả và khế ước"
                )
                .contains(
                        "### Nhân quả chưa giải quyết"
                )
                .contains(
                        "## Hành trình nhân vật"
                );
    }

    /*
     * =====================================================
     * REALM
     * =====================================================
     */

    @Test
    @DisplayName("Template REALM có cấu trúc dành cho cảnh giới")
    void shouldProvideRealmTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.REALM
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Vị trí trong hệ thống tu hành"
                )
                .contains(
                        "## Điều kiện đột phá"
                )
                .contains(
                        "### Yêu cầu tu vi"
                )
                .contains(
                        "### Yêu cầu căn cơ"
                )
                .contains(
                        "## Thiên kiếp và khảo nghiệm"
                )
                .contains(
                        "### Thiên kiếp"
                )
                .contains(
                        "### Tâm ma"
                )
                .contains(
                        "## Thọ nguyên"
                )
                .contains(
                        "## Giới hạn và bình cảnh"
                )
                .contains(
                        "## Khác biệt với cảnh giới liền kề"
                );
    }

    /*
     * =====================================================
     * CULTIVATION PATH
     * =====================================================
     */

    @Test
    @DisplayName("Template CULTIVATION_PATH có cấu trúc dành cho con đường tu hành")
    void shouldProvideCultivationPathTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.CULTIVATION_PATH
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Nguyên lý tu hành"
                )
                .contains(
                        "## Đặc điểm con đường tu hành"
                )
                .contains(
                        "## Hệ thống cảnh giới"
                )
                .contains(
                        "## Điều kiện nhập môn"
                )
                .contains(
                        "### Căn cốt / Thể chất"
                )
                .contains(
                        "## Phương pháp tu luyện"
                )
                .contains(
                        "## Tài nguyên tu luyện"
                )
                .contains(
                        "## Năng lực đặc trưng"
                )
                .contains(
                        "## Đạo thống và truyền thừa"
                )
                .contains(
                        "## Nhân vật đại diện"
                );
    }

    /*
     * =====================================================
     * FACTION
     * =====================================================
     */

    @Test
    @DisplayName("Template FACTION có cấu trúc dành cho thế lực")
    void shouldProvideFactionTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.FACTION
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Lịch sử"
                )
                .contains(
                        "## Địa bàn và phạm vi ảnh hưởng"
                )
                .contains(
                        "### Thiên hạ / Thế giới"
                )
                .contains(
                        "### Sơn môn / Tổng bộ"
                )
                .contains(
                        "## Cơ cấu tổ chức"
                )
                .contains(
                        "## Thành viên"
                )
                .contains(
                        "## Đạo thống và truyền thừa"
                )
                .contains(
                        "## Khí vận"
                )
                .contains(
                        "### Sơn thủy khí vận"
                )
                .contains(
                        "## Công pháp và bảo vật"
                )
                .contains(
                        "## Quan hệ với các thế lực khác"
                )
                .contains(
                        "## Tình trạng hiện tại"
                );
    }

    /*
     * =====================================================
     * ITEM
     * =====================================================
     */

    @Test
    @DisplayName("Template ITEM có điều kiện sử dụng, tiêu hao và tác dụng phụ")
    void shouldProvideItemTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.ITEM
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Nguồn gốc"
                )
                .contains(
                        "## Hình dạng"
                )
                .contains(
                        "## Phân loại và phẩm cấp"
                )
                .contains(
                        "### Thuộc tính / Hệ tính"
                )
                .contains(
                        "## Điều kiện sử dụng"
                )
                .contains(
                        "### Con đường tu hành yêu cầu"
                )
                .contains(
                        "### Cảnh giới tối thiểu"
                )
                .contains(
                        "### Điều kiện nhận chủ"
                )
                .contains(
                        "### Điều kiện luyện hóa"
                )
                .contains(
                        "## Năng lực và hiệu quả"
                )
                .contains(
                        "## Cách vận dụng"
                )
                .contains(
                        "## Tiêu hao"
                )
                .contains(
                        "### Thần hồn"
                )
                .contains(
                        "## Hạn chế và tác dụng phụ"
                )
                .contains(
                        "### Phản phệ"
                )
                .contains(
                        "## Lịch sử sở hữu"
                );
    }

    /*
     * =====================================================
     * TECHNIQUE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Template TECHNIQUE phân biệt điều kiện tu luyện và điều kiện thi triển"
    )
    void shouldProvideTechniqueTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.TECHNIQUE
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Phân loại"
                )
                .contains(
                        "### Loại công pháp / Kỹ năng"
                )
                .contains(
                        "## Nguyên lý"
                )
                .contains(
                        "## Điều kiện tu luyện"
                )
                .contains(
                        "### Con đường tu hành yêu cầu"
                )
                .contains(
                        "### Cảnh giới tối thiểu"
                )
                .contains(
                        "## Phương pháp tu luyện"
                )
                .contains(
                        "### Nhập môn"
                )
                .contains(
                        "### Đại thành / Viên mãn"
                )
                .contains(
                        "## Điều kiện thi triển"
                )
                .contains(
                        "### Cảnh giới yêu cầu"
                )
                .contains(
                        "### Vũ khí / Pháp bảo yêu cầu"
                )
                .contains(
                        "### Điều kiện môi trường"
                )
                .contains(
                        "## Chiêu thức / Biến hóa"
                )
                .contains(
                        "## Uy lực và hiệu quả"
                )
                .contains(
                        "## Tiêu hao"
                )
                .contains(
                        "## Hạn chế và phản phệ"
                )
                .contains(
                        "## Người sử dụng"
                )
                .contains(
                        "## Đạo thống và truyền thừa"
                );
    }

    /*
     * =====================================================
     * LOCATION
     * =====================================================
     */

    @Test
    @DisplayName("Template LOCATION có cấu trúc dành cho địa điểm")
    void shouldProvideLocationTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.LOCATION
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Vị trí"
                )
                .contains(
                        "### Thiên hạ / Thế giới"
                )
                .contains(
                        "### Châu / Khu vực"
                )
                .contains(
                        "## Địa lý"
                )
                .contains(
                        "### Địa hình"
                )
                .contains(
                        "### Linh khí / Khí vận"
                )
                .contains(
                        "## Môi trường"
                )
                .contains(
                        "### Tài nguyên"
                )
                .contains(
                        "### Nguy hiểm"
                )
                .contains(
                        "## Thế lực kiểm soát"
                )
                .contains(
                        "## Địa điểm quan trọng"
                )
                .contains(
                        "## Giá trị chiến lược / Ý nghĩa"
                );
    }

    /*
     * =====================================================
     * WORLD
     * =====================================================
     */

    @Test
    @DisplayName("Template WORLD có cấu trúc dành cho thế giới")
    void shouldProvideWorldTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.WORLD
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Quy mô và vị trí"
                )
                .contains(
                        "## Nguồn gốc"
                )
                .contains(
                        "## Quy tắc thiên địa"
                )
                .contains(
                        "### Thiên đạo"
                )
                .contains(
                        "### Quy tắc tu hành"
                )
                .contains(
                        "## Các khu vực"
                )
                .contains(
                        "### Động thiên / Phúc địa"
                )
                .contains(
                        "## Hệ thống tu hành"
                )
                .contains(
                        "## Sinh linh và chủng tộc"
                )
                .contains(
                        "## Khí vận thế giới"
                )
                .contains(
                        "## Quan hệ với các thế giới khác"
                );
    }

    /*
     * =====================================================
     * TIMELINE EVENT
     * =====================================================
     */

    @Test
    @DisplayName("Template TIMELINE_EVENT có cấu trúc dành cho sự kiện")
    void shouldProvideTimelineEventTemplate() {
        String template =
                provider.getTemplate(
                        ArticleType.TIMELINE_EVENT
                );

        assertThat(template)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Thời gian"
                )
                .contains(
                        "## Địa điểm"
                )
                .contains(
                        "## Bối cảnh"
                )
                .contains(
                        "## Nguyên nhân"
                )
                .contains(
                        "### Nguyên nhân trực tiếp"
                )
                .contains(
                        "### Nguyên nhân sâu xa"
                )
                .contains(
                        "## Các bên tham gia"
                )
                .contains(
                        "## Diễn biến"
                )
                .contains(
                        "### Giai đoạn đầu"
                )
                .contains(
                        "### Giai đoạn cuối"
                )
                .contains(
                        "## Kết quả"
                )
                .contains(
                        "## Thiệt hại và tổn thất"
                )
                .contains(
                        "## Hậu quả"
                )
                .contains(
                        "## Ảnh hưởng"
                )
                .contains(
                        "## Sự kiện trước và sau"
                );
    }
}