package com.universe.screen.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure timestamp parser, formatter, and validation helper for Screen Intro/Outro timeline configuration.
 *
 * <p>Supports {@code MM:SS}, {@code HH:MM:SS}, and raw non-negative whole seconds.
 */
public final class ScreenTimelineTimestampFormat {

    public static final String INVALID_TIMESTAMP_MESSAGE =
            "Thời gian không hợp lệ. Hãy nhập MM:SS hoặc HH:MM:SS.";
    public static final String INCOMPLETE_INTRO_PAIR_MESSAGE =
            "Vui lòng nhập đủ thời gian bắt đầu và kết thúc Intro.";
    public static final String INVALID_INTRO_ORDER_MESSAGE =
            "Thời gian kết thúc Intro phải lớn hơn thời gian bắt đầu.";
    public static final String INVALID_OUTRO_BEFORE_INTRO_END_MESSAGE =
            "Outro không được bắt đầu trước khi Intro kết thúc.";
    public static final String MISSING_OUTRO_OVERRIDE_MESSAGE =
            "Vui lòng nhập thời gian bắt đầu Outro.";
    public static final String MISSING_OUTRO_START_FOR_END_MESSAGE =
            "Không thể thiết lập thời gian kết thúc Outro khi chưa có thời gian bắt đầu.";
    public static final String INVALID_OUTRO_ORDER_MESSAGE =
            "Thời gian kết thúc Outro phải lớn hơn thời gian bắt đầu.";
    public static final String INTRO_EXCEEDS_DURATION_MESSAGE =
            "Thời gian kết thúc Intro không được vượt quá thời lượng tập phim.";
    public static final String OUTRO_EXCEEDS_DURATION_MESSAGE =
            "Thời gian bắt đầu Outro phải nhỏ hơn thời lượng tập phim.";
    public static final String OUTRO_END_EXCEEDS_DURATION_MESSAGE =
            "Thời gian kết thúc Outro không được vượt quá thời lượng tập phim.";

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    private ScreenTimelineTimestampFormat() {
    }

    /**
     * Parses an optional timestamp input into non-negative whole seconds.
     *
     * <p>Returns {@code null} when input is {@code null} or blank, preserving absent optional values
     * distinctly from {@code 0L}.
     *
     * @param rawInput user-entered timestamp string ({@code MM:SS}, {@code HH:MM:SS}, or whole seconds)
     * @return parsed non-negative seconds, or {@code null} if blank
     * @throws IllegalArgumentException if input is negative, malformed, out of range, or overflows
     */
    public static Long parseOptionalSeconds(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.contains(":")) {
            if (trimmed.startsWith(":") || trimmed.endsWith(":")) {
                throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
            }
            String[] parts = trimmed.split(":", -1);
            if (parts.length == 2) {
                long minutes = parseDigitsComponent(parts[0]);
                long seconds = parseDigitsComponent(parts[1]);
                if (seconds >= 60) {
                    throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
                }
                try {
                    return Math.addExact(Math.multiplyExact(minutes, 60L), seconds);
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE, ex);
                }
            } else if (parts.length == 3) {
                long hours = parseDigitsComponent(parts[0]);
                long minutes = parseDigitsComponent(parts[1]);
                long seconds = parseDigitsComponent(parts[2]);
                if (minutes >= 60 || seconds >= 60) {
                    throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
                }
                try {
                    long totalSeconds = Math.multiplyExact(hours, 3600L);
                    totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(minutes, 60L));
                    return Math.addExact(totalSeconds, seconds);
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE, ex);
                }
            } else {
                throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
            }
        } else {
            return parseDigitsComponent(trimmed);
        }
    }

    private static long parseDigitsComponent(String part) {
        if (part == null || !DIGITS_ONLY.matcher(part).matches()) {
            throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
        }
        try {
            long value = Long.parseLong(part);
            if (value < 0) {
                throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE, ex);
        }
    }

    /**
     * Formats non-negative seconds into {@code MM:SS} (when {@code < 3600}) or {@code HH:MM:SS} (when {@code >= 3600}).
     *
     * @param seconds non-negative seconds, or {@code null}
     * @return formatted timestamp string, or {@code null} when absent/negative
     */
    public static String formatSeconds(Long seconds) {
        if (seconds == null || seconds < 0) {
            return null;
        }
        if (seconds < 3600L) {
            long minutes = seconds / 60L;
            long remainingSeconds = seconds % 60L;
            return String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    /**
     * Validates that an Intro start/end pair is either completely absent or a valid interval with {@code end > start}.
     */
    public static void validateIntroPairAndOrder(Long introStartSeconds, Long introEndSeconds) {
        if ((introStartSeconds == null) != (introEndSeconds == null)) {
            throw new IllegalArgumentException(INCOMPLETE_INTRO_PAIR_MESSAGE);
        }
        if (introStartSeconds != null) {
            if (introStartSeconds < 0 || introEndSeconds < 0) {
                throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
            }
            if (introEndSeconds <= introStartSeconds) {
                throw new IllegalArgumentException(INVALID_INTRO_ORDER_MESSAGE);
            }
        }
    }

    /**
     * Validates that an Outro start timestamp is not before the Intro end timestamp when both are present.
     */
    public static void validateOutroNotBeforeIntroEnd(Long introEndSeconds, Long outroStartSeconds) {
        if (outroStartSeconds != null && outroStartSeconds < 0) {
            throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
        }
        if (introEndSeconds != null && outroStartSeconds != null && outroStartSeconds < introEndSeconds) {
            throw new IllegalArgumentException(INVALID_OUTRO_BEFORE_INTRO_END_MESSAGE);
        }
    }

    /**
     * Validates that an Outro start/end pair is valid:
     * <ul>
     *   <li>{@code outroEndSeconds} is optional, but if present requires {@code outroStartSeconds}.</li>
     *   <li>If {@code outroEndSeconds} is present, it must be strictly greater than {@code outroStartSeconds}.</li>
     *   <li>Neither timestamp can be negative.</li>
     * </ul>
     */
    public static void validateOutroPairAndOrder(Long outroStartSeconds, Long outroEndSeconds) {
        if (outroStartSeconds != null && outroStartSeconds < 0) {
            throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
        }
        if (outroEndSeconds != null && outroEndSeconds < 0) {
            throw new IllegalArgumentException(INVALID_TIMESTAMP_MESSAGE);
        }
        if (outroStartSeconds == null && outroEndSeconds != null) {
            throw new IllegalArgumentException(MISSING_OUTRO_START_FOR_END_MESSAGE);
        }
        if (outroStartSeconds != null && outroEndSeconds != null && outroEndSeconds <= outroStartSeconds) {
            throw new IllegalArgumentException(INVALID_OUTRO_ORDER_MESSAGE);
        }
    }
}
