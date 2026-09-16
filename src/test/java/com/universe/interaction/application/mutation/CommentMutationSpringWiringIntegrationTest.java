package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.infrastructure.eligibility.CommentTargetEligibilityAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.novel.infrastructure.persistence.reader.ReaderChapterAccessQueryPersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
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
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentTargetEligibilityAdapter.class,
        ReaderChapterAccessQueryPersistenceAdapter.class,
        CreateRootCommentUseCase.class,
        ReplyCommentUseCase.class,
        EditCommentUseCase.class,
        DeleteCommentUseCase.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class
})
@DisplayName("Comment Mutation Spring Wiring & Transaction Proxy Integration Tests")
class CommentMutationSpringWiringIntegrationTest {

    private static final UUID USER_1_ID = UUID.fromString("1111aaaa-0000-0000-0000-000000000001");
    private static final UUID USER_2_ID = UUID.fromString("2222aaaa-0000-0000-0000-000000000002");

    private static final UUID VOLUME_PUB_ID = UUID.fromString("aaaa1111-0000-0000-0000-000000000001");
    private static final UUID VOLUME_DRAFT_ID = UUID.fromString("bbbb1111-0000-0000-0000-000000000002");

    private static final UUID CH_PUB_ID = UUID.fromString("cccc1111-0000-0000-0000-000000000001");
    private static final UUID CH_DRAFT_ID = UUID.fromString("dddd1111-0000-0000-0000-000000000002");
    private static final UUID CH_IN_DRAFT_VOL_ID = UUID.fromString("eeee1111-0000-0000-0000-000000000003");

    private static final int VOL_PUB_SORT_ORDER = 8_100_001;
    private static final int VOL_DRAFT_SORT_ORDER = 8_100_002;
    private static final int CH_PUB_NUM = 8_100_001;
    private static final int CH_DRAFT_NUM = 8_100_002;
    private static final int CH_IN_DRAFT_VOL_NUM = 8_100_003;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Autowired
    private ReplyCommentUseCase replyCommentUseCase;

    @Autowired
    private EditCommentUseCase editCommentUseCase;

