package com.universe.media.infrastructure.storage.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.ImageVariantPublicUrlPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/**
 * Cloudinary implementation of {@link BinaryStoragePort} and {@link ImageVariantPublicUrlPort} for public image assets.
 * <p>
 * Core Semantics:
 * <ul>
 *     <li>Provider ID: {@code cloudinary}</li>
 *     <li>CREATE-ONLY uploads with {@code overwrite=false}</li>
 *     <li>Returns provider-neutral {@link StoredBinaryObject} containing the Cloudinary public_id and secure_url</li>
 *     <li>Idempotent deletions with CDN cache invalidation</li>
 *     <li>Direct byte streaming throws {@link StorageException} (consumers must use publicUrl/redirect delivery)</li>
 *     <li>Generates transformed CDN delivery URLs for image variants via {@link ImageVariantPublicUrlPort}</li>
 * </ul>
 */
@Component
@Conditional(CloudinaryStorageCondition.class)
public class CloudinaryStorageAdapter implements BinaryStoragePort, ImageVariantPublicUrlPort {

    public static final StorageProviderId PROVIDER_ID = StorageProviderId.of("cloudinary");
    private static final int BUFFER_SIZE = 8192;

    private final Cloudinary cloudinary;

    @Autowired
    public CloudinaryStorageAdapter(Cloudinary cloudinary) {
        this.cloudinary = Objects.requireNonNull(cloudinary, "Cloudinary client cannot be null.");
    }

    @Override
    public StorageProviderId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(StorageProviderId providerId) {
        return PROVIDER_ID.equals(providerId);
    }

    @Override
    public URI generateVariantUrl(StorageLocation sourceLocation, ImageVariantSpec spec) {
        Objects.requireNonNull(sourceLocation, "Source StorageLocation cannot be null.");
        Objects.requireNonNull(spec, "ImageVariantSpec cannot be null.");

        String rawUrl = cloudinary.url()
                .transformation(new Transformation<>().width(spec.targetWidth()).crop("scale"))
                .secure(true)
                .generate(sourceLocation.key().value().trim());

        return GetMediaAssetContentResult.validateHttpsUri(rawUrl);
    }

    @Override
    public StoredBinaryObject store(
            StorageKey key,
            InputStream content,
            long sizeBytes,
            MimeType mimeType
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative: " + sizeBytes);
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("cloudinary-upload-", ".tmp");

            long bytesWritten = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
                int read;
                while ((read = content.read(buffer)) != -1) {
                    bytesWritten += read;
                    if (bytesWritten > sizeBytes) {
                        throw new StorageException(
                                "Payload size exceeded declared sizeBytes: declared="
                                        + sizeBytes
                                        + ", received at least="
                                        + bytesWritten
                        );
                    }
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (bytesWritten != sizeBytes) {
                throw new StorageException(
                        "Payload size mismatch: declared="
                                + sizeBytes
                                + ", received="
                                + bytesWritten
                );
            }

            Map<String, Object> uploadParams = new java.util.HashMap<>();
            uploadParams.put("public_id", key.value());
            uploadParams.put("overwrite", false);
            uploadParams.put("resource_type", "image");
            uploadParams.put("unique_filename", false);

            String assetFolder = extractAssetFolder(key.value());
            if (assetFolder != null) {
                uploadParams.put("asset_folder", assetFolder);
            }

            Map<?, ?> uploadResult;
            try {
                uploadResult = cloudinary.uploader().upload(tempFile.toFile(), uploadParams);
            } catch (IOException e) {
                String message = e.getMessage() != null ? e.getMessage() : "";
                if (message.toLowerCase().contains("already exists") || message.toLowerCase().contains("duplicate")) {
                    throw new StorageObjectAlreadyExistsException(key, e);
                }
                throw new StorageException("Failed to upload binary content to Cloudinary for key: " + key.value(), e);
            }

            if (uploadResult == null) {
                throw new StorageException("Cloudinary returned null response for key: " + key.value());
            }

            String publicId = String.valueOf(uploadResult.get("public_id"));
            String secureUrl = String.valueOf(uploadResult.get("secure_url"));

            if (publicId == null || publicId.isBlank() || "null".equals(publicId)) {
                throw new StorageException("Cloudinary response missing public_id for key: " + key.value());
            }
            if (secureUrl == null || secureUrl.isBlank() || "null".equals(secureUrl)) {
                throw new StorageException("Cloudinary response missing secure_url for key: " + key.value());
            }

            StorageKey resultingKey = StorageKey.of(publicId);
            StorageLocation location = StorageLocation.of(PROVIDER_ID, resultingKey);

            return new StoredBinaryObject(location, secureUrl);

        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException("Failed to stage binary content for Cloudinary upload: " + key.value(), e);
        } catch (Exception e) {
            throw new StorageException("Failed to store binary content in Cloudinary for key: " + key.value(), e);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    @Override
    public InputStream open(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        throw new StorageException(
                "Direct byte streaming is not supported for Cloudinary storage provider; use publicUrl delivery for key: "
                        + key.value()
        );
    }

    @Override
    public InputStream openRange(StorageKey key, long startInclusive, long length) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        throw new StorageException(
                "Direct byte range streaming is not supported for Cloudinary storage provider; use publicUrl delivery for key: "
                        + key.value()
        );
    }

    @Override
    public void delete(StorageKey key) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        try {
            Map<?, ?> destroyResult = cloudinary.uploader().destroy(
                    key.value().trim(),
                    ObjectUtils.asMap(
                            "resource_type", "image",
                            "type", "upload",
                            "invalidate", true
                    )
            );

            if (destroyResult == null) {
                throw new StorageException("Cloudinary returned null result when deleting key: " + key.value());
            }

            String result = String.valueOf(destroyResult.get("result"));
            if (!"ok".equalsIgnoreCase(result) && !"not found".equalsIgnoreCase(result)) {
                throw new StorageException("Cloudinary failed to delete key: " + key.value() + ", result: " + result);
            }

        } catch (IOException e) {
            throw new StorageException("Failed to connect to Cloudinary to delete key: " + key.value(), e);
        }
    }

    /**
     * Generates a transformed CDN delivery URL for an image variant using Cloudinary SDK.
     *
     * @param publicId    the Cloudinary public ID
     * @param targetWidth the target width in pixels
     * @return the secure transformed delivery URL
     */
    public String generateVariantUrl(String publicId, int targetWidth) {
        Objects.requireNonNull(publicId, "Public ID cannot be null.");
        if (targetWidth <= 0) {
            throw new IllegalArgumentException("targetWidth must be positive: " + targetWidth);
        }

        return cloudinary.url()
                .transformation(new Transformation<>().width(targetWidth).crop("scale"))
                .secure(true)
                .generate(publicId.trim());
    }

    private static String extractAssetFolder(String storageKeyValue) {
        if (storageKeyValue == null) {
            return null;
        }
        int lastSlashIndex = storageKeyValue.lastIndexOf('/');
        if (lastSlashIndex <= 0) {
            return null;
        }
        return storageKeyValue.substring(0, lastSlashIndex);
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }
}
