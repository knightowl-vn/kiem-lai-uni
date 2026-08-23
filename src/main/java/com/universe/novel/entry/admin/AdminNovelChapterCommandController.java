package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.novel.application.chapter.ArchiveChapterCommand;
import com.universe.novel.application.chapter.ArchiveChapterUseCase;
import com.universe.novel.application.chapter.CreateChapterCommand;
import com.universe.novel.application.chapter.CreateChapterUseCase;
import com.universe.novel.application.chapter.DeleteDraftChapterCommand;
import com.universe.novel.application.chapter.DeleteDraftChapterUseCase;
import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.MoveChapterCommand;
import com.universe.novel.application.chapter.MoveChapterUseCase;
import com.universe.novel.application.chapter.PublishChapterCommand;
import com.universe.novel.application.chapter.PublishChapterUseCase;
import com.universe.novel.application.chapter.RestoreChapterCommand;
import com.universe.novel.application.chapter.RestoreChapterUseCase;
import com.universe.novel.application.chapter.UnpublishChapterCommand;
import com.universe.novel.application.chapter.UnpublishChapterUseCase;
import com.universe.novel.application.chapter.UpdateDraftChapterCommand;
import com.universe.novel.application.chapter.UpdateDraftChapterUseCase;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterNumberAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotPublishedException;

import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.entry.admin.form.CreateChapterForm;
import com.universe.novel.entry.admin.form.EditChapterForm;
import com.universe.novel.entry.admin.form.MoveChapterForm;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/novel")
public class AdminNovelChapterCommandController {

	private final CreateChapterUseCase createChapterUseCase;

	private final UpdateDraftChapterUseCase updateDraftChapterUseCase;

	private final PublishChapterUseCase publishChapterUseCase;

	private final UnpublishChapterUseCase unpublishChapterUseCase;

	private final ArchiveChapterUseCase archiveChapterUseCase;

	private final RestoreChapterUseCase restoreChapterUseCase;

	private final DeleteDraftChapterUseCase deleteDraftChapterUseCase;

	private final MoveChapterUseCase moveChapterUseCase;

	private final GetChapterDetailUseCase getChapterDetailUseCase;

	private final UserIdentityContract userIdentityContract;

	public AdminNovelChapterCommandController(CreateChapterUseCase createChapterUseCase,
			UpdateDraftChapterUseCase updateDraftChapterUseCase, PublishChapterUseCase publishChapterUseCase,
			UnpublishChapterUseCase unpublishChapterUseCase, ArchiveChapterUseCase archiveChapterUseCase,
			RestoreChapterUseCase restoreChapterUseCase, DeleteDraftChapterUseCase deleteDraftChapterUseCase,
			MoveChapterUseCase moveChapterUseCase, GetChapterDetailUseCase getChapterDetailUseCase,
			UserIdentityContract userIdentityContract) {
		this.createChapterUseCase = createChapterUseCase;

		this.updateDraftChapterUseCase = updateDraftChapterUseCase;

		this.publishChapterUseCase = publishChapterUseCase;

		this.unpublishChapterUseCase = unpublishChapterUseCase;

		this.archiveChapterUseCase = archiveChapterUseCase;

		this.restoreChapterUseCase = restoreChapterUseCase;

		this.deleteDraftChapterUseCase = deleteDraftChapterUseCase;

		this.moveChapterUseCase = moveChapterUseCase;

		this.getChapterDetailUseCase = getChapterDetailUseCase;

		this.userIdentityContract = userIdentityContract;
	}

