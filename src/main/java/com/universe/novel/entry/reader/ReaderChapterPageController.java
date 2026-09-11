package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
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

@Controller
@RequestMapping("/novel")
public class ReaderChapterPageController {

    private static final Logger log =
            LoggerFactory.getLogger(ReaderChapterPageController.class);

    private final GetReaderChapterDetailUseCase
            getReaderChapterDetailUseCase;

    private final IsChapterBookmarkedUseCase
            isChapterBookmarkedUseCase;

    public ReaderChapterPageController(
            GetReaderChapterDetailUseCase getReaderChapterDetailUseCase,
            IsChapterBookmarkedUseCase isChapterBookmarkedUseCase
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

        boolean isBookmarked = false;
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isPresent()) {
            try {
                isBookmarked =
                        isChapterBookmarkedUseCase.execute(
                                identityOptional.get().userId(),
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

        return "novel/chapter";
    }
}
