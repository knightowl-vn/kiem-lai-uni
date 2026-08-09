package com.universe.shared.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "integration_outbox_messages")
public class OutboxMessageJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "event_id",
            nullable = false,
            unique = true,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String eventId;

    @Column(
            name = "event_type",
            nullable = false,
            length = 255
    )
    private String eventType;

    @Column(
            name = "event_version",
            nullable = false,
            length = 20
    )
    private String eventVersion;

    @Column(
            name = "aggregate_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String aggregateId;

    @Column(
            name = "aggregate_type",
            nullable = false,
            length = 100
    )
    private String aggregateType;

    @Column(
            name = "aggregate_version",
            nullable = false
    )
    private long aggregateVersion;

    @Column(
            name = "source_module",
            nullable = false,
            length = 100
    )
    private String sourceModule;

    @Column(
            name = "occurred_at",
            nullable = false
    )
    private Instant occurredAt;

    @Column(
            name = "correlation_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String correlationId;

    @Column(
            name = "causation_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String causationId;

    @Column(
            name = "payload_json",
            nullable = false,
            columnDefinition = "JSON"
    )
    private String payloadJson;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Column(
            name = "attempt_count",
            nullable = false
    )
    private int attemptCount;

    @Column(
            name = "next_attempt_at"
    )
    private Instant nextAttemptAt;

    @Column(
            name = "processing_owner",
            length = 255
    )
    private String processingOwner;

    @Column(
            name = "claim_token",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String claimToken;

    @Column(
            name = "processing_started_at"
    )
    private Instant processingStartedAt;

    @Column(
            name = "processing_lease_until"
    )
    private Instant processingLeaseUntil;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "published_at"
    )
    private Instant publishedAt;

    @Column(
            name = "dead_at"
    )
    private Instant deadAt;

    @Column(
            name = "last_error",
            columnDefinition = "TEXT"
    )
    private String lastError;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    public OutboxMessageJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(
            String eventId
    ) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(
            String eventType
    ) {
        this.eventType = eventType;
    }

    public String getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(
            String eventVersion
    ) {
        this.eventVersion = eventVersion;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(
            String aggregateId
    ) {
        this.aggregateId = aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(
            String aggregateType
    ) {
        this.aggregateType = aggregateType;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public void setAggregateVersion(
            long aggregateVersion
    ) {
        this.aggregateVersion = aggregateVersion;
    }

    public String getSourceModule() {
        return sourceModule;
    }

    public void setSourceModule(
            String sourceModule
    ) {
        this.sourceModule = sourceModule;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(
            Instant occurredAt
    ) {
        this.occurredAt = occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(
            String correlationId
    ) {
        this.correlationId = correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public void setCausationId(
            String causationId
    ) {
        this.causationId = causationId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(
            String payloadJson
    ) {
        this.payloadJson = payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(
            String status
    ) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(
            int attemptCount
    ) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(
            Instant nextAttemptAt
    ) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getProcessingOwner() {
        return processingOwner;
    }

    public void setProcessingOwner(
            String processingOwner
    ) {
        this.processingOwner = processingOwner;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(
            String claimToken
    ) {
        this.claimToken = claimToken;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(
            Instant processingStartedAt
    ) {
        this.processingStartedAt = processingStartedAt;
    }

    public Instant getProcessingLeaseUntil() {
        return processingLeaseUntil;
    }

    public void setProcessingLeaseUntil(
            Instant processingLeaseUntil
    ) {
        this.processingLeaseUntil = processingLeaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(
            Instant publishedAt
    ) {
        this.publishedAt = publishedAt;
    }

    public Instant getDeadAt() {
        return deadAt;
    }

    public void setDeadAt(
            Instant deadAt
    ) {
        this.deadAt = deadAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(
            String lastError
    ) {
        this.lastError = lastError;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }
}