	@PostMapping("/volumes/{volumeId}/chapters")
	public String createChapter(@PathVariable UUID volumeId, @ModelAttribute("form") CreateChapterForm form,
			Authentication authentication, RedirectAttributes redirectAttributes) {

		try {
			validateCreateForm(form);

			UUID actorId = resolveActorId(authentication);

			ChapterDTO chapter = createChapterUseCase.execute(new CreateChapterCommand(volumeId, form.getChapterNumber(),
					form.getTitle().trim(), normalizeText(form.getSummary()), normalizeText(form.getContent()),
					actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Chapter \"" + chapter.title() + "\".");

			return "redirect:/admin/novel/chapters/" + chapter.id();

		} catch (ChapterNumberAlreadyExistsException | ChapterSlugAlreadyExistsException
				| VolumeNotFoundException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/novel/volumes/" + volumeId + "/chapters/new";
		}
	}

	@PostMapping("/chapters/{chapterId}/update")
	public String updateDraftChapter(@PathVariable UUID chapterId, @ModelAttribute("form") EditChapterForm form,
			Authentication authentication, RedirectAttributes redirectAttributes) {

		try {
			validateEditForm(form);

			UUID actorId = resolveActorId(authentication);

			ChapterDTO updatedChapter = updateDraftChapterUseCase.execute(new UpdateDraftChapterCommand(chapterId,
					form.getChapterNumber(), form.getTitle().trim(), normalizeText(form.getSummary()),
					normalizeText(form.getContent()), actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã cập nhật Chapter \"" + updatedChapter.title() + "\".");

			return "redirect:/admin/novel/chapters/" + chapterId;

		} catch (IllegalArgumentException | IllegalStateException | ChapterNotFoundException
				| ChapterNumberAlreadyExistsException | ChapterSlugAlreadyExistsException
				| VolumeNotFoundException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/novel/chapters/" + chapterId + "/edit";
		}
	}

	@PostMapping("/chapters/{chapterId}/publish")
	public String publishChapter(@PathVariable UUID chapterId, Authentication authentication,
			RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		UUID volumeId = getChapterDetailUseCase.execute(chapterId).volumeId();

		try {
			ChapterDTO chapter = publishChapterUseCase
					.execute(new PublishChapterCommand(chapterId, actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã xuất bản Chapter \"" + chapter.title() + "\".");

		} catch (VolumeNotPublishedException | ChapterNotFoundException | IllegalStateException
				| IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return redirectToChapterList(volumeId);
	}

	@PostMapping("/chapters/{chapterId}/unpublish")
	public String unpublishChapter(@PathVariable UUID chapterId, Authentication authentication,
			RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		UUID volumeId = getChapterDetailUseCase.execute(chapterId).volumeId();

		try {
			ChapterDTO chapter = unpublishChapterUseCase
					.execute(new UnpublishChapterCommand(chapterId, actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã hủy xuất bản Chapter \"" + chapter.title() + "\".");

		} catch (ChapterNotFoundException | IllegalStateException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return redirectToChapterList(volumeId);
	}

	@PostMapping("/chapters/{chapterId}/archive")
	public String archiveChapter(@PathVariable UUID chapterId, Authentication authentication,
			RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		UUID volumeId = getChapterDetailUseCase.execute(chapterId).volumeId();

		try {
			ChapterDTO chapter = archiveChapterUseCase.execute(new ArchiveChapterCommand(chapterId, actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ Chapter \"" + chapter.title() + "\".");

		} catch (ChapterNotFoundException | IllegalStateException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return redirectToChapterList(volumeId);
	}

	@PostMapping("/chapters/{chapterId}/restore")
	public String restoreChapter(@PathVariable UUID chapterId, Authentication authentication,
			RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		UUID volumeId = getChapterDetailUseCase.execute(chapterId).volumeId();

		try {
			ChapterDTO chapter = restoreChapterUseCase.execute(new RestoreChapterCommand(chapterId, actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã khôi phục Chapter \"" + chapter.title() + "\".");

		} catch (ChapterNotFoundException | IllegalStateException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return redirectToChapterList(volumeId);
	}

	@PostMapping("/chapters/{chapterId}/delete")
	public String deleteDraftChapter(@PathVariable UUID chapterId, Authentication authentication,
			RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		ChapterDTO chapter = getChapterDetailUseCase.execute(chapterId);

		UUID volumeId = chapter.volumeId();

		try {
			deleteDraftChapterUseCase.execute(new DeleteDraftChapterCommand(chapterId, actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã xóa Chapter \"" + chapter.title() + "\".");

			return redirectToChapterList(volumeId);

		} catch (ChapterNotFoundException | IllegalStateException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return redirectToChapterList(volumeId);
		}
	}

	@PostMapping("/chapters/{chapterId}/move")
	public String moveChapter(@PathVariable UUID chapterId, @ModelAttribute("moveForm") MoveChapterForm form,
			Authentication authentication, RedirectAttributes redirectAttributes) {

		UUID actorId = resolveActorId(authentication);

		UUID sourceVolumeId = getChapterDetailUseCase.execute(chapterId).volumeId();

		try {
			if (form == null || form.getTargetVolumeId() == null) {
				throw new IllegalArgumentException("Volume đích không được để trống.");
			}

			ChapterDTO chapter = moveChapterUseCase
					.execute(new MoveChapterCommand(chapterId, form.getTargetVolumeId(), actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã chuyển Chapter \"" + chapter.title() + "\" sang Volume khác.");

			return redirectToChapterList(chapter.volumeId());

		} catch (ChapterNotFoundException | VolumeNotFoundException | ChapterSlugAlreadyExistsException
				| IllegalStateException | IllegalArgumentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return redirectToChapterList(sourceVolumeId);
		}
	}

	private String redirectToChapterList(UUID volumeId) {
		return "redirect:/admin/novel/volumes/" + volumeId + "/chapters";
	}

	private void validateCreateForm(CreateChapterForm form) {

		if (form == null) {
			throw new IllegalArgumentException("Form tạo Chapter không được để trống.");
		}

		if (form.getChapterNumber() == null || form.getChapterNumber() < 1) {
			throw new IllegalArgumentException("Số chương phải lớn hơn hoặc bằng 1.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {
			throw new IllegalArgumentException("Tiêu đề Chapter không được để trống.");
		}
	}

	private void validateEditForm(EditChapterForm form) {

		if (form == null) {
			throw new IllegalArgumentException("Form chỉnh sửa Chapter không được để trống.");
		}

		if (form.getChapterNumber() == null || form.getChapterNumber() < 1) {
			throw new IllegalArgumentException("Số chương phải lớn hơn hoặc bằng 1.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {
			throw new IllegalArgumentException("Tiêu đề Chapter không được để trống.");
		}
	}

	private UUID resolveActorId(Authentication authentication) {

		if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
			throw new IllegalStateException("Không xác định được người dùng đang đăng nhập.");
		}

		String email = authentication.getName().trim();

		UserDTO user = userIdentityContract.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("Không tìm thấy người dùng đang đăng nhập."));

		return user.id();
	}

	private String normalizeText(String value) {

		if (value == null) {
			return "";
		}

		return value.trim();
	}
}
