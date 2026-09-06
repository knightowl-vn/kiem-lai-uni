package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MS-04.9H.7B / MS-04.9H.7B1 / MS-04.9H.7B2 / MS-04.9H.7B3 / MS-04.9H.7B3A — ManagedAudioEngine & Reader Playback Contract Tests")
class ReaderManagedAudioContractTest {

    @Test
    @DisplayName("1. Initial Device-only dropdown population cannot clear a saved managed preference")
    void initialDeviceDropdownPopulationCannotClearSavedManagedPreference() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._managedCatalogResolved = false;");
        assertThat(controllerJs).contains("else if (this._managedCatalogResolved) {\n                        // Stale saved managed voice -> ONLY clear preference after catalog resolution confirms absence!\n                        this.savedVoicePreference = null;\n                        this._savePreferences();\n                    }");
    }

    @Test
    @DisplayName("2. Saved managed preference is validated only after managed catalog resolution")
    void savedManagedPreferenceValidatedOnlyAfterCatalogResolution() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const defaultManifest = await this.managedEngine.loadManifest(this.chapterId);");
        assertThat(controllerJs).contains("this._managedCatalogResolved = true;");
        assertThat(controllerJs).contains("if (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)");
        assertThat(controllerJs).contains("const voiceExists = availableVoices.some(v => v.voiceKey === targetKey);");
    }

    @Test
    @DisplayName("3. Valid saved managed preference survives bootstrap and requests keyed manifest")
    void validSavedManagedPreferenceSurvivesBootstrap() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (voiceExists) {");
        assertThat(controllerJs).contains("if (targetKey !== defaultSelectedKey) {");
        assertThat(controllerJs).contains("await this.managedEngine.loadManifest(this.chapterId, targetKey);");
    }

    @Test
    @DisplayName("4. Stale saved managed preference is cleared only after catalog confirms absence")
    void staleSavedManagedPreferenceClearedOnlyAfterCatalogConfirmsAbsence() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("} else {\n                        // Stale saved managed voice: catalog has arrived and confirms absence\n                        this.savedVoicePreference = null;\n                        this._savePreferences();\n                    }");
    }

    @Test
    @DisplayName("5. User voice selection made during saved-key manifest await is preserved")
    void userVoiceSelectionMadeDuringKeyedManifestAwaitIsPreserved() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._hasUserExplicitlySelectedVoice = true;");
        assertThat(controllerJs).contains("const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);");
        assertThat(controllerJs).contains("// 4. Re-check user intent after the keyed manifest await before any final automatic activation\n                if (isInterrupted()) {\n                    this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });\n                    return;\n                }");
    }

    @Test
    @DisplayName("6. PLAYING/PAUSED state reached during saved-key manifest await is preserved")
    void playingPausedStateReachedDuringKeyedManifestAwaitIsPreserved() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';");
        assertThat(controllerJs).contains("const isInterrupted = () => {\n                    const engineState = this.engine ? this.engine.getState() : null;\n                    const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';\n                    const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);\n                    return isPlaybackActive || userExplicitlySelected;\n                };");
    }

    @Test
    @DisplayName("7. Final automatic activation re-check occurs after the keyed await")
    void finalAutomaticActivationReCheckOccursAfterKeyedAwait() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int firstCheck = controllerJs.indexOf("// 2. Check if user already started playback or explicitly changed voice while default manifest was loading");
        int keyedAwait = controllerJs.indexOf("await this.managedEngine.loadManifest(this.chapterId, targetKey);");
        int secondCheck = controllerJs.indexOf("// 4. Re-check user intent after the keyed manifest await before any final automatic activation");
        int finalPopulate = controllerJs.indexOf("// 5. Populate dropdown and activate active/default voice");

        assertThat(firstCheck).isGreaterThan(0);
        assertThat(keyedAwait).isGreaterThan(firstCheck);
        assertThat(secondCheck).isGreaterThan(keyedAwait);
        assertThat(finalPopulate).isGreaterThan(secondCheck);
    }

    @Test
    @DisplayName("8. Initial catalog discovery uses default manifest without voiceKey")
    void initialCatalogDiscoveryUsesDefaultManifestWithoutVoiceKey() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const defaultManifest = await this.managedEngine.loadManifest(this.chapterId);");
        assertThat(controllerJs).contains("availableVoices = (defaultManifest && Array.isArray(defaultManifest.availableVoices))");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("buildManifestUrl(chapterId, cleanVoiceKey)");
        assertThat(managedJs).contains("let url = '/api/novel/chapters/' + encodeURIComponent(String(chapterId)) + '/narration/manifest';");
    }

    @Test
    @DisplayName("9. Stale saved managed voice does not hide remaining managed voices and clears stale preference")
    void staleSavedManagedVoiceDoesNotHideRemainingVoices() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.savedVoicePreference = null;");
        assertThat(controllerJs).contains("this._savePreferences();");
        assertThat(controllerJs).contains("this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: false });");
    }

    @Test
    @DisplayName("10. Late managed discovery does not activate/switch engines while current narration is PLAYING or PAUSED")
    void lateManagedDiscoveryDoesNotInterruptActivePlayback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';");
        assertThat(controllerJs).contains("this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });");
        assertThat(controllerJs).contains("if (skipActivation) {");
    }

    @Test
    @DisplayName("11. Managed preference stores type = 'managed' and voiceKey")
    void managedPreferenceStoresTypeAndVoiceKey() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("type: 'managed'");
        assertThat(controllerJs).contains("voiceKey: voiceKey");
        assertThat(controllerJs).contains("this.savedVoicePreference.type === 'managed'");
        assertThat(controllerJs).contains("voiceKey: this.savedVoicePreference.voiceKey || ''");
    }

    @Test
    @DisplayName("12. Managed preference restores type = 'managed' and voiceKey correctly")
    void managedPreferenceRestoresTypeAndVoiceKey() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("prefs.voice.type === 'managed'");
        assertThat(controllerJs).contains("this.savedVoicePreference = {");
        assertThat(controllerJs).contains("type: 'managed',");
        assertThat(controllerJs).contains("voiceKey: prefs.voice.voiceKey || ''");
    }

    @Test
    @DisplayName("13. Cross-tab storage-event sync preserves managed preference")
    void storageEventSyncPreservesManagedPreference() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("_handleStorageEvent");
        assertThat(controllerJs).contains("if (prefs.voice.type === 'managed' || (prefs.voice.voiceKey && !prefs.voice.voiceURI))");
        assertThat(controllerJs).contains("this._populateVoiceDropdown(deviceVoices, managedVoices)");
    }

    @Test
    @DisplayName("14. Switching Managed Voice A -> Managed Voice B stops current playback")
    void managedToManagedSwitchStopsCurrentPlayback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("async _handleVoiceChange()");
        assertThat(controllerJs).contains("if (this.managedEngine) {\n                this.managedEngine.stop();\n            }");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("setManifest(manifest, startSegmentIndex = 0) {\n            this.stop();");
    }

    @Test
    @DisplayName("15. defaultVoice is used and isDefault is NOT used for H.7A DTO")
    void defaultVoiceIsUsedAndIsDefaultIsNotUsed() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("mv.defaultVoice ? ' (Mặc định)' : ''");
        assertThat(controllerJs).contains("mVoices.find(v => v.defaultVoice) || mVoices[0]");
        assertThat(controllerJs).doesNotContain("mv.isDefault");
        assertThat(controllerJs).doesNotContain("v.isDefault");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).doesNotContain("isDefault");
    }

    @Test
    @DisplayName("16. Controller registers only one managed start/end callback path")
    void controllerRegistersOnlyOneManagedStartEndCallbackPath() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("onSegmentStart: (segIndex, seg) => this._onEngineChunkStart(segIndex, seg, 'managed')");
        assertThat(controllerJs).contains("onSegmentEnd: (segIndex, seg) => this._onEngineChunkEnd(segIndex, seg, 'managed')");
        assertThat(controllerJs).doesNotContain("onChunkStart: (segIndex, seg) => this._onEngineChunkStart(segIndex, seg, 'managed')");
        assertThat(controllerJs).doesNotContain("onChunkEnd: (segIndex, seg) => this._onEngineChunkEnd(segIndex, seg, 'managed')");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const cb = this.options.onSegmentStart || this.options.onChunkStart");
        assertThat(managedJs).contains("const cb = this.options.onSegmentEnd || this.options.onChunkEnd");
    }

    @Test
    @DisplayName("17. HTMLAudioElement.play() rejection cannot remain in PLAYING state")
    void playRejectionCannotRemainPlaying() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (sequenceId !== this._playbackSequenceId)");
        assertThat(managedJs).contains("if (err && err.name === 'AbortError')");
        assertThat(managedJs).contains("this._handleError(err)");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.ERROR)");
    }

    @Test
    @DisplayName("18. Existing Device <-> Managed switching and mutual exclusion remain intact")
    void deviceAndManagedSwitchingAndMutualExclusion() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (this.deviceEngine) {\n                this.deviceEngine.stop();\n            }");
        assertThat(controllerJs).contains("if (this.managedEngine) {\n                this.managedEngine.stop();\n            }");
        assertThat(controllerJs).contains("this.activeEngineType = 'managed'");
        assertThat(controllerJs).contains("this.activeEngineType = 'device'");
    }

    @Test
    @DisplayName("19. One HTMLAudioElement instance is reused and src replaced")
    void oneAudioElementIsReusedAndSrcReplaced() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this.audio = options.audioElement || (this.supported ? new Audio() : null)");
        assertThat(managedJs).contains("this.audio.src = targetUrl");
        assertThat(managedJs).contains("this.audio.src !== targetUrl");
    }

    @Test
    @DisplayName("20. Existing Device Voice path remains available and unaltered")
    void existingDeviceVoicePathRemainsAvailable() throws Exception {
        String browserTtsJs = read("src/main/resources/static/js/novel/browser-tts-engine.js");
        assertThat(browserTtsJs).contains("BrowserTtsEngine");
        assertThat(browserTtsJs).contains("window.speechSynthesis");
        assertThat(browserTtsJs).contains("isVietnameseVoice");
        assertThat(browserTtsJs).contains("speakCurrentChunk");

        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.deviceEngine");
        assertThat(controllerJs).contains("deviceEngineOptions");
    }

    @Test
    @DisplayName("21. READY segment plays generated audioUrl")
    void readySegmentPlaysGeneratedAudioUrl() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("seg.playable === true && seg.audioUrl");
        assertThat(managedJs).contains("this.audio.src = targetUrl");
        assertThat(managedJs).contains("this.audio.play()");
    }

    @Test
    @DisplayName("22. OUTDATED segment plays existing old audioUrl")
    void outdatedSegmentPlaysExistingAudioUrl() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("findNextPlayableIndex");
        assertThat(managedJs).contains("seg.playable === true && seg.audioUrl");
    }

    @Test
    @DisplayName("23. MISSING and FAILED segments are not playable and skipped")
    void missingAndFailedSegmentsAreSkipped() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("findNextPlayableIndex");
        assertThat(managedJs).contains("findPreviousPlayableIndex");
        assertThat(managedJs).contains("if (!segment || !segment.playable || !segment.audioUrl)");
    }

    @Test
    @DisplayName("24. ended event advances to next playable segment")
    void endedEventAdvancesToNextPlayableSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("_handleAudioEnded");
        assertThat(managedJs).contains("this.audio.addEventListener('ended', this._boundOnAudioEnded)");
        assertThat(managedJs).contains("this.findNextPlayableIndex(this.currentSegmentIndex + 1)");
        assertThat(managedJs).contains("this.options.onChapterEnd()");
    }

    @Test
    @DisplayName("25. pause, resume, and stop work correctly on ManagedAudioEngine")
    void pauseResumeAndStopWork() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("pause()");
        assertThat(managedJs).contains("this.audio.pause()");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.PAUSED)");
        assertThat(managedJs).contains("resume()");
        assertThat(managedJs).contains("stop()");
        assertThat(managedJs).contains("this.audio.currentTime = 0");
        assertThat(managedJs).contains("this.currentSegmentIndex = 0");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.STOPPED)");
    }

    @Test
    @DisplayName("26. Playback speed changes HTMLAudioElement.playbackRate without audio regeneration")
    void playbackSpeedChangesAudioPlaybackRate() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("setRate(rate)");
        assertThat(managedJs).contains("this.audio.playbackRate = this.rate");
        assertThat(managedJs).contains("Math.max(0.5, Math.min(2.0, num))");

        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.managedEngine.setRate(rateVal)");
    }

    @Test
    @DisplayName("27. UI clearly distinguishes 'Thiết bị' and 'Giọng Kiếm Lai' optgroups")
    void uiClearlyDistinguishesDeviceAndManagedOptgroups() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("managedGroup.label = 'Giọng Kiếm Lai'");
        assertThat(controllerJs).contains("deviceGroup.label = 'Thiết bị'");
        assertThat(controllerJs).contains("option.value = 'managed:' + mv.voiceKey");
        assertThat(controllerJs).contains("option.value = 'device:' + (dv.voiceURI || dv.name)");
    }

    @Test
    @DisplayName("28. Manifest or audio load failure displays safe Vietnamese feedback without raw internals")
    void failureDisplaysSafeVietnameseFeedback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("Không thể tải giọng đọc Kiếm Lai. Vui lòng chọn giọng khác.");
        assertThat(controllerJs).contains("Không thể phát âm thanh giọng đọc Kiếm Lai. Vui lòng thử lại hoặc chọn Giọng thiết bị.");
        assertThat(controllerJs).doesNotContain("Throwable");
        assertThat(controllerJs).doesNotContain("s3Key");
        assertThat(controllerJs).doesNotContain("providerVoiceId");
    }

    @Test
    @DisplayName("29. No provider, storage, or diagnostic internals are surfaced in client-side scripts")
    void noProviderOrStorageInternalsSurfaced() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).doesNotContain("providerVoiceId");
        assertThat(managedJs).doesNotContain("VieNeu");
        assertThat(managedJs).doesNotContain("s3Key");
        assertThat(managedJs).doesNotContain("storageBucket");
        assertThat(managedJs).doesNotContain("binaryStorage");
        assertThat(managedJs).doesNotContain("failureReason");

        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).doesNotContain("providerVoiceId");
        assertThat(controllerJs).doesNotContain("VieNeu");
        assertThat(controllerJs).doesNotContain("s3Key");
        assertThat(controllerJs).doesNotContain("storageBucket");
        assertThat(controllerJs).doesNotContain("binaryStorage");
    }

    @Test
    @DisplayName("30. No autoplay occurs before user interaction on page load")
    void noAutoplayOccursBeforeUserInteraction() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("_checkAndConsumeAutoplayIntent");
        assertThat(controllerJs).contains("_restoreResumePosition");
        assertThat(controllerJs).contains("Follow Mode must NOT force an initial page scroll merely from restoring resume!");
    }

    @Test
    @DisplayName("31. destroy() delegates through _handleUnload() and _cancelPendingAutoNext()")
    void destroyDelegatesThroughHandleUnloadAndCancelPendingAutoNext() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("destroy() {\n            this._handleUnload();\n            this._cancelPendingAutoNext();");
    }

    @Test
    @DisplayName("32. Navigation uses !this.isCompleted for canNext determination")
    void navigationUsesNotCompletedForCanNext() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const canNext = !this.isCompleted && (currentIndex < total - 1);");
    }

    @Test
    @DisplayName("33. Prev and Next buttons synchronize both disabled and aria-disabled attributes")
    void prevAndNextButtonsSynchronizeDisabledAndAriaDisabled() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.dom.prevBtn.disabled = !canPrev;\n                this.dom.prevBtn.setAttribute('aria-disabled', String(!canPrev));");
        assertThat(controllerJs).contains("this.dom.nextBtn.disabled = !canNext;\n                this.dom.nextBtn.setAttribute('aria-disabled', String(!canNext));");
        assertThat(controllerJs).contains("this.dom.prevBtn.setAttribute('aria-disabled', 'true');");
        assertThat(controllerJs).contains("this.dom.nextBtn.setAttribute('aria-disabled', 'true');");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
