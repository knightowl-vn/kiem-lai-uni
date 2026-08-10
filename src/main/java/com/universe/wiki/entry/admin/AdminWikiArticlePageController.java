package com.universe.wiki.entry.admin;

import com.universe.wiki.application.article.query.list.ListWikiArticlesQuery;
import com.universe.wiki.application.article.query.list.ListWikiArticlesUseCase;
import com.universe.wiki.application.article.template.WikiArticleContentTemplateProvider;
import com.universe.wiki.application.revision.query.detail.GetWikiArticleRevisionDetailQuery;
import com.universe.wiki.application.revision.query.detail.GetWikiArticleRevisionDetailUseCase;
import com.universe.wiki.application.revision.query.list.ListWikiArticleRevisionsQuery;
import com.universe.wiki.application.revision.query.list.ListWikiArticleRevisionsUseCase;
import com.universe.wiki.contracts.dto.WikiArticlePageDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionDetailDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;

import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;

import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;

import com.universe.wiki.contracts.dto.WikiArticleDTO;

import com.universe.wiki.entry.admin.form.EditWikiArticleForm;

import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Locale;

@Controller
@RequestMapping("/admin/wiki/articles")
public class AdminWikiArticlePageController {

	private static final String PAGE_TITLE = "Quản lý Wiki";

	private static final String ACTIVE_MENU = "wiki";

	private final ListWikiArticlesUseCase listWikiArticlesUseCase;

	private final WikiArticleContentTemplateProvider contentTemplateProvider;

	private final GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;

	private final ListWikiArticleRevisionsUseCase listWikiArticleRevisionsUseCase;

	private final GetWikiArticleRevisionDetailUseCase getWikiArticleRevisionDetailUseCase;

	public AdminWikiArticlePageController(
	        ListWikiArticlesUseCase listWikiArticlesUseCase,
	        WikiArticleContentTemplateProvider contentTemplateProvider,
	        GetWikiArticleDetailUseCase getWikiArticleDetailUseCase,
	        ListWikiArticleRevisionsUseCase listWikiArticleRevisionsUseCase,
	        GetWikiArticleRevisionDetailUseCase getWikiArticleRevisionDetailUseCase
	) {
	    this.listWikiArticlesUseCase =
	            listWikiArticlesUseCase;

	    this.contentTemplateProvider =
	            contentTemplateProvider;

	    this.getWikiArticleDetailUseCase =
	            getWikiArticleDetailUseCase;

	    this.listWikiArticleRevisionsUseCase =
	            listWikiArticleRevisionsUseCase;

	    this.getWikiArticleRevisionDetailUseCase =
	            getWikiArticleRevisionDetailUseCase;
	}

	/**
	 * Danh sách quản trị Wiki.
	 *
	 * Ví dụ:
	 *
	 * /admin/wiki/articles
	 *
	 * /admin/wiki/articles ?keyword=Trần &type=CHARACTER &status=PUBLISHED &page=0
	 * &size=20
	 */
	@GetMapping({ "", "/" })
	public String listPage(
	        @RequestParam(required = false) String keyword,
	        @RequestParam(name = "type", required = false) String articleTypeValue,
	        @RequestParam(name = "status", required = false) String statusValue,
	        @RequestParam(defaultValue = "0") int page,
	        @RequestParam(defaultValue = "20") int size,
	        Model model,
	        HttpServletResponse response
	) {
	    /*
	     * Trang quản trị có dữ liệu thay đổi liên tục sau
	     * Publish / Unpublish / Archive / Restore.
	     *
	     * Không cho browser dùng lại HTML cũ trong cache.
	     */
	    response.setHeader(
	            "Cache-Control",
	            "no-store, no-cache, must-revalidate, max-age=0"
	    );

	    response.setHeader(
	            "Pragma",
	            "no-cache"
	    );

	    response.setDateHeader(
	            "Expires",
	            0
	    );

	    ArticleType selectedType =
	            resolveArticleType(
	                    articleTypeValue
	            );

	    ArticleStatus selectedStatus =
	            resolveArticleStatus(
	                    statusValue
	            );

	    WikiArticlePageDTO articlePage =
	            listWikiArticlesUseCase.execute(
	                    new ListWikiArticlesQuery(
	                            keyword,
	                            selectedType,
	                            selectedStatus,
	                            page,
	                            size
	                    )
	            );

	    model.addAttribute(
	            "articlePage",
	            articlePage
	    );

	    model.addAttribute(
	            "keyword",
	            keyword == null ? "" : keyword
	    );

	    model.addAttribute(
	            "selectedType",
	            selectedType
	    );

	    model.addAttribute(
	            "selectedStatus",
	            selectedStatus
	    );

	    model.addAttribute(
	            "articleTypes",
	            ArticleType.values()
	    );

	    model.addAttribute(
	            "articleStatuses",
	            ArticleStatus.values()
	    );

	    model.addAttribute(
	            "pageTitle",
	            PAGE_TITLE
	    );

	    model.addAttribute(
	            "activeMenu",
	            ACTIVE_MENU
	    );

	    return "admin/wiki/articles";
	}

	/**
	 * Trang tạo bài Wiki mới.
	 */
	@GetMapping("/new")
	public String createPage(Model model) {
		model.addAttribute("form", new CreateWikiArticleForm());

		model.addAttribute("articleTypes", ArticleType.values());

		model.addAttribute("pageTitle", "Tạo bài Wiki");

		model.addAttribute("activeMenu", ACTIVE_MENU);

		return "admin/wiki/create";
	}

