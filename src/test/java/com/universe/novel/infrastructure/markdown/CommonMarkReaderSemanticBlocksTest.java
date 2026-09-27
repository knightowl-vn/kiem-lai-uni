package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkReaderSemanticBlocksTest {

    private final CommonMarkReaderSemanticBlocks semanticBlocks = new CommonMarkReaderSemanticBlocks();

    @Test
    @DisplayName("parse returns canonical semantic blocks with block keys and document AST")
    void parseExtractsCanonicalSemanticBlocks() {
        String markdown = """
                # Tiêu đề

                Đoạn văn mở đầu.

                - Danh sách 1

                ```
                int x = 0;
                ```

                | C1 | C2 |
                | --- | --- |
                | V1 | V2 |
                """;

        var doc = semanticBlocks.parse(markdown);

        assertThat(doc.document()).isNotNull();
        assertThat(doc.blocks()).hasSize(6);

        assertThat(doc.blocks().get(0).blockKind()).isEqualTo("heading");
        assertThat(doc.blocks().get(0).blockKey()).startsWith("blk-");

        assertThat(doc.blocks().get(1).blockKind()).isEqualTo("paragraph");
        assertThat(doc.blocks().get(1).blockKey()).startsWith("blk-");

        assertThat(doc.blocks().get(2).blockKind()).isEqualTo("list_item");
        assertThat(doc.blocks().get(2).blockKey()).startsWith("blk-");

        assertThat(doc.blocks().get(3).blockKind()).isEqualTo("code_block");
        assertThat(doc.blocks().get(3).blockKey()).startsWith("blk-");

        // Header table row
        assertThat(doc.blocks().get(4).blockKind()).isEqualTo("table_row");
        assertThat(doc.blocks().get(4).blockKey()).startsWith("blk-");
        assertThat(doc.blocks().get(4).rawCells()).containsExactly("C1", "C2");

        // Body table row
        assertThat(doc.blocks().get(5).blockKind()).isEqualTo("table_row");
        assertThat(doc.blocks().get(5).blockKey()).startsWith("blk-");
        assertThat(doc.blocks().get(5).rawCells()).containsExactly("V1", "V2");
    }
}
