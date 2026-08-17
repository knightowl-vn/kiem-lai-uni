package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.wiki.application.article.archive.ArchiveWikiArticleCommand;
import com.universe.wiki.application.article.archive.ArchiveWikiArticleUseCase;

import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;

import com.universe.wiki.application.article.delete.DeleteWikiArticleCommand;
import com.universe.wiki.application.article.delete.DeleteWikiArticleUseCase;

import com.universe.wiki.application.article.publish.PublishWikiArticleCommand;
import com.universe.wiki.application.article.publish.PublishWikiArticleUseCase;

import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.restore.RestoreWikiArticleCommand;
import com.universe.wiki.application.article.restore.RestoreWikiArticleUseCase;
import com.universe.wiki.application.article.unpublish.UnpublishWikiArticleCommand;
import com.universe.wiki.application.article.unpublish.UnpublishWikiArticleUseCase;

import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleCommand;

import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleCommand;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleUseCase;
import com.universe.wiki.application.exceptions.WikiArticleRevisionAlreadyCurrentException;
import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.contracts.dto.WikiArticleDTO;

import com.universe.wiki.domain.article.ArticleStatus;

import com.universe.wiki.entry.admin.form.CreateWikiArticleAction;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleAction;
import com.universe.wiki.entry.admin.form.EditWikiArticleForm;

import org.springframework.security.core.Authentication;

import org.springframework.stereotype.Controller;

import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/wiki/articles")
public class AdminWikiArticleCommandController {

	private static final String AUTOSAVE_CLEANUP_ATTRIBUTE = "wikiAutosaveCleanupKey";

	private static final String CREATE_AUTOSAVE_KEY = "kiemlai:wiki:autosave:create";

	private String editAutosaveKey(UUID articleId) {

		return "kiemlai:wiki:autosave:edit:" + articleId;
	}

	/*
	 * ===================================================== CREATE
	 * =====================================================
	 */

	private final CreateWikiArticleUseCase createWikiArticleUseCase;

	private final CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase;

	/*
	 * ===================================================== UPDATE
	 * =====================================================
	 */

	private final UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

	private final UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase;

	private final UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase;

	/*
	 * ===================================================== QUERY
	 *
	 * Dùng để xác định trạng thái hiện tại của Article trước khi quyết định Update
	 * Draft hay Published. =====================================================
	 */

	private final GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;

	/*
	 * ===================================================== LIFECYCLE
	 * =====================================================
	 */

	private final PublishWikiArticleUseCase publishWikiArticleUseCase;

	private final UnpublishWikiArticleUseCase unpublishWikiArticleUseCase;

	private final ArchiveWikiArticleUseCase archiveWikiArticleUseCase;

	private final DeleteWikiArticleUseCase deleteWikiArticleUseCase;

	private final RestoreWikiArticleUseCase restoreWikiArticleUseCase;

	/*
	 * ===================================================== IDENTITY
	 * =====================================================
	 */

	private final UserIdentityContract userIdentityContract;

	/*
	 * ===================================================== CONSTRUCTOR
	 * =====================================================
	 */

	public AdminWikiArticleCommandController(CreateWikiArticleUseCase createWikiArticleUseCase,
			CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase,

			UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase,
			UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase,
			UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase,

			GetWikiArticleDetailUseCase getWikiArticleDetailUseCase,

			PublishWikiArticleUseCase publishWikiArticleUseCase,
			UnpublishWikiArticleUseCase unpublishWikiArticleUseCase,
			ArchiveWikiArticleUseCase archiveWikiArticleUseCase, RestoreWikiArticleUseCase restoreWikiArticleUseCase,
			DeleteWikiArticleUseCase deleteWikiArticleUseCase,

			UserIdentityContract userIdentityContract) {
		this.createWikiArticleUseCase = createWikiArticleUseCase;

		this.createAndPublishWikiArticleUseCase = createAndPublishWikiArticleUseCase;

		this.updateDraftWikiArticleUseCase = updateDraftWikiArticleUseCase;

		this.updateDraftAndPublishWikiArticleUseCase = updateDraftAndPublishWikiArticleUseCase;

		this.updatePublishedWikiArticleUseCase = updatePublishedWikiArticleUseCase;

		this.getWikiArticleDetailUseCase = getWikiArticleDetailUseCase;

		this.publishWikiArticleUseCase = publishWikiArticleUseCase;

		this.unpublishWikiArticleUseCase = unpublishWikiArticleUseCase;

		this.archiveWikiArticleUseCase = archiveWikiArticleUseCase;

		this.deleteWikiArticleUseCase = deleteWikiArticleUseCase;

		this.restoreWikiArticleUseCase = restoreWikiArticleUseCase;

		this.userIdentityContract = userIdentityContract;
	}

