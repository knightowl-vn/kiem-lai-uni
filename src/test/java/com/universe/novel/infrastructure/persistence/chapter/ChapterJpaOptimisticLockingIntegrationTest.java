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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ChapterJpaOptimisticLockingIntegrationTest {

    private static String resolveHost() {
        String host = System.getProperty("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = System.getenv("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        String dbHost = System.getenv("DB_HOST");
        if (dbHost != null && !dbHost.isBlank()) {
            return dbHost.trim();
        }
        return "localhost:3306";
    }

    private static String resolveDatabaseName() {
        String db = System.getProperty("test.mysql.db");
        if (db != null && !db.isBlank()) {
            return db.trim();
        }
        String envDb = System.getenv("TEST_MYSQL_DB");
        if (envDb != null && !envDb.isBlank()) {
            return envDb.trim();
        }
        String dbName = System.getenv("DB_NAME");
        if (dbName != null && !dbName.isBlank()) {
            return dbName.trim();
        }
        return "kiemlai_test";
    }

    private static String resolveUser() {
        String user = System.getProperty("test.mysql.user");
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        String envUser = System.getenv("TEST_MYSQL_USER");
        if (envUser != null && !envUser.isBlank()) {
            return envUser.trim();
        }
        String dbUser = System.getenv("DB_USERNAME");
        if (dbUser != null && !dbUser.isBlank()) {
            return dbUser.trim();
        }
        return "root";
    }

    private static String resolvePassword() {
        String pass = System.getProperty("test.mysql.pass");
        if (pass != null && !pass.isBlank()) {
            return pass;
        }
        String envPass = System.getenv("TEST_MYSQL_PASS");
        if (envPass != null && !envPass.isBlank()) {
            return envPass;
        }
        String rootPass = System.getenv("MYSQL_ROOT_PASSWORD");
        if (rootPass != null && !rootPass.isBlank()) {
            return rootPass;
        }
        throw new IllegalStateException(
                "MySQL integration test requires a database password. "
                        + "Please configure system property 'test.mysql.pass' or environment variable 'TEST_MYSQL_PASS' / 'MYSQL_ROOT_PASSWORD'."
        );
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = "jdbc:mysql://" + resolveHost() + "/" + resolveDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ChapterJpaOptimisticLockingIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ChapterJpaOptimisticLockingIntegrationTest::resolvePassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
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
