package com.universe.identity.entry.web;

import com.universe.identity.application.profile.DeleteAvatarService;
import com.universe.identity.application.profile.UpdateAvatarService;
import com.universe.identity.application.profile.UpdateBioService;
import com.universe.identity.application.profile.UpdateDisplayNameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileUpdateControllerTest {

    @Mock
    private UpdateDisplayNameService updateDisplayNameService;

    @Mock
    private UpdateBioService updateBioService;

    @Mock
    private UpdateAvatarService updateAvatarService;

    @Mock
    private DeleteAvatarService deleteAvatarService;

    @Mock
    private Authentication authentication;

    private ProfileUpdateController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfileUpdateController(
                updateDisplayNameService,
                updateBioService,
                updateAvatarService,
                deleteAvatarService
        );
    }

    @Test
    @DisplayName("updateAvatar streams file to UpdateAvatarService and sets success message")
    void shouldStreamFileToUpdateAvatarService() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user@example.com");

        byte[] content = "avatar-image-data".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile multipartFile = new MockMultipartFile(
                "avatarFile",
                "avatar.png",
                "image/png",
                content
        );

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateAvatar(multipartFile, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Cập nhật ảnh đại diện thành công.");

        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(updateAvatarService).execute(
                eq("user@example.com"),
                streamCaptor.capture(),
                eq((long) content.length),
                eq("image/png"),
                eq("avatar.png")
        );
    }

    @Test
    @DisplayName("updateAvatar handles validation errors and sets error flash message")
    void shouldHandleValidationErrorsInUpdateAvatar() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user@example.com");

        MockMultipartFile multipartFile = new MockMultipartFile(
                "avatarFile",
                "avatar.png",
                "image/png",
                "data".getBytes(StandardCharsets.UTF_8)
        );

        doThrow(new IllegalArgumentException("Ảnh đại diện không được vượt quá 2 MB."))
                .when(updateAvatarService).execute(any(), any(), anyLong(), any(), any());

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.updateAvatar(multipartFile, authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Ảnh đại diện không được vượt quá 2 MB.");
    }

    @Test
    @DisplayName("deleteAvatar delegates to DeleteAvatarService and sets success message")
    void shouldDelegateDeleteAvatarToService() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user@example.com");

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.deleteAvatar(authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Xóa ảnh đại diện thành công.");
        verify(deleteAvatarService).execute("user@example.com");
    }

    @Test
    @DisplayName("deleteAvatar handles errors and sets error flash message")
    void shouldHandleErrorsInDeleteAvatar() {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("user@example.com");
        doThrow(new IllegalStateException("Không thể xóa ảnh đại diện."))
                .when(deleteAvatarService).execute("user@example.com");

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.deleteAvatar(authentication, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Không thể xóa ảnh đại diện.");
    }
}
