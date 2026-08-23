package com.universe.novel.entry.admin;

import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.entry.admin.form.CreateVolumeForm;
import com.universe.novel.entry.admin.form.EditVolumeForm;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/volumes")
public class AdminNovelVolumePageController {

	private static final String PAGE_TITLE = "Quản lý tiểu thuyết";

	private static final String ACTIVE_MENU = "novel";

	private final GetVolumeListUseCase getVolumeListUseCase;

	private final GetVolumeDetailUseCase getVolumeDetailUseCase;

	public AdminNovelVolumePageController(GetVolumeListUseCase getVolumeListUseCase,
			GetVolumeDetailUseCase getVolumeDetailUseCase) {
		this.getVolumeListUseCase = getVolumeListUseCase;

		this.getVolumeDetailUseCase = getVolumeDetailUseCase;
	}

	/**
	 * Trang danh sách Volume trong khu vực quản trị.
	 *
	 * GET /admin/novel/volumes
	 */
	@GetMapping({ "", "/" })
	public String listPage(@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String status, Model model, HttpServletResponse response) {

		/*
		 * Volume có thể thay đổi trạng thái trong khu vực quản trị.
		 *
		 * Không cho browser dùng lại HTML cũ.
		 */
		disableCaching(response);

		String normalizedKeyword = NovelAdminListFilters.normalizeKeyword(keyword);
		String selectedStatus = NovelAdminListFilters.normalizeStatus(status);

		List<VolumeDTO> volumes = getVolumeListUseCase.execute().stream()
				.filter(volume -> NovelAdminListFilters.matches(normalizedKeyword, selectedStatus, volume.title(),
						volume.slug(), volume.status()))
				.toList();

		model.addAttribute("volumes", volumes);
		model.addAttribute("keyword", normalizedKeyword);
		model.addAttribute("selectedStatus", selectedStatus);
		model.addAttribute("pageTitle", PAGE_TITLE);
		model.addAttribute("activeMenu", ACTIVE_MENU);
		model.addAttribute("activeSubMenu", "volumes");

		return "admin/novel/volumes";
	}
	
	/**
	 * Trang tạo Volume mới.
	 *
	 * GET /admin/novel/volumes/new
	 */
	@GetMapping("/new")
	public String createPage(
	        Model model,
	        HttpServletResponse response
	) {

	    disableCaching(response);

	    model.addAttribute(
	            "form",
	            new CreateVolumeForm()
	    );

	    model.addAttribute(
	            "pageTitle",
	            "Tạo Volume"
	    );

	    model.addAttribute(
	            "activeMenu",
	            ACTIVE_MENU
	    );

	    return "admin/novel/volume-create";
	}

	/**
	 * Trang chi tiết Volume.
	 *
	 * GET /admin/novel/volumes/{id}
	 */
	@GetMapping("/{id}")
	public String detailPage(
	        @PathVariable UUID id,
	        Model model,
	        HttpServletResponse response
	) {

		disableCaching(response);

		VolumeDTO volume = getVolumeDetailUseCase.execute(id);

		model.addAttribute("volume", volume);

		model.addAttribute("pageTitle", "Chi tiết Volume");

		model.addAttribute("activeMenu", ACTIVE_MENU);

		return "admin/novel/volume-detail";
	}
	
	/**
	 * Trang chỉnh sửa Volume Draft.
	 *
	 * GET /admin/novel/volumes/{id}/edit
	 */
	@GetMapping("/{id}/edit")
	public String editPage(
	        @PathVariable UUID id,
	        Model model,
	        HttpServletResponse response,
	        RedirectAttributes redirectAttributes
	) {

	    disableCaching(response);

	    VolumeDTO volume =
	            getVolumeDetailUseCase.execute(
	                    id
	            );

	    if (!"DRAFT".equals(
	            volume.status()
	    )) {
	        redirectAttributes.addFlashAttribute(
	                "errorMessage",
	                "Volume không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
	        );

	        return "redirect:/admin/novel/volumes/"
	                + id;
	    }

	    EditVolumeForm form =
	            new EditVolumeForm();

	    form.setTitle(
	            volume.title()
	    );

	    form.setDescription(
	            volume.description()
	    );

	    model.addAttribute(
	            "volume",
	            volume
	    );

	    model.addAttribute(
	            "form",
	            form
	    );

	    model.addAttribute(
	            "pageTitle",
	            "Chỉnh sửa Volume"
	    );

	    model.addAttribute(
	            "activeMenu",
	            ACTIVE_MENU
	    );

	    return "admin/novel/volume-edit";
	}

	private void disableCaching(
	        HttpServletResponse response
	) {
	    response.setHeader(
	            "Cache-Control",
	            "no-store, no-cache, must-revalidate, max-age=0"
	    );

	    response.setHeader(
	            "Pragma",
	            "no-cache"
	    );

	    response.setDateHeader(
	            "Expires",
	            0
	    );
	}
}