package com.universe.community.application.cursor;

import com.universe.community.domain.exception.CommunityPostValidationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Codec for converting between {@link CommunityPostKeysetCursor} and opaque URL-safe Base64 cursor strings.
 */
@Component
public class CommunityPostKeysetCursorCodec {

    private static final String DELIMITER = "|";

    /**
     * Encodes a cursor into an opaque URL-safe Base64 string without padding.
     *
     * @param cursor the cursor point
     * @return opaque encoded cursor string
     */
    public String encode(CommunityPostKeysetCursor cursor) {
        if (cursor == null) {
            return null;
        }
        String raw = cursor.createdAt().toString() + DELIMITER + cursor.postId().toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes an opaque cursor string into a {@link CommunityPostKeysetCursor}.
     *
     * @param rawCursor the opaque cursor string (may be null or blank for the first page)
     * @return the decoded cursor, or null if rawCursor is null/blank
     * @throws CommunityPostValidationException if rawCursor is malformed
     */
    public CommunityPostKeysetCursor decode(String rawCursor) {
        if (rawCursor == null || rawCursor.trim().isEmpty()) {
            return null;
        }

        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(rawCursor.trim());
            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);

            int delimiterIndex = decodedString.indexOf(DELIMITER);
            if (delimiterIndex <= 0 || delimiterIndex >= decodedString.length() - 1) {
                throw new CommunityPostValidationException("Invalid cursor format.");
            }

            String createdAtPart = decodedString.substring(0, delimiterIndex);
            String postIdPart = decodedString.substring(delimiterIndex + 1);

            Instant createdAt = Instant.parse(createdAtPart);
            UUID postId = UUID.fromString(postIdPart);

            return new CommunityPostKeysetCursor(createdAt, postId);
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new CommunityPostValidationException("Invalid cursor format.");
        }
    }
}
