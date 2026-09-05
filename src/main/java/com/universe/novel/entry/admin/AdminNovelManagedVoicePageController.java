package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.exceptions.TtsProviderException;
import com.universe.novel.application.narration.TtsProviderVoice;
import com.universe.novel.application.voice.DiscoverProviderVoicesUseCase;
import com.universe.novel.application.voice.GetManagedVoiceDetailUseCase;
import com.universe.novel.application.voice.ListManagedVoicesUseCase;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.entry.admin.form.ChangeManagedVoiceProviderForm;
import com.universe.novel.entry.admin.form.CreateManagedVoiceForm;
import com.universe.novel.entry.admin.form.EditManagedVoiceMetadataForm;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/narration/voices")
public class AdminNovelManagedVoicePageController {

    private static final Logger log = LoggerFactory.getLogger(AdminNovelManagedVoicePageController.class);

    private static final String PAGE_TITLE = "Quản lý giọng đọc";
    private static final String ACTIVE_MENU = "novel";
    private static final String ACTIVE_SUB_MENU = "voices";
    private static final String PROVIDER_DISCOVERY_WARNING =
            "Không thể kết nối đến TTS Provider để tải danh sách giọng đọc tự động. Bạn vẫn có thể nhập Provider Voice ID thủ công.";

    private final ListManagedVoicesUseCase listManagedVoicesUseCase;
    private final GetManagedVoiceDetailUseCase getManagedVoiceDetailUseCase;
    private final DiscoverProviderVoicesUseCase discoverProviderVoicesUseCase;

    public AdminNovelManagedVoicePageController(
            ListManagedVoicesUseCase listManagedVoicesUseCase,
            GetManagedVoiceDetailUseCase getManagedVoiceDetailUseCase,
            DiscoverProviderVoicesUseCase discoverProviderVoicesUseCase
    ) {
        this.listManagedVoicesUseCase = Objects.requireNonNull(listManagedVoicesUseCase, "listManagedVoicesUseCase must not be null");
        this.getManagedVoiceDetailUseCase = Objects.requireNonNull(getManagedVoiceDetailUseCase, "getManagedVoiceDetailUseCase must not be null");
        this.discoverProviderVoicesUseCase = Objects.requireNonNull(discoverProviderVoicesUseCase, "discoverProviderVoicesUseCase must not be null");
    }

    /**
     * Trang danh sách giọng đọc quản lý.
     * GET /admin/novel/narration/voices
     */
    @GetMapping({ "", "/" })
    public String listPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(response);

        String normalizedKeyword = NovelAdminListFilters.normalizeKeyword(keyword);
        String selectedStatus = NovelAdminListFilters.normalizeStatus(status);

        List<ManagedVoiceDTO> voices = listManagedVoicesUseCase.execute().stream()
                .filter(voice -> NovelAdminListFilters.matchesVoice(
                        normalizedKeyword,
                        selectedStatus,
                        voice.displayName(),
                        voice.voiceKey(),
                        voice.providerVoiceId(),
                        voice.status()
                ))
                .toList();

        model.addAttribute("voices", voices);
        model.addAttribute("keyword", normalizedKeyword);
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/novel/voices";
    }

    /**
     * Trang tạo giọng đọc mới.
     * GET /admin/novel/narration/voices/new
     */
    @GetMapping("/new")
    public String createPage(
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(response);

        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateManagedVoiceForm());
        }

        populateProviderVoicesSafely(model);

        model.addAttribute("pageTitle", "Tạo giọng đọc");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/novel/voice-create";
    }

    /**
     * Trang chỉnh sửa giọng đọc.
     * GET /admin/novel/narration/voices/{id}/edit
     */
    @GetMapping("/{id}/edit")
    public String editPage(
            @PathVariable UUID id,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(response);

        ManagedVoiceDTO voice;
        try {
            voice = getManagedVoiceDetailUseCase.execute(id);
        } catch (ManagedVoiceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy giọng đọc yêu cầu.");
            return "redirect:/admin/novel/narration/voices";
        }

        if (!model.containsAttribute("metadataForm")) {
            EditManagedVoiceMetadataForm metadataForm = new EditManagedVoiceMetadataForm();
            metadataForm.setDisplayName(voice.displayName());
            model.addAttribute("metadataForm", metadataForm);
        }

        if (!model.containsAttribute("providerForm")) {
            ChangeManagedVoiceProviderForm providerForm = new ChangeManagedVoiceProviderForm();
            providerForm.setProviderVoiceId(voice.providerVoiceId());
            model.addAttribute("providerForm", providerForm);
        }

        model.addAttribute("voice", voice);
        List<TtsProviderVoice> providerVoices = populateProviderVoicesSafely(model);

        boolean isCurrentProviderVoiceDiscovered = providerVoices.stream()
                .anyMatch(pv -> Objects.equals(pv.voiceId(), voice.providerVoiceId()));
        model.addAttribute("isCurrentProviderVoiceDiscovered", isCurrentProviderVoiceDiscovered);

        model.addAttribute("pageTitle", "Chỉnh sửa giọng đọc");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/novel/voice-edit";
    }

    private List<TtsProviderVoice> populateProviderVoicesSafely(Model model) {
        try {
            List<TtsProviderVoice> providerVoices = discoverProviderVoicesUseCase.execute();
            List<TtsProviderVoice> safeList = providerVoices != null ? providerVoices : Collections.emptyList();
            model.addAttribute("providerVoices", safeList);
            return safeList;
        } catch (TtsProviderException ex) {
            log.warn("Failed to discover provider voices from TTS provider: {}", ex.getMessage(), ex);
            model.addAttribute("providerVoices", Collections.emptyList());
            model.addAttribute("providerWarning", PROVIDER_DISCOVERY_WARNING);
            return Collections.emptyList();
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
