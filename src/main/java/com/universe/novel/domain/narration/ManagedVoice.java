package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate Root representing a Novel-owned Managed Narration Voice.
 * <p>
 * A managed voice represents a stable KiemLai identity (e.g. voiceKey = "kiemlai-male-north-01")
 * mapped to an external TTS provider voice ID (e.g. "minh-duc").
 * <p>
 * Core Domain Invariants:
 * <ul>
 *     <li>{@code voiceKey} is required, stable/immutable, and alphanumeric with hyphens/underscores.</li>
 *     <li>{@code displayName} and {@code providerVoiceId} are required and editable.</li>
 *     <li>{@code synthesisRevision} starts at 1 and increments if and only if {@code providerVoiceId} changes.</li>
 *     <li>A default voice must always be in {@link ManagedVoiceStatus#ACTIVE} status.</li>
 *     <li>Disabling the current default voice directly is prohibited.</li>
 * </ul>
 */
public class ManagedVoice {

    private static final Pattern VOICE_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,100}$");
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_PROVIDER_VOICE_ID_LENGTH = 200;

    private final UUID id;
    private final String voiceKey;
    private String displayName;
    private String providerVoiceId;
    private ManagedVoiceStatus status;
    private int displayOrder;
    private boolean defaultVoice;
    private long synthesisRevision;
    private final Instant createdAt;
    private Instant updatedAt;

    private ManagedVoice(
            UUID id,
            String voiceKey,
            String displayName,
            String providerVoiceId,
            ManagedVoiceStatus status,
            int displayOrder,
            boolean defaultVoice,
            long synthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID giọng đọc không được để trống.");
        this.voiceKey = validateVoiceKey(voiceKey);
        this.displayName = validateDisplayName(displayName);
        this.providerVoiceId = validateProviderVoiceId(providerVoiceId);
        this.status = Objects.requireNonNull(status, "Trạng thái giọng đọc không được để trống.");
        this.displayOrder = validateDisplayOrder(displayOrder);
        this.synthesisRevision = validateSynthesisRevision(synthesisRevision);
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        if (defaultVoice && status != ManagedVoiceStatus.ACTIVE) {
            throw new IllegalStateException("Giọng đọc mặc định bắt buộc phải ở trạng thái ACTIVE.");
        }
        this.defaultVoice = defaultVoice;
    }

    /**
     * Creates a new managed voice aggregate.
     */
    public static ManagedVoice create(
            UUID id,
            String voiceKey,
            String displayName,
            String providerVoiceId,
            int displayOrder,
            boolean defaultVoice,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new ManagedVoice(
                id,
                voiceKey,
                displayName,
                providerVoiceId,
                ManagedVoiceStatus.ACTIVE,
                displayOrder,
                defaultVoice,
                1L,
                now,
                now
        );
    }

    /**
     * Rehydrates aggregate state from persistence storage.
     */
    public static ManagedVoice rehydrate(
            UUID id,
            String voiceKey,
            String displayName,
            String providerVoiceId,
            ManagedVoiceStatus status,
            int displayOrder,
            boolean defaultVoice,
            long synthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ManagedVoice(
                id,
                voiceKey,
                displayName,
                providerVoiceId,
                status,
                displayOrder,
                defaultVoice,
                synthesisRevision,
                createdAt,
                updatedAt
        );
    }

    /**
     * Updates display metadata without changing synthesis revision.
     */
    public void updateMetadata(
            String displayName,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        this.displayName = validateDisplayName(displayName);
        this.updatedAt = now;
    }

    /**
     * Changes external provider voice ID mapping.
     * <p>
     * If the mapping changes to a new value, {@code synthesisRevision} is incremented.
     *
     * @param newProviderVoiceId the new provider voice ID
     * @param now                update timestamp
     * @return {@code true} if mapping changed and revision incremented; {@code false} if unchanged
     */
    public boolean changeProviderMapping(
            String newProviderVoiceId,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        String validated = validateProviderVoiceId(newProviderVoiceId);

        if (Objects.equals(this.providerVoiceId, validated)) {
            return false;
        }

        this.providerVoiceId = validated;
        this.synthesisRevision++;
        this.updatedAt = now;
        return true;
    }

    /**
     * Activates the managed voice for playback and selection.
     */
    public void activate(Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        this.status = ManagedVoiceStatus.ACTIVE;
        this.updatedAt = now;
    }

    /**
     * Disables the managed voice.
     * <p>
     * Throws {@link IllegalStateException} if this voice is currently designated as default.
     */
    public void disable(Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        if (this.defaultVoice) {
            throw new IllegalStateException("Không thể vô hiệu hóa giọng đọc đang là mặc định.");
        }
        this.status = ManagedVoiceStatus.DISABLED;
        this.updatedAt = now;
    }

    /**
     * Marks this voice as the default voice.
     * <p>
     * Throws {@link IllegalStateException} if this voice is not in ACTIVE status.
     */
    public void markDefault(Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        if (this.status != ManagedVoiceStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ có thể đặt giọng đọc ACTIVE làm mặc định.");
        }
        this.defaultVoice = true;
        this.updatedAt = now;
    }

    /**
     * Clears the default voice designation.
     */
    public void unmarkDefault(Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        this.defaultVoice = false;
        this.updatedAt = now;
    }

    private static String validateVoiceKey(String voiceKey) {
        if (voiceKey == null || voiceKey.isBlank()) {
            throw new IllegalArgumentException("Voice key không được để trống.");
        }
        String trimmed = voiceKey.trim();
        if (!VOICE_KEY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Voice key không hợp lệ (yêu cầu 2-100 ký tự chữ, số, gạch ngang, gạch dưới): " + voiceKey
            );
        }
        return trimmed;
    }

    private static String validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Tên hiển thị giọng đọc không được để trống.");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Tên hiển thị không được vượt quá " + MAX_NAME_LENGTH + " ký tự.");
        }
        return trimmed;
    }

    private static String validateProviderVoiceId(String providerVoiceId) {
        if (providerVoiceId == null || providerVoiceId.isBlank()) {
            throw new IllegalArgumentException("Provider voice ID không được để trống.");
        }
        String trimmed = providerVoiceId.trim();
        if (trimmed.length() > MAX_PROVIDER_VOICE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Provider voice ID không được vượt quá " + MAX_PROVIDER_VOICE_ID_LENGTH + " ký tự."
            );
        }
        return trimmed;
    }

    private static int validateDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Thứ tự hiển thị phải lớn hơn hoặc bằng 0.");
        }
        return displayOrder;
    }

    private static long validateSynthesisRevision(long synthesisRevision) {
        if (synthesisRevision < 1) {
            throw new IllegalArgumentException("Synthesis revision phải lớn hơn hoặc bằng 1.");
        }
        return synthesisRevision;
    }

    public UUID getId() {
        return id;
    }

    public String getVoiceKey() {
        return voiceKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProviderVoiceId() {
        return providerVoiceId;
    }

    public ManagedVoiceStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == ManagedVoiceStatus.ACTIVE;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isDefaultVoice() {
        return defaultVoice;
    }

    public long getSynthesisRevision() {
        return synthesisRevision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
