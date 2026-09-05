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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelManagedVoiceCommandControllerTest {

    @Mock
    private CreateManagedVoiceUseCase createManagedVoiceUseCase;

    @Mock
    private UpdateManagedVoiceMetadataUseCase updateManagedVoiceMetadataUseCase;

    @Mock
    private ChangeManagedVoiceProviderMappingUseCase changeManagedVoiceProviderMappingUseCase;

    @Mock
    private ActivateManagedVoiceUseCase activateManagedVoiceUseCase;

    @Mock
    private DisableManagedVoiceUseCase disableManagedVoiceUseCase;

    @Mock
    private SetDefaultManagedVoiceUseCase setDefaultManagedVoiceUseCase;

    private AdminNovelManagedVoiceCommandController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelManagedVoiceCommandController(
                createManagedVoiceUseCase,
                updateManagedVoiceMetadataUseCase,
                changeManagedVoiceProviderMappingUseCase,
                activateManagedVoiceUseCase,
                disableManagedVoiceUseCase,
                setDefaultManagedVoiceUseCase
        );
    }

    @Test
    @DisplayName("Tạo giọng đọc thành công và chuyển hướng về danh sách")
    void shouldCreateVoiceSuccessfully() {
        CreateManagedVoiceForm form = new CreateManagedVoiceForm();
        form.setVoiceKey("kiemlai-male-01");
        form.setDisplayName("Anh Khôi");
        form.setProviderVoiceId("vi-VN-1");
        form.setDefaultVoice(true);

        UUID id = UUID.randomUUID();
        ManagedVoiceDTO created = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );

        when(createManagedVoiceUseCase.execute(any())).thenReturn(created);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createVoice(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("Anh Khôi");

        ArgumentCaptor<CreateManagedVoiceCommand> captor = ArgumentCaptor.forClass(CreateManagedVoiceCommand.class);
        verify(createManagedVoiceUseCase).execute(captor.capture());
        CreateManagedVoiceCommand cmd = captor.getValue();
        assertThat(cmd.voiceKey()).isEqualTo("kiemlai-male-01");
        assertThat(cmd.displayName()).isEqualTo("Anh Khôi");
        assertThat(cmd.providerVoiceId()).isEqualTo("vi-VN-1");
        assertThat(cmd.defaultVoice()).isTrue();
    }

    @Test
    @DisplayName("Chuyển hướng về trang tạo và giữ form khi voiceKey đã tồn tại")
    void shouldRedirectToCreatePageWhenCreateFailsOnDuplicateKey() {
        CreateManagedVoiceForm form = new CreateManagedVoiceForm();
        form.setVoiceKey("kiemlai-male-01");
        form.setDisplayName("Anh Khôi");
        form.setProviderVoiceId("vi-VN-1");

        when(createManagedVoiceUseCase.execute(any())).thenThrow(new ManagedVoiceKeyAlreadyExistsException("kiemlai-male-01"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createVoice(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/new");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("kiemlai-male-01");
        assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
    }

    @Test
    @DisplayName("Chuyển hướng về trang tạo và giữ form khi xảy ra xung đột trạng thái/default")
    void shouldHandleDefaultConflictWhenCreatingVoice() {
        CreateManagedVoiceForm form = new CreateManagedVoiceForm();
        form.setVoiceKey("kiemlai-male-01");
        form.setDisplayName("Anh Khôi");
        form.setProviderVoiceId("vi-VN-1");
        form.setDefaultVoice(true);

        when(createManagedVoiceUseCase.execute(any())).thenThrow(new ManagedVoiceInvalidStateException("Xung đột giọng đọc mặc định."));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createVoice(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/new");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("Xung đột giọng đọc mặc định.");
        assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
    }

    @Test
    @DisplayName("Chuyển hướng về trang tạo và giữ form khi dữ liệu không hợp lệ")
    void shouldRedirectToCreatePageWhenCreateFailsOnInvalidInput() {
        CreateManagedVoiceForm form = new CreateManagedVoiceForm();
        form.setVoiceKey("");
        form.setDisplayName("Anh Khôi");
        form.setProviderVoiceId("vi-VN-1");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createVoice(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("form")).isSameAs(form);
    }

    @Test
    @DisplayName("Cập nhật metadata giọng đọc thành công")
    void shouldUpdateMetadataSuccessfully() {
        UUID id = UUID.randomUUID();
        EditManagedVoiceMetadataForm form = new EditManagedVoiceMetadataForm();
        form.setDisplayName("Anh Khôi Mới");

        ManagedVoiceDTO updated = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi Mới", "vi-VN-1", "ACTIVE", 5, true, 1L, Instant.now(), Instant.now()
        );
        when(updateManagedVoiceMetadataUseCase.execute(any())).thenReturn(updated);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMetadata(id, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/" + id + "/edit");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("Anh Khôi Mới");

        ArgumentCaptor<UpdateManagedVoiceMetadataCommand> captor = ArgumentCaptor.forClass(UpdateManagedVoiceMetadataCommand.class);
        verify(updateManagedVoiceMetadataUseCase).execute(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(id);
        assertThat(captor.getValue().displayName()).isEqualTo("Anh Khôi Mới");
    }

    @Test
    @DisplayName("Chuyển hướng về trang chỉnh sửa và giữ form khi cập nhật metadata thất bại")
    void shouldRedirectToEditPageWhenMetadataUpdateFails() {
        UUID id = UUID.randomUUID();
        EditManagedVoiceMetadataForm form = new EditManagedVoiceMetadataForm();
        form.setDisplayName("");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMetadata(id, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/" + id + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("metadataForm")).isSameAs(form);
    }

    @Test
    @DisplayName("Chuyển hướng về trang chỉnh sửa và giữ form khi cập nhật provider mapping thất bại")
    void shouldRedirectToEditPageWhenProviderUpdateFails() {
        UUID id = UUID.randomUUID();
        ChangeManagedVoiceProviderForm form = new ChangeManagedVoiceProviderForm();
        form.setProviderVoiceId("");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateProviderMapping(id, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/" + id + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("providerForm")).isSameAs(form);
    }

    @Test
    @DisplayName("Cập nhật Provider mapping thành công và hiển thị synthesis revision")
    void shouldUpdateProviderMappingSuccessfully() {
        UUID id = UUID.randomUUID();
        ChangeManagedVoiceProviderForm form = new ChangeManagedVoiceProviderForm();
        form.setProviderVoiceId("vi-VN-99");

        ManagedVoiceDTO updated = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-99", "ACTIVE", 1, true, 2L, Instant.now(), Instant.now()
        );
        when(changeManagedVoiceProviderMappingUseCase.execute(any())).thenReturn(updated);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateProviderMapping(id, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices/" + id + "/edit");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("revision: 2");

        ArgumentCaptor<ChangeManagedVoiceProviderMappingCommand> captor = ArgumentCaptor.forClass(ChangeManagedVoiceProviderMappingCommand.class);
        verify(changeManagedVoiceProviderMappingUseCase).execute(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(id);
        assertThat(captor.getValue().providerVoiceId()).isEqualTo("vi-VN-99");
    }

    @Test
    @DisplayName("Kích hoạt giọng đọc thành công")
    void shouldActivateVoiceSuccessfully() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO activated = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, false, 1L, Instant.now(), Instant.now()
        );
        when(activateManagedVoiceUseCase.execute(id)).thenReturn(activated);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.activateVoice(id, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("kích hoạt");
    }

    @Test
    @DisplayName("Vô hiệu hóa giọng đọc thành công")
    void shouldDisableVoiceSuccessfully() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO disabled = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "DISABLED", 1, false, 1L, Instant.now(), Instant.now()
        );
        when(disableManagedVoiceUseCase.execute(id)).thenReturn(disabled);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.disableVoice(id, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("vô hiệu hóa");
    }

    @Test
    @DisplayName("Từ chối vô hiệu hóa giọng đọc mặc định")
    void shouldHandleErrorWhenDisablingDefaultVoice() {
        UUID id = UUID.randomUUID();
        when(disableManagedVoiceUseCase.execute(id)).thenThrow(
                new ManagedVoiceInvalidStateException("Không thể vô hiệu hóa giọng đọc đang được đặt làm mặc định.")
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.disableVoice(id, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("mặc định");
    }

    @Test
    @DisplayName("Đặt giọng đọc làm mặc định thành công")
    void shouldSetDefaultVoiceSuccessfully() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO defaultVoice = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );
        when(setDefaultManagedVoiceUseCase.execute(id)).thenReturn(defaultVoice);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.setDefaultVoice(id, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("successMessage")).contains("mặc định");
    }

    @Test
    @DisplayName("Từ chối đặt giọng đọc DISABLED làm mặc định")
    void shouldHandleErrorWhenSettingDefaultOnDisabledVoice() {
        UUID id = UUID.randomUUID();
        when(setDefaultManagedVoiceUseCase.execute(id)).thenThrow(
                new ManagedVoiceInvalidStateException("Chỉ có thể đặt giọng đọc ở trạng thái ACTIVE làm mặc định.")
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.setDefaultVoice(id, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage")).contains("ACTIVE");
    }
}
