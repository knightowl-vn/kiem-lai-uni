package com.universe.interaction.infrastructure.eligibility;

import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link CommentTargetEligibilityPort}.
 *
 * <p>Dispatches eligibility evaluation to the owning bounded contexts:
 * <ul>
 *   <li>{@link CommentTargetType#NOVEL_CHAPTER}: delegates to {@link ReaderChapterAccessQueryPort} to ensure the chapter
 *       (and its parent volume) is currently published and readable.</li>
 *   <li>{@link CommentTargetType#WIKI_ARTICLE}: delegates to {@link WikiArticleQueryPort} to ensure the article
 *       is currently published and readable.</li>
 *   <li>{@link CommentTargetType#COMMUNITY_POST}: delegates to {@link CommunityPostQueryPort} to ensure the post exists.</li>
 * </ul>
 */
@Component
public class CommentTargetEligibilityAdapter implements CommentTargetEligibilityPort {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final CommunityPostQueryPort communityPostQueryPort;

    public CommentTargetEligibilityAdapter(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort,
            CommunityPostQueryPort communityPostQueryPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort cannot be null."
        );
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort cannot be null."
        );
        this.communityPostQueryPort = Objects.requireNonNull(
                communityPostQueryPort,
                "CommunityPostQueryPort cannot be null."
        );
    }

    @Override
    public boolean isEligible(CommentTarget target) {
        if (target == null) {
            return false;
        }

        return switch (target.type()) {
            case NOVEL_CHAPTER -> isNovelChapterEligible(target.targetId());
            case WIKI_ARTICLE -> isWikiArticleEligible(target.targetId());
            case COMMUNITY_POST -> isCommunityPostEligible(target.targetId());
        };
    }

    private boolean isNovelChapterEligible(UUID chapterId) {
        if (chapterId == null) {
            return false;
        }
        return readerChapterAccessQueryPort.findPublishedById(chapterId).isPresent();
    }

    private boolean isWikiArticleEligible(UUID articleId) {
        if (articleId == null) {
            return false;
        }
        return wikiArticleQueryPort.isPublished(articleId);
    }

    private boolean isCommunityPostEligible(UUID postId) {
        if (postId == null) {
            return false;
        }
        return communityPostQueryPort.findPublicPostById(postId).isPresent();
    }
}