	/*
	 * ===================================================== CREATE
	 * =====================================================
	 */

	@PostMapping
	public String createArticle(@ModelAttribute("form") CreateWikiArticleForm form,

			@RequestParam("action") CreateWikiArticleAction action,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {
		validateCreateForm(form);

		UUID actorId = resolveActorId(authentication);

		WikiArticleDTO article;

		try {

			switch (action) {

			case SAVE_DRAFT ->

				article = createWikiArticleUseCase.execute(new CreateWikiArticleCommand(form.getTitle().trim(),
						form.getArticleType(), normalizeText(form.getSummary()), normalizeText(form.getContent()),
						normalizeEditSummary(form.getEditSummary()), actorId));

			case PUBLISH ->

				article = createAndPublishWikiArticleUseCase.execute(new CreateAndPublishWikiArticleCommand(
						form.getTitle().trim(), form.getArticleType(), normalizeText(form.getSummary()),
						normalizeText(form.getContent()), normalizePublishEditSummary(form.getEditSummary()), actorId));

			default -> throw new IllegalArgumentException("Hành động tạo bài Wiki không hợp lệ.");
			}

		} catch (ArticleSlugAlreadyExistsException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/wiki/articles/new";
		}

		String successMessage = switch (action) {

		case SAVE_DRAFT -> "Đã lưu bản nháp Wiki \"" + article.title() + "\".";

		case PUBLISH -> "Đã xuất bản bài Wiki \"" + article.title() + "\".";
		};

		redirectAttributes.addFlashAttribute("successMessage", successMessage);

		/*
		 * Chỉ tới được đây khi Create use case đã chạy thành công.
		 *
		 * Trang danh sách sẽ dùng tín hiệu này để xóa local draft của Create.
		 */
		redirectAttributes.addFlashAttribute(AUTOSAVE_CLEANUP_ATTRIBUTE, CREATE_AUTOSAVE_KEY);

		return redirectToArticleList();
	}

	/*
	 * ===================================================== UPDATE
	 * =====================================================
	 *
	 * Đây chính là endpoint mà edit.html gọi:
	 *
	 * POST /admin/wiki/articles/{articleId}/update
	 *
	 * Controller KHÔNG tin status từ browser.
	 *
	 * Nó đọc trạng thái thật của Article từ backend rồi:
	 *
	 * DRAFT -> UpdateDraftWikiArticleUseCase
	 *
	 * PUBLISHED -> UpdatePublishedWikiArticleUseCase
	 *
	 * ARCHIVED -> từ chối =====================================================
	 */

	@PostMapping("/{articleId}/update")
	public String updateArticle(@PathVariable UUID articleId,

			@ModelAttribute("form") EditWikiArticleForm form,

			@RequestParam(name = "action", defaultValue = "SAVE_CHANGES") EditWikiArticleAction action,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {
		validateEditForm(form);

		UUID actorId = resolveActorId(authentication);

		/*
		 * Không nhận status từ HTML.
		 *
		 * Luôn lấy trạng thái thật từ hệ thống.
		 */
		WikiArticleDTO currentArticle = getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(articleId));

		ArticleStatus status = ArticleStatus.valueOf(currentArticle.status());

		WikiArticleDTO updatedArticle;

		boolean publishedNow = false;

		try {

			switch (status) {

			/*
			 * ================================================= DRAFT
			 * =================================================
			 */
			case DRAFT -> {

				validateDraftEditForm(form);

				switch (action) {

				/*
				 * Chỉ lưu thay đổi, giữ article ở DRAFT.
				 */
				case SAVE_CHANGES ->

					updatedArticle = updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(articleId,

							form.getTitle().trim(),

							form.getArticleType(),

							normalizeText(form.getSummary()),

							normalizeText(form.getContent()),

							normalizeDraftUpdateEditSummary(form.getEditSummary()),

							actorId));

				/*
				 * Lưu dữ liệu hiện tại và publish trong cùng transaction.
				 */
				case SAVE_AND_PUBLISH -> {

					updatedArticle = updateDraftAndPublishWikiArticleUseCase
							.execute(new UpdateDraftAndPublishWikiArticleCommand(articleId,

									form.getTitle().trim(),

									form.getArticleType(),

									normalizeText(form.getSummary()),

									normalizeText(form.getContent()),

									normalizeNullableText(form.getEditSummary()),

									actorId));

					publishedNow = true;
				}

				default -> throw new IllegalArgumentException("Hành động chỉnh sửa bài Wiki không hợp lệ.");
				}
			}

			/*
			 * ================================================= PUBLISHED
			 *
			 * Article đã publish không được nhận SAVE_AND_PUBLISH từ browser.
			 * =================================================
			 */
			case PUBLISHED -> {

				if (action != EditWikiArticleAction.SAVE_CHANGES) {

					throw new IllegalStateException("Bài Wiki đã được xuất bản.");
				}

				updatedArticle = updatePublishedWikiArticleUseCase
						.execute(new UpdatePublishedWikiArticleCommand(articleId,

								normalizeText(form.getSummary()),

								normalizeText(form.getContent()),

								normalizePublishedUpdateEditSummary(form.getEditSummary()),

								actorId));
			}

			/*
			 * ================================================= ARCHIVED
			 * =================================================
			 */
			case ARCHIVED -> throw new IllegalStateException("Bài Wiki đã lưu trữ không thể chỉnh sửa trực tiếp.");

			default -> throw new IllegalStateException("Trạng thái bài Wiki không hỗ trợ chỉnh sửa: " + status.name());
			}

		} catch (ArticleSlugAlreadyExistsException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			if (status == ArticleStatus.ARCHIVED) {

				return redirectToArticleList();
			}

			return "redirect:/admin/wiki/articles/" + articleId + "/edit";
		}

		String successMessage = publishedNow
				? "Đã lưu thay đổi và xuất bản bài Wiki \"" + updatedArticle.title() + "\"."
				: "Đã cập nhật bài Wiki \"" + updatedArticle.title() + "\".";

		redirectAttributes.addFlashAttribute("successMessage", successMessage);

		/*
		 * Chỉ cleanup Auto-save của đúng bài vừa được backend lưu thành công.
		 */
		redirectAttributes.addFlashAttribute(AUTOSAVE_CLEANUP_ATTRIBUTE, editAutosaveKey(articleId));
		/*
		 * Sau khi lưu xong quay về trang chi tiết bài.
		 */
		return redirectToArticleDetail(articleId);
	}

