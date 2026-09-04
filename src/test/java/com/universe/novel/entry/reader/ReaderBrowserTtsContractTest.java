package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Novel Reader Browser TTS Engine & Text Parser Contract Tests")
class ReaderBrowserTtsContractTest {

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

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
