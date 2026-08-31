package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.infrastructure.persistence.volume.SpringDataVolumeJpaRepository;
import com.universe.novel.infrastructure.persistence.volume.VolumeJpaEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.universe.test.TestDatabaseSupport;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ChapterJpaOptimisticLockingIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final int TEST_SORT_ORDER = 2_000_000_000;
    private static final int TEST_CHAPTER_NUMBER = 2_000_000_000;

    @Autowired
    private SpringDataChapterJpaRepository chapterRepository;

    @Autowired
    private SpringDataVolumeJpaRepository volumeRepository;

    private String testVolumeId;
    private String testChapterId;

    @AfterEach
    void cleanUp() {
        if (testChapterId != null && chapterRepository.existsById(testChapterId)) {
            chapterRepository.deleteById(testChapterId);
        }
        if (testVolumeId != null && volumeRepository.existsById(testVolumeId)) {
            volumeRepository.deleteById(testVolumeId);
        }
    }

    @Test
    @DisplayName("Hibernate @Version từ chối stale detached Chapter")
    void shouldRejectStaleDetachedChapter() {
        testVolumeId = UUID.randomUUID().toString();
        testChapterId = UUID.randomUUID().toString();

        VolumeJpaEntity volumeEntity = createVolumeEntity(testVolumeId);
        volumeRepository.saveAndFlush(volumeEntity);

        ChapterJpaEntity initialEntity = createDraftChapterEntity(testChapterId, testVolumeId);
        ChapterJpaEntity inserted = chapterRepository.saveAndFlush(initialEntity);

        Long initialPersistenceVersion = inserted.getPersistenceVersion();
        assertThat(initialPersistenceVersion).isNotNull();

        /*
         * Hai lần đọc riêng biệt tạo hai detached snapshots độc lập.
         */
        ChapterJpaEntity staleEntity = chapterRepository.findById(testChapterId).orElseThrow();
        ChapterJpaEntity winningEntity = chapterRepository.findById(testChapterId).orElseThrow();

        assertThat(staleEntity.getPersistenceVersion()).isEqualTo(initialPersistenceVersion);
        assertThat(winningEntity.getPersistenceVersion()).isEqualTo(initialPersistenceVersion);

        /*
         * Writer B thắng trước: cập nhật và saveAndFlush.
         */
        winningEntity.setTitle("Chương được cập nhật trước");
        winningEntity.setContent("Nội dung được cập nhật trước");
        winningEntity.setAggregateVersion(2L);
        winningEntity.setContentVersion(2L);

        ChapterJpaEntity savedWinner = chapterRepository.saveAndFlush(winningEntity);
        assertThat(savedWinner.getPersistenceVersion()).isGreaterThan(initialPersistenceVersion);

        /*
         * Writer A vẫn giữ persistenceVersion cũ:
         * Hibernate @Version phải từ chối staleEntity và ném OptimisticLockingFailureException.
         */
        staleEntity.setTitle("Dữ liệu stale không được ghi");
        staleEntity.setContent("Nội dung stale không được ghi");
        staleEntity.setAggregateVersion(2L);
        staleEntity.setContentVersion(2L);

        assertThatThrownBy(() -> chapterRepository.saveAndFlush(staleEntity))
                .isInstanceOf(OptimisticLockingFailureException.class);

        /*
         * Xác nhận dữ liệu của Writer B vẫn còn nguyên trong DB.
         */
        ChapterJpaEntity persisted = chapterRepository.findById(testChapterId).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("Chương được cập nhật trước");
        assertThat(persisted.getContent()).isEqualTo("Nội dung được cập nhật trước");
        assertThat(persisted.getAggregateVersion()).isEqualTo(2L);
        assertThat(persisted.getContentVersion()).isEqualTo(2L);
        assertThat(persisted.getPersistenceVersion()).isEqualTo(savedWinner.getPersistenceVersion());
    }

    private VolumeJpaEntity createVolumeEntity(String id) {
        String actorId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        VolumeJpaEntity entity = new VolumeJpaEntity();
        entity.setId(id);
        entity.setTitle("Chapter Optimistic Lock Volume");
        entity.setSlug("ch-opt-lock-vol-" + id.replace("-", ""));
        entity.setDescription("Volume cho Chapter optimistic lock test.");
        entity.setSortOrder(TEST_SORT_ORDER);
        entity.setStatus("PUBLISHED");
        entity.setCreatedBy(actorId);
        entity.setUpdatedBy(actorId);
        entity.setPublishedBy(actorId);
        entity.setAggregateVersion(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setPublishedAt(now);
        return entity;
    }

    private ChapterJpaEntity createDraftChapterEntity(String chapterId, String volumeId) {
        String actorId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ChapterJpaEntity entity = new ChapterJpaEntity();
        entity.setId(chapterId);
        entity.setVolumeId(volumeId);
        entity.setChapterNumber(TEST_CHAPTER_NUMBER);
        entity.setTitle("Chapter Optimistic Lock Test");
        entity.setSlug("ch-opt-lock-" + chapterId.replace("-", ""));
        entity.setSummary("Tóm tắt optimistic lock test");
        entity.setContent("Nội dung ban đầu của chapter.");
        entity.setStatus("DRAFT");
        entity.setCreatedBy(actorId);
        entity.setUpdatedBy(actorId);
        entity.setAggregateVersion(1L);
        entity.setContentVersion(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
