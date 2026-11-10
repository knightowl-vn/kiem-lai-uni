package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenTimelineTimestampFormat Unit Tests")
class ScreenTimelineTimestampFormatTest {

    @Test
    @DisplayName("parseOptionalSeconds returns null for null, empty, or blank input distinctly from 0")
    void shouldReturnNullForNullOrBlankInput() {
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds(null)).isNull();
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("")).isNull();
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("   ")).isNull();
    }

    @Test
    @DisplayName("parseOptionalSeconds parses MM:SS format accurately including 00:00 as 0L")
    void shouldParseMmSsFormat() {
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("00:00")).isEqualTo(0L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("0:00")).isEqualTo(0L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("00:15")).isEqualTo(15L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("01:45")).isEqualTo(105L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("22:10")).isEqualTo(1330L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("59:59")).isEqualTo(3599L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("  01:45  ")).isEqualTo(105L);
    }

    @Test
    @DisplayName("parseOptionalSeconds parses HH:MM:SS format accurately")
    void shouldParseHhMmSsFormat() {
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("00:00:00")).isEqualTo(0L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("00:01:45")).isEqualTo(105L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("01:00:00")).isEqualTo(3600L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("01:15:30")).isEqualTo(4530L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("02:30:05")).isEqualTo(9005L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds(" 01:15:30 ")).isEqualTo(4530L);
    }

    @Test
    @DisplayName("parseOptionalSeconds parses raw non-negative whole seconds accurately")
    void shouldParseRawWholeSeconds() {
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("0")).isEqualTo(0L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("15")).isEqualTo(15L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("90")).isEqualTo(90L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("105")).isEqualTo(105L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds("4530")).isEqualTo(4530L);
        assertThat(ScreenTimelineTimestampFormat.parseOptionalSeconds(" 105 ")).isEqualTo(105L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-1",
            "-01:30",
            "01:-30",
            "+01:30",
            "01:60",
            "00:75",
            "01:60:00",
            "01:00:60",
            "01:99:00",
            "abc",
            "01:ab",
            "12.5",
            "01:30.5",
            ":30",
            "01:",
            "01::30",
            "01:02:03:04",
            "99999999999999999999999999",
            "9999999999999999999:00:00"
    })
    @DisplayName("parseOptionalSeconds rejects negative, malformed, out-of-range (>=60), and overflowing inputs")
    void shouldRejectInvalidTimestampInputs(String invalidInput) {
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.parseOptionalSeconds(invalidInput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian không hợp lệ. Hãy nhập MM:SS hoặc HH:MM:SS.");
    }

    @Test
    @DisplayName("formatSeconds formats seconds into MM:SS when < 3600 and HH:MM:SS when >= 3600")
    void shouldFormatSecondsProperly() {
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(null)).isNull();
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(-1L)).isNull();
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(0L)).isEqualTo("00:00");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(5L)).isEqualTo("00:05");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(90L)).isEqualTo("01:30");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(105L)).isEqualTo("01:45");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(3599L)).isEqualTo("59:59");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(3600L)).isEqualTo("01:00:00");
        assertThat(ScreenTimelineTimestampFormat.formatSeconds(4530L)).isEqualTo("01:15:30");
    }

    @Test
    @DisplayName("validateIntroPairAndOrder rejects incomplete Intro pairs and introEnd <= introStart with Vietnamese messages")
    void shouldValidateIntroPairAndOrder() {
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateIntroPairAndOrder(15L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng nhập đủ thời gian bắt đầu và kết thúc Intro.");

        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateIntroPairAndOrder(null, 90L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng nhập đủ thời gian bắt đầu và kết thúc Intro.");

        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateIntroPairAndOrder(90L, 90L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian kết thúc Intro phải lớn hơn thời gian bắt đầu.");

        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateIntroPairAndOrder(90L, 30L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian kết thúc Intro phải lớn hơn thời gian bắt đầu.");

        ScreenTimelineTimestampFormat.validateIntroPairAndOrder(null, null);
        ScreenTimelineTimestampFormat.validateIntroPairAndOrder(0L, 90L);
    }

    @Test
    @DisplayName("validateOutroNotBeforeIntroEnd rejects outroStart < introEnd and allows outroStart >= introEnd")
    void shouldValidateOutroNotBeforeIntroEnd() {
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(120L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outro không được bắt đầu trước khi Intro kết thúc.");

        ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(120L, 120L);
        ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(120L, 1200L);
        ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(null, 100L);
        ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(120L, null);
    }

    @Test
    @DisplayName("UX02-C2.1: validateOutroPairAndOrder validates optional Outro end, requiring start when end is present and end > start")
    void shouldValidateOutroPairAndOrder() {
        // Valid cases: both null, or start present with null end, or end > start
        ScreenTimelineTimestampFormat.validateOutroPairAndOrder(null, null);
        ScreenTimelineTimestampFormat.validateOutroPairAndOrder(1452L, null);
        ScreenTimelineTimestampFormat.validateOutroPairAndOrder(1452L, 1530L);

        // Orphaned Outro end
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroPairAndOrder(null, 1530L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không thể thiết lập thời gian kết thúc Outro khi chưa có thời gian bắt đầu.");

        // Negative start or end
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroPairAndOrder(-1L, 1530L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian không hợp lệ. Hãy nhập MM:SS hoặc HH:MM:SS.");

        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroPairAndOrder(1452L, -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian không hợp lệ. Hãy nhập MM:SS hoặc HH:MM:SS.");

        // Reversed or equal order
        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroPairAndOrder(1530L, 1530L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian kết thúc Outro phải lớn hơn thời gian bắt đầu.");

        assertThatThrownBy(() -> ScreenTimelineTimestampFormat.validateOutroPairAndOrder(1530L, 1452L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Thời gian kết thúc Outro phải lớn hơn thời gian bắt đầu.");
    }
}
