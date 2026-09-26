package com.universe.interaction.entry.web.personal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserAuthoredCommentTemplateContractTest — Personal Comments Template Contract Tests")
class UserAuthoredCommentTemplateContractTest {

    @Test
    @DisplayName("Personal authored comments page (comments.html) defines header, filter nav, card structure, empty state, and pagination")
    void commentsPageDefinesExpectedContract() throws Exception {
        String template = read("src/main/resources/templates/interaction/personal/comments.html");

        // 1. Shared navbar & stylesheet references
        assertThat(template).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='')}\"");
        assertThat(template).contains("th:href=\"@{/css/shared/my-comments.css}\"");

        // 2. Heading
        assertThat(template).contains("Bình luận của tôi");

        // 3. Filter Navigation with native links and active state
        assertThat(template).contains("class=\"kl-my-comments-nav\"");
        assertThat(template).contains("th:href=\"@{/comments/my(context='all')}\"");
        assertThat(template).contains("th:href=\"@{/comments/my(context='novel')}\"");
        assertThat(template).contains("th:href=\"@{/comments/my(context='wiki')}\"");
        assertThat(template).contains("th:attr=\"aria-current=${activeFilter == 'all'} ? 'page' : null\"");
        assertThat(template).contains("th:attr=\"aria-current=${activeFilter == 'novel'} ? 'page' : null\"");
        assertThat(template).contains("th:attr=\"aria-current=${activeFilter == 'wiki'} ? 'page' : null\"");

        // 4. List iteration & card structure
        assertThat(template).contains("th:if=\"${!commentsPage.isEmpty()}\"");
        assertThat(template).contains("th:each=\"item : ${commentsPage.items}\"");
        assertThat(template).contains("class=\"kl-my-comment-card\"");

        // 5. Context badge, Type badge, Relative Time
        assertThat(template).contains("kl-my-comment-context-badge");
        assertThat(template).contains("item.contextLabel");
        assertThat(template).contains("kl-my-comment-type-badge");
        assertThat(template).contains("item.typeLabel");
        assertThat(template).contains("data-relative-time");
        assertThat(template).contains("item.createdAt");

        // 6. Target Heading (Available & Unavailable branches)
        assertThat(template).contains("th:if=\"${item.targetAvailable}\"");
        assertThat(template).contains("th:text=\"${item.targetTitle}\"");
        assertThat(template).contains("th:unless=\"${item.targetAvailable}\"");
        assertThat(template).contains("item.targetUnavailableLabel");

        // 7. Comment body
        assertThat(template).contains("kl-my-comment-body");
        assertThat(template).contains("th:text=\"${item.body}\"");

        // 8. Safe Context Link
        assertThat(template).contains("th:if=\"${item.targetAvailable and item.contextUrl != null}\"");
        assertThat(template).contains("th:href=\"@{${item.contextUrl}}\"");
        assertThat(template).contains("Xem trong ngữ cảnh");

        // 9. Empty State
        assertThat(template).contains("id=\"myCommentsEmpty\"");
        assertThat(template).contains("th:if=\"${commentsPage.isEmpty()}\"");
        assertThat(template).contains("th:if=\"${activeFilter == 'all'}\"");
        assertThat(template).contains("Bạn chưa viết bình luận nào.");
        assertThat(template).contains("th:if=\"${activeFilter == 'novel'}\"");
        assertThat(template).contains("Bạn chưa có bình luận trong Novel.");
        assertThat(template).contains("th:if=\"${activeFilter == 'wiki'}\"");
        assertThat(template).contains("Bạn chưa có bình luận trong Wiki.");

        // 10. Pagination preserving context filter
        assertThat(template).contains("th:if=\"${commentsPage.totalPages > 1}\"");
        assertThat(template).contains("th:href=\"@{/comments/my(context=${activeFilter}, page=${commentsPage.page - 1})}\"");
        assertThat(template).contains("th:href=\"@{/comments/my(context=${activeFilter}, page=${commentsPage.page + 1})}\"");
        assertThat(template).contains("commentsPage.first");
        assertThat(template).contains("commentsPage.last");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
