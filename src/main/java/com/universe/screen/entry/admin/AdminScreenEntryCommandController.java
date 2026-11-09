package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.domain.ScreenTimelineMode;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;
import com.universe.screen.entry.admin.form.CreateScreenEntryForm;
import com.universe.screen.entry.admin.form.EditScreenEntryForm;
import com.universe.shared.exceptions.BaseApplicationException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles/{titleId}/entries")
public class AdminScreenEntryCommandController {

    private final CreateScreenEntryUseCase createScreenEntryUseCase;
    private final UpdateScreenEntryUseCase updateScreenEntryUseCase;
    private final PublishScreenEntryUseCase publishScreenEntryUseCase;
    private final ArchiveScreenEntryUseCase archiveScreenEntryUseCase;
    private final DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    public AdminScreenEntryCommandController(
            CreateScreenEntryUseCase createScreenEntryUseCase,
            UpdateScreenEntryUseCase updateScreenEntryUseCase,
            PublishScreenEntryUseCase publishScreenEntryUseCase,
            ArchiveScreenEntryUseCase archiveScreenEntryUseCase,
            DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase
    ) {
        this.createScreenEntryUseCase = Objects.requireNonNull(createScreenEntryUseCase, "CreateScreenEntryUseCase cannot be null.");
        this.updateScreenEntryUseCase = Objects.requireNonNull(updateScreenEntryUseCase, "UpdateScreenEntryUseCase cannot be null.");
        this.publishScreenEntryUseCase = Objects.requireNonNull(publishScreenEntryUseCase, "PublishScreenEntryUseCase cannot be null.");
        this.archiveScreenEntryUseCase = Objects.requireNonNull(archiveScreenEntryUseCase, "ArchiveScreenEntryUseCase cannot be null.");
        this.detachScreenEntryVideoUseCase = Objects.requireNonNull(detachScreenEntryVideoUseCase, "DetachScreenEntryVideoUseCase cannot be null.");
    }

