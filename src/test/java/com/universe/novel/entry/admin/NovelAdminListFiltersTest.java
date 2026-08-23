package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NovelAdminListFiltersTest {

	@Test
	@DisplayName("Khớp keyword theo title hoặc slug, không phân biệt hoa thường")
	void matchesKeywordOnTitleOrSlug() {
		assertThat(NovelAdminListFilters.matches("mot", "", "Quyen Mot", "tap-1", "DRAFT")).isTrue();
		assertThat(NovelAdminListFilters.matches("TAP-1", "", "Quyen Mot", "tap-1", "DRAFT")).isTrue();
		assertThat(NovelAdminListFilters.matches("khac", "", "Quyen Mot", "tap-1", "DRAFT")).isFalse();
	}

	@Test
	@DisplayName("Lọc status khi được chỉ định")
	void matchesStatusWhenProvided() {
		assertThat(NovelAdminListFilters.matches("", "DRAFT", "Title", "slug", "DRAFT")).isTrue();
		assertThat(NovelAdminListFilters.matches("", "published", "Title", "slug", "PUBLISHED")).isTrue();
		assertThat(NovelAdminListFilters.matches("", "ARCHIVED", "Title", "slug", "DRAFT")).isFalse();
	}
}
