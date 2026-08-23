document.addEventListener("DOMContentLoaded", function () {
	const editor = document.getElementById("novelContent");

	if (!editor) {
		return;
	}

	const history = createHistoryManager(editor);

	setupMarkdownPreview(editor);

	document.querySelectorAll("[data-markdown-action]").forEach(function (button) {
		button.addEventListener("mousedown", function (event) {
			event.preventDefault();
		});

		button.addEventListener("click", function () {
			const action = button.dataset.markdownAction;

			if (action === "undo") {
				history.undo();
				return;
			}

			if (action === "redo") {
				history.redo();
				return;
			}

			history.flush();
			applyMarkdownAction(editor, action);
			history.record();
		});
	});

	editor.addEventListener("keydown", function (event) {
		const modifier = event.ctrlKey || event.metaKey;

		if (!modifier) {
			return;
		}

		const key = event.key.toLowerCase();

		if (key === "b") {
			event.preventDefault();
			history.flush();
			toggleInline(editor, "**", "**", "văn bản in đậm");
			history.record();
			return;
		}

		if (key === "i") {
			event.preventDefault();
			history.flush();
			toggleInline(editor, "_", "_", "văn bản in nghiêng");
			history.record();
			return;
		}

		if (key === "k") {
			event.preventDefault();
			history.flush();
			insertLink(editor);
			history.record();
			return;
		}

		if (key === "z" && event.shiftKey) {
			event.preventDefault();
			history.redo();
			return;
		}

		if (key === "z" && !event.shiftKey) {
			event.preventDefault();
			history.undo();
			return;
		}

		if (key === "y") {
			event.preventDefault();
			history.redo();
		}
	});

	editor.addEventListener("input", function () {
		history.schedule();
	});
});

function applyMarkdownAction(editor, action) {
	switch (action) {
		case "h2":
			applyHeading(editor, "## ");
			break;
		case "h3":
			applyHeading(editor, "### ");
			break;
		case "h4":
			applyHeading(editor, "#### ");
			break;
		case "bold":
			toggleInline(editor, "**", "**", "văn bản in đậm");
			break;
		case "italic":
			toggleInline(editor, "_", "_", "văn bản in nghiêng");
			break;
		case "link":
			insertLink(editor);
			break;
		case "quote":
			toggleLinePrefix(editor, "> ");
			break;
		case "list":
			toggleLinePrefix(editor, "- ");
			break;
		default:
			console.warn("Markdown action không hỗ trợ:", action);
	}
}

