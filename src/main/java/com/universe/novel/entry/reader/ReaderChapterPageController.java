package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.query.GetReactionSummaryUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.application.reader.IsChapterBookmarkedUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequestMapping("/novel")
public class ReaderChapterPageController {

    private static final Logger log =
            LoggerFactory.getLogger(ReaderChapterPageController.class);

    private final GetReaderChapterDetailUseCase
            getReaderChapterDetailUseCase;

    private final IsChapterBookmarkedUseCase
            isChapterBookmarkedUseCase;

    private final GetReactionSummaryUseCase
            getReactionSummaryUseCase;

    public ReaderChapterPageController(
            GetReaderChapterDetailUseCase getReaderChapterDetailUseCase,
            IsChapterBookmarkedUseCase isChapterBookmarkedUseCase,
            GetReactionSummaryUseCase getReactionSummaryUseCase
    ) {
        this.getReaderChapterDetailUseCase =
                Objects.requireNonNull(
                        getReaderChapterDetailUseCase,
                        "GetReaderChapterDetailUseCase không được để trống."
                );
        this.isChapterBookmarkedUseCase =
                Objects.requireNonNull(
                        isChapterBookmarkedUseCase,
                        "IsChapterBookmarkedUseCase không được để trống."
                );
        this.getReactionSummaryUseCase =
                Objects.requireNonNull(
                        getReactionSummaryUseCase,
                        "GetReactionSummaryUseCase không được để trống."
                );
    }

    @GetMapping("/chapters/{chapterSlug}")
    public String chapterPage(
            @PathVariable String chapterSlug,
            HttpServletRequest request,
            Model model
    ) {
        ReaderChapterDetailDTO chapter =
                getReaderChapterDetailUseCase.execute(
                        chapterSlug
                );

        model.addAttribute(
                "chapter",
                chapter
        );

        model.addAttribute(
                "pageTitle",
                "Chương "
                        + chapter.chapterNumber()
                        + ": "
                        + chapter.title()
        );

        UUID currentUserId = null;
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isPresent()) {
            currentUserId = identityOptional.get().userId();
        }

        boolean isBookmarked = false;
        if (currentUserId != null) {
            try {
                isBookmarked =
                        isChapterBookmarkedUseCase.execute(
                                currentUserId,
                                chapter.id()
                        );
            } catch (Exception ex) {
                log.warn(
                        "Không thể kiểm tra trạng thái bookmark cho chapterId={}: {}",
                        chapter.id(),
                        ex.getMessage()
                );
                isBookmarked = false;
            }
        }

        model.addAttribute(
                "isBookmarked",
                isBookmarked
        );

        ReactionSummary reactionSummary = null;
        try {
            ReactionTarget reactionTarget =
                    ReactionTarget.novelChapter(chapter.id());
            reactionSummary =
                    getReactionSummaryUseCase.execute(
                            reactionTarget,
                            currentUserId
                    );
        } catch (RuntimeException ex) {
            log.warn(
                    "Không thể tải reaction summary cho chapterId={}",
                    chapter.id(),
                    ex
            );
            reactionSummary = null;
        }

        model.addAttribute(
                "reactionSummary",
                reactionSummary
        );

        return "novel/chapter";
    }
}
