package com.universe.shared.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HomeTemplateContractTest {

    @Test
    @DisplayName("home.html định nghĩa đúng Hero với CTA Đọc Novel và Khám phá Wiki, tái sử dụng navbar chung")
    void homeTemplateDefinesHeroAndReusesSharedNavbar() throws Exception {
        String home = read("src/main/resources/templates/home.html");

        // 1. Reuses shared navbar
        assertThat(home).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='home')}\"");
        assertThat(home).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(home).contains("th:href=\"@{/css/theme.css}\"");

        // 2. Hero structure
        assertThat(home).contains("class=\"home-hero\"");
        assertThat(home).contains("Kiếm Lai Universe");
        assertThat(home).contains("class=\"home-hero-actions\"");

        // 3. Primary Hero CTAs
        assertThat(home).contains("th:href=\"@{/novel}\"");
        assertThat(home).contains("Đọc Novel");
        assertThat(home).contains("th:href=\"@{/wiki}\"");
        assertThat(home).contains("Khám phá Wiki");

        // 4. Secondary quiet link for authenticated user
        assertThat(home).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(home).contains("th:href=\"@{/profile}\"");
    }

    @Test
    @DisplayName("home.html định nghĩa Explore section với 2 feature cards (Novel & Wiki) và không có link tính năng chưa làm")
    void homeTemplateDefinesExploreCardsAndNoFakeLinks() throws Exception {
        String home = read("src/main/resources/templates/home.html");

        // 1. Explore section and grid
        assertThat(home).contains("class=\"home-explore-section\"");
        assertThat(home).contains("class=\"home-explore-grid\"");

        // 2. Novel feature card
        assertThat(home).contains("class=\"home-feature-card home-feature-card--novel\"");
        assertThat(home).contains("TIỂU THUYẾT");
        assertThat(home).contains("Đọc Nguyên Tác Kiếm Lai");
        assertThat(home).contains("Bắt đầu đọc");

        // 3. Wiki feature card
        assertThat(home).contains("class=\"home-feature-card home-feature-card--wiki\"");
        assertThat(home).contains("BÁCH KHOA WIKI");
        assertThat(home).contains("Tra Cứu Bách Khoa Wiki");
        assertThat(home).contains("Tra cứu Wiki");

        // 4. No fake / unfinished module navigation
        assertThat(home).doesNotContain("/community");
        assertThat(home).doesNotContain("/forum");
        assertThat(home).doesNotContain("/multimedia");
    }

    @Test
    @DisplayName("theme.css định nghĩa đầy đủ responsive styles và cards layout cho homepage")
    void themeCssDefinesHomepageStyles() throws Exception {
        String css = read("src/main/resources/static/css/theme.css");

        assertThat(css).contains(".home-main");
        assertThat(css).contains(".home-hero");
        assertThat(css).contains(".home-hero-title");
        assertThat(css).contains(".home-hero-actions");
        assertThat(css).contains(".home-btn-primary");
        assertThat(css).contains(".home-btn-secondary");
        assertThat(css).contains(".home-explore-grid");
        assertThat(css).contains(".home-feature-card");
        assertThat(css).contains("@media (max-width: 767.98px)");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