    @Autowired
    private DeleteCommentUseCase deleteCommentUseCase;

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
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.update(
                "DELETE FROM interaction_comments WHERE target_type = 'NOVEL_CHAPTER' AND target_id IN (?, ?, ?)",
                CH_PUB_ID.toString(), CH_DRAFT_ID.toString(), CH_IN_DRAFT_VOL_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapters WHERE id IN (?, ?, ?) OR chapter_number IN (?, ?, ?)",
                CH_PUB_ID.toString(), CH_DRAFT_ID.toString(), CH_IN_DRAFT_VOL_ID.toString(),
                CH_PUB_NUM, CH_DRAFT_NUM, CH_IN_DRAFT_VOL_NUM
        );
        jdbcTemplate.update(
                "DELETE FROM novel_volumes WHERE id IN (?, ?) OR sort_order IN (?, ?)",
                VOLUME_PUB_ID.toString(), VOLUME_DRAFT_ID.toString(),
                VOL_PUB_SORT_ORDER, VOL_DRAFT_SORT_ORDER
        );
        jdbcTemplate.update(
                "DELETE FROM identity_users WHERE id IN (?, ?)",
                USER_1_ID.toString(), USER_2_ID.toString()
        );
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. Users
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'novel-comm-user1@universe.local', '$2a$10$hash', 'Comm User 1', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'novel-comm-user2@universe.local', '$2a$10$hash', 'Comm User 2', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_2_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volumes (1 PUBLISHED, 1 DRAFT)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1 Công Khai', 'quyen-1-cong-khai', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_PUB_ID.toString(), VOL_PUB_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 2 Bản Thảo', 'quyen-2-ban-thao', 'Mô tả', ?, 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0)",
                VOLUME_DRAFT_ID.toString(), VOL_DRAFT_SORT_ORDER, USER_1_ID.toString(), USER_1_ID.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters
        seedChapter(CH_PUB_ID, VOLUME_PUB_ID, CH_PUB_NUM, "Chương 1 Đã Xuất Bản", "chuong-1-da-xuat-ban", "PUBLISHED");
        seedChapter(CH_DRAFT_ID, VOLUME_PUB_ID, CH_DRAFT_NUM, "Chương 2 Bản Thảo", "chuong-2-ban-thao", "DRAFT");
        seedChapter(CH_IN_DRAFT_VOL_ID, VOLUME_DRAFT_ID, CH_IN_DRAFT_VOL_NUM, "Chương 3 Thuộc Quyển Nháp", "chuong-3-thuoc-quyen-nhap", "PUBLISHED");
    }

    private void seedChapter(UUID id, UUID volumeId, int chapterNumber, String title, String slug, String status) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, content_version, aggregate_version, persistence_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', ?, ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 1, 0)",
                id.toString(), volumeId.toString(), chapterNumber, title, slug, status,
                USER_1_ID.toString(), USER_1_ID.toString(),
                status.equals("PUBLISHED") ? USER_1_ID.toString() : null,
                Timestamp.from(now), Timestamp.from(now),
                status.equals("PUBLISHED") ? Timestamp.from(now) : null
        );
    }

    @Test
    @DisplayName("Should prove all four mutation use cases are Spring AOP proxies")
    void shouldProveAllMutationUseCasesAreAopProxies() {
        assertThat(AopUtils.isAopProxy(createRootCommentUseCase))
                .as("CreateRootCommentUseCase must be a Spring AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(replyCommentUseCase))
                .as("ReplyCommentUseCase must be a Spring AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(editCommentUseCase))
                .as("EditCommentUseCase must be a Spring AOP proxy")
                .isTrue();
        assertThat(AopUtils.isAopProxy(deleteCommentUseCase))
                .as("DeleteCommentUseCase must be a Spring AOP proxy")
                .isTrue();
    }

    @Test
    @DisplayName("Should create root comment on published novel chapter through Spring proxy")
    void shouldCreateRootCommentOnPublishedNovelChapter() {
        CommentTarget target = CommentTarget.novelChapter(CH_PUB_ID);
        CreateRootCommentCommand command = new CreateRootCommentCommand(USER_1_ID, target, "Tác phẩm xuất sắc!");

        Comment root = createRootCommentUseCase.execute(command);

        assertThat(root).isNotNull();
        assertThat(root.getId()).isNotNull();
        assertThat(root.getTarget()).isEqualTo(target);
        assertThat(root.getAuthorUserId()).isEqualTo(USER_1_ID);
        assertThat(root.getBody()).isEqualTo("Tác phẩm xuất sắc!");
        assertThat(root.getStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(root.isRoot()).isTrue();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                root.getId().toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Should reject root comment on DRAFT novel chapter and persist nothing")
    void shouldRejectRootCommentOnDraftChapter() {
        CommentTarget target = CommentTarget.novelChapter(CH_DRAFT_ID);
        CreateRootCommentCommand command = new CreateRootCommentCommand(USER_1_ID, target, "Bình luận nháp");

        assertThatThrownBy(() -> createRootCommentUseCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class)
                .hasMessageContaining("Comment target is not eligible for comments");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM interaction_comments WHERE target_id = ?",
                Integer.class,
                CH_DRAFT_ID.toString()
        );
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Should reject root comment on PUBLISHED chapter within DRAFT volume")
    void shouldRejectRootCommentOnChapterInDraftVolume() {
        CommentTarget target = CommentTarget.novelChapter(CH_IN_DRAFT_VOL_ID);
        CreateRootCommentCommand command = new CreateRootCommentCommand(USER_1_ID, target, "Bình luận quyển nháp");

        assertThatThrownBy(() -> createRootCommentUseCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("Should reject root comment on non-existent chapter ID")
    void shouldRejectRootCommentOnNonExistentChapter() {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        CreateRootCommentCommand command = new CreateRootCommentCommand(USER_1_ID, target, "Không tồn tại");

        assertThatThrownBy(() -> createRootCommentUseCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("Should reject root comment on WIKI_ARTICLE target (deferred to MS-05E6)")
    void shouldRejectRootCommentOnWikiArticle() {
        CommentTarget target = CommentTarget.wikiArticle(UUID.randomUUID());
        CreateRootCommentCommand command = new CreateRootCommentCommand(USER_1_ID, target, "Wiki comment");

        assertThatThrownBy(() -> createRootCommentUseCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("Should execute ReplyCommentUseCase through Spring proxy with mandatory lock passing")
    void shouldExecuteReplyThroughSpringProxyWithLock() {
        // 1. Create root comment
        CommentTarget target = CommentTarget.novelChapter(CH_PUB_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_1_ID, target, "Root comment")
        );

        // 2. Reply to root comment (proves ReplyCommentUseCase starts transaction for Propagation.MANDATORY lock)
        ReplyCommentCommand replyCmd = new ReplyCommentCommand(USER_2_ID, root.getId(), "Phản hồi đầu tiên");
        Comment reply = replyCommentUseCase.execute(replyCmd);

        assertThat(reply).isNotNull();
        assertThat(reply.isReply()).isTrue();
        assertThat(reply.getParentCommentId()).isEqualTo(root.getId());
        assertThat(reply.getThreadRootCommentId()).isEqualTo(root.getId());
        assertThat(reply.getAuthorUserId()).isEqualTo(USER_2_ID);
        assertThat(reply.getBody()).isEqualTo("Phản hồi đầu tiên");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                reply.getId().toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Should execute EditCommentUseCase through Spring proxy with mandatory lock passing")
    void shouldExecuteEditThroughSpringProxyWithLock() {
        CommentTarget target = CommentTarget.novelChapter(CH_PUB_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_1_ID, target, "Initial body")
        );

        // Edit via use case through Spring proxy
        EditCommentCommand editCmd = new EditCommentCommand(USER_1_ID, root.getId(), "Updated body");
        Comment edited = editCommentUseCase.execute(editCmd);

        assertThat(edited.getBody()).isEqualTo("Updated body");
        assertThat(edited.getUpdatedAt()).isAfterOrEqualTo(root.getCreatedAt());

        String dbBody = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                root.getId().toString()
        );
        assertThat(dbBody).isEqualTo("Updated body");
    }

    @Test
    @DisplayName("Should execute DeleteCommentUseCase through Spring proxy with mandatory lock passing")
    void shouldExecuteDeleteThroughSpringProxyWithLock() {
        CommentTarget target = CommentTarget.novelChapter(CH_PUB_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_1_ID, target, "To be deleted")
        );

        // Delete via usecase through Spring proxy
        DeleteCommentCommand deleteCmd = new DeleteCommentCommand(USER_1_ID, root.getId());
        Comment deleted = deleteCommentUseCase.execute(deleteCmd);

        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM interaction_comments WHERE id = ?",
                String.class,
                root.getId().toString()
        );
        assertThat(dbStatus).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("Should prove findByIdForUpdate requires active transaction when called outside Spring proxy")
    void shouldProveFindByIdForUpdateRequiresActiveTransaction() {
        CommentTarget target = CommentTarget.novelChapter(CH_PUB_ID);
        Comment root = createRootCommentUseCase.execute(
                new CreateRootCommentCommand(USER_1_ID, target, "Root for direct test")
        );

        // Calling findByIdForUpdate directly without an outer transaction must throw IllegalTransactionStateException
        assertThatThrownBy(() -> commentRepositoryPort.findByIdForUpdate(root.getId()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }
}
