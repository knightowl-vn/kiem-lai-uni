package com.universe.novel.infrastructure.persistence.voice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "novel_managed_voices")
public class ManagedVoiceJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "voice_key",
            nullable = false,
            length = 100
    )
    private String voiceKey;

    @Column(
            name = "display_name",
            nullable = false,
            length = 200
    )
    private String displayName;

    @Column(
            name = "provider_voice_id",
            nullable = false,
            length = 200
    )
    private String providerVoiceId;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Column(
            name = "display_order",
            nullable = false
    )
    private int displayOrder;

    @Column(
            name = "is_default",
            nullable = false
    )
    private boolean defaultVoice;

    @Column(
            name = "synthesis_revision",
            nullable = false
    )
    private long synthesisRevision;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    protected ManagedVoiceJpaEntity() {
    }

    public ManagedVoiceJpaEntity(
            String id,
            String voiceKey,
            String displayName,
            String providerVoiceId,
            String status,
            int displayOrder,
            boolean defaultVoice,
            long synthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.voiceKey = voiceKey;
        this.displayName = displayName;
        this.providerVoiceId = providerVoiceId;
        this.status = status;
        this.displayOrder = displayOrder;
        this.defaultVoice = defaultVoice;
        this.synthesisRevision = synthesisRevision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getVoiceKey() {
        return voiceKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderVoiceId() {
        return providerVoiceId;
    }

    public void setProviderVoiceId(String providerVoiceId) {
        this.providerVoiceId = providerVoiceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(boolean defaultVoice) {
        this.defaultVoice = defaultVoice;
    }

    public long getSynthesisRevision() {
        return synthesisRevision;
    }

    public void setSynthesisRevision(long synthesisRevision) {
        this.synthesisRevision = synthesisRevision;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
