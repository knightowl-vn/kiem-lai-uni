package com.universe.screen.infrastructure.contract;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.dto.ScreenTitleNavigationDTO;
import com.universe.screen.contracts.dto.ScreenTitleReportTargetDTO;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenDiscussionContractAdapter Unit Tests")
class ScreenDiscussionContractAdapterTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    private ScreenDiscussionContractAdapter adapter;

    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TITLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENTRY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-03T20:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ScreenDiscussionContractAdapter(titleRepositoryPort, entryRepositoryPort);
    }

    @Test
    @DisplayName("Constructor should reject null dependencies")
    void constructorShouldRejectNullDependencies() {
        assertThatThrownBy(() -> new ScreenDiscussionContractAdapter(null, entryRepositoryPort))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenTitleRepositoryPort cannot be null.");

        assertThatThrownBy(() -> new ScreenDiscussionContractAdapter(titleRepositoryPort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenEntryRepositoryPort cannot be null.");
    }

    @Test
    @DisplayName("isPublishedTitle should return true when title exists and is published")
    void isPublishedTitleShouldReturnTrueWhenPublished() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));

        assertThat(adapter.isPublishedTitle(TITLE_ID)).isTrue();
    }

    @Test
    @DisplayName("isPublishedTitle should return false when title is not published, not found, or id is null")
    void isPublishedTitleShouldReturnFalseWhenNotPublishedOrNotFound() {
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        assertThat(adapter.isPublishedTitle(TITLE_ID)).isFalse();
        assertThat(adapter.isPublishedTitle(null)).isFalse();
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return true when title and entry are published and belong to same title")
    void isValidPublishedOriginShouldReturnTrueWhenTitleAndEntryArePublishedAndBelongToSameTitle() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        ScreenEntry entry = createEntry(ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedById(ENTRY_ID)).thenReturn(Optional.of(entry));

        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, ENTRY_ID)).isTrue();

        verify(titleRepositoryPort).findPublishedById(TITLE_ID);
        verify(entryRepositoryPort).findPublishedById(ENTRY_ID);
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return false and skip entry lookup when title is not published")
    void isValidPublishedOriginShouldReturnFalseAndShortCircuitWhenTitleIsNotPublished() {
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, ENTRY_ID)).isFalse();

        verify(titleRepositoryPort).findPublishedById(TITLE_ID);
        verifyNoInteractions(entryRepositoryPort);
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return false when title is draft or archived (rejected by published lookup)")
    void isValidPublishedOriginShouldReturnFalseWhenTitleIsDraftOrArchived() {
        // Per ScreenTitleRepositoryPort contract, findPublishedById returns Optional.empty() for DRAFT or ARCHIVED titles
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, ENTRY_ID)).isFalse();

        verify(titleRepositoryPort).findPublishedById(TITLE_ID);
        verifyNoInteractions(entryRepositoryPort);
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return false when entry belongs to a different title")
    void isValidPublishedOriginShouldReturnFalseWhenMismatchedTitle() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        ScreenEntry entry = createEntry(ENTRY_ID, OTHER_TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedById(ENTRY_ID)).thenReturn(Optional.of(entry));

        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, ENTRY_ID)).isFalse();

        verify(titleRepositoryPort).findPublishedById(TITLE_ID);
        verify(entryRepositoryPort).findPublishedById(ENTRY_ID);
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return false when published title exists but entry is unpublished or missing")
    void isValidPublishedOriginShouldReturnFalseWhenEntryIsUnpublishedOrMissing() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedById(ENTRY_ID)).thenReturn(Optional.empty());

        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, ENTRY_ID)).isFalse();

        verify(titleRepositoryPort).findPublishedById(TITLE_ID);
        verify(entryRepositoryPort).findPublishedById(ENTRY_ID);
    }

    @Test
    @DisplayName("isValidPublishedOrigin should return false without repository lookups when title or entry id is null")
    void isValidPublishedOriginShouldReturnFalseWhenParametersAreNull() {
        assertThat(adapter.isValidPublishedOrigin(null, ENTRY_ID)).isFalse();
        assertThat(adapter.isValidPublishedOrigin(TITLE_ID, null)).isFalse();
        assertThat(adapter.isValidPublishedOrigin(null, null)).isFalse();

        verifyNoInteractions(titleRepositoryPort);
        verifyNoInteractions(entryRepositoryPort);
    }

    @Test
    @DisplayName("findPublishedEntryOriginsByTitleId should map published entries with accurate badges")
    void findPublishedEntryOriginsByTitleIdShouldMapEntries() {
        UUID ep1Id = UUID.randomUUID();
        UUID featId = UUID.randomUUID();
        UUID spId = UUID.randomUUID();
        UUID trId = UUID.randomUUID();

        ScreenEntry ep1 = createEntry(ep1Id, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, ScreenPublicationStatus.PUBLISHED);
        ScreenEntry feat = createEntry(featId, TITLE_ID, "ban-chieu-chinh", ScreenEntryType.FEATURE, null, ScreenPublicationStatus.PUBLISHED);
        ScreenEntry sp = createEntry(spId, TITLE_ID, "ngoai-truyen-1", ScreenEntryType.SPECIAL, 1, ScreenPublicationStatus.PUBLISHED);
        ScreenEntry tr = createEntry(trId, TITLE_ID, "trailer-1", ScreenEntryType.TRAILER, 1, ScreenPublicationStatus.PUBLISHED);

        when(entryRepositoryPort.findPublishedByTitleId(TITLE_ID)).thenReturn(List.of(ep1, feat, sp, tr));

        Map<UUID, ScreenEntryOriginDTO> origins = adapter.findPublishedEntryOriginsByTitleId(TITLE_ID);

        assertThat(origins).hasSize(4);
        assertThat(origins.get(ep1Id)).isEqualTo(new ScreenEntryOriginDTO(ep1Id, "tap-1", "Tập 1"));
        assertThat(origins.get(featId)).isEqualTo(new ScreenEntryOriginDTO(featId, "ban-chieu-chinh", "Bản chiếu chính"));
        assertThat(origins.get(spId)).isEqualTo(new ScreenEntryOriginDTO(spId, "ngoai-truyen-1", "Ngoại truyện 1"));
        assertThat(origins.get(trId)).isEqualTo(new ScreenEntryOriginDTO(trId, "trailer-1", "Trailer 1"));
    }

    @Test
    @DisplayName("findPublishedEntryOriginsByTitleId should return empty map for null or empty title")
    void findPublishedEntryOriginsByTitleIdShouldHandleEmpty() {
        assertThat(adapter.findPublishedEntryOriginsByTitleId(null)).isEmpty();

        when(entryRepositoryPort.findPublishedByTitleId(TITLE_ID)).thenReturn(List.of());
        assertThat(adapter.findPublishedEntryOriginsByTitleId(TITLE_ID)).isEmpty();
    }

    @Test
    @DisplayName("findReportTargetTitlesByIds should map title targets for report queue")
    void findReportTargetTitlesByIdsShouldMapTargets() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findTitlesByIds(Set.of(TITLE_ID))).thenReturn(Map.of(TITLE_ID, title));

        Map<UUID, ScreenTitleReportTargetDTO> targets = adapter.findReportTargetTitlesByIds(Set.of(TITLE_ID));

        assertThat(targets).hasSize(1);
        ScreenTitleReportTargetDTO dto = targets.get(TITLE_ID);
        assertThat(dto.titleId()).isEqualTo(TITLE_ID);
        assertThat(dto.title()).isEqualTo("Kiếm Lai");
        assertThat(dto.slug()).isEqualTo("kiem-lai");
        assertThat(dto.status()).isEqualTo("PUBLISHED");
        assertThat(dto.published()).isTrue();
    }

    @Test
    @DisplayName("findReportTargetTitleById should resolve single title metadata")
    void findReportTargetTitleByIdShouldResolveSingle() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai", ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findById(TITLE_ID)).thenReturn(Optional.of(title));

        Optional<ScreenTitleReportTargetDTO> opt = adapter.findReportTargetTitleById(TITLE_ID);

        assertThat(opt).isPresent();
        assertThat(opt.get().title()).isEqualTo("Kiếm Lai");
        assertThat(opt.get().published()).isTrue();
    }

    @Test
    @DisplayName("findEntryBadgeForReport should format badge only if entry belongs to title")
    void findEntryBadgeForReportShouldFormatMatchingEntry() {
        ScreenEntry entry = createEntry(ENTRY_ID, TITLE_ID, "tap-2", ScreenEntryType.EPISODE, 2, ScreenPublicationStatus.PUBLISHED);
        when(entryRepositoryPort.findById(ENTRY_ID)).thenReturn(Optional.of(entry));

        Optional<String> badge = adapter.findEntryBadgeForReport(TITLE_ID, ENTRY_ID);
        assertThat(badge).contains("Tập 2");

        // When titleId does not match
        Optional<String> mismatched = adapter.findEntryBadgeForReport(OTHER_TITLE_ID, ENTRY_ID);
        assertThat(mismatched).isEmpty();

        // Null params
        assertThat(adapter.findEntryBadgeForReport(null, ENTRY_ID)).isEmpty();
        assertThat(adapter.findEntryBadgeForReport(TITLE_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("findPublishedNavigationByTitleId should resolve navigation only for published title with slug")
    void findPublishedNavigationByTitleIdShouldResolveNav() {
        ScreenTitle title = createTitle(TITLE_ID, "kiem-lai-1", ScreenPublicationStatus.PUBLISHED);
        when(titleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));

        Optional<ScreenTitleNavigationDTO> nav = adapter.findPublishedNavigationByTitleId(TITLE_ID);

        assertThat(nav).isPresent();
        assertThat(nav.get().titleId()).isEqualTo(TITLE_ID);
        assertThat(nav.get().slug()).isEqualTo("kiem-lai-1");

        // When not published
        when(titleRepositoryPort.findPublishedById(OTHER_TITLE_ID)).thenReturn(Optional.empty());
        assertThat(adapter.findPublishedNavigationByTitleId(OTHER_TITLE_ID)).isEmpty();

        // When null
        assertThat(adapter.findPublishedNavigationByTitleId(null)).isEmpty();
    }

    private static ScreenTitle createTitle(UUID id, String slug, ScreenPublicationStatus status) {
        return new ScreenTitle(
                id,
                slug,
                "Kiếm Lai",
                "Jian Lai",
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                status,
                NOW,
                NOW,
                NOW,
                24
        );
    }

    private static ScreenEntry createEntry(
            UUID id,
            UUID titleId,
            String slug,
            ScreenEntryType type,
            Integer number,
            ScreenPublicationStatus status
    ) {
        return new ScreenEntry(
                id,
                titleId,
                slug,
                type,
                number,
                "Tập " + number,
                "Synopsis",
                null,
                null,
                1200L,
                status,
                NOW,
                NOW,
                NOW
        );
    }
}
