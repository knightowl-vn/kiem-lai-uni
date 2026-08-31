package com.universe.novel.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.novel.application.chapter.ArchiveChapterCommand;
import com.universe.novel.application.chapter.ArchiveChapterUseCase;
import com.universe.novel.application.chapter.CreateChapterCommand;
import com.universe.novel.application.chapter.CreateChapterUseCase;
import com.universe.novel.application.chapter.DeleteDraftChapterCommand;
import com.universe.novel.application.chapter.DeleteDraftChapterUseCase;
import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.MoveChapterCommand;
import com.universe.novel.application.chapter.MoveChapterUseCase;
import com.universe.novel.application.chapter.PublishChapterCommand;
import com.universe.novel.application.chapter.PublishChapterUseCase;
import com.universe.novel.application.chapter.RestoreChapterCommand;
import com.universe.novel.application.chapter.RestoreChapterUseCase;
import com.universe.novel.application.chapter.UnpublishChapterCommand;
import com.universe.novel.application.chapter.UnpublishChapterUseCase;
import com.universe.novel.application.chapter.UpdateDraftChapterCommand;
import com.universe.novel.application.chapter.UpdateDraftChapterUseCase;
import com.universe.novel.application.exceptions.ChapterCannotBeDeletedException;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.entry.admin.form.CreateChapterForm;
import com.universe.novel.entry.admin.form.EditChapterForm;
import com.universe.novel.entry.admin.form.MoveChapterForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.universe.shared.security.AuthenticatedEmailResolver;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelChapterCommandControllerTest {

	private static final UUID VOLUME_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID TARGET_VOLUME_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

	private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static final String ADMIN_EMAIL = "admin@example.com";

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

	@Mock
	private CreateChapterUseCase createChapterUseCase;

	@Mock
	private UpdateDraftChapterUseCase updateDraftChapterUseCase;

	@Mock
	private PublishChapterUseCase publishChapterUseCase;

	@Mock
	private UnpublishChapterUseCase unpublishChapterUseCase;

	@Mock
	private ArchiveChapterUseCase archiveChapterUseCase;

	@Mock
	private RestoreChapterUseCase restoreChapterUseCase;

	@Mock
	private DeleteDraftChapterUseCase deleteDraftChapterUseCase;

	@Mock
	private MoveChapterUseCase moveChapterUseCase;

	@Mock
	private GetChapterDetailUseCase getChapterDetailUseCase;

	@Mock
	private UserIdentityContract userIdentityContract;

	@Mock
	private Authentication authentication;

	private AdminNovelChapterCommandController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminNovelChapterCommandController(createChapterUseCase, updateDraftChapterUseCase,
				publishChapterUseCase, unpublishChapterUseCase, archiveChapterUseCase, restoreChapterUseCase,
				deleteDraftChapterUseCase, moveChapterUseCase, getChapterDetailUseCase, userIdentityContract,
				new AuthenticatedEmailResolver());
	}

	@Test
	@DisplayName("Create lấy actor từ Authentication và không nhận slug thủ công")
	void shouldCreateChapterUsingAuthenticatedActor() {
		stubCurrentActor();

		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(1266);
		form.setTitle("  Chương 1266  ");
		form.setSummary("  Tóm tắt.  ");
		form.setContent("  Nội dung.  ");

		when(createChapterUseCase.execute(new CreateChapterCommand(VOLUME_ID, 1266, "Chương 1266", "Tóm tắt.",
				"Nội dung.", ADMIN_ID))).thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.createChapter(VOLUME_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã tạo Chapter \"Chương Một\".");

		ArgumentCaptor<CreateChapterCommand> commandCaptor = ArgumentCaptor.forClass(CreateChapterCommand.class);
		verify(createChapterUseCase).execute(commandCaptor.capture());
		assertThat(commandCaptor.getValue().actorId()).isEqualTo(ADMIN_ID);
		assertThat(commandCaptor.getValue().chapterNumber()).isEqualTo(1266);
		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Create validation thất bại thì không gọi use case")
	void shouldFlashErrorWhenCreateValidationFails() {
		CreateChapterForm form = new CreateChapterForm();
		form.setTitle("Tiêu đề");

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.createChapter(VOLUME_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters/new");
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Số chương phải lớn hơn hoặc bằng 1.");
		verify(createChapterUseCase, never()).execute(org.mockito.ArgumentMatchers.any());
		verify(userIdentityContract, never()).findByEmail(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("Update Draft lấy actor từ Authentication")
	void shouldUpdateDraftUsingAuthenticatedActor() {
		stubCurrentActor();

		EditChapterForm form = new EditChapterForm();
		form.setChapterNumber(2);
		form.setTitle("  Tên mới  ");
		form.setSummary("  Tóm tắt mới  ");
		form.setContent("  Nội dung mới  ");

		when(updateDraftChapterUseCase.execute(new UpdateDraftChapterCommand(CHAPTER_ID, 2, "Tên mới", "Tóm tắt mới",
				"Nội dung mới", ADMIN_ID))).thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.updateDraftChapter(CHAPTER_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã cập nhật Chapter \"Chương Một\".");
		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Publish thành công rồi redirect về danh sách Chapter")
	void shouldPublishChapter() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));
		when(publishChapterUseCase.execute(new PublishChapterCommand(CHAPTER_ID, ADMIN_ID)))
				.thenReturn(chapterDto("PUBLISHED"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.publishChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xuất bản Chapter \"Chương Một\".");
		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Unpublish thành công rồi redirect về danh sách Chapter")
	void shouldUnpublishChapter() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("PUBLISHED"));
		when(unpublishChapterUseCase.execute(new UnpublishChapterCommand(CHAPTER_ID, ADMIN_ID)))
				.thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.unpublishChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã hủy xuất bản Chapter \"Chương Một\".");
	}

	@Test
	@DisplayName("Archive thành công rồi redirect về danh sách Chapter")
	void shouldArchiveChapter() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));
		when(archiveChapterUseCase.execute(new ArchiveChapterCommand(CHAPTER_ID, ADMIN_ID)))
				.thenReturn(chapterDto("ARCHIVED"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.archiveChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã lưu trữ Chapter \"Chương Một\".");
	}

	@Test
	@DisplayName("Restore thành công rồi redirect về danh sách Chapter")
	void shouldRestoreChapter() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("ARCHIVED"));
		when(restoreChapterUseCase.execute(new RestoreChapterCommand(CHAPTER_ID, ADMIN_ID)))
				.thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.restoreChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã khôi phục Chapter \"Chương Một\".");
	}

	@Test
	@DisplayName("Delete DRAFT redirect về danh sách Chapter của Volume")
	void shouldDeleteDraftChapter() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.deleteDraftChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã xóa Chapter \"Chương Một\".");
		verify(deleteDraftChapterUseCase).execute(new DeleteDraftChapterCommand(CHAPTER_ID, ADMIN_ID));
		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Delete bị từ chối thì flash error và quay về danh sách")
	void shouldFlashErrorWhenDeleteFails() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("PUBLISHED"));
		org.mockito.Mockito.doThrow(new IllegalStateException("Chỉ chương ở trạng thái DRAFT mới được xóa."))
				.when(deleteDraftChapterUseCase)
				.execute(new DeleteDraftChapterCommand(CHAPTER_ID, ADMIN_ID));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.deleteDraftChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Chỉ chương ở trạng thái DRAFT mới được xóa.");
	}

	@Test
	@DisplayName("Case A: Delete bị từ chối do Chapter có lịch sử revision không an toàn thì flash error và quay về danh sách")
	void shouldFlashErrorWhenHardDeleteSafetyCheckFails() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));
		ChapterCannotBeDeletedException exception = new ChapterCannotBeDeletedException(CHAPTER_ID);
		org.mockito.Mockito.doThrow(exception)
				.when(deleteDraftChapterUseCase)
				.execute(new DeleteDraftChapterCommand(CHAPTER_ID, ADMIN_ID));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.deleteDraftChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo(exception.getMessage());
	}

	@Test
	@DisplayName("Move DRAFT chỉ thay đổi Volume đích")
	void shouldMoveChapterWithoutTargetSortOrder() {
		stubCurrentActor();

		MoveChapterForm form = new MoveChapterForm();
		form.setTargetVolumeId(TARGET_VOLUME_ID);

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));
		when(moveChapterUseCase.execute(new MoveChapterCommand(CHAPTER_ID, TARGET_VOLUME_ID, ADMIN_ID)))
				.thenReturn(movedChapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.moveChapter(CHAPTER_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/volumes/" + TARGET_VOLUME_ID + "/chapters");
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã chuyển Chapter \"Chương Một\" sang Volume khác.");

		ArgumentCaptor<MoveChapterCommand> commandCaptor = ArgumentCaptor.forClass(MoveChapterCommand.class);
		verify(moveChapterUseCase).execute(commandCaptor.capture());
		assertThat(commandCaptor.getValue().targetVolumeId()).isEqualTo(TARGET_VOLUME_ID);
		assertThat(commandCaptor.getValue().actorId()).isEqualTo(ADMIN_ID);
	}

	@Test
	@DisplayName("Publish thất bại thì flash errorMessage")
	void shouldFlashErrorWhenPublishFails() {
		stubCurrentActor();

		when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapterDto("DRAFT"));
		when(publishChapterUseCase.execute(new PublishChapterCommand(CHAPTER_ID, ADMIN_ID)))
				.thenThrow(new IllegalStateException("Chỉ chương ở trạng thái DRAFT mới được xuất bản."));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		controller.publishChapter(CHAPTER_ID, authentication, redirectAttributes);

		assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
				.isEqualTo("Chỉ chương ở trạng thái DRAFT mới được xuất bản.");
	}

	private void stubCurrentActor() {
		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);
		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(new UserDTO(ADMIN_ID, ADMIN_EMAIL,
				"Admin", null, "ACTIVE", "ADMIN", NOW)));
	}

	/*
	 * ===================================================== ACTOR RESOLUTION TESTS
	 * =====================================================
	 */

	@Test
	@DisplayName("Tạo Chapter thành công khi Admin đăng nhập bằng OAuth2 (Google) với subject ID số và email attribute")
	void shouldCreateChapterWhenAuthenticatedViaOAuth2() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");
		form.setSummary("Tóm tắt 100");
		form.setContent("Nội dung 100");

		OAuth2User oauth2User = mock(OAuth2User.class);
		when(oauth2User.getAttribute("email")).thenReturn(ADMIN_EMAIL);

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getPrincipal()).thenReturn(oauth2User);
		lenient().when(authentication.getName()).thenReturn("104829374019283746152");

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(new UserDTO(ADMIN_ID, ADMIN_EMAIL,
				"Admin", null, "ACTIVE", "ADMIN", NOW)));

		when(createChapterUseCase.execute(any(CreateChapterCommand.class)))
				.thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.createChapter(VOLUME_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã tạo Chapter \"Chương Một\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
		ArgumentCaptor<CreateChapterCommand> commandCaptor = ArgumentCaptor.forClass(CreateChapterCommand.class);
		verify(createChapterUseCase).execute(commandCaptor.capture());
		assertThat(commandCaptor.getValue().actorId()).isEqualTo(ADMIN_ID);
	}

	@Test
	@DisplayName("Tạo Chapter thành công khi Admin đăng nhập bằng form login chuẩn với email principal")
	void shouldCreateChapterWhenAuthenticatedViaFormLogin() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");
		form.setSummary("Tóm tắt 100");
		form.setContent("Nội dung 100");

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getName()).thenReturn(ADMIN_EMAIL);
		when(authentication.getPrincipal()).thenReturn(ADMIN_EMAIL);

		when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(new UserDTO(ADMIN_ID, ADMIN_EMAIL,
				"Admin", null, "ACTIVE", "ADMIN", NOW)));

		when(createChapterUseCase.execute(any(CreateChapterCommand.class)))
				.thenReturn(chapterDto("DRAFT"));

		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String viewName = controller.createChapter(VOLUME_ID, form, authentication, redirectAttributes);

		assertThat(viewName).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID);
		assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
				.isEqualTo("Đã tạo Chapter \"Chương Một\".");

		verify(userIdentityContract).findByEmail(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Từ chối tạo Chapter khi Authentication là null")
	void shouldRejectWhenAuthenticationIsNull() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");

		assertThatThrownBy(() -> controller.createChapter(VOLUME_ID, form, null,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(createChapterUseCase, never()).execute(any());
	}

	@Test
	@DisplayName("Từ chối tạo Chapter khi Authentication là AnonymousAuthenticationToken")
	void shouldRejectWhenAuthenticationIsAnonymous() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");

		Authentication anonymousAuth = mock(AnonymousAuthenticationToken.class);

		assertThatThrownBy(() -> controller.createChapter(VOLUME_ID, form, anonymousAuth,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(createChapterUseCase, never()).execute(any());
	}

	@Test
	@DisplayName("Từ chối tạo Chapter khi Authentication chưa được xác thực (isAuthenticated = false)")
	void shouldRejectWhenAuthenticationIsNotAuthenticated() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");

		when(authentication.isAuthenticated()).thenReturn(false);

		assertThatThrownBy(() -> controller.createChapter(VOLUME_ID, form, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(createChapterUseCase, never()).execute(any());
	}

	@Test
	@DisplayName("Từ chối tạo Chapter khi OAuth2 principal không có email attribute")
	void shouldRejectWhenOAuth2UserHasNoEmailAttribute() {
		CreateChapterForm form = new CreateChapterForm();
		form.setChapterNumber(100);
		form.setTitle("Chương 100");

		OAuth2User oauth2User = mock(OAuth2User.class);
		when(oauth2User.getAttribute("email")).thenReturn(null);

		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getPrincipal()).thenReturn(oauth2User);

		assertThatThrownBy(() -> controller.createChapter(VOLUME_ID, form, authentication,
				new RedirectAttributesModelMap()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Không xác định được người dùng đang đăng nhập.");

		verify(userIdentityContract, never()).findByEmail(any());
		verify(createChapterUseCase, never()).execute(any());
	}

	private ChapterDTO chapterDto(String status) {
		return new ChapterDTO(CHAPTER_ID, VOLUME_ID, 1, "Chương Một", "quyen-1-chuong-1", "Tóm tắt.", "Nội dung.",
				status, ADMIN_ID, ADMIN_ID, null, null, NOW, NOW, null, null, 1L, 1L);
	}

	private ChapterDTO movedChapterDto(String status) {
		return new ChapterDTO(CHAPTER_ID, TARGET_VOLUME_ID, 1, "Chương Một", "quyen-13-chuong-1", "Tóm tắt.",
				"Nội dung.", status, ADMIN_ID, ADMIN_ID, null, null, NOW, NOW, null, null, 1L, 1L);
	}
}
