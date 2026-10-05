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

    public String displayName() {
        return switch (this) {
            case SPAM -> "Spam";
            case HARASSMENT -> "Quấy rối";
            case HATE_SPEECH -> "Phát ngôn thù hằn";
            case SEXUAL_OR_OBSCENE -> "Khiêu dâm / Thô tục";
            case SPOILER -> "Tiết lộ nội dung (Spoiler)";
            case OTHER -> "Lý do khác";
        };
    }
}
