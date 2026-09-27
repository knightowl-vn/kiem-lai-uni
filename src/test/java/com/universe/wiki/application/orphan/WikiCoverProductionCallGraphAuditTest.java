package com.universe.wiki.application.orphan;

import com.universe.wiki.application.article.cover.WikiArticleCoverOrchestrator;
import com.universe.wiki.entry.admin.AdminWikiArticleCommandController;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Cover Production Call Graph Architecture Audit Tests")
class WikiCoverProductionCallGraphAuditTest {

    @Test
    @DisplayName("Kiểm toán AdminWikiArticleCommandController: không có endpoint HTTP nào chấp nhận UUID coverMediaAssetId từ client")
    void shouldProveAdminControllerNeverAcceptsArbitraryCoverMediaAssetId() {
        Method[] methods = AdminWikiArticleCommandController.class.getDeclaredMethods();

        for (Method method : methods) {
            for (Parameter parameter : method.getParameters()) {
                // Ensure no parameter exposes or binds arbitrary cover MediaAsset ID
                if (parameter.getType().equals(UUID.class)) {
                    // Allowed UUID parameters are only articleId, revisionId, aliasId, etc.
                    assertThat(parameter.getName())
                            .as("AdminWikiArticleCommandController method %s must not accept coverMediaAssetId parameter", method.getName())
                            .isNotEqualTo("coverMediaAssetId");
                }
            }
        }
    }

    @Test
    @DisplayName("Kiểm toán Form DTOs: CreateWikiArticleForm và EditWikiArticleForm không chứa thuộc tính coverMediaAssetId")
    void shouldProveFormsNeverContainCoverMediaAssetIdField() {
        Field[] createFields = CreateWikiArticleForm.class.getDeclaredFields();
        boolean createHasCoverId = Arrays.stream(createFields)
                .anyMatch(f -> f.getName().toLowerCase().contains("covermediaassetid") || f.getName().equals("coverId"));
        assertThat(createHasCoverId)
                .as("CreateWikiArticleForm must not contain coverMediaAssetId")
                .isFalse();

        Field[] editFields = EditWikiArticleForm.class.getDeclaredFields();
        boolean editHasCoverId = Arrays.stream(editFields)
                .anyMatch(f -> f.getName().toLowerCase().contains("covermediaassetid") || f.getName().equals("coverId"));
        assertThat(editHasCoverId)
                .as("EditWikiArticleForm must not contain coverMediaAssetId")
                .isFalse();
    }

    @Test
    @DisplayName("Kiểm toán WikiArticleCoverOrchestrator: các phương thức điều phối chỉ chấp nhận WikiCoverUpload hoặc removeCover, không nhận arbitrary UUID")
    void shouldProveOrchestratorDoesNotAcceptArbitraryCoverAssetIdFromCallers() {
        Method[] methods = WikiArticleCoverOrchestrator.class.getDeclaredMethods();

        for (Method method : methods) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType().equals(UUID.class)) {
                    // Only articleId or similar aggregates should be passed, never arbitrary targetCoverId
                    assertThat(parameter.getName())
                            .as("WikiArticleCoverOrchestrator method %s should not accept external targetCoverId", method.getName())
                            .isNotEqualTo("targetCoverId");
                }
            }
        }
    }
}
