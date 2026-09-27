package com.universe.interaction.domain.report;

/**
 * Standard reason taxonomy for reporting an interaction comment.
 */
public enum ReportReason {
    SPAM,
    HARASSMENT,
    HATE_SPEECH,
    SEXUAL_OR_OBSCENE,
    SPOILER,
    OTHER;

    /**
     * Whether this reason mandates an accompanying user explanation.
     */
    public boolean requiresDescription() {
        return this == OTHER;
    }
}
