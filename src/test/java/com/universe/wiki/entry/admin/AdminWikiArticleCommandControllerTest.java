package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.entry.admin.form.CreateWikiArticleAction;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleAction;
import com.universe.wiki.application.article.archive.ArchiveWikiArticleCommand;
import com.universe.wiki.application.article.archive.ArchiveWikiArticleUseCase;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.core.Authentication;

import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;

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
	private CreateWikiArticleUseCase createWikiArticleUseCase;

	@Mock
	private CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase;

	@Mock
	private UserIdentityContract userIdentityContract;

	@Mock
	private Authentication authentication;
	@Mock
	private PublishWikiArticleUseCase publishWikiArticleUseCase;

	@Mock
	private UnpublishWikiArticleUseCase unpublishWikiArticleUseCase;

	@Mock
	private ArchiveWikiArticleUseCase archiveWikiArticleUseCase;

	@Mock
	private DeleteWikiArticleUseCase deleteWikiArticleUseCase;

	@Mock
	private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

	@Mock
	private UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase;

	@Mock
	private UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase;

	@Mock
	private GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;
	
	@Mock
	private RestoreWikiArticleUseCase
	        restoreWikiArticleUseCase;

	private AdminWikiArticleCommandController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminWikiArticleCommandController(
		        createWikiArticleUseCase,
		        createAndPublishWikiArticleUseCase,

		        updateDraftWikiArticleUseCase,
		        updateDraftAndPublishWikiArticleUseCase,
		        updatePublishedWikiArticleUseCase,

		        getWikiArticleDetailUseCase,

		        publishWikiArticleUseCase,
		        unpublishWikiArticleUseCase,
		        archiveWikiArticleUseCase,
		        restoreWikiArticleUseCase,
		        deleteWikiArticleUseCase,

		        userIdentityContract
		);
	}

	/*
	 * ===================================================== SAVE DRAFT
	 * =====================================================
	 */

	@Test
	@DisplayName("Lưu bài Wiki mới dưới dạng DRAFT")
	void shouldCreateWikiDraft() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));

		when(createWikiArticleUseCase.execute(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID)))
				.thenReturn(createDraftArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu bản nháp Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);

		verify(createWikiArticleUseCase).execute(
				new CreateWikiArticleCommand("Trần Bình An", ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.",
						"Nội dung ban đầu của bài viết.", "Khởi tạo bài Trần Bình An", ADMIN_ID));

		verify(createAndPublishWikiArticleUseCase, never()).execute(any(CreateAndPublishWikiArticleCommand.class));
	}

	/*
	 * ===================================================== PUBLISH IMMEDIATELY
	 * =====================================================
	 */

	@Test
	@DisplayName("Tạo và xuất bản bài Wiki ngay lập tức")
	void shouldCreateAndPublishWikiArticle() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));

		when(createAndPublishWikiArticleUseCase.execute(new CreateAndPublishWikiArticleCommand("Trần Bình An",
				ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.",
				"Khởi tạo bài Trần Bình An", ADMIN_ID))).thenReturn(createPublishedArticleDTO());

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String result = controller.createArticle(form, CreateWikiArticleAction.PUBLISH, authentication,
				redirectAttributes);

		assertThat(result).isEqualTo("redirect:/admin/wiki/articles");

		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xuất bản bài Wiki \"Trần Bình An\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);

		verify(createAndPublishWikiArticleUseCase).execute(new CreateAndPublishWikiArticleCommand("Trần Bình An",
				ArticleType.CHARACTER, "Nhân vật chính của Kiếm Lai.", "Nội dung ban đầu của bài viết.",
				"Khởi tạo bài Trần Bình An", ADMIN_ID));

		verify(createWikiArticleUseCase, never()).execute(any(CreateWikiArticleCommand.class));
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

		verify(createWikiArticleUseCase, never()).execute(any(CreateWikiArticleCommand.class));

		verify(createAndPublishWikiArticleUseCase, never()).execute(any(CreateAndPublishWikiArticleCommand.class));
	}

	@Test
	@DisplayName("Từ chối khi không tìm thấy người dùng đang đăng nhập")
	void shouldRejectWhenAuthenticatedUserDoesNotExist() {
		CreateWikiArticleForm form = createValidForm();

		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.createArticle(form, CreateWikiArticleAction.SAVE_DRAFT, authentication,
				new RedirectAttributesModelMap())).isInstanceOf(IllegalStateException.class)
				.hasMessage("Không tìm thấy người dùng đang đăng nhập.");

		verify(createWikiArticleUseCase, never()).execute(any(CreateWikiArticleCommand.class));

		verify(createAndPublishWikiArticleUseCase, never()).execute(any(CreateAndPublishWikiArticleCommand.class));
	}
	
	/*
	 * =====================================================
	 * UPDATE DRAFT
	 * =====================================================
	 */

	@Test
	@DisplayName(
	        "Chỉnh sửa bài Wiki DRAFT bằng UpdateDraftWikiArticleUseCase"
	)
	void shouldUpdateDraftWikiArticle() {

	    EditWikiArticleForm form =
	            new EditWikiArticleForm();

	    form.setTitle(
	            "  Trần Bình An cập nhật  "
	    );

	    form.setArticleType(
	            ArticleType.CHARACTER
	    );

	    form.setSummary(
	            "  Tóm tắt mới  "
	    );

	    form.setContent(
	            "  Nội dung mới  "
	    );

	    form.setEditSummary(
	            "  Cập nhật bản nháp  "
	    );


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            ARTICLE_ID
	                    )
	            )
	    ).thenReturn(
	            createDraftArticleDTO()
	    );


	    WikiArticleDTO updatedArticle =
	            new WikiArticleDTO(
	                    ARTICLE_ID,
	                    "Trần Bình An cập nhật",
	                    "tran-binh-an-cap-nhat",
	                    "CHARACTER",
	                    "Tóm tắt mới",
	                    "Nội dung mới",
	                    "DRAFT",
	                    ADMIN_ID,
	                    ADMIN_ID,
	                    null,
	                    null,
	                    NOW,
	                    NOW,
	                    null,
	                    null,
	                    2L,
	                    2L
	            );


	    when(
	            updateDraftWikiArticleUseCase.execute(
	                    new UpdateDraftWikiArticleCommand(
	                            ARTICLE_ID,
	                            "Trần Bình An cập nhật",
	                            ArticleType.CHARACTER,
	                            "Tóm tắt mới",
	                            "Nội dung mới",
	                            "Cập nhật bản nháp",
	                            ADMIN_ID
	                    )
	            )
	    ).thenReturn(
	            updatedArticle
	    );


	    RedirectAttributesModelMap redirectAttributes =
	            new RedirectAttributesModelMap();


	    String result =
	            controller.updateArticle(
	                    ARTICLE_ID,
	                    form,
	                    EditWikiArticleAction.SAVE_CHANGES,
	                    authentication,
	                    redirectAttributes
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles/"
	                            + ARTICLE_ID
	            );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("successMessage")
	    ).isEqualTo(
	            "Đã cập nhật bài Wiki \"Trần Bình An cập nhật\"."
	    );


	    verify(
	            updateDraftWikiArticleUseCase
	    ).execute(
	            new UpdateDraftWikiArticleCommand(
	                    ARTICLE_ID,
	                    "Trần Bình An cập nhật",
	                    ArticleType.CHARACTER,
	                    "Tóm tắt mới",
	                    "Nội dung mới",
	                    "Cập nhật bản nháp",
	                    ADMIN_ID
	            )
	    );


	    verify(
	            updateDraftAndPublishWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftAndPublishWikiArticleCommand.class)
	    );


	    verify(
	            updatePublishedWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdatePublishedWikiArticleCommand.class)
	    );
	}



	/*
	 * =====================================================
	 * UPDATE DRAFT + PUBLISH
	 * =====================================================
	 */

	@Test
	@DisplayName(
	        "Lưu thay đổi và xuất bản bài Wiki DRAFT trong cùng một hành động"
	)
	void shouldUpdateDraftAndPublishWikiArticle() {

	    EditWikiArticleForm form =
	            new EditWikiArticleForm();

	    form.setTitle(
	            "  Trần Bình An hoàn thiện  "
	    );

	    form.setArticleType(
	            ArticleType.CHARACTER
	    );

	    form.setSummary(
	            "  Tóm tắt hoàn thiện  "
	    );

	    form.setContent(
	            "  Nội dung hoàn thiện để xuất bản  "
	    );

	    form.setEditSummary(
	            "  Hoàn thiện và xuất bản  "
	    );


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            ARTICLE_ID
	                    )
	            )
	    ).thenReturn(
	            createDraftArticleDTO()
	    );


	    WikiArticleDTO publishedArticle =
	            new WikiArticleDTO(
	                    ARTICLE_ID,
	                    "Trần Bình An hoàn thiện",
	                    "tran-binh-an-hoan-thien",
	                    "CHARACTER",
	                    "Tóm tắt hoàn thiện",
	                    "Nội dung hoàn thiện để xuất bản",
	                    "PUBLISHED",
	                    ADMIN_ID,
	                    ADMIN_ID,
	                    ADMIN_ID,
	                    null,
	                    NOW,
	                    NOW,
	                    NOW,
	                    null,
	                    2L,
	                    2L
	            );


	    when(
	            updateDraftAndPublishWikiArticleUseCase.execute(
	                    new UpdateDraftAndPublishWikiArticleCommand(
	                            ARTICLE_ID,
	                            "Trần Bình An hoàn thiện",
	                            ArticleType.CHARACTER,
	                            "Tóm tắt hoàn thiện",
	                            "Nội dung hoàn thiện để xuất bản",
	                            "Hoàn thiện và xuất bản",
	                            ADMIN_ID
	                    )
	            )
	    ).thenReturn(
	            publishedArticle
	    );


	    RedirectAttributesModelMap redirectAttributes =
	            new RedirectAttributesModelMap();


	    String result =
	            controller.updateArticle(
	                    ARTICLE_ID,
	                    form,
	                    EditWikiArticleAction.SAVE_AND_PUBLISH,
	                    authentication,
	                    redirectAttributes
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles/"
	                            + ARTICLE_ID
	            );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("successMessage")
	    ).isEqualTo(
	            "Đã lưu thay đổi và xuất bản bài Wiki "
	                    + "\"Trần Bình An hoàn thiện\"."
	    );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("wikiAutosaveCleanupKey")
	    ).isEqualTo(
	            "kiemlai:wiki:autosave:edit:"
	                    + ARTICLE_ID
	    );


	    verify(
	            updateDraftAndPublishWikiArticleUseCase
	    ).execute(
	            new UpdateDraftAndPublishWikiArticleCommand(
	                    ARTICLE_ID,
	                    "Trần Bình An hoàn thiện",
	                    ArticleType.CHARACTER,
	                    "Tóm tắt hoàn thiện",
	                    "Nội dung hoàn thiện để xuất bản",
	                    "Hoàn thiện và xuất bản",
	                    ADMIN_ID
	            )
	    );


	    verify(
	            updateDraftWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftWikiArticleCommand.class)
	    );


	    verify(
	            updatePublishedWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdatePublishedWikiArticleCommand.class)
	    );
	}


	@Test
	@DisplayName(
	        "Không cho SAVE_AND_PUBLISH khi bài Wiki đã PUBLISHED"
	)
	void shouldRejectSaveAndPublishForPublishedArticle() {

	    EditWikiArticleForm form =
	            new EditWikiArticleForm();

	    form.setSummary(
	            "Tóm tắt"
	    );

	    form.setContent(
	            "Nội dung"
	    );


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            ARTICLE_ID
	                    )
	            )
	    ).thenReturn(
	            createPublishedArticleDTO()
	    );


	    assertThatThrownBy(() ->
	            controller.updateArticle(
	                    ARTICLE_ID,
	                    form,
	                    EditWikiArticleAction.SAVE_AND_PUBLISH,
	                    authentication,
	                    new RedirectAttributesModelMap()
	            )
	    )
	            .isInstanceOf(
	                    IllegalStateException.class
	            )
	            .hasMessage(
	                    "Bài Wiki đã được xuất bản."
	            );


	    verify(
	            updateDraftAndPublishWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftAndPublishWikiArticleCommand.class)
	    );


	    verify(
	            updateDraftWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftWikiArticleCommand.class)
	    );


	    verify(
	            updatePublishedWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdatePublishedWikiArticleCommand.class)
	    );
	}


	/*
	 * =====================================================
	 * UPDATE PUBLISHED
	 * =====================================================
	 */

	@Test
	@DisplayName(
	        "Chỉnh sửa bài Wiki PUBLISHED bằng UpdatePublishedWikiArticleUseCase"
	)
	void shouldUpdatePublishedWikiArticle() {

	    EditWikiArticleForm form =
	            new EditWikiArticleForm();

	    /*
	     * Cố tình truyền title/type khác.
	     *
	     * Controller phải bỏ qua hai field này khi bài
	     * đang PUBLISHED.
	     */
	    form.setTitle(
	            "Tên giả từ browser"
	    );

	    form.setArticleType(
	            ArticleType.LOCATION
	    );

	    form.setSummary(
	            "  Tóm tắt published mới  "
	    );

	    form.setContent(
	            "  Nội dung published mới  "
	    );

	    form.setEditSummary(
	            "  Bổ sung nội dung  "
	    );


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            ARTICLE_ID
	                    )
	            )
	    ).thenReturn(
	            createPublishedArticleDTO()
	    );


	    WikiArticleDTO updatedArticle =
	            new WikiArticleDTO(
	                    ARTICLE_ID,
	                    "Trần Bình An",
	                    "tran-binh-an",
	                    "CHARACTER",
	                    "Tóm tắt published mới",
	                    "Nội dung published mới",
	                    "PUBLISHED",
	                    ADMIN_ID,
	                    ADMIN_ID,
	                    ADMIN_ID,
	                    null,
	                    NOW,
	                    NOW,
	                    NOW,
	                    null,
	                    2L,
	                    2L
	            );


	    when(
	            updatePublishedWikiArticleUseCase.execute(
	                    new UpdatePublishedWikiArticleCommand(
	                            ARTICLE_ID,
	                            "Tóm tắt published mới",
	                            "Nội dung published mới",
	                            "Bổ sung nội dung",
	                            ADMIN_ID
	                    )
	            )
	    ).thenReturn(
	            updatedArticle
	    );


	    RedirectAttributesModelMap redirectAttributes =
	            new RedirectAttributesModelMap();


	    String result =
	            controller.updateArticle(
	                    ARTICLE_ID,
	                    form,
	                    EditWikiArticleAction.SAVE_CHANGES,
	                    authentication,
	                    redirectAttributes
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles/"
	                            + ARTICLE_ID
	            );


	    verify(
	            updatePublishedWikiArticleUseCase
	    ).execute(
	            new UpdatePublishedWikiArticleCommand(
	                    ARTICLE_ID,
	                    "Tóm tắt published mới",
	                    "Nội dung published mới",
	                    "Bổ sung nội dung",
	                    ADMIN_ID
	            )
	    );


	    verify(
	            updateDraftAndPublishWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftAndPublishWikiArticleCommand.class)
	    );


	    verify(
	            updateDraftWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftWikiArticleCommand.class)
	    );
	}


	/*
	 * =====================================================
	 * UPDATE ARCHIVED
	 * =====================================================
	 */

	@Test
	@DisplayName(
	        "Không cho phép chỉnh sửa trực tiếp bài Wiki ARCHIVED"
	)
	void shouldRejectUpdateArchivedWikiArticle() {

	    EditWikiArticleForm form =
	            new EditWikiArticleForm();

	    form.setSummary(
	            "Tóm tắt mới"
	    );

	    form.setContent(
	            "Nội dung mới"
	    );


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            ARTICLE_ID
	                    )
	            )
	    ).thenReturn(
	            createArchivedArticleDTO()
	    );


	    assertThatThrownBy(() ->
	            controller.updateArticle(
	                    ARTICLE_ID,
	                    form,
	                    EditWikiArticleAction.SAVE_CHANGES,
	                    authentication,
	                    new RedirectAttributesModelMap()
	            )
	    )
	            .isInstanceOf(
	                    IllegalStateException.class
	            )
	            .hasMessage(
	                    "Bài Wiki đã lưu trữ không thể chỉnh sửa trực tiếp."
	            );


	    verify(
	            updateDraftAndPublishWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftAndPublishWikiArticleCommand.class)
	    );


	    verify(
	            updateDraftWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdateDraftWikiArticleCommand.class)
	    );


	    verify(
	            updatePublishedWikiArticleUseCase,
	            never()
	    ).execute(
	            any(UpdatePublishedWikiArticleCommand.class)
	    );
	}
	
	@Test
	@DisplayName(
	        "Khôi phục một revision cũ của bài Wiki thành DRAFT"
	)
	void shouldRestoreWikiArticleRevision() {

	    long revisionNumber =
	            3L;


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    WikiArticleDTO restoredArticle =
	            createDraftArticleDTO();


	    when(
	            restoreWikiArticleUseCase.execute(
	                    new RestoreWikiArticleCommand(
	                            ARTICLE_ID,
	                            revisionNumber,
	                            "Khôi phục nội dung cũ",
	                            ADMIN_ID
	                    )
	            )
	    ).thenReturn(
	            restoredArticle
	    );


	    RedirectAttributesModelMap redirectAttributes =
	            new RedirectAttributesModelMap();


	    String result =
	            controller.restoreRevision(
	                    ARTICLE_ID,
	                    revisionNumber,
	                    "  Khôi phục nội dung cũ  ",
	                    authentication,
	                    redirectAttributes
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles/"
	                            + ARTICLE_ID
	                            + "/revisions"
	            );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("successMessage")
	    )
	            .isEqualTo(
	                    "Đã khôi phục Revision #3 "
	                            + "của bài Wiki \"Trần Bình An\" "
	                            + "thành bản nháp."
	            );


	    verify(
	            restoreWikiArticleUseCase
	    ).execute(
	            new RestoreWikiArticleCommand(
	                    ARTICLE_ID,
	                    revisionNumber,
	                    "Khôi phục nội dung cũ",
	                    ADMIN_ID
	            )
	    );
	}
	
	@Test
	@DisplayName(
	        "Cho phép khôi phục revision mà không nhập ghi chú"
	)
	void shouldRestoreRevisionWithoutEditSummary() {

	    long revisionNumber =
	            2L;


	    when(
	            authentication.getName()
	    ).thenReturn(
	            ADMIN_EMAIL
	    );


	    when(
	            userIdentityContract.findByEmail(
	                    ADMIN_EMAIL
	            )
	    ).thenReturn(
	            Optional.of(
	                    createAdminDTO()
	            )
	    );


	    when(
	            restoreWikiArticleUseCase.execute(
	                    new RestoreWikiArticleCommand(
	                            ARTICLE_ID,
	                            revisionNumber,
	                            null,
	                            ADMIN_ID
	                    )
	            )
	    ).thenReturn(
	            createDraftArticleDTO()
	    );


	    String result =
	            controller.restoreRevision(
	                    ARTICLE_ID,
	                    revisionNumber,
	                    "   ",
	                    authentication,
	                    new RedirectAttributesModelMap()
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles/"
	                            + ARTICLE_ID
	                            + "/revisions"
	            );


	    verify(
	            restoreWikiArticleUseCase
	    ).execute(
	            new RestoreWikiArticleCommand(
	                    ARTICLE_ID,
	                    revisionNumber,
	                    null,
	                    ADMIN_ID
	            )
	    );
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
		return new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin Wiki", null, "ACTIVE", "ADMIN", NOW);
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

		String result = controller.publishArticle(
		        ARTICLE_ID,
		        "list",
		        authentication,
		        redirectAttributes
		);
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

		String result = controller.unpublishArticle(
		        ARTICLE_ID,
		        "list",
		        authentication,
		        redirectAttributes
		);
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

		String result = controller.archiveArticle(
		        ARTICLE_ID,
		        "list",
		        authentication,
		        redirectAttributes
		);
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

		verify(deleteWikiArticleUseCase).execute(new DeleteWikiArticleCommand(ARTICLE_ID));
	}
	
	@Test
	@DisplayName(
	        "Publish không hợp lệ phải quay lại danh sách và hiển thị lỗi"
	)
	void shouldRedirectWithErrorMessageWhenPublishFails() {

	    prepareAuthenticatedAdmin();


	    when(
	            publishWikiArticleUseCase.execute(
	                    new PublishWikiArticleCommand(
	                            ARTICLE_ID,
	                            null,
	                            ADMIN_ID
	                    )
	            )
	    ).thenThrow(
	            new IllegalStateException(
	                    "Bài viết phải có nội dung trước khi xuất bản."
	            )
	    );


	    RedirectAttributesModelMap redirectAttributes =
	            new RedirectAttributesModelMap();


	    String result =
	            controller.publishArticle(
	                    ARTICLE_ID,
	                    "list",
	                    authentication,
	                    redirectAttributes
	            );


	    assertThat(result)
	            .isEqualTo(
	                    "redirect:/admin/wiki/articles"
	            );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("errorMessage")
	    ).isEqualTo(
	            "Không thể xuất bản bài Wiki. "
	                    + "Bài viết phải có nội dung trước khi xuất bản."
	    );


	    assertThat(
	            redirectAttributes
	                    .getFlashAttributes()
	                    .get("successMessage")
	    ).isNull();


	    verify(
	            publishWikiArticleUseCase
	    ).execute(
	            new PublishWikiArticleCommand(
	                    ARTICLE_ID,
	                    null,
	                    ADMIN_ID
	            )
	    );
	}

	private void prepareAuthenticatedAdmin() {
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(createAdminDTO()));
	}
	
	private WikiArticleDTO createArchivedArticleDTO() {

	    return new WikiArticleDTO(
	            ARTICLE_ID,
	            "Trần Bình An",
	            "tran-binh-an",
	            "CHARACTER",
	            "Nhân vật chính của Kiếm Lai.",
	            "Nội dung ban đầu của bài viết.",
	            "ARCHIVED",
	            ADMIN_ID,
	            ADMIN_ID,
	            ADMIN_ID,
	            ADMIN_ID,
	            NOW,
	            NOW,
	            NOW,
	            NOW,
	            3L,
	            1L
	    );
	}
}