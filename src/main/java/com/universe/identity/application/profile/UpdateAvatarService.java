package com.universe.identity.application.profile;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UpdateAvatarService {

    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final UserRepositoryPort userRepository;
    private final MediaContract mediaContract;

    public UpdateAvatarService(
            UserRepositoryPort userRepository,
            MediaContract mediaContract
    ) {
        this.userRepository = Objects.requireNonNull(
                userRepository,
                "User repository không được để trống."
        );
        this.mediaContract = Objects.requireNonNull(
                mediaContract,
                "MediaContract không được để trống."
        );
    }

    public void execute(
            String currentUserEmail,
            InputStream content,
            long declaredSizeBytes,
            String contentType,
            String originalFilename
    ) {
        validateMetadata(content, declaredSizeBytes, contentType);

        Path spoolFile = null;
        try {
            try {
                spoolFile = Files.createTempFile("identity-avatar-spool-", ".tmp");
            } catch (IOException exception) {
                throw new IllegalStateException("Không thể tạo file tạm để xử lý ảnh đại diện.", exception);
            }

            long actualSizeBytes = 0;
            try (OutputStream out = Files.newOutputStream(spoolFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = content.read(buffer)) != -1) {
                    actualSizeBytes += bytesRead;
                    if (actualSizeBytes > MAX_FILE_SIZE) {
                        throw new IllegalArgumentException("Ảnh đại diện không được vượt quá 2 MB.");
                    }
                    out.write(buffer, 0, bytesRead);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Không thể ghi file tạm khi upload ảnh đại diện.", exception);
            }

            if (actualSizeBytes == 0) {
                throw new IllegalArgumentException("Vui lòng chọn một ảnh đại diện.");
            }

            Email email = new Email(normalizeEmail(currentUserEmail));
            User user = userRepository
                    .findByEmail(email)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Không tìm thấy tài khoản đang đăng nhập."
                            )
                    );

            UUID existingMediaAssetId = user.getAvatarMediaAssetId();
            String normalizedFilename = normalizeFilename(originalFilename);
            String normalizedContentType = contentType.trim().toLowerCase(Locale.ROOT);

            if (existingMediaAssetId == null) {
                // User has no Media avatar (first upload, Google external avatar, or legacy Cloudinary avatar)
                UploadMediaAssetResponseDTO mediaResponse;
                try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
                    UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                            uploadIn,
                            actualSizeBytes,
                            normalizedContentType,
                            MediaTypeDTO.IMAGE,
                            MediaVisibilityDTO.PUBLIC,
                            normalizedFilename
                    );
                    mediaResponse = mediaContract.uploadAsset(request);
                } catch (IOException exception) {
                    throw new IllegalStateException("Không thể đọc file tạm để upload lên Media.", exception);
                }

                UUID newAssetId = mediaResponse.assetId();
                String deliveryUrl = "/media/assets/" + newAssetId + "/content";

                user.updateMediaAvatar(newAssetId, deliveryUrl);

                try {
                    userRepository.save(user);
                } catch (Exception persistEx) {
                    try {
                        mediaContract.delete(newAssetId);
                    } catch (Exception compEx) {
                        persistEx.addSuppressed(compEx);
                    }
                    throw persistEx;
                }
            } else {
                // User already has a Media avatar -> upload new version to existing asset
                try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
                    UploadMediaAssetVersionRequestDTO request = new UploadMediaAssetVersionRequestDTO(
                            existingMediaAssetId,
                            uploadIn,
                            actualSizeBytes,
                            normalizedContentType,
                            normalizedFilename
                    );
                    mediaContract.uploadVersion(request);
                } catch (IOException exception) {
                    throw new IllegalStateException("Không thể đọc file tạm để upload version lên Media.", exception);
                }

                // A Media version replacement changes Media state only.
                // Identity already owns the stable avatarMediaAssetId and canonical URL.
                // No Identity domain state changes occur; zero database writes performed.
            }

        } finally {
            if (spoolFile != null) {
                try {
                    Files.deleteIfExists(spoolFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void validateMetadata(InputStream content, long declaredSizeBytes, String contentType) {
        if (content == null) {
            throw new IllegalArgumentException("Vui lòng chọn một ảnh đại diện.");
        }

        if (declaredSizeBytes <= 0) {
            throw new IllegalArgumentException("Vui lòng chọn một ảnh đại diện.");
        }

        if (declaredSizeBytes > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Ảnh đại diện không được vượt quá 2 MB.");
        }

        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Không xác định được email người dùng.");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "avatar";
        }
        return Path.of(originalFilename).getFileName().toString();
    }
}