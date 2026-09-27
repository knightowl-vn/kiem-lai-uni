package com.universe.interaction.entry.web.personal;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.query.ListUserAuthoredCommentsUseCase;
import com.universe.interaction.application.query.UserCommentContextFilter;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller handling authenticated user's personal authored comments review page.
 *
 * <p>Security & Performance Rules:
 * <ol>
 *   <li>Endpoint requires authenticated user identity;</li>
 *   <li>Author user ID is extracted strictly from {@link AuthenticatedRequestIdentityAccessor}, never from client parameters;</li>
 *   <li>Anonymous requests are redirected to /login;</li>
 *   <li>Target metadata resolution executes at most 1 batch query to Novel and/or 1 batch query to Wiki;</li>
 *   <li>Unused target context batch queries are completely skipped;</li>
 *   <li>Supports filtering by context (ALL, NOVEL, WIKI) and zero-based pagination (page size 20).</li>
 * </ol>
 */
@Controller
@RequestMapping("/comments")
public class UserAuthoredCommentController {

    private final ListUserAuthoredCommentsUseCase listUserAuthoredCommentsUseCase;
    private final ChapterListQueryPort chapterListQueryPort;
    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final ArticleTypePathMapper articleTypePathMapper;

    public UserAuthoredCommentController(
            ListUserAuthoredCommentsUseCase listUserAuthoredCommentsUseCase,
            ChapterListQueryPort chapterListQueryPort,
            WikiArticleQueryPort wikiArticleQueryPort,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.listUserAuthoredCommentsUseCase = Objects.requireNonNull(
                listUserAuthoredCommentsUseCase,
                "ListUserAuthoredCommentsUseCase cannot be null."
        );
        this.chapterListQueryPort = Objects.requireNonNull(
                chapterListQueryPort,
                "ChapterListQueryPort cannot be null."
        );
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort cannot be null."
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper cannot be null."
        );
    }

    @GetMapping("/my")
    public String myCommentsPage(
            @RequestParam(name = "context", defaultValue = "all") String contextParam,
            @RequestParam(name = "page", defaultValue = "0") int page,
            HttpServletRequest request,
            Model model
    ) {
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return "redirect:/login";
        }

        UserCommentContextFilter filter = UserCommentContextFilter.fromQueryParam(contextParam);
        int normalizedPage = Math.max(0, page);

        AuthoredCommentPageDTO pageDto = listUserAuthoredCommentsUseCase.execute(
                identityOptional.get().userId(),
                filter,
                normalizedPage,
                ListUserAuthoredCommentsUseCase.DEFAULT_PAGE_SIZE
        );

        // 1. Collect unique target IDs by type
        Set<UUID> novelChapterIds = pageDto.items().stream()
                .filter(item -> item.targetType() == CommentTargetType.NOVEL_CHAPTER)
                .map(AuthoredCommentItemDTO::targetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<UUID> wikiArticleIds = pageDto.items().stream()
                .filter(item -> item.targetType() == CommentTargetType.WIKI_ARTICLE)
                .map(AuthoredCommentItemDTO::targetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. Batch resolve context (at most 1 query per context, skipped if empty)
        Map<UUID, ChapterListItemDTO> liveChaptersMap = novelChapterIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(chapterListQueryPort.findListItemsByIds(novelChapterIds), Map.of());

        Map<UUID, WikiArticleListItemDTO> liveArticlesMap = wikiArticleIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(wikiArticleQueryPort.findListItemsByIds(wikiArticleIds), Map.of());

        // 3. Compose view model
        UserAuthoredCommentPageViewModel commentsPage = UserAuthoredCommentPageViewModel.from(
                pageDto,
                filter,
                liveChaptersMap,
                liveArticlesMap,
                articleTypePathMapper
        );

        model.addAttribute("commentsPage", commentsPage);
        model.addAttribute("activeFilter", filter.queryParam());
        model.addAttribute("pageTitle", "Bình luận của tôi");

        return "interaction/personal/comments";
    }
}
