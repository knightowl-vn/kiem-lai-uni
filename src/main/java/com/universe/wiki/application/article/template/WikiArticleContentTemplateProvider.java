package com.universe.wiki.application.article.template;

import com.universe.wiki.domain.article.ArticleType;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class WikiArticleContentTemplateProvider {

    public String getTemplate(
            ArticleType articleType
    ) {
        Objects.requireNonNull(
                articleType,
                "Loại bài Wiki không được để trống."
        );

        return switch (articleType) {

            case CHARACTER ->
                    characterTemplate();

            case REALM ->
                    realmTemplate();

            case CULTIVATION_PATH ->
                    cultivationPathTemplate();

            case FACTION ->
                    factionTemplate();

            case ITEM ->
                    itemTemplate();

            case TECHNIQUE ->
                    techniqueTemplate();

            case LOCATION ->
                    locationTemplate();

            case WORLD ->
                    worldTemplate();

            case TIMELINE_EVENT ->
                    timelineEventTemplate();
        };
    }

    /*
     * =====================================================
     * CHARACTER
     * =====================================================
     */

    private String characterTemplate() {
        return """
                ## Tổng quan

                ## Xuất thân và bối cảnh

                ### Gia thế

                ### Quê quán

                ### Hoàn cảnh ban đầu

                ### Xuất thân đặc biệt / Tiền kiếp

                ## Ngoại hình

                ## Tính cách và đạo tâm

                ### Tính cách

                ### Quan niệm sống

                ### Đạo tâm

                ## Thân phận và thế lực

                ### Thân phận

                ### Chức vị

                ### Tông môn / Thế lực

                ## Tu hành và năng lực

                ### Con đường tu hành

                ### Cảnh giới

                ### Công pháp

                ### Thuật pháp và thần thông

                ### Kiếm thuật / Võ học

                ### Năng lực đặc thù

                ## Vũ khí và bảo vật

                ### Bản mệnh vật

                ### Phi kiếm / Bội kiếm

                ### Pháp bảo

                ### Vật phẩm đặc biệt

                ## Quan hệ nhân vật

                ### Gia đình

                ### Sư môn

                ### Đạo lữ

                ### Bằng hữu

                ### Đệ tử

                ### Đối thủ / Kẻ địch

                ## Nhân quả và khế ước

                ### Ân nghĩa

                ### Thù oán

                ### Lời hứa / Khế ước

                ### Nhân quả chưa giải quyết

                ## Hành trình nhân vật

                ## Chiến tích và thành tựu

                ## Danh xưng và biệt danh

                ## Trích dẫn tiêu biểu

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * REALM
     * =====================================================
     */

    private String realmTemplate() {
        return """
                ## Tổng quan

                ## Vị trí trong hệ thống tu hành

                ## Điều kiện đột phá

                ### Yêu cầu tu vi

                ### Yêu cầu căn cơ

                ### Cơ duyên / Tài nguyên

                ### Điều kiện đặc biệt

                ## Thiên kiếp và khảo nghiệm

                ### Thiên kiếp

                ### Tâm ma

                ### Khảo nghiệm đặc thù

                ## Đặc điểm

                ## Năng lực

                ## Thọ nguyên

                ## Các tầng / Giai đoạn

                ## Giới hạn và bình cảnh

                ## Khác biệt với cảnh giới liền kề

                ## Nhân vật tiêu biểu

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * CULTIVATION PATH
     * =====================================================
     */

    private String cultivationPathTemplate() {
        return """
                ## Tổng quan

                ## Nguồn gốc

                ## Nguyên lý tu hành

                ## Đặc điểm con đường tu hành

                ## Hệ thống cảnh giới

                ## Điều kiện nhập môn

                ### Căn cốt / Thể chất

                ### Tư chất

                ### Điều kiện đặc biệt

                ## Phương pháp tu luyện

                ## Tài nguyên tu luyện

                ## Năng lực đặc trưng

                ## Ưu điểm

                ## Hạn chế và bình cảnh

                ## Thiên kiếp / Khảo nghiệm

                ## Đạo thống và truyền thừa

                ## Nhân vật đại diện

                ## Quan hệ với các con đường tu hành khác

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * FACTION
     * =====================================================
     */

    private String factionTemplate() {
        return """
                ## Tổng quan

                ## Lịch sử

                ## Địa bàn và phạm vi ảnh hưởng

                ### Thiên hạ / Thế giới

                ### Châu / Khu vực

                ### Sơn môn / Tổng bộ

                ### Địa điểm trực thuộc

                ## Cơ cấu tổ chức

                ### Người lãnh đạo

                ### Các chức vị

                ### Phân nhánh / Chi mạch

                ## Thành viên

                ### Nhân vật chủ chốt

                ### Thành viên nổi bật

                ## Đạo thống và truyền thừa

                ## Khí vận

                ### Nguồn khí vận

                ### Sơn thủy khí vận

                ### Tình trạng khí vận

                ## Công pháp và bảo vật

                ### Công pháp truyền thừa

                ### Pháp bảo / Trấn môn chi bảo

                ## Quan hệ với các thế lực khác

                ### Đồng minh

                ### Đối địch

                ### Phụ thuộc / Liên minh

                ## Sự kiện quan trọng

                ## Tình trạng hiện tại

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * ITEM
     * =====================================================
     */

    private String itemTemplate() {
        return """
                ## Tổng quan

                ## Nguồn gốc

                ## Hình dạng

                ## Phân loại và phẩm cấp

                ### Loại vật phẩm

                ### Phẩm cấp

                ### Thuộc tính / Hệ tính

                ## Điều kiện sử dụng

                ### Con đường tu hành yêu cầu

                ### Cảnh giới tối thiểu

                ### Căn cốt / Huyết mạch / Thể chất

                ### Điều kiện nhận chủ

                ### Điều kiện luyện hóa

                ### Điều kiện đặc biệt

                ## Đặc tính

                ## Năng lực và hiệu quả

                ## Cách vận dụng

                ## Tiêu hao

                ### Linh khí / Chân khí / Khí huyết

                ### Thần hồn

                ### Tài nguyên khác

                ## Hạn chế và tác dụng phụ

                ### Giới hạn sử dụng

                ### Phản phệ

                ### Tác dụng phụ

                ## Chủ nhân

                ### Chủ nhân hiện tại

                ### Chủ nhân trước đây

                ## Lịch sử sở hữu

                ## Sự kiện liên quan

                ## Tình trạng hiện tại

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * TECHNIQUE
     * =====================================================
     */

    private String techniqueTemplate() {
        return """
                ## Tổng quan

                ## Nguồn gốc

                ## Phân loại

                ### Loại công pháp / Kỹ năng

                ### Thuộc tính

                ### Phẩm cấp / Tầng thứ

                ## Nguyên lý

                ## Điều kiện tu luyện

                ### Con đường tu hành yêu cầu

                ### Cảnh giới tối thiểu

                ### Căn cốt / Thể chất / Huyết mạch

                ### Tư chất

                ### Điều kiện đặc biệt

                ## Phương pháp tu luyện

                ### Nhập môn

                ### Tiến triển

                ### Đại thành / Viên mãn

                ## Điều kiện thi triển

                ### Cảnh giới yêu cầu

                ### Linh khí / Chân khí / Khí huyết

                ### Thần hồn

                ### Vũ khí / Pháp bảo yêu cầu

                ### Điều kiện môi trường

                ### Điều kiện đặc biệt

                ## Chiêu thức / Biến hóa

                ## Uy lực và hiệu quả

                ## Tiêu hao

                ## Hạn chế và phản phệ

                ### Hạn chế

                ### Phản phệ

                ### Điểm yếu

                ## Người sử dụng

                ## Đạo thống và truyền thừa

                ## Quan hệ với các công pháp khác

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * LOCATION
     * =====================================================
     */

    private String locationTemplate() {
        return """
                ## Tổng quan

                ## Vị trí

                ### Thiên hạ / Thế giới

                ### Châu / Khu vực

                ### Vị trí cụ thể

                ## Địa lý

                ### Địa hình

                ### Khí hậu

                ### Linh khí / Khí vận

                ## Môi trường

                ### Sinh linh

                ### Tài nguyên

                ### Nguy hiểm

                ## Lịch sử

                ## Thế lực kiểm soát

                ## Địa điểm quan trọng

                ## Nhân vật liên quan

                ## Sự kiện liên quan

                ## Giá trị chiến lược / Ý nghĩa

                ## Tình trạng hiện tại

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * WORLD
     * =====================================================
     */

    private String worldTemplate() {
        return """
                ## Tổng quan

                ## Quy mô và vị trí

                ## Nguồn gốc

                ## Quy tắc thiên địa

                ### Thiên đạo

                ### Linh khí

                ### Quy tắc tu hành

                ### Hạn chế đặc thù

                ## Địa lý

                ## Các khu vực

                ### Châu / Đại lục

                ### Động thiên / Phúc địa

                ### Khu vực đặc biệt

                ## Hệ thống tu hành

                ## Sinh linh và chủng tộc

                ## Thế lực

                ## Lịch sử

                ### Thời kỳ cổ đại

                ### Các thời kỳ quan trọng

                ### Hiện tại

                ## Khí vận thế giới

                ## Quan hệ với các thế giới khác

                ## Sự kiện quan trọng

                ## Tình trạng hiện tại

                ## Thông tin bên lề
                """;
    }

    /*
     * =====================================================
     * TIMELINE EVENT
     * =====================================================
     */

    private String timelineEventTemplate() {
        return """
                ## Tổng quan

                ## Thời gian

                ## Địa điểm

                ## Bối cảnh

                ## Nguyên nhân

                ### Nguyên nhân trực tiếp

                ### Nguyên nhân sâu xa

                ## Các bên tham gia

                ### Nhân vật

                ### Thế lực

                ## Diễn biến

                ### Giai đoạn đầu

                ### Giai đoạn giữa

                ### Giai đoạn cuối

                ## Kết quả

                ## Thiệt hại và tổn thất

                ## Hậu quả

                ## Ảnh hưởng

                ### Đối với nhân vật

                ### Đối với thế lực

                ### Đối với thế giới / Cục diện

                ## Nhân vật liên quan

                ## Thế lực liên quan

                ## Địa điểm liên quan

                ## Vật phẩm / Công pháp liên quan

                ## Sự kiện trước và sau

                ## Thông tin bên lề
                """;
    }
}