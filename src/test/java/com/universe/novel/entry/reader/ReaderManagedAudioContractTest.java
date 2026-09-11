package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MS-04.9H.7B / MS-04.9H.7B1 / MS-04.9H.7B2 / MS-04.9H.7B3 / MS-04.9H.7B3A / MS-04.9H.7D3A / MS-04.9H.7D3A1 / MS-04.9H.7D3A2 — ManagedAudioEngine & Reader Playback Contract Tests")
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
        assertThat(controllerJs).contains("const catalog = await this.managedEngine.loadVoiceCatalog();");
        assertThat(controllerJs).contains("const availableVoices = (catalog && Array.isArray(catalog.voices))");
        assertThat(controllerJs).contains("this._managedCatalogResolved = true;");
        assertThat(controllerJs).contains("if (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)");
        assertThat(controllerJs).contains("const voiceExists = availableVoices.some(v => v.voiceKey === targetKey);");
    }

    @Test
    @DisplayName("3. Valid saved managed preference survives catalog bootstrap without loading a manifest")
    void validSavedManagedPreferenceSurvivesBootstrap() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String initialDiscovery = controllerMethod("async _loadInitialManagedVoices()", "_onManifestLoaded(manifest)");
        assertThat(initialDiscovery).contains("const voiceExists = availableVoices.some(v => v.voiceKey === targetKey);");
        assertThat(initialDiscovery).contains("if (!voiceExists) {");
        assertThat(initialDiscovery).doesNotContain("loadManifest(");
    }

    @Test
    @DisplayName("4. Stale saved managed preference is cleared only after catalog confirms absence")
    void staleSavedManagedPreferenceClearedOnlyAfterCatalogConfirmsAbsence() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (!voiceExists) {\n                        // Stale saved managed voice: catalog has arrived and confirms absence\n                        this.savedVoicePreference = null;\n                        this._savePreferences();\n                    }");
    }

    @Test
    @DisplayName("5. User voice selection made during catalog await is preserved")
    void userVoiceSelectionMadeDuringCatalogAwaitIsPreserved() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._hasUserExplicitlySelectedVoice = true;");
        assertThat(controllerJs).contains("const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);");
        assertThat(controllerJs).contains("// 4. Re-check user intent before final automatic activation.\n                if (isInterrupted()) {\n                    this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });\n                    return;\n                }");
    }

    @Test
    @DisplayName("6. PLAYING/PAUSED state reached during catalog await is preserved")
    void playingPausedStateReachedDuringCatalogAwaitIsPreserved() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';");
        assertThat(controllerJs).contains("const isInterrupted = () => {\n                    const engineState = this.engine ? this.engine.getState() : null;\n                    const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';\n                    const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);\n                    return isPlaybackActive || userExplicitlySelected;\n                };");
    }

    @Test
    @DisplayName("7. Final automatic activation re-check occurs after catalog validation")
    void finalAutomaticActivationReCheckOccursAfterCatalogValidation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int catalogAwait = controllerJs.indexOf("await this.managedEngine.loadVoiceCatalog();");
        int firstCheck = controllerJs.indexOf("// 2. Check if user already started playback or explicitly changed voice while the catalog was loading.");
        int savedValidation = controllerJs.indexOf("// 3. Restore and validate saved managed preference only after catalog discovery.");
        int secondCheck = controllerJs.indexOf("// 4. Re-check user intent before final automatic activation.");
        int finalPopulate = controllerJs.indexOf("// 5. Populate dropdown and activate active/default voice");

        assertThat(catalogAwait).isGreaterThan(0);
        assertThat(firstCheck).isGreaterThan(catalogAwait);
        assertThat(savedValidation).isGreaterThan(firstCheck);
        assertThat(secondCheck).isGreaterThan(savedValidation);
        assertThat(finalPopulate).isGreaterThan(secondCheck);
    }

    @Test
    @DisplayName("8. Initial catalog discovery uses the lightweight global endpoint")
    void initialCatalogDiscoveryUsesLightweightGlobalEndpoint() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String initialDiscovery = controllerMethod("async _loadInitialManagedVoices()", "_onManifestLoaded(manifest)");
        assertThat(initialDiscovery).contains("const catalog = await this.managedEngine.loadVoiceCatalog();");
        assertThat(initialDiscovery).doesNotContain("loadManifest(");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("function buildVoiceCatalogUrl() {");
        assertThat(managedJs).contains("return '/api/novel/narration/voices';");
        assertThat(managedJs).contains("async loadVoiceCatalog()");
        assertThat(managedJs).contains("const response = await fetchFn(buildVoiceCatalogUrl()");
        assertThat(managedJs).contains("this.availableVoices = catalog && Array.isArray(catalog.voices) ? catalog.voices : [];");
        assertThat(managedJs).contains("return { voices: this.availableVoices.slice() };");
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
    @DisplayName("PERF-02B1: Playback cancellation does not cancel passive catalog discovery")
    void playbackCancellationDoesNotCancelPassiveCatalogDiscovery() throws Exception {
        String cancel = managedMethod("cancel() {", "_handleAudioEnded() {");

        assertThat(cancel).contains(
                "this._playbackSequenceId++;",
                "this._manifestLoadSequenceId++;",
                "this._cancelPrefetch();",
                "this._activeFetchController.abort();",
                "this._activeManifestFetchController.abort();"
        );
        assertThat(cancel).doesNotContain(
                "_voiceCatalogLoadSequenceId",
                "_activeVoiceCatalogFetchController",
                "cancelVoiceCatalogLoad"
        );

        String voiceChange = controllerMethod("async _handleVoiceChange() {", "_handleRateChange() {");
        assertThat(voiceChange).contains("this.managedEngine.cancel();");
        assertThat(voiceChange).doesNotContain("cancelVoiceCatalogLoad");
    }

    @Test
    @DisplayName("PERF-02B1: Late catalog discovery remains usable after explicit Device selection")
    void lateCatalogDiscoveryRemainsUsableWithoutAutomaticManagedActivation() throws Exception {
        String discovery = controllerMethod("async _loadInitialManagedVoices()", "_onManifestLoaded(manifest)");

        assertThat(discovery).contains(
                "const catalog = await this.managedEngine.loadVoiceCatalog();",
                "const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);",
                "this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });"
        );
        assertThat(discovery).doesNotContain(
                "_voiceSelectionSequenceId",
                "catalogChapterId",
                "catalogSelection"
        );
    }

    @Test
    @DisplayName("PERF-02B1: Catalog failure rejects without mutating Managed playback error state")
    void catalogFailureDoesNotInvokePlaybackErrorHandling() throws Exception {
        String catalogLoad = managedMethod("async loadVoiceCatalog() {", "cancelVoiceCatalogLoad() {");

        assertThat(catalogLoad).contains("throw error;");
        assertThat(catalogLoad).doesNotContain("this._handleError(error)");
    }

    @Test
    @DisplayName("PERF-02B1: Catalog supersession and teardown have a dedicated cancellation boundary")
    void catalogSupersessionAndTeardownRemainExplicit() throws Exception {
        String catalogLoad = managedMethod("async loadVoiceCatalog() {", "cancelVoiceCatalogLoad() {");
        String catalogCancel = managedMethod("cancelVoiceCatalogLoad() {", "async loadManifest(");
        String destroy = managedMethod("destroy() {", "return {");
        String unload = controllerMethod("_handleUnload() {", "_handleStorageEvent(event) {");

        assertThat(catalogLoad).contains(
                "const sequenceId = ++this._voiceCatalogLoadSequenceId;",
                "previousController.abort();",
                "sequenceId !== this._voiceCatalogLoadSequenceId",
                "this._activeVoiceCatalogFetchController !== controller"
        );
        assertThat(catalogCancel).contains(
                "this._voiceCatalogLoadSequenceId++;",
                "this._activeVoiceCatalogFetchController.abort();",
                "this._activeVoiceCatalogFetchController = null;"
        );
        assertThat(destroy).contains("this.cancelVoiceCatalogLoad();", "this.cancel();");
        assertThat(unload).contains("this.managedEngine.cancelVoiceCatalogLoad();");
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
        assertThat(controllerJs).contains("onSegmentStart: (segIndex, seg, playbackDto) => this._onEngineChunkStart(segIndex, seg, 'managed', playbackDto)");
        assertThat(controllerJs).contains("onSegmentEnd: (segIndex, seg) => this._onEngineChunkEnd(segIndex, seg, 'managed')");
        assertThat(controllerJs).doesNotContain("onChunkStart: (segIndex, seg) => this._onEngineChunkStart(segIndex, seg, 'managed'");
        assertThat(controllerJs).doesNotContain("onChunkEnd: (segIndex, seg) => this._onEngineChunkEnd(segIndex, seg, 'managed'");

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
    @DisplayName("22. OUTDATED segment compatibility fallback checks playability")
    void outdatedSegmentPlaysExistingAudioUrl() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("findNextPlayableIndex");
        assertThat(managedJs).contains("seg.playable === true && seg.audioUrl");
    }

    @Test
    @DisplayName("23. Direct fallback and compatibility helpers identify playable segments")
    void missingAndFailedSegmentsAreSkipped() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("findNextPlayableIndex");
        assertThat(managedJs).contains("findPreviousPlayableIndex");
        assertThat(managedJs).contains("if (!segment || !segment.playable || !segment.audioUrl)");
    }

    @Test
    @DisplayName("24. ended event advances sequentially to adjacent next segment via fresh /prepare authority")
    void endedEventAdvancesToNextPlayableSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("_handleAudioEnded");
        assertThat(managedJs).contains("this.audio.addEventListener('ended', this._boundOnAudioEnded)");
        assertThat(managedJs).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(managedJs).contains("this.play(nextIndex);");
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
        assertThat(controllerJs).contains("const canNext = this.engine === this.chapterEngine ? this.chapterEngine.canNext() : !this.isCompleted && (currentIndex < total - 1);");
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

    @Test
    @DisplayName("34. ManagedEngineState includes PREPARING and BLOCKED states (MS-04.9H.7D2A)")
    void managedEngineStateIncludesPreparingAndBlockedStates() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("PREPARING: 'PREPARING'");
        assertThat(managedJs).contains("BLOCKED: 'BLOCKED'");
        assertThat(managedJs).contains("IDLE: 'IDLE'");
        assertThat(managedJs).contains("PLAYING: 'PLAYING'");
        assertThat(managedJs).contains("PAUSED: 'PAUSED'");
        assertThat(managedJs).contains("STOPPED: 'STOPPED'");
        assertThat(managedJs).contains("ERROR: 'ERROR'");
    }

    @Test
    @DisplayName("35. prepareSegmentPlayback builds canonical POST /prepare URL with JSON body (MS-04.9H.7D2A)")
    void prepareSegmentPlaybackBuildsCanonicalPrepareUrlWithJsonBody() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("buildPrepareUrl(chapterId, segmentId)");
        assertThat(managedJs).contains("'/api/novel/chapters/' + encodeURIComponent(String(chapterId)) +");
        assertThat(managedJs).contains("'/narration/segments/' + encodeURIComponent(String(segmentId)) +");
        assertThat(managedJs).contains("'/prepare'");
        assertThat(managedJs).contains("method: 'POST'");
        assertThat(managedJs).contains("body: JSON.stringify({ voiceKey: String(voiceKey).trim() })");
    }

    @Test
    @DisplayName("36. prepareSegmentPlayback includes CSRF header from resolveCsrfToken (MS-04.9H.7D2A)")
    void prepareSegmentPlaybackIncludesCsrfHeader() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("resolveCsrfToken()");
        assertThat(managedJs).contains("meta[name=\"_csrf\"]");
        assertThat(managedJs).contains("meta[name=\"_csrf_header\"]");
        assertThat(managedJs).contains("headers[csrf.header] = csrf.token;");
    }

    @Test
    @DisplayName("37. Public voice key is sent and internal managedVoiceId is never exposed (MS-04.9H.7D2A)")
    void publicVoiceKeyIsSentAndInternalManagedVoiceIdNeverExposed() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("getSelectedVoiceKey()");
        assertThat(managedJs).doesNotContain("managedVoiceId");
        assertThat(managedJs).doesNotContain("providerVoiceId");
    }

    @Test
    @DisplayName("38. Monotonic sequence ID guards against stale async prepare responses (MS-04.9H.7D2A)")
    void monotonicSequenceIdGuardsAgainstStalePrepareResponses() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this._playbackSequenceId++;");
        assertThat(managedJs).contains("const sequenceId = this._playbackSequenceId;");
        assertThat(managedJs).contains("sequenceId !== this._playbackSequenceId");
    }

    @Test
    @DisplayName("39. Playable response assigns audioUrl and transitions to PLAYING (MS-04.9H.7D2A)")
    void playableResponseAssignsAudioUrlAndTransitionsToPlaying() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl)");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.PLAYING);");
        assertThat(managedJs).contains("this._notifySegmentStart(requestedIndex, segment, playbackDto);");
    }

    @Test
    @DisplayName("40. Non-playable response does not set audio.src and transitions to BLOCKED (MS-04.9H.7D2A)")
    void nonPlayableResponseDoesNotSetAudioSrcAndTransitionsToBlocked() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.BLOCKED);");
        assertThat(managedJs).contains("this._notifyBlocked(requestedIndex, segment, playbackDto);");
    }

    @Test
    @DisplayName("41. chapter.html includes CSRF meta tags and novelNarrationPlayer attributes (MS-04.9H.7D2A)")
    void chapterTemplateIncludesCsrfMetaTagsAndPlayerAttributes() throws Exception {
        String html = read("src/main/resources/templates/novel/chapter.html");
        assertThat(html).contains("<meta name=\"_csrf\"");
        assertThat(html).contains("th:content=\"${_csrf.token}\"");
        assertThat(html).contains("<meta name=\"_csrf_header\"");
        assertThat(html).contains("th:content=\"${_csrf.headerName}\"");
        assertThat(html).contains("id=\"novelNarrationPlayer\"");
        assertThat(html).contains("data-chapter-id=");
        assertThat(html).contains("data-csrf-token=");
        assertThat(html).contains("data-csrf-header=");
    }

    @Test
    @DisplayName("42. NarrationController handles PREPARING and BLOCKED states gracefully (MS-04.9H.7D2A)")
    void narrationControllerHandlesPreparingAndBlockedStates() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (newState === 'PREPARING') {\n                label = 'Dừng chuẩn bị giọng đọc';");
        assertThat(controllerJs).contains("if (newState === 'PREPARING') {\n                this._setStatusMessage('Đang chuẩn bị giọng đọc...');");
        assertThat(controllerJs).contains("else if (newState === 'BLOCKED') {\n                this._setStatusMessage('Đoạn đọc này hiện chưa sẵn sàng. Vui lòng thử lại sau.');");
        assertThat(controllerJs).contains("_onEngineBlocked(segIndex, seg, engineType, playbackDto)");
        assertThat(controllerJs).contains("onBlocked: (segIndex, seg, playbackDto) => this._onEngineBlocked(segIndex, seg, 'managed', playbackDto)");
    }

    @Test
    @DisplayName("43. PREPARING cancellation in NarrationController calls cancel() directly, not pause() (MS-04.9H.7D2A1)")
    void preparingCancellationCallsCancelDirectly() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (state === 'PREPARING') {\n                if (typeof this.engine.cancel === 'function') {\n                    this.engine.cancel();\n                }");
        assertThat(controllerJs).doesNotContain("state === 'PREPARING') {\n                if (typeof this.engine.pause === 'function')");
    }

    @Test
    @DisplayName("44. cancel() in ManagedAudioEngine aborts active prepare fetch and clears controller (MS-04.9H.7D2A1)")
    void cancelAbortsActiveFetchAndClearsController() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("cancel() {");
        assertThat(managedJs).contains("if (this._activeFetchController) {\n                try {\n                    this._activeFetchController.abort();\n                } catch (ignored) {}\n                this._activeFetchController = null;\n            }");
    }

    @Test
    @DisplayName("45. cancel() in ManagedAudioEngine increments playback sequence (MS-04.9H.7D2A1)")
    void cancelIncrementsPlaybackSequence() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("cancel() {\n            this._playbackSequenceId++;");
    }

    @Test
    @DisplayName("46. cancel() in ManagedAudioEngine leaves PREPARING and transitions to IDLE (MS-04.9H.7D2A1)")
    void cancelLeavesPreparingAndTransitionsToIdle() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this._transitionState(this.segments.length > 0 ? ManagedEngineState.IDLE : ManagedEngineState.STOPPED);");
    }

    @Test
    @DisplayName("47. Late prepare response remains guarded by sequence ID and does not start audio (MS-04.9H.7D2A1)")
    void latePrepareResponseRemainsGuardedBySequenceId() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const sequenceId = this._playbackSequenceId;");
        assertThat(managedJs).contains("const playbackDto = await this.prepareSegmentPlayback(");
        assertThat(managedJs).contains("const isStale = (sequenceId !== this._playbackSequenceId)");
    }

    @Test
    @DisplayName("48. Start-of-prepare pauses any currently playing audio before new PREPARING request (MS-04.9H.7D2A1)")
    void startOfPreparePausesCurrentlyPlayingAudio() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("// Start-of-prepare safety: pause any currently playing audio before preparing new segment\n                if (this.audio) {\n                    try {\n                        this.audio.pause();\n                    } catch (ignored) {}\n                }");
    }

    @Test
    @DisplayName("49. Next Play after cancellation can prepare current segment again (MS-04.9H.7D2A1)")
    void nextPlayAfterCancellationPreparesCurrentSegmentAgain() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.engine.play(this.engine.getCurrentChunkIndex());");
    }

    @Test
    @DisplayName("50. Cancelled status message is politely announced on PREPARING cancellation (MS-04.9H.7D2A1)")
    void cancelledStatusMessagePolitelyAnnounced() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.");
        assertThat(controllerJs).contains("else if (newState === 'IDLE' && prevState === 'PREPARING') {\n                this._setStatusMessage('Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.');");
    }

    @Test
    @DisplayName("51. seekToSegment while PREPARING cancels active prepare operation (MS-04.9H.7D2A2)")
    void seekToSegmentWhilePreparingCancelsActivePrepare() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (this.state === ManagedEngineState.PREPARING) {\n                this.cancel();\n            }");
    }

    @Test
    @DisplayName("52. sequence changes before cursor moves during seek in PREPARING (MS-04.9H.7D2A2)")
    void sequenceChangesBeforeCursorMovesDuringSeek() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekToSegmentIndex = managedJs.indexOf("seekToSegment(index) {");
        int cancelIndex = managedJs.indexOf("if (this.state === ManagedEngineState.PREPARING) {\n                this.cancel();\n            }", seekToSegmentIndex);
        int cursorChangeIndex = managedJs.indexOf("this.currentSegmentIndex = targetIndex;", seekToSegmentIndex);
        assertThat(seekToSegmentIndex).isGreaterThan(0);
        assertThat(cancelIndex).isGreaterThan(seekToSegmentIndex);
        assertThat(cursorChangeIndex).isGreaterThan(cancelIndex);
    }

    @Test
    @DisplayName("53. Late response for old segment cannot commit after seek (MS-04.9H.7D2A2)")
    void lateResponseForOldSegmentCannotCommitAfterSeek() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const isStale = (sequenceId !== this._playbackSequenceId) ||\n                                    (this.currentSegmentIndex !== requestedIndex) ||\n                                    (!currentSegment || currentSegment.segmentId !== requestedSegmentId);");
        assertThat(managedJs).contains("if (isStale) {\n                        return; // Stale/superseded by another user action or navigation\n                    }");
    }

    @Test
    @DisplayName("54. Response commit verifies requested index and segment identity (MS-04.9H.7D2A2)")
    void responseCommitVerifiesRequestedIndexAndSegmentIdentity() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const requestedIndex = targetIndex;");
        assertThat(managedJs).contains("const requestedSegmentId = segment.segmentId;");
        assertThat(managedJs).contains("this.currentSegmentIndex !== requestedIndex");
        assertThat(managedJs).contains("currentSegment.segmentId !== requestedSegmentId");
    }

    @Test
    @DisplayName("55. Segment-start callback uses stable requested index (MS-04.9H.7D2A2)")
    void segmentStartCallbackUsesStableRequestedIndex() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this._notifySegmentStart(requestedIndex, segment, playbackDto);");
        assertThat(managedJs).contains("this._notifyBlocked(requestedIndex, segment, playbackDto);");
    }

    @Test
    @DisplayName("56. Next Play after seek prepares the new current segment (MS-04.9H.7D2A2)")
    void nextPlayAfterSeekPreparesNewCurrentSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("seekToSegment(index) {");
        assertThat(managedJs).contains("this.currentSegmentIndex = targetIndex;");
        assertThat(managedJs).contains("if (prevState === ManagedEngineState.PLAYING) {\n                if (targetIndex !== prevIndex) {\n                    this.play(targetIndex);\n                }\n            }");
    }

    @Test
    @DisplayName("57. Existing PREPARING Play/Pause cancellation remains intact (MS-04.9H.7D2A2)")
    void existingPreparingPlayPauseCancellationRemainsIntact() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (state === 'PREPARING') {\n                if (typeof this.engine.cancel === 'function') {\n                    this.engine.cancel();\n                }\n                this._setStatusMessage('Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.');");
    }

    @Test
    @DisplayName("58. Ended segment N advances exactly to N + 1 via prepared fast path or canonical play (MS-04.9H.7D8)")
    void endedSegmentAdvancesExactlyToNextIndexAndInvokesPlay() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("_handleAudioEnded() {");
        assertThat(managedJs).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(managedJs).contains("if (this._canUsePreparedFastPath(nextIndex)) {\n                    this._playPreparedAdjacentSegment(nextIndex);\n                } else {\n                    this.play(nextIndex);\n                }");
    }

    @Test
    @DisplayName("59. Next segment preparation uses canonical play(index) as fresh authority (MS-04.9H.7D2B)")
    void nextSegmentPreparationUsesCanonicalPlayAsFreshAuthority() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const playbackDto = await this.prepareSegmentPlayback(");
        assertThat(managedJs).contains("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl)");
    }

    @Test
    @DisplayName("60. Stale manifest MISSING does not cause N + 1 to be skipped (MS-04.9H.7D2B)")
    void staleManifestMissingDoesNotCauseNextSegmentToBeSkipped() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedIdx = managedJs.indexOf("_handleAudioEnded() {");
        int endOfEnded = managedJs.indexOf("/**\n         * Audio event: error", endedIdx);
        String endedMethod = managedJs.substring(endedIdx, endOfEnded);
        assertThat(endedMethod).doesNotContain("findNextPlayableIndex");
        assertThat(endedMethod).contains("this.play(nextIndex);");
    }

    @Test
    @DisplayName("61. Freshly READY response for previously non-playable segment plays normally (MS-04.9H.7D2B)")
    void freshlyReadyResponsePlaysNormally() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("segment.playable = true;");
        assertThat(managedJs).contains("segment.audioUrl = playbackDto.audioUrl;");
        assertThat(managedJs).contains("segment.healthStatus = playbackDto.healthStatus;");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.PLAYING);");
    }

    @Test
    @DisplayName("62. OUTDATED next segment remains playable immediately (MS-04.9H.7D2B)")
    void outdatedNextSegmentRemainsPlayableImmediately() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl)");
    }

    @Test
    @DisplayName("63. BLOCKED N + 1 stays at N + 1 and does not skip to N + 2 (MS-04.9H.7D2B)")
    void blockedNextSegmentStaysAtTargetIndexWithoutSkipping() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("segment.playable = false;");
        assertThat(managedJs).contains("this._transitionState(ManagedEngineState.BLOCKED);");
        assertThat(managedJs).contains("this._notifyBlocked(requestedIndex, segment, playbackDto);");
        int blockedBranch = managedJs.indexOf("this._transitionState(ManagedEngineState.BLOCKED);");
        int catchBlock = managedJs.indexOf("} catch (error) {", blockedBranch);
        String blockedBlock = managedJs.substring(blockedBranch, catchBlock);
        assertThat(blockedBlock).doesNotContain("this.play(");
        assertThat(blockedBlock).doesNotContain("nextIndex");
    }

    @Test
    @DisplayName("64. BLOCKED intermediate segment does not call chapter end (MS-04.9H.7D2B)")
    void blockedIntermediateSegmentDoesNotCallChapterEnd() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int blockedBranch = managedJs.indexOf("this._transitionState(ManagedEngineState.BLOCKED);");
        int catchBlock = managedJs.indexOf("} catch (error) {", blockedBranch);
        String blockedBlock = managedJs.substring(blockedBranch, catchBlock);
        assertThat(blockedBlock).doesNotContain("onChapterEnd");
    }

    @Test
    @DisplayName("65. Only actual final segment ended triggers chapter end (MS-04.9H.7D8)")
    void onlyActualFinalSegmentEndedTriggersChapterEnd() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(managedJs).contains("if (nextIndex < this.segments.length) {");
        assertThat(managedJs).contains("this._playPreparedAdjacentSegment(nextIndex);");
        assertThat(managedJs).contains("this.play(nextIndex);");
        assertThat(managedJs).contains("// Chapter finished: actual final manifest segment ended\n                this._transitionState(ManagedEngineState.STOPPED);\n                if (typeof this.options.onChapterEnd === 'function') {");
    }

    @Test
    @DisplayName("66. Manual nextSegment uses adjacent index without findNextPlayableIndex (MS-04.9H.7D2B)")
    void manualNextSegmentUsesAdjacentIndex() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int nextMethodIdx = managedJs.indexOf("nextSegment() {");
        int endOfNextMethod = managedJs.indexOf("nextChunk() {", nextMethodIdx);
        String nextMethod = managedJs.substring(nextMethodIdx, endOfNextMethod);
        assertThat(nextMethod).doesNotContain("findNextPlayableIndex");
        assertThat(nextMethod).contains("if (this.currentSegmentIndex + 1 < this.segments.length) {\n                this.seekToSegment(this.currentSegmentIndex + 1);\n            }");
    }

    @Test
    @DisplayName("67. Manual previousSegment uses adjacent index without findPreviousPlayableIndex (MS-04.9H.7D2B)")
    void manualPreviousSegmentUsesAdjacentIndex() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int prevMethodIdx = managedJs.indexOf("previousSegment() {");
        int endOfPrevMethod = managedJs.indexOf("previousChunk() {", prevMethodIdx);
        String prevMethod = managedJs.substring(prevMethodIdx, endOfPrevMethod);
        assertThat(prevMethod).doesNotContain("findPreviousPlayableIndex");
        assertThat(prevMethod).contains("if (this.currentSegmentIndex - 1 >= 0) {\n                this.seekToSegment(this.currentSegmentIndex - 1);\n            }");
    }

    @Test
    @DisplayName("68. PREPARING cancellation and A2 stable guards remain intact (MS-04.9H.7D2B)")
    void preparingCancellationAndA2GuardsRemainIntact() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (this.state === ManagedEngineState.PREPARING) {\n                this.cancel();\n            }");
        assertThat(managedJs).contains("const isStale = (sequenceId !== this._playbackSequenceId) ||\n                                    (this.currentSegmentIndex !== requestedIndex) ||\n                                    (!currentSegment || currentSegment.segmentId !== requestedSegmentId);");
    }

    @Test
    @DisplayName("69. PAUSED A -> seek different B transitions away from PAUSED to IDLE and invalidates sequence (MS-04.9H.7D2B1)")
    void pausedSeekDifferentSegmentTransitionsToIdle() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("else if (prevState === ManagedEngineState.PAUSED) {\n                if (targetIndex !== prevIndex) {\n                    this._playbackSequenceId++;\n                    this._transitionState(ManagedEngineState.IDLE);\n                }\n            }");
    }

    @Test
    @DisplayName("70. PAUSED A -> seek B does not assign manifest audioUrl directly to audio.src (MS-04.9H.7D2B1)")
    void pausedSeekDoesNotAssignManifestAudioUrlDirectly() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekStart = managedJs.indexOf("seekToSegment(index) {");
        int seekEnd = managedJs.indexOf("seekToChunk(index) {", seekStart);
        String seekMethod = managedJs.substring(seekStart, seekEnd);
        assertThat(seekMethod).doesNotContain("this.audio.src =");
    }

    @Test
    @DisplayName("71. Next Play after PAUSED navigation delegates to play(B) (MS-04.9H.7D2B1)")
    void nextPlayAfterPausedNavigationDelegatesToPlay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.engine.play(this.engine.getCurrentChunkIndex());");
    }

    @Test
    @DisplayName("72. Fresh /prepare remains the playback authority for B (MS-04.9H.7D2B1)")
    void freshPrepareRemainsPlaybackAuthorityForTarget() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const playbackDto = await this.prepareSegmentPlayback(");
        assertThat(managedJs).contains("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl) {");
        assertThat(managedJs).contains("this.audio.src = targetUrl;");
    }

    @Test
    @DisplayName("73. Seeking the same PAUSED segment preserves normal resume behavior (MS-04.9H.7D2B1)")
    void seekingSamePausedSegmentPreservesResumeBehavior() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("else if (prevState === ManagedEngineState.PAUSED) {\n                if (targetIndex !== prevIndex) {");
        assertThat(managedJs).contains("resume() {\n            if (this.state === ManagedEngineState.PAUSED) {");
    }

    @Test
    @DisplayName("74. PLAYING navigation still invokes play(targetIndex) (MS-04.9H.7D2B1)")
    void playingNavigationInvokesPlayTargetIndex() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (prevState === ManagedEngineState.PLAYING) {\n                if (targetIndex !== prevIndex) {\n                    this.play(targetIndex);\n                }\n            }");
    }

    @Test
    @DisplayName("75. PREPARING seek cancellation from H.7D2A2 remains intact (MS-04.9H.7D2B1)")
    void preparingSeekCancellationRemainsIntact() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (this.state === ManagedEngineState.PREPARING) {\n                this.cancel();\n            }");
    }

    @Test
    @DisplayName("76. H.7D2B adjacent ended auto-advance remains intact (MS-04.9H.7D8)")
    void adjacentEndedAutoAdvanceRemainsIntact() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(managedJs).contains("if (nextIndex < this.segments.length) {");
        assertThat(managedJs).contains("this._playPreparedAdjacentSegment(nextIndex);");
        assertThat(managedJs).contains("this.play(nextIndex);");
    }

    @Test
    @DisplayName("77. Stale header documentation is corrected (MS-04.9H.7D2B1)")
    void staleHeaderDocumentationIsCorrected() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).doesNotContain("skip during auto-advance");
        assertThat(managedJs).contains("Canonical segment playability & fresh authority:");
        assertThat(managedJs).contains("dynamically prepared via fresh POST /prepare; becomes BLOCKED without skipping");
        assertThat(managedJs).contains("Advance sequentially to adjacent next segment on 'ended' event via fresh /prepare.");
    }

    @Test
    @DisplayName("78. Fresh playable /prepare resets audio.currentTime to 0 safely (MS-04.9H.7D2B2)")
    void freshPlayablePrepareResetsAudioCurrentTimeToZero() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this.audio.currentTime = 0;");
        int prepareSuccess = managedJs.indexOf("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl) {");
        int audioPlay = managedJs.indexOf("const playPromise = this.audio.play();", prepareSuccess);
        String setupBlock = managedJs.substring(prepareSuccess, audioPlay);
        assertThat(setupBlock).contains("this.audio.currentTime = 0;");
    }

    @Test
    @DisplayName("79. Reset currentTime occurs unconditionally even when targetUrl equals existing audio.src (MS-04.9H.7D2B2)")
    void resetCurrentTimeOccursUnconditionally() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int prepareSuccess = managedJs.indexOf("if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl) {");
        int audioPlay = managedJs.indexOf("const playPromise = this.audio.play();", prepareSuccess);
        String setupBlock = managedJs.substring(prepareSuccess, audioPlay);
        int srcCheck = setupBlock.indexOf("if (this.audio.src !== targetUrl");
        int resetCurrentTime = setupBlock.indexOf("this.audio.currentTime = 0;");
        assertThat(resetCurrentTime).isGreaterThan(srcCheck);
    }

    @Test
    @DisplayName("80. Different segment sharing the same audioUrl cannot inherit old currentTime (MS-04.9H.7D2B2)")
    void differentSegmentSharingSameAudioUrlCannotInheritOldCurrentTime() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("try {\n                                    this.audio.currentTime = 0;\n                                } catch (ignored) {}");
    }

    @Test
    @DisplayName("81. Same-segment PAUSED resume does NOT reset currentTime (MS-04.9H.7D2B2)")
    void sameSegmentPausedResumeDoesNotResetCurrentTime() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int resumeStart = managedJs.indexOf("resume() {");
        int resumeEnd = managedJs.indexOf("stop() {", resumeStart);
        String resumeMethod = managedJs.substring(resumeStart, resumeEnd);
        assertThat(resumeMethod).doesNotContain("currentTime = 0");
        assertThat(resumeMethod).contains("this.audio.play()");
    }

    @Test
    @DisplayName("82. PAUSED different-segment seek invalidates playback sequence (MS-04.9H.7D2B2)")
    void pausedDifferentSegmentSeekInvalidatesPlaybackSequence() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("else if (prevState === ManagedEngineState.PAUSED) {\n                if (targetIndex !== prevIndex) {\n                    this._playbackSequenceId++;\n                    this._transitionState(ManagedEngineState.IDLE);\n                }\n            }");
    }

    @Test
    @DisplayName("83. Next Play after PAUSED navigation uses canonical /prepare (MS-04.9H.7D2B2)")
    void nextPlayAfterPausedNavigationUsesCanonicalPrepare() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.engine.play(this.engine.getCurrentChunkIndex());");
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const playbackDto = await this.prepareSegmentPlayback(");
    }

    @Test
    @DisplayName("84. Adjacent auto-advance remains fresh-authority based (MS-04.9H.7D8)")
    void adjacentAutoAdvanceRemainsFreshAuthorityBased() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(managedJs).contains("if (nextIndex < this.segments.length) {");
        assertThat(managedJs).contains("if (this._canUsePreparedFastPath(nextIndex)) {\n                    this._playPreparedAdjacentSegment(nextIndex);\n                } else {\n                    this.play(nextIndex);\n                }");
    }

    @Test
    @DisplayName("85. PREPARING cancellation and A2 guards remain intact (MS-04.9H.7D2B2)")
    void preparingCancellationAndA2GuardsRemainIntactH7D2B2() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("if (this.state === ManagedEngineState.PREPARING) {\n                this.cancel();\n            }");
        assertThat(managedJs).contains("const isStale = (sequenceId !== this._playbackSequenceId) ||\n                                    (this.currentSegmentIndex !== requestedIndex) ||\n                                    (!currentSegment || currentSegment.segmentId !== requestedSegmentId);");
    }

    @Test
    @DisplayName("86. PREPARING sets aria-busy=\"true\" on player and statusText (MS-04.9H.7D2C)")
    void preparingSetsAriaBusyOnPlayerAndStatusText() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("const isPreparing = newState === 'PREPARING';");
        assertThat(controllerJs).contains("if (this.dom.player) {\n                this.dom.player.setAttribute('aria-busy', String(isPreparing));\n            }");
        assertThat(controllerJs).contains("if (this.dom.statusText) {\n                this.dom.statusText.setAttribute('aria-busy', String(isPreparing));\n            }");
    }

    @Test
    @DisplayName("87. PREPARING updates Play/Pause button label to stop preparation (MS-04.9H.7D2C)")
    void preparingUpdatesPlayPauseButtonLabelToStopPreparation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (newState === 'PREPARING') {\n                label = 'Dừng chuẩn bị giọng đọc';\n            }");
    }

    @Test
    @DisplayName("88. PREPARING displays polite loading status message (MS-04.9H.7D2C)")
    void preparingDisplaysPoliteLoadingStatusMessage() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (newState === 'PREPARING') {\n                this._setStatusMessage('Đang chuẩn bị giọng đọc...');\n            }");
    }

    @Test
    @DisplayName("89. PREPARING cancellation resets aria-busy to false and informs reader (MS-04.9H.7D2C)")
    void preparingCancellationResetsAriaBusyAndInformsReader() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (newState === 'IDLE' && prevState === 'PREPARING') {\n                this._setStatusMessage('Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.');\n            }");
        assertThat(controllerJs).contains("if (state === 'PREPARING') {\n                if (typeof this.engine.cancel === 'function') {\n                    this.engine.cancel();\n                }");
    }

    @Test
    @DisplayName("90. BLOCKED state resets aria-busy to false (MS-04.9H.7D2C)")
    void blockedStateResetsAriaBusyToFalse() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("_onEngineBlocked(segIndex, seg, engineType, playbackDto) {");
        assertThat(controllerJs).contains("if (this.dom.player) {\n                this.dom.player.setAttribute('aria-busy', 'false');\n            }");
        assertThat(controllerJs).contains("if (this.dom.statusText) {\n                this.dom.statusText.setAttribute('aria-busy', 'false');\n            }");
    }

    @Test
    @DisplayName("91. BLOCKED state sets retry button label (MS-04.9H.7D2C)")
    void blockedStateSetsRetryButtonLabel() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("else if (newState === 'BLOCKED' || newState === 'ERROR') {\n                label = 'Thử lại';\n            }");
        assertThat(controllerJs).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Thử lại');");
    }

    @Test
    @DisplayName("92. BLOCKED state displays polite Vietnamese message (MS-04.9H.7D2C)")
    void blockedStateDisplaysPoliteVietnameseMessage() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._setStatusMessage('Đoạn đọc này hiện chưa sẵn sàng. Vui lòng thử lại sau.');");
    }

    @Test
    @DisplayName("93. BLOCKED retry invokes play on current chunk index (MS-04.9H.7D2C)")
    void blockedRetryInvokesPlayOnCurrentChunkIndex() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.engine.play(this.engine.getCurrentChunkIndex());");
    }

    @Test
    @DisplayName("94. _onEngineError handles engine errors and offers retry (MS-04.9H.7D2C)")
    void onEngineErrorHandlesEngineErrorsAndOffersRetry() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        assertThat(methodStart).isGreaterThan(0);
        assertThat(methodEnd).isGreaterThan(methodStart);

        String methodBody = controllerJs.substring(methodStart, methodEnd);
        assertThat(methodBody).contains("this._setStatusMessage('Không thể phát âm thanh giọng đọc Kiếm Lai. Vui lòng thử lại hoặc chọn Giọng thiết bị.');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Thử lại');");
    }

    @Test
    @DisplayName("95. managedEngineOptions forwards playbackDto from onSegmentStart (MS-04.9H.7D2C)")
    void managedEngineOptionsForwardsPlaybackDtoFromOnSegmentStart() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("onSegmentStart: (segIndex, seg, playbackDto) => this._onEngineChunkStart(segIndex, seg, 'managed', playbackDto),");
    }

    @Test
    @DisplayName("96. OUTDATED or refreshRecommended segment playback displays subtle non-blocking notice (MS-04.9H.7D2C)")
    void outdatedSegmentPlaybackDisplaysSubtleNonBlockingNotice() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (playbackDto && (playbackDto.refreshRecommended === true || playbackDto.healthStatus === 'OUTDATED')) {\n                this._setStatusMessage('Đang phát bản giọng đọc hiện có; bản mới có thể đang được cập nhật.');\n            } else {");
    }

    @Test
    @DisplayName("97. READY segment playback displays standard reading progress message (MS-04.9H.7D2C)")
    void readySegmentPlaybackDisplaysStandardReadingProgressMessage() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._setStatusMessage('Đang đọc câu ' + currentNum + ' / ' + totalNum);");
    }

    @Test
    @DisplayName("98. NarrationController contains exactly ONE _onEngineError method declaration (MS-04.9H.7D2C1)")
    void narrationControllerContainsExactlyOneOnEngineErrorDeclaration() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String targetPattern = "_onEngineError(error, engineType) {";
        int firstIndex = controllerJs.indexOf(targetPattern);
        int lastIndex = controllerJs.lastIndexOf(targetPattern);

        assertThat(firstIndex).isGreaterThan(0);
        assertThat(firstIndex).isEqualTo(lastIndex);
    }

    @Test
    @DisplayName("99. Autoplay continuation failure in consolidated _onEngineError restores normal Play button (MS-04.9H.7D2C1)")
    void autoplayContinuationFailureRestoresNormalPlayButton() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.isAutoplayContinuation) {");
        assertThat(methodBody).contains("this.isAutoplayContinuation = false;");
        assertThat(methodBody).contains("this._setStatusMessage('Đã sang chương mới. Nhấn Phát để tiếp tục.');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.title = 'Phát giọng đọc';");
        assertThat(methodBody).contains("this.dom.playPauseBtn.classList.remove('is-playing');");
        assertThat(methodBody).contains("return;");
    }

    @Test
    @DisplayName("100. Managed error in consolidated _onEngineError clears aria-busy and exposes retry UX (MS-04.9H.7D2C1)")
    void managedErrorClearsAriaBusyAndExposesRetryUx() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.dom.player) {\n                this.dom.player.setAttribute('aria-busy', 'false');\n            }");
        assertThat(methodBody).contains("if (this.dom.statusText) {\n                this.dom.statusText.setAttribute('aria-busy', 'false');\n            }");
        assertThat(methodBody).contains("if (engineType === 'managed') {");
        assertThat(methodBody).contains("this._setStatusMessage('Không thể phát âm thanh giọng đọc Kiếm Lai. Vui lòng thử lại hoặc chọn Giọng thiết bị.');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Thử lại');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.classList.remove('is-playing');");
    }

    @Test
    @DisplayName("101. Device error in consolidated _onEngineError preserves device error message (MS-04.9H.7D2C1)")
    void deviceErrorPreservesDeviceErrorMessage() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("else {\n                this._setStatusMessage('Xảy ra lỗi khi phát giọng đọc.');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');");
    }

    @Test
    @DisplayName("102. No raw exception, stack or provider details leaked in _onEngineError (MS-04.9H.7D2C1)")
    void noRawExceptionOrProviderDetailsLeakedInOnEngineError() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).doesNotContain("error.message");
        assertThat(methodBody).doesNotContain("error.stack");
        assertThat(methodBody).doesNotContain("error.toString");
        assertThat(methodBody).doesNotContain("+ error");
    }

    @Test
    @DisplayName("103. Managed options use public voiceKey only and never internal IDs (MS-04.9H.7D3)")
    void managedOptionsUsePublicVoiceKeyOnly() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("option.value = 'managed:' + mv.voiceKey;");
        assertThat(controllerJs).contains("option.textContent = mv.displayName + (mv.defaultVoice ? ' (Mặc định)' : '');");
        assertThat(controllerJs).doesNotContain("managedVoiceId");
        assertThat(controllerJs).doesNotContain("providerVoiceId");
    }

    @Test
    @DisplayName("104. Device and Managed optgroups remain distinct with appropriate labels (MS-04.9H.7D3)")
    void deviceAndManagedOptgroupsRemainDistinct() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("managedGroup.label = 'Giọng Kiếm Lai';");
        assertThat(controllerJs).contains("deviceGroup.label = 'Thiết bị';");
        assertThat(controllerJs).contains("option.value = 'device:' + (dv.voiceURI || dv.name);");
    }

    @Test
    @DisplayName("105. Selecting Managed voice stops existing engines and loads manifest by voiceKey (MS-04.9H.7D3)")
    void selectingManagedVoiceStopsEnginesAndLoadsManifest() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.deviceEngine) {\n                this.deviceEngine.stop();\n            }");
        assertThat(methodBody).contains("if (this.managedEngine) {\n                this.managedEngine.stop();\n            }");
        assertThat(methodBody).contains("const manifest = await this._selectManagedPlayback(this.chapterId, voiceKey);");
    }

    @Test
    @DisplayName("106. Monotonic voice selection sequence guards against rapid selection race (MS-04.9H.7D3)")
    void monotonicVoiceSelectionSequenceGuardsAgainstRapidSelectionRace() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._voiceSelectionSequenceId = 0;");
        assertThat(controllerJs).contains("const selectionSequence = ++this._voiceSelectionSequenceId;");
        assertThat(controllerJs).contains("if (selectionSequence !== this._voiceSelectionSequenceId");
    }

    @Test
    @DisplayName("107. Late manifest load cannot overwrite chunks or state of latest selected voice (MS-04.9H.7D3)")
    void lateManifestLoadCannotOverwriteLatestSelectionState() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (selectionSequence !== this._voiceSelectionSequenceId ||\n                            !this.dom.voiceSelect ||\n                            this.dom.voiceSelect.value !== voiceVal) {\n                            return;\n                        }");

        int onManifestStart = controllerJs.indexOf("_onManifestLoaded(manifest) {");
        int onManifestEnd = controllerJs.indexOf("_populateVoiceDropdown(", onManifestStart);
        String onManifestBody = controllerJs.substring(onManifestStart, onManifestEnd);
        assertThat(onManifestBody).contains("manifest.selectedVoice.voiceKey === currentSelectedKey");
    }

    @Test
    @DisplayName("108. Successful voice switch keeps the selected playback mode ready state without autoplaying (MS-04.9H.7D3)")
    void successfulVoiceSwitchEndsInCleanReadyStateWithoutAutoplay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.engine === this.chapterEngine)",
                "this._updateChapterProgressDisplay(this.chapterEngine.getProgress())",
                "this._setStatusMessage('Sẵn sàng phát âm thanh cả chương.')",
                "this._setStatusMessage('Sẵn sàng phát giọng đọc Kiếm Lai (' + this.chunks.length + ' đoạn).');");
        assertThat(methodBody).doesNotContain("this.engine.play(");
        assertThat(methodBody).doesNotContain("this.play(");
    }

    @Test
    @DisplayName("109. Failed Managed voice load shows safe Vietnamese feedback without raw exceptions (MS-04.9H.7D3)")
    void failedManagedVoiceLoadShowsSafeFeedbackWithoutRawExceptions() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai. Vui lòng chọn giọng khác.');");
        assertThat(methodBody).doesNotContain("e.message");
        assertThat(methodBody).doesNotContain("e.stack");
        assertThat(methodBody).doesNotContain("e.toString");
    }

    @Test
    @DisplayName("110. Voice switch performs full state, highlight, and aria-busy cleanup (MS-04.9H.7D3)")
    void voiceSwitchPerformsFullStateAndAriaBusyCleanup() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("this._cancelPendingAutoNext();");
        assertThat(methodBody).contains("this.isCompleted = false;");
        assertThat(methodBody).contains("this.hasMeaningfulResume = false;");
        assertThat(methodBody).contains("this.isAutoplayContinuation = false;");
        assertThat(methodBody).contains("this._clearSavedResume();");
        assertThat(methodBody).contains("this._clearHighlight();");
        assertThat(methodBody).contains("this.dom.player.setAttribute('aria-busy', 'false');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');");
        assertThat(methodBody).contains("this.dom.playPauseBtn.classList.remove('is-playing');");
    }

    @Test
    @DisplayName("111. Selecting Device voice preserves Device TTS path and preference (MS-04.9H.7D3)")
    void selectingDeviceVoicePreservesDeviceTtsPathAndPreference() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("this.activeEngineType = 'device';");
        assertThat(methodBody).contains("this.deviceEngine.setVoice(voiceIdentifier);");
        assertThat(methodBody).contains("this._parseAndLoadChunks();");
        assertThat(methodBody).contains("this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' câu).');");
    }

    @Test
    @DisplayName("112. No raw provider or storage identifiers leaked in voice picker (MS-04.9H.7D3)")
    void noRawProviderOrStorageIdentifiersLeakedInVoicePicker() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).doesNotContain("cloudinary");
        assertThat(controllerJs).doesNotContain("providerVoiceId");
        assertThat(controllerJs).doesNotContain("managedVoiceId");
    }

    @Test
    @DisplayName("113. ManagedAudioEngine maintains distinct controllers for playback prepare and manifest loading (MS-04.9H.7D3A1)")
    void managedAudioEngineMaintainsDistinctControllers() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("this._activeFetchController = null;");
        assertThat(managedJs).contains("this._activeManifestFetchController = null;");
    }

    @Test
    @DisplayName("114. loadManifest aborts previous manifest controller BEFORE evaluating cache (MS-04.9H.7D3A1)")
    void loadManifestAbortsPreviousManifestControllerBeforeEvaluatingCache() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int methodStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int abortIdx = managedJs.indexOf("prevController.abort();", methodStart);
        int cacheIdx = managedJs.indexOf("this.manifestCache.has(cacheKey)", methodStart);

        assertThat(abortIdx).isGreaterThan(methodStart);
        assertThat(cacheIdx).isGreaterThan(abortIdx);
    }

    @Test
    @DisplayName("115. Manifest commit requires BOTH sequence and controller ownership (MS-04.9H.7D3A1)")
    void manifestCommitRequiresSequenceAndControllerOwnership() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int methodStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int methodEnd = managedJs.indexOf("findNextPlayableIndex(", methodStart);
        String methodBody = managedJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (sequenceId !== this._manifestLoadSequenceId || this._activeManifestFetchController !== controller) {");
        assertThat(methodBody).contains("this.setManifest(manifest, this.currentSegmentIndex);");
    }

    @Test
    @DisplayName("116. Stale or superseded manifest response throws AbortError rather than normal success (MS-04.9H.7D3A1)")
    void staleOrSupersededManifestThrowsAbortError() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int methodStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int methodEnd = managedJs.indexOf("findNextPlayableIndex(", methodStart);
        String methodBody = managedJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("const abortError = new Error('Manifest load was aborted or superseded.');\n                    abortError.name = 'AbortError';\n                    throw abortError;");
    }

    @Test
    @DisplayName("117. Stale or aborted manifest load does not transition engine to ERROR (MS-04.9H.7D3A1)")
    void staleOrAbortedManifestLoadDoesNotTransitionEngineToError() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int methodStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int methodEnd = managedJs.indexOf("findNextPlayableIndex(", methodStart);
        String methodBody = managedJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (sequenceId !== this._manifestLoadSequenceId || this._activeManifestFetchController !== controller) {\n                    throw error;\n                }");
        assertThat(methodBody).contains("this._handleError(error);");
    }

    @Test
    @DisplayName("118. Stale manifest controller cannot clear a newer manifest controller (MS-04.9H.7D3A1)")
    void staleManifestControllerCannotClearNewerController() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int methodStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int methodEnd = managedJs.indexOf("findNextPlayableIndex(", methodStart);
        String methodBody = managedJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this._activeManifestFetchController === controller) {\n                    this._activeManifestFetchController = null;\n                }");
    }

    @Test
    @DisplayName("119. cancel() increments sequences and aborts both playback and manifest controllers (MS-04.9H.7D3A1)")
    void cancelIncrementsSequencesAndAbortsBothControllers() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int cancelStart = managedJs.indexOf("cancel() {");
        int cancelEnd = managedJs.indexOf("_handleAudioEnded() {", cancelStart);
        String cancelBody = managedJs.substring(cancelStart, cancelEnd);

        assertThat(cancelBody).contains("this._playbackSequenceId++;");
        assertThat(cancelBody).contains("this._manifestLoadSequenceId++;");
        assertThat(cancelBody).contains("this._activeFetchController.abort();");
        assertThat(cancelBody).contains("this._activeFetchController = null;");
        assertThat(cancelBody).contains("this._activeManifestFetchController.abort();");
        assertThat(cancelBody).contains("this._activeManifestFetchController = null;");
    }

    @Test
    @DisplayName("120. Voice change in NarrationController clears chunks and disables Play before fetch (MS-04.9H.7D3A)")
    void voiceChangeClearsChunksAndDisablesPlayBeforeFetch() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        int chunksClear = methodBody.indexOf("this.chunks = [];");
        int disableBtn = methodBody.indexOf("this.dom.playPauseBtn.disabled = true;");
        int fetchCall = methodBody.indexOf("await this._selectManagedPlayback(this.chapterId, voiceKey);");

        assertThat(chunksClear).isGreaterThan(0);
        assertThat(disableBtn).isGreaterThan(chunksClear);
        assertThat(fetchCall).isGreaterThan(disableBtn);
    }

    @Test
    @DisplayName("121. Failed Managed voice manifest fetch isolates state, keeps Play disabled, and shows notice (MS-04.9H.7D3A)")
    void failedManagedVoiceManifestFetchIsolatesStateAndKeepsPlayDisabled() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai. Vui lòng chọn giọng khác.');");
        int catchStart = methodBody.indexOf("} catch (e) {");
        String catchBlock = methodBody.substring(catchStart);

        assertThat(catchBlock).contains("this.chunks = [];");
        assertThat(catchBlock).contains("this._updateProgressDisplay(0, 0);");
        assertThat(catchBlock).contains("this.dom.playPauseBtn.disabled = true;");
        assertThat(catchBlock).contains("this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');");
    }

    @Test
    @DisplayName("122. Successful legacy Managed voice manifest fetch enables Play and keeps segment count UI (MS-04.9H.7D3A)")
    void successfulLegacyManagedVoiceManifestFetchEnablesPlayAndUpdatesSegmentCount() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("this.dom.playPauseBtn.disabled = (this.chunks.length === 0);");
        assertThat(methodBody).contains("this.dom.playPauseBtn.removeAttribute('aria-disabled');");
        assertThat(methodBody).contains("} else {\n                            this._updateProgressDisplay(0, this.chunks.length);",
                "} else {\n                            this._setStatusMessage('Sẵn sàng phát giọng đọc Kiếm Lai (' + this.chunks.length + ' đoạn).');");
    }

    @Test
    @DisplayName("123. Switching to Device voice invalidates and cancels pending Managed work (MS-04.9H.7D3A1)")
    void switchingToDeviceVoiceInvalidatesAndCancelsPendingManagedWork() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.managedEngine && typeof this.managedEngine.cancel === 'function') {\n                this.managedEngine.cancel();\n            }");
    }

    @Test
    @DisplayName("124. _activateEngine validates requested chapterId across in-page async transitions (MS-04.9H.7D3A1)")
    void activateEngineValidatesRequestedChapterId() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_activateEngine(type, identifier) {");
        int methodEnd = controllerJs.indexOf("setFollowMode(enabled, persist = true) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("const requestedChapterId = this.chapterId;");
        assertThat(methodBody).contains("if (this.chapterId !== requestedChapterId) {\n                            return;\n                        }");
        assertThat(methodBody).contains("if (this.activeEngineType !== 'managed') {\n                            return;\n                        }");
        assertThat(methodBody).contains("if (currentSelectedKey && currentSelectedKey !== identifier) {\n                            return;\n                        }");
        assertThat(methodBody).contains("if (this.dom.voiceSelect && this.dom.voiceSelect.value !== ('managed:' + identifier)) {\n                            return;\n                        }");
    }

    @Test
    @DisplayName("125. Playback prepare and manifest loading operate on independent request controllers (MS-04.9H.7D3A1)")
    void playbackPrepareAndManifestLoadingOperateOnIndependentControllers() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("pause() {", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        int loadStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int loadEnd = managedJs.indexOf("findNextPlayableIndex(", loadStart);
        String loadBody = managedJs.substring(loadStart, loadEnd);

        assertThat(playBody).contains("this._activeFetchController = controller;");
        assertThat(playBody).doesNotContain("this._activeManifestFetchController = controller;");

        assertThat(loadBody).contains("this._activeManifestFetchController = controller;");
        assertThat(loadBody).doesNotContain("this._activeFetchController = controller;");
    }

    @Test
    @DisplayName("126. play() invalidates pending manifest authority and aborts active manifest controller (MS-04.9H.7D3A2)")
    void playInvalidatesPendingManifestAuthorityAndAbortsController() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("pause() {", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("if (this._activeManifestFetchController) {\n                this._manifestLoadSequenceId++;\n                const manifestCtrl = this._activeManifestFetchController;\n                try {\n                    manifestCtrl.abort();\n                } catch (ignored) {}\n                if (this._activeManifestFetchController === manifestCtrl) {\n                    this._activeManifestFetchController = null;\n                }\n            }");
    }

    @Test
    @DisplayName("127. Late manifest after playback cannot call setManifest or replace playing audio (MS-04.9H.7D3A2)")
    void lateManifestAfterPlaybackCannotCallSetManifest() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int loadStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int loadEnd = managedJs.indexOf("findNextPlayableIndex(", loadStart);
        String loadBody = managedJs.substring(loadStart, loadEnd);

        assertThat(loadBody).contains("if (sequenceId !== this._manifestLoadSequenceId || this._activeManifestFetchController !== controller) {");
        assertThat(loadBody).contains("throw abortError;");
    }

    @Test
    @DisplayName("128. Auto-next stale manifest success cannot update chunks or autoplay narration (MS-04.9H.7D3A2)")
    void autoNextStaleManifestSuccessCannotUpdateChunksOrAutoplay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transitionStart = controllerJs.indexOf("_applyChapterTransition(");
        int transitionEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transitionStart);
        String transitionBody = controllerJs.substring(transitionStart, transitionEnd);

        assertThat(transitionBody).contains("const requestedChapterId = newChapterId;");
        assertThat(transitionBody).contains("const activeEngineVoiceKey = (this.managedEngine && typeof this.managedEngine.getSelectedVoiceKey === 'function')\n                    ? this.managedEngine.getSelectedVoiceKey()\n                    : null;");
        assertThat(transitionBody).contains("const requestedVoiceKey = (continuationIntent && continuationIntent.mode === 'managed' ? continuationIntent.voiceKey : null) || activeEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)\n" +
                "                    ? this.savedVoicePreference.voiceKey\n" +
                "                    : null);");
        assertThat(transitionBody).contains("const requestedVoiceSequence = this._voiceSelectionSequenceId;");
        assertThat(transitionBody).contains("if (this.chapterId !== requestedChapterId) {\n                        return;\n                    }");
        assertThat(transitionBody).contains("if (this.activeEngineType !== 'managed') {\n                        return;\n                    }");
        assertThat(transitionBody).contains("if (this._voiceSelectionSequenceId !== requestedVoiceSequence) {\n                        return;\n                    }");
        assertThat(transitionBody).contains("if (currentSelectedKey !== requestedVoiceKey) {\n                        return;\n                    }");
    }

    @Test
    @DisplayName("129. Auto-next stale AbortError cannot overwrite new voice status or show error (MS-04.9H.7D3A2)")
    void autoNextStaleAbortErrorCannotOverwriteStatusOrShowError() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transitionStart = controllerJs.indexOf("_applyChapterTransition(");
        int transitionEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transitionStart);
        String transitionBody = controllerJs.substring(transitionStart, transitionEnd);

        assertThat(transitionBody).contains("if (err && err.name === 'AbortError') {\n                        return;\n                    }");
        assertThat(transitionBody).contains("if (this.chapterId !== requestedChapterId ||\n                        this.activeEngineType !== 'managed' ||\n                        this._voiceSelectionSequenceId !== requestedVoiceSequence) {\n                        return;\n                    }");
    }

    @Test
    @DisplayName("130. _activateEngine('device') cancels pending Managed work (MS-04.9H.7D3A2)")
    void activateEngineDeviceCancelsPendingManagedWork() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_activateEngine(type, identifier) {");
        int methodEnd = controllerJs.indexOf("setFollowMode(enabled, persist = true) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("} else if (this.deviceEngine) {");
        assertThat(methodBody).contains("if (this.managedEngine && typeof this.managedEngine.cancel === 'function') {\n                    this.managedEngine.cancel();\n                }");
    }

    @Test
    @DisplayName("131. Chrome voiceschanged before managed catalog resolution skips automatic activation and preserves pending fetch (MS-04.9H.7D7)")
    void chromeVoicesChangedBeforeManagedCatalogResolutionSkipsAutomaticActivation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineVoicesChanged(voices) {");
        int methodEnd = controllerJs.indexOf("_updateProgressDisplay(current, total) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("const skipActivation = isPlaybackActive || Boolean(this._hasUserExplicitlySelectedVoice) || !this._managedCatalogResolved;");
        assertThat(methodBody).contains("this._populateVoiceDropdown(voices, mVoices, { skipActivation });");
    }

    @Test
    @DisplayName("132. Initial dropdown population in init() skips premature device engine activation (MS-04.9H.7D7)")
    void initialDropdownPopulationInInitSkipsPrematureDeviceActivation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int initStart = controllerJs.indexOf("init() {");
        int initEnd = controllerJs.indexOf("_queryDomElements() {", initStart);
        String initBody = controllerJs.substring(initStart, initEnd);

        assertThat(initBody).contains("this._populateVoiceDropdown(deviceVoices, [], { skipActivation: true });");
        assertThat(initBody).contains("this._loadInitialManagedVoices();");
    }

    @Test
    @DisplayName("133. Natural pause before ended event does not transition to PAUSED or suppress auto-advance (MS-04.9H.7D7)")
    void naturalPauseBeforeEndedDoesNotTransitionToPaused() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int pauseStart = managedJs.indexOf("_handleAudioPause() {");
        int pauseEnd = managedJs.indexOf("_handleError(error) {", pauseStart);
        String pauseBody = managedJs.substring(pauseStart, pauseEnd);

        assertThat(pauseBody).contains("if (this.audio && this.audio.ended) {\n                return;\n            }");
        assertThat(pauseBody).contains("if (this.state === ManagedEngineState.PLAYING) {\n                this._transitionState(ManagedEngineState.PAUSED);\n            }");
    }

    @Test
    @DisplayName("134. _handleAudioEnded requires strict PLAYING state and advances naturally (MS-04.9H.7D8)")
    void handleAudioEndedRequiresStrictPlayingState() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).contains("if (this.state !== ManagedEngineState.PLAYING) {\n                return;\n            }");
        assertThat(endedBody).doesNotContain("this.state !== ManagedEngineState.PAUSED");
        assertThat(endedBody).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(endedBody).contains("if (nextIndex < this.segments.length) {");
        assertThat(endedBody).contains("if (this._canUsePreparedFastPath(nextIndex)) {\n                    this._playPreparedAdjacentSegment(nextIndex);\n                } else {\n                    this.play(nextIndex);\n                }");
    }

    @Test
    @DisplayName("135. loadManifest fetch explicitly sets cache: 'no-store' (MS-04.9H.7D7)")
    void loadManifestFetchExplicitlySetsCacheNoStore() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int loadStart = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {");
        int loadEnd = managedJs.indexOf("findNextPlayableIndex(", loadStart);
        String loadBody = managedJs.substring(loadStart, loadEnd);

        assertThat(loadBody).contains("const response = await fetchFn(url, {");
        assertThat(loadBody).contains("cache: 'no-store',");
        assertThat(loadBody).contains("headers: { 'Accept': 'application/json' },");
    }

    @Test
    @DisplayName("136. Manifest controller sets no-store, no-cache, and Expires = 0 headers (MS-04.9H.7D7)")
    void manifestControllerSetsNoStoreHeaders() throws Exception {
        String controllerJava = read("src/main/java/com/universe/novel/entry/reader/PublicNovelChapterNarrationManifestController.java");
        assertThat(controllerJava).contains("disableCaching(response);");
        assertThat(controllerJava).contains("response.setHeader(\"Cache-Control\", \"no-store, no-cache, must-revalidate, max-age=0\");");
        assertThat(controllerJava).contains("response.setHeader(\"Pragma\", \"no-cache\");");
        assertThat(controllerJava).contains("response.setDateHeader(\"Expires\", 0);");
    }

    @Test
    @DisplayName("137. Explicit user pause transitions to PAUSED and suppresses subsequent ended event auto-advance (MS-04.9H.7D7)")
    void explicitUserPauseSuppressesSubsequentEndedEvent() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int pauseStart = managedJs.indexOf("pause() {");
        int pauseEnd = managedJs.indexOf("resume() {", pauseStart);
        String pauseBody = managedJs.substring(pauseStart, pauseEnd);

        assertThat(pauseBody).contains("this._transitionState(ManagedEngineState.PAUSED);");

        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).contains("if (this.state !== ManagedEngineState.PLAYING) {\n                return;\n            }");
    }

    @Test
    @DisplayName("138. Final natural segment completes chapter once and transitions to STOPPED (MS-04.9H.7D7)")
    void finalNaturalSegmentCompletesChapterOnce() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).contains("this._transitionState(ManagedEngineState.STOPPED);");
        assertThat(endedBody).contains("if (typeof this.options.onChapterEnd === 'function') {");
        assertThat(endedBody).contains("this.options.onChapterEnd();");
    }

    @Test
    @DisplayName("139. Stale ended events during STOPPED, IDLE, PREPARING, or BLOCKED states are ignored (MS-04.9H.7D7)")
    void staleEndedEventsDuringNonPlayingStatesAreIgnored() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).startsWith("_handleAudioEnded() {\n            if (this.state !== ManagedEngineState.PLAYING) {\n                return;\n            }");
    }

    @Test
    @DisplayName("140. Speculative warm-up targets ONLY adjacent N+1 segment while N is PLAYING (MS-04.9H.7D8)")
    void speculativeWarmUpTargetsOnlyAdjacentNextSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int warmUpStart = managedJs.indexOf("_scheduleAdjacentWarmUp(currentIndex) {");
        int warmUpEnd = managedJs.indexOf("async prepareSegmentPlayback(", warmUpStart);
        String warmUpBody = managedJs.substring(warmUpStart, warmUpEnd);

        assertThat(warmUpBody).contains("if (this.state !== ManagedEngineState.PLAYING) {\n                return;\n            }");
        assertThat(warmUpBody).contains("const nextIndex = currentIndex + 1;");
        assertThat(warmUpBody).contains("if (nextIndex >= this.segments.length)");
        assertThat(warmUpBody).contains("const nextSegment = this.segments[nextIndex];");
        assertThat(warmUpBody).contains("this._cancelPrefetch();");
    }

    @Test
    @DisplayName("141. Speculative warm-up does not mutate active playback state or replace active HTMLAudioElement (MS-04.9H.7D8)")
    void speculativeWarmUpDoesNotMutateActivePlaybackState() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int warmUpStart = managedJs.indexOf("_scheduleAdjacentWarmUp(currentIndex) {");
        int warmUpEnd = managedJs.indexOf("async prepareSegmentPlayback(", warmUpStart);
        String warmUpBody = managedJs.substring(warmUpStart, warmUpEnd);

        assertThat(warmUpBody).doesNotContain("this.audio.src =");
        assertThat(warmUpBody).doesNotContain("this._transitionState(");
        assertThat(warmUpBody).doesNotContain("this.currentSegmentIndex =");
        assertThat(warmUpBody).contains("let standbyAudio = null;");
        assertThat(warmUpBody).contains("standbyAudio = new Audio();");
        assertThat(warmUpBody).contains("standbyAudio.preload = 'auto';");
        assertThat(warmUpBody).contains("standbyAudio.src = playbackDto.audioUrl;");
    }

    @Test
    @DisplayName("142. Speculative prefetch is cleanly cancelled on seek, stop, cancel, and voice changes (MS-04.9H.7D8)")
    void speculativePrefetchCancelledOnInterruption() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");

        int cancelPrefetchStart = managedJs.indexOf("_cancelPrefetch() {");
        int cancelPrefetchEnd = managedJs.indexOf("_scheduleAdjacentWarmUp(", cancelPrefetchStart);
        String cancelPrefetchBody = managedJs.substring(cancelPrefetchStart, cancelPrefetchEnd);
        assertThat(cancelPrefetchBody).contains("this._prefetchSequenceId++;");
        assertThat(cancelPrefetchBody).contains("this._activePrefetchController.abort();");
        assertThat(cancelPrefetchBody).contains("this._warmedSegment");

        int stopStart = managedJs.indexOf("stop() {");
        int stopEnd = managedJs.indexOf("seekToSegment(", stopStart);
        assertThat(managedJs.substring(stopStart, stopEnd)).contains("this._cancelPrefetch();");

        int seekStart = managedJs.indexOf("seekToSegment(index) {");
        int seekEnd = managedJs.indexOf("seekToChunk(", seekStart);
        assertThat(managedJs.substring(seekStart, seekEnd)).contains("this._cancelPrefetch();");

        int cancelStart = managedJs.indexOf("cancel() {");
        int cancelEnd = managedJs.indexOf("_handleAudioEnded() {", cancelStart);
        assertThat(managedJs.substring(cancelStart, cancelEnd)).contains("this._cancelPrefetch();");
    }

    @Test
    @DisplayName("143. Canonical POST /prepare is strictly retained at actual playback transition (MS-04.9H.7D8)")
    void canonicalPrepareRetainedAtActualTransition() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const playbackDto = await this.prepareSegmentPlayback(");
        assertThat(playBody).contains("this.prepareSegmentPlayback(\n                        this.chapterId,\n                        requestedSegmentId,\n                        voiceKey,");
        assertThat(playBody).contains("this._scheduleAdjacentWarmUp(requestedIndex);");
    }

    @Test
    @DisplayName("144. Managed Audio seekBySeconds handles boundary seeking and preserves paused state (MS-04.9H.7D8)")
    void managedAudioSeekBySecondsClampingAndStatePreservation() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekTimeStart = managedJs.indexOf("seekBySeconds(deltaSeconds) {");
        int seekTimeEnd = managedJs.indexOf("nextSegment() {", seekTimeStart);
        String seekTimeBody = managedJs.substring(seekTimeStart, seekTimeEnd);

        assertThat(seekTimeBody).contains("const wasPaused = (this.state === ManagedEngineState.PAUSED);");
        assertThat(seekTimeBody).contains("const curTime = Number.isFinite(this.audio.currentTime) ? this.audio.currentTime : 0;");
        assertThat(seekTimeBody).contains("const duration = (Number.isFinite(this.audio.duration) && this.audio.duration > 0) ? this.audio.duration : null;");
        assertThat(seekTimeBody).contains("if (delta < 0)");
        assertThat(seekTimeBody).contains("this.play(prevIndex, { seekFromEnd: carry, paused: wasPaused });");
        assertThat(seekTimeBody).contains("this.play(nextIndex, { seekFromStart: overflow, paused: wasPaused });");
    }

    @Test
    @DisplayName("145. Reader HTML template contains Rewind 5s and Forward 5s buttons with proper accessibility (MS-04.9H.7D8)")
    void readerHtmlContainsRewindAndForwardButtons() throws Exception {
        String chapterHtml = read("src/main/resources/templates/novel/chapter.html");
        assertThat(chapterHtml).contains("id=\"novelNarrationRewindBtn\"");
        assertThat(chapterHtml).contains("aria-label=\"Lùi 5 giây\"");
        assertThat(chapterHtml).contains("title=\"Lùi 5 giây\"");
        assertThat(chapterHtml).contains("id=\"novelNarrationForwardBtn\"");
        assertThat(chapterHtml).contains("aria-label=\"Tua nhanh 5 giây\"");
        assertThat(chapterHtml).contains("title=\"Tua nhanh 5 giây\"");
    }

    @Test
    @DisplayName("146. Reader CSS styles seek buttons matching narration bar aesthetics (MS-04.9H.7D8)")
    void readerCssStylesSeekButtons() throws Exception {
        String readerCss = read("src/main/resources/static/css/novel/reader.css");
        assertThat(readerCss).contains(".novel-narration-btn--seek");
        assertThat(readerCss).contains(".novel-narration-btn--seek:hover:not(:disabled)");
        assertThat(readerCss).contains(".novel-narration-btn:disabled,");
    }

    @Test
    @DisplayName("147. NarrationController delegates rewind and forward to managedEngine.seekBySeconds(±5) (MS-04.9H.7D8)")
    void narrationControllerDelegatesRewindAndForward() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("rewindBtn: '#novelNarrationRewindBtn',");
        assertThat(controllerJs).contains("forwardBtn: '#novelNarrationForwardBtn',");
        assertThat(controllerJs).contains("this.engine.seekBySeconds(-5);");
        assertThat(controllerJs).contains("this.engine.seekBySeconds(5);");
    }

    @Test
    @DisplayName("148. Rewind and Forward buttons are disabled for Device TTS and enabled for Managed Audio (MS-04.9H.7D8)")
    void rewindAndForwardButtonsDisabledForDeviceTts() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int navStart = controllerJs.indexOf("_updateNavButtons() {");
        int navEnd = controllerJs.indexOf("destroy() {", navStart);
        String navBody = controllerJs.substring(navStart, navEnd);

        assertThat(navBody).contains("const isManaged = this.activeEngineType === 'managed';");
        assertThat(navBody).contains("if (this.dom.rewindBtn) {\n                this.dom.rewindBtn.disabled = !isManaged;\n                this.dom.rewindBtn.setAttribute('aria-disabled', String(!isManaged));\n            }");
        assertThat(navBody).contains("if (this.dom.forwardBtn) {\n                this.dom.forwardBtn.disabled = !isManaged;\n                this.dom.forwardBtn.setAttribute('aria-disabled', String(!isManaged));\n            }");
    }

    @Test
    @DisplayName("149. Prev and Next buttons remain dedicated segment navigation with boundary disabling (MS-04.9H.7D8)")
    void prevAndNextButtonsRemainSegmentNavigation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int navStart = controllerJs.indexOf("_updateNavButtons() {");
        int navEnd = controllerJs.indexOf("destroy() {", navStart);
        String navBody = controllerJs.substring(navStart, navEnd);

        assertThat(navBody).contains("const currentIndex = this.engine.getCurrentChunkIndex();");
        assertThat(navBody).contains("const total = this.chunks.length;");
        assertThat(navBody).contains("const canPrev = this.engine === this.chapterEngine ? this.chapterEngine.canPrevious() : currentIndex > 0;");
        assertThat(navBody).contains("const canNext = this.engine === this.chapterEngine ? this.chapterEngine.canNext() : !this.isCompleted && (currentIndex < total - 1);");
        assertThat(navBody).contains("this.dom.prevBtn.disabled = !canPrev;");
        assertThat(navBody).contains("this.dom.nextBtn.disabled = !canNext;");
    }

    @Test
    @DisplayName("150. Auto Next prefers active engine voiceKey over saved preference and requests on-demand preparation (MS-04.9H.7D8)")
    void autoNextPrefersActiveEngineVoiceKeyOverSavedPreference() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("const activeEngineVoiceKey = (this.managedEngine && typeof this.managedEngine.getSelectedVoiceKey === 'function')\n                    ? this.managedEngine.getSelectedVoiceKey()\n                    : null;");
        assertThat(transBody).contains("const requestedVoiceKey = (continuationIntent && continuationIntent.mode === 'managed' ? continuationIntent.voiceKey : null) || activeEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)\n                    ? this.savedVoicePreference.voiceKey\n                    : null);");
        assertThat(transBody).contains("this._selectManagedPlayback(requestedChapterId, requestedVoiceKey).then(manifest => {");
        assertThat(transBody).contains("this.managedEngine.play(0);");
    }

    @Test
    @DisplayName("151. Auto Next null Managed voice authority fails safely without un-keyed default re-resolution (MS-04.9H.7D8)")
    void autoNextNullManagedVoiceFailsSafelyWithoutUnkeyedReResolution() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("if (!requestedVoiceKey) {");
        assertThat(transBody).contains("handleManagedFailure();");
        assertThat(transBody).contains("this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai cho chương mới. Vui lòng chọn giọng khác hoặc sử dụng Giọng thiết bị.');");
        assertThat(transBody).doesNotContain("defManaged");
    }

    @Test
    @DisplayName("152. Auto Next failure isolates Chapter-A audio, disables play button, and keeps voice selector usable (MS-04.9H.7D8)")
    void autoNextFailureIsolatesChapterAAudioAndKeepsVoiceSelectorUsable() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("if (this.managedEngine && typeof this.managedEngine.setManifest === 'function') {\n                        this.managedEngine.setManifest(null);\n                    }");
        assertThat(transBody).contains("this.chunks = [];");
        assertThat(transBody).contains("this._updateProgressDisplay(0, 0);");
        assertThat(transBody).contains("this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai cho chương mới. Vui lòng chọn giọng khác hoặc sử dụng Giọng thiết bị.');");
        assertThat(transBody).contains("this.dom.playPauseBtn.disabled = true;");
        assertThat(transBody).contains("if (this.dom.voiceSelect) {\n                        this.dom.voiceSelect.disabled = false;\n                    }");
        assertThat(transBody).contains("this._updateNavButtons();");
    }

    @Test
    @DisplayName("153. Reader Narration Dock uses single-row 7-slot grid without obsolete 5-slot layout (MS-04.9H.7D8)")
    void narrationDockUsesSevenSlotGrid() throws Exception {
        String readerCss = read("src/main/resources/static/css/novel/reader.css");
        assertThat(readerCss).contains(".novel-narration-dock {\n    display: grid;\n    grid-template-columns: repeat(7, minmax(0, 1fr));");
        assertThat(readerCss).doesNotContain("grid-template-columns: repeat(5,");
        assertThat(readerCss).doesNotContain("Five-Equal-Slot");
        assertThat(readerCss).contains("/* Seven-Slot Centered Narration Dock */");
    }

    @Test
    @DisplayName("154. Chapter transition immediately clears Chapter-A chunks and progress before loading Chapter-B (MS-04.9H.7D8)")
    void chapterTransitionImmediatelyClearsChapterAChunks() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int dispatchEvent = controllerJs.indexOf("document.dispatchEvent(new CustomEvent('kiemlai:chapter-changed'", transStart);
        String entryBody = controllerJs.substring(transStart, dispatchEvent);

        assertThat(entryBody).contains("this.chapterId = newChapterId;");
        assertThat(entryBody).contains("this.chunks = [];");
        assertThat(entryBody).contains("this._updateProgressDisplay(0, 0);");
        assertThat(entryBody).contains("this._updateNavButtons();");
    }

    @Test
    @DisplayName("155. Double Buffering: standby Audio element is promoted and reused upon matching segment transition (MS-04.9H.7D8)")
    void doubleBufferingPromotesStandbyAudioElement() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const isWarmedMatch = this._warmedSegment &&");
        assertThat(playBody).contains("this._warmedSegment.chapterId === this.chapterId &&");
        assertThat(playBody).contains("this._warmedSegment.segmentId === requestedSegmentId &&");
        assertThat(playBody).contains("this._warmedSegment.voiceKey === voiceKey &&");
        assertThat(playBody).contains("this._warmedSegment.audioUrl === playbackDto.audioUrl &&");
        assertThat(playBody).contains("const promotedAudio = this._warmedSegment.standbyAudio || this._warmedSegment.preloadAudio;");
        assertThat(playBody).contains("this._unbindAudioListeners();");
        assertThat(playBody).contains("this.audio = promotedAudio;");
        assertThat(playBody).contains("this._bindAudioListeners();");
    }

    @Test
    @DisplayName("156. Double Buffering: N+1 audio is loaded into standby element before segment N ends (MS-04.9H.7D8)")
    void doubleBufferingLoadsNextAudioBeforeEnded() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int warmUpStart = managedJs.indexOf("_scheduleAdjacentWarmUp(currentIndex) {");
        int warmUpEnd = managedJs.indexOf("async prepareSegmentPlayback(", warmUpStart);
        String warmUpBody = managedJs.substring(warmUpStart, warmUpEnd);

        assertThat(warmUpBody).contains("if (this.state !== ManagedEngineState.PLAYING) {\n                return;\n            }");
        assertThat(warmUpBody).contains("standbyAudio = new Audio();");
        assertThat(warmUpBody).contains("standbyAudio.preload = 'auto';");
        assertThat(warmUpBody).contains("standbyAudio.src = playbackDto.audioUrl;");
        assertThat(warmUpBody).contains("this._warmedSegment = {");
        assertThat(warmUpBody).contains("standbyAudio: standbyAudio,");
    }

    @Test
    @DisplayName("157. Double Buffering: Actual playback transition still validates canonical /prepare authority (MS-04.9H.7D8)")
    void doubleBufferingStillFreshValidatesPrepareAuthority() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const playbackDto = await this.prepareSegmentPlayback(");
        int prepCall = playBody.indexOf("prepareSegmentPlayback(");
        int promoCheck = playBody.indexOf("const isWarmedMatch = this._warmedSegment");
        assertThat(prepCall).isGreaterThan(0);
        assertThat(promoCheck).isGreaterThan(prepCall);
    }

    @Test
    @DisplayName("158. Double Buffering: Stale standby is cleanly discarded and cannot play (MS-04.9H.7D8)")
    void staleStandbyIsCleanlyDiscarded() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int cancelPrefetchStart = managedJs.indexOf("_cancelPrefetch() {");
        int cancelPrefetchEnd = managedJs.indexOf("_scheduleAdjacentWarmUp(", cancelPrefetchStart);
        String cancelPrefetchBody = managedJs.substring(cancelPrefetchStart, cancelPrefetchEnd);

        assertThat(cancelPrefetchBody).contains("this._prefetchSequenceId++;");
        assertThat(cancelPrefetchBody).contains("standby.pause();");
        assertThat(cancelPrefetchBody).contains("standby.src = '';");
        assertThat(cancelPrefetchBody).contains("this._warmedSegment = null;");
    }

    @Test
    @DisplayName("159. Boundary Seek: -5s crosses to previous segment with carry calculation and segment 0 clamps (MS-04.9H.7D8)")
    void boundarySeekRewindCarriesToPreviousSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekTimeStart = managedJs.indexOf("seekBySeconds(deltaSeconds) {");
        int seekTimeEnd = managedJs.indexOf("nextSegment() {", seekTimeStart);
        String seekTimeBody = managedJs.substring(seekTimeStart, seekTimeEnd);

        assertThat(seekTimeBody).contains("const rewindAmount = Math.abs(delta);");
        assertThat(seekTimeBody).contains("if (curTime >= rewindAmount) {");
        assertThat(seekTimeBody).contains("const carry = rewindAmount - curTime;");
        assertThat(seekTimeBody).contains("if (this.currentSegmentIndex === 0) {\n                        // Segment 0 rewind clamps to chapter start\n                        try {\n                            this.audio.currentTime = 0;");
        assertThat(seekTimeBody).contains("const prevIndex = this.currentSegmentIndex - 1;");
        assertThat(seekTimeBody).contains("this.play(prevIndex, { seekFromEnd: carry, paused: wasPaused });");
    }

    @Test
    @DisplayName("160. Boundary Seek: +5s crosses to adjacent next segment with overflow calculation and final segment clamps (MS-04.9H.7D8)")
    void boundarySeekForwardCarriesToNextSegment() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekTimeStart = managedJs.indexOf("seekBySeconds(deltaSeconds) {");
        int seekTimeEnd = managedJs.indexOf("nextSegment() {", seekTimeStart);
        String seekTimeBody = managedJs.substring(seekTimeStart, seekTimeEnd);

        assertThat(seekTimeBody).contains("const forwardAmount = delta;");
        assertThat(seekTimeBody).contains("if (duration !== null && (curTime + forwardAmount >= duration)) {");
        assertThat(seekTimeBody).contains("const overflow = (curTime + forwardAmount) - duration;");
        assertThat(seekTimeBody).contains("if (this.currentSegmentIndex < this.segments.length - 1) {");
        assertThat(seekTimeBody).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(seekTimeBody).contains("this.play(nextIndex, { seekFromStart: overflow, paused: wasPaused });");
        assertThat(seekTimeBody).contains("// Final segment forward clamps/completes safely");
    }

    @Test
    @DisplayName("161. Boundary Seek: Playing and paused states are preserved across boundary seeks (MS-04.9H.7D8)")
    void boundarySeekPreservesPlayingAndPausedState() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekTimeStart = managedJs.indexOf("seekBySeconds(deltaSeconds) {");
        int seekTimeEnd = managedJs.indexOf("nextSegment() {", seekTimeStart);
        String seekTimeBody = managedJs.substring(seekTimeStart, seekTimeEnd);

        assertThat(seekTimeBody).contains("const wasPaused = (this.state === ManagedEngineState.PAUSED);");
        assertThat(seekTimeBody).contains("paused: wasPaused");

        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const shouldBePaused = Boolean(seekOptions && seekOptions.paused);");
        assertThat(playBody).contains("if (shouldBePaused) {\n                            this._transitionState(ManagedEngineState.PAUSED);");
        assertThat(playBody).contains("} else {\n                            this._transitionState(ManagedEngineState.PLAYING);");
    }

    @Test
    @DisplayName("162. Fallback Policy: Auto Next on MISSING audio triggers on-demand generation for the same voice (MS-04.9H.7D8)")
    void autoNextMissingAudioTriggersOnDemandGenerationWithoutFallback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("this._selectManagedPlayback(requestedChapterId, requestedVoiceKey).then(manifest => {");
        assertThat(transBody).contains("this.managedEngine.play(0);");
    }

    @Test
    @DisplayName("163. Fallback Policy: Definitive failure uses Device TTS only when fallbackToDevice is true and preserves saved preference (MS-04.9H.7D8)")
    void autoNextDefinitiveFailureUsesDeviceTtsOnlyWhenPolicyPermits() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {");
        assertThat(transBody).contains("this.activeEngineType = 'device';");
        assertThat(transBody).contains("this.activeEngine = this.deviceEngine;");
        assertThat(transBody).contains("this.engine = this.deviceEngine;");
        assertThat(transBody).contains("this._setStatusMessage('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');");
        assertThat(transBody).doesNotContain("this.savedVoicePreference = null;");
    }

    @Test
    @DisplayName("164. Fallback Policy: Auto Next never automatically falls back to another Managed voice (MS-04.9H.7D8)")
    void autoNextNeverFallsBackToAnotherManagedVoiceAutomatically() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).doesNotContain("defManaged");
        assertThat(transBody).doesNotContain("mVoices.find");
    }

    @Test
    @DisplayName("165. Settings Fallback Toggle: HTML template, controller DOM, and storage preferences support fallback toggle (MS-04.9H.7D8)")
    void settingsFallbackToggleSupportedInHtmlAndController() throws Exception {
        String chapterHtml = read("src/main/resources/templates/novel/chapter.html");
        assertThat(chapterHtml).contains("id=\"novelNarrationFallbackToggle\"");
        assertThat(chapterHtml).contains("Tự dùng Giọng thiết bị khi Giọng Kiếm Lai không khả dụng");

        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("fallbackToggle: '#novelNarrationFallbackToggle',");
        assertThat(controllerJs).contains("this.fallbackToDevice = typeof config.fallbackToDevice === 'boolean' ? config.fallbackToDevice : false;");
        assertThat(controllerJs).contains("setFallbackToDevice(enabled, persist = true)");
        assertThat(controllerJs).contains("fallbackToDevice: Boolean(this.fallbackToDevice)");
    }

    @Test
    @DisplayName("166. Standby Audio: is not promotable before canplay / readyState >= 3 readiness (MS-04.9H.7D8)")
    void standbyAudioIsNotPromotableBeforeReadiness() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int warmUpStart = managedJs.indexOf("_scheduleAdjacentWarmUp(currentIndex) {");
        int warmUpEnd = managedJs.indexOf("async prepareSegmentPlayback(", warmUpStart);
        String warmUpBody = managedJs.substring(warmUpStart, warmUpEnd);

        assertThat(warmUpBody).contains("isReady: false");
        assertThat(warmUpBody).contains("standbyAudio.addEventListener('canplay', onStandbyReady, { once: true });");
        assertThat(warmUpBody).contains("standbyAudio.addEventListener('canplaythrough', onStandbyReady, { once: true });");
        assertThat(warmUpBody).contains("if (standbyAudio.readyState >= 3) {");
        assertThat(warmUpBody).contains("warmEntry.isReady = true;");
        assertThat(warmUpBody).doesNotContain("loadeddata");
        assertThat(warmUpBody).doesNotContain("readyState >= 2");
    }

    @Test
    @DisplayName("167. Standby Audio: ready matching standby is promoted and unready is safely discarded (MS-04.9H.7D8)")
    void readyMatchingStandbyIsPromoted() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("(this._warmedSegment.isReady === true || (this._warmedSegment.standbyAudio && this._warmedSegment.standbyAudio.readyState >= 3))");
        assertThat(playBody).doesNotContain("readyState >= 2");
        assertThat(playBody).contains("this.audio = promotedAudio;");
        assertThat(playBody).contains("this._cancelPrefetch();");
    }

    @Test
    @DisplayName("168. Standby Audio: stale prefetch sequence cannot mark readiness or regain authority (MS-04.9H.7D8)")
    void stalePrefetchSequenceCannotMarkReadiness() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int warmUpStart = managedJs.indexOf("_scheduleAdjacentWarmUp(currentIndex) {");
        int warmUpEnd = managedJs.indexOf("async prepareSegmentPlayback(", warmUpStart);
        String warmUpBody = managedJs.substring(warmUpStart, warmUpEnd);

        assertThat(warmUpBody).contains("if (prefetchSequence !== this._prefetchSequenceId) {\n                                    return;\n                                }");
        assertThat(warmUpBody).contains("if (this.chapterId !== requestedChapterId || this.getSelectedVoiceKey() !== requestedVoiceKey) {\n                                    return;\n                                }");
    }

    @Test
    @DisplayName("169. Cross-Segment Seek: async authority guards validate exact targetAudio, sequenceId, segment index, and chapter (MS-04.9H.7D8)")
    void crossSegmentSeekValidatesAuthorityGuards() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const targetAudio = this.audio;");
        assertThat(playBody).contains("const targetSequenceId = sequenceId;");
        assertThat(playBody).contains("const targetSegmentIndex = requestedIndex;");
        assertThat(playBody).contains("const isSeekAuthorityValid = () => {");
        assertThat(playBody).contains("targetAudio === this.audio &&");
        assertThat(playBody).contains("targetSequenceId === this._playbackSequenceId &&");
        assertThat(playBody).contains("this.currentSegmentIndex === targetSegmentIndex &&");
        assertThat(playBody).contains("this.chapterId === targetChapterId &&");
        assertThat(playBody).contains("this.getSelectedVoiceKey() === targetVoiceKey;");
    }

    @Test
    @DisplayName("170. Cross-Segment Seek: seekFromStart and seekFromEnd commit exactly once and wait safely for metadata event listeners (MS-04.9H.7D8)")
    void crossSegmentSeekWaitsSafelyForMetadata() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("let seekApplied = false;");
        assertThat(playBody).contains("if (seekApplied || !isSeekAuthorityValid()) {");
        assertThat(playBody).contains("seekApplied = true;");
        assertThat(playBody).contains("if (targetAudio.readyState >= 1) {\n                                    applySeekStart();\n                                } else {\n                                    targetAudio.addEventListener('loadedmetadata', applySeekStart, { once: true });");
        assertThat(playBody).contains("if (targetAudio.readyState >= 1 && Number.isFinite(targetAudio.duration) && targetAudio.duration > 0) {\n                                    applySeekEnd();\n                                } else {\n                                    targetAudio.addEventListener('loadedmetadata', applySeekEnd, { once: true });");
    }

    @Test
    @DisplayName("171. Device Fallback: prepare FAILED/BLOCKED or engine error triggers fallback only when enabled (MS-04.9H.7D8)")
    void prepareFailureTriggersDeviceFallbackWhenEnabled() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");

        int blockedStart = controllerJs.indexOf("_onEngineBlocked(segIndex, seg, engineType, playbackDto) {");
        int blockedEnd = controllerJs.indexOf("_onEngineChunkStart(", blockedStart);
        String blockedBody = controllerJs.substring(blockedStart, blockedEnd);

        assertThat(blockedBody).contains("if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {");
        assertThat(blockedBody).contains("this._fallbackToDeviceTts('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');");

        int errorStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int errorEnd = controllerJs.indexOf("_onEngineVoicesChanged(", errorStart);
        String errorBody = controllerJs.substring(errorStart, errorEnd);

        assertThat(errorBody).contains("if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {");
        assertThat(errorBody).contains("this._fallbackToDeviceTts('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');");
    }

    @Test
    @DisplayName("172. Device Fallback: AbortError and stale cancellation never trigger device fallback (MS-04.9H.7D8)")
    void abortErrorNeverTriggersDeviceFallback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int errorStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int errorEnd = controllerJs.indexOf("_onEngineVoicesChanged(", errorStart);
        String errorBody = controllerJs.substring(errorStart, errorEnd);

        assertThat(errorBody).contains("if (error && (error.name === 'AbortError' || error === 'AbortError')) {\n                return;\n            }");
    }

    @Test
    @DisplayName("173. Device Fallback: Chapter B temporary fallback does NOT prevent Chapter C from retrying preferred Managed voice first (MS-04.9H.7D8)")
    void temporaryFallbackDoesNotPreventNextChapterRetryingManagedVoice() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("let shouldAttemptManaged = Boolean(this.managedEngine && (this.activeEngineType === 'managed' || (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)));");
        assertThat(transBody).contains("if (shouldAttemptManaged) {");
        assertThat(transBody).contains("this.activeEngineType = 'managed';");
    }

    @Test
    @DisplayName("174. Device Fallback: Managed voice options in dropdown remain preserved via _cachedManagedVoices during temporary fallback (MS-04.9H.7D8)")
    void managedVoiceOptionsPreservedDuringTemporaryFallback() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._cachedManagedVoices = [];");
        assertThat(controllerJs).contains("this._cachedManagedVoices && this._cachedManagedVoices.length > 0");

        int fallbackStart = controllerJs.indexOf("_fallbackToDeviceTts(customMessage) {");
        int fallbackEnd = controllerJs.indexOf("_resolveNextChapterUrl() {", fallbackStart);
        String fallbackBody = controllerJs.substring(fallbackStart, fallbackEnd);

        assertThat(fallbackBody).contains("this._populateVoiceDropdown(deviceVoices, mVoices, { skipActivation: true });");
        assertThat(fallbackBody).doesNotContain("setManifest(null)");
    }

    @Test
    @DisplayName("175. Prepared Fast Path: Natural N -> N+1 with valid prepared authority uses fast path without second /prepare (MS-04.9H.7D8)")
    void naturalEndedWithValidPreparedAuthorityUsesFastPathWithoutSecondPrepare() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).contains("if (this._canUsePreparedFastPath(nextIndex)) {\n                    this._playPreparedAdjacentSegment(nextIndex);\n                } else {\n                    this.play(nextIndex);\n                }");
    }

    @Test
    @DisplayName("176. Prepared Fast Path: Authority is single-use and marked consumed immediately upon execution (MS-04.9H.7D8)")
    void preparedAuthorityIsSingleUseAndMarkedConsumed() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int fastPlayStart = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {");
        int fastPlayEnd = managedJs.indexOf("destroy() {", fastPlayStart);
        String fastPlayBody = managedJs.substring(fastPlayStart, fastPlayEnd);

        assertThat(fastPlayBody).contains("const warmed = this._warmedSegment;");
        assertThat(fastPlayBody).contains("this._warmedSegment = null;");
        assertThat(fastPlayBody).contains("warmed.consumed = true;");
    }

    @Test
    @DisplayName("177. Prepared Fast Path: Eligibility requires exact chapter, adjacent index, segmentId, and voiceKey match (MS-04.9H.7D8)")
    void preparedFastPathEligibilityRequiresExactIdentityMatch() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int canFastStart = managedJs.indexOf("_canUsePreparedFastPath(targetIndex) {");
        int canFastEnd = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {", canFastStart);
        String canFastBody = managedJs.substring(canFastStart, canFastEnd);

        assertThat(canFastBody).contains("if (!this._warmedSegment || this._warmedSegment.consumed) {\n                return false;\n            }");
        assertThat(canFastBody).contains("if (warmed.prefetchSequence !== this._prefetchSequenceId) {\n                return false;\n            }");
        assertThat(canFastBody).contains("if (!this.chapterId || warmed.chapterId !== this.chapterId) {\n                return false;\n            }");
        assertThat(canFastBody).contains("targetIndex !== this.currentSegmentIndex + 1");
        assertThat(canFastBody).contains("warmed.segmentId !== targetSegment.segmentId");
        assertThat(canFastBody).contains("warmed.voiceKey !== currentVoiceKey");
        assertThat(canFastBody).contains("warmed.playbackDto.playableNow");
    }

    @Test
    @DisplayName("178. Prepared Fast Path: Stale sequence or mismatch falls back cleanly to canonical play(index) (MS-04.9H.7D8)")
    void staleSequenceOrMismatchFallsBackToCanonicalPlay() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int fastPlayStart = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {");
        int fastPlayEnd = managedJs.indexOf("destroy() {", fastPlayStart);
        String fastPlayBody = managedJs.substring(fastPlayStart, fastPlayEnd);

        assertThat(fastPlayBody).contains("if (!this._canUsePreparedFastPath(nextIndex)) {\n                debugLogNarration('prepared-fast-path-miss'");
        assertThat(fastPlayBody).contains("this.play(nextIndex);\n                return;\n            }");
    }

    @Test
    @DisplayName("179. Canonical Prepare: Manual Next and arbitrary play(index) strictly retain canonical /prepare (MS-04.9H.7D8)")
    void manualNextAndArbitraryPlayRetainCanonicalPrepare() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);

        assertThat(playBody).contains("const playbackDto = await this.prepareSegmentPlayback(");

        int nextStart = managedJs.indexOf("nextSegment() {");
        int nextEnd = managedJs.indexOf("nextChunk() {", nextStart);
        String nextBody = managedJs.substring(nextStart, nextEnd);
        assertThat(nextBody).contains("this.seekToSegment(this.currentSegmentIndex + 1);");

        int seekStart = managedJs.indexOf("seekToSegment(index) {");
        int seekEnd = managedJs.indexOf("seekToChunk(", seekStart);
        String seekBody = managedJs.substring(seekStart, seekEnd);
        assertThat(seekBody).contains("this._cancelPrefetch();");
        assertThat(seekBody).contains("this.play(targetIndex);");
    }

    @Test
    @DisplayName("180. Canonical Prepare: Cross-segment ±5s seeking strictly retains canonical /prepare (MS-04.9H.7D8)")
    void crossSegmentSeekingRetainsCanonicalPrepare() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int seekTimeStart = managedJs.indexOf("seekBySeconds(deltaSeconds) {");
        int seekTimeEnd = managedJs.indexOf("nextSegment() {", seekTimeStart);
        String seekTimeBody = managedJs.substring(seekTimeStart, seekTimeEnd);

        assertThat(seekTimeBody).contains("this.play(prevIndex, { seekFromEnd: carry, paused: wasPaused });");
        assertThat(seekTimeBody).contains("this.play(nextIndex, { seekFromStart: overflow, paused: wasPaused });");
    }

    @Test
    @DisplayName("181. Prepared Authority: Cancel, stop, seek, and setManifest cleanly invalidate prepared authority (MS-04.9H.7D8)")
    void preparedAuthorityInvalidatedOnInterruption() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int cancelPrefetchStart = managedJs.indexOf("_cancelPrefetch() {");
        int cancelPrefetchEnd = managedJs.indexOf("_scheduleAdjacentWarmUp(", cancelPrefetchStart);
        String cancelPrefetchBody = managedJs.substring(cancelPrefetchStart, cancelPrefetchEnd);

        assertThat(cancelPrefetchBody).contains("this._prefetchSequenceId++;");
        assertThat(cancelPrefetchBody).contains("this._warmedSegment = null;");

        int setManifestStart = managedJs.indexOf("setManifest(manifest, startSegmentIndex = 0) {");
        int setManifestEnd = managedJs.indexOf("async loadManifest(chapterId, voiceKey = null) {", setManifestStart);
        assertThat(managedJs.substring(setManifestStart, setManifestEnd)).contains("this.stop();");
    }

    @Test
    @DisplayName("182. Prepared Fast Path: Standby audio is promoted, listeners rebound, and N+2 warm-up scheduled immediately (MS-04.9H.7D8)")
    void preparedFastPathPromotesStandbyAndSchedulesNextWarmUp() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int fastPlayStart = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {");
        int fastPlayEnd = managedJs.indexOf("destroy() {", fastPlayStart);
        String fastPlayBody = managedJs.substring(fastPlayStart, fastPlayEnd);

        assertThat(fastPlayBody).contains("const promotedAudio = warmed.standbyAudio || warmed.preloadAudio;");
        assertThat(fastPlayBody).contains("this._unbindAudioListeners();");
        assertThat(fastPlayBody).contains("this.audio = promotedAudio;");
        assertThat(fastPlayBody).contains("this._bindAudioListeners();");
        assertThat(fastPlayBody).contains("this.audio.playbackRate = this.rate;");
        assertThat(fastPlayBody).contains("this.audio.volume = this.volume;");
        assertThat(fastPlayBody).contains("this._transitionState(ManagedEngineState.PLAYING);");
        assertThat(fastPlayBody).contains("this._notifySegmentStart(nextIndex, segment, playbackDto);");
        assertThat(fastPlayBody).contains("const playPromise = this.audio.play();");
        assertThat(fastPlayBody).contains("this._scheduleAdjacentWarmUp(nextIndex);");
    }

    @Test
    @DisplayName("182a. Prepared Fast Path: Natural handoff retires previous audio without pausing or clearing source at the audible boundary (MS-04.9H.7D8)")
    void preparedFastPathDoesNotTearDownPreviousAudioDuringHandoff() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int fastPlayStart = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {");
        int fastPlayEnd = managedJs.indexOf("destroy() {", fastPlayStart);
        String fastPlayBody = managedJs.substring(fastPlayStart, fastPlayEnd);

        assertThat(fastPlayBody).contains("const previousAudio = this.audio;");
        assertThat(fastPlayBody).contains("this.audio = promotedAudio;");
        assertThat(fastPlayBody).contains("this._retiredAudio = previousAudio;");
        assertThat(fastPlayBody).doesNotContain("this.audio.pause();\n                    this.audio.src = '';");
        assertThat(fastPlayBody).doesNotContain("previousAudio.pause()");
        assertThat(fastPlayBody).doesNotContain("previousAudio.src = ''");
    }

    @Test
    @DisplayName("182b. Prepared Fast Path: Pristine promoted standby is not redundantly seeked to zero (MS-04.9H.7D8)")
    void preparedFastPathDoesNotSeekPristineStandbyToZero() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int fastPlayStart = managedJs.indexOf("_playPreparedAdjacentSegment(nextIndex) {");
        int fastPlayEnd = managedJs.indexOf("destroy() {", fastPlayStart);
        String fastPlayBody = managedJs.substring(fastPlayStart, fastPlayEnd);

        assertThat(fastPlayBody).doesNotContain("currentTime = 0");
        assertThat(fastPlayBody).contains("this.audio.playbackRate = this.rate;");
        assertThat(fastPlayBody).contains("this.audio.volume = this.volume;");
        assertThat(fastPlayBody).contains("const playPromise = this.audio.play();");
    }

    @Test
    @DisplayName("182c. Canonical and manual playback retain explicit position reset while retired audio is released off the fast boundary (MS-04.9H.7D8)")
    void canonicalPlaybackStillResetsPositionAndLifecycleReleasesRetiredAudio() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int playStart = managedJs.indexOf("async play(startSegmentIndex");
        int playEnd = managedJs.indexOf("_playCurrentSegment(", playStart);
        String playBody = managedJs.substring(playStart, playEnd);
        int directPlayStart = managedJs.indexOf("_playCurrentSegment(seekOptions = null) {");
        int directPlayEnd = managedJs.indexOf("pause() {", directPlayStart);
        String directPlayBody = managedJs.substring(directPlayStart, directPlayEnd);

        assertThat(playBody).contains("this.audio.currentTime = 0;");
        assertThat(directPlayBody).contains("this.audio.currentTime = 0;");
        assertThat(managedJs).contains("this._retiredAudio = null;");
        assertThat(managedJs).contains("_releaseRetiredAudioResource()");
        assertThat(managedJs).contains("this._releaseRetiredAudioResource();\n            this._playbackSequenceId++;");
    }

    @Test
    @DisplayName("183. Diagnostics: Emits prepared-fast-path-hit, miss, and authority-consumed events (MS-04.9H.7D8)")
    void diagnosticsEmitPreparedFastPathEvents() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("debugLogNarration('prepared-fast-path-hit',");
        assertThat(managedJs).contains("debugLogNarration('prepared-fast-path-miss',");
        assertThat(managedJs).contains("debugLogNarration('prepared-authority-consumed',");
    }

    @Test
    @DisplayName("184. Natural Ended: Last chapter segment finishes cleanly into STOPPED state without fast path error (MS-04.9H.7D8)")
    void naturalEndedLastSegmentFinishesIntoStoppedState() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int endedStart = managedJs.indexOf("_handleAudioEnded() {");
        int endedEnd = managedJs.indexOf("_handleAudioError(event) {", endedStart);
        String endedBody = managedJs.substring(endedStart, endedEnd);

        assertThat(endedBody).contains("const nextIndex = this.currentSegmentIndex + 1;");
        assertThat(endedBody).contains("if (nextIndex < this.segments.length) {");
        assertThat(endedBody).contains("this._transitionState(ManagedEngineState.STOPPED);");
        assertThat(endedBody).contains("this.options.onChapterEnd();");
    }

    @Test
    void h9gLoadsPublicChapterPlaybackBeforeLegacyFallback() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("'/narration/playback?voiceKey=' + encodeURIComponent(voiceKey)",
                "method: 'GET', cache: 'no-store', signal: controller.signal");
        String selection = controllerMethod("async _selectManagedPlayback(", "_onChapterCueChange(index, cue) {");
        assertThat(selection.indexOf("await this.chapterEngine.loadPlayback(chapterId, voiceKey)"))
                .isLessThan(selection.indexOf("await this.managedEngine.loadManifest(chapterId, voiceKey)"));
        assertThat(selection).contains("manifest.selectedVoice.voiceKey !== voiceKey", "assertCurrent();");
        assertThat(selection).doesNotContain(".play(", "/prepare", "buildPlayback");
    }

    @Test
    void h9gOnlyReadyPlayableCurrentOrStaleVoiceSelectsChapterAudio() throws Exception {
        String engine = chapterEngine();
        String eligibility = engine.substring(engine.indexOf("function isChapterPlayable"), engine.indexOf("class ChapterAudioEngine"));
        assertThat(eligibility).contains("metadata.availability === 'READY'", "metadata.playable === true",
                "metadata.freshness === 'CURRENT' || metadata.freshness === 'STALE_VOICE'");
        assertThat(eligibility).doesNotContain("=== 'STALE_CONTENT'", "=== 'MISSING'");
        assertThat(engine).contains("if (!isChapterPlayable(metadata)) {", "return null;");
    }

    @Test
    void h9gCueTransitionsOnlyObservePersistedIntervals() throws Exception {
        String engine = chapterEngine();
        String synchronization = engine.substring(engine.indexOf("_syncCue() {"), engine.indexOf("async play(index) {"));
        assertThat(engine).contains("metadata.cues.slice().sort((a, b) => a.cueOrdinal - b.cueOrdinal)",
                "listen('timeupdate', () => this._syncCue())", "audio.src = metadata.audioUrl");
        assertThat(synchronization).contains("millis >= cue.startMillis && millis < cue.endMillis",
                "this._activeCueIndex = index", "this.cues[index] || null");
        assertThat(synchronization).doesNotContain(".play(", ".pause(", ".load(", ".src", "fetch", "new Audio");
        assertThat(engine).doesNotContain("/prepare", "Range", "arrayBuffer", "startMillis =", "endMillis =");
    }

    @Test
    void h9gCueHooksClearHighlightInGapsWithoutInventingTextMapping() throws Exception {
        String hooks = controllerMethod("_onChapterCueChange(index, cue) {", "async _offerLegacyChapterFallback() {");
        assertThat(hooks).contains("this._clearHighlight()", "if (!cue)",
                "this._updateChapterProgressDisplay(this.chapterEngine.getProgress())", "this._onEngineChunkStart(index,",
                "this._resolveChapterCueElements(cue.segmentId)", "_syncChapterHighlight(ensureVisible = false)");
        assertThat(hooks).doesNotContain("parseChapterBody", "this.chunks[cue.segmentIndex]");
    }

    @Test
    void h9gChapterTimelineUsesAudioPositionAndEmitsProgressWithinCues() throws Exception {
        String engine = chapterEngine();
        String progress = engine.substring(engine.indexOf("getProgress() {"), engine.indexOf("_transitionState(state) {"));
        String synchronization = engine.substring(engine.indexOf("_syncCue() {"), engine.indexOf("async play(index) {"));
        assertThat(progress).contains("this._time()", "this.audio.duration", "currentTimeSeconds / durationSeconds");
        assertThat(synchronization).contains("this.options.onProgress(this.getProgress())")
                .doesNotContain("index === this._activeCueIndex) return");
        assertThat(engine).contains("listen('timeupdate', () => this._syncCue())");

        String controller = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controller).contains("onProgress: progress => this._onChapterProgress(progress)",
                "this._updateChapterProgressDisplay(progress)", "const percent = ratio * 100");
    }

    @Test
    void h9h1ChapterProgressControlSeeksAbsoluteRatioWithExplicitEndDetection() throws Exception {
        String engine = chapterEngine();
        String seek = engine.substring(engine.indexOf("_seekTime(seconds) {"), engine.indexOf("_previousIndex() {"));
        assertThat(seek).contains("seekToRatio(ratio)", "this._seekTime(Math.max(0, Math.min(1, ratio)) * duration)")
                .contains(".pause(", "_transitionState('STOPPED')");
        String click = controllerMethod("_handleProgressBarClick(event) {", "_handleProgressBarKeydown(event) {");
        assertThat(click).contains("this.chapterEngine.seekToRatio(ratio)", "this._syncNavigationAndProgress()", "return;");
    }

    @Test
    void h9gSegmentNavigationUsesCueStartsAndBoundaries() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("this._seekTime(cue.startMillis / 1000)",
                "return this._activeCueIndex - 1", "return this._activeCueIndex + 1",
                "canPrevious() { return this._previousIndex() >= 0; }",
                "index >= 0 && index < this.cues.length",
                "previousChunk() { if (this.canPrevious())", "nextChunk() { if (this.canNext())");
    }

    @Test
    void h9h1SecondsSeekClampsAndDetectsExplicitEnd() throws Exception {
        String engine = chapterEngine();
        String seek = engine.substring(engine.indexOf("_seekTime(seconds) {"), engine.indexOf("_previousIndex() {"));
        assertThat(seek).contains("const duration = this._duration()", "Math.max(0, Math.min(duration, seconds))", "this.audio.currentTime = target",
                "this._syncCue()", "this._seekTime(this._time() + deltaSeconds)");
        assertThat(seek).doesNotContain(".play(", ".load(", ".src", "fetch");
        assertThat(engine).contains("this.audio.duration", "this.metadata.durationMillis / 1000");
        String controls = controllerMethod("_handleRewind() {", "_handleProgressBarClick(event) {");
        assertThat(controls).contains("this.activeEngineType === 'managed'", "this.engine.seekBySeconds(-5)", "this.engine.seekBySeconds(5)");
    }

    @Test
    void h9gSelectionAndAudioCallbacksRejectStaleAuthority() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("generation !== this._generation || controller.signal.aborted",
                "generation === this._generation && this.audio === audio", "if (ownsAudio()) callback()",
                "playId !== this._playId", "this._fetchController.abort()",
                "audio.removeEventListener(name, callback)", "audio.removeAttribute('src')");
        String selection = controllerMethod("async _selectManagedPlayback(", "_onChapterCueChange(index, cue) {");
        assertThat(selection).contains("this.chapterId === chapterId", "selection === this._chapterSelectionId",
                "voiceSequence === this._voiceSelectionSequenceId", "this.managedEngine.stop()", "this.managedEngine.cancel()");
        for (String lifecycle : new String[]{"_handleVoiceChange() {", "_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent) {"}) {
            String controller = read("src/main/resources/static/js/novel/narration-controller.js");
            assertThat(controller.substring(controller.indexOf(lifecycle), controller.indexOf(lifecycle) + 140))
                    .contains("this._invalidateChapterPlayback()");
        }
        assertThat(controllerMethod("_handleUnload() {", "_handleStorageEvent(event) {"))
                .contains("this._invalidateChapterPlayback()");
    }

    @Test
    void h9gExplicitPauseDefeatsLatePlayAndEndedWithoutNaturalPauseRace() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("if (audio.ended) return;",
                "if (!audio.ended || !this._wantsPlay || this.state !== 'PLAYING') return;",
                "if (!this._wantsPlay) audio.pause()");
        String pause = engine.substring(engine.indexOf("pause() {"), engine.indexOf("resume() {"));
        assertThat(pause.indexOf("this._wantsPlay = false")).isLessThan(pause.indexOf("this.audio.pause()"));
        assertThat(pause).contains("++this._playId", "this._transitionState('PAUSED')");
    }

    @Test
    void h9gAudioFailureOffersSameVoiceLegacyPlaybackWithoutAutoplay() throws Exception {
        String fallback = controllerMethod("async _offerLegacyChapterFallback() {", "setFollowMode(enabled, persist = true) {");
        assertThat(fallback).contains("this.chapterEngine.getSelectedVoiceKey()",
                "await this._selectManagedPlayback(chapterId, voiceKey, true)", "error.name !== 'AbortError'");
        assertThat(fallback).doesNotContain(".play(", "_fallbackToDeviceTts", "defaultVoice", "[0]");
    }

    @Test
    @DisplayName("H.9H1: No ChapterAudioEngine early-return before Auto Next")
    void h9h1NoChapterAudioEngineEarlyReturnBeforeAutoNext() throws Exception {
        String ended = controllerMethod("_onEngineChapterEnd(engineType) {", "async _transitionToNextChapter(");
        assertThat(ended).doesNotContain("if (this.engine === this.chapterEngine) return;");
    }

    @Test
    @DisplayName("H.9H1: ChapterEngine voice key captured before playback invalidation and passed to transition")
    void h9h1ChapterEngineVoiceKeyCapturedAndPassedToTransition() throws Exception {
        String ended = controllerMethod("_onEngineChapterEnd(engineType) {", "async _transitionToNextChapter(");
        assertThat(ended).contains("continuationIntent = { mode: 'managed', voiceKey: this.chapterEngine.getSelectedVoiceKey() };");
        assertThat(ended).contains("this._transitionToNextChapter(nextUrl, continuationIntent);");
    }

    @Test
    @DisplayName("H.9H1: Direct chapter timeline seek cancels pending Auto Next")
    void h9h1DirectChapterTimelineSeekCancelsPendingAutoNext() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String progressClick = controllerMethod("_handleProgressBarClick(event) {", "_handleProgressBarKeydown(event) {");
        String progressKeydown = controllerMethod("_handleProgressBarKeydown(event) {", "_handleVoiceChange() {");

        assertThat(progressClick).contains("this._cancelPendingAutoNext();\n                this.isCompleted = false;\n                this.chapterEngine.seekToRatio(");
        assertThat(progressKeydown).contains("this._cancelPendingAutoNext();\n                    this.isCompleted = false;\n                    this.chapterEngine.seekBySeconds(");
        assertThat(progressKeydown).contains("this._cancelPendingAutoNext();\n                    this.isCompleted = false;\n                    this.chapterEngine.seekToRatio(");
    }

    @Test
    @DisplayName("H.9H1: Next-chapter ChapterAudioEngine is actually started")
    void h9h1NextChapterChapterAudioEngineIsStarted() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("} else if (this.engine === this.chapterEngine) {\n                            this.chapterEngine.play(0);\n                        }");
    }

    @Test
    @DisplayName("H.9H1: Existing legacy Managed and Device paths remain intact")
    void h9h1ExistingLegacyPathsRemainIntact() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("this.managedEngine.play(0);");
        assertThat(applyTransition).contains("this.deviceEngine.play(0);");
    }

    private String chapterEngine() throws Exception {
        return read("src/main/resources/static/js/novel/chapter-audio-engine.js");
    }

    @Test
    void h9gFollowModeUsesExactTokenMembershipAndOneWeightedBlock() throws Exception {
        String hooks = controllerMethod("_resolveChapterCueElements(segmentId) {", "async _offerLegacyChapterFallback() {");
        assertThat(hooks).contains("querySelectorAll('[data-narration-segment-ids]')",
                "split(/\\s+/).includes(segmentId)", "this._normalizedVisibleTextLength(element)",
                "localMillis / cueDuration", "ratio * totalWeight", "targetWeight < cumulativeWeight",
                "selectedElement !== this.activeHighlightedElement", "new Set(selectedElement ? [selectedElement] : [])",
                "selectedElement && (changed || ensureVisible) && !this.isSettingsOpen()");
        assertThat(hooks).contains("element.innerText", "element.textContent")
                .doesNotContain("segmentIndex", "parseChapterBody", "restoreVisibility");
    }

    @Test
    void h9gFollowModeRestoresPausedCueOnToggleSettingsAndNavigation() throws Exception {
        assertThat(controllerMethod("setFollowMode(enabled, persist = true) {", "_handleFollowChange() {"))
                .contains("this._syncChapterHighlight(this.followMode)");
        assertThat(controllerMethod("closeSettings() {", "_handleSettingsTriggerClick(event) {"))
                .contains("this._syncChapterHighlight(true)");
        assertThat(controllerMethod("_syncNavigationAndProgress() {", "async _handleVoiceChange() {"))
                .contains("this._syncChapterHighlight()");
        assertThat(controllerMethod("_syncChapterHighlight(ensureVisible = false) {", "async _offerLegacyChapterFallback() {"))
                .contains("this.chapterEngine.getCurrentChunk()", "!this.followMode", "!cue", "this._clearHighlight()",
                        "state !== 'PLAYING' && state !== 'PAUSED'", "changed || ensureVisible")
                .doesNotContain("getState() === 'PLAYING'");
    }

    @Test
    void h9gChapterStateTransitionSynchronizesAlreadyCurrentCueWithoutPlaybackSideEffects() throws Exception {
        String state = controllerMethod("_onEngineStateChange(newState, prevState, engineType) {", "_onEngineBlocked(segIndex, seg, engineType, playbackDto) {");
        assertThat(state).contains("if (this.chapterEngine && this.engine === this.chapterEngine) this._syncChapterHighlight();");
        assertThat(state).doesNotContain(".play(", ".load(", "/prepare", "fetch(");
        String highlight = controllerMethod("_syncChapterHighlight(ensureVisible = false) {", "async _offerLegacyChapterFallback() {");
        assertThat(highlight.indexOf("state !== 'PLAYING' && state !== 'PAUSED'"))
                .isLessThan(highlight.indexOf("this._resolveChapterCueElements(cue.segmentId)"));
    }

    @Test
    void h9gChapterHighlightCleanupCoversResetFallbackAndEnd() throws Exception {
        assertThat(controllerMethod("_clearHighlight() {", "_getViewportClearance() {"))
                .contains("this.activeChapterHighlightedElements.clear()", "this.activeNarrationSegmentId = null");
        assertThat(controllerMethod("_invalidateChapterPlayback() {", "async _selectManagedPlayback("))
                .contains("this._clearHighlight()");
        assertThat(controllerMethod("_fallbackToDeviceTts(customMessage) {", "_resolveNextChapterUrl() {"))
                .contains("this._invalidateChapterPlayback()");
        assertThat(controllerMethod("_onEngineChapterEnd(engineType) {", "async _transitionToNextChapter(nextUrl, continuationIntent) {"))
                .contains("this._clearHighlight()");
    }

    private String controllerMethod(String start, String end) throws Exception {
        String controller = read("src/main/resources/static/js/novel/narration-controller.js");
        int from = controller.indexOf(start);
        return controller.substring(from, controller.indexOf(end, from));
    }

    private String managedMethod(String start, String end) throws Exception {
        String managed = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        int from = managed.indexOf(start);
        return managed.substring(from, managed.indexOf(end, from));
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}






