package com.universe.wiki.application.contribution;

import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSubmissionThrottlePort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionSourcePersistenceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@Import({
        WikiContributionPersistenceAdapter.class,
        WikiContributionSourcePersistenceAdapter.class,
        SubmitWikiContributionUseCase.class,
        SubmitWikiContributionIntegrationTest.IntegrationTestConfig.class
})
@DisplayName("SubmitWikiContribution Integration Tests")
class SubmitWikiContributionIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanDbBeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanDbBeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_credits");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_workflow_events");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_sources");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contributions");
            String dbName = TestDatabaseSupport.resolveDatabaseName();
            Integer revIdxExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND index_name = 'idx_wiki_article_revisions_source_contribution'",
                    Integer.class,
                    dbName
            );
            if (revIdxExists != null && revIdxExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP INDEX idx_wiki_article_revisions_source_contribution");
            }
            Integer revColExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND column_name = 'source_contribution_id'",
                    Integer.class,
                    dbName
            );
            if (revColExists != null && revColExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP COLUMN source_contribution_id");
            }
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version IN ('65', '66', '67', '68', '69')");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clean database baseline before Flyway in SubmitWikiContributionIntegrationTest", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiContributionRepositoryPort contributionRepository;

    @Autowired
    private TestFailableSourceRepository testFailableSourceRepository;

    @Autowired
    private TestArticleRepository testArticleRepository;

    @Autowired
    private MutableTestClock testClock;

    @Autowired
    private SubmitWikiContributionUseCase useCase;

    @BeforeEach
    void setUp() {
        cleanData();
        testFailableSourceRepository.setFailOnSave(false);
        testClock.setCurrentTime(Instant.parse("2026-09-24T10:00:00Z"));

        // Khởi tạo bài viết mẫu đã publish
        WikiArticle article = WikiArticle.rehydrate(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug("tran-binh-an"),
                ArticleType.CHARACTER,
                "Tóm tắt nhân vật Trần Bình An",
                "Nội dung bài viết về kiếm khí trường thành.",
                null, 50, 50,
                ArticleStatus.PUBLISHED,
                AUTHOR_ID, AUTHOR_ID, AUTHOR_ID, null,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:05:00Z"),
                Instant.parse("2026-09-20T10:05:00Z"),
                null,
                1L, 1L
        );
        testArticleRepository.register(article);
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.update("DELETE FROM wiki_contributions");
    }

    @Test
    @DisplayName("Gửi thành công đóng góp GENERAL kèm 2 nguồn tham khảo và kiểm tra dữ liệu thật trong MySQL")
    void shouldSubmitGeneralContributionWithSourcesSuccessfully() {
        SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "WORDING",
                "Đóng góp điều chỉnh câu từ cho phần mở đầu nhân vật Trần Bình An.",
                null, null, null, null,
                List.of(
                        "/wiki/articles/tran-binh-an",
                        "https://example.com/source-ref"
                )
        );

        SubmitWikiContributionResult result = useCase.execute(command);

        assertThat(result.alreadySubmitted()).isFalse();
        assertThat(result.status()).isEqualTo("NEW");
        assertThat(result.contributionId()).isNotNull();

        // Kiểm tra dữ liệu trong bảng wiki_contributions
        Integer contribCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contributions WHERE id = ?",
                Integer.class,
                result.contributionId().toString()
        );
        assertThat(contribCount).isEqualTo(1);

        Map<String, Object> contribRow = jdbcTemplate.queryForMap(
                "SELECT * FROM wiki_contributions WHERE id = ?",
                result.contributionId().toString()
        );
        assertThat(contribRow.get("article_id")).isEqualTo(ARTICLE_ID.toString());
        assertThat(contribRow.get("article_type_snapshot")).isEqualTo("CHARACTER");
        assertThat(contribRow.get("article_title_snapshot")).isEqualTo("Trần Bình An");
        assertThat(contribRow.get("article_slug_snapshot")).isEqualTo("tran-binh-an");
        assertThat(((Number) contribRow.get("article_content_version")).longValue()).isEqualTo(1L);
        assertThat(contribRow.get("submitted_by_user_id")).isEqualTo(USER_ID.toString());
        assertThat(contribRow.get("context_type")).isEqualTo("GENERAL");
        assertThat(contribRow.get("contribution_type")).isEqualTo("WORDING");
        assertThat(contribRow.get("status")).isEqualTo("NEW");
        assertThat(((Number) contribRow.get("version")).longValue()).isEqualTo(0L);

        // Kiểm tra dữ liệu trong bảng wiki_contribution_sources
        List<Map<String, Object>> sourceRows = jdbcTemplate.queryForList(
                "SELECT * FROM wiki_contribution_sources WHERE contribution_id = ? ORDER BY source_order ASC",
                result.contributionId().toString()
        );
        assertThat(sourceRows).hasSize(2);

        Map<String, Object> s0 = sourceRows.get(0);
        assertThat(((Number) s0.get("source_order")).intValue()).isEqualTo(0);
        assertThat(s0.get("source_type")).isEqualTo("INTERNAL");
        assertThat(s0.get("url")).isEqualTo("/wiki/articles/tran-binh-an");

        Map<String, Object> s1 = sourceRows.get(1);
        assertThat(((Number) s1.get("source_order")).intValue()).isEqualTo(1);
        assertThat(s1.get("source_type")).isEqualTo("EXTERNAL");
        assertThat(s1.get("url")).isEqualTo("https://example.com/source-ref");
    }

    @Test
    @DisplayName("Gửi thành công đóng góp TEXT_SELECTION với đầy đủ trường neo đoạn văn bản")
    void shouldSubmitTextSelectionContributionSuccessfully() {
        SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "TEXT_SELECTION",
                "INCORRECT_INFORMATION",
                "Đoạn này bị nhầm lẫn giữa Kiếm khí trường thành và Lạc Phách sơn.",
                "Kiếm khí trường thành sừng sững nghìn năm",
                "Mở đầu đoạn trước",
                "Kết thúc đoạn sau",
                "phan-1-tong-quan",
                Collections.emptyList()
        );

        SubmitWikiContributionResult result = useCase.execute(command);

        assertThat(result.alreadySubmitted()).isFalse();

        Map<String, Object> contribRow = jdbcTemplate.queryForMap(
                "SELECT * FROM wiki_contributions WHERE id = ?",
                result.contributionId().toString()
        );
        assertThat(contribRow.get("context_type")).isEqualTo("TEXT_SELECTION");
        assertThat(contribRow.get("selected_text")).isEqualTo("Kiếm khí trường thành sừng sững nghìn năm");
        assertThat(contribRow.get("selected_prefix")).isEqualTo("Mở đầu đoạn trước");
        assertThat(contribRow.get("selected_suffix")).isEqualTo("Kết thúc đoạn sau");
        assertThat(contribRow.get("selected_heading_anchor")).isEqualTo("phan-1-tong-quan");
    }

    @Test
    @DisplayName("Chấp nhận reader contentVersion cũ (17) khi bài viết hiện tại đã lên 19")
    void shouldAcceptStaleReaderVersionAndPersistSnapshotAccurately() {
        // Cập nhật bài viết lên contentVersion = 19
        WikiArticle articleV19 = WikiArticle.rehydrate(
                ARTICLE_ID,
                "Trần Bình An (Bản sửa đổi)",
                new Slug("tran-binh-an"),
                ArticleType.CHARACTER,
                "Tóm tắt mới",
                "Nội dung v19",
                null, 50, 50,
                ArticleStatus.PUBLISHED,
                AUTHOR_ID, AUTHOR_ID, AUTHOR_ID, null,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-21T10:05:00Z"),
                Instant.parse("2026-09-21T10:05:00Z"),
                null,
                19L, 19L
        );
        testArticleRepository.register(articleV19);
        assertThat(articleV19.getContentVersion()).isEqualTo(19L);

        // Độc giả gửi đóng góp với version = 17
        SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                17L,
                "GENERAL",
                "MISSING_INFORMATION",
                "Độc giả thấy ở bản v17 còn thiếu thông tin bối cảnh lịch sử.",
                null, null, null, null,
                Collections.emptyList()
        );

        SubmitWikiContributionResult result = useCase.execute(command);
        assertThat(result.alreadySubmitted()).isFalse();

        Map<String, Object> contribRow = jdbcTemplate.queryForMap(
                "SELECT * FROM wiki_contributions WHERE id = ?",
                result.contributionId().toString()
        );
        assertThat(((Number) contribRow.get("article_content_version")).longValue()).isEqualTo(17L);
        assertThat(contribRow.get("article_title_snapshot")).isEqualTo("Trần Bình An (Bản sửa đổi)");
    }

    @Test
    @DisplayName("Từ chối khi reader contentVersion (20) lớn hơn contentVersion hiện tại (19) của bài viết -> 0 rows trong DB")
    void shouldRejectFutureReaderContentVersion() {
        WikiArticle articleV19 = WikiArticle.rehydrate(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug("tran-binh-an"),
                ArticleType.CHARACTER,
                "Tóm tắt",
                "Nội dung",
                null, 50, 50,
                ArticleStatus.PUBLISHED,
                AUTHOR_ID, AUTHOR_ID, AUTHOR_ID, null,
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:05:00Z"),
                Instant.parse("2026-09-20T10:05:00Z"),
                null,
                19L, 19L
        );
        testArticleRepository.register(articleV19);

        SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                20L, // reader version 20 > current version 19
                "GENERAL",
                "WORDING",
                "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                null, null, null, null,
                Collections.emptyList()
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được lớn hơn phiên bản hiện tại");

        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(total).isZero();
    }

    @Test
    @DisplayName("Cơ chế phòng ngừa trùng lặp 60s trên MySQL: nguồn đảo thứ tự, đúng biên 60s, và vượt quá 60s")
    void shouldVerifyDeduplicationGuardAgainstRealDatabase() {
        SubmitWikiContributionCommand command1 = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "SOURCE_REFERENCE",
                "Cần bổ sung nguồn tham khảo chính xác từ nguyên tác chương 100.",
                null, null, null, null,
                List.of("https://source1.example.com", "https://source2.example.com")
        );

        // 1. Lần gửi đầu tiên: thành công tạo mới
        SubmitWikiContributionResult firstResult = useCase.execute(command1);
        assertThat(firstResult.alreadySubmitted()).isFalse();

        Integer countAfterFirst = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterFirst).isEqualTo(1);

        // 2. Lần gửi thứ hai trong 15s tiếp theo với nguồn đảo thứ tự [source2, source1] -> bị chặn trùng lặp
        testClock.advanceSeconds(15);
        SubmitWikiContributionCommand command2 = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "SOURCE_REFERENCE",
                "Cần bổ sung nguồn tham khảo chính xác từ nguyên tác chương 100.",
                null, null, null, null,
                List.of("https://source2.example.com", "https://source1.example.com") // reversed order
        );

        SubmitWikiContributionResult secondResult = useCase.execute(command2);
        assertThat(secondResult.alreadySubmitted()).isTrue();
        assertThat(secondResult.contributionId()).isEqualTo(firstResult.contributionId());

        Integer countAfterSecond = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterSecond).isEqualTo(1);

        // 3. Lần gửi thứ ba tại đúng mốc 60 giây kể từ lần đầu (15s + 45s = 60s) -> vẫn bị chặn trùng lặp
        testClock.advanceSeconds(45);
        SubmitWikiContributionResult boundaryResult = useCase.execute(command1);
        assertThat(boundaryResult.alreadySubmitted()).isTrue();
        assertThat(boundaryResult.contributionId()).isEqualTo(firstResult.contributionId());

        Integer countAfterBoundary = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterBoundary).isEqualTo(1);

        // 4. Lần gửi thứ tư sau khi đã quá 60s (tổng cộng 60s + 10s = 70s) -> cho phép tạo mới
        testClock.advanceSeconds(10);
        SubmitWikiContributionResult fourthResult = useCase.execute(command1);
        assertThat(fourthResult.alreadySubmitted()).isFalse();
        assertThat(fourthResult.contributionId()).isNotEqualTo(firstResult.contributionId());

        Integer countAfterFourth = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterFourth).isEqualTo(2);
    }

    @Test
    @DisplayName("Bằng chứng khác biệt nguồn tham khảo trong 60s (Different-Evidence): [X] vs [X, Y] không bị coi là trùng lặp")
    void shouldAllowNewSubmissionWhenSourcesDifferWithinSixtySeconds() {
        // Submission A: chỉ có nguồn X
        SubmitWikiContributionCommand commandA = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "SOURCE_REFERENCE",
                "Cần bổ sung nguồn tham khảo chính xác từ nguyên tác chương 100.",
                null, null, null, null,
                List.of("https://source-x.example.com")
        );

        SubmitWikiContributionResult resultA = useCase.execute(commandA);
        assertThat(resultA.alreadySubmitted()).isFalse();

        Integer countAfterA = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterA).isEqualTo(1);

        // Submission B: trong 20s tiếp theo có cả nguồn X và Y -> KHÔNG bị chặn
        testClock.advanceSeconds(20);
        SubmitWikiContributionCommand commandB = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "SOURCE_REFERENCE",
                "Cần bổ sung nguồn tham khảo chính xác từ nguyên tác chương 100.",
                null, null, null, null,
                List.of("https://source-x.example.com", "https://source-y.example.com")
        );

        SubmitWikiContributionResult resultB = useCase.execute(commandB);
        assertThat(resultB.alreadySubmitted()).isFalse();
        assertThat(resultB.contributionId()).isNotEqualTo(resultA.contributionId());

        Integer countAfterB = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterB).isEqualTo(2);

        // 3. Tiến đồng hồ qua 65s (để submission A ra khỏi cửa sổ 60s, chỉ còn submission B [X, Y])
        testClock.advanceSeconds(65);
        // Submission C: gửi lại [X] trong khi trong cửa sổ 60s chỉ có candidate B với [X, Y] -> KHÔNG bằng nhau -> KHÔNG bị chặn
        SubmitWikiContributionResult resultC = useCase.execute(commandA);
        assertThat(resultC.alreadySubmitted()).isFalse();
        assertThat(resultC.contributionId()).isNotEqualTo(resultB.contributionId());

        Integer countAfterC = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wiki_contributions", Integer.class);
        assertThat(countAfterC).isEqualTo(3);
    }

    @Test
    @DisplayName("Minh chứng tính nguyên tử (Rollback Atomicity): Khi lưu nguồn tham khảo thất bại, đóng góp bị rollback hoàn toàn")
    void shouldProveRollbackAtomicityWhenSourcePersistenceFails() {
        testFailableSourceRepository.setFailOnSave(true);

        SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                ARTICLE_ID,
                USER_ID,
                1L,
                "GENERAL",
                "SOURCE_REFERENCE",
                "Đóng góp sẽ bị rollback nếu việc lưu nguồn tham khảo ném lỗi.",
                null, null, null, null,
                List.of("https://example.com/source-will-fail")
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Mô phỏng lỗi lưu nguồn tham khảo để kiểm tra rollback atomicity");

        // Xác thực trong MySQL: wiki_contributions không còn bản ghi nào
        Integer contribCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contributions",
                Integer.class
        );
        assertThat(contribCount).isZero();

        // Xác thực trong MySQL: wiki_contribution_sources không còn bản ghi nào
        Integer sourceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contribution_sources",
                Integer.class
        );
        assertThat(sourceCount).isZero();
    }

    // -------------------------------------------------------------
    // Test Configurations & Supporting Beans
    // -------------------------------------------------------------

    @TestConfiguration
    static class IntegrationTestConfig {

        @Bean
        public MutableTestClock mutableTestClock() {
            return new MutableTestClock();
        }

        @Bean
        public TestArticleRepository testArticleRepository() {
            return new TestArticleRepository();
        }

        @Bean
        public WikiContributionSubmissionThrottlePort throttlePort() {
            return (userId, now) -> com.universe.wiki.application.ports.WikiContributionSubmissionThrottleDecision.allow();
        }

        @Bean
        @Primary
        public TestFailableSourceRepository testFailableSourceRepository(
                WikiContributionSourcePersistenceAdapter realAdapter
        ) {
            return new TestFailableSourceRepository(realAdapter);
        }
    }

    static class MutableTestClock implements ClockPort {
        private Instant current = Instant.parse("2026-09-24T10:00:00Z");

        @Override
        public Instant now() {
            return current;
        }

        public void setCurrentTime(Instant time) {
            this.current = time;
        }

        public void advanceSeconds(long seconds) {
            this.current = this.current.plusSeconds(seconds);
        }
    }

    static class TestArticleRepository implements WikiArticleRepositoryPort {
        private final Map<UUID, WikiArticle> store = new ConcurrentHashMap<>();

        public void register(WikiArticle article) {
            store.put(article.getId(), article);
        }

        @Override
        public Optional<WikiArticle> findById(UUID articleId) {
            return Optional.ofNullable(store.get(articleId));
        }

        @Override
        public Optional<WikiArticle> findByArticleTypeAndSlug(ArticleType articleType, Slug slug) {
            return Optional.empty();
        }

        @Override
        public boolean existsByArticleTypeAndSlug(ArticleType articleType, Slug slug) {
            return false;
        }

        @Override
        public void save(WikiArticle article) {
            store.put(article.getId(), article);
        }

        @Override
        public void deleteById(UUID articleId) {
            store.remove(articleId);
        }

        @Override
        public boolean hasCoverReference(UUID mediaAssetId) {
            return false;
        }

        @Override
        public void lockCoverReferenceKey(UUID mediaAssetId) {
        }

        @Override
        public Optional<String> findMaxCoverMediaAssetId() {
            return Optional.empty();
        }

        @Override
        public List<String> findDistinctCoverMediaAssetIdsKeyset(com.universe.wiki.application.article.cover.backfill.WikiReferencedCoverKeysetQuery query) {
            return Collections.emptyList();
        }

        @Override
        public void flush() {
        }
    }

    static class TestFailableSourceRepository implements WikiContributionSourceRepositoryPort {
        private final WikiContributionSourcePersistenceAdapter delegate;
        private final AtomicBoolean failOnSave = new AtomicBoolean(false);

        public TestFailableSourceRepository(WikiContributionSourcePersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        public void setFailOnSave(boolean fail) {
            this.failOnSave.set(fail);
        }

        @Override
        public WikiContributionSource save(WikiContributionSource source) {
            if (failOnSave.get()) {
                throw new RuntimeException("Mô phỏng lỗi lưu nguồn tham khảo để kiểm tra rollback atomicity");
            }
            return delegate.save(source);
        }

        @Override
        public List<WikiContributionSource> saveAll(List<WikiContributionSource> sources) {
            if (failOnSave.get()) {
                throw new RuntimeException("Mô phỏng lỗi lưu nguồn tham khảo để kiểm tra rollback atomicity");
            }
            return delegate.saveAll(sources);
        }

        @Override
        public List<WikiContributionSource> findByContributionId(UUID contributionId) {
            return delegate.findByContributionId(contributionId);
        }
    }
}