	/**
	 * Trả về Markdown template tương ứng với ArticleType.
	 *
	 * Ví dụ:
	 *
	 * GET /admin/wiki/articles/content-template?type=CHARACTER
	 *
	 * Response:
	 *
	 * ## Tổng quan
	 *
	 * ## Xuất thân và bối cảnh
	 *
	 * ...
	 *
	 * Endpoint này được giao diện tạo/chỉnh sửa bài sử dụng khi Admin nhấn nút "Áp
	 * dụng mẫu".
	 */
	@GetMapping(value = "/content-template", produces = MediaType.TEXT_PLAIN_VALUE)
	@ResponseBody
	public String contentTemplate(@RequestParam("type") ArticleType articleType) {
		return contentTemplateProvider.getTemplate(articleType);
	}

	private ArticleType resolveArticleType(String articleTypeValue) {
		if (articleTypeValue == null || articleTypeValue.isBlank()) {
			return null;
		}

		try {
			return ArticleType.valueOf(articleTypeValue.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Article type không hợp lệ: " + articleTypeValue);
		}
	}

	private ArticleStatus resolveArticleStatus(String statusValue) {
		if (statusValue == null || statusValue.isBlank()) {
			return null;
		}

		try {
			return ArticleStatus.valueOf(statusValue.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Article status không hợp lệ: " + statusValue);
		}
	}

	@GetMapping("/{id}")
	public String detailPage(@PathVariable UUID id,

			Model model) {
		WikiArticleDTO article = getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(id));

		model.addAttribute("article", article);

		model.addAttribute("pageTitle", "Chi tiết bài Wiki");

		model.addAttribute("activeMenu", ACTIVE_MENU);

		return "admin/wiki/detail";
	}

	@GetMapping("/{id}/edit")
	public String editPage(@PathVariable UUID id,

			Model model) {
		WikiArticleDTO article = getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(id));

		if ("ARCHIVED".equals(article.status())) {
			throw new IllegalStateException("Bài Wiki đã lưu trữ không thể chỉnh sửa trực tiếp.");
		}

		EditWikiArticleForm form = new EditWikiArticleForm();

		form.setTitle(article.title());

		form.setArticleType(ArticleType.valueOf(article.articleType()));

		form.setSummary(article.summary());

		form.setContent(article.content());

		model.addAttribute("article", article);

		model.addAttribute("form", form);

		model.addAttribute("articleTypes", ArticleType.values());

		/*
		 * DRAFT: title/type được sửa.
		 *
		 * PUBLISHED: chỉ summary/content được sửa.
		 */
		model.addAttribute("draft", "DRAFT".equals(article.status()));

		model.addAttribute("pageTitle", "Chỉnh sửa bài Wiki");

		model.addAttribute("activeMenu", ACTIVE_MENU);

		return "admin/wiki/edit";
	}
	
	/*
	 * =====================================================
	 * REVISION HISTORY
	 * =====================================================
	 */

	@GetMapping("/{id}/revisions")
	public String revisionHistoryPage(
	        @PathVariable
	        UUID id,

	        @RequestParam(defaultValue = "0")
	        int page,

	        @RequestParam(defaultValue = "20")
	        int size,

	        Model model
	) {
	    /*
	     * Lấy bài hiện tại để trang History biết
	     * lịch sử này thuộc bài nào.
	     */
	    WikiArticleDTO article =
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            id
	                    )
	            );


	    WikiArticleRevisionPageDTO revisionPage =
	            listWikiArticleRevisionsUseCase.execute(
	                    new ListWikiArticleRevisionsQuery(
	                            id,
	                            page,
	                            size
	                    )
	            );


	    model.addAttribute(
	            "article",
	            article
	    );

	    model.addAttribute(
	            "revisionPage",
	            revisionPage
	    );

	    model.addAttribute(
	            "pageTitle",
	            "Lịch sử phiên bản"
	    );

	    model.addAttribute(
	            "activeMenu",
	            ACTIVE_MENU
	    );


	    return "admin/wiki/revisions";
	}
	
	
	/*
	 * =====================================================
	 * REVISION DETAIL
	 * =====================================================
	 */

	@GetMapping("/{id}/revisions/{revisionNumber}")
	public String revisionDetailPage(
	        @PathVariable
	        UUID id,

	        @PathVariable
	        long revisionNumber,

	        Model model
	) {
	    WikiArticleDTO article =
	            getWikiArticleDetailUseCase.execute(
	                    new GetWikiArticleDetailQuery(
	                            id
	                    )
	            );


	    WikiArticleRevisionDetailDTO revision =
	            getWikiArticleRevisionDetailUseCase.execute(
	                    new GetWikiArticleRevisionDetailQuery(
	                            id,
	                            revisionNumber
	                    )
	            );
	    boolean currentContentVersion =
	            article.contentVersion()
	            == revision.contentVersion();


	    boolean archivedArticle =
	            "ARCHIVED".equals(
	                    article.status()
	            );


	    boolean restoreAllowed =
	            !currentContentVersion
	            || archivedArticle;

	    model.addAttribute(
	            "article",
	            article
	    );

	    model.addAttribute(
	            "revision",
	            revision
	    );

	    model.addAttribute(
	            "pageTitle",
	            "Chi tiết phiên bản"
	    );

	    model.addAttribute(
	            "activeMenu",
	            ACTIVE_MENU
	    );
	    
	    model.addAttribute(
	            "currentContentVersion",
	            currentContentVersion
	    );
	    

	    model.addAttribute(
	            "archivedArticle",
	            archivedArticle
	    );

	    model.addAttribute(
	            "restoreAllowed",
	            restoreAllowed
	    );


	    return "admin/wiki/revision-detail";
	}
}