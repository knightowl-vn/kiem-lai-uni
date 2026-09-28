package com.universe.identity;

import com.cloudinary.Cloudinary;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.oauth.GoogleUserInfo;
import com.universe.identity.application.profile.DeleteAvatarService;
import com.universe.identity.application.profile.UpdateAvatarService;
import com.universe.identity.contracts.admin.dto.AdminUserDetailView;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.persistence.CurrentUserQueryAdapter;
import com.universe.identity.infrastructure.persistence.IdentityAdminQueryAdapter;
import com.universe.identity.infrastructure.persistence.UserPersistenceMapper;
import com.universe.identity.infrastructure.persistence.UserRepositoryAdapter;
import com.universe.identity.infrastructure.storage.CloudinaryAvatarStorageAdapter;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RasterContentSignatureValidator;
import com.universe.media.application.asset.RegisterMediaAssetUseCase;
import com.universe.media.application.asset.RegisterMediaAssetVersionUseCase;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.facade.MediaFacade;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetCurrentMetadataQueryPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetContentDeliveryQueryPersistenceAdapter;
import com.universe.media.infrastructure.persistence.MediaAssetVersionPersistenceAdapter;
import com.universe.media.infrastructure.storage.DefaultStorageProviderRegistry;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        UserRepositoryAdapter.class,
        UserPersistenceMapper.class,
        CurrentUserQueryAdapter.class,
        IdentityAdminQueryAdapter.class,
        UpdateAvatarService.class,
        DeleteAvatarService.class,
        GoogleOAuthUserService.class,
        MediaAssetPersistenceAdapter.class,
        MediaAssetContentDeliveryQueryPersistenceAdapter.class,
        MediaAssetCurrentMetadataQueryPersistenceAdapter.class,
        MediaAssetVersionPersistenceAdapter.class,
        RegisterMediaAssetUseCase.class,
        RegisterMediaAssetVersionUseCase.class,
        GetMediaAssetDetailUseCase.class,
        GetMediaAssetCurrentMetadataUseCase.class,
        GetCurrentMediaAssetVersionSnapshotUseCase.class,
        OpenMediaAssetVersionContentUseCase.class,
        ChangeMediaVisibilityUseCase.class,
        ArchiveMediaAssetUseCase.class,
        RestoreMediaAssetUseCase.class,
        DeleteMediaAssetUseCase.class,
        AssignMediaAssetClientTagUseCase.class,
        com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase.class,
        UploadMediaAssetUseCase.class,
        UploadMediaAssetVersionUseCase.class,
        com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase.class,
        GetMediaAssetContentUseCase.class,
        LocalFilesystemStorageAdapter.class,
        MediaStorageRoutingService.class,
        DefaultStorageProviderRegistry.class,
        com.universe.media.infrastructure.persistence.MediaImageVariantPersistenceAdapter.class,
        com.universe.media.infrastructure.image.JavaImageProcessorAdapter.class,
        com.universe.media.application.variant.GenerateMediaImageVariantUseCase.class,
        RasterContentSignatureValidator.class,
        MediaFacade.class,
        IdentityAvatarMediaIntegrationTest.TestConfig.class
})
class IdentityAvatarMediaIntegrationTest {

