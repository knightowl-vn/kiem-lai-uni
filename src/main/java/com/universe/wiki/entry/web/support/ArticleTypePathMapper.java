package com.universe.wiki.entry.web.support;

import com.universe.wiki.domain.article.ArticleType;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Chuyển đổi giữa ArticleType trong Domain và chuỗi thân thiện dùng trên URL.
 *
 * Ví dụ: TIMELINE_EVENT <-> timeline-event
 */
@Component
public class ArticleTypePathMapper {

	/**
	 * Chuyển giá trị trên URL thành ArticleType.
	 */
	public ArticleType fromPath(String pathValue) {
		if (pathValue == null || pathValue.isBlank()) {

			throw new InvalidArticleTypePathException(pathValue);
		}

		String normalizedPath = pathValue.trim().toLowerCase(Locale.ROOT);

		return switch (normalizedPath) {

		case "character" -> ArticleType.CHARACTER;

		case "realm" -> ArticleType.REALM;

		case "cultivation-path" -> ArticleType.CULTIVATION_PATH;

		case "faction" -> ArticleType.FACTION;

		case "item" -> ArticleType.ITEM;

		case "technique" -> ArticleType.TECHNIQUE;

		case "location" -> ArticleType.LOCATION;

		case "world" -> ArticleType.WORLD;

		case "timeline-event" -> ArticleType.TIMELINE_EVENT;

		default -> throw new InvalidArticleTypePathException(pathValue);
		};
	}

	/**
	 * Chuyển ArticleType thành chuỗi dùng trên URL.
	 */
	public String toPath(ArticleType articleType) {
		Objects.requireNonNull(articleType, "Article type không được để trống.");

		return switch (articleType) {

		case CHARACTER -> "character";

		case REALM -> "realm";

		case CULTIVATION_PATH -> "cultivation-path";

		case FACTION -> "faction";

		case ITEM -> "item";

		case TECHNIQUE -> "technique";

		case LOCATION -> "location";

		case WORLD -> "world";

		case TIMELINE_EVENT -> "timeline-event";
		};
	}
}