	/*
	 * ===================================================== PUBLISH DRAFT
	 * =====================================================
	 */

	@PostMapping("/{articleId}/publish")
	public String publishArticle(@PathVariable UUID articleId,

			@RequestParam(name = "returnTo", defaultValue = "list") String returnTo,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {
		UUID actorId = resolveActorId(authentication);

		try {

			WikiArticleDTO article = publishWikiArticleUseCase
					.execute(new PublishWikiArticleCommand(articleId, null, actorId));

			redirectAttributes.addFlashAttribute("successMessage",

					"Đã xuất bản bài Wiki \"" + article.title() + "\".");

		} catch (IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage",

					"Không thể xuất bản bài Wiki. " + exception.getMessage());
		}

		return redirectAfterLifecycleAction(articleId, returnTo);
	}

	/*
	 * ===================================================== UNPUBLISH
	 * =====================================================
	 */

	@PostMapping("/{articleId}/unpublish")
	public String unpublishArticle(@PathVariable UUID articleId,
			@RequestParam(name = "returnTo", defaultValue = "list") String returnTo, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		UUID actorId = resolveActorId(authentication);

		try {

			WikiArticleDTO article = unpublishWikiArticleUseCase
					.execute(new UnpublishWikiArticleCommand(articleId, null, actorId));

			redirectAttributes.addFlashAttribute("successMessage",
					"Đã gỡ xuất bản bài Wiki \"" + article.title() + "\". " + "Bài viết đã trở về bản nháp.");

		} catch (IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage",
					"Không thể gỡ xuất bản bài Wiki. " + exception.getMessage());
		}

