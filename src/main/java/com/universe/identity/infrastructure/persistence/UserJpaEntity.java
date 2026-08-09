package com.universe.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

import com.universe.identity.domain.UserRole;

/**
 * Persistence Model ánh xạ trực tiếp xuống bảng identity_users. Không chứa
 * business logic của domain.
 */
@Entity
@Table(name = "identity_users")
public class UserJpaEntity {

	@Id
	@Column(name = "id", length = 36, nullable = false, columnDefinition = "CHAR(36)")
	private String id;

	@Column(name = "email", nullable = false, unique = true, length = 255)
	private String email;

	/*
	 * Tài khoản LOCAL có passwordHash. Tài khoản GOOGLE thuần có thể để null.
	 */
	@Column(name = "password_hash", nullable = true, length = 255)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 50)
	private String displayName;

	@Column(name = "avatar_url", length = 1000)
	private String avatarUrl;

	@Column(name = "avatar_customized", nullable = false)
	private boolean avatarCustomized;

	@Column(name = "bio", length = 500)
	private String bio;

	@Column(name = "status", nullable = false, length = 30)
	private String status;

	/*
	 * LOCAL: đăng nhập bằng email/mật khẩu. GOOGLE: đăng nhập bằng Google OAuth.
	 *
	 * Có thể mở rộng thêm GITHUB, FACEBOOK...
	 */
	@Column(name = "auth_provider", nullable = false, length = 20)
	private String authProvider = "LOCAL";

	/*
	 * Với Google, đây là claim "sub". LOCAL thì để null.
	 */
	@Column(name = "provider_subject", length = 255)
	private String providerSubject;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private UserRole role = UserRole.USER;

	@Column(name = "aggregate_version", nullable = false)
	private long aggregateVersion = 1L;

	@Version
	@Column(name = "persistence_version", nullable = false)
	private long persistenceVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/*
	 * Public để GoogleOAuthUserService có thể tạo user mới.
	 */
	public UserJpaEntity() {
	}

	/*
	 * Constructor dành cho tài khoản đăng ký bằng email/mật khẩu.
	 */
	public UserJpaEntity(String id, String email, String passwordHash, String displayName, String status, UserRole role,
			long aggregateVersion, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.status = status;
		this.authProvider = "LOCAL";
		this.providerSubject = null;
		this.role = role == null ? UserRole.USER : role;
		this.aggregateVersion = Math.max(aggregateVersion, 1L);
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public String getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public boolean isAvatarCustomized() {
		return avatarCustomized;
	}

	public String getBio() {
		return bio;
	}

	public String getStatus() {
		return status;
	}

	public String getAuthProvider() {
		return authProvider;
	}

	public String getProviderSubject() {
		return providerSubject;
	}

	public UserRole getRole() {
		return role;
	}

	public long getAggregateVersion() {
		return aggregateVersion;
	}

	public long getPersistenceVersion() {
		return persistenceVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setId(String id) {
		this.id = id;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public void setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
	}

	public void setAvatarCustomized(boolean avatarCustomized) {
		this.avatarCustomized = avatarCustomized;
	}

	public void setBio(String bio) {
		this.bio = bio;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public void setAuthProvider(String authProvider) {
		this.authProvider = authProvider;
	}

	public void setProviderSubject(String providerSubject) {
		this.providerSubject = providerSubject;
	}

	public void setRole(UserRole role) {
		this.role = role == null ? UserRole.USER : role;
	}

	public void setAggregateVersion(long aggregateVersion) {
		this.aggregateVersion = Math.max(aggregateVersion, 1L);
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}