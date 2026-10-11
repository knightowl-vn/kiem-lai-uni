package com.universe.media.application.ports.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Outbound port for large-binary multipart storage operations.
 * <p>
 * Supported operations:
 * <ul>
 *     <li>Initiate multipart upload session on remote provider</li>
 *     <li>Generate short-lived presigned upload URLs for individual parts</li>
 *     <li>List uploaded parts from the provider (authoritative verification)</li>
 *     <li>Complete multipart upload</li>
 *     <li>Abort multipart upload (idempotent)</li>
 *     <li>Inspect completed object metadata (head object)</li>
 * </ul>
 */
public interface MultipartStoragePort extends BinaryStoragePort {

    /**
     * Initiates a multipart upload on the storage provider without metadata.
     *
     * @param key the opaque target storage key
     * @param mimeType the validated MIME type of the upload
     * @return provider-assigned upload ID
     * @throws StorageException if initiating upload fails
     * @throws NullPointerException if key or mimeType is null
     */
    default String initiateMultipartUpload(StorageKey key, MimeType mimeType) {
        return initiateMultipartUpload(key, mimeType, Map.of());
    }

    /**
     * Initiates a multipart upload on the storage provider with bound metadata.
     *
     * @param key the opaque target storage key
     * @param mimeType the validated MIME type of the upload
     * @param metadata key-value metadata to attach to the initiated multipart upload
     * @return provider-assigned upload ID
     * @throws StorageException if initiating upload fails
     * @throws NullPointerException if key, mimeType, or metadata is null
     */
    String initiateMultipartUpload(StorageKey key, MimeType mimeType, Map<String, String> metadata);

    /**
     * Generates a presigned URL allowing direct client PUT for an individual part.
     *
     * @param key the target storage key
     * @param uploadId provider-assigned upload ID
     * @param partNumber 1-based part number (1..10000)
     * @param expiry validity duration for the presigned URL
     * @return full presigned upload URL
     * @throws StorageException if presigning fails
     * @throws NullPointerException if key, uploadId, or expiry is null
     * @throws IllegalArgumentException if partNumber is out of range or expiry is non-positive
     */
    String generatePresignedPartUploadUrl(StorageKey key, String uploadId, int partNumber, Duration expiry);

    /**
     * Authoritatively queries the storage provider for all uploaded parts under this upload ID.
     * Implementations must handle remote pagination transparently.
     *
     * @param key the target storage key
     * @param uploadId provider-assigned upload ID
     * @return list of uploaded parts in ascending part-number order
     * @throws StorageException if querying parts fails
     * @throws NullPointerException if key or uploadId is null
     */
    List<MultipartUploadedPart> listUploadedParts(StorageKey key, String uploadId);

    /**
     * Completes the multipart upload by assembling the verified parts on the provider.
     *
     * @param key the target storage key
     * @param uploadId provider-assigned upload ID
     * @param parts list of part numbers and their ETags to assemble
     * @return completed object details including canonical location and ETag
     * @throws StorageException if completion fails
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if parts is empty
     */
    MultipartCompletedObject completeMultipartUpload(StorageKey key, String uploadId, List<MultipartPartETag> parts);

    /**
     * Aborts the multipart upload on the provider and cleans up any uncompleted parts.
     * <p>
     * <b>Idempotency:</b> Aborting an upload that does not exist or was already completed/aborted
     * must return {@link MultipartAbortResult#UPLOAD_ALREADY_ABSENT}.
     *
     * @param key the target storage key
     * @param uploadId provider-assigned upload ID
     * @return typed outcome indicating whether the provider acknowledged the abort or reported the upload was absent
     * @throws StorageException if abort fails due to provider errors other than missing upload
     * @throws NullPointerException if key or uploadId is null
     */
    MultipartAbortResult abortMultipartUpload(StorageKey key, String uploadId);

    /**
     * Inspects metadata of a completed object in storage without downloading its content.
     *
     * @param key the storage key
     * @return metadata of the object
     * @throws StorageObjectNotFoundException if no object exists at key
     * @throws StorageException if provider check fails
     * @throws NullPointerException if key is null
     */
    MultipartObjectMetadata inspectObjectMetadata(StorageKey key);
}
