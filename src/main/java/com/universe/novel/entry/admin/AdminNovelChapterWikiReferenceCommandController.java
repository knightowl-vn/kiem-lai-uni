package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.chapter.reference.BindChapterWideWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.BindChapterWideWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.BindOccurrenceSpecificWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.BindOccurrenceSpecificWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.RemoveChapterWikiReferenceCommand;
import com.universe.novel.application.chapter.reference.RemoveChapterWikiReferenceUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.TargetWikiArticleNotPublishedException;
import com.universe.novel.entry.admin.form.BindChapterWideWikiReferenceForm;
import com.universe.novel.entry.admin.form.BindOccurrenceSpecificWikiReferenceForm;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller xử lý các thao tác ghi (POST) liên kết Wiki cho Chapter (MS-02.8.1 Step 6B).
 */
@Controller
@RequestMapping("/admin/novel/chapters/{chapterId}/wiki-references")
public class AdminNovelChapterWikiReferenceCommandController {

    private final BindChapterWideWikiReferenceUseCase bindChapterWideWikiReferenceUseCase;
    private final BindOccurrenceSpecificWikiReferenceUseCase bindOccurrenceSpecificWikiReferenceUseCase;
    private final RemoveChapterWikiReferenceUseCase removeChapterWikiReferenceUseCase;
    private final UserIdentityContract userIdentityContract;
    private final AuthenticatedEmailResolver authenticatedEmailResolver;

    public AdminNovelChapterWikiReferenceCommandController(
            BindChapterWideWikiReferenceUseCase bindChapterWideWikiReferenceUseCase,
            BindOccurrenceSpecificWikiReferenceUseCase bindOccurrenceSpecificWikiReferenceUseCase,
            RemoveChapterWikiReferenceUseCase removeChapterWikiReferenceUseCase,
            UserIdentityContract userIdentityContract,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.bindChapterWideWikiReferenceUseCase = Objects.requireNonNull(
                bindChapterWideWikiReferenceUseCase,
                "BindChapterWideWikiReferenceUseCase không được để trống."
        );
        this.bindOccurrenceSpecificWikiReferenceUseCase = Objects.requireNonNull(
                bindOccurrenceSpecificWikiReferenceUseCase,
                "BindOccurrenceSpecificWikiReferenceUseCase không được để trống."
        );
        this.removeChapterWikiReferenceUseCase = Objects.requireNonNull(
                removeChapterWikiReferenceUseCase,
                "RemoveChapterWikiReferenceUseCase không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract không được để trống."
        );
        this.authenticatedEmailResolver = Objects.requireNonNull(
                authenticatedEmailResolver,
                "AuthenticatedEmailResolver không được để trống."
        );
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/wiki-references/chapter-wide
     * Gán hoặc cập nhật liên kết Wiki toàn chương.
     */
    @PostMapping("/chapter-wide")
    public String bindChapterWide(
            @PathVariable UUID chapterId,
            @ModelAttribute("form") BindChapterWideWikiReferenceForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        UUID actorId = resolveActorId(authentication);

        try {
            if (form == null || form.getTerm() == null || form.getTerm().isBlank()) {
                throw new IllegalArgumentException("Thuật ngữ không được để trống.");
            }
            if (form.getWikiArticleId() == null) {
                throw new IllegalArgumentException("Wiki Article ID không được để trống.");
            }

            bindChapterWideWikiReferenceUseCase.execute(
                    new BindChapterWideWikiReferenceCommand(
                            chapterId,
                            form.getTerm(),
                            form.getWikiArticleId(),
                            actorId
                    )
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã liên kết thuật ngữ \"" + form.getTerm().trim() + "\" với bài viết Wiki trên toàn chương."
            );
        } catch (ChapterNotFoundException
                | TargetWikiArticleNotPublishedException
                | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/novel/chapters/" + chapterId + "/wiki-references";
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/wiki-references/occurrence
     * Gán hoặc cập nhật liên kết Wiki theo vị trí xuất hiện cụ thể.
     */
    @PostMapping("/occurrence")
    public String bindOccurrenceSpecific(
            @PathVariable UUID chapterId,
            @ModelAttribute("form") BindOccurrenceSpecificWikiReferenceForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        UUID actorId = resolveActorId(authentication);

        try {
            if (form == null || form.getTerm() == null || form.getTerm().isBlank()) {
                throw new IllegalArgumentException("Thuật ngữ không được để trống.");
            }
            if (form.getOccurrenceIndex() == null || form.getOccurrenceIndex() < 1) {
                throw new IllegalArgumentException("Chỉ số xuất hiện phải lớn hơn hoặc bằng 1.");
            }
            if (form.getWikiArticleId() == null) {
                throw new IllegalArgumentException("Wiki Article ID không được để trống.");
            }

            bindOccurrenceSpecificWikiReferenceUseCase.execute(
                    new BindOccurrenceSpecificWikiReferenceCommand(
                            chapterId,
                            form.getTerm(),
                            form.getOccurrenceIndex(),
                            form.getContextSnippet(),
                            form.getWikiArticleId(),
                            actorId
                    )
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã liên kết thuật ngữ \"" + form.getTerm().trim() + "\" (vị trí #" + form.getOccurrenceIndex() + ") với bài viết Wiki."
            );
        } catch (ChapterNotFoundException
                | TargetWikiArticleNotPublishedException
                | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/novel/chapters/" + chapterId + "/wiki-references";
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/wiki-references/{referenceId}/delete
     * Xóa một liên kết Wiki của chapter.
     */
    @PostMapping("/{referenceId}/delete")
    public String removeReference(
            @PathVariable UUID chapterId,
            @PathVariable UUID referenceId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        UUID actorId = resolveActorId(authentication);

        try {
            boolean removed = removeChapterWikiReferenceUseCase.execute(
                    new RemoveChapterWikiReferenceCommand(
                            referenceId,
                            chapterId,
                            actorId
                    )
            );

            if (removed) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Đã xóa liên kết Wiki thành công."
                );
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Liên kết Wiki không tồn tại hoặc đã bị xóa trước đó."
                );
            }
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/novel/chapters/" + chapterId + "/wiki-references";
    }

    private UUID resolveActorId(Authentication authentication) {
        String email = authenticatedEmailResolver.require(authentication);
        UserDTO user = userIdentityContract.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy người dùng đang đăng nhập."));
        return user.id();
    }
}
