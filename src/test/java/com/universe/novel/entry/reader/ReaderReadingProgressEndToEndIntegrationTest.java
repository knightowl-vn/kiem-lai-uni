package com.universe.novel.entry.reader;

import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
class ReaderReadingProgressEndToEndIntegrationTest {

    private static String resolveHost() {
        String host = System.getProperty("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = System.getenv("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        return "localhost:3306";
    }

    private static String resolveDatabaseName() {
        String db = System.getProperty("test.mysql.db");
        if (db != null && !db.isBlank()) {
            return db.trim();
        }
        String envDb = System.getenv("TEST_MYSQL_DB");
        if (envDb != null && !envDb.isBlank()) {
            return envDb.trim();
        }
        String dbName = System.getenv("MYSQL_DATABASE");
        if (dbName != null && !dbName.isBlank()) {
            return dbName.trim();
        }
        String dbUrl = System.getenv("DB_URL");
        if (dbUrl != null && !dbUrl.isBlank()) {
            int slashIndex = dbUrl.indexOf('/', "jdbc:mysql://".length());
            if (slashIndex != -1) {
                int qIndex = dbUrl.indexOf('?', slashIndex);
                if (qIndex != -1) {
                    return dbUrl.substring(slashIndex + 1, qIndex);
                } else {
                    return dbUrl.substring(slashIndex + 1);
                }
            }
        }
        return "kiemlai_test";
    }

    private static String resolveUser() {
        String user = System.getProperty("test.mysql.user");
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        String envUser = System.getenv("TEST_MYSQL_USER");
        if (envUser != null && !envUser.isBlank()) {
            return envUser.trim();
        }
        String dbUser = System.getenv("DB_USERNAME");
        if (dbUser != null && !dbUser.isBlank()) {
            return dbUser.trim();
        }
        return "root";
    }

    private static String resolvePassword() {
        String pass = System.getProperty("test.mysql.pass");
        if (pass != null && !pass.isBlank()) {
            return pass;
        }
        String envPass = System.getenv("TEST_MYSQL_PASS");
        if (envPass != null && !envPass.isBlank()) {
            return envPass;
        }
        String rootPass = System.getenv("MYSQL_ROOT_PASSWORD");
        if (rootPass != null && !rootPass.isBlank()) {
            return rootPass;
        }
        String dbPass = System.getenv("DB_PASSWORD");
        if (dbPass != null && !dbPass.isBlank()) {
            return dbPass;
        }
        return "";
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = "jdbc:mysql://" + resolveHost() + "/" + resolveDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ReaderReadingProgressEndToEndIntegrationTest::resolveUser);
        registry.add("spring.datasource.password", ReaderReadingProgressEndToEndIntegrationTest::resolvePassword);
    }

    private static final UUID USER_ID =
            UUID.fromString("11111111-e2e1-2222-3333-444444444444");

    private static final String USER_EMAIL = "e2e-reader@universe.local";

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-e2e1-2222-3333-444444444444");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("33333333-e2e1-2222-3333-444444444444");

    private static final UUID CHAPTER_20_ID =
            UUID.fromString("44444444-e2e1-2222-3333-444444444444");

    private static final int VOLUME_SORT_ORDER = 5_000_001;
    private static final int CHAPTER_1_NUM = 5_000_001;
    private static final int CHAPTER_20_NUM = 5_000_020;

    private static final String CHAPTER_1_SLUG = "quyen-e2e-chuong-5000001";
    private static final String CHAPTER_20_SLUG = "quyen-e2e-chuong-5000020";

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
        jdbcTemplate.update("DELETE FROM novel_reading_progress WHERE user_id = ?", USER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id IN (?, ?) OR chapter_number IN (?, ?)",
                CHAPTER_1_ID.toString(), CHAPTER_20_ID.toString(),
                CHAPTER_1_NUM, CHAPTER_20_NUM);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ? OR email = ?", USER_ID.toString(), USER_EMAIL);
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', 'E2E Reader', 'ACTIVE', 'USER', 1, 0, ?, ?)",
                USER_ID.toString(), USER_EMAIL, Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển E2E', 'quyen-e2e', 'Mô tả', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapters: 1, 20 (both PUBLISHED)
        seedChapter(CHAPTER_1_ID, CHAPTER_1_NUM, "Chương 1: Mở Đầu E2E", CHAPTER_1_SLUG);
        seedChapter(CHAPTER_20_ID, CHAPTER_20_NUM, "Chương 20: Cao Trào E2E", CHAPTER_20_SLUG);
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
    @DisplayName("1. Anonymous reading: Đọc chapter thuần túy GET không tạo progress, landing page hiển thị Start Reading")
    void shouldAllowAnonymousReadingWithoutMutatingProgress() throws Exception {
        // Anonymous GET chapter
        mockMvc.perform(get("/novel/chapters/" + CHAPTER_1_SLUG))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"));

        // Verify no progress record was created in DB
        Integer progressCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_reading_progress WHERE user_id = ?",
                Integer.class,
                USER_ID.toString()
        );
        assertThat(progressCount).isEqualTo(0);

