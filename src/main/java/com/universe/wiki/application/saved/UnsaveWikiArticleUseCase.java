package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện bỏ lưu bài viết Wiki của người dùng đã xác thực.
 *
 * Đảm bảo:
 * 1. Hoàn toàn có tính lũy suy (idempotent): nếu chưa lưu hoặc đã xóa trước đó vẫn trả về thành công;
 * 2. Không yêu cầu kiểm tra trạng thái PUBLISHED của bài viết (bài viết đã chuyển sang DRAFT hoặc
 *    ARCHIVED vẫn phải cho phép người dùng bỏ lưu khỏi danh sách cá nhân).
 */
@Service
public class UnsaveWikiArticleUseCase {

    private final WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;

    public UnsaveWikiArticleUseCase(
            WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort
    ) {
        this.wikiSavedArticleRepositoryPort = Objects.requireNonNull(
                wikiSavedArticleRepositoryPort,
                "WikiSavedArticleRepositoryPort không được để trống."
        );
    }

    public void execute(UnsaveWikiArticleCommand command) {
        Objects.requireNonNull(command, "UnsaveWikiArticleCommand không được để trống.");

        UUID userId = command.userId();
        UUID articleId = command.articleId();

        // Xóa bản ghi trực tiếp, không kiểm tra trạng thái bài viết
        wikiSavedArticleRepositoryPort.deleteByUserIdAndArticleId(userId, articleId);
    }
}
