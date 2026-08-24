package com.universe.novel.entry.admin.form;

public class RestoreChapterRevisionForm {

    private Long expectedAggregateVersion;

    private String editSummary;

    public Long getExpectedAggregateVersion() {
        return expectedAggregateVersion;
    }

    public void setExpectedAggregateVersion(Long expectedAggregateVersion) {
        this.expectedAggregateVersion = expectedAggregateVersion;
    }

    public String getEditSummary() {
        return editSummary;
    }

    public void setEditSummary(String editSummary) {
        this.editSummary = editSummary;
    }
}