        // Anonymous GET landing page
        mockMvc.perform(get("/novel"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/index"))
                .andExpect(model().attributeDoesNotExist("continueReading"));
    }

    @Test
    @DisplayName("2. Authenticated flow: Mở chapter, ghi progress qua POST, landing page hiển thị Continue Reading chính xác")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldRecordProgressAndDisplayContinueReadingOnLanding() throws Exception {
        // Read Chapter 1 (pure GET)
        mockMvc.perform(get("/novel/chapters/" + CHAPTER_1_SLUG))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"));

        // Asynchronously record progress via authenticated POST with CSRF
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/progress").with(csrf()))
                .andExpect(status().isNoContent());

        // Verify DB state
        Map<String, Object> progressRow = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );
        assertThat(progressRow.get("last_opened_chapter_id")).isEqualTo(CHAPTER_1_ID.toString());
        assertThat(((Number) progressRow.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_1_NUM);

        // Authenticated GET landing page -> continueReading attribute is present
        MvcResult landingResult = mockMvc.perform(get("/novel"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/index"))
                .andExpect(model().attributeExists("continueReading"))
                .andReturn();

        ReaderContinueReadingDTO continueReading = (ReaderContinueReadingDTO)
                landingResult.getModelAndView().getModel().get("continueReading");

        assertThat(continueReading.chapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(continueReading.chapterNumber()).isEqualTo(CHAPTER_1_NUM);
        assertThat(continueReading.highestReachedChapterNumber()).isEqualTo(CHAPTER_1_NUM);
    }

    @Test
    @DisplayName("3. Monotonicity flow: Đọc chapter lớn hơn rồi quay lại chapter nhỏ -> lastOpened cập nhật, highest không giảm")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldMaintainMonotonicityWhenRevisitingOlderChapter() throws Exception {
        // Read Chapter 20
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_20_ID + "/progress").with(csrf()))
                .andExpect(status().isNoContent());

        // Revisit Chapter 1
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/progress").with(csrf()))
                .andExpect(status().isNoContent());

        // Verify DB state: lastOpened is Chapter 1, but highest is 20
        Map<String, Object> progressRow = jdbcTemplate.queryForMap(
                "SELECT last_opened_chapter_id, highest_reached_chapter_number FROM novel_reading_progress WHERE user_id = ?",
                USER_ID.toString()
        );
        assertThat(progressRow.get("last_opened_chapter_id")).isEqualTo(CHAPTER_1_ID.toString());
        assertThat(((Number) progressRow.get("highest_reached_chapter_number")).intValue()).isEqualTo(CHAPTER_20_NUM);

        // Landing page resolves Continue Reading to Chapter 1, with highestReached = 20 for display
        MvcResult landingResult = mockMvc.perform(get("/novel"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/index"))
                .andExpect(model().attributeExists("continueReading"))
                .andReturn();

        ReaderContinueReadingDTO continueReading = (ReaderContinueReadingDTO)
                landingResult.getModelAndView().getModel().get("continueReading");

        assertThat(continueReading.chapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(continueReading.chapterNumber()).isEqualTo(CHAPTER_1_NUM);
        assertThat(continueReading.highestReachedChapterNumber()).isEqualTo(CHAPTER_20_NUM);
    }

    @Test
    @DisplayName("4. Security CSRF enforcement: POST progress thiếu CSRF bị chặn và chuyển hướng /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldEnforceCsrfProtectionOnProgressPost() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_1_ID + "/progress"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }
}
