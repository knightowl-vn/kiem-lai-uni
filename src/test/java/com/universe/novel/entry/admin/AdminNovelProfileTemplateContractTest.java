package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminNovelProfileTemplateContractTest {

    @Test
    @DisplayName("Admin sidebar chứa liên kết Quản lý Novel trỏ đến /admin/novel/profile")
    void sidebarLinksToAdminNovelProfile() throws Exception {
        String sidebar = read("src/main/resources/templates/admin/fragments/sidebar.html");

        assertThat(sidebar).contains("th:href=\"@{/admin/novel/profile}\"");
        assertThat(sidebar).contains("Quản lý Novel");
    }

    @Test
    @DisplayName("Novel Admin subnav navigation fragment chứa liên kết Hồ sơ Novel và Quyển & Chương")
    void subnavContainsExpectedLinks() throws Exception {
        String subnav = read("src/main/resources/templates/admin/novel/fragments/navigation.html");

        assertThat(subnav).contains("th:fragment=\"subnav(activeSubMenu)\"");
        assertThat(subnav).contains("th:href=\"@{/admin/novel/profile}\"");
        assertThat(subnav).contains("Hồ sơ Novel");
        assertThat(subnav).contains("th:href=\"@{/admin/novel/volumes}\"");
        assertThat(subnav).contains("Quyển & Chương");
    }

    @Test
    @DisplayName("Trang Hồ sơ Novel (profile.html) sử dụng multipart form, có file upload, preview, và không có ô nhập coverImageUrl thủ công")
    void profilePageDefinesExpectedStructure() throws Exception {
        String profile = read("src/main/resources/templates/admin/novel/profile.html");

        // Subnav & Form setup
        assertThat(profile).contains("admin/novel/fragments/navigation");
        assertThat(profile).contains("th:action=\"@{/admin/novel/profile}\"");
        assertThat(profile).contains("enctype=\"multipart/form-data\"");

        // System metadata (read-only)
        assertThat(profile).contains("th:text=\"${profile.id}\"");
        assertThat(profile).contains("th:text=\"${profile.slug}\"");

        // Standard form groups
        assertThat(profile).contains("novel-admin-form-group");
        assertThat(profile).contains("novel-admin-required");
        assertThat(profile).contains("th:field=\"*{title}\"");
        assertThat(profile).contains("th:field=\"*{author}\"");
        assertThat(profile).contains("th:field=\"*{description}\"");
        assertThat(profile).contains("th:field=\"*{status}\"");

        // File upload field
        assertThat(profile).contains("type=\"file\"");
        assertThat(profile).contains("name=\"coverImageFile\"");
        assertThat(profile).contains("accept=\"image/jpeg,image/png,image/webp\"");

        // Cover preview & placeholder
        assertThat(profile).contains("profile.coverImageUrl");
        assertThat(profile).contains("Chưa có ảnh bìa");

        // Đảm bảo không còn ô text input chỉnh sửa coverImageUrl thủ công
        assertThat(profile).doesNotContain("th:field=\"*{coverImageUrl}\"");
    }

    @Test
    @DisplayName("Trang Quản lý Volume (volumes.html) chứa subnav fragment")
    void volumesPageIncludesSubnav() throws Exception {
        String volumes = read("src/main/resources/templates/admin/novel/volumes.html");

        assertThat(volumes).contains("admin/novel/fragments/navigation");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
