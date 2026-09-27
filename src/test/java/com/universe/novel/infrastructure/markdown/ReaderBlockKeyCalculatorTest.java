package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderBlockKeyCalculatorTest {

    @Test
    @DisplayName("normalizeProseText collapses whitespace and replaces line endings with single spaces")
    void normalizeProseTextCollapsesWhitespace() {
        String input = "  Đoạn   văn \r\n có \t nhiều   khoảng trắng.  ";
        String normalized = ReaderBlockKeyCalculator.normalizeProseText(input);

        assertThat(normalized).isEqualTo("Đoạn văn có nhiều khoảng trắng.");
    }

    @Test
    @DisplayName("normalizeCodeText preserves internal newlines and indentation while normalizing linebreaks")
    void normalizeCodeTextPreservesInternalIndentation() {
        String code = "\r\n  public void hello() {\r\n      return;\r\n  }\r\n\r\n";
        String normalized = ReaderBlockKeyCalculator.normalizeCodeText(code);

        assertThat(normalized).isEqualTo("  public void hello() {\n      return;\n  }");
    }

    @Test
    @DisplayName("normalizeCodeText removes leading and trailing blank lines only, preserving nonblank indentation")
    void normalizeCodeTextRemovesBlankLinesOnly() {
        String code = "\n  foo()\n    bar()\n\n";
        String normalized = ReaderBlockKeyCalculator.normalizeCodeText(code);

        assertThat(normalized).isEqualTo("  foo()\n    bar()");
    }

    @Test
    @DisplayName("normalizeCodeText preserves non-ASCII whitespace characters inside code lines rather than converting them to ASCII spaces")
    void normalizeCodeTextPreservesNonAsciiWhitespace() {
        String codeWithNbsp = "  val\u00A0x = 1;";
        String codeWithAscii = "  val x = 1;";

        String normNbsp = ReaderBlockKeyCalculator.normalizeCodeText(codeWithNbsp);
        String normAscii = ReaderBlockKeyCalculator.normalizeCodeText(codeWithAscii);

        assertThat(normNbsp).isEqualTo("  val\u00A0x = 1;");
        assertThat(normNbsp).contains("\u00A0");
        assertThat(normNbsp).isNotEqualTo(normAscii);
    }

    @Test
    @DisplayName("normalizeProseText preserves visible text differences such as excessive dots without collapsing them")
    void normalizeProseTextPreservesExcessiveDots() {
        String input1 = "Khoan.... đã.";
        String input2 = "Khoan... đã.";

        String norm1 = ReaderBlockKeyCalculator.normalizeProseText(input1);
        String norm2 = ReaderBlockKeyCalculator.normalizeProseText(input2);

        assertThat(norm1).isEqualTo("Khoan.... đã.");
        assertThat(norm2).isEqualTo("Khoan... đã.");
        assertThat(norm1).isNotEqualTo(norm2);
    }

    @Test
    @DisplayName("formatTableRowFingerprintContent produces unambiguous length-prefixed cell encoding")
    void formatTableRowFingerprintContentIsUnambiguous() {
        String row1 = ReaderBlockKeyCalculator.formatTableRowFingerprintContent(java.util.List.of("A, B", "C"));
        String row2 = ReaderBlockKeyCalculator.formatTableRowFingerprintContent(java.util.List.of("A", "B, C"));

        assertThat(row1).isEqualTo("4:A, B;1:C;");
        assertThat(row2).isEqualTo("1:A;4:B, C;");
        assertThat(row1).isNotEqualTo(row2);
    }

    @Test
    @DisplayName("computeFingerprint produces deterministic 16-character hex hash")
    void computeFingerprintIsDeterministic() {
        String fp1 = ReaderBlockKeyCalculator.computeFingerprint("paragraph", "Xin chào thế giới");
        String fp2 = ReaderBlockKeyCalculator.computeFingerprint("paragraph", "Xin chào thế giới");
        String fp3 = ReaderBlockKeyCalculator.computeFingerprint("heading", "Xin chào thế giới");

        assertThat(fp1).hasSize(16);
        assertThat(fp1).matches("^[0-9a-f]{16}$");
        assertThat(fp1).isEqualTo(fp2);
        // Different block kinds produce different fingerprints
        assertThat(fp1).isNotEqualTo(fp3);
    }

    @Test
    @DisplayName("formatBlockKey produces opaque, safe data attribute key")
    void formatBlockKeyProducesExpectedFormat() {
        String key = ReaderBlockKeyCalculator.formatBlockKey("1234567890abcdef", 2);

        assertThat(key).isEqualTo("blk-1234567890abcdef-2");
    }
}
