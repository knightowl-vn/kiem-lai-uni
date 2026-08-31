package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionCommand;
import com.universe.novel.application.chapter.revision.RestoreChapterRevisionUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionAlreadyCurrentException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.entry.admin.form.RestoreChapterRevisionForm;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/chapters")
public class AdminNovelChapterRevisionCommandController {

    private final RestoreChapterRevisionUseCase
            restoreChapterRevisionUseCase;

    private final UserIdentityContract
            userIdentityContract;

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    public AdminNovelChapterRevisionCommandController(
            RestoreChapterRevisionUseCase restoreChapterRevisionUseCase,
            UserIdentityContract userIdentityContract,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.restoreChapterRevisionUseCase =
                Objects.requireNonNull(
                        restoreChapterRevisionUseCase,
                        "RestoreChapterRevisionUseCase không được để trống."
                );

        this.userIdentityContract =
                Objects.requireNonNull(
                        userIdentityContract,
                        "UserIdentityContract không được để trống."
                );

        this.authenticatedEmailResolver =
                Objects.requireNonNull(
                        authenticatedEmailResolver,
                        "AuthenticatedEmailResolver không được để trống."
                );
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore
     */
    @PostMapping("/{chapterId}/revisions/{revisionNumber}/restore")
    public String restoreRevision(
            @PathVariable UUID chapterId,
            @PathVariable long revisionNumber,
            @ModelAttribute("form") RestoreChapterRevisionForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (form == null || form.getExpectedAggregateVersion() == null) {
                throw new IllegalArgumentException(
                        "Expected aggregate version không được để trống."
                );
            }

            UUID actorId =
                    resolveActorId(
                            authentication
                    );

            restoreChapterRevisionUseCase.execute(
                    new RestoreChapterRevisionCommand(
                            chapterId,
                            revisionNumber,
                            form.getExpectedAggregateVersion(),
                            actorId,
                            form.getEditSummary()
                    )
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã khôi phục nội dung từ phiên bản #"
                            + revisionNumber
                            + "."
            );

            return "redirect:/admin/novel/chapters/"
                    + chapterId;

        } catch (ChapterRevisionAlreadyCurrentException
                | ChapterRevisionNotFoundException
                | ChapterNotFoundException
                | IllegalStateException
                | IllegalArgumentException
                | ConcurrentModificationException exception) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/admin/novel/chapters/"
                    + chapterId
                    + "/revisions/"
                    + revisionNumber;
        }
    }

    private UUID resolveActorId(
            Authentication authentication
    ) {
        String email =
                authenticatedEmailResolver.require(
                        authentication
                );

        UserDTO user =
                userIdentityContract
                        .findByEmail(email)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Không tìm thấy người dùng đang đăng nhập."
                                )
                        );

        return user.id();
    }
}
