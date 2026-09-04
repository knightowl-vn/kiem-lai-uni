package com.universe.wiki.application.image;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiImageRepositoryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UploadWikiImageUseCase {

	private static final Logger log = LoggerFactory.getLogger(UploadWikiImageUseCase.class);

	private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;


	private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

	private final MediaContract mediaContract;
	private final WikiImageRepositoryPort imageRepositoryPort;
	private final ClockPort clockPort;

	public UploadWikiImageUseCase(MediaContract mediaContract, WikiImageRepositoryPort imageRepositoryPort,
			ClockPort clockPort) {
		this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract không được để trống.");
		this.imageRepositoryPort = Objects.requireNonNull(imageRepositoryPort,
				"WikiImageRepositoryPort không được để trống.");
		this.clockPort = Objects.requireNonNull(clockPort, "ClockPort không được để trống.");
	}

	public WikiImageUploadResult execute(InputStream content, long declaredSizeBytes, String contentType,
			String originalFilename) {
		validateMetadata(originalFilename, contentType, content);

		if (declaredSizeBytes <= 0) {
			throw new IllegalArgumentException("Vui lòng chọn một ảnh Wiki.");
		}

		if (declaredSizeBytes > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("Ảnh Wiki không được vượt quá 5 MB.");
		}

		Path spoolFile = null;
		try {
			try {
				spoolFile = Files.createTempFile("wiki-upload-spool-", ".tmp");
			} catch (IOException exception) {
				throw new UncheckedIOException("Không thể tạo file tạm để xử lý ảnh Wiki.", exception);
			}

			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException("Không thể tạo fingerprint cho ảnh Wiki.", exception);
			}

			long actualSizeBytes = 0;
			try (OutputStream out = Files.newOutputStream(spoolFile)) {
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = content.read(buffer)) != -1) {
					actualSizeBytes += bytesRead;
					if (actualSizeBytes > MAX_FILE_SIZE) {
						throw new IllegalArgumentException("Ảnh Wiki không được vượt quá 5 MB.");
					}
					digest.update(buffer, 0, bytesRead);
					out.write(buffer, 0, bytesRead);
				}
			} catch (IOException exception) {
				throw new UncheckedIOException("Không thể ghi file tạm khi upload ảnh Wiki.", exception);
			}

			if (actualSizeBytes == 0) {
				throw new IllegalArgumentException("Vui lòng chọn một ảnh Wiki.");
			}

			String contentHash = HexFormat.of().formatHex(digest.digest());

			var existingAssetOpt = imageRepositoryPort.findByContentHash(contentHash);

			if (existingAssetOpt.isPresent()) {
				WikiImageAsset existing = existingAssetOpt.get();
				if (existing.mediaAssetId() != null) {
					tryGenerateWikiImageVariant(existing.mediaAssetId());
				}
				return new WikiImageUploadResult(existing.url(), existing.publicId());
			}

			UploadMediaAssetResponseDTO mediaResponse;
			try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
				UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(uploadIn, actualSizeBytes,
						contentType, MediaTypeDTO.IMAGE, MediaVisibilityDTO.PUBLIC,
						normalizeFilename(originalFilename));
				mediaResponse = mediaContract.uploadAsset(uploadRequest);
			} catch (IOException exception) {
				throw new UncheckedIOException("Không thể đọc file tạm để upload lên Media.", exception);
			}

			UUID mediaAssetId = mediaResponse.assetId();
			String mediaDeliveryUrl = MediaDeliveryUrlSupport.contentUrl(mediaAssetId);

			WikiImageAsset newAsset = new WikiImageAsset(UUID.randomUUID(), contentHash, mediaDeliveryUrl, null,
					mediaAssetId, contentType, actualSizeBytes, clockPort.now());

			try {
				imageRepositoryPort.save(newAsset);
			} catch (RuntimeException persistEx) {
				try {
					mediaContract.delete(mediaAssetId);
				} catch (RuntimeException compEx) {
					persistEx.addSuppressed(compEx);
				}
				throw persistEx;
			}

			tryGenerateWikiImageVariant(mediaAssetId);

			return new WikiImageUploadResult(mediaDeliveryUrl, null);

		} finally {
			if (spoolFile != null) {
				try {
					Files.deleteIfExists(spoolFile);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private void tryGenerateWikiImageVariant(UUID mediaAssetId) {
		if (mediaAssetId == null) {
			return;
		}
		try {
			mediaContract.generateImageVariant(
					new GenerateImageVariantRequestDTO(
							mediaAssetId,
							WikiImageVariantPolicy.TARGET_WIDTH
					)
			);
		} catch (RuntimeException ex) {
			log.warn(
					"Không thể tạo biến thể ảnh Wiki w1400 cho Media Asset [{}]: {}. Giữ nguyên ảnh gốc.",
					mediaAssetId,
					ex.getMessage(),
					ex
			);
		}
	}


	public WikiImageUploadResult execute(String originalFilename, String contentType, InputStream content,
			long declaredSizeBytes) {
		return execute(content, declaredSizeBytes, contentType, originalFilename);
	}

	/*
	 * ===================================================== VALIDATION
	 * =====================================================
	 */

	private void validateMetadata(String originalFilename, String contentType, InputStream content) {
		if (content == null) {
			throw new IllegalArgumentException("Vui lòng chọn một ảnh Wiki.");
		}

		validateContentType(contentType);
		validateFilename(originalFilename);
	}

	private void validateContentType(String contentType) {
		if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
			throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
		}
	}

	private void validateFilename(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new IllegalArgumentException("Tên file ảnh không hợp lệ.");
		}

		String extension = extractExtension(originalFilename);

		if (!SUPPORTED_EXTENSIONS.contains(extension)) {
			throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
		}
	}

	/*
	 * ===================================================== FILENAME
	 * =====================================================
	 */

	private String normalizeFilename(String originalFilename) {
		String normalized = originalFilename.trim();

		/*
		 * Phòng trường hợp client gửi cả đường dẫn: C:\fakepath\image.png
		 * /home/user/image.png
		 */
		normalized = normalized.replace('\\', '/');

		int lastSlashIndex = normalized.lastIndexOf('/');

		if (lastSlashIndex >= 0 && lastSlashIndex < normalized.length() - 1) {
			normalized = normalized.substring(lastSlashIndex + 1);
		}

		return normalized;
	}

	private String extractExtension(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			return "";
		}

		String normalizedFilename = normalizeFilename(originalFilename);

		int dotIndex = normalizedFilename.lastIndexOf('.');

		if (dotIndex < 0 || dotIndex == normalizedFilename.length() - 1) {
			return "";
		}

		return normalizedFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}
}