package com.universe.screen.entry.admin.form;

public class EditScreenFranchiseForm {

    private String name;
    private String slug;

    public EditScreenFranchiseForm() {
    }

    public EditScreenFranchiseForm(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }
}
