package com.universe.novel.entry.admin;

import com.universe.novel.application.narration.AdminChapterNarrationSegmentViewDTO;
import com.universe.novel.application.narration.AdminNarrationOperationState;
import com.universe.novel.application.narration.AdminNarrationOperationStatus;
import com.universe.novel.application.narration.ChapterNarrationAudioHealthStatus;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MS-04.9H.7D4B — Admin Novel Chapter Narration View and UX Tests")
class AdminNovelChapterNarrationViewAndUxTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SEGMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private SpringTemplateEngine templateEngine;
    private JakartaServletWebApplication webApplication;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        webApplication = JakartaServletWebApplication.buildApplication(servletContext);
    }

    private IWebContext createWebContext(
            String chapterStatus,
            String voiceStatus,
            boolean isRunning,
            int readyCount,
            int outdatedCount,
            int missingCount,
            int failedCount,
            boolean contentChangeWarning,
            int obsoleteRetiredAudioCount
    ) {
        Map<String, Object> variables = new HashMap<>();
        ChapterDTO chapter = new ChapterDTO(
                CHAPTER_ID, VOLUME_ID, 1, "Chương Một", "chuong-mot", "Tóm tắt", "Nội dung",
                chapterStatus, UUID.randomUUID(), UUID.randomUUID(), null, null,
                Instant.now(), Instant.now(), Instant.now(), null, 1L, 1L
        );
        VolumeDTO volume = new VolumeDTO(
                VOLUME_ID, "Quyển Một", "quyen-mot", "Tóm tắt", 1, "PUBLISHED",
                UUID.randomUUID(), UUID.randomUUID(), null, null,
                Instant.now(), Instant.now(), Instant.now(), null, 1L
        );
        ManagedVoiceDTO voice = new ManagedVoiceDTO(
                VOICE_ID, "kiemlai-male-01", "Minh Đức", "minh-duc", voiceStatus, 1, true, 1L,
                Instant.now(), Instant.now()
        );

        AdminChapterNarrationSegmentViewDTO missingSegment = new AdminChapterNarrationSegmentViewDTO(
                SEGMENT_ID, 0, "Đoạn 0", 10, ChapterNarrationAudioHealthStatus.MISSING,
                null, null, null, 1L, null
        );

        int totalSegments = readyCount + outdatedCount + missingCount + failedCount;
        if (totalSegments == 0) totalSegments = 1;
        int currentGenRequired = outdatedCount + missingCount + failedCount;

        AdminNarrationOperationState opState = isRunning
                ? AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, Instant.now())
                : AdminNarrationOperationState.idle(CHAPTER_ID, VOICE_ID);

        variables.put("chapter", chapter);
        variables.put("volume", volume);
        variables.put("voices", List.of(voice));
        variables.put("selectedVoice", voice);
        variables.put("segments", List.of(missingSegment));
        variables.put("totalSegments", totalSegments);
        variables.put("currentSegmentCount", totalSegments);
        variables.put("readyCount", readyCount);
        variables.put("outdatedCount", outdatedCount);
        variables.put("missingCount", missingCount);
        variables.put("failedCount", failedCount);
        variables.put("currentGenerationRequiredCount", currentGenRequired);
        variables.put("retiredSegmentCount", 0);
        variables.put("obsoleteRetiredSegmentCount", 0);
        variables.put("obsoleteRetiredAudioCount", obsoleteRetiredAudioCount);
        variables.put("contentChangeWarning", contentChangeWarning);
        variables.put("operationState", opState);
        variables.put("isOperationRunning", isRunning);
        variables.put("pageTitle", "Quản lý giọng đọc Chapter");
        variables.put("activeMenu", "novel");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IServletWebExchange exchange = webApplication.buildExchange(request, response);

        return new WebContext(exchange, Locale.getDefault(), variables);
    }

    @Test
    @DisplayName("7. Template renders whole-chapter generate-all form and button when PUBLISHED + ACTIVE")
    void shouldRenderGenerateAllButtonWhenPublishedAndActive() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 0, 1, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("generate-all");
        assertThat(html).contains("managedVoiceId");
        assertThat(html).contains("Tạo / cập nhật toàn bộ giọng đọc");
    }

    @Test
    @DisplayName("8. RUNNING state disables whole-chapter action and per-segment actions")
    void shouldDisableActionButtonsWhenRunning() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", true, 1, 0, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("novelAdminOperationProgressPanel");
        assertThat(html).contains("Đang tạo / cập nhật giọng đọc...");
        assertThat(html).contains("disabled=\"disabled\"");
        assertThat(html).contains("novelAdminProgressBar");
    }

    @Test
    @DisplayName("9. Polling script initializes with running state variables")
    void shouldIncludePollingScriptWhenRunning() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", true, 1, 0, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("/admin/novel/chapters/");
        assertThat(html).contains("/narration/status?voiceId=");
        assertThat(html).contains("setInterval(pollStatus, pollIntervalMs)");
        assertThat(html).contains("window.location.reload()");
    }

    @Test
    @DisplayName("10. Polling logic stops on terminal status (SUCCEEDED / PARTIAL / FAILED)")
    void shouldContainTerminalHandlingInPollingScript() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", true, 1, 0, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("data.operationStatus === 'SUCCEEDED'");
        assertThat(html).contains("data.operationStatus === 'PARTIAL'");
        assertThat(html).contains("data.operationStatus === 'FAILED'");
        assertThat(html).contains("clearInterval(timer)");
    }

    @Test
    @DisplayName("11. Content change warning is rendered separately and not labeled OUTDATED")
    void shouldRenderContentChangeWarningSeparately() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 2, 0, 0, 0, true, 3);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("novelAdminContentChangeWarning");
        assertThat(html).contains("Cảnh báo thay đổi nội dung");
        assertThat(html).contains("3");
        assertThat(html).contains("RETIRED");
        assertThat(html).doesNotContain("Cảnh báo thay đổi nội dung: OUTDATED");
    }

    @Test
    @DisplayName("12. All-ready audio with no content change renders disabled already-ready button")
    void shouldRenderAlreadyReadyDisabledButtonWhenAllReady() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 5, 0, 0, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("Đã tạo đủ giọng đọc");
        assertThat(html).contains("disabled=\"disabled\"");
    }

    @Test
    @DisplayName("13. Segment table contains action forms and buttons")
    void shouldRenderSegmentActionForms() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 0, 0, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("/segments/" + SEGMENT_ID + "/generate");
        assertThat(html).contains("Tạo Audio");
    }

    @Test
    @DisplayName("14. Chapter narration renders direct link to Managed Voice management")
    void shouldRenderLinkToManagedVoiceManagement() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 0, 0, 1, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("/admin/novel/narration/voices");
        assertThat(html).contains("Quản lý danh mục giọng đọc");
    }

    @Test
    @DisplayName("15. Chapter narration renders selected voice details with synthesis revision explanation")
    void shouldRenderSelectedVoiceDetailsAndRevisionExplanation() {
        IWebContext context = createWebContext("PUBLISHED", "ACTIVE", false, 1, 0, 0, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        assertThat(html).contains("GIỌNG ĐỌC ĐANG CHỌN");
        assertThat(html).contains("Minh Đức");
        assertThat(html).contains("kiemlai-male-01");
        assertThat(html).contains("is-active");
        assertThat(html).contains("Mặc định");
        assertThat(html).contains("v1");
        assertThat(html).contains("Lệch revision làm phân đoạn thành <strong>OUTDATED</strong>, không đồng nghĩa văn bản chương bị sửa đổi");
    }

    @Test
    @DisplayName("16. Chapter narration renders clear warning and disables generation when voice is DISABLED")
    void shouldRenderWarningAndDisableActionsWhenVoiceIsDisabled() {
        IWebContext context = createWebContext("PUBLISHED", "DISABLED", false, 0, 1, 0, 0, false, 0);
        String html = templateEngine.process("admin/novel/chapter-narration", context);

        // Shows disabled warning
        assertThat(html).contains("Giọng đọc này đang ở trạng thái <strong>DISABLED</strong>");
        assertThat(html).contains("is-disabled");

        // No whole-chapter generate form
        assertThat(html).doesNotContain("generate-all");

        // Per-segment action column shows disabled note instead of buttons
        assertThat(html).contains("Giọng đọc bị tắt");
        assertThat(html).doesNotContain("Tái tạo Audio");
        assertThat(html).doesNotContain("Tạo Audio");
    }
}
