package com.universe.novel;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.application.profile.NovelCoverUpload;
import com.universe.novel.application.profile.UpdateNovelProfileCommand;
import com.universe.novel.application.profile.UpdateNovelProfileUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;
import com.universe.novel.infrastructure.persistence.profile.NovelProfilePersistenceAdapter;
import com.universe.novel.infrastructure.persistence.profile.SpringDataNovelProfileJpaRepository;
import com.universe.novel.infrastructure.persistence.reader.ReaderNovelLandingQueryPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.SpringDataVolumeJpaRepository;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        NovelProfilePersistenceAdapter.class,
        ReaderNovelLandingQueryPersistenceAdapter.class,
        GetNovelProfileUseCase.class,
        UpdateNovelProfileUseCase.class,
        MediaAssetPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        RegisterMediaAssetUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        GetMediaAssetDetailUseCase.class,
        ChangeMediaVisibilityUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        UploadMediaAssetUseCase.class,
        UploadMediaAssetVersionUseCase.class,
        GetMediaAssetContentUseCase.class,
        LocalFilesystemStorageAdapter.class,
        MediaFacade.class,
        NovelCoverMediaIntegrationTest.TestConfig.class
})
class NovelCoverMediaIntegrationTest {

    private static final byte[] FIRST_COVER_BYTES =
            "PNG_FIRST_COVER_IMAGE_DATA_123456789".getBytes(StandardCharsets.UTF_8);

    private static final byte[] SECOND_COVER_BYTES =
            "PNG_SECOND_COVER_IMAGE_DATA_REPLACEMENT_987654321".getBytes(StandardCharsets.UTF_8);

    private static final String LEGACY_COVER_URL =
            "https://res.cloudinary.com/demo/image/upload/v123456/kiemlai/novel/covers/legacy.jpg";

