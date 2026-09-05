package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminNovelManagedVoiceTemplateContractTest {

    @Test
    @DisplayName("Novel Admin subnav navigation fragment chứa liên kết Giọng đọc")
    void subnavContainsVoicesLink() throws Exception {
        String subnav = read("src/main/resources/templates/admin/novel/fragments/navigation.html");

        assertThat(subnav).contains("th:fragment=\"subnav(activeSubMenu)\"");
        assertThat(subnav).contains("th:href=\"@{/admin/novel/narration/voices}\"");
        assertThat(subnav).contains("Giọng đọc");
    }

    @Test
    @DisplayName("Trang danh sách giọng đọc (voices.html) chứa đúng bảng, cột, filter, và action forms")
    void voicesListPageDefinesExpectedStructure() throws Exception {
        String voices = read("src/main/resources/templates/admin/novel/voices.html");

        // Subnav & layout
        assertThat(voices).contains("admin/novel/fragments/navigation");
        assertThat(voices).contains("th:action=\"@{/admin/novel/narration/voices}\"");
        assertThat(voices).contains("th:href=\"@{/admin/novel/narration/voices/new}\"");

        // Table headers & fields
        assertThat(voices).contains("displayName");
        assertThat(voices).contains("voiceKey");
        assertThat(voices).contains("providerVoiceId");
        assertThat(voices).contains("synthesisRevision");
        assertThat(voices).contains("displayOrder");
        assertThat(voices).contains("defaultVoice");

        // Action forms
        assertThat(voices).contains("/activate");
        assertThat(voices).contains("/disable");
        assertThat(voices).contains("/default");
    }

    @Test
    @DisplayName("Trang tạo giọng đọc (voice-create.html) chứa form tạo, warning banner, và không có ô nhập displayOrder")
    void voiceCreatePageDefinesExpectedStructure() throws Exception {
        String create = read("src/main/resources/templates/admin/novel/voice-create.html");

        assertThat(create).contains("th:action=\"@{/admin/novel/narration/voices}\"");
        assertThat(create).contains("th:field=\"*{voiceKey}\"");
        assertThat(create).contains("th:field=\"*{displayName}\"");
        assertThat(create).contains("th:field=\"*{providerVoiceId}\"");
        assertThat(create).contains("th:field=\"*{defaultVoice}\"");

        // displayOrder is automatic and not exposed
        assertThat(create).doesNotContain("th:field=\"*{displayOrder}\"");

        // Warning banner & note & regex-aligned help text
        assertThat(create).contains("providerWarning");
        assertThat(create).contains("Lưu ý về Voice Key");
        assertThat(create).contains("2-100");
    }

    @Test
    @DisplayName("Trang chỉnh sửa giọng đọc (voice-edit.html) chứa form metadata, form provider mapping, không có ô nhập displayOrder, fallback mapping và script xác nhận")
    void voiceEditPageDefinesExpectedStructure() throws Exception {
        String edit = read("src/main/resources/templates/admin/novel/voice-edit.html");

        assertThat(edit).contains("/metadata");
        assertThat(edit).contains("/provider");
        assertThat(edit).contains("th:field=\"*{displayName}\"");
        assertThat(edit).contains("th:field=\"*{providerVoiceId}\"");

        // displayOrder is automatic and not exposed
        assertThat(edit).doesNotContain("th:field=\"*{displayOrder}\"");

        // Introductory copy mentions display name and provider mapping only
        assertThat(edit).contains("Cập nhật tên hiển thị hoặc thay đổi liên kết Provider Voice ID.");
        assertThat(edit).doesNotContain("thứ tự");

        // Revision indicator & distinct-value semantics copy
        assertThat(edit).contains("synthesisRevision");
        assertThat(edit).contains("sang một giá trị khác");
        assertThat(edit).contains("providerWarning");

        // Safe fallback option for unlisted current provider ID
        assertThat(edit).contains("!isCurrentProviderVoiceDiscovered");

        // Confirmation behavior script
        assertThat(edit).contains("admin-list-menus.js");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
