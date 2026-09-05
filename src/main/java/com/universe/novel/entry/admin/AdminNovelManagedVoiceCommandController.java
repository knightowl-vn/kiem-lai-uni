package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceKeyAlreadyExistsException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.voice.ActivateManagedVoiceUseCase;
import com.universe.novel.application.voice.ChangeManagedVoiceProviderMappingUseCase;
import com.universe.novel.application.voice.CreateManagedVoiceUseCase;
import com.universe.novel.application.voice.DisableManagedVoiceUseCase;
import com.universe.novel.application.voice.SetDefaultManagedVoiceUseCase;
import com.universe.novel.application.voice.UpdateManagedVoiceMetadataUseCase;
import com.universe.novel.application.voice.commands.ChangeManagedVoiceProviderMappingCommand;
import com.universe.novel.application.voice.commands.CreateManagedVoiceCommand;
import com.universe.novel.application.voice.commands.UpdateManagedVoiceMetadataCommand;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.entry.admin.form.ChangeManagedVoiceProviderForm;
import com.universe.novel.entry.admin.form.CreateManagedVoiceForm;
import com.universe.novel.entry.admin.form.EditManagedVoiceMetadataForm;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/narration/voices")
public class AdminNovelManagedVoiceCommandController {

    private final CreateManagedVoiceUseCase createManagedVoiceUseCase;
    private final UpdateManagedVoiceMetadataUseCase updateManagedVoiceMetadataUseCase;
    private final ChangeManagedVoiceProviderMappingUseCase changeManagedVoiceProviderMappingUseCase;
    private final ActivateManagedVoiceUseCase activateManagedVoiceUseCase;
    private final DisableManagedVoiceUseCase disableManagedVoiceUseCase;
    private final SetDefaultManagedVoiceUseCase setDefaultManagedVoiceUseCase;

    public AdminNovelManagedVoiceCommandController(
            CreateManagedVoiceUseCase createManagedVoiceUseCase,
            UpdateManagedVoiceMetadataUseCase updateManagedVoiceMetadataUseCase,
            ChangeManagedVoiceProviderMappingUseCase changeManagedVoiceProviderMappingUseCase,
            ActivateManagedVoiceUseCase activateManagedVoiceUseCase,
            DisableManagedVoiceUseCase disableManagedVoiceUseCase,
            SetDefaultManagedVoiceUseCase setDefaultManagedVoiceUseCase
    ) {
        this.createManagedVoiceUseCase = Objects.requireNonNull(createManagedVoiceUseCase, "createManagedVoiceUseCase must not be null");
        this.updateManagedVoiceMetadataUseCase = Objects.requireNonNull(updateManagedVoiceMetadataUseCase, "updateManagedVoiceMetadataUseCase must not be null");
        this.changeManagedVoiceProviderMappingUseCase = Objects.requireNonNull(changeManagedVoiceProviderMappingUseCase, "changeManagedVoiceProviderMappingUseCase must not be null");
        this.activateManagedVoiceUseCase = Objects.requireNonNull(activateManagedVoiceUseCase, "activateManagedVoiceUseCase must not be null");
        this.disableManagedVoiceUseCase = Objects.requireNonNull(disableManagedVoiceUseCase, "disableManagedVoiceUseCase must not be null");
        this.setDefaultManagedVoiceUseCase = Objects.requireNonNull(setDefaultManagedVoiceUseCase, "setDefaultManagedVoiceUseCase must not be null");
    }

    @PostMapping
    public String createVoice(
            @ModelAttribute("form") CreateManagedVoiceForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            validateCreateForm(form);

            ManagedVoiceDTO voice = createManagedVoiceUseCase.execute(new CreateManagedVoiceCommand(
                    form.getVoiceKey().trim(),
                    form.getDisplayName().trim(),
                    form.getProviderVoiceId().trim(),
                    form.isDefaultVoice()
            ));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã tạo giọng đọc \"" + voice.displayName() + "\"."
            );

            return "redirect:/admin/novel/narration/voices";

        } catch (ManagedVoiceKeyAlreadyExistsException | ManagedVoiceInvalidStateException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("form", form);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/novel/narration/voices/new";
        }
    }

    @PostMapping("/{id}/metadata")
    public String updateMetadata(
            @PathVariable UUID id,
            @ModelAttribute("metadataForm") EditManagedVoiceMetadataForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            validateMetadataForm(form);

            ManagedVoiceDTO voice = updateManagedVoiceMetadataUseCase.execute(new UpdateManagedVoiceMetadataCommand(
                    id,
                    form.getDisplayName().trim()
            ));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã cập nhật thông tin giọng đọc \"" + voice.displayName() + "\"."
            );

            return "redirect:/admin/novel/narration/voices/" + id + "/edit";

        } catch (ManagedVoiceNotFoundException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("metadataForm", form);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/novel/narration/voices/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/provider")
    public String updateProviderMapping(
            @PathVariable UUID id,
            @ModelAttribute("providerForm") ChangeManagedVoiceProviderForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            validateProviderForm(form);

            ManagedVoiceDTO voice = changeManagedVoiceProviderMappingUseCase.execute(
                    new ChangeManagedVoiceProviderMappingCommand(id, form.getProviderVoiceId().trim())
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã cập nhật liên kết provider (synthesis revision: " + voice.synthesisRevision() + ")."
            );

            return "redirect:/admin/novel/narration/voices/" + id + "/edit";

        } catch (ManagedVoiceNotFoundException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("providerForm", form);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/novel/narration/voices/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/activate")
    public String activateVoice(
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ManagedVoiceDTO voice = activateManagedVoiceUseCase.execute(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã kích hoạt giọng đọc \"" + voice.displayName() + "\"."
            );
        } catch (ManagedVoiceNotFoundException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/novel/narration/voices";
    }

    @PostMapping("/{id}/disable")
    public String disableVoice(
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ManagedVoiceDTO voice = disableManagedVoiceUseCase.execute(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã vô hiệu hóa giọng đọc \"" + voice.displayName() + "\"."
            );
        } catch (ManagedVoiceNotFoundException | ManagedVoiceInvalidStateException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/novel/narration/voices";
    }

    @PostMapping("/{id}/default")
    public String setDefaultVoice(
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ManagedVoiceDTO voice = setDefaultManagedVoiceUseCase.execute(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã đặt giọng đọc \"" + voice.displayName() + "\" làm mặc định."
            );
        } catch (ManagedVoiceNotFoundException | ManagedVoiceInvalidStateException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/novel/narration/voices";
    }

    private void validateCreateForm(CreateManagedVoiceForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Dữ liệu form không được để trống.");
        }
        if (form.getVoiceKey() == null || form.getVoiceKey().isBlank()) {
            throw new IllegalArgumentException("Mã giọng đọc (voiceKey) không được để trống.");
        }
        if (form.getDisplayName() == null || form.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("Tên hiển thị không được để trống.");
        }
        if (form.getProviderVoiceId() == null || form.getProviderVoiceId().isBlank()) {
            throw new IllegalArgumentException("Provider Voice ID không được để trống.");
        }
    }

    private void validateMetadataForm(EditManagedVoiceMetadataForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Dữ liệu form không được để trống.");
        }
        if (form.getDisplayName() == null || form.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("Tên hiển thị không được để trống.");
        }
    }

    private void validateProviderForm(ChangeManagedVoiceProviderForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Dữ liệu form không được để trống.");
        }
        if (form.getProviderVoiceId() == null || form.getProviderVoiceId().isBlank()) {
            throw new IllegalArgumentException("Provider Voice ID không được để trống.");
        }
    }
}
