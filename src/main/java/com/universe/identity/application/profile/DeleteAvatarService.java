package com.universe.identity.application.profile;

import com.universe.identity.application.ports.AvatarStoragePort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeleteAvatarService {

    private final UserRepositoryPort userRepository;
    private final AvatarStoragePort avatarStorage;
    private final MediaContract mediaContract;

    public DeleteAvatarService(
            UserRepositoryPort userRepository,
            AvatarStoragePort avatarStorage,
            MediaContract mediaContract
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "User repository không được để trống.");
        this.avatarStorage = Objects.requireNonNull(avatarStorage, "Avatar storage không được để trống.");
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract không được để trống.");
    }

    public void execute(String currentUserEmail) {
        Email email = new Email(normalizeEmail(currentUserEmail));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản đang đăng nhập."));

        UUID mediaAssetId = user.getAvatarMediaAssetId();
        String currentAvatarUrl = user.getAvatarUrl();

        if (mediaAssetId != null) {
            // 1. Media-backed avatar
            Optional<MediaAssetDetailDTO> maybeDetail = mediaContract.getAssetDetail(mediaAssetId);
            if (maybeDetail.isEmpty()) {
                throw new IllegalStateException("Không tìm thấy thông tin Media asset của ảnh đại diện: " + mediaAssetId);
            }

            MediaAssetStatusDTO status = maybeDetail.get().status();
            if (status == MediaAssetStatusDTO.ACTIVE || status == MediaAssetStatusDTO.ARCHIVED) {
                mediaContract.delete(mediaAssetId);
            } else if (status == MediaAssetStatusDTO.DELETED) {
                // Already DELETED in Media: skip delete call for retry safety
            } else {
                throw new IllegalStateException("Trạng thái Media asset không hợp lệ để xóa: " + status);
            }

            user.removeAvatar();
            userRepository.save(user);

        } else if (currentAvatarUrl != null && !currentAvatarUrl.isBlank()) {
            if (isLegacyCloudinaryAvatarUrl(currentAvatarUrl)) {
                // 2. Legacy Cloudinary avatar
                avatarStorage.deleteAvatar(user.getId());
                user.removeAvatar();
                userRepository.save(user);
            } else {
                // 3. External avatar (e.g. Google avatar)
                user.removeAvatar();
                userRepository.save(user);
            }
        } else {
            // 4. No avatar
            long versionBefore = user.getAggregateVersion();
            user.removeAvatar();
            if (user.getAggregateVersion() != versionBefore) {
                userRepository.save(user);
            }
        }
    }

    private boolean isLegacyCloudinaryAvatarUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!normalizedHost.equals("res.cloudinary.com") && !normalizedHost.endsWith(".res.cloudinary.com")) {
                return false;
            }
            String path = uri.getPath();
            if (path == null) {
                return false;
            }
            return path.contains("/kiemlai/avatars/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Không xác định được email người dùng.");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }
}