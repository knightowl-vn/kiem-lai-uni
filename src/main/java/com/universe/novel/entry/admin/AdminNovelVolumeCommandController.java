package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.novel.application.exceptions.VolumeHasPublishedChaptersException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeSortOrderAlreadyExistsException;

import com.universe.novel.application.volume.ArchiveVolumeCommand;
import com.universe.novel.application.volume.ArchiveVolumeUseCase;
import com.universe.novel.application.volume.CreateVolumeCommand;
import com.universe.novel.application.volume.CreateVolumeUseCase;
import com.universe.novel.application.volume.PublishVolumeCommand;
import com.universe.novel.application.volume.PublishVolumeUseCase;
import com.universe.novel.application.volume.RestoreVolumeCommand;
import com.universe.novel.application.volume.RestoreVolumeUseCase;
import com.universe.novel.application.volume.UpdateDraftVolumeCommand;
import com.universe.novel.application.volume.UpdateDraftVolumeUseCase;
import com.universe.novel.contracts.dto.VolumeDTO;

import com.universe.novel.entry.admin.form.CreateVolumeForm;
import com.universe.novel.entry.admin.form.EditVolumeForm;

import org.springframework.security.core.Authentication;

import org.springframework.stereotype.Controller;

import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/volumes")
public class AdminNovelVolumeCommandController {

	private final CreateVolumeUseCase createVolumeUseCase;

	private final UserIdentityContract userIdentityContract;

	private final UpdateDraftVolumeUseCase updateDraftVolumeUseCase;

	private final PublishVolumeUseCase publishVolumeUseCase;

	private final ArchiveVolumeUseCase archiveVolumeUseCase;

	private final RestoreVolumeUseCase restoreVolumeUseCase;

	public AdminNovelVolumeCommandController(CreateVolumeUseCase createVolumeUseCase,
			UpdateDraftVolumeUseCase updateDraftVolumeUseCase, PublishVolumeUseCase publishVolumeUseCase,
			ArchiveVolumeUseCase archiveVolumeUseCase, RestoreVolumeUseCase restoreVolumeUseCase,
			UserIdentityContract userIdentityContract) {
		this.createVolumeUseCase = createVolumeUseCase;

		this.updateDraftVolumeUseCase = updateDraftVolumeUseCase;

		this.publishVolumeUseCase = publishVolumeUseCase;

		this.archiveVolumeUseCase = archiveVolumeUseCase;

		this.restoreVolumeUseCase = restoreVolumeUseCase;

		this.userIdentityContract = userIdentityContract;
	}

	/*
	 * ===================================================== CREATE
	 * =====================================================
	 */

