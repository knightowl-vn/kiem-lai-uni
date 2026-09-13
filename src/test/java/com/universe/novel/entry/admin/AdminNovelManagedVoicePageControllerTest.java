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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelManagedVoicePageControllerTest {

    @Mock
    private ListManagedVoicesUseCase listManagedVoicesUseCase;

    @Mock
    private GetManagedVoiceDetailUseCase getManagedVoiceDetailUseCase;

    @Mock
    private DiscoverProviderVoicesUseCase discoverProviderVoicesUseCase;

    private AdminNovelManagedVoicePageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelManagedVoicePageController(
                listManagedVoicesUseCase,
                getManagedVoiceDetailUseCase,
                discoverProviderVoicesUseCase
        );
    }

    @Test
    @DisplayName("Hiển thị trang danh sách giọng đọc thành công")
    void shouldShowVoiceListPage() {
        UUID id = UUID.randomUUID();
        List<ManagedVoiceDTO> voices = List.of(
                new ManagedVoiceDTO(
                        id,
                        "kiemlai-male-01",
                        "Anh Khôi",
                        "vi-VN-1",
                        "ACTIVE",
                        1,
                        true,
                        1L,
                        Instant.now(),
                        Instant.now()
                )
        );

        when(listManagedVoicesUseCase.execute()).thenReturn(voices);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listPage(null, null, model, response);

        assertThat(view).isEqualTo("admin/novel/voices");
        assertThat(model.getAttribute("voices")).isEqualTo(voices);
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Quản lý giọng đọc");
        assertThat(model.getAttribute("activeMenu")).isEqualTo("novel");
        assertThat(model.getAttribute("activeSubMenu")).isEqualTo("voices");
    }

    @Test
    @DisplayName("Lọc danh sách giọng đọc theo keyword và status")
    void shouldFilterVoiceListByKeywordAndStatus() {
        ManagedVoiceDTO v1 = new ManagedVoiceDTO(
                UUID.randomUUID(), "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );
        ManagedVoiceDTO v2 = new ManagedVoiceDTO(
                UUID.randomUUID(), "kiemlai-female-01", "Chị Mai", "vi-VN-2", "DISABLED", 2, false, 1L, Instant.now(), Instant.now()
        );

        when(listManagedVoicesUseCase.execute()).thenReturn(List.of(v1, v2));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listPage("Khôi", "ACTIVE", model, response);

        assertThat(view).isEqualTo("admin/novel/voices");
        @SuppressWarnings("unchecked")
        List<ManagedVoiceDTO> filtered = (List<ManagedVoiceDTO>) model.getAttribute("voices");
        assertThat(filtered).containsExactly(v1);
    }

    @Test
    @DisplayName("Hiển thị trang tạo giọng đọc mới với danh sách provider voices khám phá thành công")
    void shouldShowCreateVoicePageWithDiscoveredProviderVoices() {
        List<TtsProviderVoice> providerVoices = List.of(
                new TtsProviderVoice("vi-VN-1", "VieNeu Voice 1")
        );
        when(discoverProviderVoicesUseCase.execute()).thenReturn(providerVoices);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.createPage(model, response);

        assertThat(view).isEqualTo("admin/novel/voice-create");
        assertThat(model.getAttribute("form")).isInstanceOf(CreateManagedVoiceForm.class);
        assertThat(model.getAttribute("providerVoices")).isEqualTo(providerVoices);
        assertThat(model.getAttribute("providerWarning")).isNull();
    }

    @Test
    @DisplayName("Hiển thị trang tạo giọng đọc an toàn với cảnh báo khi kết nối TTS Provider thất bại (không quăng 500)")
    void shouldShowCreateVoicePageGracefullyWhenProviderFails() {
        when(discoverProviderVoicesUseCase.execute()).thenThrow(new TtsProviderException("Network connection refused"));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.createPage(model, response);

        assertThat(view).isEqualTo("admin/novel/voice-create");
        assertThat(model.getAttribute("form")).isInstanceOf(CreateManagedVoiceForm.class);
        assertThat(model.getAttribute("providerVoices")).isEqualTo(List.of());
        assertThat((String) model.getAttribute("providerWarning"))
                .isEqualTo("Không thể kết nối đến TTS Provider để tải danh sách giọng đọc tự động. Bạn vẫn có thể nhập Provider Voice ID thủ công.");
    }

    @Test
    @DisplayName("Ngoại lệ lập trình không mong muốn không bị nuốt như lỗi provider")
    void shouldNotSwallowUnexpectedProgrammingExceptionsOnCreatePage() {
        when(discoverProviderVoicesUseCase.execute()).thenThrow(new IllegalStateException("Unexpected internal bug"));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.createPage(model, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected internal bug");
    }

    @Test
    @DisplayName("Hiển thị trang chỉnh sửa giọng đọc thành công khi provider voice hiện tại có trong discovery")
    void shouldShowEditVoicePageWithDiscoveredProviderVoices() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO voice = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );
        List<TtsProviderVoice> providerVoices = List.of(
                new TtsProviderVoice("vi-VN-1", "VieNeu Voice 1")
        );

        when(getManagedVoiceDetailUseCase.execute(id)).thenReturn(voice);
        when(discoverProviderVoicesUseCase.execute()).thenReturn(providerVoices);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("admin/novel/voice-edit");
        assertThat(model.getAttribute("voice")).isEqualTo(voice);
        assertThat(model.getAttribute("isCurrentProviderVoiceDiscovered")).isEqualTo(true);

        EditManagedVoiceMetadataForm metadataForm = (EditManagedVoiceMetadataForm) model.getAttribute("metadataForm");
        assertThat(metadataForm.getDisplayName()).isEqualTo("Anh Khôi");

        ChangeManagedVoiceProviderForm providerForm = (ChangeManagedVoiceProviderForm) model.getAttribute("providerForm");
        assertThat(providerForm.getProviderVoiceId()).isEqualTo("vi-VN-1");

        assertThat(model.getAttribute("providerVoices")).isEqualTo(providerVoices);
        assertThat(model.getAttribute("providerWarning")).isNull();
    }

    @Test
    @DisplayName("Trang chỉnh sửa giữ ID provider hiện tại khi discovery thành công nhưng không còn trả về ID đó")
    void shouldPreserveCurrentProviderIdWhenDiscoveryNoLongerReturnsIt() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO voice = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-deprecated", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );
        List<TtsProviderVoice> providerVoices = List.of(
                new TtsProviderVoice("vi-VN-1", "VieNeu Voice 1"),
                new TtsProviderVoice("vi-VN-2", "VieNeu Voice 2")
        );

        when(getManagedVoiceDetailUseCase.execute(id)).thenReturn(voice);
        when(discoverProviderVoicesUseCase.execute()).thenReturn(providerVoices);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("admin/novel/voice-edit");
        assertThat(model.getAttribute("isCurrentProviderVoiceDiscovered")).isEqualTo(false);
        ChangeManagedVoiceProviderForm providerForm = (ChangeManagedVoiceProviderForm) model.getAttribute("providerForm");
        assertThat(providerForm.getProviderVoiceId()).isEqualTo("vi-VN-deprecated");
    }

    @Test
    @DisplayName("Hiển thị trang chỉnh sửa an toàn với cảnh báo ổn định khi kết nối TTS Provider thất bại")
    void shouldShowEditVoicePageGracefullyWhenProviderFails() {
        UUID id = UUID.randomUUID();
        ManagedVoiceDTO voice = new ManagedVoiceDTO(
                id, "kiemlai-male-01", "Anh Khôi", "vi-VN-1", "ACTIVE", 1, true, 1L, Instant.now(), Instant.now()
        );

        when(getManagedVoiceDetailUseCase.execute(id)).thenReturn(voice);
        when(discoverProviderVoicesUseCase.execute()).thenThrow(new TtsProviderException("Timeout connecting to provider"));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("admin/novel/voice-edit");
        assertThat(model.getAttribute("providerVoices")).isEqualTo(List.of());
        assertThat(model.getAttribute("isCurrentProviderVoiceDiscovered")).isEqualTo(false);
        assertThat((String) model.getAttribute("providerWarning"))
                .isEqualTo("Không thể kết nối đến TTS Provider để tải danh sách giọng đọc tự động. Bạn vẫn có thể nhập Provider Voice ID thủ công.");
    }

    @Test
    @DisplayName("Chuyển hướng về trang danh sách khi không tìm thấy giọng đọc trên trang chỉnh sửa")
    void shouldRedirectToListWhenVoiceNotFoundOnEditPage() {
        UUID id = UUID.randomUUID();
        when(getManagedVoiceDetailUseCase.execute(id)).thenThrow(new ManagedVoiceNotFoundException(id));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.editPage(id, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/narration/voices");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }
}
