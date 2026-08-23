package com.universe.novel.entry.admin;

final class NovelAdminListFilters {

	private NovelAdminListFilters() {
	}

	static String normalizeKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return "";
		}

		return keyword.trim();
	}

	static String normalizeStatus(String status) {
		if (status == null || status.isBlank()) {
			return "";
		}

		return status.trim().toUpperCase();
	}

	static boolean matches(String keyword, String status, String title, String slug, String actualStatus) {
		String normalizedKeyword = normalizeKeyword(keyword);
		String normalizedStatus = normalizeStatus(status);

		if (!normalizedStatus.isEmpty() && !normalizedStatus.equalsIgnoreCase(actualStatus)) {
			return false;
		}

		if (normalizedKeyword.isEmpty()) {
			return true;
		}

		String needle = normalizedKeyword.toLowerCase();
		String safeTitle = title == null ? "" : title.toLowerCase();
		String safeSlug = slug == null ? "" : slug.toLowerCase();

		return safeTitle.contains(needle) || safeSlug.contains(needle);
	}
}
