package com.universe.identity.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerProfileTemplateContractTest {

    @Test
    @DisplayName("Owner profile template (profile.html) displays immutable publicHandle and link to public Community profile without handle edit affordance")
    void ownerProfileTemplateContract() throws Exception {
        String profileHtml = Files.readString(Path.of("src/main/resources/templates/identity/profile.html"), StandardCharsets.UTF_8);

        // 1. Reuses shared navbar
        assertThat(profileHtml).contains("fragments/navbar :: navbar");

        // 2. Renders @publicHandle below display name
        assertThat(profileHtml).contains("th:if=\"${user.publicHandle != null and !user.publicHandle.isBlank()}\"");
        assertThat(profileHtml).contains("th:text=\"'@' + ${user.publicHandle}\"");
        assertThat(profileHtml).contains("class=\"profile-handle-text mb-1 text-muted\"");

        // 3. Renders public handle row in profile-info-row section
        assertThat(profileHtml).contains("Handle công khai");
        assertThat(profileHtml).contains("Xem trang cá nhân công khai");
        assertThat(profileHtml).contains("th:href=\"@{'/community/@' + ${user.publicHandle}}\"");

        // 4. Immutability guarantee: NO handle editing form, input, or mutation endpoint
        assertThat(profileHtml).doesNotContain("name=\"publicHandle\"");
        assertThat(profileHtml).doesNotContain("publicHandleEditForm");
        assertThat(profileHtml).doesNotContain("th:action=\"@{/profile/public-handle");
        assertThat(profileHtml).doesNotContain("th:action=\"@{/profile/handle");
        assertThat(profileHtml).doesNotContain("data-edit-target=\"publicHandleEditForm\"");
    }
}
