package com.universe.wiki.infrastructure.persistence.contribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "wiki_contribution_workflow_events",
        indexes = {
                @Index(
                        name = "idx_wiki_contribution_events_timeline",
                        columnList = "contribution_id,created_at,id"
                )
        }
)
public class WikiContributionWorkflowEventJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "contribution_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String contributionId;

    @Column(
            name = "event_type",
            nullable = false,
            length = 50
    )
    private String eventType;

    @Column(
            name = "actor_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String actorUserId;

    @Column(
            name = "target_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String targetUserId;

    @Column(
            name = "from_status",
            length = 20
    )
    private String fromStatus;

    @Column(
            name = "to_status",
            length = 20
    )
    private String toStatus;

    @Column(
            name = "article_content_version"
    )
    private Long articleContentVersion;

    @Column(
            name = "resolution_outcome",
            length = 30
    )
    private String resolutionOutcome;

    @Column(
            name = "note",
            length = 2000
    )
    private String note;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    public WikiContributionWorkflowEventJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContributionId() {
        return contributionId;
    }

    public void setContributionId(String contributionId) {
        this.contributionId = contributionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(String targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public Long getArticleContentVersion() {
        return articleContentVersion;
    }

    public void setArticleContentVersion(Long articleContentVersion) {
        this.articleContentVersion = articleContentVersion;
    }

    public String getResolutionOutcome() {
        return resolutionOutcome;
    }

    public void setResolutionOutcome(String resolutionOutcome) {
        this.resolutionOutcome = resolutionOutcome;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
