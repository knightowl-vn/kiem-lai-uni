package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaAssetPersistenceAdapter.class
})
class MediaClientTagKeysetPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private SpringDataMediaAssetJpaRepository jpaRepository;

    @Autowired
    private MediaAssetPersistenceAdapter persistenceAdapter;

    private final List<String> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : createdAssetIds) {
            if (jpaRepository.existsById(id)) {
                jpaRepository.deleteById(id);
            }
        }
        createdAssetIds.clear();
    }

    private UUID createAsset(
            UUID assetId,
            String clientTag,
            MediaAssetStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        MediaAsset asset = MediaAsset.rehydrate(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                status,
                1,
                clientTag,
                createdAt,
                updatedAt
        );
        persistenceAdapter.save(asset);
        createdAssetIds.add(assetId.toString());
        return assetId;
    }

    @Test
    @DisplayName("Identical createdAt tie break: ordering by persisted ID ASC and cursor transition across same timestamp (Section 16)")
    void shouldOrderByIdAscWhenCreatedAtIsIdenticalAndTraverseCursorCorrectly() {
        String clientTag = "test.tiebreak." + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        Instant upperBound = Instant.parse("2026-09-01T12:00:00Z");

        // Create three deterministic IDs with known lexicographical ordering: A < B < C
        UUID idA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID idC = UUID.fromString("33333333-3333-3333-3333-333333333333");

        // Insert in reverse order to ensure DB index/ordering actually does the sorting
        createAsset(idC, clientTag, MediaAssetStatus.ACTIVE, t0, t0);
        createAsset(idA, clientTag, MediaAssetStatus.ACTIVE, t0, t0);
        createAsset(idB, clientTag, MediaAssetStatus.ACTIVE, t0, t0);

        // Page 1: pageSize = 2
        List<MediaAssetCandidate> page1 = persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                upperBound,
                null,
                null,
                2
        );

        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).assetId()).isEqualTo(idA);
        assertThat(page1.get(0).createdAt()).isEqualTo(t0);
        assertThat(page1.get(1).assetId()).isEqualTo(idB);
        assertThat(page1.get(1).createdAt()).isEqualTo(t0);

        // Advance cursor using last item of page 1 (idB at t0)
        MediaAssetCandidate lastItem = page1.get(1);
        List<MediaAssetCandidate> page2 = persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                upperBound,
                lastItem.createdAt(),
                lastItem.assetId(),
                2
        );

        // Page 2 must begin at idC without repeating idB or idA
        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).assetId()).isEqualTo(idC);
        assertThat(page2.get(0).createdAt()).isEqualTo(t0);

        // Page 3: advance from idC
        List<MediaAssetCandidate> page3 = persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                upperBound,
                page2.get(0).createdAt(),
                page2.get(0).assetId(),
                2
        );
        assertThat(page3).isEmpty();
    }

    @Test
    @DisplayName("Multi-page starvation regression: traversal across multiple pages reaches candidate beyond page 1 (Section 17)")
    void shouldTraverseMultiplePagesWithoutStarvation() {
        String clientTag = "test.starvation." + UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-09-01T08:00:00Z");
        Instant upperBound = Instant.parse("2026-09-01T12:00:00Z");

        // Create 7 active assets spanning 4 pages (pageSize = 2: 2 + 2 + 2 + 1)
        int totalAssets = 7;
        int pageSize = 2;
        List<UUID> createdUuids = new ArrayList<>();

        for (int i = 0; i < totalAssets; i++) {
            UUID id = UUID.randomUUID();
            Instant createdAt = baseTime.plusSeconds(i * 60);
            createAsset(id, clientTag, MediaAssetStatus.ACTIVE, createdAt, createdAt);
            createdUuids.add(id);
        }

        // Full keyset traversal
        List<MediaAssetCandidate> allDiscovered = new ArrayList<>();
        Instant cursorCreatedAt = null;
        UUID cursorAssetId = null;

        while (true) {
            List<MediaAssetCandidate> page = persistenceAdapter.findActiveByClientTagKeyset(
                    clientTag,
                    upperBound,
                    cursorCreatedAt,
                    cursorAssetId,
                    pageSize
            );

            if (page.isEmpty()) {
                break;
            }

            allDiscovered.addAll(page);

            if (page.size() < pageSize) {
                break;
            }

            MediaAssetCandidate last = page.get(page.size() - 1);
            cursorCreatedAt = last.createdAt();
            cursorAssetId = last.assetId();
        }

        // Verification: all 7 assets visited, target beyond page 1 is returned
        assertThat(allDiscovered).hasSize(totalAssets);

        List<UUID> discoveredUuids = allDiscovered.stream()
                .map(MediaAssetCandidate::assetId)
                .toList();

        assertThat(discoveredUuids).containsExactlyElementsOf(createdUuids);

        // Assets on page 2, 3, 4 were successfully reached (no starvation by page 1)
        assertThat(discoveredUuids.get(4)).isEqualTo(createdUuids.get(4));
        assertThat(discoveredUuids.get(6)).isEqualTo(createdUuids.get(6));
    }

    @Test
    @DisplayName("Filter regression: only matching clientTag + ACTIVE + created_at <= upperBound are returned (Section 18)")
    void shouldReturnOnlyMatchingActiveTaggedAssetsWithinUpperBound() {
        String targetTag = "wiki.article.cover." + UUID.randomUUID();
        String otherTag = "novel.chapter.illustration." + UUID.randomUUID();

        Instant tValid = Instant.parse("2026-09-01T09:00:00Z");
        Instant tUpper = Instant.parse("2026-09-01T10:00:00Z");
        Instant tTooNew = Instant.parse("2026-09-01T11:00:00Z");

        // 1. matching tag + ACTIVE + old enough (SHOULD BE INCLUDED)
        UUID eligibleId = UUID.randomUUID();
        createAsset(eligibleId, targetTag, MediaAssetStatus.ACTIVE, tValid, tValid);

        // 2. matching tag + ACTIVE + newer than upper bound (SHOULD BE EXCLUDED)
        UUID tooNewId = UUID.randomUUID();
        createAsset(tooNewId, targetTag, MediaAssetStatus.ACTIVE, tTooNew, tTooNew);

        // 3. matching tag + DELETED (SHOULD BE EXCLUDED)
        UUID deletedId = UUID.randomUUID();
        createAsset(deletedId, targetTag, MediaAssetStatus.DELETED, tValid, tValid);

        // 4. matching tag + ARCHIVED (SHOULD BE EXCLUDED)
        UUID archivedId = UUID.randomUUID();
        createAsset(archivedId, targetTag, MediaAssetStatus.ARCHIVED, tValid, tValid);

        // 5. different tag + ACTIVE (SHOULD BE EXCLUDED)
        UUID differentTagId = UUID.randomUUID();
        createAsset(differentTagId, otherTag, MediaAssetStatus.ACTIVE, tValid, tValid);

        // 6. null tag + ACTIVE (SHOULD BE EXCLUDED)
        UUID nullTagId = UUID.randomUUID();
        createAsset(nullTagId, null, MediaAssetStatus.ACTIVE, tValid, tValid);

        List<MediaAssetCandidate> result = persistenceAdapter.findActiveByClientTagKeyset(
                targetTag,
                tUpper,
                null,
                null,
                100
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).assetId()).isEqualTo(eligibleId);
        assertThat(result.get(0).createdAt()).isEqualTo(tValid);
    }

    @Test
    @DisplayName("Upper bound regression: createdAt < upperBound and == upperBound included, > upperBound excluded (Section 19)")
    void shouldRespectInclusiveUpperBoundSemantics() {
        String clientTag = "test.upperbound." + UUID.randomUUID();
        Instant tBefore = Instant.parse("2026-09-01T10:00:00Z");
        Instant tExact = Instant.parse("2026-09-01T11:00:00Z");
        Instant tAfter = Instant.parse("2026-09-01T12:00:00Z");

        UUID idBefore = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idExact = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID idAfter = UUID.fromString("33333333-3333-3333-3333-333333333333");

        createAsset(idBefore, clientTag, MediaAssetStatus.ACTIVE, tBefore, tBefore);
        createAsset(idExact, clientTag, MediaAssetStatus.ACTIVE, tExact, tExact);
        createAsset(idAfter, clientTag, MediaAssetStatus.ACTIVE, tAfter, tAfter);

        List<MediaAssetCandidate> result = persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                tExact, // Upper bound exactly matches tExact
                null,
                null,
                50
        );

        // Must include idBefore and idExact (inclusive <=), exclude idAfter
        assertThat(result).hasSize(2);
        assertThat(result.get(0).assetId()).isEqualTo(idBefore);
        assertThat(result.get(1).assetId()).isEqualTo(idExact);
    }

    @Test
    @DisplayName("No mutation invariant: query execution does not alter asset state, clientTag, or updatedAt (Section 22)")
    void shouldNotMutateAssetStateDuringQuery() {
        String clientTag = "test.nomutation." + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID assetId = UUID.randomUUID();

        createAsset(assetId, clientTag, MediaAssetStatus.ACTIVE, t0, t0);

        List<MediaAssetCandidate> result = persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                t0.plusSeconds(3600),
                null,
                null,
                10
        );

        assertThat(result).hasSize(1);

        // Reload asset directly from DB and assert pristine state
        MediaAssetJpaEntity entity = jpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getClientTag()).isEqualTo(clientTag);
        assertThat(entity.getCreatedAt()).isEqualTo(t0);
        assertThat(entity.getUpdatedAt()).isEqualTo(t0);
        assertThat(entity.getCurrentVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Partial cursor state is rejected by persistence adapter (Section 21)")
    void shouldRejectPartialCursorStateInAdapter() {
        String clientTag = "test.cursor." + UUID.randomUUID();
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                now,
                null,
                id,
                10
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");

        assertThatThrownBy(() -> persistenceAdapter.findActiveByClientTagKeyset(
                clientTag,
                now,
                now,
                null,
                10
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");
    }
}