	@PostMapping
	public String createVolume(@ModelAttribute("form") CreateVolumeForm form,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {

		try {

			validateCreateForm(form);

			UUID actorId = resolveActorId(authentication);

			VolumeDTO volume = createVolumeUseCase.execute(new CreateVolumeCommand(form.getTitle().trim(),
					normalizeText(form.getDescription()), form.getSortOrder(), actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Volume \"" + volume.title() + "\".");

			return "redirect:/admin/novel/volumes/" + volume.id();

		} catch (VolumeSlugAlreadyExistsException | VolumeSortOrderAlreadyExistsException
				| IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/novel/volumes/new";
		}
	}

	/*
	 * ===================================================== UPDATE DRAFT
	 * =====================================================
	 */

	@PostMapping("/{volumeId}/update")
	public String updateDraftVolume(@PathVariable UUID volumeId,

			@ModelAttribute("form") EditVolumeForm form,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {

		try {

			validateEditForm(form);

			UUID actorId = resolveActorId(authentication);

			VolumeDTO updatedVolume = updateDraftVolumeUseCase.execute(new UpdateDraftVolumeCommand(volumeId,
					form.getTitle().trim(), normalizeText(form.getDescription()), actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã cập nhật Volume \"" + updatedVolume.title() + "\".");

			return "redirect:/admin/novel/volumes/" + volumeId;

		} catch (IllegalArgumentException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/novel/volumes/" + volumeId + "/edit";
		}
	}

	/*
	 * =====================================================
	 * PUBLISH
	 * =====================================================
	 */

	@PostMapping("/{volumeId}/publish")
	public String publishVolume(
	        @PathVariable UUID volumeId,

	        Authentication authentication,

	        RedirectAttributes redirectAttributes
	) {

	    UUID actorId =
	            resolveActorId(
	                    authentication
	            );

	    try {

	        VolumeDTO volume =
	                publishVolumeUseCase.execute(
	                        new PublishVolumeCommand(
	                                volumeId,
	                                actorId
	                        )
	                );

	        redirectAttributes.addFlashAttribute(
	                "successMessage",
	                "Đã xuất bản Volume \""
	                        + volume.title()
	                        + "\"."
	        );

	    } catch (IllegalStateException exception) {

	        redirectAttributes.addFlashAttribute(
	                "errorMessage",
	                "Không thể xuất bản Volume. "
	                        + exception.getMessage()
	        );
	    }

	    return "redirect:/admin/novel/volumes";
	}

	/*
	 * =====================================================
	 * ARCHIVE
	 * =====================================================
	 */

	@PostMapping("/{volumeId}/archive")
	public String archiveVolume(
	        @PathVariable UUID volumeId,

	        Authentication authentication,

	        RedirectAttributes redirectAttributes
	) {

	    UUID actorId =
	            resolveActorId(
	                    authentication
	            );

	    try {

	        VolumeDTO volume =
	                archiveVolumeUseCase.execute(
	                        new ArchiveVolumeCommand(
	                                volumeId,
	                                actorId
	                        )
	                );

	        redirectAttributes.addFlashAttribute(
	                "successMessage",
	                "Đã lưu trữ Volume \""
	                        + volume.title()
	                        + "\"."
	        );

	    } catch (VolumeHasPublishedChaptersException
	            | VolumeNotFoundException
	            | IllegalStateException exception) {

	        redirectAttributes.addFlashAttribute(
	                "errorMessage",
	                exception.getMessage()
	        );
	    }

	    return "redirect:/admin/novel/volumes";
	}

	/*
	 * =====================================================
	 * RESTORE
	 * =====================================================
	 */

	@PostMapping("/{volumeId}/restore")
	public String restoreVolume(
	        @PathVariable UUID volumeId,

	        Authentication authentication,

	        RedirectAttributes redirectAttributes
	) {

	    UUID actorId =
	            resolveActorId(
	                    authentication
	            );

	    try {

	        VolumeDTO volume =
	                restoreVolumeUseCase.execute(
	                        new RestoreVolumeCommand(
	                                volumeId,
	                                actorId
	                        )
	                );

	        redirectAttributes.addFlashAttribute(
	                "successMessage",
	                "Đã khôi phục Volume \""
	                        + volume.title()
	                        + "\" về bản nháp."
	        );

	    } catch (VolumeNotFoundException
	            | IllegalStateException
	            | IllegalArgumentException exception) {

	        redirectAttributes.addFlashAttribute(
	                "errorMessage",
	                exception.getMessage()
	        );
	    }

	    return "redirect:/admin/novel/volumes";
	}

	/*
	 * ===================================================== VALIDATION
	 * =====================================================
	 */

	private void validateCreateForm(CreateVolumeForm form) {

		if (form == null) {
			throw new IllegalArgumentException("Form tạo Volume không được để trống.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {
			throw new IllegalArgumentException("Tên Volume không được để trống.");
		}

		if (form.getSortOrder() == null || form.getSortOrder() < 1) {
			throw new IllegalArgumentException("Thứ tự Volume phải từ 1 trở lên.");
		}
	}

	private void validateEditForm(EditVolumeForm form) {

		if (form == null) {
			throw new IllegalArgumentException("Form chỉnh sửa Volume không được để trống.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {
			throw new IllegalArgumentException("Tên Volume không được để trống.");
		}
	}

	/*
	 * ===================================================== CURRENT ACTOR
	 * =====================================================
	 */

	private UUID resolveActorId(Authentication authentication) {

		if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
			throw new IllegalStateException("Không xác định được người dùng đang đăng nhập.");
		}

		String email = authentication.getName().trim();

		UserDTO user = userIdentityContract.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("Không tìm thấy người dùng đang đăng nhập."));

		return user.id();
	}

	/*
	 * ===================================================== NORMALIZATION
	 * =====================================================
	 */

	private String normalizeText(String value) {

		if (value == null) {
			return "";
		}

		return value.trim();
	}
}