    @PostMapping
    public String createEntry(
            @PathVariable UUID titleId,
            @ModelAttribute("form") CreateScreenEntryForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", resolveBindingErrorMessage(bindingResult));
            redirectAttributes.addFlashAttribute("form", form);
            redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "form", bindingResult);
            return "redirect:/admin/screen/titles/" + titleId + "/entries/new";
        }

        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateEntryForm(form.getTitle(), form.getSlug(), form.getType());

            ScreenEntryDTO created = createScreenEntryUseCase.execute(
                    new CreateScreenEntryCommand(
                            titleId,
                            form.getSlug(),
                            form.getType(),
                            form.getNumber(),
                            form.getTitle(),
                            form.getSynopsis(),
                            form.getDurationSeconds()
                    )
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Screen Entry \"" + created.title() + "\".");
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + created.id();

        } catch (ScreenEntrySlugAlreadyExistsException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:/admin/screen/titles/" + titleId + "/entries/new";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    @PostMapping("/{entryId}/update")
    public String updateEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @ModelAttribute("form") EditScreenEntryForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", resolveBindingErrorMessage(bindingResult));
            redirectAttributes.addFlashAttribute("form", form);
            redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "form", bindingResult);
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit";
        }

        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateEntryForm(form.getTitle(), form.getSlug(), form.getType());

            boolean updateTimeline = form.hasTimelineUpdateIntent();
            UpdateScreenEntryCommand command;
            if (updateTimeline) {
                ParsedEntryTimeline parsedTimeline = parseAndValidateEntryTimeline(form);
                command = new UpdateScreenEntryCommand(
                        titleId,
                        entryId,
                        form.getSlug(),
                        form.getType(),
                        form.getNumber(),
                        form.getTitle(),
                        form.getSynopsis(),
                        form.getDurationSeconds(),
                        parsedTimeline.introMode(),
                        parsedTimeline.introStartSeconds(),
                        parsedTimeline.introEndSeconds(),
                        parsedTimeline.outroMode(),
                        parsedTimeline.outroStartSeconds()
                );
            } else {
                command = new UpdateScreenEntryCommand(
                        titleId,
                        entryId,
                        form.getSlug(),
                        form.getType(),
                        form.getNumber(),
                        form.getTitle(),
                        form.getSynopsis(),
                        form.getDurationSeconds()
                );
            }

            ScreenEntryDTO updated = updateScreenEntryUseCase.execute(command);

            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật Screen Entry \"" + updated.title() + "\".");
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;

        } catch (ScreenEntrySlugAlreadyExistsException
                 | IllegalArgumentException
                 | IllegalStateException
                 | InvalidScreenTitleTotalEpisodeCountException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit";
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    @PostMapping("/{entryId}/publish")
    public String publishEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenEntryDTO published = publishScreenEntryUseCase.execute(titleId, entryId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xuất bản Screen Entry \"" + published.title() + "\".");
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException | InvalidScreenTitleTotalEpisodeCountException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
    }

    @PostMapping("/{entryId}/archive")
    public String archiveEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenEntryDTO archived = archiveScreenEntryUseCase.execute(titleId, entryId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ Screen Entry \"" + archived.title() + "\".");
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException | InvalidScreenTitleTotalEpisodeCountException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
    }

    @PostMapping(
            value = "/{entryId}/video/detach",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE}
    )
    public String detachVideo(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestParam(name = "expectedVideoMediaAssetId", required = false) UUID expectedVideoMediaAssetId,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVideoMediaAssetId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy mã video hiện tại để xác thực thao tác gỡ bỏ.");
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
        }

        try {
            detachScreenEntryVideoUseCase.execute(titleId, entryId, expectedVideoMediaAssetId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã gỡ bỏ video khỏi Screen Entry thành công. Video gốc vẫn được bảo toàn an toàn trên hệ thống Media.");
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (BaseApplicationException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
    }

    private void validateEntryForm(String title, String slug, com.universe.screen.domain.ScreenEntryType type) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề Screen entry không được để trống.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Slug URL không được để trống.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Vui lòng chọn loại nội dung (Type: EPISODE, FEATURE, SPECIAL, TRAILER).");
        }
    }

    private static ParsedEntryTimeline parseAndValidateEntryTimeline(EditScreenEntryForm form) {
        if (form.getIntroMode() == null || form.getOutroMode() == null) {
            throw new IllegalArgumentException("Vui lòng chọn chế độ mốc thời gian cho cả Intro và Outro.");
        }
        ScreenTimelineMode introMode = form.getIntroMode();
        ScreenTimelineMode outroMode = form.getOutroMode();

        Long introStartSeconds = null;
        Long introEndSeconds = null;
        if (introMode == ScreenTimelineMode.OVERRIDE) {
            introStartSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(form.getIntroStart());
            introEndSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(form.getIntroEnd());
            if (introStartSeconds == null || introEndSeconds == null) {
                throw new IllegalArgumentException(ScreenTimelineTimestampFormat.INCOMPLETE_INTRO_PAIR_MESSAGE);
            }
            ScreenTimelineTimestampFormat.validateIntroPairAndOrder(introStartSeconds, introEndSeconds);
        } else {
            form.setIntroStart(null);
            form.setIntroEnd(null);
        }

        Long outroStartSeconds = null;
        if (outroMode == ScreenTimelineMode.OVERRIDE) {
            outroStartSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(form.getOutroStart());
            if (outroStartSeconds == null) {
                throw new IllegalArgumentException(ScreenTimelineTimestampFormat.MISSING_OUTRO_OVERRIDE_MESSAGE);
            }
        } else {
            form.setOutroStart(null);
        }

        ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(introEndSeconds, outroStartSeconds);
        return new ParsedEntryTimeline(introMode, introStartSeconds, introEndSeconds, outroMode, outroStartSeconds);
    }

    private record ParsedEntryTimeline(
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
    ) {
    }

    private String resolveBindingErrorMessage(BindingResult bindingResult) {
        if (bindingResult.hasFieldErrors("type")) {
            return "Loại tập phim/video không hợp lệ.";
        }
        if (bindingResult.hasFieldErrors("number")) {
            return "Số tập/thứ tự không hợp lệ.";
        }
        if (bindingResult.hasFieldErrors("durationSeconds")) {
            return "Thời lượng không hợp lệ.";
        }
        if (bindingResult.hasFieldErrors("introMode") || bindingResult.hasFieldErrors("outroMode")) {
            return "Chế độ mốc thời gian Intro/Outro không hợp lệ.";
        }
        var error = bindingResult.getAllErrors().stream().findFirst();
        if (error.isPresent() && error.get().getDefaultMessage() != null && !error.get().getDefaultMessage().isBlank()) {
            return error.get().getDefaultMessage();
        }
        return "Dữ liệu biểu mẫu không hợp lệ.";
    }
}
