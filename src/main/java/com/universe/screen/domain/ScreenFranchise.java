package com.universe.screen.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight grouping metadata for related audiovisual Screen titles.
 *
 * <p>A franchise provides organizational and navigational hierarchy across
 * installments (e.g. Kiếm Lai 1, Kiếm Lai 2, Kiếm Lai Movie 1).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar UUID identity ({@code id});</li>
 *   <li>Normalized public URL slug ({@code slug});</li>
 *   <li>Display name ({@code name}) with outer whitespace trimmed, preserving accents and casing;</li>
 *   <li>Zero framework or persistence dependencies (pure Java domain).</li>
 * </ul>
 */
public final class ScreenFranchise {

    private final UUID id;
    private String slug;
    private String name;

    /**
     * Constructs a ScreenFranchise with validated attributes.
     *
     * @param id non-null franchise UUID
     * @param slug non-blank, valid URL slug
     * @param name non-blank franchise display name
     */
    public ScreenFranchise(UUID id, String slug, String name) {
        this.id = Objects.requireNonNull(id, "Franchise ID cannot be null.");
        this.slug = ScreenSlugValidator.validate(slug);
        this.name = validateName(name);
    }

    /**
     * Factory method to create a new ScreenFranchise.
     */
    public static ScreenFranchise create(UUID id, String slug, String name) {
        return new ScreenFranchise(id, slug, name);
    }

    /**
     * Updates franchise display name and URL slug.
     */
    public void update(String slug, String name) {
        String validatedSlug = ScreenSlugValidator.validate(slug);
        String validatedName = validateName(name);
        this.slug = validatedSlug;
        this.name = validatedName;
    }

    /**
     * Renames the franchise while maintaining its current slug.
     */
    public void rename(String name) {
        this.name = validateName(name);
    }

    /**
     * Changes the franchise slug while maintaining its current name.
     */
    public void changeSlug(String slug) {
        this.slug = ScreenSlugValidator.validate(slug);
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "Franchise name cannot be null.");
        String trimmed = name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Franchise name cannot be blank.");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScreenFranchise that = (ScreenFranchise) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScreenFranchise{" +
                "id=" + id +
                ", slug='" + slug + '\'' +
                ", name='" + name + '\'' +
                '}';
    }
}