    private static byte[] createPngBytes(int width, int height, java.awt.Color color) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    width,
                    height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB
            );
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(color);
            g.fillRect(0, 0, width, height);
            g.dispose();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte[] AVATAR_IMAGE_BYTES_V1 =
            createPngBytes(100, 100, java.awt.Color.BLUE);

    private static final byte[] AVATAR_IMAGE_BYTES_V2 =
            createPngBytes(200, 200, java.awt.Color.GREEN);

    private static Path tempStorageDir;
    private static final List<Path> createdTempDirs = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
        try {
            tempStorageDir = Files.createTempDirectory("identity-avatar-media-it-");
            createdTempDirs.add(tempStorageDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("media.storage.local.root-dir", () -> tempStorageDir.toAbsolutePath().toString());
    }

    @AfterAll
    static void cleanUpTempStorage() {
        for (Path dir : createdTempDirs) {
            if (dir != null && Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // best effort deletion
                                }
                            });
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return UUID::randomUUID;
        }

        @Bean
        public OutboxPort outboxPort() {
            return mock(OutboxPort.class);
        }

        @Bean
        public Cloudinary cloudinary() {
            return Mockito.spy(new Cloudinary(Map.of("cloud_name", "kiemlai")));
        }

        @Bean
        public CloudinaryAvatarStorageAdapter cloudinaryAvatarStorageAdapter(Cloudinary cloudinary) {
            return new CloudinaryAvatarStorageAdapter(cloudinary);
        }

        @Bean
        public BinaryStoragePort cloudinaryTestStoragePort() {
            return new TestCloudinaryStoragePort(tempStorageDir);
        }
    }

    static class TestCloudinaryStoragePort implements BinaryStoragePort {
        private final LocalFilesystemStorageAdapter delegate;

        public TestCloudinaryStoragePort(Path rootDir) {
            this.delegate = new LocalFilesystemStorageAdapter(rootDir);
        }

        @Override
        public StorageProviderId providerId() {
            return StorageProviderId.of("cloudinary");
        }

        @Override
        public StoredBinaryObject store(StorageKey storageKey, InputStream content, long sizeBytes, MimeType mimeType) {
            delegate.store(storageKey, content, sizeBytes, mimeType);
            return StoredBinaryObject.of(StorageLocation.of(providerId(), storageKey));
        }

        @Override
        public InputStream open(StorageKey storageKey) {
            return delegate.open(storageKey);
        }

        @Override
        public InputStream openRange(StorageKey storageKey, long startInclusive, long length) {
            return delegate.openRange(storageKey, startInclusive, length);
        }

        @Override
        public void delete(StorageKey storageKey) {
            delegate.delete(storageKey);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private CurrentUserQueryAdapter currentUserQueryAdapter;

    @Autowired
    private IdentityAdminQueryAdapter identityAdminQueryAdapter;

    @Autowired
    private UpdateAvatarService updateAvatarService;

    @Autowired
    private DeleteAvatarService deleteAvatarService;

    @Autowired
    private GoogleOAuthUserService googleOAuthUserService;

    @Autowired
    private MediaContract mediaContract;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
        jdbcTemplate.update("DELETE FROM media_image_variants");
        jdbcTemplate.update("DELETE FROM media_asset_versions");
        jdbcTemplate.update("DELETE FROM media_assets");
    }

    @Test
    @DisplayName("full Media avatar lifecycle: upload -> persist -> read views -> replace version -> delete")
    void shouldPersistAndManageMediaAvatarLifecycle() {
        UUID userId = UUID.randomUUID();
        String emailStr = "athena@example.com";
        User initialUser = User.createLocal(
                userId,
                new Email(emailStr),
                "$2a$10$hashedPassword1234567890",
                "Athena",
                Instant.now()
        );
        userRepositoryAdapter.save(initialUser);

        // 1. Upload initial Media avatar
        updateAvatarService.execute(
                emailStr,
                new ByteArrayInputStream(AVATAR_IMAGE_BYTES_V1),
                AVATAR_IMAGE_BYTES_V1.length,
                "image/png",
                "avatar.png"
        );

        // 2. Reload and verify Domain state
        User userAfterUpload = userRepositoryAdapter.findByEmail(new Email(emailStr)).orElseThrow();
        UUID mediaAssetId = userAfterUpload.getAvatarMediaAssetId();
        assertThat(mediaAssetId).isNotNull();
        String expectedUrl = "/media/assets/" + mediaAssetId + "/content";
        assertThat(userAfterUpload.getAvatarUrl()).isEqualTo(expectedUrl);
        assertThat(userAfterUpload.isAvatarCustomized()).isTrue();

        // 3. Verify Database Row
        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "SELECT avatar_media_asset_id, avatar_url, avatar_customized FROM identity_users WHERE id = ?",
                userId.toString()
        );
        assertThat(userRow.get("avatar_media_asset_id")).isEqualTo(mediaAssetId.toString());
        assertThat(userRow.get("avatar_url")).isEqualTo(expectedUrl);
        assertThat((Boolean) userRow.get("avatar_customized")).isTrue();

        // 4. Verify CurrentUserView & AdminUserDetailView compatibility
        CurrentUserView currentUserView = currentUserQueryAdapter.findByEmail(emailStr).orElseThrow();
        assertThat(currentUserView.avatarUrl()).isEqualTo(expectedUrl);

        AdminUserDetailView adminView = identityAdminQueryAdapter.findUserDetail(userId).orElseThrow();
        assertThat(adminView.avatarUrl()).isEqualTo(expectedUrl);

        // 5. Verify Media platform status
        Optional<MediaAssetDetailDTO> mediaDetail = mediaContract.getAssetDetail(mediaAssetId);
        assertThat(mediaDetail).isPresent();
        assertThat(mediaDetail.get().status()).isEqualTo(MediaAssetStatusDTO.ACTIVE);
        assertThat(mediaDetail.get().currentVersionNumber()).isEqualTo(1);

        // 6a. Upload identical avatar binary (MS-05G9 dedupe): Media version unchanged, 0 new versions
        long userAggregateVersionBeforeSameUpload = userAfterUpload.getAggregateVersion();
        updateAvatarService.execute(
                emailStr,
                new ByteArrayInputStream(AVATAR_IMAGE_BYTES_V1),
                AVATAR_IMAGE_BYTES_V1.length,
                "image/png",
                "avatar-same.png"
        );

        User userAfterSameUpload = userRepositoryAdapter.findByEmail(new Email(emailStr)).orElseThrow();
        assertThat(userAfterSameUpload.getAvatarMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(userAfterSameUpload.getAvatarUrl()).isEqualTo(expectedUrl);
        assertThat(userAfterSameUpload.getAggregateVersion()).isEqualTo(userAggregateVersionBeforeSameUpload);

        MediaAssetDetailDTO mediaDetailAfterSame = mediaContract.getAssetDetail(mediaAssetId).orElseThrow();
        assertThat(mediaDetailAfterSame.currentVersionNumber()).isEqualTo(1);
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_asset_versions WHERE asset_id = ?",
                Integer.class,
                mediaAssetId.toString()
        );
        assertThat(versionCount).isEqualTo(1);

        // 6b. Replace avatar via new Media version (different bytes)
        updateAvatarService.execute(
                emailStr,
                new ByteArrayInputStream(AVATAR_IMAGE_BYTES_V2),
                AVATAR_IMAGE_BYTES_V2.length,
                "image/png",
                "avatar-v2.png"
        );

        // Verify Identity reference remains stable
        User userAfterReplace = userRepositoryAdapter.findByEmail(new Email(emailStr)).orElseThrow();
        assertThat(userAfterReplace.getAvatarMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(userAfterReplace.getAvatarUrl()).isEqualTo(expectedUrl);

        MediaAssetDetailDTO mediaDetailV2 = mediaContract.getAssetDetail(mediaAssetId).orElseThrow();
        assertThat(mediaDetailV2.currentVersionNumber()).isEqualTo(2);

        // 7. Delete avatar
        deleteAvatarService.execute(emailStr);

        User userAfterDelete = userRepositoryAdapter.findByEmail(new Email(emailStr)).orElseThrow();
        assertThat(userAfterDelete.getAvatarMediaAssetId()).isNull();
        assertThat(userAfterDelete.getAvatarUrl()).isNull();
        assertThat(userAfterDelete.isAvatarCustomized()).isTrue();

        // Database row is cleared
        Map<String, Object> deletedUserRow = jdbcTemplate.queryForMap(
                "SELECT avatar_media_asset_id, avatar_url, avatar_customized FROM identity_users WHERE id = ?",
                userId.toString()
        );
        assertThat(deletedUserRow.get("avatar_media_asset_id")).isNull();
        assertThat(deletedUserRow.get("avatar_url")).isNull();
        assertThat((Boolean) deletedUserRow.get("avatar_customized")).isTrue();

        // Media asset transitioned to DELETED
        MediaAssetDetailDTO deletedMediaDetail = mediaContract.getAssetDetail(mediaAssetId).orElseThrow();
        assertThat(deletedMediaDetail.status()).isEqualTo(MediaAssetStatusDTO.DELETED);
    }

    @Test
    @DisplayName("Google external avatar remains compatible and respects user customization boundaries")
    void shouldHandleGoogleOAuthAvatarCompatibilityAndCustomizationProtection() {
        String email = "oauth.user@example.com";
        String googleAvatarUrl = "https://lh3.googleusercontent.com/a/sample-avatar-id.png";

        // 1. Initial Google Login creates user with Google avatar and avatarCustomized = false
        GoogleUserInfo googleUserInfo = new GoogleUserInfo(
                "google-subject-12345",
                email,
                "Google User",
                googleAvatarUrl,
                true
        );
        User googleUser = googleOAuthUserService.findOrCreateGoogleUser(googleUserInfo);
        assertThat(googleUser.getAvatarMediaAssetId()).isNull();
        assertThat(googleUser.getAvatarUrl()).isEqualTo(googleAvatarUrl);
        assertThat(googleUser.isAvatarCustomized()).isFalse();

        // Verify views
        CurrentUserView view = currentUserQueryAdapter.findByEmail(email).orElseThrow();
        assertThat(view.avatarUrl()).isEqualTo(googleAvatarUrl);

        // 2. User uploads a custom Media avatar
        updateAvatarService.execute(
                email,
                new ByteArrayInputStream(AVATAR_IMAGE_BYTES_V1),
                AVATAR_IMAGE_BYTES_V1.length,
                "image/png",
                "avatar.png"
        );

        User userWithCustomAvatar = userRepositoryAdapter.findByEmail(new Email(email)).orElseThrow();
        UUID customMediaId = userWithCustomAvatar.getAvatarMediaAssetId();
        assertThat(customMediaId).isNotNull();
        String customMediaUrl = "/media/assets/" + customMediaId + "/content";
        assertThat(userWithCustomAvatar.getAvatarUrl()).isEqualTo(customMediaUrl);
        assertThat(userWithCustomAvatar.isAvatarCustomized()).isTrue();

        // 3. Subsequent Google Login must NOT overwrite customized Media avatar
        GoogleUserInfo subsequentLoginInfo = new GoogleUserInfo(
                "google-subject-12345",
                email,
                "Google User Updated",
                "https://lh3.googleusercontent.com/a/another-avatar.png",
                true
        );
        User loginResult = googleOAuthUserService.findOrCreateGoogleUser(subsequentLoginInfo);
        assertThat(loginResult.getAvatarMediaAssetId()).isEqualTo(customMediaId);
        assertThat(loginResult.getAvatarUrl()).isEqualTo(customMediaUrl);

        // 4. User explicitly deletes avatar
        deleteAvatarService.execute(email);
        User userAfterRemove = userRepositoryAdapter.findByEmail(new Email(email)).orElseThrow();
        assertThat(userAfterRemove.getAvatarMediaAssetId()).isNull();
        assertThat(userAfterRemove.getAvatarUrl()).isNull();
        assertThat(userAfterRemove.isAvatarCustomized()).isTrue();

        // 5. Subsequent Google Login must NOT restore Google avatar after explicit user removal
        User loginAfterRemove = googleOAuthUserService.findOrCreateGoogleUser(subsequentLoginInfo);
        assertThat(loginAfterRemove.getAvatarMediaAssetId()).isNull();
        assertThat(loginAfterRemove.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("legacy null-media-ID row with Cloudinary URL remains compatible in persistence and views")
    void shouldHandleLegacyCloudinaryRowCompatibility() {
        UUID userId = UUID.randomUUID();
        String email = "legacy@example.com";
        String legacyCloudinaryUrl = "https://res.cloudinary.com/kiemlai/image/upload/v123/kiemlai/avatars/" + userId + ".jpg";

        User legacyUser = User.rehydrate(
                userId,
                email,
                "$2a$10$hash",
                "Legacy User",
                null,
                legacyCloudinaryUrl,
                true,
                "Legacy bio",
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                1L,
                Instant.now()
        );
        userRepositoryAdapter.save(legacyUser);

        // Reload from DB
        User reloaded = userRepositoryAdapter.findByEmail(new Email(email)).orElseThrow();
        assertThat(reloaded.getAvatarMediaAssetId()).isNull();
        assertThat(reloaded.getAvatarUrl()).isEqualTo(legacyCloudinaryUrl);

        // View queries
        CurrentUserView view = currentUserQueryAdapter.findByEmail(email).orElseThrow();
        assertThat(view.avatarUrl()).isEqualTo(legacyCloudinaryUrl);

        AdminUserDetailView adminView = identityAdminQueryAdapter.findUserDetail(userId).orElseThrow();
        assertThat(adminView.avatarUrl()).isEqualTo(legacyCloudinaryUrl);
    }

    @Test
    @DisplayName("verifies no cross-module foreign key exists between identity_users and media_assets in MySQL")
    void shouldVerifyNoCrossModuleForeignKeyOnIdentityUsers() {
        List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME " +
                        "FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = 'identity_users' " +
                        "AND COLUMN_NAME = 'avatar_media_asset_id'"
        );

        assertThat(foreignKeys)
                .as("identity_users.avatar_media_asset_id must not have foreign keys to media_assets")
                .allMatch(row -> row.get("REFERENCED_TABLE_NAME") == null);
    }
}
