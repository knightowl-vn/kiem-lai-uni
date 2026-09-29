package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.wiki.application.article.alias.AddWikiArticleAliasCommand;
import com.universe.wiki.application.article.alias.AddWikiArticleAliasUseCase;
import com.universe.wiki.application.article.alias.RemoveWikiArticleAliasCommand;
import com.universe.wiki.application.article.alias.RemoveWikiArticleAliasUseCase;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;

import com.universe.wiki.application.article.cover.WikiArticleCoverOrchestrator;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.entry.admin.form.CreateWikiArticleAction;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleAction;
import com.universe.wiki.application.article.archive.ArchiveWikiArticleCommand;
import com.universe.wiki.application.article.archive.ArchiveWikiArticleUseCase;

import com.universe.wiki.application.article.publish.PublishWikiArticleCommand;
import com.universe.wiki.application.article.publish.PublishWikiArticleUseCase;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.restore.RestoreWikiArticleCommand;
import com.universe.wiki.application.article.restore.RestoreWikiArticleUseCase;
import com.universe.wiki.application.article.unpublish.UnpublishWikiArticleCommand;
import com.universe.wiki.application.article.unpublish.UnpublishWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleCommand;

import com.universe.shared.security.AuthenticatedEmailResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiArticleCommandControllerTest {

	private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final String ADMIN_EMAIL = "admin@example.com";

	private static final Instant NOW = Instant.parse("2026-08-07T02:00:00Z");

	@Mock
	private WikiArticleCoverOrchestrator wikiArticleCoverOrchestrator;

	@Mock
	private UserIdentityContract userIdentityContract;

	@Mock
	private Authentication authentication;

	private AuthenticatedEmailResolver authenticatedEmailResolver;

	@Mock
	private PublishWikiArticleUseCase publishWikiArticleUseCase;

	@Mock
	private UnpublishWikiArticleUseCase unpublishWikiArticleUseCase;

	@Mock
	private ArchiveWikiArticleUseCase archiveWikiArticleUseCase;

	@Mock
	private GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;

	@Mock
	private RestoreWikiArticleUseCase restoreWikiArticleUseCase;

	@Mock
	private AddWikiArticleAliasUseCase addWikiArticleAliasUseCase;

	@Mock
	private RemoveWikiArticleAliasUseCase removeWikiArticleAliasUseCase;

	private AdminWikiArticleCommandController controller;

	@BeforeEach
	void setUp() {
		authenticatedEmailResolver = new AuthenticatedEmailResolver();

		controller = new AdminWikiArticleCommandController(
				wikiArticleCoverOrchestrator,
				getWikiArticleDetailUseCase,
				publishWikiArticleUseCase,
				unpublishWikiArticleUseCase,
				archiveWikiArticleUseCase,
				restoreWikiArticleUseCase,
				addWikiArticleAliasUseCase,
				removeWikiArticleAliasUseCase,
				authenticatedEmailResolver,
				userIdentityContract);
	}

	/*
	 * ===================================================== SAVE DRAFT
	 * =====================================================
	 */

	@Test
	@DisplayName("Lưu bài Wiki mới dưới dạng DRAFT")
	void shouldCreateWikiDraft() {
		CreateWikiArticleForm form = createValidForm();

		prepareAuthenticatedAdmin();

		when(wikiArticleCoverOrchestrator.createDraft(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID),
				null))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu bản nháp Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);

		verify(wikiArticleCoverOrchestrator).createDraft(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID),
				null);

		verify(wikiArticleCoverOrchestrator, never()).createAndPublish(any(CreateAndPublishWikiArticleCommand.class), any());
	}

	/*
	 * ===================================================== PUBLISH IMMEDIATELY
	 * =====================================================
	 */

	@Test
	@DisplayName("Tạo và xuất bản bài Wiki ngay lập tức")
	void shouldCreateAndPublishWikiArticle() {
		CreateWikiArticleForm form = createValidForm();

		prepareAuthenticatedAdmin();

		when(wikiArticleCoverOrchestrator.createAndPublish(new CreateAndPublishWikiArticleCommand("Trần Bình An",
				ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.",
				"Khởi tạo bài Trần Bình An", ADMIN_ID), null)).thenReturn(createPublishedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.PUBLISH, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xuất bản bài Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);

		verify(wikiArticleCoverOrchestrator).createAndPublish(new CreateAndPublishWikiArticleCommand("Trần Bình An",
				ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.",
				"Khởi tạo bài Trần Bình An", ADMIN_ID), null);

		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());
	}

	/*
	 * ===================================================== VALIDATION
	 * =====================================================
	 */

	@Test
	@DisplayName("Từ chối tạo bài khi tiêu đề để trống")
	void shouldRejectBlankTitle() {
		CreateWikiArticleForm form = new CreateWikiArticleForm();

		form.setTitle("   ");

		form.setArticleType(ArticleType.CHARACTER);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap())).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Tiêu đề bài Wiki không được để trống.");

		verify(userIdentityContract, never()).findByEmail(any());

		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());

		verify(wikiArticleCoverOrchestrator, never()).createAndPublish(any(CreateAndPublishWikiArticleCommand.class), any());
	}

	@Test
	@DisplayName("Từ chối khi không tìm thấy người dùng đang đăng nhập")
	void shouldRejectWhenAuthenticatedUserDoesNotExist() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap())).isInstanceOf(IllegalStateException.class)
				.hasMessage("Không tìm thấy người dùng đang đăng nhập.");

		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());

		verify(wikiArticleCoverOrchestrator, never()).createAndPublish(any(CreateAndPublishWikiArticleCommand.class), any());
	}

	/*
	 * ===================================================== UPDATE DRAFT
	 * =====================================================
	 */

	@Test
	@DisplayName("Chỉnh sửa bài Wiki DRAFT bằng UpdateDraftWikiArticleUseCase")
	void shouldUpdateDraftWikiArticle() {

		EditWikiArticleForm form = new EditWikiArticleForm();

		form.setTitle("  Trần Bình An cập nhật  ");

		form.setArticleType(ArticleType.CHARACTER);

		form.setSummary("  Tóm tắt mới  ");

		form.setContent("  Nội dung mới  ");

		form.setEditSummary("  Cập nhật bản nháp  ");

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createDraftArticleDTO());

		WikiArticleDTO updatedArticle = new WikiArticleDTO(ARTICLE_ID, "Trần Bình An cập nhật", "tran-binh-an-cap-nhat",
				"CHARACTER", "Tóm tắt mới", "Nội dung mới", "DRAFT", ADMIN_ID, ADMIN_ID, null, null, NOW, NOW, null,
				null, 2L, 2L);

		when(wikiArticleCoverOrchestrator
				.updateDraft(new UpdateDraftWikiArticleCommand(ARTICLE_ID, "Trần Bình An cập nhật", ArticleType.CHARACTER,
						"Tóm tắt mới", "Nội dung mới", "Cập nhật bản nháp", ADMIN_ID), null, false))
				.thenReturn(updatedArticle);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã cập nhật bài Wiki \"Trần Bình An cập nhật\".");

		verify(wikiArticleCoverOrchestrator)
				.updateDraft(new UpdateDraftWikiArticleCommand(ARTICLE_ID, "Trần Bình An cập nhật", ArticleType.CHARACTER,
						"Tóm tắt mới", "Nội dung mới", "Cập nhật bản nháp", ADMIN_ID), null, false);

		verify(wikiArticleCoverOrchestrator, never())
				.updateDraftAndPublish(any(UpdateDraftAndPublishWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updatePublished(any(UpdatePublishedWikiArticleCommand.class), any(), anyBoolean());
	}

	/*
	 * ===================================================== UPDATE DRAFT + PUBLISH
	 * =====================================================
	 */

	@Test
	@DisplayName("Lưu thay đổi và xuất bản bài Wiki DRAFT trong cùng một hành động")
	void shouldUpdateDraftAndPublishWikiArticle() {

		EditWikiArticleForm form = new EditWikiArticleForm();

		form.setTitle("  Trần Bình An hoàn thiện  ");

		form.setArticleType(ArticleType.CHARACTER);

		form.setSummary("  Tóm tắt hoàn thiện  ");

		form.setContent("  Nội dung hoàn thiện để xuất bản  ");

		form.setEditSummary("  Hoàn thiện và xuất bản  ");

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createDraftArticleDTO());

		WikiArticleDTO publishedArticle = new WikiArticleDTO(ARTICLE_ID, "Trần Bình An hoàn thiện",
				"tran-binh-an-hoan-thien", "CHARACTER", "Tóm tắt hoàn thiện", "Nội dung hoàn thiện để xuất bản",
				"PUBLISHED", ADMIN_ID, ADMIN_ID, ADMIN_ID, null, NOW, NOW, NOW, null, 2L, 2L);

		when(wikiArticleCoverOrchestrator.updateDraftAndPublish(new UpdateDraftAndPublishWikiArticleCommand(ARTICLE_ID,
				"Trần Bình An hoàn thiện", ArticleType.CHARACTER, "Tóm tắt hoàn thiện",
				"Nội dung hoàn thiện để xuất bản", "Hoàn thiện và xuất bản", ADMIN_ID), null, false)).thenReturn(publishedArticle);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_AND_PUBLISH,
				authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu thay đổi và xuất bản bài Wiki " + "\"Trần Bình An hoàn thiện\".");

		assertThat(redirectAttributes.getFlashAttributes().get("wikiAutosaveCleanupKey"))
				.isEqualTo("kiemlai:wiki:autosave:edit:" + ARTICLE_ID);

		verify(wikiArticleCoverOrchestrator).updateDraftAndPublish(new UpdateDraftAndPublishWikiArticleCommand(ARTICLE_ID,
				"Trần Bình An hoàn thiện", ArticleType.CHARACTER, "Tóm tắt hoàn thiện",
				"Nội dung hoàn thiện để xuất bản", "Hoàn thiện và xuất bản", ADMIN_ID), null, false);

		verify(wikiArticleCoverOrchestrator, never()).updateDraft(any(UpdateDraftWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updatePublished(any(UpdatePublishedWikiArticleCommand.class), any(), anyBoolean());
	}

	@Test
	@DisplayName("Không cho SAVE_AND_PUBLISH khi bài Wiki đã PUBLISHED")
	void shouldRejectSaveAndPublishForPublishedArticle() {

		EditWikiArticleForm form = new EditWikiArticleForm();

		form.setSummary("Tóm tắt");

		form.setContent("Nội dung");

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createPublishedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_AND_PUBLISH,
				authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID + "/edit");

		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Bài Wiki đã được xuất bản.");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isNull();

		verify(wikiArticleCoverOrchestrator, never())
				.updateDraftAndPublish(any(UpdateDraftAndPublishWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updateDraft(any(UpdateDraftWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updatePublished(any(UpdatePublishedWikiArticleCommand.class), any(), anyBoolean());
	}

	/*
	 * ===================================================== UPDATE PUBLISHED
	 * =====================================================
	 */

	@Test
	@DisplayName("Chỉnh sửa bài Wiki PUBLISHED bằng UpdatePublishedWikiArticleUseCase")
	void shouldUpdatePublishedWikiArticle() {

		EditWikiArticleForm form = new EditWikiArticleForm();

		/*
		 * Cố tình truyền title/type khác.
		 *
		 * Controller phải bỏ qua hai field này khi bài đang PUBLISHED.
		 */
		form.setTitle("Tên giả từ browser");

		form.setArticleType(ArticleType.LOCATION);

		form.setSummary("  Tóm tắt published mới  ");

		form.setContent("  Nội dung published mới  ");

		form.setEditSummary("  Bổ sung nội dung  ");

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createPublishedArticleDTO());

		WikiArticleDTO updatedArticle = new WikiArticleDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER",
				"Tóm tắt published mới", "Nội dung published mới", "PUBLISHED", ADMIN_ID, ADMIN_ID, ADMIN_ID, null, NOW,
				NOW, NOW, null, 2L, 2L);

		when(wikiArticleCoverOrchestrator.updatePublished(new UpdatePublishedWikiArticleCommand(ARTICLE_ID,
				"Tóm tắt published mới", "Nội dung published mới", "Bổ sung nội dung", ADMIN_ID), null, false))
				.thenReturn(updatedArticle);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);

		verify(wikiArticleCoverOrchestrator).updatePublished(new UpdatePublishedWikiArticleCommand(ARTICLE_ID,
				"Tóm tắt published mới", "Nội dung published mới", "Bổ sung nội dung", ADMIN_ID), null, false);

		verify(wikiArticleCoverOrchestrator, never())
				.updateDraftAndPublish(any(UpdateDraftAndPublishWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updateDraft(any(UpdateDraftWikiArticleCommand.class), any(), anyBoolean());
	}

	@Test
	@DisplayName("Chỉnh sửa bài Wiki PUBLISHED với sourceContributionId sẽ chuyển tiếp ID và redirect về chi tiết đóng góp")
	void shouldUpdatePublishedWikiArticleWithSourceContributionId() {
		UUID sourceContributionId = UUID.randomUUID();
		EditWikiArticleForm form = new EditWikiArticleForm();
		form.setSummary("Tóm tắt theo đóng góp");
		form.setContent("Nội dung theo đóng góp");
		form.setEditSummary("Cập nhật theo ý kiến độc giả");
		form.setSourceContributionId(sourceContributionId);

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createPublishedArticleDTO());

		WikiArticleDTO updatedArticle = new WikiArticleDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER",
				"Tóm tắt theo đóng góp", "Nội dung theo đóng góp", "PUBLISHED", ADMIN_ID, ADMIN_ID, ADMIN_ID, null, NOW,
				NOW, NOW, null, 2L, 2L);

		when(wikiArticleCoverOrchestrator.updatePublished(new UpdatePublishedWikiArticleCommand(ARTICLE_ID,
				"Tóm tắt theo đóng góp", "Nội dung theo đóng góp", "Cập nhật theo ý kiến độc giả", ADMIN_ID, 50, 50, sourceContributionId), null, false))
				.thenReturn(updatedArticle);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/contributions/" + sourceContributionId);

		verify(wikiArticleCoverOrchestrator).updatePublished(new UpdatePublishedWikiArticleCommand(ARTICLE_ID,
				"Tóm tắt theo đóng góp", "Nội dung theo đóng góp", "Cập nhật theo ý kiến độc giả", ADMIN_ID, 50, 50, sourceContributionId), null, false);
	}

	/*
	 * ===================================================== UPDATE ARCHIVED
	 * =====================================================
	 */

	@Test
	@DisplayName("Không cho phép chỉnh sửa trực tiếp bài Wiki ARCHIVED")
	void shouldRejectUpdateArchivedWikiArticle() {

		EditWikiArticleForm form = new EditWikiArticleForm();

		form.setSummary("Tóm tắt mới");

		form.setContent("Nội dung mới");

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createArchivedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Bài Wiki đã lưu trữ không thể chỉnh sửa trực tiếp.");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isNull();
		verify(wikiArticleCoverOrchestrator, never())
				.updateDraftAndPublish(any(UpdateDraftAndPublishWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updateDraft(any(UpdateDraftWikiArticleCommand.class), any(), anyBoolean());

		verify(wikiArticleCoverOrchestrator, never()).updatePublished(any(UpdatePublishedWikiArticleCommand.class), any(), anyBoolean());
	}

	@Test
	@DisplayName("Khôi phục một revision cũ của bài Wiki thành DRAFT")
	void shouldRestoreWikiArticleRevision() {

		long revisionNumber = 3L;

		prepareAuthenticatedAdmin();

		WikiArticleDTO restoredArticle = createDraftArticleDTO();

		when(restoreWikiArticleUseCase
				.execute(new RestoreWikiArticleCommand(ARTICLE_ID, revisionNumber, "Khôi phục nội dung cũ", ADMIN_ID)))
				.thenReturn(restoredArticle);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.restoreRevision(ARTICLE_ID, revisionNumber, "  Khôi phục nội dung cũ  ",
				authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID + "/revisions");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã khôi phục Revision #3 " + "của bài Wiki \"Trần Bình An\" " + "thành bản nháp.");

		verify(restoreWikiArticleUseCase)
				.execute(new RestoreWikiArticleCommand(ARTICLE_ID, revisionNumber, "Khôi phục nội dung cũ", ADMIN_ID));
	}

	@Test
	@DisplayName("Cho phép khôi phục revision mà không nhập ghi chú")
	void shouldRestoreRevisionWithoutEditSummary() {

		long revisionNumber = 2L;

		prepareAuthenticatedAdmin();

		when(restoreWikiArticleUseCase
				.execute(new RestoreWikiArticleCommand(ARTICLE_ID, revisionNumber, null, ADMIN_ID)))
				.thenReturn(createDraftArticleDTO());

		String result = controller.restoreRevision(ARTICLE_ID, revisionNumber, "   ", authentication,
				new RedirectAttributesModelMap());

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID + "/revisions");

		verify(restoreWikiArticleUseCase)
				.execute(new RestoreWikiArticleCommand(ARTICLE_ID, revisionNumber, null, ADMIN_ID));
	}

	/*
	 * ===================================================== TEST DATA
	 * =====================================================
	 */

	private CreateWikiArticleForm createValidForm() {
		CreateWikiArticleForm form = new CreateWikiArticleForm();

		/*
		 * Cố tình có khoảng trắng để kiểm tra Controller normalize dữ liệu.
		 */
		form.setTitle("  Trần Bình An  ");

		form.setArticleType(ArticleType.CHARACTER);

		form.setSummary("  Nhân vật chính của Kiếm Lai.  ");

		form.setContent("  Nội dung ban đầu của bài viết.  ");

		form.setEditSummary("  Khởi tạo bài Trần Bình An  ");

		return form;
	}

	private UserDTO createAdminDTO() {
		return new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin Wiki", null, "admin_wiki", "ACTIVE", "ADMIN", NOW);
	}

	private WikiArticleDTO createDraftArticleDTO() {
		return new WikiArticleDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER",
				"Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.", "DRAFT", ADMIN_ID, ADMIN_ID, null,
				null, NOW, NOW, null, null, 1L, 1L);
	}

	private WikiArticleDTO createPublishedArticleDTO() {
		return new WikiArticleDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER",
				"Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.", "PUBLISHED", ADMIN_ID, ADMIN_ID,
				ADMIN_ID, null, NOW, NOW, NOW, null, 1L, 1L);
	}

	@Test
	@DisplayName("Xuất bản bài DRAFT từ trang quản trị")
	void shouldPublishExistingDraft() {
		prepareAuthenticatedAdmin();

		when(publishWikiArticleUseCase.execute(new PublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID)))
				.thenReturn(createPublishedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.publishArticle(ARTICLE_ID, "list", authentication, redirectAttributes);
		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xuất bản bài Wiki \"Trần Bình An\".");

		verify(publishWikiArticleUseCase).execute(new PublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID));
	}

	@Test
	@DisplayName("Gỡ xuất bản bài PUBLISHED về DRAFT")
	void shouldUnpublishPublishedArticle() {
		prepareAuthenticatedAdmin();

		when(unpublishWikiArticleUseCase.execute(new UnpublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID)))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.unpublishArticle(ARTICLE_ID, "list", authentication, redirectAttributes);
		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã gỡ xuất bản bài Wiki \"Trần Bình An\". " + "Bài viết đã trở về bản nháp.");

		verify(unpublishWikiArticleUseCase).execute(new UnpublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID));
	}

	@Test
	@DisplayName("Lưu trữ bài Wiki từ trang quản trị")
	void shouldArchiveArticle() {
		prepareAuthenticatedAdmin();

		when(archiveWikiArticleUseCase.execute(new ArchiveWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID)))
				.thenReturn(createArchivedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.archiveArticle(ARTICLE_ID, "list", authentication, redirectAttributes);
		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu trữ bài Wiki \"Trần Bình An\".");

		verify(archiveWikiArticleUseCase).execute(new ArchiveWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID));
	}

	@Test
	@DisplayName("Xóa bài Wiki từ trang quản trị")
	void shouldDeleteArticle() {
		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.deleteArticle(ARTICLE_ID, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Đã xóa bài Wiki.");

		verify(wikiArticleCoverOrchestrator).deleteArticle(ARTICLE_ID);
	}

	@Test
	@DisplayName("Publish không hợp lệ phải quay lại danh sách và hiển thị lỗi")
	void shouldRedirectWithErrorMessageWhenPublishFails() {

		prepareAuthenticatedAdmin();

		when(publishWikiArticleUseCase.execute(new PublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID)))
				.thenThrow(new IllegalStateException("Bài viết phải có nội dung trước khi xuất bản."));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.publishArticle(ARTICLE_ID, "list", authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Không thể xuất bản bài Wiki. " + "Bài viết phải có nội dung trước khi xuất bản.");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isNull();

		verify(publishWikiArticleUseCase).execute(new PublishWikiArticleCommand(ARTICLE_ID, null, ADMIN_ID));
	}

	@Test
	@DisplayName("Thêm alias thành công quay lại trang chi tiết và hiển thị flash message thành công")
	void shouldAddAliasSuccessfullyAndRedirectToDetail() {
		WikiArticleAliasDTO aliasDTO = new WikiArticleAliasDTO(
				UUID.randomUUID(), ARTICLE_ID, "Tiểu Phu Tử", "tiểu phu tử", Instant.now()
		);
		when(addWikiArticleAliasUseCase.execute(new AddWikiArticleAliasCommand(ARTICLE_ID, "Tiểu Phu Tử")))
				.thenReturn(aliasDTO);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.addAlias(ARTICLE_ID, "Tiểu Phu Tử", redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã thêm danh xưng/biệt danh \"Tiểu Phu Tử\".");
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isNull();

		verify(addWikiArticleAliasUseCase).execute(new AddWikiArticleAliasCommand(ARTICLE_ID, "Tiểu Phu Tử"));
	}

	@Test
	@DisplayName("Thêm alias thất bại do validation hoặc không tìm thấy bài viết sẽ quay lại trang chi tiết kèm flash error")
	void shouldRedirectWithErrorMessageWhenAddAliasFails() {
		when(addWikiArticleAliasUseCase.execute(new AddWikiArticleAliasCommand(ARTICLE_ID, "")))
				.thenThrow(new IllegalArgumentException("Danh xưng/biệt danh phải có độ dài từ 1 đến 200 ký tự."));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.addAlias(ARTICLE_ID, "", redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Danh xưng/biệt danh phải có độ dài từ 1 đến 200 ký tự.");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isNull();

		verify(addWikiArticleAliasUseCase).execute(new AddWikiArticleAliasCommand(ARTICLE_ID, ""));
	}

	@Test
	@DisplayName("Xóa alias thành công quay lại trang chi tiết và hiển thị flash message thành công")
	void shouldRemoveAliasSuccessfullyAndRedirectToDetail() {
		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.removeAlias(ARTICLE_ID, "Tiểu Phu Tử", redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xóa danh xưng/biệt danh.");
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isNull();

		verify(removeWikiArticleAliasUseCase).execute(new RemoveWikiArticleAliasCommand(ARTICLE_ID, "Tiểu Phu Tử"));
	}

	@Test
	@DisplayName("Xóa alias thất bại do bài viết không tồn tại sẽ quay lại trang chi tiết kèm flash error")
	void shouldRedirectWithErrorMessageWhenRemoveAliasFails() {
		doThrow(new WikiArticleNotFoundException(ARTICLE_ID))
				.when(removeWikiArticleAliasUseCase).execute(new RemoveWikiArticleAliasCommand(ARTICLE_ID, "Tiểu Phu Tử"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.removeAlias(ARTICLE_ID, "Tiểu Phu Tử", redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Không tìm thấy bài viết Wiki: " + ARTICLE_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isNull();

		verify(removeWikiArticleAliasUseCase).execute(new RemoveWikiArticleAliasCommand(ARTICLE_ID, "Tiểu Phu Tử"));
	}

	/*
	 * ===================================================== ACTOR RESOLUTION TESTS
	 * =====================================================
	 */

	@Test
	@DisplayName("Tạo bài Wiki thành công khi Admin đăng nhập bằng OAuth2 (Google) với subject ID số và email attribute")
	void shouldCreateWikiDraftWhenAuthenticatedViaOAuth2() {
		CreateWikiArticleForm form = createValidForm();

		OAuth2User oauth2User = mock(OAuth2User.class);
		when(oauth2User.getAttribute("email")).thenReturn(ADMIN_EMAIL);

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getPrincipal()).thenReturn(oauth2User);
		lenient().when(authentication.getName()).thenReturn("104829374019283746152");

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));
		when(wikiArticleCoverOrchestrator.createDraft(any(CreateWikiArticleCommand.class), any()))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu bản nháp Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
		verify(wikiArticleCoverOrchestrator).createDraft(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID), null);
	}

	@Test
	@DisplayName("Tạo bài Wiki thành công khi Admin đăng nhập bằng form login chuẩn với email principal")
	void shouldCreateWikiDraftWhenAuthenticatedViaFormLogin() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);
		when(authentication.getPrincipal()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));
		when(wikiArticleCoverOrchestrator.createDraft(any(CreateWikiArticleCommand.class), any()))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu bản nháp Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
		verify(wikiArticleCoverOrchestrator).createDraft(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID), null);
	}

	@Test
	@DisplayName("Từ chối tạo bài khi Authentication là null")
	void shouldRejectWhenAuthenticationIsNull() {
		CreateWikiArticleForm form = createValidForm();

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, null,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());
	}

	@Test
	@DisplayName("Từ chối tạo bài khi Authentication là AnonymousAuthenticationToken")
	void shouldRejectWhenAuthenticationIsAnonymous() {
		CreateWikiArticleForm form = createValidForm();

		Authentication anonymousAuth = mock(AnonymousAuthenticationToken.class);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, anonymousAuth,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());
	}

	@Test
	@DisplayName("Từ chối tạo bài khi Authentication chưa được xác thực (isAuthenticated = false)")
	void shouldRejectWhenAuthenticationIsNotAuthenticated() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.isAuthenticated()).thenReturn(false);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());
	}

	@Test
	@DisplayName("Từ chối tạo bài khi OAuth2 principal không có email attribute")
	void shouldRejectWhenOAuth2UserHasNoEmailAttribute() {
		CreateWikiArticleForm form = createValidForm();

		OAuth2User oauth2User = mock(OAuth2User.class);
		when(oauth2User.getAttribute("email")).thenReturn(null);

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getPrincipal()).thenReturn(oauth2User);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(CreateWikiArticleCommand.class), any());
	}

	@Test
	@DisplayName("Tạo bản nháp bài Wiki kèm ảnh bìa hợp lệ")
	void shouldCreateWikiDraftWithCoverImage() {
		CreateWikiArticleForm form = createValidForm();
		org.springframework.mock.web.MockMultipartFile coverFile =
				new org.springframework.mock.web.MockMultipartFile("coverImageFile", "cover.webp", "image/webp", new byte[]{1, 2, 3, 4});
		form.setCoverImageFile(coverFile);

		prepareAuthenticatedAdmin();

		when(wikiArticleCoverOrchestrator.createDraft(any(CreateWikiArticleCommand.class), any()))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu bản nháp Wiki \"Trần Bình An\".");

		verify(wikiArticleCoverOrchestrator).createDraft(
				org.mockito.ArgumentMatchers.eq(new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID)),
				org.mockito.ArgumentMatchers.argThat(upload -> upload != null
						&& upload.sizeBytes() == 4
						&& "image/webp".equals(upload.contentType())
						&& "cover.webp".equals(upload.originalFilename()))
		);
	}

	@Test
	@DisplayName("Từ chối tạo bài Wiki khi ảnh bìa vượt quá 5MB")
	void shouldRejectCoverImageOver5MB() {
		CreateWikiArticleForm form = createValidForm();
		byte[] largeBytes = new byte[5 * 1024 * 1024 + 1];
		org.springframework.mock.web.MockMultipartFile coverFile =
				new org.springframework.mock.web.MockMultipartFile("coverImageFile", "large.png", "image/png", largeBytes);
		form.setCoverImageFile(coverFile);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Kích thước ảnh bìa không được vượt quá 5MB.");

		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(), any());
	}

	@Test
	@DisplayName("Từ chối tạo bài Wiki khi định dạng ảnh bìa không được hỗ trợ")
	void shouldRejectUnsupportedCoverImageType() {
		CreateWikiArticleForm form = createValidForm();
		org.springframework.mock.web.MockMultipartFile coverFile =
				new org.springframework.mock.web.MockMultipartFile("coverImageFile", "anim.gif", "image/gif", new byte[]{1, 2, 3});
		form.setCoverImageFile(coverFile);

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Định dạng ảnh bìa không được hỗ trợ. Chỉ chấp nhận JPG, PNG hoặc WebP.");

		verify(wikiArticleCoverOrchestrator, never()).createDraft(any(), any());
	}

	@Test
	@DisplayName("Từ chối cập nhật bài Wiki khi vừa chọn xóa ảnh bìa vừa tải lên ảnh bìa mới")
	void shouldRejectSimultaneousCoverRemovalAndUploadInUpdate() {
		EditWikiArticleForm form = new EditWikiArticleForm();
		form.setTitle("Tiêu đề");
		form.setArticleType(ArticleType.CHARACTER);
		form.setRemoveCover(true);
		org.springframework.mock.web.MockMultipartFile coverFile =
				new org.springframework.mock.web.MockMultipartFile("coverImageFile", "new.jpg", "image/jpeg", new byte[]{1, 2});
		form.setCoverImageFile(coverFile);

		assertThatThrownBy(() -> controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES,
				authentication, new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Không thể đồng thời vừa xóa ảnh bìa vừa tải lên ảnh bìa mới.");

		verify(wikiArticleCoverOrchestrator, never()).updateDraft(any(), any(), anyBoolean());
		verify(wikiArticleCoverOrchestrator, never()).updatePublished(any(), any(), anyBoolean());
	}

	@Test
	@DisplayName("Cập nhật bài Wiki với cờ xóa ảnh bìa")
	void shouldUpdateDraftWithCoverRemoval() {
		EditWikiArticleForm form = new EditWikiArticleForm();
		form.setTitle("Trần Bình An");
		form.setArticleType(ArticleType.CHARACTER);
		form.setSummary("Tóm tắt");
		form.setContent("Nội dung");
		form.setEditSummary("Gỡ ảnh bìa");
		form.setRemoveCover(true);

		prepareAuthenticatedAdmin();

		when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
				.thenReturn(createDraftArticleDTO());

		WikiArticleDTO updated = createDraftArticleDTO();
		when(wikiArticleCoverOrchestrator.updateDraft(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(true)))
				.thenReturn(updated);

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.updateArticle(ARTICLE_ID, form, EditWikiArticleAction.SAVE_CHANGES,
				authentication, redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles/" + ARTICLE_ID);
		verify(wikiArticleCoverOrchestrator).updateDraft(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(true));
	}

	private void prepareAuthenticatedAdmin() {
		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));
	}

	private WikiArticleDTO createArchivedArticleDTO() {

		return new WikiArticleDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER",
				"Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.", "ARCHIVED", ADMIN_ID, ADMIN_ID,
				ADMIN_ID, ADMIN_ID, NOW, NOW, NOW, NOW, 3L, 1L);
	}
}