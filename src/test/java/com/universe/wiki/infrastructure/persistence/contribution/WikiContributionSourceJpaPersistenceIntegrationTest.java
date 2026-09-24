package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
        WikiContributionSourcePersistenceAdapter.class
})
@DisplayName("WikiContributionSource JPA Persistence Integration Tests")
class WikiContributionSourceJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV66BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV66BeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version = '66'");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_sources");
        } catch (Exception ignored) {
        }
    }

    @Autowired
    private WikiContributionPersistenceAdapter contributionAdapter;

    @Autowired
    private WikiContributionSourcePersistenceAdapter sourceAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.execute("DELETE FROM wiki_contributions");
    }

    private WikiContribution createTestContribution(UUID contributionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Đóng góp bài viết thử nghiệm đính kèm nguồn tham khảo.",
                now
        );
        return contributionAdapter.save(contribution);
    }

    @Test
    @DisplayName("Lưu và truy vấn nguồn tham khảo theo contributionId: bảo đảm sắp xếp tăng dần theo sourceOrder")
    void shouldPersistAndRetrieveSourcesOrderedBySourceOrderAsc() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Tạo 3 nguồn tham khảo theo thứ tự ngẫu nhiên (2, 0, 1)
        WikiContributionSource sOrder2 = WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 2, "https://example.com/ref-2", now
        );
        WikiContributionSource sOrder0 = WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 0, "/wiki/character/tran-binh-an", now
        );
        WikiContributionSource sOrder1 = WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 1, "https://example.com/ref-1", now
        );

        sourceAdapter.saveAll(List.of(sOrder2, sOrder0, sOrder1));

        // Truy vấn lại từ DB
        List<WikiContributionSource> retrieved = sourceAdapter.findByContributionId(contributionId);

        assertThat(retrieved).hasSize(3);
        assertThat(retrieved.get(0).getSourceOrder()).isZero();
        assertThat(retrieved.get(0).getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
        assertThat(retrieved.get(0).getUrl()).isEqualTo("/wiki/character/tran-binh-an");
        assertThat(retrieved.get(0).getId()).isEqualTo(sOrder0.getId());

        assertThat(retrieved.get(1).getSourceOrder()).isEqualTo(1);
        assertThat(retrieved.get(1).getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
        assertThat(retrieved.get(1).getUrl()).isEqualTo("https://example.com/ref-1");
        assertThat(retrieved.get(1).getId()).isEqualTo(sOrder1.getId());

        assertThat(retrieved.get(2).getSourceOrder()).isEqualTo(2);
        assertThat(retrieved.get(2).getSourceType()).isEqualTo(WikiContributionSourceType.EXTERNAL);
        assertThat(retrieved.get(2).getUrl()).isEqualTo("https://example.com/ref-2");
        assertThat(retrieved.get(2).getId()).isEqualTo(sOrder2.getId());
    }

    @Test
    @DisplayName("Giới hạn tối đa 5 nguồn tham khảo (0..4) trên một đóng góp: lưu thành công 5 nguồn")
    void shouldAllowMaximumFiveSourcesPerContribution() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<WikiContributionSource> sources = List.of(
                WikiContributionSource.create(UUID.randomUUID(), contributionId, 0, "https://example.com/0", now),
                WikiContributionSource.create(UUID.randomUUID(), contributionId, 1, "https://example.com/1", now),
                WikiContributionSource.create(UUID.randomUUID(), contributionId, 2, "https://example.com/2", now),
                WikiContributionSource.create(UUID.randomUUID(), contributionId, 3, "https://example.com/3", now),
                WikiContributionSource.create(UUID.randomUUID(), contributionId, 4, "https://example.com/4", now)
        );

        sourceAdapter.saveAll(sources);

        List<WikiContributionSource> retrieved = sourceAdapter.findByContributionId(contributionId);
        assertThat(retrieved).hasSize(5);

        // Nguồn thứ 6 với sourceOrder = 5 bị từ chối ngay tại tầng domain
        assertThatThrownBy(() -> WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 5, "https://example.com/5", now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("từ 0 đến 4");
    }

    @Test
    @DisplayName("Ràng buộc UNIQUE: Bác bỏ lưu hai nguồn tham khảo có cùng (contribution_id, source_order)")
    void shouldRejectDuplicateSourceOrderForSameContribution() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionSource s1 = WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 1, "https://example.com/first", now
        );
        sourceAdapter.save(s1);

        WikiContributionSource s2 = WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 1, "https://example.com/duplicate-slot", now
        );

        assertThatThrownBy(() -> sourceAdapter.save(s2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Khóa ngoại và ON DELETE CASCADE: Khi xóa wiki_contributions, các nguồn tham khảo bị xóa tự động")
    void shouldCascadeDeleteSourcesWhenContributionIsDeleted() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        sourceAdapter.save(WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 0, "/wiki/character/tran-binh-an", now
        ));
        sourceAdapter.save(WikiContributionSource.create(
                UUID.randomUUID(), contributionId, 1, "https://example.com/external", now
        ));

        assertThat(sourceAdapter.findByContributionId(contributionId)).hasSize(2);

        // Xóa contribution cha trực tiếp từ DB
        jdbcTemplate.update("DELETE FROM wiki_contributions WHERE id = ?", contributionId.toString());

        // Kiểm tra các bản ghi nguồn tham khảo đã bị CASCADE xóa sạch
        List<WikiContributionSource> remaining = sourceAdapter.findByContributionId(contributionId);
        assertThat(remaining).isEmpty();
    }

    @Test
    @DisplayName("Ngăn chặn nguồn mồ côi: Bác bỏ lưu nguồn tham khảo khi contributionId không tồn tại")
    void shouldRejectOrphanSourceWithoutExistingContribution() {
        UUID nonExistentContributionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionSource orphan = WikiContributionSource.create(
                UUID.randomUUID(), nonExistentContributionId, 0, "https://example.com/orphan", now
        );

        assertThatThrownBy(() -> sourceAdapter.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
