package com.universe.novel.application.narration;

/**
 * Action determined by chapter narration generation planning for a segment (MS-04.9H.7C1A).
 */
public enum ChapterNarrationGenerationAction {
    /**
     * Narration audio already exists and is compatible with current voice synthesis revision.
     * No generation or modification required.
     */
    SKIP_READY,

    /**
     * Narration audio is missing or previously failed for current voice synthesis revision.
     * Initial audio generation is required.
     */
    GENERATE,

    /**
     * Narration audio exists but is outdated relative to current voice synthesis revision.
     * Audio regeneration is required.
     */
    REGENERATE
}
