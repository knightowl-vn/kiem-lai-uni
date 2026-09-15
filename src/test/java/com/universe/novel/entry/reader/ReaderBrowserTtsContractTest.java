package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Novel Reader Browser TTS Engine, Controller & Player Contract Tests")
class ReaderBrowserTtsContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) includes narration player dock, progress bar, controls, settings panel, and script tags")
    void chapterReadingPageIncludesNarrationPlayerContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // 1. Narration Player Section, Dock, Progress Bar & Controls
        assertThat(chapterPage).contains("id=\"novelNarrationPlayer\"");
        assertThat(chapterPage).contains("id=\"novelNarrationProgressBar\"");
        assertThat(chapterPage).contains("id=\"novelNarrationProgressFill\"");
        assertThat(chapterPage).containsOnlyOnce("id=\"novelNarrationProgressCurrent\"");
        assertThat(chapterPage).containsOnlyOnce("id=\"novelNarrationProgressTotal\"");
        assertThat(chapterPage).contains("class=\"novel-narration-timeline\"",
                "aria-valuemin=\"0\"", "aria-valuemax=\"100\"", "aria-valuenow=\"0\"",
                "aria-valuetext=\"0 trên 0 câu\"", "tabindex=\"0\"");
        assertThat(chapterPage).contains("role=\"slider\"");
        assertThat(chapterPage).contains("class=\"novel-narration-dock\"");
        assertThat(chapterPage).contains("class=\"novel-narration-dock-lead\"");
        assertThat(chapterPage).contains("id=\"novelNarrationCollapseToggle\"");
        assertThat(chapterPage).contains("id=\"novelNarrationPlayPauseBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationPrevBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationNextBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationStatusText\"");
        assertThat(chapterPage).contains("id=\"novelNarrationSettingsTrigger\"");

        // 2. Narration Settings Popover Panel with Voice, Rate, Follow Mode & Auto-Next
        assertThat(chapterPage).contains("id=\"novelNarrationSettingsPanel\"");
        assertThat(chapterPage).contains("id=\"novelNarrationSettingsCloseBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationVoiceSelect\"");
        assertThat(chapterPage).contains("id=\"novelNarrationRateSelect\"");
        assertThat(chapterPage).contains("id=\"novelNarrationFollowToggle\"");
        assertThat(chapterPage).contains("role=\"switch\"");
        assertThat(chapterPage).contains("aria-checked=\"true\"");
        assertThat(chapterPage).contains("Theo dõi giọng đọc");
        assertThat(chapterPage).contains("id=\"novelNarrationAutoNextToggle\"");
        assertThat(chapterPage).contains("Tự động sang chương");

        // 3. Narration JavaScript Inclusions
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/narration-text-parser.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/browser-tts-engine.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/managed-audio-engine.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/narration-controller.js}\"");
        assertThat(chapterPage).contains("defer");
    }

    @Test
    @DisplayName("narration-text-parser.js defines expected extraction, filtering, nested block duplicate prevention, and fallback splitting")
    void narrationTextParserScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/narration-text-parser.js");

        // 1. Module Export / UMD Contract
        assertThat(js).contains("NarrationTextParser");
        assertThat(js).contains("KiemLai.NarrationTextParser");
        assertThat(js).contains("module.exports");

        // 2. Core Parser APIs
        assertThat(js).contains("cleanText");
        assertThat(js).contains("segmentParagraph");
        assertThat(js).contains("parseChapterBody");
        assertThat(js).contains("parseText");
        assertThat(js).contains("DEFAULT_OPTIONS");

        // 3. Excluded Elements and Ancestor Chain Checking
        assertThat(js).contains("'SCRIPT'");
        assertThat(js).contains("'STYLE'");
        assertThat(js).contains("'BUTTON'");
        assertThat(js).contains("'SVG'");
        assertThat(js).contains("aria-hidden");
        assertThat(js).contains("novel-wiki-lookup-action-btn");
        assertThat(js).contains("novel-reading-settings-popover");
        assertThat(js).contains("isExcludedNode(node, options, container)");

        // 4. Nested Block Duplicate Prevention (Innermost / Leaf Block Filtering)
        assertThat(js).contains("hasChildBlock = blockEl.querySelector(blockSelector) !== null");
        assertThat(js).contains("blockElements = rawBlocks.filter");

        // 5. Text Cleaning and NFC Normalization
        assertThat(js).contains(".normalize('NFC')");
        assertThat(js).contains(".replace(/\\s+/g, ' ')");

        // 6. Chunk Structure and Fallback Splitting
        assertThat(js).contains("fallbackSplitLongChunk");
        assertThat(js).contains("maxChunkLength");
        assertThat(js).contains("paragraphIndex");
        assertThat(js).contains("globalChunkIndex");
    }

    @Test
    @DisplayName("browser-tts-engine.js defines SpeechSynthesis wrapper, Vietnamese-only voice filtering, sequence tokening, pending-playback race fix, and lifecycle controls")
    void browserTtsEngineScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/browser-tts-engine.js");

        // 1. Module Export / UMD Contract
        assertThat(js).contains("BrowserTtsEngine");
        assertThat(js).contains("KiemLai.BrowserTtsEngine");
        assertThat(js).contains("EngineState");
        assertThat(js).contains("ENGINE_CAPABILITIES");

        // 2. Browser Detection & Capabilities
        assertThat(js).contains("'speechSynthesis' in window");
        assertThat(js).contains("canSeekTime: false");
        assertThat(js).contains("canSeekSentence: true");
        assertThat(js).contains("supportsBackgroundPlayback: false");

        // 3. State Lifecycle & Immediate Transition on Play
        assertThat(js).contains("UNINITIALIZED");
        assertThat(js).contains("IDLE");
        assertThat(js).contains("PLAYING");
        assertThat(js).contains("PAUSED");
        assertThat(js).contains("STOPPED");
        assertThat(js).contains("ERROR");
        assertThat(js).contains("this._transitionState(EngineState.PLAYING)");

        // 4. Voice Discovery, Vietnamese-Only Filtering & Utterance Lang
        assertThat(js).contains("isVietnameseVoice");
        assertThat(js).contains("getVietnameseVoices");
        assertThat(js).contains("getSortedVoices");
        assertThat(js).contains("startsWith('vi')");
        assertThat(js).contains("utterance.lang");
        assertThat(js).contains("Thiết bị chưa có giọng đọc Tiếng Việt.");
        assertThat(js).contains("window.speechSynthesis.onvoiceschanged = null");

        // 5. Sequence Tokening & Stale Callback Protection
        assertThat(js).contains("utteranceSequenceId");
        assertThat(js).contains("utteranceId !== this.utteranceSequenceId");
        assertThat(js).contains("canceled");
        assertThat(js).contains("interrupted");

        // 6. Playback Controls & Chunk Navigation
        assertThat(js).contains("loadChunks");
        assertThat(js).contains("play");
        assertThat(js).contains("pause");
        assertThat(js).contains("resume");
        assertThat(js).contains("stop");
        assertThat(js).contains("seekToChunk");
        assertThat(js).contains("nextChunk");
        assertThat(js).contains("previousChunk");
        assertThat(js).contains("destroy");
    }

    @Test
    @DisplayName("narration-controller.js coordinates parser, engine, follow mode highlights, progress bar, settings popover, and graceful auto-next")
    void narrationControllerScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/narration-controller.js");

        // 1. Module Export / UMD Contract
        assertThat(js).contains("NarrationController");
        assertThat(js).contains("KiemLai.NarrationController");
        assertThat(js).contains("HIGHLIGHT_CLASS");
        assertThat(js).contains("novel-narration-highlight");
        assertThat(js).contains("module.exports");

        // 2. DOM Selectors & Initialization
        assertThat(js).contains("DEFAULT_SELECTORS");
        assertThat(js).contains("#novelNarrationPlayer");
        assertThat(js).contains(".novel-reader-chapter-body");
        assertThat(js).contains("#novelNarrationPlayPauseBtn");
        assertThat(js).contains("#novelNarrationPrevBtn");
        assertThat(js).contains("#novelNarrationNextBtn");
        assertThat(js).contains("#novelNarrationProgressBar");
        assertThat(js).contains("#novelNarrationProgressFill");
        assertThat(js).contains("#novelNarrationVoiceSelect");
        assertThat(js).contains("#novelNarrationRateSelect");
        assertThat(js).contains("#novelNarrationFollowToggle");
        assertThat(js).contains("#novelNarrationAutoNextToggle");
        assertThat(js).contains("#novelNarrationCollapseToggle");
        assertThat(js).contains("#novelNarrationSettingsTrigger");
        assertThat(js).contains("#novelNarrationSettingsPanel");
        assertThat(js).contains("nextChapterLink");

        // 3. Engine Orchestration & Callbacks
        assertThat(js).contains("onStateChange");
        assertThat(js).contains("onChunkStart");
        assertThat(js).contains("onChapterEnd");
        assertThat(js).contains("onVoicesChanged");
        assertThat(js).contains("parseChapterBody");
        assertThat(js).contains("loadChunks");

        // 4. Follow Mode Highlights & Smooth Viewport Visibility with Dynamic Dock Offsets
        assertThat(js).contains("setFollowMode");
        assertThat(js).contains("_highlightChunk");
        assertThat(js).contains("_clearHighlight");
        assertThat(js).contains("_isElementComfortablyVisible");
        assertThat(js).contains("_scrollElementIntoView");
        assertThat(js).contains("_getViewportClearance");
        assertThat(js).contains("this.followMode");
        assertThat(js).contains("this.activeHighlightedElement === chunk.element");
        assertThat(js).contains("comfortableHeight");
        assertThat(js).contains("dockHeight");

        // 5. Interactive Progress Bar & Paused/Idle Navigation Synchronization
        assertThat(js).contains("_handleProgressBarClick");
        assertThat(js).contains("_handleProgressBarKeydown");
        assertThat(js).contains("_syncNavigationAndProgress");
        assertThat(js).contains("seekToChunk");

        // 6. Chapter Completion Semantics & Replay
        assertThat(js).contains("isCompleted");
        assertThat(js).contains("Phát lại từ đầu");
        assertThat(js).contains("Đã đọc xong chương.");

        // 7. Unsupported State & Vietnamese-Only Voice Selection
        assertThat(js).contains("_renderUnsupportedState");
        assertThat(js).contains("is-unsupported");
        assertThat(js).contains("isVietnameseVoice");
        assertThat(js).contains("Chưa có giọng đọc Tiếng Việt");

        // 8. Settings & Dock Collapse Management & Stable Interactions
        assertThat(js).contains("openSettings");
        assertThat(js).contains("closeSettings");
        assertThat(js).contains("isSettingsOpen");
        assertThat(js).contains("isCollapsed");
        assertThat(js).contains("collapseDock");
        assertThat(js).contains("expandDock");
        assertThat(js).contains("toggleDock");
        assertThat(js).contains("_handleCollapseToggleClick");
        assertThat(js).contains("_handleSettingsTriggerClick");
        assertThat(js).contains("_handleDocumentClick");
        assertThat(js).contains("_handleDocumentKeydown");

        // 9. Storage Keys & Preferences Persistence
        assertThat(js).contains("STORAGE_KEYS");
        assertThat(js).contains("kiemlai:narration:preferences:v1");
        assertThat(js).contains("kiemlai:narration:resume:v1");
        assertThat(js).contains("_initStorageAndPreferences");
        assertThat(js).contains("_loadPreferences");
        assertThat(js).contains("_savePreferences");
        assertThat(js).contains("_resolveBestMatchingVoice");

        // 10. Resume Position, Stale-Resume Handling, & Non-Autoplay State
        assertThat(js).contains("_restoreResumePosition");
        assertThat(js).contains("_loadResumePosition");
        assertThat(js).contains("_saveResumePosition");
        assertThat(js).contains("_clearSavedResume");
        assertThat(js).contains("Sẵn sàng đọc tiếp từ câu");
        assertThat(js).contains("saved.totalChunks !== this.chunks.length");
        assertThat(js).contains("this.hasMeaningfulResume");

        // 11. Auto-Next Seamless Cross-Chapter Transition & Lifecycle
        assertThat(js).contains("autoNext");
        assertThat(js).contains("setAutoNext");
        assertThat(js).contains("_handleAutoNextChange");
        assertThat(js).contains("_resolveNextChapterUrl");
        assertThat(js).contains("_transitionToNextChapter");
        assertThat(js).contains("_validateFetchedChapterDocument");
        assertThat(js).contains("_applyChapterTransition");
        assertThat(js).contains("DOMParser");
        assertThat(js).contains("AbortController");
        assertThat(js).contains("_transitionSequenceId");
        assertThat(js).contains("history.pushState");
        assertThat(js).contains("kiemlai:chapter-changed");
        assertThat(js).contains("isNavigatingToNext");
        assertThat(js).contains("_cancelPendingAutoNext");
        assertThat(js).contains("_handlePopState");
        assertThat(js).contains("popstate");

        // 12. Cleanup & Lifecycle Teardown
        assertThat(js).contains("beforeunload");
        assertThat(js).contains("pagehide");
        assertThat(js).contains("storage");
        assertThat(js).contains("this.isUnloaded");
        assertThat(js).contains("destroy()");
    }

    @Test
    @DisplayName("reader-progress.js and reader-history.js define reusable handlers and subscribe to kiemlai:chapter-changed event")
    void readerProgressAndHistoryCrossChapterContract() throws Exception {
        String progressJs = read("src/main/resources/static/js/novel/reader-progress.js");
        assertThat(progressJs).contains("recordReadingProgress");
        assertThat(progressJs).contains("kiemlai:chapter-changed");
        assertThat(progressJs).contains("novelReadingProgressTracker");
        assertThat(progressJs).contains("/novel/chapters/");

        String historyJs = read("src/main/resources/static/js/novel/reader-history.js");
        assertThat(historyJs).contains("recordReadingHistory");
        assertThat(historyJs).contains("kiemlai:chapter-changed");
        assertThat(historyJs).contains("novelReadingHistoryTracker");
        assertThat(historyJs).contains("/novel/chapters/");
    }

    @Test
    @DisplayName("reader.css defines responsive styles for fixed bottom narration player dock, progress bar, settings panel, and layout-stable highlight")
    void narrationPlayerCssStylesContract() throws Exception {
        String css = read("src/main/resources/static/css/novel/reader.css");

        // 1. Player Container & Fixed Bottom Dock
        assertThat(css).contains(".novel-narration-player");
        assertThat(css).contains(".novel-narration-player.is-collapsed");
        assertThat(css).contains("position: fixed");
        assertThat(css).contains(".novel-narration-dock");
        assertThat(css).contains(".novel-narration-player.is-unsupported");
        assertThat(css).contains(".novel-narration-controls");
        assertThat(css).contains(".novel-chapter-reading");
        assertThat(css).contains("env(safe-area-inset-bottom");

        // 2. Horizontal Progress Bar
        assertThat(css).contains(".novel-narration-progress-bar");
        assertThat(css).contains(".novel-narration-progress-track");
        assertThat(css).contains(".novel-narration-progress-fill");
        assertThat(css).contains(".novel-narration-timeline", ".novel-narration-progress-time",
                "font-variant-numeric: tabular-nums", "grid-template-columns: 7ch minmax(0, 1fr) 7ch");

        // 3. Buttons, Dock Actions & Progress
        assertThat(css).contains("--narration-dock-action-size");
        assertThat(css).contains(".novel-narration-dock-lead");
        assertThat(css).contains(".novel-narration-btn--lead");
        assertThat(css).contains(".novel-narration-btn--play");
        assertThat(css).contains(".novel-narration-btn--nav");
        assertThat(css).contains(".novel-narration-btn--settings");
        assertThat(css).contains(".novel-narration-status");

        // 4. Settings Popover Panel & Controls
        assertThat(css).contains(".novel-narration-settings-panel");
        assertThat(css).contains(".novel-narration-settings-header");
        assertThat(css).contains(".novel-narration-setting-row");
        assertThat(css).contains("@media (max-width: 640px)",
                "grid-template-columns: 6.5ch minmax(0, 1fr) 6.5ch",
                ".novel-narration-player.is-collapsed .novel-narration-timeline");
        assertThat(css).contains(".novel-narration-select--voice");
        assertThat(css).contains(".novel-reading-toggle-checkbox");
        assertThat(css).contains(".novel-reading-toggle-switch");

        // 5. Layout-Stable Highlight
        assertThat(css).contains(".novel-reader-chapter-body .novel-narration-highlight");
        assertThat(css).contains("box-shadow: inset 3px 0 0 var(--reader-primary)");

        // 6. Mobile Responsive Rules
        assertThat(css).contains("@media (max-width: 640px)");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
