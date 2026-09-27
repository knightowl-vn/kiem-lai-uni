package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Use case kiểm tra một bài viết Wiki đã được lưu bởi người dùng hay chưa.
 */
@Service
public class IsWikiArticleSavedUseCase {

    private final WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;

    public IsWikiArticleSavedUseCase(
            WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort
    ) {
        this.wikiSavedArticleRepositoryPort = wikiSavedArticleRepositoryPort;
    }

    public boolean execute(UUID userId, UUID articleId) {
        if (userId == null || articleId == null) {
            return false;
        }

        return wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(userId, articleId);
    }
}
