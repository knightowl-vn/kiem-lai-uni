package com.universe.screen.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminScreenTemplateContractTest {

    @Test
    @DisplayName("Sidebar contract: sidebar contains exactly one Screen item 'Quản lý Screen' and no separate 'Tiêu đề Screen'")
    void sidebarContainsScreenLinks() throws Exception {
        String sidebar = read("src/main/resources/templates/admin/fragments/sidebar.html");

        assertThat(sidebar).contains("Quản lý Screen");
        assertThat(sidebar).contains("th:href=\"@{/admin/screen/titles}\"");
        assertThat(sidebar).doesNotContain("Tiêu đề Screen");
        assertThat(sidebar).doesNotContain("th:href=\"@{/admin/screen/franchises}\"");
    }

    @Test
    @DisplayName("Franchises page contract: franchises.html defines expected structure and actions")
    void franchisesPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/franchises.html");

        assertThat(template).contains("admin/fragments/sidebar");
        assertThat(template).contains("admin/fragments/header");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles}\"");
        assertThat(template).contains("th:href=\"@{/admin/screen/franchises}\"");
        assertThat(template).contains("Tiêu đề Screen");
        assertThat(template).contains("Franchise");
        assertThat(template).contains("th:href=\"@{/admin/screen/franchises/new}\"");
        assertThat(template).contains("th:each=\"franchise : ${franchises}\"");
        assertThat(template).contains("th:text=\"${franchise.name}\"");
        assertThat(template).contains("th:text=\"${franchise.slug}\"");
        assertThat(template).contains("th:text=\"${franchise.memberCount}\"");
        assertThat(template).contains("/memberships(id=${franchise.id})");
        assertThat(template).contains("/edit(id=${franchise.id})");
    }

    @Test
    @DisplayName("Franchise create contract: franchise-create.html contains form with name and slug")
    void franchiseCreatePageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/franchise-create.html");

        assertThat(template).contains("th:action=\"@{/admin/screen/franchises}\"");
        assertThat(template).contains("th:field=\"*{name}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("type=\"submit\"");
    }

    @Test
    @DisplayName("Franchise edit contract: franchise-edit.html contains update form and fields")
    void franchiseEditPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/franchise-edit.html");

        assertThat(template).contains("/update(id=${franchise.id})");
        assertThat(template).contains("th:field=\"*{name}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("th:text=\"${franchise.id}\"");
    }

    @Test
    @DisplayName("Franchise memberships contract: franchise-memberships.html contains member table, reorder form, remove form, and add form")
    void franchiseMembershipsPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/franchise-memberships.html");

        // Membership table
        assertThat(template).contains("th:each=\"member : ${members}\"");
        assertThat(template).contains("th:value=\"${member.displayOrder}\"");
        assertThat(template).contains("th:text=\"${member.title}\"");
        assertThat(template).contains("th:text=\"${member.titleSlug}\"");

        // Reorder form
        assertThat(template).contains("/memberships/{mid}/order");

        // Remove form
        assertThat(template).contains("/memberships/{mid}/remove");

        // Add form
        assertThat(template).contains("th:action=\"@{/admin/screen/franchises/{id}/memberships(id=${franchise.id})}\"");
        assertThat(template).contains("th:field=\"*{titleId}\"");
        assertThat(template).contains("th:field=\"*{displayOrder}\"");
    }

    @Test
    @DisplayName("Titles page contract: titles.html defines expected structure, columns, and actions")
    void titlesPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/titles.html");

        assertThat(template).contains("admin/fragments/sidebar");
        assertThat(template).contains("admin/fragments/header");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles}\"");
        assertThat(template).contains("th:href=\"@{/admin/screen/franchises}\"");
        assertThat(template).contains("Tiêu đề Screen");
        assertThat(template).contains("Franchise");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles/new}\"");
        assertThat(template).contains("th:each=\"title : ${titles}\"");
        assertThat(template).contains("th:text=\"${title.title}\"");
        assertThat(template).contains("th:text=\"${title.slug}\"");
        assertThat(template).contains("th:text=\"${title.structure}\"");
        assertThat(template).contains("th:text=\"${title.presentation}\"");
        assertThat(template).contains("th:text=\"${title.status}\"");
        assertThat(template).contains("/admin/screen/titles/{id}(id=${title.id})");
        assertThat(template).contains("/edit(id=${title.id})");
    }

    @Test
    @DisplayName("Title create contract: title-create.html contains form with all domain fields")
    void titleCreatePageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/title-create.html");

        assertThat(template).contains("th:action=\"@{/admin/screen/titles}\"");
        assertThat(template).contains("th:field=\"*{title}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("th:field=\"*{originalTitle}\"");
        assertThat(template).contains("th:field=\"*{synopsis}\"");
        assertThat(template).contains("th:field=\"*{structure}\"");
        assertThat(template).contains("th:field=\"*{presentation}\"");
        assertThat(template).contains("th:field=\"*{posterMediaAssetId}\"");
        assertThat(template).contains("th:field=\"*{backdropMediaAssetId}\"");
        assertThat(template).contains("type=\"submit\"");
    }

    @Test
    @DisplayName("Title edit contract: title-edit.html contains update action and existing data")
    void titleEditPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/title-edit.html");

        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{id}/update(id=${title.id})}\"");
        assertThat(template).contains("th:field=\"*{title}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("th:field=\"*{originalTitle}\"");
        assertThat(template).contains("th:field=\"*{synopsis}\"");
        assertThat(template).contains("th:field=\"*{structure}\"");
        assertThat(template).contains("th:field=\"*{presentation}\"");
        assertThat(template).contains("th:field=\"*{posterMediaAssetId}\"");
        assertThat(template).contains("th:field=\"*{backdropMediaAssetId}\"");
        assertThat(template).contains("th:text=\"${title.id}\"");
        assertThat(template).contains("th:text=\"${title.status}\"");
    }

    @Test
    @DisplayName("Title detail contract: title-detail.html contains detail state, entries link, publish, and archive actions")
    void titleDetailPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/title-detail.html");

        assertThat(template).contains("th:text=\"${title.title}\"");
        assertThat(template).contains("th:text=\"${title.slug}\"");
        assertThat(template).contains("title.status");
        assertThat(template).contains("/entries(id=${title.id})");
        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{id}/publish(id=${title.id})}\"");
        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{id}/archive(id=${title.id})}\"");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles/{id}/edit(id=${title.id})}\"");
    }

    @Test
    @DisplayName("Entries page contract: entries.html defines expected structure, columns, and actions")
    void entriesPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/entries.html");

        assertThat(template).contains("admin/fragments/sidebar");
        assertThat(template).contains("admin/fragments/header");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles/{id}(id=${title.id})}\"");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles/{titleId}/entries/new(titleId=${title.id})}\"");
        assertThat(template).contains("th:each=\"entry : ${entries}\"");
        assertThat(template).contains("th:text=\"${entry.title}\"");
        assertThat(template).contains("th:text=\"${entry.slug}\"");
        assertThat(template).contains("th:text=\"${entry.status}\"");
        assertThat(template).contains("/admin/screen/titles/{titleId}/entries/{entryId}(titleId=${title.id}, entryId=${entry.id})");
        assertThat(template).contains("/edit(titleId=${title.id}, entryId=${entry.id})");
    }

    @Test
    @DisplayName("Entry create contract: entry-create.html contains form with all entry fields")
    void entryCreatePageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/entry-create.html");

        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{titleId}/entries(titleId=${title.id})}\"");
        assertThat(template).contains("th:field=\"*{title}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("th:field=\"*{type}\"");
        assertThat(template).contains("th:field=\"*{number}\"");
        assertThat(template).contains("th:field=\"*{durationSeconds}\"");
        assertThat(template).contains("th:field=\"*{synopsis}\"");
        assertThat(template).contains("type=\"submit\"");
    }

    @Test
    @DisplayName("Entry edit contract: entry-edit.html contains update action, fields, and read-only media references")
    void entryEditPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/entry-edit.html");

        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{titleId}/entries/{entryId}/update(titleId=${title.id}, entryId=${entry.id})}\"");
        assertThat(template).contains("th:field=\"*{title}\"");
        assertThat(template).contains("th:field=\"*{slug}\"");
        assertThat(template).contains("th:field=\"*{type}\"");
        assertThat(template).contains("th:field=\"*{number}\"");
        assertThat(template).contains("th:field=\"*{durationSeconds}\"");
        assertThat(template).contains("th:field=\"*{synopsis}\"");
        assertThat(template).contains("th:text=\"${entry.id}\"");
        assertThat(template).contains("th:text=\"${entry.status}\"");
        assertThat(template).contains("th:text=\"${form.videoMediaAssetId}\"");
        assertThat(template).contains("th:text=\"${form.thumbnailMediaAssetId}\"");
    }

    @Test
    @DisplayName("Entry detail contract: entry-detail.html contains detail state, publish, and archive actions")
    void entryDetailPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/entry-detail.html");

        assertThat(template).contains("th:text=\"${entry.title}\"");
        assertThat(template).contains("th:text=\"${entry.slug}\"");
        assertThat(template).contains("entry.status");
        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{titleId}/entries/{entryId}/publish(titleId=${title.id}, entryId=${entry.id})}\"");
        assertThat(template).contains("th:action=\"@{/admin/screen/titles/{titleId}/entries/{entryId}/archive(titleId=${title.id}, entryId=${entry.id})}\"");
        assertThat(template).contains("th:href=\"@{/admin/screen/titles/{titleId}/entries/{entryId}/edit(titleId=${title.id}, entryId=${entry.id})}\"");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
