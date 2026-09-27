package com.universe.wiki.application.saved;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.DuplicateWikiSavedArticleException;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import com.universe.wiki.domain.saved.UserSavedWikiArticle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện lưu bài viết Wiki cho người dùng đã xác thực.
 *
 * Đảm bảo:
 * 1. Bài viết Wiki phải đang ở trạng thái công khai PUBLISHED (sử dụng truy vấn nhẹ, không tải nội dung lớn);
 * 2. Thao tác có tính lũy suy (idempotent): nếu đã lưu trước đó thì trả về thành công;
 * 3. Bảo vệ tranh chấp ghi đồng thời (concurrent race) thông qua cơ chế bắt ngoại lệ
 *    DuplicateWikiSavedArticleException từ ràng buộc UNIQUE ở cơ sở dữ liệu;
 * 4. Sử dụng ClockPort và IdGeneratorPort dùng chung cho thời gian và định danh.
 */
@Service
public class SaveWikiArticleUseCase {

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SaveWikiArticleUseCase(
            WikiArticleQueryPort wikiArticleQueryPort,
            WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
        this.wikiSavedArticleRepositoryPort = Objects.requireNonNull(
                wikiSavedArticleRepositoryPort,
                "WikiSavedArticleRepositoryPort không được để trống."
        );
        this.idGeneratorPort = Objects.requireNonNull(
                idGeneratorPort,
                "IdGeneratorPort không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    public void execute(SaveWikiArticleCommand command) {
        Objects.requireNonNull(command, "SaveWikiArticleCommand không được để trống.");

        UUID userId = command.userId();
        UUID articleId = command.articleId();

        // 1. Fast-path / Idempotency check: nếu đã lưu trước đó -> thành công ngay lập tức
        if (wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(userId, articleId)) {
            return;
        }

        // 2. Chỉ với bài viết mới: kiểm tra bài viết có đang ở trạng thái PUBLISHED hay không qua query tối ưu
        if (!wikiArticleQueryPort.isPublished(articleId)) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }

        // 3. Khởi tạo aggregate qua IdGeneratorPort và ClockPort
        UUID savedId = idGeneratorPort.generate();
        Instant now = clockPort.now();
        UserSavedWikiArticle savedArticle = UserSavedWikiArticle.create(
                savedId,
                userId,
                articleId,
                now
        );

        // 4. Lưu vào database, bắt duplicate race condition để đảm bảo idempotent success
        try {
            wikiSavedArticleRepositoryPort.save(savedArticle);
        } catch (DuplicateWikiSavedArticleException ex) {
            // Concurrent race: bản ghi đã được lưu đồng thời bởi một request khác -> idempotent success
        }
    }
}
