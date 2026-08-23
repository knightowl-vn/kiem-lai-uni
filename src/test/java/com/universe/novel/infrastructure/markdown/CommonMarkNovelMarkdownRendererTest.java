package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkNovelMarkdownRendererTest {

	private final CommonMarkNovelMarkdownRenderer renderer = new CommonMarkNovelMarkdownRenderer();

	@Test
	@DisplayName("Render Markdown cơ bản thành HTML an toàn")
	void shouldRenderBasicMarkdown() {
		String html = renderer.renderToHtml("## Tiêu đề\n\nĐoạn **in đậm** và _in nghiêng_.");

		assertThat(html).contains("<h2>");
		assertThat(html).contains("Tiêu đề");
		assertThat(html).contains("<strong>in đậm</strong>");
		assertThat(html).contains("<em>in nghiêng</em>");
	}

	@Test
	@DisplayName("Loại bỏ raw HTML trong Markdown")
	void shouldStripRawHtml() {
		String html = renderer.renderToHtml("Hello <script>alert(1)</script> world");

		assertThat(html).doesNotContain("<script>");
		assertThat(html).contains("Hello");
		assertThat(html).contains("world");
	}

	@Test
	@DisplayName("Blank markdown trả về chuỗi rỗng")
	void shouldReturnEmptyForBlankInput() {
		assertThat(renderer.renderToHtml(null)).isEmpty();
		assertThat(renderer.renderToHtml("   ")).isEmpty();
	}
}
