package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ChapterNarrationAudioNotFoundException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.AdminGenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.AdminNarrationDispatchResult;
import com.universe.novel.application.narration.AdminNarrationGenerationDispatcher;
import com.universe.novel.application.narration.AdminRegenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.GenerateChapterNarrationAudioResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import com.universe.novel.application.narration.RegenerateChapterNarrationAudioResult;
import com.universe.shared.exceptions.BaseApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller handling Admin POST actions for chapter narration audio generation, regeneration,
 * and whole-chapter asynchronous generation dispatch (MS-04.9H.6B, MS-04.9H.6B1, MS-04.9H.7D4B).
 */
@Controller
@RequestMapping("/admin/novel/chapters/{chapterId}/narration")
public class AdminNovelChapterNarrationCommandController {

    private static final Logger log = LoggerFactory.getLogger(AdminNovelChapterNarrationCommandController.class);

    private final AdminGenerateChapterNarrationAudioUseCase generateUseCase;
    private final AdminRegenerateChapterNarrationAudioUseCase regenerateUseCase;
    private final AdminNarrationGenerationDispatcher dispatcher;
    private final GetAdminChapterNarrationOverviewUseCase overviewUseCase;

    public AdminNovelChapterNarrationCommandController(
            AdminGenerateChapterNarrationAudioUseCase generateUseCase,
            AdminRegenerateChapterNarrationAudioUseCase regenerateUseCase,
            AdminNarrationGenerationDispatcher dispatcher,
            GetAdminChapterNarrationOverviewUseCase overviewUseCase
    ) {
        this.generateUseCase = Objects.requireNonNull(generateUseCase, "generateUseCase must not be null");
        this.regenerateUseCase = Objects.requireNonNull(regenerateUseCase, "regenerateUseCase must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.overviewUseCase = Objects.requireNonNull(overviewUseCase, "overviewUseCase must not be null");
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/narration/generate-all
     */
    @PostMapping("/generate-all")
    public String generateAllAudio(
            @PathVariable UUID chapterId,
            @RequestParam(name = "managedVoiceId", required = false) UUID managedVoiceId,
            RedirectAttributes redirectAttributes
    ) {
        if (managedVoiceId == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Vui lòng chọn một giọng đọc hợp lệ để tạo audio."
            );
            return buildRedirectUrl(chapterId, null);
        }

        try {
            // 1. Validate chapter & voice through existing overview/read path
            GetAdminChapterNarrationOverviewResult overview = overviewUseCase.execute(chapterId, managedVoiceId);

            // 2. Require Chapter PUBLISHED
            if (!"PUBLISHED".equalsIgnoreCase(overview.chapter().status())) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Chỉ có thể tạo giọng đọc cho chương đã xuất bản (PUBLISHED)."
                );
                return buildRedirectUrl(chapterId, managedVoiceId);
            }

            // 3. Require selected Managed Voice ACTIVE
            if (overview.selectedVoice() == null || !"ACTIVE".equalsIgnoreCase(overview.selectedVoice().status())) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Giọng đọc đang ở trạng thái không thể tạo audio."
                );
                return buildRedirectUrl(chapterId, managedVoiceId);
            }

            // 4. Delegate only to AdminNarrationGenerationDispatcher
            AdminNarrationDispatchResult dispatchResult = dispatcher.dispatch(chapterId, managedVoiceId);

            switch (dispatchResult.status()) {
                case STARTED -> redirectAttributes.addFlashAttribute(
                        "successMessage",
                        dispatchResult.message()
                );
                case ALREADY_RUNNING -> redirectAttributes.addFlashAttribute(
                        "infoMessage",
                        dispatchResult.message()
                );
                case REJECTED, FAILED -> redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        dispatchResult.message()
                );
            }

        } catch (ManagedVoiceNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy giọng đọc được chỉ định."
            );
        } catch (ManagedVoiceInvalidStateException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Giọng đọc đang ở trạng thái không thể tạo audio."
            );
        } catch (BaseApplicationException ex) {
            log.error("Operational failure dispatching whole-chapter narration generation for chapter {}, voice {}",
                    chapterId, managedVoiceId, ex);
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không thể khởi chạy tiến trình tạo giọng đọc do lỗi hệ thống."
            );
        }

        return buildRedirectUrl(chapterId, managedVoiceId);
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/narration/segments/{segmentId}/generate
     */
    @PostMapping("/segments/{segmentId}/generate")
    public String generateAudio(
            @PathVariable UUID chapterId,
            @PathVariable UUID segmentId,
            @RequestParam(name = "managedVoiceId", required = false) UUID managedVoiceId,
            RedirectAttributes redirectAttributes
    ) {
        if (managedVoiceId == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Vui lòng chọn một giọng đọc hợp lệ để tạo audio."
            );
            return buildRedirectUrl(chapterId, null);
        }

        try {
            GenerateChapterNarrationAudioResult result = generateUseCase.execute(chapterId, segmentId, managedVoiceId);
            switch (result.outcome()) {
                case GENERATED -> redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Tạo audio thuyết minh cho phân đoạn thành công."
                );
                case REUSED -> redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Phân đoạn đã có audio thuyết minh tương thích."
                );
                case STALE -> redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Phân đoạn đã có audio nhưng cần tái tạo do thay đổi cấu hình giọng đọc."
                );
            }
        } catch (ChapterNarrationSegmentNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy phân đoạn thuyết minh hoặc phân đoạn không thuộc chương này."
            );
        } catch (ChapterNarrationSegmentInvalidStateException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Phân đoạn thuyết minh không ở trạng thái hợp lệ để tạo audio."
            );
        } catch (ManagedVoiceNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy giọng đọc được chỉ định."
            );
        } catch (ManagedVoiceInvalidStateException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Giọng đọc đang ở trạng thái không thể tạo audio."
            );
        } catch (BaseApplicationException ex) {
            log.error("Operational failure generating narration audio for chapter {}, segment {}, voice {}",
                    chapterId, segmentId, managedVoiceId, ex);
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Tạo audio thuyết minh thất bại do lỗi xử lý. Vui lòng kiểm tra chi tiết lỗi trong bảng phân đoạn."
            );
        }

        return buildRedirectUrl(chapterId, managedVoiceId);
    }

    /**
     * POST /admin/novel/chapters/{chapterId}/narration/segments/{segmentId}/regenerate
     */
    @PostMapping("/segments/{segmentId}/regenerate")
    public String regenerateAudio(
            @PathVariable UUID chapterId,
            @PathVariable UUID segmentId,
            @RequestParam(name = "managedVoiceId", required = false) UUID managedVoiceId,
            RedirectAttributes redirectAttributes
    ) {
        if (managedVoiceId == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Vui lòng chọn một giọng đọc hợp lệ để tái tạo audio."
            );
            return buildRedirectUrl(chapterId, null);
        }

        try {
            RegenerateChapterNarrationAudioResult result = regenerateUseCase.execute(chapterId, segmentId, managedVoiceId);
            switch (result.outcome()) {
                case REGENERATED -> redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Tái tạo audio thuyết minh cho phân đoạn thành công."
                );
                case ALREADY_CURRENT -> redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Audio thuyết minh của phân đoạn đã ở phiên bản mới nhất."
                );
            }
        } catch (ChapterNarrationSegmentNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy phân đoạn thuyết minh hoặc phân đoạn không thuộc chương này."
            );
        } catch (ChapterNarrationSegmentInvalidStateException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Phân đoạn thuyết minh không ở trạng thái hợp lệ để tái tạo audio."
            );
        } catch (ManagedVoiceNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy giọng đọc được chỉ định."
            );
        } catch (ManagedVoiceInvalidStateException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Giọng đọc đang ở trạng thái không thể tái tạo audio."
            );
        } catch (ChapterNarrationAudioNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy audio phân đoạn hiện tại để thực hiện tái tạo."
            );
        } catch (BaseApplicationException ex) {
            log.error("Operational failure regenerating narration audio for chapter {}, segment {}, voice {}",
                    chapterId, segmentId, managedVoiceId, ex);
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Tái tạo audio thuyết minh thất bại do lỗi xử lý. Vui lòng kiểm tra chi tiết lỗi trong bảng phân đoạn."
            );
        }

        return buildRedirectUrl(chapterId, managedVoiceId);
    }

    private String buildRedirectUrl(UUID chapterId, UUID managedVoiceId) {
        if (managedVoiceId != null) {
            return "redirect:/admin/novel/chapters/" + chapterId + "/narration?voiceId=" + managedVoiceId;
        }
        return "redirect:/admin/novel/chapters/" + chapterId + "/narration";
    }
}
