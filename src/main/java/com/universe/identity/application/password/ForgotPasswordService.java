package com.universe.identity.application.password;

import com.universe.identity.application.ports.PasswordResetEmailPort;
import com.universe.identity.application.ports.PasswordResetTokenRepositoryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.PasswordResetToken;
import com.universe.identity.domain.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class ForgotPasswordService {

	private static final Logger log = LoggerFactory.getLogger(ForgotPasswordService.class);

	private static final int TOKEN_SIZE_BYTES = 32;
	private static final int TOKEN_EXPIRATION_MINUTES = 15;
	private static final int MAX_USER_AGENT_LENGTH = 500;
	private static final int MAX_IP_ADDRESS_LENGTH = 45;

	private final UserRepositoryPort userRepository;

	private final PasswordResetTokenRepositoryPort tokenRepository;

	private final PasswordResetEmailPort emailPort;
	private final PasswordResetTokenHasher tokenHasher;
	private final String baseUrl;

	private final SecureRandom secureRandom = new SecureRandom();

	public ForgotPasswordService(UserRepositoryPort userRepository, PasswordResetTokenRepositoryPort tokenRepository,
			PasswordResetEmailPort emailPort, PasswordResetTokenHasher tokenHasher,
			@Value("${app.base-url}") String baseUrl) {
		this.userRepository = userRepository;

		this.tokenRepository = tokenRepository;

		this.emailPort = emailPort;

		this.tokenHasher = tokenHasher;

		this.baseUrl = normalizeBaseUrl(baseUrl);
	}

	@Transactional
	public void requestPasswordReset(String email, String requestedIp, String userAgent) {
		String normalizedEmail = normalizeEmail(email);

		/*
		 * Không để lộ email có tồn tại trong hệ thống hay không.
		 */
		if (normalizedEmail.isEmpty()) {
			log.info("Yêu cầu đặt lại mật khẩu bị bỏ qua " + "vì email không hợp lệ.");

			return;
		}

		User user = userRepository.findByEmail(new Email(normalizedEmail)).orElse(null);

		if (user == null) {
			/*
			 * Không ghi trực tiếp địa chỉ email vào log để hạn chế làm lộ dữ liệu cá nhân.
			 */
			log.info("Nhận được yêu cầu đặt lại mật khẩu " + "cho email không tồn tại.");

			return;
		}

		Instant now = Instant.now();

		/*
		 * Thu hồi toàn bộ token cũ chưa sử dụng và vẫn còn hiệu lực của người dùng.
		 */
		tokenRepository.revokeAllActiveByUserId(user.getId(), now);

		String rawToken = generateSecureToken();

		String tokenHash = tokenHasher.hash(rawToken);

		Instant expiresAt = now.plus(TOKEN_EXPIRATION_MINUTES, ChronoUnit.MINUTES);

		PasswordResetToken token = new PasswordResetToken(UUID.randomUUID(), user.getId(), tokenHash, expiresAt, now,
				normalizeIpAddress(requestedIp), normalizeUserAgent(userAgent), null, null);

		/*
		 * Repository adapter sẽ chuyển domain object thành PasswordResetTokenJpaEntity
		 * và lưu xuống database.
		 */
		tokenRepository.save(token);

		String resetLink = baseUrl + "/reset-password?token=" + rawToken;

		log.info("Đã tạo token đặt lại mật khẩu " + "cho người dùng có ID: {}", user.getId());

		emailPort.sendPasswordResetEmail(user.getEmail().value(), resetLink);

		log.info("Đã hoàn tất gửi email đặt lại mật khẩu " + "cho người dùng có ID: {}", user.getId());
	}

	/**
	 * Tạo token ngẫu nhiên an toàn dùng cho liên kết đặt lại mật khẩu.
	 */
	private String generateSecureToken() {
		byte[] bytes = new byte[TOKEN_SIZE_BYTES];

		secureRandom.nextBytes(bytes);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Chuẩn hóa email trước khi truy vấn database.
	 */
	private String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {

			return "";
		}

		return email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Chuẩn hóa địa chỉ gốc của ứng dụng và loại bỏ dấu / ở cuối.
	 */
	private String normalizeBaseUrl(String value) {
		if (value == null || value.isBlank()) {

			throw new IllegalStateException("Thiếu cấu hình app.base-url.");
		}

		String normalized = value.trim();

		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		return normalized;
	}

	/**
	 * Chuẩn hóa địa chỉ IP và giới hạn tối đa 45 ký tự, phù hợp với cả IPv4 và
	 * IPv6.
	 */
	private String normalizeIpAddress(String ipAddress) {
		if (ipAddress == null || ipAddress.isBlank()) {

			return null;
		}

		String normalized = ipAddress.trim();

		if (normalized.length() <= MAX_IP_ADDRESS_LENGTH) {

			return normalized;
		}

		return normalized.substring(0, MAX_IP_ADDRESS_LENGTH);
	}

	/**
	 * Chuẩn hóa User-Agent và giới hạn theo độ dài cột database.
	 */
	private String normalizeUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {

			return null;
		}

		String normalized = userAgent.trim();

		if (normalized.length() <= MAX_USER_AGENT_LENGTH) {

			return normalized;
		}

		return normalized.substring(0, MAX_USER_AGENT_LENGTH);
	}
}