function applyHeading(editor, prefix) {
	const value = editor.value;
	const start = editor.selectionStart;
	const end = editor.selectionEnd;
	const lineStart = value.lastIndexOf("\n", start - 1) + 1;
	const nextLineBreak = value.indexOf("\n", end);
	const lineEnd = nextLineBreak === -1 ? value.length : nextLineBreak;

	let selectedLines = value.substring(lineStart, lineEnd);
	selectedLines = selectedLines.replace(/^(#{1,6})\s+/gm, "");

	const transformed = selectedLines
		.split("\n")
		.map(function (line) {
			if (!line.trim()) {
				return line;
			}
			return prefix + line;
		})
		.join("\n");

	replaceRange(editor, lineStart, lineEnd, transformed);
	editor.focus();
}

function toggleInline(editor, before, after, placeholder) {
	const start = editor.selectionStart;
	const end = editor.selectionEnd;
	const value = editor.value;
	const selected = value.substring(start, end);

	if (
		selected.length > 0 &&
		selected.startsWith(before) &&
		selected.endsWith(after) &&
		selected.length >= before.length + after.length
	) {
		const inner = selected.substring(before.length, selected.length - after.length);
		replaceRange(editor, start, end, inner);
		editor.focus();
		editor.setSelectionRange(start, start + inner.length);
		return;
	}

	if (
		selected.length > 0 &&
		start >= before.length &&
		value.substring(start - before.length, start) === before &&
		value.substring(end, end + after.length) === after
	) {
		replaceRange(editor, start - before.length, end + after.length, selected);
		editor.focus();
		editor.setSelectionRange(start - before.length, start - before.length + selected.length);
		return;
	}

	if (selected.length > 0 && before === "**" && after === "**") {
		if (
			start >= 3 &&
			value.substring(start - 3, start) === "**_" &&
			value.substring(end, end + 3) === "_**"
		) {
			const replacement = "_" + selected + "_";
			replaceRange(editor, start - 3, end + 3, replacement);
			editor.focus();
			editor.setSelectionRange(start - 2, start - 2 + selected.length);
			return;
		}

		if (
			start >= 1 &&
			value.substring(start - 1, start) === "_" &&
			value.substring(end, end + 1) === "_"
		) {
			const replacement = "**_" + selected + "_**";
			replaceRange(editor, start - 1, end + 1, replacement);
			editor.focus();
			editor.setSelectionRange(start + 2, start + 2 + selected.length);
			return;
		}
	}

	const content = selected.length > 0 ? selected : placeholder;
	const replacement = before + content + after;

	replaceRange(editor, start, end, replacement);
	editor.focus();

	if (selected.length === 0) {
		editor.setSelectionRange(start + before.length, start + before.length + placeholder.length);
		return;
	}

	editor.setSelectionRange(start + before.length, start + before.length + selected.length);
}

function insertLink(editor) {
	const start = editor.selectionStart;
	const end = editor.selectionEnd;
	const selected = editor.value.substring(start, end);
	const label = selected.length > 0 ? selected : "Tên liên kết";
	const defaultUrl = "https://example.com";
	const replacement = "[" + label + "](" + defaultUrl + ")";

	replaceRange(editor, start, end, replacement);
	editor.focus();

	const urlStart = start + 1 + label.length + 2;
	const urlEnd = urlStart + defaultUrl.length;
	editor.setSelectionRange(urlStart, urlEnd);
}

function toggleLinePrefix(editor, prefix) {
	const value = editor.value;
	const start = editor.selectionStart;
	const end = editor.selectionEnd;
	const lineStart = value.lastIndexOf("\n", start - 1) + 1;
	const nextLineBreak = value.indexOf("\n", end);
	const lineEnd = nextLineBreak === -1 ? value.length : nextLineBreak;
	const selectedLines = value.substring(lineStart, lineEnd);
	const lines = selectedLines.split("\n");

	const meaningfulLines = lines.filter(function (line) {
		return line.trim().length > 0;
	});

	const shouldRemove =
		meaningfulLines.length > 0 &&
		meaningfulLines.every(function (line) {
			return line.startsWith(prefix);
		});

	const transformed = lines
		.map(function (line) {
			if (!line.trim()) {
				return line;
			}
			if (shouldRemove) {
				return line.substring(prefix.length);
			}
			if (line.startsWith(prefix)) {
				return line;
			}
			return prefix + line;
		})
		.join("\n");

	replaceRange(editor, lineStart, lineEnd, transformed);
	editor.focus();
}

function replaceRange(editor, start, end, replacement) {
	const before = editor.value.substring(0, start);
	const after = editor.value.substring(end);

	editor.value = before + replacement + after;

	const cursorPosition = start + replacement.length;
	editor.setSelectionRange(cursorPosition, cursorPosition);
	editor.dispatchEvent(new Event("input", { bubbles: true }));
}

function createHistoryManager(editor) {
	const history = [];
	let currentIndex = -1;
	let timer = null;
	let restoring = false;

	function createSnapshot() {
		return {
			value: editor.value,
			selectionStart: editor.selectionStart,
			selectionEnd: editor.selectionEnd
		};
	}

	function snapshotsEqual(first, second) {
		if (!first || !second) {
			return false;
		}
		return first.value === second.value;
	}

	function record() {
		if (restoring) {
			return;
		}

		if (timer) {
			clearTimeout(timer);
			timer = null;
		}

		const snapshot = createSnapshot();
		const currentSnapshot = history[currentIndex];

		if (snapshotsEqual(snapshot, currentSnapshot)) {
			return;
		}

		if (currentIndex < history.length - 1) {
			history.splice(currentIndex + 1);
		}

		history.push(snapshot);
		currentIndex = history.length - 1;
	}

	function schedule() {
		if (restoring) {
			return;
		}

		if (timer) {
			clearTimeout(timer);
		}

		timer = setTimeout(function () {
			record();
		}, 350);
	}

	function flush() {
		if (timer) {
			clearTimeout(timer);
			timer = null;
		}
		record();
	}

	function restore(snapshot) {
		if (!snapshot) {
			return;
		}

		restoring = true;
		editor.value = snapshot.value;
		editor.focus();
		editor.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
		editor.dispatchEvent(new Event("input", { bubbles: true }));
		restoring = false;
	}

	function undo() {
		flush();

		if (currentIndex <= 0) {
			return;
		}

		currentIndex--;
		restore(history[currentIndex]);
	}

	function redo() {
		if (timer) {
			clearTimeout(timer);
			timer = null;
		}

		if (currentIndex >= history.length - 1) {
			return;
		}

		currentIndex++;
		restore(history[currentIndex]);
	}

	record();

	return {
		record: record,
		schedule: schedule,
		flush: flush,
		undo: undo,
		redo: redo
	};
}

function setupMarkdownPreview(editor) {
	const writeTab = document.getElementById("novelWriteTab");
	const previewTab = document.getElementById("novelPreviewTab");
	const writePanel = document.getElementById("novelWritePanel");
	const previewPanel = document.getElementById("novelPreviewPanel");
	const previewBody = document.getElementById("novelMarkdownPreview");
	const loadingState = document.getElementById("novelPreviewLoading");
	const emptyState = document.getElementById("novelPreviewEmpty");
	const csrf = document.getElementById("novelCsrf");

	if (!writeTab || !previewTab || !writePanel || !previewPanel || !previewBody) {
		return;
	}

	let lastPreviewSource = null;

	writeTab.addEventListener("click", function () {
		activateTab("write");
		editor.focus();
	});

	previewTab.addEventListener("click", async function () {
		activateTab("preview");
		await renderPreview();
	});

	[writeTab, previewTab].forEach(function (tab) {
		tab.addEventListener("keydown", function (event) {
			if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
				return;
			}

			event.preventDefault();

			const targetTab =
				event.key === "Home" || event.key === "ArrowLeft" ? writeTab : previewTab;

			targetTab.click();

			requestAnimationFrame(function () {
				targetTab.focus();
			});
		});
	});

	function activateTab(mode) {
		const writeActive = mode === "write";

		writeTab.classList.toggle("is-active", writeActive);
		previewTab.classList.toggle("is-active", !writeActive);
		writeTab.setAttribute("aria-selected", String(writeActive));
		previewTab.setAttribute("aria-selected", String(!writeActive));
		writeTab.tabIndex = writeActive ? 0 : -1;
		previewTab.tabIndex = writeActive ? -1 : 0;
		writePanel.hidden = !writeActive;
		previewPanel.hidden = writeActive;
	}

	async function renderPreview() {
		const markdown = editor.value;

		hideState(loadingState);
		hideState(emptyState);

		if (!markdown.trim()) {
			previewBody.innerHTML = "";
			previewBody.dataset.rendered = "false";
			lastPreviewSource = markdown;
			showState(emptyState);
			return;
		}

		if (lastPreviewSource === markdown && previewBody.dataset.rendered === "true") {
			return;
		}

		previewBody.innerHTML = "";
		showState(loadingState);

		try {
			const headers = {
				"Content-Type": "text/plain;charset=UTF-8",
				Accept: "text/html"
			};

			if (csrf && csrf.dataset.token && csrf.dataset.header) {
				headers[csrf.dataset.header] = csrf.dataset.token;
			}

			const response = await fetch("/admin/novel/chapters/content-preview", {
				method: "POST",
				headers: headers,
				body: markdown
			});

			if (!response.ok) {
				throw new Error("Không thể tạo bản xem trước.");
			}

			const html = await response.text();
			previewBody.innerHTML = html;
			previewBody.dataset.rendered = "true";
			lastPreviewSource = markdown;
			hideState(loadingState);
		} catch (error) {
			previewBody.innerHTML = "";
			previewBody.dataset.rendered = "false";
			hideState(loadingState);
			emptyState.textContent = "Không thể tạo bản xem trước.";
			showState(emptyState);
		}
	}

	function showState(element) {
		if (element) {
			element.hidden = false;
		}
	}

	function hideState(element) {
		if (element) {
			element.hidden = true;
		}
	}
}
