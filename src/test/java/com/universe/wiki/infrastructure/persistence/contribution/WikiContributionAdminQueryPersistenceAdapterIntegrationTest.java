package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@Import(WikiContributionAdminQueryPersistenceAdapter.class)
@DisplayName("WikiContributionAdminQueryPersistenceAdapter Integration Tests (MS-05H6)")
class WikiContributionAdminQueryPersistenceAdapterIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private WikiContributionAdminQueryPersistenceAdapter adapter;

    @Autowired
    private SpringDataWikiContributionJpaRepository contributionRepository;

    @Autowired
    private SpringDataWikiContributionSourceJpaRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.execute("DELETE FROM wiki_contributions");
    }

    @Test
    @DisplayName("Tie-breaker proof: multiple contributions with EXACT same createdAt are deterministically ordered by id DESC")
    void shouldBreakCreatedAtTiesUsingIdDesc() {
        Instant exactSameCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Deterministic UUIDs where id3 > id2 > id1
        String idLow = "00000000-0000-0000-0000-000000000001";
        String idMid = "00000000-0000-0000-0000-000000000002";
        String idHigh = "00000000-0000-0000-0000-000000000003";

        // Insert in non-descending order to guarantee database order does not dictate results
        insertContribution(idLow, "NEW", "INCORRECT_INFORMATION", "GENERAL", "Bài 1", "bai-1", "Tin nhắn 1", null, exactSameCreatedAt);
        insertContribution(idHigh, "NEW", "WORDING", "GENERAL", "Bài 3", "bai-3", "Tin nhắn 3", null, exactSameCreatedAt);
        insertContribution(idMid, "NEW", "MISSING_INFORMATION", "GENERAL", "Bài 2", "bai-2", "Tin nhắn 2", null, exactSameCreatedAt);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                10
        );

        WikiContributionAdminPage page = adapter.findAdminInboxPage(filter);

        assertThat(page.items()).hasSize(3);
        // idHigh > idMid > idLow under id DESC
        assertThat(page.items().get(0).contributionId()).isEqualTo(UUID.fromString(idHigh));
        assertThat(page.items().get(1).contributionId()).isEqualTo(UUID.fromString(idMid));
        assertThat(page.items().get(2).contributionId()).isEqualTo(UUID.fromString(idLow));
    }

    @Test
    @DisplayName("Pagination proof: multiple pages with same-createdAt rows have no duplicate and no skipped records")
    void shouldPaginateDeterministicallyWithoutDuplicatesOrSkips() {
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant tNew = baseTime.plus(10, ChronoUnit.MINUTES);
        Instant tMid = baseTime;
        Instant tOld = baseTime.minus(10, ChronoUnit.MINUTES);

        String c1 = "00000000-0000-0000-0000-000000000010"; // tNew
        String c2Low = "00000000-0000-0000-0000-000000000021"; // tMid
        String c2High = "00000000-0000-0000-0000-000000000022"; // tMid
        String c3Low = "00000000-0000-0000-0000-000000000031"; // tOld
        String c3High = "00000000-0000-0000-0000-000000000032"; // tOld

        // Expected deterministic descending order:
        // 1. c1 (tNew)
        // 2. c2High (tMid, id 22)
        // 3. c2Low (tMid, id 21)
        // 4. c3High (tOld, id 32)
        // 5. c3Low (tOld, id 31)

        insertContribution(c1, "NEW", "INCORRECT_INFORMATION", "GENERAL", "B1", "b1", "Msg 1", null, tNew);
        insertContribution(c2Low, "NEW", "MISSING_INFORMATION", "GENERAL", "B2", "b2", "Msg 2", null, tMid);
        insertContribution(c2High, "NEW", "WORDING", "GENERAL", "B3", "b3", "Msg 3", null, tMid);
        insertContribution(c3Low, "NEW", "SOURCE_REFERENCE", "GENERAL", "B4", "b4", "Msg 4", null, tOld);
        insertContribution(c3High, "NEW", "OTHER", "GENERAL", "B5", "b5", "Msg 5", null, tOld);

        // Page 0 (size 2)
        WikiContributionAdminPage page0 = adapter.findAdminInboxPage(
                new WikiContributionAdminFilter(WikiContributionStatus.NEW, null, null, 0, 2)
        );
        assertThat(page0.totalElements()).isEqualTo(5L);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.first()).isTrue();
        assertThat(page0.last()).isFalse();

        List<UUID> page0Ids = page0.items().stream().map(WikiContributionAdminItem::contributionId).toList();
        assertThat(page0Ids).containsExactly(UUID.fromString(c1), UUID.fromString(c2High));

        // Page 1 (size 2)
        WikiContributionAdminPage page1 = adapter.findAdminInboxPage(
                new WikiContributionAdminFilter(WikiContributionStatus.NEW, null, null, 1, 2)
        );
        assertThat(page1.first()).isFalse();
        assertThat(page1.last()).isFalse();

        List<UUID> page1Ids = page1.items().stream().map(WikiContributionAdminItem::contributionId).toList();
        assertThat(page1Ids).containsExactly(UUID.fromString(c2Low), UUID.fromString(c3High));

        // Page 2 (size 2)
        WikiContributionAdminPage page2 = adapter.findAdminInboxPage(
                new WikiContributionAdminFilter(WikiContributionStatus.NEW, null, null, 2, 2)
        );
        assertThat(page2.first()).isFalse();
        assertThat(page2.last()).isTrue();

        List<UUID> page2Ids = page2.items().stream().map(WikiContributionAdminItem::contributionId).toList();
        assertThat(page2Ids).containsExactly(UUID.fromString(c3Low));

        // Assert no duplicates: intersection of all pages is empty
        assertThat(Collections.disjoint(page0Ids, page1Ids)).isTrue();
        assertThat(Collections.disjoint(page0Ids, page2Ids)).isTrue();
        assertThat(Collections.disjoint(page1Ids, page2Ids)).isTrue();

        // Assert all 5 elements are covered without skip
        Set<UUID> allCovered = new HashSet<>();
        allCovered.addAll(page0Ids);
        allCovered.addAll(page1Ids);
        allCovered.addAll(page2Ids);
        assertThat(allCovered).hasSize(5);
    }

    @Test
    @DisplayName("Projection proof: messagePreview is bounded in DB query and hasSelectedText is derived accurately")
    void shouldProjectBoundedMessagePreviewAndAvoidFullHydration() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();

        // 350-character long message
        String longMessage = "A".repeat(350);

        insertContribution(id1, "NEW", "INCORRECT_INFORMATION", "TEXT_SELECTION", "Bài 1", "bai-1", longMessage, "Đoạn trích", now);
        insertContribution(id2, "NEW", "WORDING", "GENERAL", "Bài 2", "bai-2", "Tin nhắn ngắn gọn.", null, now);

        WikiContributionAdminPage page = adapter.findAdminInboxPage(
                new WikiContributionAdminFilter(WikiContributionStatus.NEW, null, null, 0, 10)
        );

        assertThat(page.items()).hasSize(2);

        WikiContributionAdminItem item1 = page.items().stream()
                .filter(i -> i.contributionId().equals(UUID.fromString(id1)))
                .findFirst().orElseThrow();
        WikiContributionAdminItem item2 = page.items().stream()
                .filter(i -> i.contributionId().equals(UUID.fromString(id2)))
                .findFirst().orElseThrow();

        // DB SUBSTRING bounds messagePreview to 200 characters
        assertThat(item1.messagePreview()).hasSize(200);
        assertThat(item1.messagePreview()).isEqualTo("A".repeat(200));
        assertThat(item1.hasSelectedText()).isTrue();

        assertThat(item2.messagePreview()).isEqualTo("Tin nhắn ngắn gọn.");
        assertThat(item2.hasSelectedText()).isFalse();
    }

    @Test
    @DisplayName("findAdminInboxPage filters by status: NEW vs ALL")
    void shouldFilterByStatus() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertContribution(UUID.randomUUID().toString(), "NEW", "INCORRECT_INFORMATION", "GENERAL", "Bài 1", "bai-1", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "MISSING_INFORMATION", "GENERAL", "Bài 2", "bai-2", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "REVIEWING", "WORDING", "GENERAL", "Bài 3", "bai-3", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "RESOLVED", "OTHER", "GENERAL", "Bài 4", "bai-4", "Msg", null, now);

        // 1. Filter by NEW
        WikiContributionAdminFilter filterNew = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                10
        );
        WikiContributionAdminPage pageNew = adapter.findAdminInboxPage(filterNew);
        assertThat(pageNew.items()).hasSize(2);
        assertThat(pageNew.totalElements()).isEqualTo(2L);
        assertThat(pageNew.items()).allMatch(i -> i.status() == WikiContributionStatus.NEW);

        // 2. Filter by ALL (null status)
        WikiContributionAdminFilter filterAll = new WikiContributionAdminFilter(
                null,
                null,
                null,
                0,
                10
        );
        WikiContributionAdminPage pageAll = adapter.findAdminInboxPage(filterAll);
        assertThat(pageAll.items()).hasSize(4);
        assertThat(pageAll.totalElements()).isEqualTo(4L);
    }

    @Test
    @DisplayName("findAdminInboxPage filters by contributionType")
    void shouldFilterByContributionType() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertContribution(UUID.randomUUID().toString(), "NEW", "INCORRECT_INFORMATION", "GENERAL", "Bài 1", "bai-1", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "MISSING_INFORMATION", "GENERAL", "Bài 2", "bai-2", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "WORDING", "GENERAL", "Bài 3", "bai-3", "Msg", null, now);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                WikiContributionType.MISSING_INFORMATION,
                null,
                0,
                10
        );
        WikiContributionAdminPage page = adapter.findAdminInboxPage(filter);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).contributionType()).isEqualTo(WikiContributionType.MISSING_INFORMATION);
    }

    @Test
    @DisplayName("findAdminInboxPage searches keyword in articleTitleSnapshot and articleSlugSnapshot")
    void shouldFilterByKeyword() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertContribution(UUID.randomUUID().toString(), "NEW", "INCORRECT_INFORMATION", "GENERAL", "Trần Bình An", "tran-binh-an", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "MISSING_INFORMATION", "GENERAL", "Ninh Diêu", "ninh-dieu", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "WORDING", "GENERAL", "Tề Tĩnh Xuân", "te-tinh-xuan", "Msg", null, now);

        // Search by title snippet
        WikiContributionAdminFilter filter1 = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                "bình an",
                0,
                10
        );
        WikiContributionAdminPage page1 = adapter.findAdminInboxPage(filter1);
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.items().get(0).articleTitleSnapshot()).isEqualTo("Trần Bình An");

        // Search by slug snippet
        WikiContributionAdminFilter filter2 = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                "ninh-dieu",
                0,
                10
        );
        WikiContributionAdminPage page2 = adapter.findAdminInboxPage(filter2);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).articleTitleSnapshot()).isEqualTo("Ninh Diêu");
    }

    @Test
    @DisplayName("findAdminInboxPage resolves sourceCount in bulk without N+1")
    void shouldResolveSourceCountsInBulk() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();

        insertContribution(c1, "NEW", "SOURCE_REFERENCE", "GENERAL", "Bài 1", "bai-1", "Msg", null, now);
        insertContribution(c2, "NEW", "WORDING", "GENERAL", "Bài 2", "bai-2", "Msg", null, now);

        // Insert 2 sources for c1
        insertSource(UUID.randomUUID().toString(), c1, 0, "INTERNAL", "/wiki/character/ninh-dieu", now);
        insertSource(UUID.randomUUID().toString(), c1, 1, "EXTERNAL", "https://example.com/reference", now);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                10
        );
        WikiContributionAdminPage page = adapter.findAdminInboxPage(filter);

        assertThat(page.items()).hasSize(2);
        WikiContributionAdminItem item1 = page.items().stream()
                .filter(i -> i.contributionId().equals(UUID.fromString(c1)))
                .findFirst().orElseThrow();
        WikiContributionAdminItem item2 = page.items().stream()
                .filter(i -> i.contributionId().equals(UUID.fromString(c2)))
                .findFirst().orElseThrow();

        assertThat(item1.hasSources()).isTrue();
        assertThat(item1.sourceCount()).isEqualTo(2);

        assertThat(item2.hasSources()).isFalse();
        assertThat(item2.sourceCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("countByStatus returns the exact count of contributions in status")
    void shouldCountByStatus() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        insertContribution(UUID.randomUUID().toString(), "NEW", "INCORRECT_INFORMATION", "GENERAL", "Bài 1", "bai-1", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "MISSING_INFORMATION", "GENERAL", "Bài 2", "bai-2", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "NEW", "WORDING", "GENERAL", "Bài 3", "bai-3", "Msg", null, now);
        insertContribution(UUID.randomUUID().toString(), "REVIEWING", "OTHER", "GENERAL", "Bài 4", "bai-4", "Msg", null, now);

        long newCount = adapter.countByStatus(WikiContributionStatus.NEW);
        long reviewingCount = adapter.countByStatus(WikiContributionStatus.REVIEWING);
        long resolvedCount = adapter.countByStatus(WikiContributionStatus.RESOLVED);

        assertThat(newCount).isEqualTo(3L);
        assertThat(reviewingCount).isEqualTo(1L);
        assertThat(resolvedCount).isEqualTo(0L);
    }

    private void insertContribution(String id, String status, String type, String contextType,
                                    String title, String slug, String message, String selectedText, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO wiki_contributions (
                    id, article_id, article_type_snapshot, article_title_snapshot,
                    article_slug_snapshot, article_content_version, submitted_by_user_id,
                    context_type, contribution_type, message, selected_text, selected_prefix,
                    selected_suffix, selected_heading_anchor, status, version, created_at, updated_at
                ) VALUES (
                    ?, ?, 'CHARACTER', ?, ?, 1, ?, ?, ?, ?,
                    ?, NULL, NULL, NULL, ?, 0, ?, ?
                )
                """,
                id,
                UUID.randomUUID().toString(),
                title,
                slug,
                UUID.randomUUID().toString(),
                contextType,
                type,
                message,
                selectedText,
                status,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void insertSource(String id, String contributionId, int order, String type, String url, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO wiki_contribution_sources (
                    id, contribution_id, source_order, source_type, url, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                contributionId,
                order,
                type,
                url,
                Timestamp.from(createdAt)
        );
    }
}
