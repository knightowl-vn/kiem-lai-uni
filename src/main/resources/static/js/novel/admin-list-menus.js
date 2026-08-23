/**
 * Novel admin list pages: exclusive menus + fixed action dropdown positioning.
 *
 * Action menus are <details.novel-admin-list-action-menu>.
 * Filter controls are native <select>/<input> inside .novel-admin-filter-form.
 * Only one popup surface may be active at a time.
 */
(function () {
	"use strict";

	var ACTION_MENU_SELECTOR = ".novel-admin-list-action-menu";
	var ACTION_DROPDOWN_SELECTOR = ".novel-admin-detail-action-dropdown";
	var FILTER_FORM_SELECTOR = ".novel-admin-filter-form";
	var FILTER_CONTROL_SELECTOR =
		".novel-admin-filter-form select, .novel-admin-filter-form input";

	function isActionMenu(element) {
		return (
			element instanceof HTMLDetailsElement &&
			element.classList.contains("novel-admin-list-action-menu")
		);
	}

	function queryOpenActionMenus() {
		return document.querySelectorAll(ACTION_MENU_SELECTOR + "[open]");
	}

	function resetDropdownPosition(dropdown) {
		if (!dropdown) {
			return;
		}

		dropdown.style.position = "";
		dropdown.style.top = "";
		dropdown.style.left = "";
		dropdown.style.right = "";
		dropdown.style.bottom = "";
		dropdown.style.maxHeight = "";
		dropdown.style.overflowY = "";
		dropdown.style.zIndex = "";
	}

	function closeActionMenus(exceptMenu) {
		queryOpenActionMenus().forEach(function (menu) {
			if (exceptMenu && menu === exceptMenu) {
				return;
			}

			menu.open = false;
		});
	}

	function blurFilterControls(exceptControl) {
		document.querySelectorAll(FILTER_CONTROL_SELECTOR).forEach(function (control) {
			if (exceptControl && control === exceptControl) {
				return;
			}

			if (document.activeElement === control) {
				control.blur();
			}
		});
	}

	function closeAllOverlays(exceptMenu, exceptControl) {
		closeActionMenus(exceptMenu);
		blurFilterControls(exceptControl);
	}

	function positionDropdown(menu) {
		var dropdown = menu.querySelector(ACTION_DROPDOWN_SELECTOR);
		var trigger = menu.querySelector("summary");

		if (!dropdown || !trigger) {
			return;
		}

		resetDropdownPosition(dropdown);

		var rect = trigger.getBoundingClientRect();
		var gap = 6;
		var viewportPadding = 8;
		var dropdownWidth = dropdown.offsetWidth || 280;

		var left = rect.right - dropdownWidth;
		if (left < viewportPadding) {
			left = viewportPadding;
		}
		if (left + dropdownWidth > window.innerWidth - viewportPadding) {
			left = Math.max(
				viewportPadding,
				window.innerWidth - dropdownWidth - viewportPadding
			);
		}

		var top = rect.bottom + gap;

		dropdown.style.position = "fixed";
		dropdown.style.left = left + "px";
		dropdown.style.right = "auto";
		dropdown.style.top = top + "px";
		dropdown.style.zIndex = "1000";

		var dropdownHeight = dropdown.offsetHeight;
		var spaceBelow = window.innerHeight - rect.bottom - gap - viewportPadding;
		var spaceAbove = rect.top - gap - viewportPadding;

		if (dropdownHeight > spaceBelow && spaceAbove > spaceBelow) {
			top = Math.max(viewportPadding, rect.top - dropdownHeight - gap);
			dropdown.style.top = top + "px";
		}

		var available =
			window.innerHeight - parseFloat(dropdown.style.top) - viewportPadding;

		if (dropdownHeight > available) {
			dropdown.style.maxHeight = available + "px";
			dropdown.style.overflowY = "auto";
		}
	}

	function repositionOpenMenus() {
		queryOpenActionMenus().forEach(positionDropdown);
	}

	/* Confirm destructive/lifecycle posts */
	document.addEventListener("submit", function (event) {
		var form = event.target.closest("[data-novel-confirm]");

		if (!form) {
			return;
		}

		var message = form.dataset.novelConfirm && form.dataset.novelConfirm.trim();

		if (!message) {
			return;
		}

		if (!window.confirm(message)) {
			event.preventDefault();
		}
	});

	/*
	 * Opening an action menu closes every other action menu and blurs
	 * filter selects/inputs so native OS dropdowns collapse.
	 */
	document.addEventListener(
		"toggle",
		function (event) {
			var menu = event.target;

			if (!isActionMenu(menu)) {
				return;
			}

			var dropdown = menu.querySelector(ACTION_DROPDOWN_SELECTOR);

			if (!menu.open) {
				resetDropdownPosition(dropdown);
				return;
			}

			closeActionMenus(menu);
			blurFilterControls();

			requestAnimationFrame(function () {
				positionDropdown(menu);
			});
		},
		true
	);

	/*
	 * Opening / focusing a filter control closes every action menu.
	 * Native <select> cannot be force-closed while focused; blurring others
	 * and closing <details> is the reliable exclusive rule.
	 */
	document.addEventListener(
		"pointerdown",
		function (event) {
			var control = event.target.closest(FILTER_CONTROL_SELECTOR);

			if (!control) {
				return;
			}

			closeActionMenus();
		},
		true
	);

	document.addEventListener(
		"focusin",
		function (event) {
			var control = event.target.closest(FILTER_CONTROL_SELECTOR);

			if (!control) {
				return;
			}

			closeActionMenus();
		},
		true
	);

	/* Bootstrap Dropdown compatibility (if Bootstrap JS is present later). */
	document.addEventListener("show.bs.dropdown", function () {
		blurFilterControls();
		closeActionMenus();
	});

	/* Click outside: close action menus; blur filter controls when leaving filter. */
	document.addEventListener(
		"pointerdown",
		function (event) {
			var target = event.target;

			if (!(target instanceof Element)) {
				return;
			}

			var insideActionMenu = target.closest(ACTION_MENU_SELECTOR);
			var insideFilterForm = target.closest(FILTER_FORM_SELECTOR);

			if (!insideActionMenu) {
				closeActionMenus();
			}

			if (!insideFilterForm && !insideActionMenu) {
				blurFilterControls();
			}
		},
		true
	);

	document.addEventListener("keydown", function (event) {
		if (event.key !== "Escape") {
			return;
		}

		closeAllOverlays();
	});

	window.addEventListener("resize", repositionOpenMenus);
	window.addEventListener("scroll", repositionOpenMenus, true);
})();
