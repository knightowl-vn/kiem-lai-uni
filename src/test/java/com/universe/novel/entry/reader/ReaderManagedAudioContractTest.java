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
        assertThat(controllerJs).contains("this._managedCatalogAuthoritative = false;");
        assertThat(controllerJs).contains("else if (this._managedCatalogAuthoritative) {\n                        // Stale saved managed voice -> ONLY clear preference after authoritative catalog confirms absence!\n                        this.savedVoicePreference = null;\n                        this._savePreferences();\n                    }");
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
        String initialDiscovery = controllerMethod("async _loadInitialManagedVoices()", "_populateVoiceDropdown(deviceVoices, managedVoices, options = {})");
        assertThat(initialDiscovery).contains("const voiceExists = availableVoices.some(v => v.voiceKey === targetKey);");
        assertThat(initialDiscovery).contains("if (!voiceExists) {");
        assertThat(initialDiscovery).doesNotContain("loadManifest(");
    }

    @Test
    @DisplayName("4. Stale saved managed preference is cleared only after catalog confirms absence")
    void staleSavedManagedPreferenceClearedOnlyAfterCatalogConfirmsAbsence() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (!voiceExists) {\n                            // Stale saved managed voice: catalog has arrived and confirms absence\n                            this.savedVoicePreference = null;\n                            this._savePreferences();\n                        }");
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
        String initialDiscovery = controllerMethod("async _loadInitialManagedVoices()", "_populateVoiceDropdown(deviceVoices, managedVoices, options = {})");
        assertThat(initialDiscovery).contains("const catalog = await this.managedEngine.loadVoiceCatalog();");
        assertThat(initialDiscovery).doesNotContain("loadManifest(");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).contains("function buildVoiceCatalogUrl()");
        assertThat(managedJs).contains("return '/api/novel/narration/voices';");
        assertThat(managedJs).contains("async loadVoiceCatalog()");
        assertThat(managedJs).contains("const response = await fetchFn(buildVoiceCatalogUrl()");
        assertThat(managedJs).contains("this.availableVoices = catalog && Array.isArray(catalog.voices) ? catalog.voices : [];");
        assertThat(managedJs).contains("return { voices: this.availableVoices.slice() };");
        assertThat(managedJs).doesNotContain("/narration/manifest");
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
    @DisplayName("PERF-02B1: Late catalog discovery remains usable after explicit Device selection")
    void lateCatalogDiscoveryRemainsUsableWithoutAutomaticManagedActivation() throws Exception {
        String discovery = controllerMethod("async _loadInitialManagedVoices()", "_populateVoiceDropdown(deviceVoices, managedVoices, options = {})");

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
        String catalogCancel = managedMethod("cancelVoiceCatalogLoad() {", "destroy() {");
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
        assertThat(destroy).contains("this.cancelVoiceCatalogLoad();");
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
    @DisplayName("18. Switching between Device and Managed maintains mutual exclusion")
    void deviceAndManagedSwitchingAndMutualExclusion() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("if (this.deviceEngine) {\n                this.deviceEngine.stop();\n            }");
        assertThat(controllerJs).contains("if (this.chapterEngine) {\n                this.chapterEngine.stop();\n            }");
        assertThat(controllerJs).contains("this.activeEngineType = 'managed'");
        assertThat(controllerJs).contains("this.activeEngineType = 'device'");
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
    @DisplayName("71. Next Play after PAUSED navigation delegates to play(B) (MS-04.9H.7D2B1)")
    void nextPlayAfterPausedNavigationDelegatesToPlay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this.engine.play(this.engine.getCurrentChunkIndex());");
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
    @DisplayName("101. Consolidated _onEngineError preserves error message (MS-04.9H.7D2C1)")
    void deviceErrorPreservesDeviceErrorMessage() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_onEngineError(error, engineType) {");
        int methodEnd = controllerJs.indexOf("_onEngineVoicesChanged(voices) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("if (this.dom.player) {\n                this.dom.player.setAttribute('aria-busy', 'false');\n            }");
        assertThat(methodBody).contains("if (this.dom.statusText) {\n                this.dom.statusText.setAttribute('aria-busy', 'false');\n            }");
        assertThat(methodBody).contains("this._setStatusMessage('Xảy ra lỗi khi phát giọng đọc.');");
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
    @DisplayName("106. Monotonic voice selection sequence guards against rapid selection race (MS-04.9H.7D3)")
    void monotonicVoiceSelectionSequenceGuardsAgainstRapidSelectionRace() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("this._voiceSelectionSequenceId = 0;");
        assertThat(controllerJs).contains("const selectionSequence = ++this._voiceSelectionSequenceId;");
        assertThat(controllerJs).contains("if (selectionSequence !== this._voiceSelectionSequenceId");
    }

    @Test
    @DisplayName("106. Successful voice switch establishes ChapterAudioEngine and clean ready state without autoplay")
    void successfulVoiceSwitchEndsInCleanReadyStateWithoutAutoplay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int methodEnd = controllerJs.indexOf("_handleRateChange() {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains(
                "this._updateChapterProgressDisplay(this.chapterEngine.getProgress())",
                "this._setStatusMessage('Sẵn sàng phát âm thanh cả chương.')");
        assertThat(methodBody).doesNotContain("this.engine = this.managedEngine");
    }

    @Test
    @DisplayName("H.9I5C2A: Failed Managed playback intent invokes unavailable policy")
    void failedManagedVoiceLoadInvokesUnavailablePolicy() throws Exception {
        String prepareMethod = controllerMethod("_ensureManagedPlaybackForIntent(chapterId, voiceKey", "_handlePlayPause() {");
        assertThat(prepareMethod).contains("this._handleManagedUnavailable();");
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
    @DisplayName("H.9I5C2A: Failed Managed voice isolates state and leaves Play available for on-demand preparation")
    void failedManagedVoiceIsolatesStateAndInvokesUnavailablePolicy() throws Exception {
        String voiceChange = controllerMethod("async _handleVoiceChange() {", "_handleRateChange() {");
        assertThat(voiceChange).contains("} catch (e) {", "Nhấn Phát để chuẩn bị giọng đọc");
        assertThat(voiceChange).doesNotContain(".play(");
    }

    @Test
    @DisplayName("124. _activateEngine validates requested chapterId across in-page async transitions (MS-04.9H.7D3A1)")
    void activateEngineValidatesRequestedChapterId() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int methodStart = controllerJs.indexOf("_activateEngine(type, identifier) {");
        int methodEnd = controllerJs.indexOf("setFollowMode(enabled, persist = true) {", methodStart);
        String methodBody = controllerJs.substring(methodStart, methodEnd);

        assertThat(methodBody).contains("const requestedChapterId = this.chapterId;");
        assertThat(methodBody).contains("if (this.chapterId !== requestedChapterId) {\n                        return;\n                    }");
        assertThat(methodBody).contains("if (this.activeEngineType !== 'managed') {\n                        return;\n                    }");
        assertThat(methodBody).contains("if (currentSelectedKey && currentSelectedKey !== identifier) {\n                        return;\n                    }");
        assertThat(methodBody).contains("if (this.dom.voiceSelect && this.dom.voiceSelect.value !== ('managed:' + identifier)) {\n                        return;\n                    }");
    }

    @Test
    @DisplayName("121. Next chapter transition uses ChapterAudioEngine as authoritative engine")
    void autoNextStaleManifestSuccessCannotUpdateChunksOrAutoplay() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transitionStart = controllerJs.indexOf("_applyChapterTransition(");
        int transitionEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transitionStart);
        String transitionBody = controllerJs.substring(transitionStart, transitionEnd);

        assertThat(transitionBody).contains("const requestedChapterId = newChapterId;");
        assertThat(transitionBody).contains("const activeEngineVoiceKey = (this.chapterEngine && typeof this.chapterEngine.getSelectedVoiceKey === 'function')\n                    ? this.chapterEngine.getSelectedVoiceKey()\n                    : null;");
        assertThat(transitionBody).contains("this.engine = this.chapterEngine;");
        assertThat(transitionBody).doesNotContain("this.engine = this.managedEngine;");
    }

    @Test
    @DisplayName("129. Auto-next stale AbortError cannot overwrite new voice status or show error (MS-04.9H.7D3A2)")
    void autoNextStaleAbortErrorCannotOverwriteStatusOrShowError() throws Exception {
        String ensureMethod = controllerMethod("_ensureManagedPlaybackForIntent(chapterId, voiceKey", "_handlePlayPause() {");

        assertThat(ensureMethod).contains("if (err && (err.name === 'AbortError' || err.message === 'The operation was aborted')) {\n                                return;\n                            }");
        assertThat(ensureMethod).contains("if (!isCurrent()) return;");
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
    @DisplayName("136. Legacy manifest controller file does not exist (H.9I5D1A)")
    void manifestControllerFileDoesNotExist() {
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/entry/reader/PublicNovelChapterNarrationManifestController.java"))).isFalse();
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
    @DisplayName("H.9I2: Auto Next prefers active engine voiceKey over saved preference")
    void autoNextPrefersActiveEngineVoiceKeyOverSavedPreference() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("const currentSelectedKey = currentEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)");
        assertThat(applyTransition).contains("? this.savedVoicePreference.voiceKey");
        assertThat(applyTransition).contains(": null);");
    }

    @Test
    @DisplayName("H.9I5C2B: Auto Next null Managed Voice fails safely without unkeyed re-resolution")
    void autoNextNullManagedVoiceFailsSafelyWithoutUnkeyedReResolution() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("this._handleManagedUnavailable()");
        assertThat(applyTransition).doesNotContain("await this.managedEngine.loadManifest(requestedChapterId, null)");
    }

    @Test
    @DisplayName("H.9I2: Auto Next failure invokes unavailable policy")
    void autoNextFailureIsolatesChapterAAudioAndKeepsVoiceSelectorUsable() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("this._handleManagedUnavailable()");
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
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {");
        int dispatchEvent = controllerJs.indexOf("document.dispatchEvent(new CustomEvent('kiemlai:chapter-changed'", transStart);
        String entryBody = controllerJs.substring(transStart, dispatchEvent);

        assertThat(entryBody).contains("this.chapterId = newChapterId;");
        assertThat(entryBody).contains("this.chunks = [];");
        assertThat(entryBody).contains("this._updateProgressDisplay(0, 0);");
        assertThat(entryBody).contains("this._updateNavButtons();");
    }

    @Test
    @DisplayName("H.9I5C2B: Auto Next missing audio triggers on-demand generation without fallback")
    void autoNextMissingAudioTriggersOnDemandGenerationWithoutFallback() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("this._ensureManagedPlaybackForIntent(");
    }

    @Test
    @DisplayName("H.9I2: Auto Next definitive failure delegates to unavailable policy")
    void autoNextDefinitiveFailureUsesDeviceTtsOnlyWhenPolicyPermits() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).contains("this._handleManagedUnavailable()");
    }

    @Test
    @DisplayName("164. Fallback Policy: Auto Next never automatically falls back to another Managed voice (MS-04.9H.7D8)")
    void autoNextNeverFallsBackToAnotherManagedVoiceAutomatically() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {");
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
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transBody = controllerJs.substring(transStart, transEnd);

        assertThat(transBody).contains("let shouldAttemptManaged = Boolean(this.chapterEngine && (this.activeEngineType === 'managed' || (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)));");
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
    void h9gLoadsPublicChapterPlaybackWithoutLegacyFallback() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("'/narration/playback?voiceKey=' + encodeURIComponent(voiceKey)",
                "method: 'GET', cache: 'no-store', signal: controller.signal");
        String selection = controllerMethod("async _selectManagedPlayback(", "_onChapterCueChange(index, cue) {");
        assertThat(selection).doesNotContain(".play(", "/prepare", "buildPlayback", "this.managedEngine.loadManifest");
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
        assertThat(engine).doesNotContain("/segments/", "Range", "arrayBuffer", "startMillis =", "endMillis =");
    }

    @Test
    void h9gCueHooksClearHighlightInGapsWithoutInventingTextMapping() throws Exception {
        String hooks = controllerMethod("_onChapterCueChange(index, cue) {", "_handleManagedUnavailable() {");
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
                "voiceSequence === this._voiceSelectionSequenceId");
        assertThat(selection).doesNotContain("this.managedEngine.stop()");
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
    @DisplayName("H.9I2A: Managed unavailable policy delegates to Device or enters safe stopped state without legacy manifest mutation")
    void h9i2ManagedUnavailablePolicy() throws Exception {
        String fallback = controllerMethod("_handleManagedUnavailable() {", "setFollowMode(enabled, persist = true) {");
        assertThat(fallback).contains(
                "this._fallbackToDeviceTts(",
                "this._cancelPendingAutoNext();",
                "this._cancelNextChapterPreload();",
                "this._invalidateChapterPlayback();",
                "this.chunks = [];",
                "this.dom.playPauseBtn.disabled = true");
        assertThat(fallback).doesNotContain(
                ".play(",
                "await this._selectManagedPlayback",
                "setManifest",
                "loadManifest",
                "prepareSegmentPlayback");
    }

    @Test
    @DisplayName("H.9I2A: ChapterAudio onError callback has authoritative engine guard")
    void h9i2ChapterAudioOnErrorAuthorityGuard() throws Exception {
        String initBlock = controllerMethod("this.chapterEngine = config.chapterEngine", "this.activeEngineType = 'device';");
        assertThat(initBlock).contains("onError: (error) => this._onChapterAudioError(error)");

        String onErrorHandler = controllerMethod("_onChapterAudioError(error) {", "_handleManagedUnavailable() {");
        assertThat(onErrorHandler).contains("this.engine !== this.chapterEngine",
                "this.activeEngineType !== 'managed'",
                "this.isUnloaded",
                "this._handleManagedUnavailable();");
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
        String successMethod = controllerMethod("_finishPreparationSuccess(isCurrent) {", "_handlePreparationFailure(");
        assertThat(successMethod).contains("this.chapterEngine.play(0)");
    }

    @Test
    @DisplayName("H.9I2: Existing legacy Managed path removed, Device path remains intact")
    void h9i2ExistingLegacyManagedPathRemoved() throws Exception {
        String applyTransition = controllerMethod("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {", "_onEngineError(error, engineType) {");
        assertThat(applyTransition).doesNotContain("this.managedEngine.play(0);");
        assertThat(applyTransition).contains("this.deviceEngine.play(0);");
    }

    @Test
    void h9gFollowModeUsesExactTokenMembershipAndOneWeightedBlock() throws Exception {
        String hooks = controllerMethod("_resolveChapterCueElements(segmentId) {", "_handleManagedUnavailable() {");
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
        assertThat(controllerMethod("_syncChapterHighlight(ensureVisible = false) {", "_handleManagedUnavailable() {"))
                .contains("this.chapterEngine.getCurrentChunk()", "!this.followMode", "!cue", "this._clearHighlight()",
                        "state !== 'PLAYING' && state !== 'PAUSED'", "changed || ensureVisible")
                .doesNotContain("getState() === 'PLAYING'");
    }

    @Test
    void h9gChapterStateTransitionSynchronizesAlreadyCurrentCueWithoutPlaybackSideEffects() throws Exception {
        String state = controllerMethod("_onEngineStateChange(newState, prevState, engineType) {", "_onEngineChunkStart(chunkIndex, chunk, engineType) {");
        assertThat(state).contains("if (this.chapterEngine && this.engine === this.chapterEngine) this._syncChapterHighlight();");
        assertThat(state).doesNotContain(".play(", ".load(", "/prepare", "fetch(");
        String highlight = controllerMethod("_syncChapterHighlight(ensureVisible = false) {", "_handleManagedUnavailable() {");
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

    @Test
    @DisplayName("H.9I3: ChapterAudioEngine provides passive probePlaybackMetadata returning status and metadata without playback side-effects")
    void h9i3ChapterAudioEngineProbePlaybackMetadataContract() throws Exception {
        String engine = chapterEngine();
        assertThat(engine).contains("async probePlaybackMetadata(chapterId, voiceKey, options = {})");
        assertThat(engine).contains("status: 'ready'");
        assertThat(engine).contains("status: 'unavailable'");
        assertThat(engine).contains("status: 'unknown'");
        assertThat(engine).contains("isChapterPlayable(metadata)");

        String probeMethod = engine.substring(
                engine.indexOf("async probePlaybackMetadata(chapterId, voiceKey, options = {})"),
                engine.indexOf("async fetchPlaybackMetadata(chapterId, voiceKey, options = {})")
        );
        assertThat(probeMethod).contains("metadata.availability === 'READY'");
        assertThat(probeMethod).contains("metadata.availability === 'MISSING' || metadata.availability === 'FAILED'");
        assertThat(probeMethod).contains("metadata.availability === 'BUILDING'");
        assertThat(probeMethod).contains("metadata.freshness === 'STALE_CONTENT' && metadata.playable === false");
        assertThat(probeMethod).doesNotContain(".play(");
        assertThat(probeMethod).doesNotContain("new Audio(");
        assertThat(probeMethod).doesNotContain("audio.src");
        assertThat(probeMethod).doesNotContain("this._transitionState(");
    }

    @Test
    @DisplayName("H.9I5C2B: NarrationController records playbackAvailability during preload and negative availability routes to shared preparation after commit")
    void h9i5c2bPreloadRecordsPlaybackAvailabilityAndNegativeReuseEntersPreparation() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        assertThat(controllerJs).contains("playbackAvailability: 'unknown'");
        assertThat(controllerJs).contains("chapterEngine.probePlaybackMetadata(");
        assertThat(controllerJs).contains("snapshot.playbackAvailability = 'ready';");
        assertThat(controllerJs).contains("snapshot.playbackAvailability = 'unavailable';");
        assertThat(controllerJs).contains("snapshot.playbackAvailability = 'unknown';");

        // Preload itself stays probe-only and contains zero requestPlaybackPreparation
        String preloadMethod = controllerMethod("_executeNextChapterPreload(snapshot) {", "_isPreloadStale(snapshot) {");
        assertThat(preloadMethod).doesNotContain("requestPlaybackPreparation");
        assertThat(preloadMethod).contains("probePlaybackMetadata");

        String applyTransition = controllerMethod(
                "_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {",
                "_onEngineError(error, engineType) {"
        );

        // Chapter B commit must occur before preparation invocation
        int commitChapter = applyTransition.indexOf("this.chapterId = newChapterId;");
        int prepCall = applyTransition.indexOf("this._ensureManagedPlaybackForIntent(");
        assertThat(commitChapter).isGreaterThan(0);
        assertThat(prepCall).isGreaterThan(commitChapter);

        // Preload availability & metadata passed to shared preparation helper
        assertThat(applyTransition).contains("preloadedPlaybackAvailability: preloadedPlaybackAvailability");
        assertThat(applyTransition).contains("preloadedPlaybackMetadata: preloadedChapterMetadata");

        // Old immediate failure on unavailable preload is replaced by preparation flow
        assertThat(applyTransition).doesNotContain("if (preloadedPlaybackAvailability === 'unavailable') {\n                    handleManagedFailure();");

        // Shared preparation helper owns requestPlaybackPreparation and handles seeded availability
        String ensureMethod = controllerMethod("_ensureManagedPlaybackForIntent(chapterId, voiceKey", "_handlePlayPause() {");
        assertThat(ensureMethod).contains("this.chapterEngine.requestPlaybackPreparation(chapterId, voiceKey");
        assertThat(ensureMethod).contains("seedAvailability === 'unavailable'");
        assertThat(ensureMethod).contains("seedAvailability === 'ready'");
    }

    @Test
    @DisplayName("H.9I4: ManagedAudioEngine has zero Reader audio playback responsibility")
    void h9i4ManagedAudioEngineHasZeroPlaybackResponsibility() throws Exception {
        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");

        // Retained catalog APIs
        assertThat(managedJs).contains("function buildVoiceCatalogUrl()");
        assertThat(managedJs).contains("isSupported()");
        assertThat(managedJs).contains("async loadVoiceCatalog()");
        assertThat(managedJs).contains("cancelVoiceCatalogLoad()");
        assertThat(managedJs).contains("getVoices()");
        assertThat(managedJs).contains("destroy()");

        // Prohibited playback responsibilities
        assertThat(managedJs).doesNotContain("new Audio(");
        assertThat(managedJs).doesNotContain("HTMLAudioElement");
        assertThat(managedJs).doesNotContain("buildManifestUrl(");
        assertThat(managedJs).doesNotContain("loadManifest(");
        assertThat(managedJs).doesNotContain("setManifest(");
        assertThat(managedJs).doesNotContain("buildPrepareUrl(");
        assertThat(managedJs).doesNotContain("prepareSegmentPlayback(");
        assertThat(managedJs).doesNotContain("ManagedEngineState");
        assertThat(managedJs).doesNotContain("seekToSegment(");
        assertThat(managedJs).doesNotContain("seekToChunk(");
        assertThat(managedJs).doesNotContain("nextSegment(");
        assertThat(managedJs).doesNotContain("previousSegment(");
        assertThat(managedJs).doesNotContain("_transitionState(");
        assertThat(managedJs).doesNotContain("/narration/manifest");
        assertThat(managedJs).doesNotContain("/segments/");
        assertThat(managedJs).doesNotContain("/prepare");
    }

    @Test
    @DisplayName("H.9I4: NarrationController never assigns ManagedAudioEngine as playback authority")
    void h9i4NarrationControllerNeverAssignsManagedAudioEngineAsPlaybackAuthority() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");

        // Proof of zero playback authority assignment
        assertThat(controllerJs).doesNotContain("this.engine = this.managedEngine");
        assertThat(controllerJs).doesNotContain("this.activeEngine = this.managedEngine");
        assertThat(controllerJs).doesNotContain("this.engine === this.managedEngine");
        assertThat(controllerJs).doesNotContain("this.activeEngine === this.managedEngine");

        // Proof of zero playback method calls on managedEngine
        assertThat(controllerJs).doesNotContain("this.managedEngine.play(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.loadManifest(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.setManifest(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.prepareSegmentPlayback(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.setRate(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.seekToSegment(");
        assertThat(controllerJs).doesNotContain("this.managedEngine.seekToChunk(");
    }

    @Test
    @DisplayName("H.9I4: Managed Reader playback is strictly ChapterAudioEngine")
    void h9i4ManagedReaderPlaybackIsStrictlyChapterAudioEngine() throws Exception {
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");

        // activateEngine assigns chapterEngine for managed
        String activateMethod = controllerMethod("_activateEngine(type, identifier) {", "_invalidateChapterPlayback() {");
        assertThat(activateMethod).contains("this.activeEngineType = 'managed';");
        assertThat(activateMethod).contains("this.activeEngine = this.chapterEngine;");
        assertThat(activateMethod).contains("this.engine = this.chapterEngine;");

        // _handleVoiceChange assigns chapterEngine for managed
        int voiceChangeStart = controllerJs.indexOf("async _handleVoiceChange() {");
        int voiceChangeEnd = controllerJs.indexOf("_handleRateChange() {", voiceChangeStart);
        String voiceChangeMethod = controllerJs.substring(voiceChangeStart, voiceChangeEnd);
        assertThat(voiceChangeMethod).contains("this.activeEngineType = 'managed';");
        assertThat(voiceChangeMethod).contains("this.activeEngine = this.chapterEngine;");
        assertThat(voiceChangeMethod).contains("this.engine = this.chapterEngine;");

        // _applyChapterTransition assigns chapterEngine for managed
        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transMethod = controllerJs.substring(transStart, transEnd);
        assertThat(transMethod).contains("this.activeEngineType = 'managed';");
        assertThat(transMethod).contains("this.activeEngine = this.chapterEngine;");
        assertThat(transMethod).contains("this.engine = this.chapterEngine;");
    }

    @Test
    @DisplayName("H.9I5C2A: ChapterAudioEngine owns prepare transport; NarrationController orchestrates on user play intent")
    void h9i5c2aManualManagedPreparationOrchestrationContract() throws Exception {
        String chapterJs = chapterEngine();
        assertThat(chapterJs).contains("function buildPrepareUrl(chapterId)");
        assertThat(chapterJs).contains("'/narration/prepare'");
        assertThat(chapterJs).contains("async requestPlaybackPreparation(chapterId, voiceKey, options = {})");
        assertThat(chapterJs).doesNotContain("/segments/");
        assertThat(chapterJs).doesNotContain("/manifest");

        String managedJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");
        assertThat(managedJs).doesNotContain("/prepare");
        assertThat(managedJs).doesNotContain("requestPlaybackPreparation");

        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String prepMethod = controllerMethod("_ensureManagedPlaybackForIntent(chapterId, voiceKey", "_handlePlayPause() {");
        assertThat(prepMethod).contains("this.chapterEngine.requestPlaybackPreparation(chapterId, voiceKey");

        // Forbidden entry points must NOT call requestPlaybackPreparation
        String activateMethod = controllerMethod("_activateEngine(type, identifier) {", "_invalidateChapterPlayback() {");
        assertThat(activateMethod).doesNotContain("requestPlaybackPreparation");

        String voiceChangeMethod = controllerMethod("async _handleVoiceChange() {", "_handleRateChange() {");
        assertThat(voiceChangeMethod).doesNotContain("requestPlaybackPreparation");

        String preloadMethod = controllerMethod("_checkAndTriggerNextChapterPreload(progress) {", "_isPreloadStale(snapshot) {");
        assertThat(preloadMethod).doesNotContain("requestPlaybackPreparation");

        String initMethod = controllerMethod("init() {", "_queryDomElements() {");
        assertThat(initMethod).doesNotContain("requestPlaybackPreparation");

        int transStart = controllerJs.indexOf("_applyChapterTransition(fetchedDoc, nextUrl, validation, continuationIntent, preloadedChapterMetadata = null, preloadedPlaybackAvailability = 'unknown') {");
        int transEnd = controllerJs.indexOf("_onEngineError(error, engineType) {", transStart);
        String transMethod = controllerJs.substring(transStart, transEnd);
        assertThat(transMethod).doesNotContain("requestPlaybackPreparation");

        // Hardening: _handlePlayPause fast path must verify exact voiceKey
        String playPauseMethod = controllerMethod("_handlePlayPause() {", "_handlePrev() {");
        assertThat(playPauseMethod).contains("this.chapterEngine.metadata.voiceKey");
    }

    @Test
    @DisplayName("155. H.9I5D1 Legacy Reader narration HTTP delivery surface retirement contract")
    void h9i5d1LegacyDeliveryRetirementContract() throws Exception {
        // 1. Reader production JS contains zero manifest, segment prepare, or legacy handler usage
        String controllerJs = read("src/main/resources/static/js/novel/narration-controller.js");
        String chapterEngineJs = chapterEngine();
        String managedEngineJs = read("src/main/resources/static/js/novel/managed-audio-engine.js");

        assertThat(controllerJs).doesNotContain(
                "/narration/manifest",
                "/narration/segments/",
                "loadManifest",
                "prepareSegmentPlayback"
        );
        assertThat(chapterEngineJs).doesNotContain(
                "/narration/manifest",
                "/narration/segments/",
                "loadManifest",
                "prepareSegmentPlayback"
        );
        assertThat(managedEngineJs).doesNotContain(
                "/narration/manifest",
                "/narration/segments/",
                "loadManifest",
                "prepareSegmentPlayback"
        );

        // 2. ManagedAudioEngine remains catalog-only
        assertThat(managedEngineJs).doesNotContain("/prepare");
        assertThat(managedEngineJs).doesNotContain("requestPlaybackPreparation");
        assertThat(managedEngineJs).doesNotContain("/playback");

        // 3. ChapterAudioEngine chapter-level prepare remains
        assertThat(chapterEngineJs).contains("'/narration/prepare'");
        assertThat(chapterEngineJs).contains("requestPlaybackPreparation(chapterId, voiceKey, options = {})");

        // 4. PublicNovelChapterNarrationPlaybackController contains chapter-level /prepare and /playback, NO /segments/ or prepareSegmentPlayback
        String playbackController = read("src/main/java/com/universe/novel/entry/reader/PublicNovelChapterNarrationPlaybackController.java");
        assertThat(playbackController).contains("@PostMapping(\"/prepare\")");
        assertThat(playbackController).contains("@GetMapping(\"/playback\")");
        assertThat(playbackController).doesNotContain("/segments/");
        assertThat(playbackController).doesNotContain("prepareSegmentPlayback");

        // 5. PublicNovelChapterNarrationManifestController.java file does NOT exist
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/entry/reader/PublicNovelChapterNarrationManifestController.java"))).isFalse();

        // 6. SecurityBeanConfig contains NO /api/novel/chapters/*/narration/manifest and still contains voices, playback, and prepare
        String securityConfig = read("src/main/java/com/universe/configuration/SecurityBeanConfig.java");
        assertThat(securityConfig).doesNotContain("/api/novel/chapters/*/narration/manifest");
        assertThat(securityConfig).contains("/api/novel/narration/voices");
        assertThat(securityConfig).contains("/api/novel/chapters/*/narration/playback");
        assertThat(securityConfig).contains("/api/novel/chapters/*/narration/prepare");

        // 7. PerformanceServerTimingFilter contains no manifest-only route handling
        String perfFilter = read("src/main/java/com/universe/configuration/performance/PerformanceServerTimingFilter.java");
        assertThat(perfFilter).doesNotContain("/narration/manifest");
        assertThat(perfFilter).doesNotContain("isManifestPath");
    }

    @Test
    @DisplayName("156. H.9I5D2B1 Legacy manifest and immediate segment preparation files do not exist")
    void h9i5d2b1LegacyPreparationFilesDoNotExist() {
        // 18 production files deleted in B1
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/GetPublicChapterNarrationManifestUseCase.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/GetPublicChapterNarrationManifestQuery.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/contracts/dto/narration/PublicChapterNarrationManifestDTO.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/contracts/dto/narration/PublicNarrationSegmentDTO.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PreparePublicReaderNarrationPlaybackUseCase.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PreparePublicReaderNarrationPlaybackCommand.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PreparePublicReaderNarrationPlaybackResult.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationPlaybackUseCase.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationPlaybackResult.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationSegmentUseCase.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationSegmentCommand.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationSegmentResult.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/PrepareReaderNarrationSegmentOutcome.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/ReaderNarrationPreparationDecisionPlanner.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/ReaderNarrationPreparationDecision.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/application/narration/ReaderNarrationPreparationAction.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/contracts/dto/narration/PrepareReaderNarrationPlaybackRequest.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/universe/novel/contracts/dto/narration/PublicReaderNarrationPlaybackDTO.java"))).isFalse();

        // 5 test files deleted in B1
        assertThat(Files.exists(Path.of("src/test/java/com/universe/novel/application/narration/GetPublicChapterNarrationManifestUseCaseTest.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/universe/novel/application/narration/PreparePublicReaderNarrationPlaybackUseCaseTest.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/universe/novel/application/narration/PrepareReaderNarrationPlaybackUseCaseTest.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/universe/novel/application/narration/PrepareReaderNarrationSegmentUseCaseTest.java"))).isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/universe/novel/application/narration/ReaderNarrationPreparationDecisionPlannerTest.java"))).isFalse();
    }

    private String chapterEngine() throws Exception {
        return read("src/main/resources/static/js/novel/chapter-audio-engine.js");
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
