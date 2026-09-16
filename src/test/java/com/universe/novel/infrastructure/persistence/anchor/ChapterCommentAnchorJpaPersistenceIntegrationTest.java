package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorKind;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        ChapterCommentAnchorPersistenceAdapter.class,
        ChapterCommentAnchorPersistenceMapper.class
})
@DisplayName("ChapterCommentAnchor Real MySQL Persistence Integration Tests")
class ChapterCommentAnchorJpaPersistenceIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("1111aaaa-1111-2222-3333-444444444444");

    private static final UUID VOLUME_ID =
            UUID.fromString("2222bbbb-1111-2222-3333-444444444444");

    private static final UUID CHAPTER_ID =
            UUID.fromString("3333cccc-1111-2222-3333-444444444444");

    private static final UUID ROOT_COMMENT_1_ID =
            UUID.fromString("4444dddd-1111-2222-3333-444444444444");

    private static final UUID ROOT_COMMENT_2_ID =
            UUID.fromString("5555eeee-1111-2222-3333-444444444444");

    private static final String BLOCK_KEY_1 = "blk-0123456789abcdef-1";
    private static final String BLOCK_KEY_2 = "blk-abcdef0123456789-2";

    private static final int VOLUME_SORT_ORDER = 7_000_001;
    private static final int CHAPTER_NUMBER = 7_000_001;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @BeforeEach
    void setUp() {
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("DELETE FROM novel_chapter_comment_anchors WHERE chapter_id = ?",
                CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id = ? OR id = ? OR chapter_number = ?",
                VOLUME_ID.toString(), CHAPTER_ID.toString(), CHAPTER_NUMBER);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?",
                USER_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'anchor-user@universe.local', '$2a$10$hash', 'Anchor User', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Anchor Test', 'quyen-anchor-test', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapter
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, 'Chương Anchor Test', 'chuong-7000001-anchor', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER,
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("1. BLOCK anchor: Lưu và đọc khứ hồi thành công")
    void shouldSaveAndFindBlockAnchor() {
        Instant now = Instant.now();
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_1_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_1,
                "Đoạn văn hoàn chỉnh được đánh dấu làm thảo luận.",
                now
        );

        anchorRepositoryPort.save(anchor);

        Optional<ChapterCommentAnchor> found = anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_1_ID);
        assertThat(found).isPresent();
        ChapterCommentAnchor actual = found.get();
        assertThat(actual.getRootCommentId()).isEqualTo(ROOT_COMMENT_1_ID);
        assertThat(actual.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(actual.getContentVersion()).isEqualTo(1L);
        assertThat(actual.getBlockKey()).isEqualTo(BLOCK_KEY_1);
        assertThat(actual.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(actual.getStartOffset()).isNull();
        assertThat(actual.getEndOffset()).isNull();
        assertThat(actual.getSelectedText()).isEqualTo("Đoạn văn hoàn chỉnh được đánh dấu làm thảo luận.");
        assertThat(actual.getContextBefore()).isEmpty();
        assertThat(actual.getContextAfter()).isEmpty();
    }

    @Test
    @DisplayName("2. TEXT_RANGE anchor: Lưu và đọc khứ hồi thành công với offset và ngữ cảnh")
    void shouldSaveAndFindTextRangeAnchor() {
        Instant now = Instant.now();
        String selected = "cụm từ nổi bật";
        int start = 15;
        int end = start + selected.length(); // 29

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_2_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_2,
                start,
                end,
                selected,
                "Phía trước có ",
                ", phía sau có tiếp.",
                now
        );

        anchorRepositoryPort.save(anchor);

        Optional<ChapterCommentAnchor> found = anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_2_ID);
        assertThat(found).isPresent();
        ChapterCommentAnchor actual = found.get();
        assertThat(actual.getRootCommentId()).isEqualTo(ROOT_COMMENT_2_ID);
        assertThat(actual.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(actual.getContentVersion()).isEqualTo(1L);
        assertThat(actual.getBlockKey()).isEqualTo(BLOCK_KEY_2);
        assertThat(actual.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(actual.getStartOffset()).isEqualTo(start);
        assertThat(actual.getEndOffset()).isEqualTo(end);
        assertThat(actual.getSelectedText()).isEqualTo(selected);
        assertThat(actual.getContextBefore()).isEqualTo("Phía trước có ");
        assertThat(actual.getContextAfter()).isEqualTo(", phía sau có tiếp.");
    }

    @Test
    @DisplayName("3. Duplicate rootCommentId: Ném DataIntegrityViolationException và không ghi đè")
    void shouldRejectDuplicateRootCommentId() {
        Instant now = Instant.now();
        ChapterCommentAnchor anchor1 = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_1_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_1,
                "Văn bản khối 1",
                now
        );
        anchorRepositoryPort.save(anchor1);

        ChapterCommentAnchor anchor2 = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_1_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_2,
                "Văn bản khối 2 (cố ý trùng rootCommentId)",
                now.plusSeconds(5)
        );

        assertThatThrownBy(() -> anchorRepositoryPort.save(anchor2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify original data is preserved and not overwritten
        Optional<ChapterCommentAnchor> preserved = anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_1_ID);
        assertThat(preserved).isPresent();
        assertThat(preserved.get().getBlockKey()).isEqualTo(BLOCK_KEY_1);
        assertThat(preserved.get().getSelectedText()).isEqualTo("Văn bản khối 1");
    }

    @Test
    @DisplayName("4. Database constraints: Kiểm tra các CHECK constraint tại tầng MySQL")
    void shouldEnforceDatabaseConstraints() {
        Instant now = Instant.now();

        // 4a. content_version < 1 vi phạm chk_novel_chapter_comment_anchors_version
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 0, 'blk-0123456789abcdef-1', 'BLOCK', NULL, NULL, 'Text', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_version");

        // 4b. anchor_kind không hợp lệ vi phạm chk_novel_chapter_comment_anchors_kind
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'blk-0123456789abcdef-1', 'INVALID_KIND', NULL, NULL, 'Text', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_kind");

        // 4c. BLOCK với offset vi phạm chk_novel_chapter_comment_anchors_shape
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'blk-0123456789abcdef-1', 'BLOCK', 0, 10, 'Text', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_shape");

        // 4d. BLOCK với non-empty context vi phạm chk_novel_chapter_comment_anchors_shape
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'blk-0123456789abcdef-1', 'BLOCK', NULL, NULL, 'Text', 'invalid_context', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_shape");

        // 4e. TEXT_RANGE với end_offset <= start_offset vi phạm chk_novel_chapter_comment_anchors_shape
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'blk-0123456789abcdef-1', 'TEXT_RANGE', 20, 10, 'Text', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_shape");

        // 4f. block_key không bắt đầu bằng blk- vi phạm chk_novel_chapter_comment_anchors_block_key
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'invalid_key', 'BLOCK', NULL, NULL, 'Text', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_block_key");

        // 4g. selected_text rỗng vi phạm chk_novel_chapter_comment_anchors_selected_text
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO novel_chapter_comment_anchors (root_comment_id, chapter_id, content_version, block_key, anchor_kind, start_offset, end_offset, selected_text, context_before, context_after, created_at) " +
                        "VALUES (?, ?, 1, 'blk-0123456789abcdef-1', 'BLOCK', NULL, NULL, '', '', '', ?)",
                UUID.randomUUID().toString(), CHAPTER_ID.toString(), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_novel_chapter_comment_anchors_selected_text");
    }

    @Test
    @DisplayName("5. Cross-context FK absence: rootCommentId không có khóa ngoại tới interaction_comments")
    void shouldConfirmNoCrossContextForeignKeyToInteractionComments() {
        // Can insert anchor for rootCommentId that does NOT exist in interaction_comments
        UUID nonExistentCommentId = UUID.randomUUID();
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                nonExistentCommentId,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_1,
                "Thảo luận trên bình luận không tồn tại trong DB tương tác",
                Instant.now()
        );

        anchorRepositoryPort.save(anchor);
        assertThat(anchorRepositoryPort.findByRootCommentId(nonExistentCommentId)).isPresent();

        // Verify via information_schema that NO foreign key references interaction_comments
        Integer fkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = 'novel_chapter_comment_anchors' " +
                        "AND REFERENCED_TABLE_NAME = 'interaction_comments'",
                Integer.class
        );
        assertThat(fkCount).isZero();
    }

    @Test
    @DisplayName("6. Same-context chapter cascade: Xóa chương trong Novel tự động xóa anchor tương ứng (ON DELETE CASCADE)")
    void shouldCascadeDeleteWhenChapterIsDeleted() {
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_1_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_1,
                "Nội dung khối cần cascade",
                Instant.now()
        );
        anchorRepositoryPort.save(anchor);
        assertThat(anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_1_ID)).isPresent();

        // Delete the parent chapter
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?", CHAPTER_ID.toString());

        // Anchor row must be cascaded
        Optional<ChapterCommentAnchor> afterChapterDelete = anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_1_ID);
        assertThat(afterChapterDelete).isEmpty();
    }

    @Test
    @DisplayName("7. MEDIUMTEXT: Lưu và đọc lại khối selectedText vượt quá giới hạn TEXT chuẩn (65,535 bytes)")
    void shouldSaveAndRetrieveMediumTextAnchorExceedingStandardTextLimit() {
        // Standard MySQL TEXT maximum size is 65,535 bytes.
        // MEDIUMTEXT supports up to 16 MB (16,777,215 bytes).
        // Test with 70,200 characters which exceeds 65,535 bytes.
        String largeText = "KiemLai Large Anchor Text ".repeat(2700);
        assertThat(largeText.length()).isGreaterThan(65_535);

        ChapterCommentAnchor largeAnchor = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_1_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY_1,
                largeText,
                Instant.now()
        );

        anchorRepositoryPort.save(largeAnchor);

        Optional<ChapterCommentAnchor> found = anchorRepositoryPort.findByRootCommentId(ROOT_COMMENT_1_ID);
        assertThat(found).isPresent();
        assertThat(found.get().getSelectedText()).isEqualTo(largeText);
        assertThat(found.get().getSelectedText().length()).isEqualTo(largeText.length());
    }
}
