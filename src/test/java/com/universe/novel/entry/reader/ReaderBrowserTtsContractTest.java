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
    @DisplayName("Novel chapter reading page (chapter.html) includes narration player bar, controls, follow mode toggle, and script tags")
    void chapterReadingPageIncludesNarrationPlayerContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // 1. Narration Player Section & Controls
        assertThat(chapterPage).contains("id=\"novelNarrationPlayer\"");
        assertThat(chapterPage).contains("id=\"novelNarrationPlayPauseBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationPrevBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationNextBtn\"");
        assertThat(chapterPage).contains("id=\"novelNarrationProgress\"");
        assertThat(chapterPage).contains("id=\"novelNarrationProgressCurrent\"");
        assertThat(chapterPage).contains("id=\"novelNarrationProgressTotal\"");
        assertThat(chapterPage).contains("id=\"novelNarrationVoiceSelect\"");
        assertThat(chapterPage).contains("id=\"novelNarrationRateSelect\"");
        assertThat(chapterPage).contains("id=\"novelNarrationStatusText\"");

        // 2. Narration Follow Mode Toggle in Reading Settings Popover
        assertThat(chapterPage).contains("id=\"novelNarrationFollowToggle\"");
        assertThat(chapterPage).contains("role=\"switch\"");
        assertThat(chapterPage).contains("aria-checked=\"true\"");
        assertThat(chapterPage).contains("Theo dõi giọng đọc");

        // 3. Narration JavaScript Inclusions
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/narration-text-parser.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/browser-tts-engine.js}\"");
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
    @DisplayName("browser-tts-engine.js defines SpeechSynthesis wrapper, voice discovery, sequence tokening, pending-playback race fix, and lifecycle controls")
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

        // 4. Voice Discovery, Vietnamese Priority, & Property Fallback Cleanup in Destroy
        assertThat(js).contains("voiceschanged");
        assertThat(js).contains("getVietnameseVoices");
        assertThat(js).contains("getSortedVoices");
        assertThat(js).contains("startsWith('vi')");
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
    @DisplayName("narration-controller.js coordinates parser, engine, follow mode highlights, scrolling, and settings toggle")
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
        assertThat(js).contains("#novelNarrationVoiceSelect");
        assertThat(js).contains("#novelNarrationRateSelect");
        assertThat(js).contains("#novelNarrationFollowToggle");

        // 3. Engine Orchestration & Callbacks
        assertThat(js).contains("onStateChange");
        assertThat(js).contains("onChunkStart");
        assertThat(js).contains("onChapterEnd");
        assertThat(js).contains("onVoicesChanged");
        assertThat(js).contains("parseChapterBody");
        assertThat(js).contains("loadChunks");

        // 4. Follow Mode Highlights & Smooth Viewport Visibility
        assertThat(js).contains("setFollowMode");
        assertThat(js).contains("_highlightChunk");
        assertThat(js).contains("_clearHighlight");
        assertThat(js).contains("_isElementComfortablyVisible");
        assertThat(js).contains("_scrollElementIntoView");
        assertThat(js).contains("this.followMode");
        assertThat(js).contains("this.activeHighlightedElement === chunk.element");
        assertThat(js).contains("comfortableHeight");

        // 5. Paused/Idle Navigation Synchronization & Immediate UI Update
        assertThat(js).contains("_syncNavigationAndProgress");
        assertThat(js).contains("seekToChunk");

        // 6. Chapter Completion Semantics & Replay
        assertThat(js).contains("isCompleted");
        assertThat(js).contains("Phát lại từ đầu");
        assertThat(js).contains("Đã đọc xong chương.");

        // 7. Unsupported State & Voice Grouping
        assertThat(js).contains("_renderUnsupportedState");
        assertThat(js).contains("is-unsupported");
        assertThat(js).contains("Giọng đọc Tiếng Việt");
        assertThat(js).contains("Giọng đọc khác");

        // 8. Cleanup & Lifecycle Teardown
        assertThat(js).contains("beforeunload");
        assertThat(js).contains("pagehide");
        assertThat(js).contains("destroy()");
    }

    @Test
    @DisplayName("reader.css defines responsive styles for narration player, toggle switch, and layout-stable active content highlight")
    void narrationPlayerCssStylesContract() throws Exception {
        String css = read("src/main/resources/static/css/novel/reader.css");

        // 1. Player Container
        assertThat(css).contains(".novel-narration-player");
        assertThat(css).contains(".novel-narration-player.is-unsupported");
        assertThat(css).contains(".novel-narration-controls");

        // 2. Buttons & Controls
        assertThat(css).contains(".novel-narration-btn--play");
        assertThat(css).contains(".novel-narration-btn--nav");
        assertThat(css).contains(".novel-narration-progress");
        assertThat(css).contains(".novel-narration-select--voice");
        assertThat(css).contains(".novel-narration-select--rate");
        assertThat(css).contains(".novel-narration-status");

        // 3. Follow Mode Switch & Layout-Stable Highlight
        assertThat(css).contains(".novel-reading-toggle-wrapper");
        assertThat(css).contains(".novel-reading-toggle-checkbox");
        assertThat(css).contains(".novel-reading-toggle-switch");
        assertThat(css).contains(".novel-reader-chapter-body .novel-narration-highlight");
        assertThat(css).contains("box-shadow: inset 3px 0 0 var(--reader-primary)");

        // 4. Mobile Responsive Rules
        assertThat(css).contains("@media (max-width: 640px)");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
