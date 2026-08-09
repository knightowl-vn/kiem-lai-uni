package com.universe.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class PasswordResetToken {

	private final UUID id;
	private final UUID userId;
	private final String tokenHash;
	private final Instant expiresAt;
	private final Instant createdAt;
	private final String requestedIp;
	private final String userAgent;

	private Instant usedAt;
	private Instant revokedAt;

	public PasswordResetToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant createdAt,
			String requestedIp, String userAgent, Instant usedAt, Instant revokedAt) {
		this.id = Objects.requireNonNull(id, "Token ID không được để trống.");

		this.userId = Objects.requireNonNull(userId, "User ID không được để trống.");

		this.tokenHash = normalizeTokenHash(tokenHash);

		this.expiresAt = Objects.requireNonNull(expiresAt, "Thời gian hết hạn không được để trống.");

		this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");

		if (!expiresAt.isAfter(createdAt)) {
			throw new IllegalArgumentException("Thời gian hết hạn phải sau thời gian tạo token.");
		}

		this.requestedIp = normalizeNullableValue(requestedIp);

		this.userAgent = normalizeNullableValue(userAgent);

		this.usedAt = usedAt;

		this.revokedAt = revokedAt;
	}

	public boolean isUsable(Instant now) {
		Objects.requireNonNull(now, "Thời điểm kiểm tra không được để trống.");

		return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
	}

	public void markUsed(Instant now) {
		Objects.requireNonNull(now, "Thời điểm sử dụng token không được để trống.");

		if (usedAt != null) {
			return;
		}

		if (revokedAt != null) {
			throw new IllegalStateException("Không thể sử dụng token đã bị thu hồi.");
		}

		if (!expiresAt.isAfter(now)) {
			throw new IllegalStateException("Không thể sử dụng token đã hết hạn.");
		}

		usedAt = now;
	}

	public void revoke(Instant now) {
		Objects.requireNonNull(now, "Thời điểm thu hồi token không được để trống.");

		if (revokedAt != null) {
			return;
		}

		if (usedAt != null) {
			return;
		}

		revokedAt = now;
	}

	private static String normalizeTokenHash(String tokenHash) {
		if (tokenHash == null || tokenHash.isBlank()) {

			throw new IllegalArgumentException("Token hash không được để trống.");
		}

		return tokenHash.trim();
	}

	private static String normalizeNullableValue(String value) {
		if (value == null || value.isBlank()) {

			return null;
		}

		return value.trim();
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getRequestedIp() {
		return requestedIp;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}
}