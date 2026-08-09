package com.universe.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "identity_password_reset_tokens")
public class PasswordResetTokenJpaEntity {

	@Id
	@Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
	private String id;

	@Column(name = "user_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
	private String userId;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "used_at", columnDefinition = "DATETIME(6)")
	private Instant usedAt;

	@Column(name = "revoked_at", columnDefinition = "DATETIME(6)")
	private Instant revokedAt;

	@Column(name = "requested_ip", length = 45)
	private String requestedIp;

	@Column(name = "user_agent", length = 500)
	private String userAgent;

	@Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant createdAt;

	@Version
	@Column(name = "persistence_version", nullable = false)
	private Long persistenceVersion;

	public PasswordResetTokenJpaEntity() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public void setTokenHash(String tokenHash) {
		this.tokenHash = tokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public void setUsedAt(Instant usedAt) {
		this.usedAt = usedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public void setRevokedAt(Instant revokedAt) {
		this.revokedAt = revokedAt;
	}

	public String getRequestedIp() {
		return requestedIp;
	}

	public void setRequestedIp(String requestedIp) {
		this.requestedIp = requestedIp;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Long getPersistenceVersion() {
		return persistenceVersion;
	}

	public void setPersistenceVersion(Long persistenceVersion) {
		this.persistenceVersion = persistenceVersion;
	}

	public boolean isExpired(Instant now) {
		return expiresAt == null || !expiresAt.isAfter(now);
	}

	public boolean isUsed() {
		return usedAt != null;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isUsable(Instant now) {
		return !isExpired(now) && !isUsed() && !isRevoked();
	}

	public void markUsed(Instant usedAt) {
		this.usedAt = usedAt;
	}

	public void revoke(Instant revokedAt) {
		this.revokedAt = revokedAt;
	}
}