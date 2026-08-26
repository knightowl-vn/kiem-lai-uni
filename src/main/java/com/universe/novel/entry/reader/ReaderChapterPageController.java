package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.application.reader.IsChapterBookmarkedUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
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

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    private final UserIdentityContract
            userIdentityContract;

    public ReaderChapterPageController(
            GetReaderChapterDetailUseCase getReaderChapterDetailUseCase,
            IsChapterBookmarkedUseCase isChapterBookmarkedUseCase,
            AuthenticatedEmailResolver authenticatedEmailResolver,
            UserIdentityContract userIdentityContract
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
        this.authenticatedEmailResolver =
                Objects.requireNonNull(
                        authenticatedEmailResolver,
                        "AuthenticatedEmailResolver không được để trống."
                );
        this.userIdentityContract =
                Objects.requireNonNull(
                        userIdentityContract,
                        "UserIdentityContract không được để trống."
                );
    }

    @GetMapping("/chapters/{chapterSlug}")
    public String chapterPage(
            @PathVariable String chapterSlug,
            Authentication authentication,
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
        Optional<String> emailOpt =
                authenticatedEmailResolver.resolve(authentication);
        if (emailOpt.isPresent()) {
            try {
                Optional<UserDTO> userOpt =
                        userIdentityContract.findByEmail(emailOpt.get());
                if (userOpt.isPresent()) {
                    isBookmarked =
                            isChapterBookmarkedUseCase.execute(
                                    userOpt.get().id(),
                                    chapter.id()
                            );
                }
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
