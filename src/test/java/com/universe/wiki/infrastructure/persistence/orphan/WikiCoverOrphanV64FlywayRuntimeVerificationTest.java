package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
@DisplayName("Wiki Cover Orphan V64 Flyway Runtime Verification Tests (MySQL)")
class WikiCoverOrphanV64FlywayRuntimeVerificationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID id : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM wiki_cover_orphans WHERE media_asset_id = ?", id.toString());
        }
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Verify that status='DELETING' is valid under V64 check constraint")
    void shouldAllowDeletingStatusInDatabase() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);
        Instant now = Instant.now();

        // 1. Direct INSERT with status = 'DELETING'
        int inserted = jdbcTemplate.update("""
                INSERT INTO wiki_cover_orphans (
                    media_asset_id,
                    status,
                    first_seen_orphan_at,
                    claim_token,
                    locked_at,
                    retry_count,
                    last_error,
                    created_at,
                    updated_at
                ) VALUES (?, 'DELETING', ?, ?, ?, 0, NULL, ?, ?)
                """,
                assetId.toString(),
                Timestamp.from(now),
                UUID.randomUUID().toString(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        assertThat(inserted).isEqualTo(1);

        // 2. Query via JPA repository to verify entity mapping
        var found = jpaRepository.findById(assetId.toString());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(WikiCoverOrphanStatus.DELETING);

        // 3. Update from DELETING to another valid status or test transition
        int updated = jdbcTemplate.update("""
                UPDATE wiki_cover_orphans
                SET status = 'PROCESSING'
                WHERE media_asset_id = ?
                """, assetId.toString());
        assertThat(updated).isEqualTo(1);

        int updatedBack = jdbcTemplate.update("""
                UPDATE wiki_cover_orphans
                SET status = 'DELETING'
                WHERE media_asset_id = ?
                """, assetId.toString());
        assertThat(updatedBack).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify that invalid status is rejected by V64 check constraint")
    void shouldRejectInvalidStatusInDatabase() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);
        Instant now = Instant.now();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_cover_orphans (
                    media_asset_id,
                    status,
                    first_seen_orphan_at,
                    created_at,
                    updated_at
                ) VALUES (?, 'INVALID_STATUS', ?, ?, ?)
                """,
                assetId.toString(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        )).isInstanceOf(Exception.class)
          .hasMessageContaining("chk_wiki_cover_orphans_status");
    }
}
