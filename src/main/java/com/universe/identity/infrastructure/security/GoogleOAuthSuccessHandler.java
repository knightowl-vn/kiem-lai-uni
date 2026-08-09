package com.universe.identity.infrastructure.security;

import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.oauth.GoogleUserInfo;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Component
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

	private final GoogleOAuthUserService googleOAuthUserService;

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public GoogleOAuthSuccessHandler(GoogleOAuthUserService googleOAuthUserService) {
		this.googleOAuthUserService = googleOAuthUserService;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {

		if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {

			throw new IllegalStateException("Authentication không phải OAuth2.");
		}

		OAuth2User oauth2User = oauthToken.getPrincipal();

		Boolean emailVerified = oauth2User.getAttribute("email_verified");

		GoogleUserInfo googleUserInfo = new GoogleUserInfo(oauth2User.getAttribute("sub"),
				oauth2User.getAttribute("email"), oauth2User.getAttribute("name"), oauth2User.getAttribute("picture"),
				Boolean.TRUE.equals(emailVerified));

		User user = googleOAuthUserService.findOrCreateGoogleUser(googleUserInfo);

		/*
		 * Tài khoản bị khóa.
		 */
		if (user.getStatus() == UserStatus.BLOCKED) {

			clearAuthentication(request);

			response.sendRedirect(request.getContextPath() + "/login?blocked");

			return;
		}

		/*
		 * Chỉ tài khoản ACTIVE được đăng nhập.
		 */
		if (user.getStatus() != UserStatus.ACTIVE) {

			clearAuthentication(request);

			response.sendRedirect(request.getContextPath() + "/login?disabled");

			return;
		}

		UserRole role = user.getRole() == null ? UserRole.USER : user.getRole();

		String roleName = role.name();

		Set<GrantedAuthority> authorities = new HashSet<>(oauthToken.getAuthorities());

		/*
		 * Loại bỏ role cũ rồi thêm role được đọc từ database/domain.
		 */
		authorities.removeIf(authority -> authority.getAuthority().startsWith("ROLE_"));

		authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));

		OAuth2AuthenticationToken updatedAuthentication = new OAuth2AuthenticationToken(oauth2User, authorities,
				oauthToken.getAuthorizedClientRegistrationId());

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();

		securityContext.setAuthentication(updatedAuthentication);

		SecurityContextHolder.setContext(securityContext);

		securityContextRepository.saveContext(securityContext, request, response);

		request.getSession().setAttribute("currentUserId", user.getId().toString());

		/*
		 * Nếu trước khi đăng nhập có SavedRequest: quay lại trang đó.
		 *
		 * Nếu không có: - ADMIN vào dashboard - USER vào home
		 */
		boolean isAdministrator = role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN;

		String defaultTargetUrl = isAdministrator ? "/admin/dashboard" : "/home";

		SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();

		successHandler.setDefaultTargetUrl(defaultTargetUrl);

		successHandler.setAlwaysUseDefaultTargetUrl(false);

		successHandler.onAuthenticationSuccess(request, response, updatedAuthentication);
	}

	private void clearAuthentication(HttpServletRequest request) {
		SecurityContextHolder.clearContext();

		HttpSession session = request.getSession(false);

		if (session != null) {
			session.invalidate();
		}
	}
}