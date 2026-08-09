package com.universe.identity.entry.web;

import com.universe.identity.application.profile.DeleteAvatarService;
import com.universe.identity.application.profile.UpdateAvatarService;
import com.universe.identity.application.profile.UpdateBioService;
import com.universe.identity.application.profile.UpdateDisplayNameService;
import com.universe.identity.domain.exceptions.InvalidBioException;
import com.universe.identity.domain.exceptions.InvalidDisplayNameException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProfileUpdateController {

	private final UpdateDisplayNameService updateDisplayNameService;

	private final UpdateBioService updateBioService;

	private final UpdateAvatarService updateAvatarService;

	private final DeleteAvatarService deleteAvatarService;

	public ProfileUpdateController(UpdateDisplayNameService updateDisplayNameService, UpdateBioService updateBioService,
			UpdateAvatarService updateAvatarService, DeleteAvatarService deleteAvatarService) {
		this.updateDisplayNameService = updateDisplayNameService;

		this.updateBioService = updateBioService;

		this.updateAvatarService = updateAvatarService;

		this.deleteAvatarService = deleteAvatarService;
	}

	@PostMapping("/profile/avatar")
	public String updateAvatar(@RequestParam("avatarFile") MultipartFile avatarFile,

			Authentication authentication, RedirectAttributes redirectAttributes) {
		try {
			updateAvatarService.execute(resolveCurrentUserEmail(authentication), avatarFile);

			redirectAttributes.addFlashAttribute("successMessage", "Cập nhật ảnh đại diện thành công.");

		} catch (IllegalArgumentException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return "redirect:/profile";
	}

	@PostMapping("/profile/avatar/delete")
	public String deleteAvatar(Authentication authentication, RedirectAttributes redirectAttributes) {
		try {
			deleteAvatarService.execute(resolveCurrentUserEmail(authentication));

			redirectAttributes.addFlashAttribute("successMessage", "Xóa ảnh đại diện thành công.");

		} catch (IllegalArgumentException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return "redirect:/profile";
	}

	@PostMapping("/profile/display-name")
	public String updateDisplayName(@RequestParam("displayName") String displayName,

			Authentication authentication, RedirectAttributes redirectAttributes) {
		try {
			updateDisplayNameService.execute(resolveCurrentUserEmail(authentication), displayName);

			redirectAttributes.addFlashAttribute("successMessage", "Cập nhật tên hiển thị thành công.");

		} catch (InvalidDisplayNameException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return "redirect:/profile";
	}

	@PostMapping("/profile/bio")
	public String updateBio(@RequestParam(value = "bio", required = false) String bio,

			Authentication authentication, RedirectAttributes redirectAttributes) {
		try {
			updateBioService.execute(resolveCurrentUserEmail(authentication), bio);

			redirectAttributes.addFlashAttribute("successMessage", "Cập nhật phần giới thiệu thành công.");

		} catch (InvalidBioException | IllegalStateException exception) {

			redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
		}

		return "redirect:/profile";
	}

	private String resolveCurrentUserEmail(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {

			throw new IllegalStateException("Người dùng chưa đăng nhập.");
		}

		Object principal = authentication.getPrincipal();

		if (principal instanceof OAuth2User oauth2User) {
			String email = oauth2User.getAttribute("email");

			if (email == null || email.isBlank()) {

				throw new IllegalStateException("Google không trả về email người dùng.");
			}

			return email;
		}

		String email = authentication.getName();

		if (email == null || email.isBlank()) {

			throw new IllegalStateException("Không xác định được email người dùng.");
		}

		return email;
	}
}