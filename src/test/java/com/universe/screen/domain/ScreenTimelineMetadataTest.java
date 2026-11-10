package com.universe.screen.domain;

import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MS-08G3-UX02-C1: Screen Timeline Metadata & Inheritance Domain Tests")
class ScreenTimelineMetadataTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T13:00:00Z");

    // =========================================================================
    // 1. Existing records default to INHERIT and have no effective timeline
    //    when title has no defaults
    // =========================================================================
    @Test
    @DisplayName("1. Existing records default to INHERIT and resolve to empty effective timeline when title has no defaults")
    void existingRecordsDefaultToInheritWithEmptyEffectiveTimeline() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThat(title.getDefaultIntroStartSeconds()).isNull();
        assertThat(title.getDefaultIntroEndSeconds()).isNull();
        assertThat(title.getDefaultOutroStartSeconds()).isNull();
        assertThat(title.getDefaultIntroInterval()).isNull();
        assertThat(title.getDefaultOutroMarker()).isNull();

        assertThat(entry.getIntroMode()).isEqualTo(ScreenTimelineMode.INHERIT);
        assertThat(entry.getIntroStartSeconds()).isNull();
        assertThat(entry.getIntroEndSeconds()).isNull();
        assertThat(entry.getOutroMode()).isEqualTo(ScreenTimelineMode.INHERIT);
        assertThat(entry.getOutroStartSeconds()).isNull();
        assertThat(entry.getIntroOverride()).isNull();
        assertThat(entry.getOutroOverride()).isNull();

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isFalse();
        assertThat(effective.hasOutro()).isFalse();
        assertThat(effective.introInterval()).isNull();
        assertThat(effective.outroMarker()).isNull();
        assertThat(effective.introStartSeconds()).isNull();
        assertThat(effective.introEndSeconds()).isNull();
        assertThat(effective.outroStartSeconds()).isNull();
    }

    // =========================================================================
    // 2. Valid title default Intro is inherited
    // =========================================================================
    @Test
    @DisplayName("2. Valid title default Intro is inherited when episode introMode is INHERIT")
    void validTitleDefaultIntroIsInherited() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(15L, 105L, null, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, T0
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isTrue();
        assertThat(effective.introStartSeconds()).isEqualTo(15L);
        assertThat(effective.introEndSeconds()).isEqualTo(105L);
        assertThat(effective.introInterval()).isEqualTo(new ScreenIntroInterval(15L, 105L));
        assertThat(effective.hasOutro()).isFalse();
    }

    // =========================================================================
    // 3. Valid title default Outro is inherited
    // =========================================================================
    @Test
    @DisplayName("3. Valid title default Outro is inherited when episode outroMode is INHERIT")
    void validTitleDefaultOutroIsInherited() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(null, null, 1320L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, T0
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isFalse();
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1320L);
        assertThat(effective.outroMarker()).isEqualTo(new ScreenOutroMarker(1320L));
    }

    // =========================================================================
    // 4. Episode Intro OVERRIDE takes precedence over title default
    // =========================================================================
    @Test
    @DisplayName("4. Episode Intro OVERRIDE takes precedence over title default Intro")
    void episodeIntroOverrideTakesPrecedenceOverTitleDefault() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(10L, 100L, 1300L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-2",
                ScreenEntryType.EPISODE, 2, "Tập 2", null, null, null, 1500L, T0
        );
        entry.updateTimeline(ScreenTimelineMode.OVERRIDE, 45L, 135L, ScreenTimelineMode.INHERIT, null, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isTrue();
        assertThat(effective.introStartSeconds()).isEqualTo(45L);
        assertThat(effective.introEndSeconds()).isEqualTo(135L);
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1300L);
    }

    // =========================================================================
    // 5. Episode Outro OVERRIDE takes precedence over title default
    // =========================================================================
    @Test
    @DisplayName("5. Episode Outro OVERRIDE takes precedence over title default Outro")
    void episodeOutroOverrideTakesPrecedenceOverTitleDefault() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(10L, 100L, 1300L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-2",
                ScreenEntryType.EPISODE, 2, "Tập 2", null, null, null, 1500L, T0
        );
        entry.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 1250L, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isTrue();
        assertThat(effective.introStartSeconds()).isEqualTo(10L);
        assertThat(effective.introEndSeconds()).isEqualTo(100L);
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1250L);
    }

    // =========================================================================
    // 6. Episode Intro DISABLED suppresses title default
    // =========================================================================
    @Test
    @DisplayName("6. Episode Intro DISABLED suppresses title default Intro")
    void episodeIntroDisabledSuppressesTitleDefault() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(0L, 90L, 1300L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1 (Cold Open)", null, null, null, 1500L, T0
        );
        entry.updateTimeline(ScreenTimelineMode.DISABLED, null, null, ScreenTimelineMode.INHERIT, null, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isFalse();
        assertThat(effective.introInterval()).isNull();
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1300L);
    }

    // =========================================================================
    // 7. Episode Outro DISABLED suppresses title default
    // =========================================================================
    @Test
    @DisplayName("7. Episode Outro DISABLED suppresses title default Outro")
    void episodeOutroDisabledSuppressesTitleDefault() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(0L, 90L, 1300L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-26",
                ScreenEntryType.EPISODE, 26, "Tập Cuối", null, null, null, 1500L, T0
        );
        entry.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.DISABLED, null, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isTrue();
        assertThat(effective.introStartSeconds()).isEqualTo(0L);
        assertThat(effective.introEndSeconds()).isEqualTo(90L);
        assertThat(effective.hasOutro()).isFalse();
        assertThat(effective.outroMarker()).isNull();
    }

    // =========================================================================
    // 8. Intro and Outro modes operate independently
    // =========================================================================
    @ParameterizedTest(name = "IntroMode={0}, OutroMode={1} -> expectedIntro={2}..{3}, expectedOutro={4}")
    @MethodSource("provideIndependentModeCombinations")
    @DisplayName("8. Intro and Outro modes operate independently across all 9 mode combinations")
    void introAndOutroModesOperateIndependently(
            ScreenTimelineMode introMode,
            Long overrideIntroStart,
            Long overrideIntroEnd,
            ScreenTimelineMode outroMode,
            Long overrideOutroStart,
            Long expectedIntroStart,
            Long expectedIntroEnd,
            Long expectedOutroStart
    ) {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(10L, 100L, 1200L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, T0,
                introMode, overrideIntroStart, overrideIntroEnd, outroMode, overrideOutroStart
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.introStartSeconds()).isEqualTo(expectedIntroStart);
        assertThat(effective.introEndSeconds()).isEqualTo(expectedIntroEnd);
        assertThat(effective.outroStartSeconds()).isEqualTo(expectedOutroStart);
    }

    private static Stream<Arguments> provideIndependentModeCombinations() {
        return Stream.of(
                Arguments.of(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.INHERIT, null, 10L, 100L, 1200L),
                Arguments.of(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 1350L, 10L, 100L, 1350L),
                Arguments.of(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.DISABLED, null, 10L, 100L, null),
                Arguments.of(ScreenTimelineMode.OVERRIDE, 30L, 120L, ScreenTimelineMode.INHERIT, null, 30L, 120L, 1200L),
                Arguments.of(ScreenTimelineMode.OVERRIDE, 30L, 120L, ScreenTimelineMode.OVERRIDE, 1350L, 30L, 120L, 1350L),
                Arguments.of(ScreenTimelineMode.OVERRIDE, 30L, 120L, ScreenTimelineMode.DISABLED, null, 30L, 120L, null),
                Arguments.of(ScreenTimelineMode.DISABLED, null, null, ScreenTimelineMode.INHERIT, null, null, null, 1200L),
                Arguments.of(ScreenTimelineMode.DISABLED, null, null, ScreenTimelineMode.OVERRIDE, 1350L, null, null, 1350L),
                Arguments.of(ScreenTimelineMode.DISABLED, null, null, ScreenTimelineMode.DISABLED, null, null, null, null)
        );
    }

    // =========================================================================
    // 9. Invalid negative timestamps are rejected
    // =========================================================================
    @Test
    @DisplayName("9. Invalid negative timestamps are rejected on value objects, ScreenTitle, and ScreenEntry")
    void invalidNegativeTimestampsAreRejected() {
        assertThatThrownBy(() -> new ScreenIntroInterval(-1L, 90L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");

        assertThatThrownBy(() -> new ScreenOutroMarker(-5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");

        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        assertThatThrownBy(() -> title.updateDefaultTimeline(-1L, 90L, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> title.updateDefaultTimeline(null, null, -10L, T1))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, -1L, 90L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, null, null,
                ScreenTimelineMode.OVERRIDE, -1L, T1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 10. Empty or partial Intro intervals (only start or only end) are rejected
    // =========================================================================
    @Test
    @DisplayName("10. Partial Intro intervals (only start or only end) are rejected on ScreenTitle and ScreenEntry")
    void partialIntroIntervalsAreRejected() {
        assertThatThrownBy(() -> ScreenIntroInterval.ofNullable(10L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScreenIntroInterval.ofNullable(null, 90L))
                .isInstanceOf(IllegalArgumentException.class);

        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        assertThatThrownBy(() -> title.updateDefaultTimeline(10L, null, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> title.updateDefaultTimeline(null, 90L, null, T1))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 10L, null,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, null, 90L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 11. Zero-length and inverted Intro intervals are rejected
    // =========================================================================
    @Test
    @DisplayName("11. Zero-length (start == end) and inverted (end < start) Intro intervals are rejected")
    void zeroLengthAndInvertedIntroIntervalsAreRejected() {
        assertThatThrownBy(() -> new ScreenIntroInterval(50L, 50L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenIntroInterval(100L, 50L))
                .isInstanceOf(IllegalArgumentException.class);

        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        assertThatThrownBy(() -> title.updateDefaultTimeline(60L, 60L, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> title.updateDefaultTimeline(90L, 30L, null, T1))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 60L, 60L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 90L, 30L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 12. INHERIT mode with non-null override values is rejected
    // =========================================================================
    @Test
    @DisplayName("12. INHERIT mode with non-null override values is rejected")
    void inheritModeWithNonNullOverrideValuesIsRejected() {
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, 0L, 90L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INHERIT");

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, 0L, null,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INHERIT");

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, null, null,
                ScreenTimelineMode.INHERIT, 1200L, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INHERIT");
    }

    // =========================================================================
    // 13. DISABLED mode with non-null override values is rejected
    // =========================================================================
    @Test
    @DisplayName("13. DISABLED mode with non-null override values is rejected")
    void disabledModeWithNonNullOverrideValuesIsRejected() {
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.DISABLED, 0L, 90L,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, null, null,
                ScreenTimelineMode.DISABLED, 1200L, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");
    }

    // =========================================================================
    // 14. OVERRIDE mode without required values is rejected
    // =========================================================================
    @Test
    @DisplayName("14. OVERRIDE mode without required values is rejected")
    void overrideModeWithoutRequiredValuesIsRejected() {
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, null, null,
                ScreenTimelineMode.INHERIT, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OVERRIDE");

        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.INHERIT, null, null,
                ScreenTimelineMode.OVERRIDE, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OVERRIDE");
    }

    // =========================================================================
    // 15. Invalid effective Intro/Outro overlap fails safely according to
    //     the documented domain rule
    // =========================================================================
    @Test
    @DisplayName("15. Invalid Intro/Outro overlap: rejected within same aggregate and suppressed safely across inherited/override boundary")
    void invalidEffectiveIntroOutroOverlapFailsSafely() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        // Same-aggregate overlap on ScreenTitle is rejected at mutation time
        assertThatThrownBy(() -> title.updateDefaultTimeline(10L, 120L, 100L, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outroStartSeconds");

        // Boundary case outroStart == introEnd is allowed (no overlap: intro is [10, 120), outro starts at 120)
        title.updateDefaultTimeline(10L, 120L, 120L, T1);
        assertThat(title.getDefaultOutroStartSeconds()).isEqualTo(120L);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, T0
        );

        // Same-aggregate overlap on ScreenEntry (both OVERRIDE) is rejected at mutation time
        assertThatThrownBy(() -> entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 20L, 150L,
                ScreenTimelineMode.OVERRIDE, 140L, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outroStartSeconds");

        // Cross-aggregate overlap (e.g. episode overrides Intro to 20..180 while inheriting title default Outro at 120):
        // EffectiveScreenTimeline.resolve suppresses conflicting outroMarker so Intro and Outro never overlap at runtime
        entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 20L, 180L,
                ScreenTimelineMode.INHERIT, null, T1
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasIntro()).isTrue();
        assertThat(effective.introStartSeconds()).isEqualTo(20L);
        assertThat(effective.introEndSeconds()).isEqualTo(180L);
        assertThat(effective.hasOutro()).isFalse();
        assertThat(effective.outroMarker()).isNull();

        // Explicit command-time cross-aggregate validator rejects the overlapping combination
        assertThatThrownBy(() -> EffectiveScreenTimeline.validateCombinedTimeline(title, entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outroStartSeconds");
    }

    // =========================================================================
    // 16. Missing or inconsistent durationSeconds does not invent a seek target
    // =========================================================================
    @Test
    @DisplayName("16. Missing or inconsistent durationSeconds does not invent a seek target and suppresses out-of-bounds markers")
    void missingOrInconsistentDurationDoesNotInventSeekTarget() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(10L, 100L, 1200L, T1);

        // Case A: durationSeconds is null -> no duration is invented; configured timeline is preserved as metadata
        ScreenEntry entryNullDuration = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-null-dur",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, null, T0
        );
        assertThat(entryNullDuration.getDurationSeconds()).isNull();
        EffectiveScreenTimeline effectiveWithNullDuration = entryNullDuration.resolveEffectiveTimeline(title);
        assertThat(effectiveWithNullDuration.introStartSeconds()).isEqualTo(10L);
        assertThat(effectiveWithNullDuration.introEndSeconds()).isEqualTo(100L);
        assertThat(effectiveWithNullDuration.outroStartSeconds()).isEqualTo(1200L);

        // Case B: entry durationSeconds is 90s (smaller than introEndSeconds=100 and outroStartSeconds=1200) ->
        // EffectiveScreenTimeline suppresses both out-of-bounds markers and never clamps/invents a fake seek target
        ScreenEntry shortEntry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-short",
                ScreenEntryType.EPISODE, 2, "Tập 2 Ngắn", null, null, null, 90L, T0
        );
        EffectiveScreenTimeline effectiveShort = shortEntry.resolveEffectiveTimeline(title);
        assertThat(effectiveShort.hasIntro()).isFalse();
        assertThat(effectiveShort.introEndSeconds()).isNull();
        assertThat(effectiveShort.hasOutro()).isFalse();
        assertThat(effectiveShort.outroStartSeconds()).isNull();

        // Case C: entry durationSeconds is 600s (valid for Intro 10..100, smaller than Outro 1200) ->
        // Intro is kept, out-of-bounds Outro is suppressed
        ScreenEntry mediumEntry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-medium",
                ScreenEntryType.EPISODE, 3, "Tập 3 Vừa", null, null, null, 600L, T0
        );
        EffectiveScreenTimeline effectiveMedium = mediumEntry.resolveEffectiveTimeline(title);
        assertThat(effectiveMedium.hasIntro()).isTrue();
        assertThat(effectiveMedium.introStartSeconds()).isEqualTo(10L);
        assertThat(effectiveMedium.introEndSeconds()).isEqualTo(100L);
        assertThat(effectiveMedium.hasOutro()).isFalse();
        assertThat(effectiveMedium.outroStartSeconds()).isNull();
    }

    // =========================================================================
    // 17. Unrelated title updates preserve title default timeline metadata
    // =========================================================================
    @Test
    @DisplayName("17. Unrelated title updates, media updates, publish, archive, and UpdateScreenTitleUseCase preserve title default timeline metadata")
    void unrelatedTitleUpdatesPreserveDefaultTimelineMetadata() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        title.updateDefaultTimeline(15L, 105L, 1320L, T1);

        // 1. Domain updateDetails (all overloads)
        title.updateDetails(
                "kiem-lai-s1-renamed", "Kiếm Lai Phần 1 Mới", "剑来", "Tóm tắt mới",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                UUID.randomUUID(), UUID.randomUUID(), T2,
                26, ScreenAiringStatus.ONGOING, 2026, "Trung Quốc", "Phong Hỏa", "Đạo diễn", "Studio"
        );
        assertThat(title.getDefaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(title.getDefaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(title.getDefaultOutroStartSeconds()).isEqualTo(1320L);

        // 2. Domain updateMedia, publish, archive
        title.updateMedia(UUID.randomUUID(), UUID.randomUUID(), T2);
        title.publish(T2);
        title.archive(T3);
        assertThat(title.getDefaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(title.getDefaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(title.getDefaultOutroStartSeconds()).isEqualTo(1320L);

        // 3. UseCase: UpdateScreenTitleUseCase
        ScreenTitleRepositoryPort titleRepo = mock(ScreenTitleRepositoryPort.class);
        ScreenEntryRepositoryPort entryRepo = mock(ScreenEntryRepositoryPort.class);
        ScreenMediaValidationPort mediaValidation = mock(ScreenMediaValidationPort.class);
        ClockPort fixedClock = () -> T3;

        ScreenTitle activeTitle = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        activeTitle.updateDefaultTimeline(15L, 105L, 1320L, T1);

        when(titleRepo.findByIdForUpdate(titleId)).thenReturn(Optional.of(activeTitle));
        when(titleRepo.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateScreenTitleUseCase useCase = new UpdateScreenTitleUseCase(
                titleRepo, mediaValidation, fixedClock, entryRepo
        );
        ScreenTitleDTO updatedDto = useCase.execute(new UpdateScreenTitleCommand(
                titleId, "kiem-lai-s1-edited", "Kiếm Lai Edited", "剑来", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 24
        ));

        assertThat(updatedDto.defaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(updatedDto.defaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(updatedDto.defaultOutroStartSeconds()).isEqualTo(1320L);
    }

    // =========================================================================
    // 18 & 19 & 20. Unrelated entry updates, video attach/replace/detach, and
    //               entry publication/archive preserve episode timeline metadata
    // =========================================================================
    @Test
    @DisplayName("18-20. Unrelated entry updates, video attach/replace/detach, and publish/archive preserve episode timeline metadata")
    void unrelatedEntryMutationsAndUseCasesPreserveEpisodeTimelineMetadata() {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID initialVideoId = UUID.randomUUID();
        UUID replacedVideoId = UUID.randomUUID();
        UUID reattachedVideoId = UUID.randomUUID();

        ScreenTitle parentTitle = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", "Tóm tắt", initialVideoId, null, 1500L, T0
        );
        entry.updateTimeline(
                ScreenTimelineMode.OVERRIDE, 20L, 110L,
                ScreenTimelineMode.OVERRIDE, 1380L, T1
        );

        // 18. Unrelated entry metadata/details/duration updates preserve timeline
        entry.updateDetails(
                "tap-1-edited", ScreenEntryType.EPISODE, 1, "Tập 1 Edited",
                "Tóm tắt mới", 1520L, initialVideoId, UUID.randomUUID(), T2
        );
        entry.updateMetadata("tap-1-meta", ScreenEntryType.EPISODE, 1, "Tập 1 Meta", "Synopsis", T2);
        entry.updateDuration(1550L, T2);
        assertThat(entry.getIntroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(entry.getIntroStartSeconds()).isEqualTo(20L);
        assertThat(entry.getIntroEndSeconds()).isEqualTo(110L);
        assertThat(entry.getOutroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(entry.getOutroStartSeconds()).isEqualTo(1380L);

        // 19 & 20. Use cases: UpdateScreenEntryUseCase, ReplaceScreenEntryVideoUseCase,
        //          DetachScreenEntryVideoUseCase, AttachScreenEntryVideoUseCase, PublishScreenEntryUseCase
        ScreenEntryRepositoryPort entryRepo = mock(ScreenEntryRepositoryPort.class);
        ScreenTitleRepositoryPort titleRepo = mock(ScreenTitleRepositoryPort.class);
        ScreenMediaValidationPort mediaValidation = mock(ScreenMediaValidationPort.class);
        ScreenMediaVisibilityPort mediaVisibility = mock(ScreenMediaVisibilityPort.class);
        ClockPort fixedClock = () -> T3;

        when(titleRepo.existsById(titleId)).thenReturn(true);
        when(titleRepo.findById(titleId)).thenReturn(Optional.of(parentTitle));
        when(titleRepo.findByIdForUpdate(titleId)).thenReturn(Optional.of(parentTitle));
        when(entryRepo.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepo.findVideoMediaAssetIdById(entryId)).thenAnswer(inv -> Optional.ofNullable(entry.getVideoMediaAssetId()));
        when(entryRepo.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        // UpdateScreenEntryUseCase
        UpdateScreenEntryUseCase updateUseCase = new UpdateScreenEntryUseCase(
                titleRepo, entryRepo, fixedClock
        );
        ScreenEntryDTO afterUpdateDto = updateUseCase.execute(new UpdateScreenEntryCommand(
                titleId, entryId, "tap-1-uc", ScreenEntryType.EPISODE, 1, "Tập 1 UC",
                "Synopsis", 1550L
        ));
        assertTimelinePreserved(afterUpdateDto);

        // ReplaceScreenEntryVideoUseCase
        ReplaceScreenEntryVideoUseCase replaceVideoUseCase = new ReplaceScreenEntryVideoUseCase(
                titleRepo, entryRepo, mediaValidation, mediaVisibility, fixedClock
        );
        ScreenEntryDTO afterReplaceDto = replaceVideoUseCase.execute(titleId, entryId, replacedVideoId, initialVideoId);
        assertTimelinePreserved(afterReplaceDto);

        // DetachScreenEntryVideoUseCase (allowed while DRAFT)
        DetachScreenEntryVideoUseCase detachVideoUseCase = new DetachScreenEntryVideoUseCase(
                titleRepo, entryRepo, fixedClock
        );
        ScreenEntryDTO afterDetachDto = detachVideoUseCase.execute(titleId, entryId, replacedVideoId);
        assertTimelinePreserved(afterDetachDto);

        // AttachScreenEntryVideoUseCase
        AttachScreenEntryVideoUseCase attachVideoUseCase = new AttachScreenEntryVideoUseCase(
                titleRepo, entryRepo, mediaValidation, mediaVisibility, fixedClock
        );
        ScreenEntryDTO afterAttachDto = attachVideoUseCase.execute(titleId, entryId, reattachedVideoId);
        assertTimelinePreserved(afterAttachDto);

        // PublishScreenEntryUseCase
        PublishScreenEntryUseCase publishUseCase = new PublishScreenEntryUseCase(
                titleRepo, entryRepo, mediaVisibility, fixedClock
        );
        ScreenEntryDTO afterPublishDto = publishUseCase.execute(titleId, entryId);
        assertTimelinePreserved(afterPublishDto);

        // Archive on domain
        entry.archive(T3);
        assertThat(entry.getIntroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(entry.getIntroStartSeconds()).isEqualTo(20L);
        assertThat(entry.getIntroEndSeconds()).isEqualTo(110L);
        assertThat(entry.getOutroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(entry.getOutroStartSeconds()).isEqualTo(1380L);
    }

    // =========================================================================
    // UX02-C2.1: OUTRO END BOUNDARY & AFTER-CREDIT PROTECTION TESTS
    // =========================================================================

    @Test
    @DisplayName("UX02-C2.1: ScreenOutroMarker models both unbounded (end=null) and bounded (end>start) Outro intervals")
    void outroMarkerSupportsBoundedAndUnboundedIntervals() {
        // Unbounded Outro (null end)
        ScreenOutroMarker unbounded = ScreenOutroMarker.ofNullable(1452L);
        assertThat(unbounded).isNotNull();
        assertThat(unbounded.startSeconds()).isEqualTo(1452L);
        assertThat(unbounded.endSeconds()).isNull();
        assertThat(unbounded.hasEnd()).isFalse();
        assertThat(unbounded.isReachedAt(1451L)).isFalse();
        assertThat(unbounded.isReachedAt(1452L)).isTrue();
        assertThat(unbounded.isReachedAt(1530L)).isTrue();
        assertThat(unbounded.contains(1451L)).isFalse();
        assertThat(unbounded.contains(1452L)).isTrue();
        assertThat(unbounded.contains(1576L)).isTrue(); // unbounded extends to end of video

        // Bounded Outro [1452, 1530)
        ScreenOutroMarker bounded = ScreenOutroMarker.ofNullable(1452L, 1530L);
        assertThat(bounded).isNotNull();
        assertThat(bounded.startSeconds()).isEqualTo(1452L);
        assertThat(bounded.endSeconds()).isEqualTo(1530L);
        assertThat(bounded.hasEnd()).isTrue();
        assertThat(bounded.contains(1451L)).isFalse();
        assertThat(bounded.contains(1452L)).isTrue();
        assertThat(bounded.contains(1529L)).isTrue();
        assertThat(bounded.contains(1530L)).isFalse(); // after-credit boundary!
        assertThat(bounded.contains(1576L)).isFalse(); // after-credit scene protected

        // Invariants
        assertThatThrownBy(() -> new ScreenOutroMarker(1530L, 1530L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly greater");

        assertThatThrownBy(() -> new ScreenOutroMarker(1530L, 1452L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly greater");

        assertThatThrownBy(() -> ScreenOutroMarker.ofNullable(null, 1530L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startSeconds is null");
    }

    @Test
    @DisplayName("UX02-C2.1: Title default Outro with end boundary is inherited semantically by entry in INHERIT mode")
    void titleDefaultBoundedOutroIsInheritedSemantically() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        // 24:12 to 25:30 (1452s to 1530s)
        title.updateDefaultTimeline(15L, 105L, 1452L, 1530L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1576L, T0
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1452L);
        assertThat(effective.outroEndSeconds()).isEqualTo(1530L);
        assertThat(effective.outroMarker().hasEnd()).isTrue();
        assertThat(effective.outroMarker().contains(1500L)).isTrue();
        assertThat(effective.outroMarker().contains(1550L)).isFalse(); // After-credit scene protected
    }

    @Test
    @DisplayName("UX02-C2.1: Entry OVERRIDE with null end means video end and does NOT inherit title Outro end independently")
    void entryOverrideWithNullEndDoesNotInheritTitleOutroEnd() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        // Title has bounded Outro: 1452s to 1530s
        title.updateDefaultTimeline(15L, 105L, 1452L, 1530L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1576L, T0
        );
        // Entry overrides Outro to start at 1400s without end (unbounded)
        entry.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 1400L, null, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1400L);
        assertThat(effective.outroEndSeconds()).isNull(); // Must NOT inherit title's 1530L!
        assertThat(effective.outroMarker().hasEnd()).isFalse();
    }

    @Test
    @DisplayName("UX02-C2.1: Entry OVERRIDE with bounded Outro uses its own end")
    void entryOverrideWithBoundedOutroUsesItsOwnEnd() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        // Title has unbounded Outro at 1452s
        title.updateDefaultTimeline(15L, 105L, 1452L, null, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1576L, T0
        );
        // Entry overrides Outro with bounded 1420s to 1500s
        entry.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 1420L, 1500L, T1);

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasOutro()).isTrue();
        assertThat(effective.outroStartSeconds()).isEqualTo(1420L);
        assertThat(effective.outroEndSeconds()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("UX02-C2.1: EffectiveScreenTimeline suppresses Outro when candidate outroEnd exceeds known duration")
    void effectiveTimelineSuppressesOutroWhenOutroEndExceedsDuration() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "kiem-lai-s1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );
        // Duration is 1500s, but Outro end is 1530s
        title.updateDefaultTimeline(15L, 105L, 1400L, 1530L, T1);

        ScreenEntry entry = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "tap-1",
                ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, T0
        );

        EffectiveScreenTimeline effective = entry.resolveEffectiveTimeline(title);
        assertThat(effective.hasOutro()).isFalse(); // Safely suppressed due to exceeding duration
    }

    private static void assertTimelinePreserved(ScreenEntryDTO dto) {
        assertThat(dto.introMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(dto.introStartSeconds()).isEqualTo(20L);
        assertThat(dto.introEndSeconds()).isEqualTo(110L);
        assertThat(dto.outroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
        assertThat(dto.outroStartSeconds()).isEqualTo(1380L);
    }
}