		return redirectAfterLifecycleAction(articleId, returnTo);
	}

	/*
	 * ===================================================== ARCHIVE
	 * =====================================================
	 */

	@PostMapping("/{articleId}/archive")
	public String archiveArticle(@PathVariable UUID articleId,
			@RequestParam(name = "returnTo", defaultValue = "list") String returnTo, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		UUID actorId = resolveActorId(authentication);

		try {

			WikiArticleDTO article = archiveWikiArticleUseCase
					.execute(new ArchiveWikiArticleCommand(articleId, null, actorId));

			redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ bài Wiki \"" + article.title() + "\".");

		} catch (IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage",
					"Không thể lưu trữ bài Wiki. " + exception.getMessage());
		}

		return redirectAfterLifecycleAction(articleId, returnTo);
	}

	/*
	 * ===================================================== RESTORE REVISION
	 * =====================================================
	 */

	@PostMapping("/{articleId}/revisions/{revisionNumber}/restore")
	public String restoreRevision(@PathVariable UUID articleId,

			@PathVariable long revisionNumber,

			@RequestParam(name = "editSummary", required = false) String editSummary,

			Authentication authentication,

			RedirectAttributes redirectAttributes) {
		UUID actorId = resolveActorId(authentication);

		try {

			WikiArticleDTO article = restoreWikiArticleUseCase.execute(new RestoreWikiArticleCommand(articleId,
					revisionNumber, normalizeNullableText(editSummary), actorId));

			redirectAttributes.addFlashAttribute("successMessage",

					"Đã khôi phục Revision #" + revisionNumber + " của bài Wiki \"" + article.title()
							+ "\" thành bản nháp.");

			return "redirect:/admin/wiki/articles/" + articleId + "/revisions";

		} catch (WikiArticleRevisionAlreadyCurrentException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

			return "redirect:/admin/wiki/articles/" + articleId + "/revisions/" + revisionNumber;
		}
	}

	/*
	 * ===================================================== DELETE
	 * =====================================================
	 */

	@PostMapping("/{articleId}/delete")
	public String deleteArticle(@PathVariable UUID articleId,

			RedirectAttributes redirectAttributes) {
		deleteWikiArticleUseCase.execute(new DeleteWikiArticleCommand(articleId));

		redirectAttributes.addFlashAttribute("successMessage", "Đã xóa bài Wiki.");

		return redirectToArticleList();
	}

	/*
	 * ===================================================== CREATE VALIDATION
	 * =====================================================
	 */

	private void validateCreateForm(CreateWikiArticleForm form) {
		if (form == null) {

			throw new IllegalArgumentException("Form tạo bài Wiki không được để trống.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {

			throw new IllegalArgumentException("Tiêu đề bài Wiki không được để trống.");
		}

		if (form.getArticleType() == null) {

			throw new IllegalArgumentException("Loại bài Wiki không được để trống.");
		}
	}

	/*
	 * ===================================================== EDIT VALIDATION
	 * =====================================================
	 */

	private void validateEditForm(EditWikiArticleForm form) {
		if (form == null) {

			throw new IllegalArgumentException("Form chỉnh sửa bài Wiki không được để trống.");
		}
	}

	/*
	 * Chỉ Draft mới bắt buộc gửi title + articleType từ giao diện.
	 */
	private void validateDraftEditForm(EditWikiArticleForm form) {
		if (form.getTitle() == null || form.getTitle().isBlank()) {

			throw new IllegalArgumentException("Tiêu đề bài Wiki không được để trống.");
		}

		if (form.getArticleType() == null) {

			throw new IllegalArgumentException("Loại bài Wiki không được để trống.");
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

		UserDTO user = userIdentityContract.findByEmail(email).orElseThrow(() ->

		new IllegalStateException("Không tìm thấy người dùng đang đăng nhập."));

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

	/*
	 * CREATE DRAFT
	 */
	private String normalizeEditSummary(String value) {
		if (value == null || value.isBlank()) {

			return "Tạo bản nháp đầu tiên";
		}

		return value.trim();
	}

	/*
	 * CREATE + PUBLISH
	 */
	private String normalizePublishEditSummary(String value) {
		if (value == null || value.isBlank()) {

			return "Tạo và xuất bản bài viết";
		}

		return value.trim();
	}

	/*
	 * UPDATE DRAFT
	 */
	private String normalizeDraftUpdateEditSummary(String value) {
		if (value == null || value.isBlank()) {

			return "Cập nhật bản nháp";
		}

		return value.trim();
	}

	/*
	 * UPDATE PUBLISHED
	 */
	private String normalizePublishedUpdateEditSummary(String value) {
		if (value == null || value.isBlank()) {

			return "Cập nhật nội dung bài viết đã xuất bản";
		}

		return value.trim();
	}

	/*
	 * ===================================================== REDIRECT
	 * =====================================================
	 */

	private String redirectToArticleList() {

		return "redirect:/admin/wiki/articles";
	}

	private String redirectToArticleDetail(UUID articleId) {

		return "redirect:/admin/wiki/articles/" + articleId;
	}

	private String normalizeNullableText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		return value.trim();
	}

	private String redirectAfterLifecycleAction(UUID articleId, String returnTo) {
		if ("detail".equalsIgnoreCase(returnTo)) {
			return redirectToArticleDetail(articleId);
		}

		return redirectToArticleList();
	}
}