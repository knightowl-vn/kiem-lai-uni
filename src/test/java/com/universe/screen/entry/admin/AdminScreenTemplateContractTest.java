package com.universe.screen.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminScreenTemplateContractTest {

    @Test
    @DisplayName("Sidebar contract: sidebar contains link to /admin/screen/franchises")
    void sidebarContainsScreenFranchisesLink() throws Exception {
        String sidebar = read("src/main/resources/templates/admin/fragments/sidebar.html");

        assertThat(sidebar).contains("th:href=\"@{/admin/screen/franchises}\"");
        assertThat(sidebar).contains("Quản lý Screen");
    }

    @Test
    @DisplayName("Franchises page contract: franchises.html defines expected structure and actions")
    void franchisesPageDefinesExpectedStructure() throws Exception {
        String template = read("src/main/resources/templates/admin/screen/franchises.html");

        assertThat(template).contains("admin/fragments/sidebar");
        assertThat(template).contains("admin/fragments/header");
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

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
