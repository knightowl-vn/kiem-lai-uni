package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.InvalidScreenTimelineConfigurationException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenImageMediaPort;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.CreateScreenTitleUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenMetadataValidator;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;
import com.universe.screen.entry.admin.form.CreateScreenTitleForm;
import com.universe.screen.entry.admin.form.EditScreenTitleForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles")
public class AdminScreenTitleCommandController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminScreenTitleCommandController.class);

    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final CreateScreenTitleUseCase createScreenTitleUseCase;
    private final UpdateScreenTitleUseCase updateScreenTitleUseCase;
    private final PublishScreenTitleUseCase publishScreenTitleUseCase;
    private final ArchiveScreenTitleUseCase archiveScreenTitleUseCase;
    private final GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;
    private final ScreenImageMediaPort screenImageMediaPort;

    @Autowired
    public AdminScreenTitleCommandController(
            CreateScreenTitleUseCase createScreenTitleUseCase,
            UpdateScreenTitleUseCase updateScreenTitleUseCase,
            PublishScreenTitleUseCase publishScreenTitleUseCase,
            ArchiveScreenTitleUseCase archiveScreenTitleUseCase,
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase,
            ScreenImageMediaPort screenImageMediaPort
    ) {
        this.createScreenTitleUseCase = Objects.requireNonNull(createScreenTitleUseCase, "CreateScreenTitleUseCase cannot be null.");
        this.updateScreenTitleUseCase = Objects.requireNonNull(updateScreenTitleUseCase, "UpdateScreenTitleUseCase cannot be null.");
        this.publishScreenTitleUseCase = Objects.requireNonNull(publishScreenTitleUseCase, "PublishScreenTitleUseCase cannot be null.");
        this.archiveScreenTitleUseCase = Objects.requireNonNull(archiveScreenTitleUseCase, "ArchiveScreenTitleUseCase cannot be null.");
        this.getScreenTitleDetailUseCase = getScreenTitleDetailUseCase;
        this.screenImageMediaPort = screenImageMediaPort;
    }

    public AdminScreenTitleCommandController(
            CreateScreenTitleUseCase createScreenTitleUseCase,
            UpdateScreenTitleUseCase updateScreenTitleUseCase,
            PublishScreenTitleUseCase publishScreenTitleUseCase,
            ArchiveScreenTitleUseCase archiveScreenTitleUseCase
    ) {
        this(createScreenTitleUseCase, updateScreenTitleUseCase, publishScreenTitleUseCase, archiveScreenTitleUseCase, null, null);
    }

    @PostMapping
    public String createTitle(
            @ModelAttribute("form") CreateScreenTitleForm form,
            RedirectAttributes redirectAttributes
    ) {
        UUID newlyUploadedPosterId = null;
        UUID newlyUploadedBackdropId = null;
        ScreenTitleDTO created;

        try {
            if (form == null) {
                throw new AdminScreenValidationException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateTitleForm(form.getTitle(), form.getStructure(), form.getPresentation());
            validateImageFile(form.getPosterFile(), "Poster");
            validateImageFile(form.getBackdropFile(), "Backdrop");

            if (form.getStructure() == ScreenStructure.FEATURE && form.getTotalEpisodeCount() != null) {
                throw new AdminScreenValidationException("Phim lẻ (FEATURE) không áp dụng tổng số tập dự kiến.");
            }
            if (form.getStructure() == ScreenStructure.SERIES && form.getTotalEpisodeCount() != null && form.getTotalEpisodeCount() <= 0) {
                throw new AdminScreenValidationException("Tổng số tập dự kiến phải là số nguyên dương lớn hơn 0.");
            }

            validateReleaseMetadataForm(
                    form.getStructure(),
                    form.getAiringStatus(),
                    form.getReleaseYear(),
                    form.getCountry(),
                    form.getOriginalAuthor(),
                    form.getDirector(),
                    form.getProductionStudio()
            );

            UUID posterId = null;
            if (form.getPosterFile() != null && !form.getPosterFile().isEmpty()) {
                if (screenImageMediaPort != null) {
                    try (InputStream is = form.getPosterFile().getInputStream()) {
                        newlyUploadedPosterId = screenImageMediaPort.uploadPoster(
                                is,
                                form.getPosterFile().getSize(),
                                form.getPosterFile().getContentType(),
                                form.getPosterFile().getOriginalFilename(),
                                form.getTitle()
                        );
                        posterId = newlyUploadedPosterId;
                    } catch (IOException ex) {
                        throw new AdminScreenValidationException("Không thể đọc tệp ảnh poster đã tải lên.", ex);
                    }
                }
            }

            UUID backdropId = null;
            if (form.getBackdropFile() != null && !form.getBackdropFile().isEmpty()) {
                if (screenImageMediaPort != null) {
                    try (InputStream is = form.getBackdropFile().getInputStream()) {
                        newlyUploadedBackdropId = screenImageMediaPort.uploadBackdrop(
                                is,
                                form.getBackdropFile().getSize(),
                                form.getBackdropFile().getContentType(),
                                form.getBackdropFile().getOriginalFilename(),
                                form.getTitle()
                        );
                        backdropId = newlyUploadedBackdropId;
                    } catch (IOException ex) {
                        throw new AdminScreenValidationException("Không thể đọc tệp ảnh backdrop đã tải lên.", ex);
                    }
                }
            }

            // Boundary: Pass null as slug to enforce automatic server-side generation from title.
            // Client form.getSlug() is ignored.
            created = createScreenTitleUseCase.execute(
                    new CreateScreenTitleCommand(
                            null,
                            form.getTitle(),
                            form.getOriginalTitle(),
                            form.getSynopsis(),
                            form.getStructure(),
                            form.getPresentation(),
                            posterId,
                            backdropId,
                            form.getTotalEpisodeCount(),
                            form.getAiringStatus(),
                            form.getReleaseYear(),
                            form.getCountry(),
                            form.getOriginalAuthor(),
                            form.getDirector(),
                            form.getProductionStudio()
                    )
            );

        } catch (AccessDeniedException ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            throw ex;
        } catch (AdminScreenValidationException
                 | ScreenTitleSlugAlreadyExistsException
                 | ScreenMediaAssetNotFoundException
                 | InvalidScreenMediaAssetTypeException
                 | InvalidScreenTitleTotalEpisodeCountException ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            LOGGER.warn("Screen Title creation rejected due to validation failure: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/new";
        } catch (Exception ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            LOGGER.error("Screen Title creation failed unexpectedly: {}", ex.getMessage(), ex);
            redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi tạo Screen Title. Vui lòng thử lại sau.");
            return "redirect:/admin/screen/titles/new";
        }

        // Response formatting occurs strictly outside the upload/persistence compensation boundary.
        // Once persistence succeeds, media is NEVER compensated due to downstream response issues.
        try {
            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Screen Title \"" + created.title() + "\".");
        } catch (Exception ex) {
            LOGGER.warn("Screen Title [{}] created successfully, but flash message creation failed: {}",
                    created.id(), ex.getMessage(), ex);
        }
        return "redirect:/admin/screen/titles/" + created.id();
    }

    @PostMapping("/{titleId}/update")
    public String updateTitle(
            @PathVariable UUID titleId,
            @ModelAttribute("form") EditScreenTitleForm form,
            RedirectAttributes redirectAttributes
    ) {
        UUID newlyUploadedPosterId = null;
        UUID newlyUploadedBackdropId = null;
        ScreenTitleDTO updated;

        try {
            if (form == null) {
                throw new AdminScreenValidationException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateTitleForm(form.getTitle(), form.getStructure(), form.getPresentation());

            if (form.isRemovePoster() && form.getPosterFile() != null && !form.getPosterFile().isEmpty()) {
                throw new AdminScreenValidationException("Không thể đồng thời vừa xóa poster vừa tải lên poster mới.");
            }
            if (form.isRemoveBackdrop() && form.getBackdropFile() != null && !form.getBackdropFile().isEmpty()) {
                throw new AdminScreenValidationException("Không thể đồng thời vừa xóa backdrop vừa tải lên backdrop mới.");
            }

            validateImageFile(form.getPosterFile(), "Poster");
            validateImageFile(form.getBackdropFile(), "Backdrop");

            if (form.getStructure() == ScreenStructure.FEATURE && form.getTotalEpisodeCount() != null) {
                throw new AdminScreenValidationException("Phim lẻ (FEATURE) không áp dụng tổng số tập dự kiến.");
            }
            if (form.getStructure() == ScreenStructure.SERIES && form.getTotalEpisodeCount() != null && form.getTotalEpisodeCount() <= 0) {
                throw new AdminScreenValidationException("Tổng số tập dự kiến phải là số nguyên dương lớn hơn 0.");
            }

            validateReleaseMetadataForm(
                    form.getStructure(),
                    form.getAiringStatus(),
                    form.getReleaseYear(),
                    form.getCountry(),
                    form.getOriginalAuthor(),
                    form.getDirector(),
                    form.getProductionStudio()
            );

            boolean updateDefaultTimeline = form.hasDefaultTimelineUpdateIntent();
            ParsedDefaultTimeline parsedTimeline = null;
            if (updateDefaultTimeline) {
                parsedTimeline = parseAndValidateDefaultTimeline(
                        form.getDefaultIntroStart(),
                        form.getDefaultIntroEnd(),
                        form.getDefaultOutroStart()
                );
            }

            ScreenTitleDTO existing = null;
            if (getScreenTitleDetailUseCase != null) {
                try {
                    existing = getScreenTitleDetailUseCase.execute(titleId);
                } catch (ScreenTitleNotFoundException ex) {
                    redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
                    return "redirect:/admin/screen/titles";
                }
            }

            UUID posterId;
            if (form.isRemovePoster()) {
                posterId = null;
            } else if (form.getPosterFile() != null && !form.getPosterFile().isEmpty()) {
                if (screenImageMediaPort != null) {
                    try (InputStream is = form.getPosterFile().getInputStream()) {
                        newlyUploadedPosterId = screenImageMediaPort.uploadPoster(
                                is,
                                form.getPosterFile().getSize(),
                                form.getPosterFile().getContentType(),
                                form.getPosterFile().getOriginalFilename(),
                                form.getTitle()
                        );
                        posterId = newlyUploadedPosterId;
                    } catch (IOException ex) {
                        throw new AdminScreenValidationException("Không thể đọc tệp ảnh poster đã tải lên.", ex);
                    }
                } else {
                    posterId = null;
                }
            } else {
                posterId = (existing != null) ? existing.posterMediaAssetId() : null;
            }

            UUID backdropId;
            if (form.isRemoveBackdrop()) {
                backdropId = null;
            } else if (form.getBackdropFile() != null && !form.getBackdropFile().isEmpty()) {
                if (screenImageMediaPort != null) {
                    try (InputStream is = form.getBackdropFile().getInputStream()) {
                        newlyUploadedBackdropId = screenImageMediaPort.uploadBackdrop(
                                is,
                                form.getBackdropFile().getSize(),
                                form.getBackdropFile().getContentType(),
                                form.getBackdropFile().getOriginalFilename(),
                                form.getTitle()
                        );
                        backdropId = newlyUploadedBackdropId;
                    } catch (IOException ex) {
                        throw new AdminScreenValidationException("Không thể đọc tệp ảnh backdrop đã tải lên.", ex);
                    }
                } else {
                    backdropId = null;
                }
            } else {
                backdropId = (existing != null) ? existing.backdropMediaAssetId() : null;
            }

            // Boundary: Preserve the trusted persisted slug regardless of any client-submitted slug parameter.
            // Client form.getSlug() is ignored. Title renames do not change established slugs.
            String targetSlug = (existing != null) ? existing.slug() : null;

            updated = updateScreenTitleUseCase.execute(
                    new UpdateScreenTitleCommand(
                            titleId,
                            targetSlug,
                            form.getTitle(),
                            form.getOriginalTitle(),
                            form.getSynopsis(),
                            form.getStructure(),
                            form.getPresentation(),
                            posterId,
                            backdropId,
                            form.getTotalEpisodeCount(),
                            true,
                            true,
                            form.getAiringStatus(),
                            form.getReleaseYear(),
                            form.getCountry(),
                            form.getOriginalAuthor(),
                            form.getDirector(),
                            form.getProductionStudio(),
                            updateDefaultTimeline,
                            parsedTimeline != null ? parsedTimeline.introStartSeconds() : null,
                            parsedTimeline != null ? parsedTimeline.introEndSeconds() : null,
                            parsedTimeline != null ? parsedTimeline.outroStartSeconds() : null
                    )
            );

        } catch (AccessDeniedException ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            throw ex;
        } catch (ScreenTitleNotFoundException ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            LOGGER.warn("Screen Title [{}] not found for update: {}", titleId, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (AdminScreenValidationException
                 | ScreenTitleSlugAlreadyExistsException
                 | ScreenMediaAssetNotFoundException
                 | InvalidScreenMediaAssetTypeException
                 | InvalidScreenTitleTotalEpisodeCountException
                 | InvalidScreenTimelineConfigurationException ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            LOGGER.warn("Screen Title [{}] update rejected due to validation failure: {}", titleId, ex.getMessage());
            preserveTitleFormForRetry(form, redirectAttributes);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/edit";
        } catch (Exception ex) {
            compensateNewlyUploadedMedia(newlyUploadedPosterId, newlyUploadedBackdropId, ex);
            LOGGER.error("Screen Title [{}] update failed unexpectedly: {}", titleId, ex.getMessage(), ex);
            preserveTitleFormForRetry(form, redirectAttributes);
            redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi cập nhật Screen Title. Vui lòng thử lại sau.");
            return "redirect:/admin/screen/titles/" + titleId + "/edit";
        }

        // Response formatting occurs strictly outside the upload/persistence compensation boundary.
        // Once persistence succeeds, media is NEVER compensated due to downstream response issues.
        try {
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật Screen Title \"" + updated.title() + "\".");
        } catch (Exception ex) {
            LOGGER.warn("Screen Title [{}] updated successfully, but flash message creation failed: {}",
                    titleId, ex.getMessage(), ex);
        }
        return "redirect:/admin/screen/titles/" + titleId;
    }

    @PostMapping("/{titleId}/publish")
    public String publishTitle(
            @PathVariable UUID titleId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenTitleDTO published = publishScreenTitleUseCase.execute(titleId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xuất bản Screen Title \"" + published.title() + "\".");
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (ScreenTitleNotFoundException ex) {
            LOGGER.warn("Screen Title [{}] not found for publish: {}", titleId, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (com.universe.screen.application.exceptions.ScreenTitleVideoConflictException ex) {
            LOGGER.warn("Screen Title [{}] publish dynamic asset conflict: {}", titleId, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (IllegalStateException ex) {
            if (isTrustedPublishLifecycleMessage(ex.getMessage())) {
                LOGGER.warn("Screen Title [{}] publish rejected: {}", titleId, ex.getMessage());
                redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            } else {
                LOGGER.error("Unexpected error publishing Screen Title [{}]: {}", titleId, ex.getMessage(), ex);
                redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi xuất bản Screen Title.");
            }
        } catch (Exception ex) {
            LOGGER.error("Unexpected error publishing Screen Title [{}]: {}", titleId, ex.getMessage(), ex);
            redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi xuất bản Screen Title.");
        }

        return "redirect:/admin/screen/titles/" + titleId;
    }

    @PostMapping("/{titleId}/archive")
    public String archiveTitle(
            @PathVariable UUID titleId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenTitleDTO archived = archiveScreenTitleUseCase.execute(titleId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ Screen Title \"" + archived.title() + "\".");
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (ScreenTitleNotFoundException ex) {
            LOGGER.warn("Screen Title [{}] not found for archive: {}", titleId, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (com.universe.screen.application.exceptions.ScreenTitleVideoConflictException ex) {
            LOGGER.warn("Screen Title [{}] archive dynamic asset conflict: {}", titleId, ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (IllegalStateException ex) {
            if (isTrustedArchiveLifecycleMessage(ex.getMessage())) {
                LOGGER.warn("Screen Title [{}] archive rejected: {}", titleId, ex.getMessage());
                redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            } else {
                LOGGER.error("Unexpected error archiving Screen Title [{}]: {}", titleId, ex.getMessage(), ex);
                redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi lưu trữ Screen Title.");
            }
        } catch (Exception ex) {
            LOGGER.error("Unexpected error archiving Screen Title [{}]: {}", titleId, ex.getMessage(), ex);
            redirectAttributes.addFlashAttribute("errorMessage", "Đã xảy ra lỗi hệ thống khi lưu trữ Screen Title.");
        }

        return "redirect:/admin/screen/titles/" + titleId;
    }

    private void compensateNewlyUploadedMedia(UUID newPosterId, UUID newBackdropId, Throwable primaryException) {
        if (screenImageMediaPort != null) {
            if (newPosterId != null) {
                try {
                    screenImageMediaPort.compensateUploadedImage(newPosterId, primaryException);
                } catch (RuntimeException compEx) {
                    LOGGER.error("Failed to compensate Screen media asset [{}]: {}", newPosterId, compEx.getMessage(), compEx);
                    if (primaryException != null) {
                        primaryException.addSuppressed(compEx);
                    }
                }
            }
            if (newBackdropId != null) {
                try {
                    screenImageMediaPort.compensateUploadedImage(newBackdropId, primaryException);
                } catch (RuntimeException compEx) {
                    LOGGER.error("Failed to compensate Screen media asset [{}]: {}", newBackdropId, compEx.getMessage(), compEx);
                    if (primaryException != null) {
                        primaryException.addSuppressed(compEx);
                    }
                }
            }
        }
    }

    private void validateImageFile(MultipartFile file, String fieldLabel) {
        if (file == null || file.isEmpty()) {
            return;
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new AdminScreenValidationException("Kích thước " + fieldLabel + " không được vượt quá 5MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new AdminScreenValidationException("Định dạng " + fieldLabel + " không được hỗ trợ. Chỉ chấp nhận JPG, PNG hoặc WebP.");
        }
    }

    private void validateTitleForm(String title, ScreenStructure structure, ScreenPresentation presentation) {
        if (title == null || title.isBlank()) {
            throw new AdminScreenValidationException("Tiêu đề Screen không được để trống.");
        }
        if (structure == null) {
            throw new AdminScreenValidationException("Vui lòng chọn cấu trúc (Structure: SERIES hoặc FEATURE).");
        }
        if (presentation == null) {
            throw new AdminScreenValidationException("Vui lòng chọn phong cách thể hiện (Presentation: ANIMATION_3D hoặc LIVE_ACTION).");
        }
    }

    private void validateReleaseMetadataForm(
            ScreenStructure structure,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
    ) {
        try {
            ScreenMetadataValidator.validateAiringStatus(structure, airingStatus);
            ScreenMetadataValidator.validateReleaseYear(releaseYear);
            ScreenMetadataValidator.validateCountry(country);
            ScreenMetadataValidator.validateOriginalAuthor(originalAuthor);
            ScreenMetadataValidator.validateDirector(director);
            ScreenMetadataValidator.validateProductionStudio(productionStudio);
        } catch (IllegalArgumentException ex) {
            throw new AdminScreenValidationException(ex.getMessage(), ex);
        }
    }

    private static ParsedDefaultTimeline parseAndValidateDefaultTimeline(
            String defaultIntroStartRaw,
            String defaultIntroEndRaw,
            String defaultOutroStartRaw
    ) {
        try {
            Long introStartSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(defaultIntroStartRaw);
            Long introEndSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(defaultIntroEndRaw);
            Long outroStartSeconds = ScreenTimelineTimestampFormat.parseOptionalSeconds(defaultOutroStartRaw);
            ScreenTimelineTimestampFormat.validateIntroPairAndOrder(introStartSeconds, introEndSeconds);
            ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(introEndSeconds, outroStartSeconds);
            return new ParsedDefaultTimeline(introStartSeconds, introEndSeconds, outroStartSeconds);
        } catch (IllegalArgumentException ex) {
            throw new AdminScreenValidationException(ex.getMessage(), ex);
        }
    }

    private static void preserveTitleFormForRetry(EditScreenTitleForm form, RedirectAttributes redirectAttributes) {
        if (form != null && redirectAttributes != null) {
            form.setPosterFile(null);
            form.setBackdropFile(null);
            redirectAttributes.addFlashAttribute("form", form);
        }
    }

    private record ParsedDefaultTimeline(
            Long introStartSeconds,
            Long introEndSeconds,
            Long outroStartSeconds
    ) {
    }

    private static final Set<String> TRUSTED_PUBLISH_LIFECYCLE_MESSAGES = Set.of(
            "ScreenTitle is already published.",
            "Archived ScreenTitle cannot be published.",
            "Only DRAFT titles can be published."
    );

    private static final Set<String> TRUSTED_ARCHIVE_LIFECYCLE_MESSAGES = Set.of(
            "ScreenTitle is already archived.",
            "Title is already archived."
    );

    private static boolean isTrustedPublishLifecycleMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        for (String trusted : TRUSTED_PUBLISH_LIFECYCLE_MESSAGES) {
            if (trusted.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrustedArchiveLifecycleMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        for (String trusted : TRUSTED_ARCHIVE_LIFECYCLE_MESSAGES) {
            if (trusted.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Local validation exception representing trusted, user-facing validation errors
     * detected by controller-level form and file constraints.
     */
    private static final class AdminScreenValidationException extends RuntimeException {
        AdminScreenValidationException(String message) {
            super(message);
        }

        AdminScreenValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
