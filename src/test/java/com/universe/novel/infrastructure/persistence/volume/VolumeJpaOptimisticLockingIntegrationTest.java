package com.universe.novel.infrastructure.persistence.volume;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Transactional(
        propagation = Propagation.NOT_SUPPORTED
)
class VolumeJpaOptimisticLockingIntegrationTest {

    private static final int TEST_SORT_ORDER =
            2_000_000_000;

    @Autowired
    private SpringDataVolumeJpaRepository repository;

    private String testVolumeId;

    @AfterEach
    void cleanUp() {
        if (testVolumeId == null) {
            return;
        }

        if (repository.existsById(
                testVolumeId
        )) {
            repository.deleteById(
                    testVolumeId
            );
        }
    }

    @Test
    @DisplayName(
            "Hibernate @Version từ chối stale detached Volume"
    )
    void shouldRejectStaleDetachedVolume() {
        testVolumeId =
                UUID.randomUUID()
                        .toString();

        VolumeJpaEntity initialEntity =
                createDraftEntity(
                        testVolumeId
                );

        VolumeJpaEntity inserted =
                repository.saveAndFlush(
                        initialEntity
                );

        Long initialPersistenceVersion =
                inserted.getPersistenceVersion();

        assertThat(
                initialPersistenceVersion
        ).isNotNull();

        /*
         * Hai lần đọc riêng biệt.
         *
         * Vì test không chạy trong một transaction bao ngoài,
         * mỗi repository call hoàn tất transaction riêng,
         * nên hai object sau sẽ là detached snapshots.
         */
        VolumeJpaEntity staleEntity =
                repository.findById(
                                testVolumeId
                        )
                        .orElseThrow();

        VolumeJpaEntity winningEntity =
                repository.findById(
                                testVolumeId
                        )
                        .orElseThrow();

        assertThat(
                staleEntity.getPersistenceVersion()
        ).isEqualTo(
                initialPersistenceVersion
        );

        assertThat(
                winningEntity.getPersistenceVersion()
        ).isEqualTo(
                initialPersistenceVersion
        );

        /*
         * Writer B thắng trước.
         */
        winningEntity.setTitle(
                "Quyển được cập nhật trước"
        );

        winningEntity.setAggregateVersion(
                2L
        );

        VolumeJpaEntity savedWinner =
                repository.saveAndFlush(
                        winningEntity
                );

        assertThat(
                savedWinner.getPersistenceVersion()
        ).isGreaterThan(
                initialPersistenceVersion
        );

        /*
         * Writer A vẫn giữ persistenceVersion cũ.
         *
         * Nếu @Version hoạt động đúng,
         * staleEntity không được phép ghi đè winner.
         */
        staleEntity.setTitle(
                "Dữ liệu stale không được ghi"
        );

        staleEntity.setAggregateVersion(
                2L
        );

        assertThatThrownBy(() ->
                repository.saveAndFlush(
                        staleEntity
                )
        )
                .isInstanceOf(
                        OptimisticLockingFailureException.class
                );

        /*
         * Xác nhận dữ liệu thắng trước vẫn còn nguyên,
         * tức stale writer thực sự không overwrite DB.
         */
        VolumeJpaEntity persisted =
                repository.findById(
                                testVolumeId
                        )
                        .orElseThrow();

        assertThat(
                persisted.getTitle()
        ).isEqualTo(
                "Quyển được cập nhật trước"
        );

        assertThat(
                persisted.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                persisted.getPersistenceVersion()
        ).isEqualTo(
                savedWinner.getPersistenceVersion()
        );
    }

    private VolumeJpaEntity createDraftEntity(
            String id
    ) {
        String actorId =
                UUID.randomUUID()
                        .toString();

        Instant now =
                Instant.now();

        VolumeJpaEntity entity =
                new VolumeJpaEntity();

        entity.setId(
                id
        );

        entity.setTitle(
                "Optimistic Lock Test"
        );

        entity.setSlug(
                "optimistic-lock-"
                        + id.replace(
                                "-",
                                ""
                        )
        );

        entity.setDescription(
                "Integration test cho Hibernate @Version."
        );

        entity.setSortOrder(
                TEST_SORT_ORDER
        );

        entity.setStatus(
                "DRAFT"
        );

        entity.setCreatedBy(
                actorId
        );

        entity.setUpdatedBy(
                actorId
        );

        entity.setAggregateVersion(
                1L
        );

        entity.setCreatedAt(
                now
        );

        entity.setUpdatedAt(
                now
        );

        return entity;
    }
}