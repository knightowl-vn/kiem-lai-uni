package com.universe.identity.infrastructure.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.universe.identity.application.ports.LegacyAvatarStoragePort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class CloudinaryAvatarStorageAdapter implements LegacyAvatarStoragePort {

    private static final String AVATAR_FOLDER = "kiemlai/avatars";

    private final Cloudinary cloudinary;

    public CloudinaryAvatarStorageAdapter(Cloudinary cloudinary) {
        this.cloudinary = Objects.requireNonNull(
                cloudinary,
                "Cloudinary không được để trống."
        );
    }

    @Override
    public boolean isLegacyAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return false;
        }

        String configuredCloudName = getConfiguredCloudName();
        if (configuredCloudName == null || configuredCloudName.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(avatarUrl.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (path == null) {
                return false;
            }

            String normalizedPath = path.toLowerCase(Locale.ROOT);

            boolean isStandardHostWithCloudPath = normalizedHost.equals("res.cloudinary.com")
                    && (normalizedPath.startsWith("/" + configuredCloudName + "/")
                    || normalizedPath.equals("/" + configuredCloudName));

            boolean isSubdomainHost = normalizedHost.equals(configuredCloudName + ".res.cloudinary.com");

            if (!isStandardHostWithCloudPath && !isSubdomainHost) {
                return false;
            }

            return path.contains("/" + AVATAR_FOLDER + "/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public void deleteAvatar(UUID userId) {
        validateUserId(userId);

        try {
            String publicId = buildPublicId(userId);

            Map<?, ?> result = cloudinary
                    .uploader()
                    .destroy(
                            publicId,
                            ObjectUtils.asMap(
                                    "resource_type",
                                    "image",
                                    "invalidate",
                                    true
                            )
                    );

            if (result == null) {
                throw new IllegalStateException("Cloudinary trả về kết quả rỗng khi xóa ảnh đại diện.");
            }

            String status = String.valueOf(result.get("result"));

            if (!"ok".equalsIgnoreCase(status) && !"not found".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Cloudinary xóa avatar thất bại: " + status);
            }

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể kết nối Cloudinary để xóa ảnh đại diện.",
                    exception
            );
        }
    }

    private String getConfiguredCloudName() {
        if (cloudinary.config != null && cloudinary.config.cloudName != null) {
            return cloudinary.config.cloudName.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String buildPublicId(UUID userId) {
        return AVATAR_FOLDER + "/" + userId;
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID không được để trống.");
        }
    }
}