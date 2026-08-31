package com.universe.novel.entry.reader;

import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.universe.test.TestDatabaseSupport;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
class ReaderReadingHistoryEndToEndIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID USER_ID =
            UUID.fromString("11111111-e2e2-2222-3333-444444444444");

    private static final String USER_EMAIL = "e2e-history-reader@universe.local";

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-e2e2-2222-3333-444444444444");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("33333333-e2e2-2222-3333-444444444444");

    private static final UUID CHAPTER_20_ID =
            UUID.fromString("44444444-e2e2-2222-3333-444444444444");

    private static final int VOLUME_SORT_ORDER = 6_000_001;
    private static final int CHAPTER_1_NUM = 6_000_001;
    private static final int CHAPTER_20_NUM = 6_000_020;

    private static final String CHAPTER_1_SLUG = "quyen-e2e-hist-chuong-6000001";
    private static final String CHAPTER_20_SLUG = "quyen-e2e-hist-chuong-6000020";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("DELETE FROM novel_reading_history WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_reading_progress WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_bookmarks WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE volume_id = ?", VOLUME_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ? OR email = ?", USER_ID.toString(), USER_EMAIL);
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', 'E2E History Reader', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), USER_EMAIL, Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển E2E History', 'quyen-e2e-history', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters: 1, 20 (both PUBLISHED)
        seedChapter(CHAPTER_1_ID, CHAPTER_1_NUM, "Chương 1: Mở Đầu Lịch Sử", CHAPTER_1_SLUG);
        seedChapter(CHAPTER_20_ID, CHAPTER_20_NUM, "Chương 20: Giữa Chặng Lịch Sử", CHAPTER_20_SLUG);
    }

    private void seedChapter(UUID chapterId, int chapterNumber, String title, String slug) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, ?, ?, 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapterId.toString(), VOLUME_ID.toString(), chapterNumber, title, slug,
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("1. Authenticated chapter read: Chapter render chứa history tracker, POST trả về 204 và lưu vào DB")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldRenderChapterAndRecordReadingHistory() throws Exception {
        // Render chapter page
        MvcResult chapterResult = mockMvc.perform(get("/novel/chapters/" + CHAPTER_1_SLUG))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"))
                .andReturn();

        String html = chapterResult.getResponse().getContentAsString();
        assertThat(html).contains("id=\"novelReadingHistoryTracker\"");
        assertThat(html).contains("reader-history.js");

        // Asynchronous POST history
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        // Verify row created in MySQL
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, chapter_id, first_read_at, last_read_at FROM novel_reading_history WHERE user_id = ? AND chapter_id = ?",
                USER_ID.toString(), CHAPTER_1_ID.toString()
        );
        assertThat(row.get("user_id")).isEqualTo(USER_ID.toString());
        assertThat(row.get("chapter_id")).isEqualTo(CHAPTER_1_ID.toString());
        assertThat(row.get("first_read_at")).isNotNull();
        assertThat(row.get("last_read_at")).isNotNull();
    }

    @Test
    @DisplayName("2. History page & Ordering: Đọc chương mới đưa lên đầu, đọc lại chương cũ đẩy lại lên đầu, không tạo duplicate")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldOrderHistoryByLastReadAtAndMaintainSingleRowPerChapter() throws Exception {
        // Read Chapter 1
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        Thread.sleep(50); // slight time increment

        // Read Chapter 20
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_20_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        // Query history: Chapter 20 should be first, Chapter 1 second
        MvcResult historyResult1 = mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andExpect(model().attributeExists("historyList"))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ReaderReadingHistoryDTO> list1 = (List<ReaderReadingHistoryDTO>)
                historyResult1.getModelAndView().getModel().get("historyList");

        assertThat(list1).hasSize(2);
        assertThat(list1.get(0).chapterId()).isEqualTo(CHAPTER_20_ID);
        assertThat(list1.get(1).chapterId()).isEqualTo(CHAPTER_1_ID);

        Thread.sleep(50);

        // Revisit Chapter 1
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        // Total count in database must still be exactly 2 (no duplicates created)
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(totalCount).isEqualTo(2);

        // Query history: Chapter 1 is now first, Chapter 20 is second
        MvcResult historyResult2 = mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ReaderReadingHistoryDTO> list2 = (List<ReaderReadingHistoryDTO>)
                historyResult2.getModelAndView().getModel().get("historyList");

        assertThat(list2).hasSize(2);
        assertThat(list2.get(0).chapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(list2.get(1).chapterId()).isEqualTo(CHAPTER_20_ID);
    }

    @Test
    @DisplayName("3. Anonymous behavior: Anonymous đọc chapter không tạo history và GET /novel/history redirect về /login")
    void shouldEnforceAnonymousSecurityRules() throws Exception {
        // Anonymous GET chapter
        mockMvc.perform(get("/novel/chapters/" + CHAPTER_1_SLUG))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"));

        // Verify 0 rows in history
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(count).isEqualTo(0);

        // Anonymous GET /novel/history redirects to /login
        mockMvc.perform(get("/novel/history"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // Anonymous POST /novel/chapters/{id}/history redirects to /login
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/history").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("4. Non-interfering isolation: Bookmark và Progress hoạt động độc lập và song song với History")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldOperateHarmoniouslyWithProgressAndBookmarks() throws Exception {
        // Record progress on Chapter 1
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/progress").with(csrf()))
                .andExpect(status().isNoContent());

        // Bookmark Chapter 20
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_20_ID + "/bookmark").with(csrf()))
                .andExpect(status().isNoContent());

        // Record history on Chapter 1
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        // Verify bookmark list has Chapter 20
        MvcResult bmResult = mockMvc.perform(get("/novel/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/bookmarks"))
                .andReturn();
        assertThat(bmResult.getResponse().getContentAsString()).contains(CHAPTER_20_SLUG);

        // Verify history list has Chapter 1
        MvcResult histResult = mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andReturn();
        assertThat(histResult.getResponse().getContentAsString()).contains(CHAPTER_1_SLUG);

        // Unbookmark Chapter 20
        mockMvc.perform(delete("/novel/chapters/" + CHAPTER_20_ID + "/bookmark").with(csrf()))
                .andExpect(status().isNoContent());

        // History of Chapter 1 is completely untouched
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Empty state: Người dùng mới mở /novel/history nhận empty list và giao diện empty state")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldRenderEmptyStateForUserWithoutHistory() throws Exception {
        MvcResult result = mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andExpect(model().attribute("historyList", List.of()))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("id=\"novelHistoryEmpty\"");
        assertThat(html).contains("Bạn chưa có lịch sử đọc nào");
    }

    @Test
    @DisplayName("6. Retention limit 50 & Display limit 10: Đọc 51 chương distinct thì prune chương cũ nhất (giữ đúng 50), trang history chỉ hiển thị 10")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldEnforce50RetentionLimitAnd10DisplayLimit() throws Exception {
        UUID[] chapterIds = new UUID[51];
        Instant baseTime = Instant.parse("2026-08-26T08:00:00Z");

        // Seed 51 distinct chapters
        for (int i = 0; i < 51; i++) {
            chapterIds[i] = UUID.randomUUID();
            seedChapter(chapterIds[i], 6_000_100 + i, "Chương Lớn " + (i + 1), "chuong-lon-" + (i + 1));
        }

        // Record history for chapters 0 to 49 (first 50 distinct chapters)
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/novel/chapters/" + chapterIds[i] + "/history").with(csrf()))
                    .andExpect(status().isNoContent());
        }

        // Exactly 50 rows in DB
        Integer countAt50 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(countAt50).isEqualTo(50);

        // Now record the 51st chapter
        mockMvc.perform(post("/novel/chapters/" + chapterIds[50] + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        // Count must still be capped at 50 (oldest chapter chapterIds[0] was pruned)
        Integer countAfter51 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(countAfter51).isEqualTo(50);

        // Verify oldest chapter (chapterIds[0]) was indeed pruned
        Integer oldestChapterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ? AND chapter_id = ?",
                Integer.class,
                USER_ID.toString(), chapterIds[0].toString()
        );
        assertThat(oldestChapterCount).isEqualTo(0);

        // Verify newest chapter (chapterIds[50]) is present
        Integer newestChapterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_history WHERE user_id = ? AND chapter_id = ?",
                Integer.class,
                USER_ID.toString(), chapterIds[50].toString()
        );
        assertThat(newestChapterCount).isEqualTo(1);

        // Verify /novel/history displays only the 10 newest items
        MvcResult historyResult = mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ReaderReadingHistoryDTO> displayList = (List<ReaderReadingHistoryDTO>)
                historyResult.getModelAndView().getModel().get("historyList");

        assertThat(displayList).hasSize(10);
        assertThat(displayList.get(0).chapterId()).isEqualTo(chapterIds[50]); // Newest
    }
}
