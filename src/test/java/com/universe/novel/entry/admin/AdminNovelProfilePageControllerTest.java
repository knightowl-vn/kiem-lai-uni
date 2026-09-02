package com.universe.novel.entry.admin;

import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.entry.admin.form.EditNovelProfileForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelProfilePageControllerTest {

    private static final UUID PROFILE_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-08-22T17:30:00Z");

    @Mock
    private GetNovelProfileUseCase
            getNovelProfileUseCase;

    private AdminNovelProfilePageController
            controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelProfilePageController(
                getNovelProfileUseCase
        );
    }

    @Test
    @DisplayName("GET /admin/novel/profile hiển thị view admin/novel/profile với đầy đủ model attributes")
    void shouldRenderProfilePageSuccessfully() {
        NovelProfileDTO profile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả tiểu thuyết Kiếm Lai.",
                "https://example.com/cover.jpg",
                "ONGOING",
                CREATED_AT,
                UPDATED_AT
        );

        when(getNovelProfileUseCase.execute()).thenReturn(profile);

        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.profilePage(model);

        assertThat(view).isEqualTo("admin/novel/profile");
        assertThat(model.getAttribute("profile")).isEqualTo(profile);
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Hồ sơ Novel");
        assertThat(model.getAttribute("activeMenu")).isEqualTo("novel");
        assertThat(model.getAttribute("activeSubMenu")).isEqualTo("profile");

        EditNovelProfileForm form = (EditNovelProfileForm) model.getAttribute("form");
        assertThat(form).isNotNull();
        assertThat(form.getTitle()).isEqualTo("Kiếm Lai");
        assertThat(form.getAuthor()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(form.getDescription()).isEqualTo("Mô tả tiểu thuyết Kiếm Lai.");
        assertThat(form.getCoverImageUrl()).isEqualTo("https://example.com/cover.jpg");
        assertThat(form.getStatus()).isEqualTo("ONGOING");

        verify(getNovelProfileUseCase).execute();
    }

    @Test
    @DisplayName("GET /admin/novel/profile với Media-backed cover hiển thị form/displayCoverUrl với đường dẫn /media/assets/{id}/content trong khi profile giữ nguyên raw legacy URL")
    void shouldRenderProfilePageWithMediaBackedCover() {
        UUID mediaAssetId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        NovelProfileDTO profile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả tiểu thuyết Kiếm Lai.",
                "https://res.cloudinary.com/legacy-cover.jpg",
                mediaAssetId,
                "ONGOING",
                CREATED_AT,
                UPDATED_AT
        );

        when(getNovelProfileUseCase.execute()).thenReturn(profile);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.profilePage(model);

        assertThat(view).isEqualTo("admin/novel/profile");
        assertThat(model.getAttribute("profile")).isEqualTo(profile);
        assertThat(model.getAttribute("displayCoverUrl")).isEqualTo("/media/assets/" + mediaAssetId + "/content");

        // Profile giữ nguyên raw legacy URL
        assertThat(((NovelProfileDTO) model.getAttribute("profile")).coverImageUrl())
                .isEqualTo("https://res.cloudinary.com/legacy-cover.jpg");
        assertThat(((NovelProfileDTO) model.getAttribute("profile")).coverMediaAssetId())
                .isEqualTo(mediaAssetId);

        // Form và display nhận URL Media delivery (Media thắng cho display)
        EditNovelProfileForm form = (EditNovelProfileForm) model.getAttribute("form");
        assertThat(form).isNotNull();
        assertThat(form.getCoverImageUrl()).isEqualTo("/media/assets/" + mediaAssetId + "/content");

        verify(getNovelProfileUseCase).execute();
    }
}
