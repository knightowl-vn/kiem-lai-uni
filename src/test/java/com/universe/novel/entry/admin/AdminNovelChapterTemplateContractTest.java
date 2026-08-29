package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminNovelChapterTemplateContractTest {

	@Test
	@DisplayName("Detail Chapter là trang chỉ đọc, không còn nút lifecycle, có link lịch sử và quản lý Wiki")
	void detailPageIsReadOnlyWithoutLifecycleActions() throws Exception {
		String detail = read("src/main/resources/templates/admin/novel/chapter-detail.html");

		assertThat(detail).contains("Quay lại danh sách chương");
		assertThat(detail).contains("Aggregate");
		assertThat(detail).contains("Content");
		assertThat(detail).contains("Tóm tắt Chapter");
		assertThat(detail).contains("Nội dung Chapter");
		assertThat(detail).contains("Lịch sử chỉnh sửa");
		assertThat(detail).contains("th:href=\"@{/admin/novel/chapters/{id}/revisions(id=${chapter.id})}\"");
		assertThat(detail).contains("Quản lý liên kết Wiki");
		assertThat(detail).contains("th:href=\"@{/admin/novel/chapters/{id}/wiki-references(id=${chapter.id})}\"");

		assertThat(detail).doesNotContain("novel-admin-detail-actions");
		assertThat(detail).doesNotContain("Chỉnh sửa");
		assertThat(detail).doesNotContain("Di chuyển");
		assertThat(detail).doesNotContain("/chapters/{id}/publish");
		assertThat(detail).doesNotContain("/chapters/{id}/delete");
		assertThat(detail).doesNotContain("/chapters/{id}/move");
		assertThat(detail).doesNotContain("/chapters/{id}/edit");
		assertThat(detail).doesNotContain("Reorder");
		assertThat(detail).doesNotContain("targetSortOrder");
	}

	@Test
	@DisplayName("List Chapter Revision có bảng lịch sử, phân trang, và link xem chi tiết")
	void chapterRevisionListHasTablePaginationAndDetailLink() throws Exception {
		String list = read("src/main/resources/templates/admin/novel/chapter-revisions.html");

		assertThat(list).contains("Lịch sử chỉnh sửa");
		assertThat(list).contains("Quay lại chi tiết chương");
		assertThat(list).contains("th:href=\"@{/admin/novel/chapters/{id}(");
		assertThat(list).contains("Phiên bản");
		assertThat(list).contains("Thay đổi");
		assertThat(list).contains("Tiêu đề");
		assertThat(list).contains("Trạng thái");
		assertThat(list).contains("Người sửa");
		assertThat(list).contains("Ghi chú");
		assertThat(list).contains("Thời gian");
		assertThat(list).contains("Thao tác");
		assertThat(list).contains("Xem chi tiết");
		assertThat(list).contains("th:href=\"@{/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}(");
		assertThat(list).contains("novel-admin-pagination");
		assertThat(list).contains("Trang trước");
		assertThat(list).contains("Trang sau");
	}

	@Test
	@DisplayName("Detail Chapter Revision hiển thị snapshot và form khôi phục phiên bản cho DRAFT, hướng dẫn cho PUBLISHED/ARCHIVED")
	void chapterRevisionDetailHasRestoreFormAndGuidance() throws Exception {
		String detail = read("src/main/resources/templates/admin/novel/chapter-revision-detail.html");

		assertThat(detail).contains("Quay lại lịch sử");
		assertThat(detail).contains("Xem chi tiết chương hiện tại");
		assertThat(detail).contains("Phiên bản");
		assertThat(detail).contains("Số chương");
		assertThat(detail).contains("Phiên bản nội dung");
		assertThat(detail).contains("Thời gian ghi");
		assertThat(detail).contains("Ghi chú chỉnh sửa");
		assertThat(detail).contains("Tóm tắt tại phiên bản này");
		assertThat(detail).contains("Nội dung tại phiên bản này");
		assertThat(detail).contains("th:action=\"@{/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore(");
		assertThat(detail).contains("Không thể khôi phục phiên bản lịch sử trực tiếp trên chương đã xuất bản");
		assertThat(detail).doesNotContain("name=\"actorId\"");
	}

	@Test
	@DisplayName("Chapter Wiki References page hiển thị stats summary, bảng liên kết, và preview HTML render từ markdown")
	void chapterWikiReferencesPageHasStructureAndPreviewContract() throws Exception {
		String page = read("src/main/resources/templates/admin/novel/chapter-wiki-references.html");

		// Navigation & Header
		assertThat(page).contains("Quản lý liên kết Wiki");
		assertThat(page).contains("Quay lại chi tiết chương");
		assertThat(page).contains("th:href=\"@{/admin/novel/chapters/{id}(id=${chapter.id})}\"");

		// Stats Summary
		assertThat(page).contains("TỔNG SỐ LIÊN KẾT");
		assertThat(page).contains("ĐANG HOẠT ĐỘNG (ACTIVE)");
		assertThat(page).contains("CẦN CẬP NHẬT (STALE)");
		assertThat(page).contains("CONTENT VERSION");

		// Flash Alerts
		assertThat(page).contains("th:if=\"${successMessage != null}\"");
		assertThat(page).contains("th:if=\"${errorMessage != null}\"");

		// References Table Columns & Scope / Status Badges
		assertThat(page).contains("Thuật ngữ");
		assertThat(page).contains("Phạm vi");
		assertThat(page).contains("Bài viết Wiki liên kết");
		assertThat(page).contains("Trạng thái");
		assertThat(page).contains("Ngữ cảnh");
		assertThat(page).contains("Thao tác");
		assertThat(page).contains("Toàn chương");
		assertThat(page).contains("Vị trí #");
		assertThat(page).contains("Bài viết không khả dụng");
		assertThat(page).contains("th:action=\"@{/admin/novel/chapters/{chapterId}/wiki-references/{refId}/delete(");
		assertThat(page).doesNotContain("name=\"actorId\"");

		// Content Preview Section
		assertThat(page).contains("NỘI DUNG CHAPTER");
		assertThat(page).contains("Bản xem trước nội dung");
		assertThat(page).contains("class=\"novel-reader-chapter-body\"");
		assertThat(page).contains("th:attr=\"data-chapter-id=${chapter.id}\"");
		assertThat(page).contains("th:utext=\"${contentHtml}\"");
	}

	@Test
	@DisplayName("Chapter List dùng table view, có link xem chi tiết và action menu")
	void chapterListHasTableAndActionMenu() throws Exception {
		String list = read("src/main/resources/templates/admin/novel/chapters.html");

		assertThat(list).contains("novel-admin-table");
		assertThat(list).contains("Số chương");
		assertThat(list).contains("Tiêu đề");
		assertThat(list).contains("Trạng thái");
		assertThat(list).contains("Cập nhật");
		assertThat(list).contains("Xem chi tiết");
		assertThat(list).contains("Thao tác");
		assertThat(list).contains("novel-admin-list-action-menu");
		assertThat(list).contains("data-bs-boundary=\"window\"");
		assertThat(list).contains("name=\"keyword\"");
		assertThat(list).contains("name=\"status\"");
		assertThat(list).contains("Lọc kết quả");
		assertThat(list).contains("Đặt lại");
		assertThat(list).contains("th:value=\"${keyword}\"");
		assertThat(list).contains("/js/novel/admin-list-menus.js");
		assertThat(list).contains("/chapters/{id}/edit");
		assertThat(list).contains("/chapters/{id}/publish");
		assertThat(list).contains("/chapters/{id}/archive");
		assertThat(list).contains("/chapters/{id}/restore");
		assertThat(list).doesNotContain("> Chi tiết </a>");
	}

	@Test
	@DisplayName("Create/Edit dùng Markdown editor, không cho nhập slug hoặc sortOrder")
	void createAndEditDoNotExposeSlugOrSortOrderFields() throws Exception {
		String create = read("src/main/resources/templates/admin/novel/chapter-create.html");
		String edit = read("src/main/resources/templates/admin/novel/chapter-edit.html");
		String editorFragment = read("src/main/resources/templates/admin/novel/fragments/markdown-editor.html");

		assertThat(create).doesNotContain("th:field=\"*{slug}\"");
		assertThat(create).doesNotContain("th:field=\"*{sortOrder}\"");
		assertThat(create).doesNotContain("name=\"slug\"");
		assertThat(create).doesNotContain("name=\"actorId\"");
		assertThat(create).contains("th:field=\"*{chapterNumber}\"");
		assertThat(create).contains("admin/novel/fragments/markdown-editor");
		assertThat(create).contains("/js/novel/admin-markdown-editor.js");
		assertThat(create).doesNotContain("/js/wiki/");
		assertThat(create).doesNotContain("/css/wiki/");

		assertThat(edit).doesNotContain("th:field=\"*{slug}\"");
		assertThat(edit).doesNotContain("th:field=\"*{sortOrder}\"");
		assertThat(edit).doesNotContain("name=\"actorId\"");
		assertThat(edit).contains("th:field=\"*{chapterNumber}\"");
		assertThat(edit).contains("admin/novel/fragments/markdown-editor");
		assertThat(edit).contains("/js/novel/admin-markdown-editor.js");
		assertThat(edit).doesNotContain("/js/wiki/");

		assertThat(editorFragment).contains("id=\"novelContent\"");
		assertThat(editorFragment).contains("data-markdown-action");
		assertThat(editorFragment).contains("novelWriteTab");
		assertThat(editorFragment).contains("novelPreviewTab");
	}

	private String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
	}
}
