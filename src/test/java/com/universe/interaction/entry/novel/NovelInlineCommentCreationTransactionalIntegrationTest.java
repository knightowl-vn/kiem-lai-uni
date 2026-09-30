package com.universe.interaction.entry.novel;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.novel.application.anchor.CreateChapterCommentBlockAnchorUseCase;
import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring transactional integration test verifying atomic creation and rollback
 * across Interaction root comment creation and Novel anchor persistence.
 */
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
        CreateRootCommentUseCase.class,
        CreateChapterCommentBlockAnchorUseCase.class,
        NovelInlineCommentCreationCoordinator.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class
})
@DisplayName("NovelInlineCommentCreationCoordinator Transaction Rollback Integration Tests")
class NovelInlineCommentCreationTransactionalIntegrationTest {

    private static final UUID ACTOR_USER_ID = UUID.fromString("1111bbbb-0000-0000-0000-000000000001");
    private static final UUID CHAPTER_ID = UUID.fromString("2222bbbb-0000-0000-0000-000000000002");
    private static final String BLOCK_KEY = "blk-0123456789abcdef-1";

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NovelInlineCommentCreationCoordinator coordinator;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @MockBean
    private CommentTargetEligibilityPort eligibilityPort;

    @MockBean
    private CommunityPostInteractionMutationPort communityPostInteractionMutationPort;

    @MockBean
    private ChapterAnchorResolutionSourcePort resolutionSourcePort;

    @MockBean
    private ChapterCommentAnchorRepositoryPort anchorRepositoryPort;

    @BeforeEach
    void setUp() {
        cleanData();
        when(eligibilityPort.isEligible(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM interaction_comments WHERE target_id = ?", CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("Transaction rollback: when Novel anchor stage fails, root comment is NOT persisted in DB")
    void shouldRollbackInteractionRootCommentWhenAnchorStageFails() {
        // Snapshot has contentVersion 2, but client requests version 1 -> causes ChapterCommentAnchorVersionConflictException
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                2L,
                List.of(new ReaderBlock(BLOCK_KEY, "Nội dung chương phiên bản 2"))
        ));

        // Coordinate inline creation: root creation succeeds in DB, then anchor throws
        assertThatThrownBy(() -> coordinator.createInlineComment(
                ACTOR_USER_ID,
                CHAPTER_ID,
                "Bình luận thử nghiệm rollback",
                1L, // Mismatch with snapshot version 2L
                BLOCK_KEY
        )).isInstanceOf(ChapterCommentAnchorVersionConflictException.class);

        // Verify that AFTER transaction rollback, NO root comment survived in the database
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE target_id = ?",
                Integer.class,
                CHAPTER_ID.toString()
        );
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Transaction commit: when both stages succeed, root comment is persisted in DB")
    void shouldCommitInteractionRootCommentWhenBothStagesSucceed() {
        String canonicalText = "Đạo khả đạo, phi thường đạo.";
        when(resolutionSourcePort.loadCurrent(CHAPTER_ID)).thenReturn(new ChapterAnchorDocumentSnapshot(
                CHAPTER_ID,
                1L,
                List.of(new ReaderBlock(BLOCK_KEY, canonicalText))
        ));
        when(anchorRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID createdCommentId = coordinator.createInlineComment(
                ACTOR_USER_ID,
                CHAPTER_ID,
                "Bình luận thành công",
                1L,
                BLOCK_KEY
        );

        assertThat(createdCommentId).isNotNull();

        // Verify root comment committed and present in DB
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                createdCommentId.toString()
        );
        assertThat(count).isEqualTo(1);

        // Verify anchor was saved with matching rootCommentId
        verify(anchorRepositoryPort).save(any(ChapterCommentAnchor.class));
    }
}
