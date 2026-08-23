package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminNovelChapterTemplateContractTest {

	@Test
	@DisplayName("Detail Chapter là trang chỉ đọc, không còn nút lifecycle")
	void detailPageIsReadOnlyWithoutLifecycleActions() throws Exception {
		String detail = read("src/main/resources/templates/admin/novel/chapter-detail.html");

		assertThat(detail).contains("Quay lại danh sách chương");
		assertThat(detail).contains("Aggregate");
		assertThat(detail).contains("Content");
		assertThat(detail).contains("Tóm tắt Chapter");
		assertThat(detail).contains("Nội dung Chapter");
		assertThat(detail).contains("Lịch sử chỉnh sửa");
		assertThat(detail).contains("th:href=\"@{/admin/novel/chapters/{id}/revisions(id=${chapter.id})}\"");

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
		assertThat(detail).contains("Loại thay đổi");
		assertThat(detail).contains("Trạng thái snapshot");
		assertThat(detail).contains("Slug snapshot");
		assertThat(detail).contains("Người thực hiện");
		assertThat(detail).contains("Thời gian ghi");
		assertThat(detail).contains("Ghi chú chỉnh sửa");
		assertThat(detail).contains("Tóm tắt tại phiên bản này");
		assertThat(detail).contains("Nội dung tại phiên bản này");
		assertThat(detail).contains("th:utext=\"${revision.contentHtml}\"");

		// Restore Form for DRAFT
		assertThat(detail).contains("th:if=\"${chapter.status == 'DRAFT'}\"");
		assertThat(detail).contains("th:action=\"@{/admin/novel/chapters/{chapterId}/revisions/{revisionNumber}/restore");
		assertThat(detail).contains("name=\"expectedAggregateVersion\"");
		assertThat(detail).contains("th:value=\"${chapter.aggregateVersion}\"");
		assertThat(detail).contains("name=\"editSummary\"");
		assertThat(detail).contains("maxlength=\"500\"");
		assertThat(detail).contains("Khôi phục phiên bản này");

		// Guidance for PUBLISHED / ARCHIVED
		assertThat(detail).contains("th:if=\"${chapter.status == 'PUBLISHED'}\"");
		assertThat(detail).contains("th:if=\"${chapter.status == 'ARCHIVED'}\"");
		assertThat(detail).contains("Hủy xuất bản");

		// No controls for structural fields
		assertThat(detail).doesNotContain("name=\"volumeId\"");
		assertThat(detail).doesNotContain("name=\"chapterNumber\"");
		assertThat(detail).doesNotContain("name=\"slug\"");
		assertThat(detail).doesNotContain("name=\"status\"");
	}

	@Test
	@DisplayName("List Chapter dùng một dropdown Thao tác, giữ link tiêu đề")
	void chapterListHasSingleActionMenuAndTitleLink() throws Exception {
		String list = read("src/main/resources/templates/admin/novel/chapters.html");

		assertThat(list).contains("class=\"novel-admin-volume-link\"");
		assertThat(list).contains("th:href=\"@{/admin/novel/chapters/{id}(id=${chapter.id})}\"");

		assertThat(list).contains("Thao tác");
		assertThat(list).contains("Xem chi tiết");
		assertThat(list).contains("Chỉnh sửa");
		assertThat(list).contains("Di chuyển");
		assertThat(list).contains("Xuất bản");
		assertThat(list).contains("Hủy xuất bản");
		assertThat(list).contains("Lưu trữ");
		assertThat(list).contains("Khôi phục");
		assertThat(list).contains("Xóa");
		assertThat(list).contains("novel-admin-list-action-menu");
		assertThat(list).contains("novel-admin-table-wrapper--actions");
		assertThat(list).contains("data-bs-boundary=\"window\"");
		assertThat(list).contains("name=\"keyword\"");
		assertThat(list).contains("name=\"status\"");
		assertThat(list).contains("Lọc kết quả");
		assertThat(list).contains("Đặt lại");
		assertThat(list).contains("th:value=\"${keyword}\"");
		assertThat(list).contains("/js/novel/admin-list-menus.js");
		assertThat(list).contains("/chapters/{id}/edit");
		assertThat(list).contains("/chapters/{id}/publish");
		assertThat(list).contains("/chapters/{id}/move");
		assertThat(list).contains("/chapters/{id}/delete");
		assertThat(list).contains("targetVolumeId");
		assertThat(list).contains("+ Thêm chương");

		assertThat(list).doesNotContain("> Chi tiết </a>");
		assertThat(list).doesNotContain("Reorder");
		assertThat(list).doesNotContain("targetSortOrder");
		assertThat(list).doesNotContain("Sắp xếp lại");
	}

	@Test
	@DisplayName("List Volume dùng một dropdown Thao tác, filter bar, giữ link tiêu đề")
	void volumeListHasSingleActionMenuFilterAndTitleLink() throws Exception {
		String list = read("src/main/resources/templates/admin/novel/volumes.html");

		assertThat(list).contains("class=\"novel-admin-volume-link\"");
		assertThat(list).contains("th:href=\"@{/admin/novel/volumes/{id}(");
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
		assertThat(list).contains("/volumes/{id}/edit");
		assertThat(list).contains("/volumes/{id}/publish");
		assertThat(list).contains("/volumes/{id}/archive");
		assertThat(list).contains("/volumes/{id}/restore");
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
