package com.universe.identity.infrastructure.persistence;

import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Mapper chuyển đổi giữa Domain User và UserJpaEntity.
 */
@Component
public class UserPersistenceMapper {

	/**
	 * Tạo JPA Entity mới từ Domain User.
	 *
	 * Method này phù hợp với trường hợp insert.
	 */
	public UserJpaEntity toJpaEntity(User user) {
		if (user == null) {
			throw new IllegalArgumentException("User không được null.");
		}

		UserJpaEntity entity = new UserJpaEntity();

		updateJpaEntity(user, entity);

		return entity;
	}

	/**
	 * Cập nhật dữ liệu từ Domain User vào JPA Entity hiện có.
	 *
	 * /* Không cập nhật persistenceVersion.
	 *
	 * Trường này do Hibernate quản lý thông qua @Version.
	 */
	public void updateJpaEntity(User user, UserJpaEntity entity) {
		if (user == null) {
			throw new IllegalArgumentException("User không được null.");
		}

		if (entity == null) {
			throw new IllegalArgumentException("UserJpaEntity không được null.");
		}

		if (user.getEmail() == null) {
			throw new IllegalStateException("Email của User không được null.");
		}

		UserRole role = user.getRole() == null ? UserRole.USER : user.getRole();

		UserStatus status = user.getStatus() == null ? UserStatus.ACTIVE : user.getStatus();

		AuthProvider authProvider = user.getAuthProvider() == null ? AuthProvider.LOCAL : user.getAuthProvider();

		entity.setId(user.getId().toString());

		entity.setEmail(user.getEmail().value());

		entity.setPasswordHash(user.getPasswordHash());

		entity.setDisplayName(user.getDisplayName());

		entity.setAvatarUrl(user.getAvatarUrl());

		entity.setAvatarMediaAssetId(
				user.getAvatarMediaAssetId() != null
						? user.getAvatarMediaAssetId().toString()
						: null
		);

		entity.setAvatarCustomized(user.isAvatarCustomized());

		entity.setBio(user.getBio());

		entity.setStatus(status.name());

		entity.setRole(role);

		entity.setAuthProvider(authProvider.name());

		entity.setProviderSubject(user.getProviderSubject());

		entity.setAggregateVersion(Math.max(user.getAggregateVersion(), 1L));

		if (entity.getCreatedAt() == null) {
			entity.setCreatedAt(user.getCreatedAt());
		}

		entity.setUpdatedAt(Instant.now());
	}

	/**
	 * Chuyển JPA Entity sang Domain User.
	 */
	public User toDomain(UserJpaEntity jpaEntity) {
		if (jpaEntity == null) {
			throw new IllegalArgumentException("UserJpaEntity không được null.");
		}

		UUID userId = parseUserId(jpaEntity.getId());

		UUID avatarMediaAssetId = parseNullableUuid(jpaEntity.getAvatarMediaAssetId());

		UserStatus status = parseUserStatus(jpaEntity.getStatus());

		UserRole role = jpaEntity.getRole() == null ? UserRole.USER : jpaEntity.getRole();

		AuthProvider authProvider = parseAuthProvider(jpaEntity.getAuthProvider());

		return User.rehydrate(userId, jpaEntity.getEmail(), jpaEntity.getPasswordHash(), jpaEntity.getDisplayName(),
				avatarMediaAssetId, jpaEntity.getAvatarUrl(), jpaEntity.isAvatarCustomized(),
				jpaEntity.getBio(), status, role, authProvider, jpaEntity.getProviderSubject(),
				Math.max(jpaEntity.getAggregateVersion(), 1L), jpaEntity.getCreatedAt());
	}

	private UUID parseNullableUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}

		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(
					"Avatar Media Asset ID trong database không đúng định dạng UUID: " + raw,
					exception
			);
		}
	}

	private UUID parseUserId(String userId) {
		if (userId == null || userId.isBlank()) {

			throw new IllegalStateException("User ID trong database không hợp lệ.");
		}

		try {
			return UUID.fromString(userId.trim());

		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("User ID không đúng định dạng UUID: " + userId, exception);
		}
	}

	private UserStatus parseUserStatus(String status) {
		if (status == null || status.isBlank()) {

			throw new IllegalStateException("Trạng thái tài khoản trong database không hợp lệ.");
		}

		String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);

		try {
			return UserStatus.valueOf(normalizedStatus);

		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Trạng thái tài khoản không hợp lệ: " + status, exception);
		}
	}

	private AuthProvider parseAuthProvider(String authProvider) {
		if (authProvider == null || authProvider.isBlank()) {

			return AuthProvider.LOCAL;
		}

		String normalizedProvider = authProvider.trim().toUpperCase(Locale.ROOT);

		try {
			return AuthProvider.valueOf(normalizedProvider);

		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Auth provider không hợp lệ: " + authProvider, exception);
		}
	}
}