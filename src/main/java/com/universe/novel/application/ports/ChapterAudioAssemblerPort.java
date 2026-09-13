package com.universe.novel.application.ports;

import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;

/**
 * Application port for assembling ordered narration segment audio into one temporary chapter audio resource.
 */
public interface ChapterAudioAssemblerPort {

    /**
     * Assembles ordered segment audio sources into one caller-owned temporary chapter audio result.
     */
    ChapterAudioAssemblyResult assemble(ChapterAudioAssemblyRequest request);
}