    private static Path tempStorageDir;
    private static final List<Path> createdTempDirs = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
        try {
            tempStorageDir = Files.createTempDirectory("novel-cover-media-it-");
            createdTempDirs.add(tempStorageDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("media.storage.local.root-dir", () -> tempStorageDir.toAbsolutePath().toString());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UpdateNovelProfileUseCase updateNovelProfileUseCase;

    @Autowired
    private GetNovelProfileUseCase getNovelProfileUseCase;

    @Autowired
    private ReaderNovelLandingQueryPersistenceAdapter readerNovelLandingQueryPersistenceAdapter;

    @Autowired
    private GetMediaAssetContentUseCase getMediaAssetContentUseCase;

    private record NovelProfileSnapshot(
            String title,
            String author,
            String description,
            String coverImageUrl,
            String coverMediaAssetId,
            String status,
            Object updatedAt
    ) {}

    private NovelProfileSnapshot originalNovelProfileSnapshot;
    private final List<UUID> createdMediaAssetIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpDatabaseState() {
        createdMediaAssetIds.clear();

        Map<String, Object> existingRow = jdbcTemplate.queryForMap(
                "SELECT title, author, description, cover_image_url, cover_media_asset_id, status, updated_at "
                        + "FROM novel_profile WHERE slug = 'kiem-lai'"
        );
        this.originalNovelProfileSnapshot = new NovelProfileSnapshot(
                (String) existingRow.get("title"),
                (String) existingRow.get("author"),
                (String) existingRow.get("description"),
                (String) existingRow.get("cover_image_url"),
                (String) existingRow.get("cover_media_asset_id"),
                (String) existingRow.get("status"),
                existingRow.get("updated_at")
        );

        jdbcTemplate.update(
                "UPDATE novel_profile SET cover_image_url = ?, cover_media_asset_id = NULL WHERE slug = 'kiem-lai'",
                LEGACY_COVER_URL
        );
    }

    @AfterEach
    void cleanUpDatabaseState() {
        for (UUID assetId : createdMediaAssetIds) {
            jdbcTemplate.update("DELETE FROM media_asset_versions WHERE asset_id = ?", assetId.toString());
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", assetId.toString());
        }
        createdMediaAssetIds.clear();

        if (originalNovelProfileSnapshot != null) {
            jdbcTemplate.update(
                    "UPDATE novel_profile SET title = ?, author = ?, description = ?, cover_image_url = ?, "
                            + "cover_media_asset_id = ?, status = ?, updated_at = ? WHERE slug = 'kiem-lai'",
                    originalNovelProfileSnapshot.title(),
                    originalNovelProfileSnapshot.author(),
                    originalNovelProfileSnapshot.description(),
                    originalNovelProfileSnapshot.coverImageUrl(),
                    originalNovelProfileSnapshot.coverMediaAssetId(),
                    originalNovelProfileSnapshot.status(),
                    originalNovelProfileSnapshot.updatedAt()
            );
        }
    }

    @AfterAll
    static void cleanUpStorage() {
        for (Path dir : createdTempDirs) {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Test
    @DisplayName("End-to-End: Khởi tạo legacy -> Upload ảnh bìa mới qua Media -> Thay thế ảnh bìa version mới -> Dual-read & Delivery")
    void shouldExecuteFullNovelCoverMediaAdoptionLifecycle() throws IOException {
        /*
         * 1. Trạng thái ban đầu: Novel Profile mang cover_image_url legacy, coverMediaAssetId == null.
         */
        NovelProfileDTO initialProfile = getNovelProfileUseCase.execute();
        assertThat(initialProfile.coverMediaAssetId()).isNull();
        assertThat(initialProfile.coverImageUrl()).isEqualTo(LEGACY_COVER_URL);
        assertThat(initialProfile.displayCoverImageUrl()).isEqualTo(LEGACY_COVER_URL);

        ReaderNovelOverviewDTO initialReaderOverview = readerNovelLandingQueryPersistenceAdapter
                .findNovelOverview()
                .orElseThrow();
        assertThat(initialReaderOverview.coverImageUrl()).isEqualTo(LEGACY_COVER_URL);

        /*
         * 2. Upload ảnh bìa đầu tiên qua Novel application flow:
         *    - Media tạo MediaAsset mới (PUBLIC, IMAGE).
         *    - Novel lưu coverMediaAssetId mới.
         *    - Raw cover_image_url legacy trong DB được bảo toàn nguyên vẹn.
         */
        NovelCoverUpload firstUpload = new NovelCoverUpload(
                new ByteArrayInputStream(FIRST_COVER_BYTES),
                FIRST_COVER_BYTES.length,
                "image/png",
                "first-cover.png"
        );

        UpdateNovelProfileCommand firstCommand = new UpdateNovelProfileCommand(
                "Kiếm Lai (Đệ Nhất)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả cập nhật đợt 1",
                "ONGOING",
                firstUpload
        );

        NovelProfileDTO afterFirstUploadProfile = updateNovelProfileUseCase.execute(firstCommand);

        UUID firstMediaAssetId = afterFirstUploadProfile.coverMediaAssetId();
        assertThat(firstMediaAssetId).isNotNull();
        createdMediaAssetIds.add(firstMediaAssetId);

        assertThat(afterFirstUploadProfile.coverImageUrl()).isEqualTo(LEGACY_COVER_URL);
        assertThat(afterFirstUploadProfile.displayCoverImageUrl())
                .isEqualTo("/media/assets/" + firstMediaAssetId + "/content");

        // Kiểm tra database trực tiếp
        Map<String, Object> novelProfileRow = jdbcTemplate.queryForMap(
                "SELECT cover_image_url, cover_media_asset_id FROM novel_profile WHERE slug = 'kiem-lai'"
        );
        assertThat(novelProfileRow.get("cover_image_url")).isEqualTo(LEGACY_COVER_URL);
        assertThat(novelProfileRow.get("cover_media_asset_id")).isEqualTo(firstMediaAssetId.toString());

        Map<String, Object> mediaAssetRow = jdbcTemplate.queryForMap(
                "SELECT media_type, visibility, status FROM media_assets WHERE id = ?",
                firstMediaAssetId.toString()
        );
        assertThat(mediaAssetRow.get("media_type")).isEqualTo("IMAGE");
        assertThat(mediaAssetRow.get("visibility")).isEqualTo("PUBLIC");
        assertThat(mediaAssetRow.get("status")).isEqualTo("ACTIVE");

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                "SELECT version_number, mime_type, size_bytes FROM media_asset_versions WHERE asset_id = ? ORDER BY version_number ASC",
                firstMediaAssetId.toString()
        );
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("version_number")).isEqualTo(1);
        assertThat(versions.get(0).get("mime_type")).isEqualTo("image/png");
        assertThat(((Number) versions.get(0).get("size_bytes")).longValue()).isEqualTo((long) FIRST_COVER_BYTES.length);

        // Reader display mapping
        ReaderNovelOverviewDTO readerOverviewAfterFirst = readerNovelLandingQueryPersistenceAdapter
                .findNovelOverview()
                .orElseThrow();
        assertThat(readerOverviewAfterFirst.coverImageUrl())
                .isEqualTo("/media/assets/" + firstMediaAssetId + "/content");

        // Media delivery trả về đúng bytes của ảnh bìa đầu tiên
        GetMediaAssetContentResult firstContentResult = getMediaAssetContentUseCase.execute(
                new GetMediaAssetContentQuery(firstMediaAssetId)
        );
        assertThat(firstContentResult.mimeType()).isEqualTo("image/png");
        assertThat(firstContentResult.sizeBytes()).isEqualTo((long) FIRST_COVER_BYTES.length);
        try (InputStream in = firstContentResult.content()) {
            byte[] deliveredBytes = in.readAllBytes();
            assertThat(deliveredBytes).isEqualTo(FIRST_COVER_BYTES);
        }

        /*
         * 3. Thay thế ảnh bìa (Replacement cover upload):
         *    - Media tạo version 2 cho CÙNG assetId.
         *    - Novel giữ nguyên coverMediaAssetId.
         *    - Raw cover_image_url legacy trong DB tiếp tục được bảo toàn.
         */
        NovelCoverUpload secondUpload = new NovelCoverUpload(
                new ByteArrayInputStream(SECOND_COVER_BYTES),
                SECOND_COVER_BYTES.length,
                "image/png",
                "second-cover.png"
        );

        UpdateNovelProfileCommand secondCommand = new UpdateNovelProfileCommand(
                "Kiếm Lai (Đệ Nhị)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả cập nhật đợt 2",
                "ONGOING",
                secondUpload
        );

        NovelProfileDTO afterSecondUploadProfile = updateNovelProfileUseCase.execute(secondCommand);

        assertThat(afterSecondUploadProfile.coverMediaAssetId()).isEqualTo(firstMediaAssetId);
        assertThat(afterSecondUploadProfile.coverImageUrl()).isEqualTo(LEGACY_COVER_URL);
        assertThat(afterSecondUploadProfile.displayCoverImageUrl())
                .isEqualTo("/media/assets/" + firstMediaAssetId + "/content");

        // Kiểm tra database: 2 versions cùng thuộc về firstMediaAssetId
        List<Map<String, Object>> versionsAfterSecond = jdbcTemplate.queryForList(
                "SELECT version_number, size_bytes FROM media_asset_versions WHERE asset_id = ? ORDER BY version_number ASC",
                firstMediaAssetId.toString()
        );
        assertThat(versionsAfterSecond).hasSize(2);
        assertThat(versionsAfterSecond.get(0).get("version_number")).isEqualTo(1);
        assertThat(versionsAfterSecond.get(1).get("version_number")).isEqualTo(2);
        assertThat(((Number) versionsAfterSecond.get(1).get("size_bytes")).longValue())
                .isEqualTo((long) SECOND_COVER_BYTES.length);

        // Media delivery hiện tại trả về bytes của version mới nhất (version 2)
        GetMediaAssetContentResult secondContentResult = getMediaAssetContentUseCase.execute(
                new GetMediaAssetContentQuery(firstMediaAssetId)
        );
        assertThat(secondContentResult.mimeType()).isEqualTo("image/png");
        assertThat(secondContentResult.sizeBytes()).isEqualTo((long) SECOND_COVER_BYTES.length);
        try (InputStream in = secondContentResult.content()) {
            byte[] deliveredBytes = in.readAllBytes();
            assertThat(deliveredBytes).isEqualTo(SECOND_COVER_BYTES);
        }
    }
}
