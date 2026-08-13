document.addEventListener(
    "DOMContentLoaded",
    function() {

        const editor =
            document.getElementById(
                "wikiContent"
            );

        if (!editor) {
            return;
        }

        const history =
            createHistoryManager(
                editor
            );
        setupImageUpload(
            editor,
            history
        );

        setupMarkdownPreview(
            editor
        );

        setupWikiEditorDiagnostics(
            editor,
            history
        );

        setupWikiLocalAutosave(
            editor
        );

        const toolbarButtons =
            document.querySelectorAll(
                "[data-markdown-action]"
            );

        toolbarButtons.forEach(
            function(button) {

                /*
                 * Không cho toolbar làm textarea mất focus
                 * trước khi action đọc selectionStart/selectionEnd.
                 */
                button.addEventListener(
                    "mousedown",
                    function(event) {

                        event.preventDefault();
                    }
                );


                button.addEventListener(
                    "click",
                    function() {

                        const action =
                            button.dataset
                                .markdownAction;

                        if (action === "undo") {
                            history.undo();
                            return;
                        }

                        if (action === "redo") {
                            history.redo();
                            return;
                        }

                        /*
                         * Đảm bảo trạng thái trước khi thao tác
                         * đã tồn tại trong history.
                         */
                        history.flush();

                        applyMarkdownAction(
                            editor,
                            action
                        );

                        history.record();
                    }
                );
            }
        );


        /* =================================================
           KEYBOARD SHORTCUTS
           ================================================= */

        editor.addEventListener(
            "keydown",
            function(event) {

                const modifier =
                    event.ctrlKey
                    || event.metaKey;

                if (!modifier) {
                    return;
                }

                const key =
                    event.key.toLowerCase();

                /*
                 * Ctrl + B
                 */
                if (key === "b") {

                    event.preventDefault();

                    history.flush();

                    toggleInline(
                        editor,
                        "**",
                        "**",
                        "văn bản in đậm"
                    );

                    history.record();

                    return;
                }

                /*
                 * Ctrl + I
                 */
                if (key === "i") {

                    event.preventDefault();

                    history.flush();

                    toggleInline(
                        editor,
                        "*",
                        "*",
                        "văn bản in nghiêng"
                    );

                    history.record();

                    return;
                }

                /*
                 * Ctrl + K
                 */
                if (key === "k") {

                    event.preventDefault();

                    history.flush();

                    insertLink(
                        editor
                    );

                    history.record();

                    return;
                }

                /*
                 * Ctrl + Shift + Z
                 *
                 * Redo kiểu macOS / nhiều editor hiện đại.
                 */
                if (
                    key === "z"
                    && event.shiftKey
                ) {
                    event.preventDefault();

                    history.redo();

                    return;
                }

                /*
                 * Ctrl + Z
                 */
                if (
                    key === "z"
                    && !event.shiftKey
                ) {
                    event.preventDefault();

                    history.undo();

                    return;
                }

                /*
                 * Ctrl + Y
                 */
                if (key === "y") {

                    event.preventDefault();

                    history.redo();
                }
            }
        );


        /*
         * Theo dõi text được gõ trực tiếp.
         *
         * Delay một chút để không tạo một history
         * cho từng ký tự.
         */
        editor.addEventListener(
            "input",
            function() {
                history.schedule();
            }
        );
    }
);


/* =========================================================
   ACTION ROUTER
   ========================================================= */

function applyMarkdownAction(
    editor,
    action
) {
    switch (action) {

        case "h2":
            applyHeading(
                editor,
                "## "
            );
            break;

        case "h3":
            applyHeading(
                editor,
                "### "
            );
            break;

        case "h4":
            applyHeading(
                editor,
                "#### "
            );
            break;

        case "bold":
            toggleInline(
                editor,
                "**",
                "**",
                "văn bản in đậm"
            );
            break;

        case "italic":
            toggleInline(
                editor,
                "*",
                "*",
                "văn bản in nghiêng"
            );
            break;

        case "link":
            insertLink(
                editor
            );
            break;

        case "quote":
            toggleLinePrefix(
                editor,
                "> "
            );
            break;

        case "list":
            toggleLinePrefix(
                editor,
                "- "
            );
            break;

        case "clear-wrap":
            insertClearWrapMarker(
                editor
            );
            break;

        default:
            console.warn(
                "Markdown action không hỗ trợ:",
                action
            );
    }
}


/* =========================================================
   HEADINGS
   ========================================================= */

function applyHeading(
    editor,
    prefix
) {
    const value =
        editor.value;

    const start =
        editor.selectionStart;

    const end =
        editor.selectionEnd;

    const lineStart =
        value.lastIndexOf(
            "\n",
            start - 1
        ) + 1;

    const nextLineBreak =
        value.indexOf(
            "\n",
            end
        );

    const lineEnd =
        nextLineBreak === -1
            ? value.length
            : nextLineBreak;

    let selectedLines =
        value.substring(
            lineStart,
            lineEnd
        );

    /*
     * Bỏ heading cũ trước khi đặt heading mới.
     *
     * ### ABC
     * bấm H2
     * →
     * ## ABC
     */
    selectedLines =
        selectedLines.replace(
            /^(#{1,6})\s+/gm,
            ""
        );

    const transformed =
        selectedLines
            .split("\n")
            .map(function(line) {

                if (!line.trim()) {
                    return line;
                }

                return prefix + line;
            })
            .join("\n");

    replaceRange(
        editor,
        lineStart,
        lineEnd,
        transformed
    );

    editor.focus();
}


/* =========================================================
   BOLD / ITALIC — TOGGLE
   ========================================================= */

function toggleInline(
    editor,
    before,
    after,
    placeholder
) {
    const start =
        editor.selectionStart;

    const end =
        editor.selectionEnd;

    const value =
        editor.value;

    const selected =
        value.substring(
            start,
            end
        );


    /*
     * CASE 1
     *
     * Người dùng chọn cả:
     *
     * **Trần Bình An**
     *
     * rồi bấm B.
     *
     * → bỏ **
     */
    if (
        selected.length > 0
        && selected.startsWith(before)
        && selected.endsWith(after)
        && selected.length
        >= before.length + after.length
    ) {
        const inner =
            selected.substring(
                before.length,
                selected.length
                - after.length
            );

        replaceRange(
            editor,
            start,
            end,
            inner
        );

        editor.focus();

        editor.setSelectionRange(
            start,
            start + inner.length
        );

        return;
    }


    /*
     * CASE 2
     *
     * Source:
     *
     * **Trần Bình An**
     *
     * Người dùng chỉ select:
     *
     * Trần Bình An
     *
     * → phát hiện ** ở hai bên
     * → bỏ formatting.
     */
    if (
        selected.length > 0
        && start >= before.length
        && value.substring(
            start - before.length,
            start
        ) === before
        && value.substring(
            end,
            end + after.length
        ) === after
    ) {
        const replacement =
            selected;

        replaceRange(
            editor,
            start - before.length,
            end + after.length,
            replacement
        );

        editor.focus();

        editor.setSelectionRange(
            start - before.length,
            start
            - before.length
            + selected.length
        );

        return;
    }


    /*
     * CASE 3
     *
     * Chưa được format → thêm Markdown.
     */
    const content =
        selected.length > 0
            ? selected
            : placeholder;

    const replacement =
        before
        + content
        + after;

    replaceRange(
        editor,
        start,
        end,
        replacement
    );

    editor.focus();

    if (selected.length === 0) {

        editor.setSelectionRange(
            start + before.length,
            start
            + before.length
            + placeholder.length
        );

        return;
    }

    /*
     * Sau khi format,
     * giữ selection ở phần text bên trong.
     */
    editor.setSelectionRange(
        start + before.length,
        start
        + before.length
        + selected.length
    );
}


/* =========================================================
   LINK
   ========================================================= */

function insertLink(
    editor
) {
    const start =
        editor.selectionStart;

    const end =
        editor.selectionEnd;

    const selected =
        editor.value.substring(
            start,
            end
        );

    const label =
        selected.length > 0
            ? selected
            : "Tên liên kết";

    const defaultUrl =
        "https://example.com";

    const replacement =
        "["
        + label
        + "]("
        + defaultUrl
        + ")";

    replaceRange(
        editor,
        start,
        end,
        replacement
    );

    editor.focus();

    /*
     * Chọn sẵn URL.
     */
    const urlStart =
        start
        + 1
        + label.length
        + 2;

    const urlEnd =
        urlStart
        + defaultUrl.length;

    editor.setSelectionRange(
        urlStart,
        urlEnd
    );
}


/* =========================================================
   QUOTE / LIST — TOGGLE
   ========================================================= */

function toggleLinePrefix(
    editor,
    prefix
) {
    const value =
        editor.value;

    const start =
        editor.selectionStart;

    const end =
        editor.selectionEnd;

    const lineStart =
        value.lastIndexOf(
            "\n",
            start - 1
        ) + 1;

    const nextLineBreak =
        value.indexOf(
            "\n",
            end
        );

    const lineEnd =
        nextLineBreak === -1
            ? value.length
            : nextLineBreak;

    const selectedLines =
        value.substring(
            lineStart,
            lineEnd
        );

    const lines =
        selectedLines.split(
            "\n"
        );

    /*
     * Nếu tất cả dòng không rỗng đều đã có prefix
     * thì bấm lần nữa = bỏ prefix.
     */
    const meaningfulLines =
        lines.filter(
            function(line) {
                return line.trim().length > 0;
            }
        );

    const shouldRemove =
        meaningfulLines.length > 0
        && meaningfulLines.every(
            function(line) {
                return line.startsWith(prefix);
            }
        );

    const transformed =
        lines
            .map(function(line) {

                if (!line.trim()) {
                    return line;
                }

                if (shouldRemove) {
                    return line.substring(
                        prefix.length
                    );
                }

                if (line.startsWith(prefix)) {
                    return line;
                }

                return prefix + line;
            })
            .join("\n");

    replaceRange(
        editor,
        lineStart,
        lineEnd,
        transformed
    );

    editor.focus();
}


/* =========================================================
   STOP IMAGE TEXT WRAPPING
   ========================================================= */

function insertClearWrapMarker(
    editor
) {
    const marker =
        "[[WIKI_CLEAR]]";


    const value =
        editor.value;


    /*
     * Dùng CHÍNH XÁC vị trí caret.
     *
     * Không tìm đầu dòng,
     * vì textarea có thể chỉ đang visual-wrap.
     */
    const cursor =
        editor.selectionStart;


    const before =
        value.substring(
            0,
            cursor
        );


    const after =
        value.substring(
            cursor
        );


    /*
     * =====================================================
     * KHÔNG CHÈN MARKER TRÙNG
     * =====================================================
     */

    const trimmedBefore =
        before.replace(
            /\s+$/,
            ""
        );


    const trimmedAfter =
        after.replace(
            /^\s+/,
            ""
        );


    if (
        trimmedBefore.endsWith(
            marker
        )
        || trimmedAfter.startsWith(
            marker
        )
    ) {

        editor.focus();

        return;
    }


    /*
     * =====================================================
     * TẠO PARAGRAPH RIÊNG CHO MARKER
     * =====================================================
     */

    let prefix =
        "";


    if (
        before.length > 0
        && !before.endsWith(
            "\n\n"
        )
    ) {

        if (
            before.endsWith(
                "\n"
            )
        ) {

            prefix =
                "\n";

        }
        else {

            prefix =
                "\n\n";
        }
    }


    let suffix =
        "";


    if (
        after.length === 0
    ) {

        /*
         * Cursor ở cuối nội dung.
         *
         * Sau marker tạo sẵn paragraph mới
         * để tiếp tục viết.
         */
        suffix =
            "\n\n";

    }
    else if (
        after.startsWith(
            "\n\n"
        )
    ) {

        suffix =
            "";

    }
    else if (
        after.startsWith(
            "\n"
        )
    ) {

        suffix =
            "\n";

    }
    else {

        suffix =
            "\n\n";
    }


    const insertion =
        prefix
        + marker
        + suffix;


    replaceRange(
        editor,
        cursor,
        cursor,
        insertion
    );


    /*
     * Đưa caret xuống sau marker.
     */
    const newCursor =
        cursor
        + insertion.length;


    editor.focus();


    editor.setSelectionRange(
        newCursor,
        newCursor
    );
}

/* =========================================================
   COMMON TEXT REPLACEMENT
   ========================================================= */

function replaceRange(
    editor,
    start,
    end,
    replacement
) {
    const before =
        editor.value.substring(
            0,
            start
        );

    const after =
        editor.value.substring(
            end
        );

    editor.value =
        before
        + replacement
        + after;

    const cursorPosition =
        start + replacement.length;

    editor.setSelectionRange(
        cursorPosition,
        cursorPosition
    );

    editor.dispatchEvent(
        new Event(
            "input",
            {
                bubbles: true
            }
        )
    );
}


/* =========================================================
   UNDO / REDO HISTORY
   ========================================================= */

function createHistoryManager(
    editor
) {
    const history = [];

    let currentIndex = -1;

    let timer = null;

    let restoring = false;


    function createSnapshot() {
        return {
            value:
                editor.value,

            selectionStart:
                editor.selectionStart,

            selectionEnd:
                editor.selectionEnd
        };
    }


    function snapshotsEqual(
        first,
        second
    ) {
        if (!first || !second) {
            return false;
        }

        return first.value
            === second.value;
    }


    function record() {
        if (restoring) {
            return;
        }

        if (timer) {
            clearTimeout(timer);
            timer = null;
        }

        const snapshot =
            createSnapshot();

        const currentSnapshot =
            history[currentIndex];

        if (
            snapshotsEqual(
                snapshot,
                currentSnapshot
            )
        ) {
            return;
        }

        /*
         * Nếu đã Undo rồi sau đó gõ/chỉnh sửa,
         * bỏ phần Redo phía sau.
         */
        if (
            currentIndex
            < history.length - 1
        ) {
            history.splice(
                currentIndex + 1
            );
        }

        history.push(
            snapshot
        );

        currentIndex =
            history.length - 1;
    }


    function schedule() {
        if (restoring) {
            return;
        }

        if (timer) {
            clearTimeout(timer);
        }

        timer =
            setTimeout(
                function() {
                    record();
                },
                350
            );
    }


    function flush() {
        if (timer) {
            clearTimeout(timer);
            timer = null;
        }

        record();
    }


    function restore(
        snapshot
    ) {
        if (!snapshot) {
            return;
        }

        restoring = true;

        editor.value =
            snapshot.value;

        editor.focus();

        editor.setSelectionRange(
            snapshot.selectionStart,
            snapshot.selectionEnd
        );

        editor.dispatchEvent(
            new Event(
                "input",
                {
                    bubbles: true
                }
            )
        );

        restoring = false;
    }


    function undo() {
        flush();

        if (currentIndex <= 0) {
            return;
        }

        currentIndex--;

        restore(
            history[currentIndex]
        );
    }


    function redo() {
        if (timer) {
            clearTimeout(timer);
            timer = null;
        }

        if (
            currentIndex
            >= history.length - 1
        ) {
            return;
        }

        currentIndex++;

        restore(
            history[currentIndex]
        );
    }


    /*
     * Snapshot ban đầu.
     */
    record();


    return {
        record,
        schedule,
        flush,
        undo,
        redo
    };
}

/* =========================================================
   WIKI LOCAL AUTO-SAVE + RECOVERY
   ========================================================= */

function setupWikiLocalAutosave(
    editor
) {
    const form =
        document.getElementById(
            "wikiArticleForm"
        );


    const status =
        document.getElementById(
            "wikiAutosaveStatus"
        );


    if (!form) {
        return;
    }


    /* =====================================================
       RECOVERY ELEMENTS
       ===================================================== */

    const recoveryPanel =
        document.getElementById(
            "wikiAutosaveRecovery"
        );


    const recoveryMessage =
        document.getElementById(
            "wikiAutosaveRecoveryMessage"
        );


    const restoreButton =
        document.getElementById(
            "wikiAutosaveRestore"
        );


    const discardButton =
        document.getElementById(
            "wikiAutosaveDiscard"
        );


    /* =====================================================
       PAGE MODE
       ===================================================== */

    const mode =
        form.dataset
            .wikiAutosaveMode;


    const articleId =
        form.dataset
            .wikiArticleId
        || null;


    let storageKey;


    if (
        mode === "create"
    ) {

        storageKey =
            "kiemlai:wiki:autosave:create";

    }
    else if (
        mode === "edit"
        && articleId
    ) {

        storageKey =
            "kiemlai:wiki:autosave:edit:"
            + articleId;

    }
    else {

        console.warn(
            "Không thể xác định Wiki Auto-save key."
        );

        return;
    }


    /* =====================================================
       FORM FIELDS
       ===================================================== */

    const title =
        document.getElementById(
            "wikiTitle"
        );


    const articleType =
        document.getElementById(
            "wikiArticleType"
        );


    const summary =
        document.getElementById(
            "wikiSummary"
        );


    const editSummary =
        document.getElementById(
            "wikiEditSummary"
        );


    const contentTemplateButton =
        document.getElementById(
            "applyContentTemplate"
        );


    const previewPanel =
        document.getElementById(
            "wikiPreviewPanel"
        );


    const previewTab =
        document.getElementById(
            "wikiPreviewTab"
        );


    const watchedFields = [
        title,
        articleType,
        summary,
        editor,
        editSummary
    ].filter(
        function(field) {
            return Boolean(field);
        }
    );

    /* =====================================================
       RECOVERY LOCK
       ===================================================== */

	   const recoveryLockElements =
	       Array.from(
	           document.querySelectorAll(
	               [
	                   "[data-markdown-action]",
	                   ".wiki-markdown-editor-tab",
	                   "#wikiImageButton",
	                   "#applyContentTemplate"
	               ].join(",")
	           )
	       );


    const originalDisabledState =
        new Map();


    watchedFields
        .concat(
            recoveryLockElements
        )
        .forEach(
            function(element) {

                if (!element) {
                    return;
                }


                originalDisabledState.set(
                    element,
                    Boolean(
                        element.disabled
                    )
                );
            }
        );


    function setRecoveryLock(
        locked
    ) {

        watchedFields
            .concat(
                recoveryLockElements
            )
            .forEach(
                function(element) {

                    if (!element) {
                        return;
                    }


                    if (locked) {

                        element.disabled =
                            true;

                        return;
                    }


                    element.disabled =
                        originalDisabledState.get(
                            element
                        ) || false;
                }
            );
    }


    /* =====================================================
       STATE
       ===================================================== */

    let saveTimer =
        null;


    let lastSavedData =
        null;


    let pendingRecoveryDraft =
        null;


    let recoveryPending =
        false;


    /*
     * Dữ liệu server tại thời điểm
     * trang editor vừa được mở.
     *
     * Đây là mốc để xác định:
     *
     * current form === server baseline
     *     → không có thay đổi chưa lưu
     *
     * current form !== server baseline
     *     → dirty
     */
    let serverBaselineData =
        null;


    /*
     * Đang submit form thật lên backend.
     *
     * Khi true thì beforeunload
     * không được hiện cảnh báo.
     */
    let submitting =
        false;


    const AUTO_SAVE_DELAY =
        800;


    /* =====================================================
       CREATE SNAPSHOT
       ===================================================== */

    function createLocalDraft() {

        return {

            schemaVersion:
                1,

            mode:
                mode,

            articleId:
                articleId,

            pagePath:
                window.location.pathname,

            savedAt:
                new Date()
                    .toISOString(),

            data: {

                title:
                    title
                        ? title.value
                        : "",

                articleType:
                    articleType
                        ? articleType.value
                        : "",

                summary:
                    summary
                        ? summary.value
                        : "",

                content:
                    editor.value,

                editSummary:
                    editSummary
                        ? editSummary.value
                        : ""
            }
        };
    }


    function serializeDraftData(
        draft
    ) {

        return JSON.stringify(
            draft.data
        );
    }

    /* =====================================================
       DIRTY STATE
       ===================================================== */

    function hasUnsavedServerChanges() {

        if (
            serverBaselineData === null
        ) {
            return false;
        }


        const currentData =
            serializeDraftData(
                createLocalDraft()
            );


        return currentData
            !== serverBaselineData;
    }


    /* =====================================================
       SAVE
       ===================================================== */

    function saveNow() {

        if (saveTimer) {

            clearTimeout(
                saveTimer
            );

            saveTimer =
                null;
        }


        /*
         * Nếu đang có một recovery chưa được quyết định,
         * tuyệt đối không ghi đè local draft cũ.
         */
        if (
            recoveryPending
        ) {

            return;
        }


        const draft =
            createLocalDraft();


        const serializedData =
            serializeDraftData(
                draft
            );

        /*
         * Nếu người dùng Undo / chỉnh lại
         * đúng về dữ liệu server ban đầu,
         * local draft không còn cần thiết.
         */
        if (
            serverBaselineData !== null
            && serializedData
            === serverBaselineData
        ) {

            try {

                localStorage.removeItem(
                    storageKey
                );

            }
            catch (error) {

                console.error(
                    "Không thể xóa Wiki local draft:",
                    error
                );
            }


            lastSavedData =
                serializedData;


            if (status) {

                status.textContent =
                    "Không có thay đổi chưa lưu lên hệ thống.";
            }


            return;
        }


        if (
            serializedData
            === lastSavedData
        ) {

            return;
        }


        try {

            localStorage.setItem(
                storageKey,
                JSON.stringify(
                    draft
                )
            );


            lastSavedData =
                serializedData;


            showSavedStatus(
                draft.savedAt
            );

        }
        catch (error) {

            console.error(
                "Wiki local auto-save failed:",
                error
            );


            if (status) {

                status.textContent =
                    "Không thể tự động lưu cục bộ.";
            }
        }
    }


    /* =====================================================
       SCHEDULE SAVE
       ===================================================== */

    function scheduleSave() {

        /*
         * Bảo vệ recovery draft.
         *
         * Người dùng phải quyết định:
         *
         * - Khôi phục
         * - Bỏ
         *
         * trước khi Auto-save được tiếp tục.
         */
        if (
            recoveryPending
        ) {

            if (status) {

                status.textContent =
                    "Có bản tự lưu chưa được xử lý. "
                    + "Hãy khôi phục hoặc bỏ bản tự lưu.";
            }

            return;
        }


        if (saveTimer) {

            clearTimeout(
                saveTimer
            );
        }


        if (status) {

            status.textContent =
                "Đang chờ tự động lưu...";
        }


        saveTimer =
            setTimeout(
                saveNow,
                AUTO_SAVE_DELAY
            );
    }


    /* =====================================================
       STATUS
       ===================================================== */

    function formatSavedTime(
        savedAt
    ) {

        const date =
            new Date(
                savedAt
            );


        if (
            Number.isNaN(
                date.getTime()
            )
        ) {

            return null;
        }


        return date.toLocaleString(
            "vi-VN",
            {
                hour:
                    "2-digit",

                minute:
                    "2-digit",

                second:
                    "2-digit",

                day:
                    "2-digit",

                month:
                    "2-digit",

                year:
                    "numeric"
            }
        );
    }


    function showSavedStatus(
        savedAt
    ) {

        if (!status) {
            return;
        }


        const time =
            formatSavedTime(
                savedAt
            );


        if (!time) {

            status.textContent =
                hasUnsavedServerChanges()
                    ? "Đã tự lưu cục bộ. "
                    + "Thay đổi chưa được lưu lên hệ thống."
                    : "Nội dung hiện tại đã đồng bộ với hệ thống.";

            return;
        }


        if (
            hasUnsavedServerChanges()
        ) {

            status.textContent =
                "Đã tự lưu cục bộ lúc "
                + time
                + ". Thay đổi chưa được lưu lên hệ thống.";

        }
        else {

            status.textContent =
                "Nội dung hiện tại đã đồng bộ với hệ thống.";
        }
    }


    /* =====================================================
       VALIDATE LOCAL DRAFT
       ===================================================== */

    function isCompatibleDraft(
        draft
    ) {

        if (
            !draft
            || typeof draft
            !== "object"
        ) {

            return false;
        }


        if (
            draft.schemaVersion
            !== 1
        ) {

            return false;
        }


        if (
            draft.mode
            !== mode
        ) {

            return false;
        }


        const storedArticleId =
            draft.articleId
            || null;


        if (
            storedArticleId
            !== articleId
        ) {

            return false;
        }


        if (
            !draft.data
            || typeof draft.data
            !== "object"
        ) {

            return false;
        }


        const fields = [
            "title",
            "articleType",
            "summary",
            "content",
            "editSummary"
        ];


        return fields.every(
            function(fieldName) {

                return typeof draft.data[
                    fieldName
                ] === "string";
            }
        );
    }


    /* =====================================================
       LOAD RECOVERY
       ===================================================== */

    function initializeRecovery() {

        /*
         * Server-rendered form chính là baseline
         * của lần mở trang hiện tại.
         */
        const currentDraft =
            createLocalDraft();


        const currentData =
            serializeDraftData(
                currentDraft
            );

        /*
         * Đây chính là dữ liệu server
         * vừa render xuống editor.
         */
        serverBaselineData =
            currentData;


        lastSavedData =
            currentData;


        let rawDraft;


        try {

            rawDraft =
                localStorage.getItem(
                    storageKey
                );

        }
        catch (error) {

            console.error(
                "Không thể đọc Wiki local draft:",
                error
            );

            return;
        }


        if (!rawDraft) {

            return;
        }


        let localDraft;


        try {

            localDraft =
                JSON.parse(
                    rawDraft
                );

        }
        catch (error) {

            /*
             * Dữ liệu hỏng thì bỏ.
             *
             * Không để JSON lỗi làm editor chết.
             */
            console.warn(
                "Wiki local draft không hợp lệ. "
                + "Đang xóa bản cục bộ.",
                error
            );


            localStorage.removeItem(
                storageKey
            );

            return;
        }


        if (
            !isCompatibleDraft(
                localDraft
            )
        ) {

            console.warn(
                "Wiki local draft không tương thích. "
                + "Đang xóa bản cục bộ."
            );


            localStorage.removeItem(
                storageKey
            );

            return;
        }


        const localData =
            serializeDraftData(
                localDraft
            );


        /*
         * Local draft giống hệt dữ liệu server.
         *
         * Không cần recovery nữa.
         */
        if (
            localData
            === currentData
        ) {

            localStorage.removeItem(
                storageKey
            );


            if (status) {

                status.textContent =
                    "Nội dung hiện tại đã đồng bộ.";
            }


            return;
        }


        /*
         * Có khác biệt thật sự.
         */
        pendingRecoveryDraft =
            localDraft;


        recoveryPending =
            true;


        showRecoveryPanel(
            localDraft
        );
    }


    /* =====================================================
       SHOW RECOVERY
       ===================================================== */

    function showRecoveryPanel(
        draft
    ) {

        setRecoveryLock(
            true
        );


        if (
            recoveryPanel
        ) {

            recoveryPanel.hidden =
                false;
        }


        document.body.classList.add(
            "wiki-autosave-recovery-open"
        );


        const savedTime =
            formatSavedTime(
                draft.savedAt
            );


        if (
            recoveryMessage
        ) {

            if (savedTime) {

                recoveryMessage.textContent =
                    "Có một bản chỉnh sửa cục bộ "
                    + "được lưu lúc "
                    + savedTime
                    + " nhưng chưa được lưu lên hệ thống.";

            }
            else {

                recoveryMessage.textContent =
                    "Có một bản chỉnh sửa cục bộ "
                    + "chưa được lưu lên hệ thống.";
            }
        }


        if (status) {

            status.textContent =
                "Có bản tự lưu cần được xử lý.";
        }


        requestAnimationFrame(
            function() {

                restoreButton
                    ?.focus();
            }
        );
    }


    function hideRecoveryPanel() {

        if (
            recoveryPanel
        ) {

            recoveryPanel.hidden =
                true;
        }


        document.body.classList.remove(
            "wiki-autosave-recovery-open"
        );
    }


    /* =====================================================
       RESTORE FIELD
       ===================================================== */

    function restoreField(
        field,
        value
    ) {

        if (!field) {
            return;
        }


        /*
         * Published article:
         *
         * title readonly
         * articleType disabled
         *
         * Không được recovery ghi đè những field
         * mà giao diện không cho phép sửa.
         */
        if (
            field.readOnly
            || field.disabled
        ) {

            return;
        }


        field.value =
            value;


        field.dispatchEvent(
            new Event(
                "input",
                {
                    bubbles:
                        true
                }
            )
        );


        if (
            field.tagName
            === "SELECT"
        ) {

            field.dispatchEvent(
                new Event(
                    "change",
                    {
                        bubbles:
                            true
                    }
                )
            );
        }
    }


    /* =====================================================
       RESTORE
       ===================================================== */

    function restoreLocalDraft() {

        if (
            !pendingRecoveryDraft
        ) {

            return;
        }


        const draft =
            pendingRecoveryDraft;


        /*
         * Tắt khóa recovery trước khi dispatch input.
         *
         * saveNow() cuối function sẽ flush
         * tất cả thay đổi một lần.
         */
        recoveryPending =
            false;

        setRecoveryLock(
            false
        );

        restoreField(
            title,
            draft.data.title
        );


        restoreField(
            articleType,
            draft.data.articleType
        );


        restoreField(
            summary,
            draft.data.summary
        );


        restoreField(
            editor,
            draft.data.content
        );


        restoreField(
            editSummary,
            draft.data.editSummary
        );


        pendingRecoveryDraft =
            null;


        hideRecoveryPanel();


		/*
		 * Bản vừa khôi phục đã tồn tại
		 * trong localStorage từ trước.
		 *
		 * Giữ nguyên savedAt của bản tự lưu gốc.
		 */
		lastSavedData =
		    serializeDraftData(
		        draft
		    );


        if (status) {

            status.textContent =
                "Đã khôi phục bản tự lưu. "
                + "Thay đổi chưa được lưu lên hệ thống.";
        }


        /*
         * Nếu người dùng đang ở Preview,
         * render lại preview theo nội dung
         * vừa recovery.
         */
        if (
            previewPanel
            && !previewPanel.hidden
            && previewTab
        ) {

            previewTab.click();

        }
        else {

            editor.focus();
        }
    }


    /* =====================================================
       DISCARD RECOVERY
       ===================================================== */

    function discardLocalDraft() {

        try {

            localStorage.removeItem(
                storageKey
            );

        }
        catch (error) {

            console.error(
                "Không thể xóa Wiki local draft:",
                error
            );

            return;
        }


        pendingRecoveryDraft =
            null;


        recoveryPending =
            false;

        setRecoveryLock(
            false
        );



        hideRecoveryPanel();
		
		editor.focus();


        /*
         * Baseline bây giờ chính là dữ liệu
         * server đang hiển thị.
         */
        lastSavedData =
            serializeDraftData(
                createLocalDraft()
            );


        if (status) {

            status.textContent =
                "Đã bỏ bản tự lưu. "
                + "Đang sử dụng dữ liệu hiện tại.";
        }
    }


    /* =====================================================
       EVENT LISTENERS
       ===================================================== */

    watchedFields.forEach(
        function(field) {

            field.addEventListener(
                "input",
                scheduleSave
            );


            field.addEventListener(
                "change",
                scheduleSave
            );
        }
    );


    contentTemplateButton
        ?.addEventListener(
            "click",
            function() {

                setTimeout(
                    scheduleSave,
                    0
                );
            }
        );


    restoreButton
        ?.addEventListener(
            "click",
            restoreLocalDraft
        );


    discardButton
        ?.addEventListener(
            "click",
            discardLocalDraft
        );


    document.addEventListener(
        "visibilitychange",
        function() {

            if (
                document.visibilityState
                === "hidden"
                && saveTimer
                && !recoveryPending
            ) {

                saveNow();
            }
        }
    );

    /* =====================================================
       UNSAVED CHANGES WARNING
       ===================================================== */

    window.addEventListener(
        "beforeunload",
        function(event) {

            /*
             * Người dùng đang chủ động gửi form
             * Save Draft / Publish / Update.
             *
             * Không cảnh báo trong trường hợp này.
             */
            if (
                submitting
            ) {
                return;
            }


            /*
             * recoveryPending:
             *
             * Có một local draft chưa được xử lý.
             *
             * hasUnsavedServerChanges():
             *
             * Form hiện tại khác dữ liệu server.
             */
            const shouldWarn =
                recoveryPending
                || hasUnsavedServerChanges();


            if (
                !shouldWarn
            ) {
                return;
            }


            event.preventDefault();


            /*
             * Các browser hiện đại tự hiển thị
             * nội dung cảnh báo chuẩn của browser.
             *
             * Không thể tùy chỉnh message.
             */
            event.returnValue =
                "";
        }
    );

    /* =====================================================
       REAL SERVER SUBMIT
       ===================================================== */

    form.addEventListener(
        "submit",
        function() {

            /*
             * Flush thay đổi cuối cùng vào localStorage
             * trước khi gửi request.
             *
             * Nếu backend gặp lỗi, local draft
             * vẫn còn để Recovery.
             */
            if (
                !recoveryPending
            ) {

                saveNow();
            }


            /*
             * Sau khi native validation đã pass
             * và submit event xảy ra,
             * user đang chủ động lưu.
             *
             * Không hiện beforeunload warning
             * khi browser chuyển sang response mới.
             */
            submitting =
                true;


            if (status) {

                status.textContent =
                    "Đang lưu lên hệ thống...";
            }
        }
    );

    document.addEventListener(
        "keydown",
        function(event) {

            if (
                !recoveryPending
            ) {
                return;
            }


            if (
                event.key === "Escape"
            ) {

                event.preventDefault();

                return;
            }


            /*
             * Focus trap đơn giản giữa
             * hai nút của Recovery modal.
             */
            if (
                event.key === "Tab"
                && restoreButton
                && discardButton
            ) {

                const focusable = [
                    discardButton,
                    restoreButton
                ];


                const currentIndex =
                    focusable.indexOf(
                        document.activeElement
                    );


                if (
                    event.shiftKey
                ) {

                    if (
                        currentIndex <= 0
                    ) {

                        event.preventDefault();

                        restoreButton.focus();
                    }

                }
                else {

                    if (
                        currentIndex ===
                        focusable.length - 1
                    ) {

                        event.preventDefault();

                        discardButton.focus();
                    }
                }
            }
        }
    );


    /* =====================================================
       INITIALIZE
       ===================================================== */

    initializeRecovery();
}

/* =========================================================
   WIKI IMAGE UPLOAD
   ========================================================= */

function setupImageUpload(
    editor,
    history
) {
    const imageButton =
        document.getElementById(
            "wikiImageButton"
        );

    const fileInput =
        document.getElementById(
            "wikiImageFileInput"
        );

    const csrf =
        document.getElementById(
            "wikiCsrf"
        );


    /* =====================================================
       MODAL ELEMENTS
       ===================================================== */

    const modal =
        document.getElementById(
            "wikiImageEditorModal"
        );

    const modalTitle =
        document.getElementById(
            "wikiImageEditorTitle"
        );

    const modalPreview =
        document.getElementById(
            "wikiImageEditorPreview"
        );

    const altInput =
        document.getElementById(
            "wikiImageAlt"
        );

    const captionInput =
        document.getElementById(
            "wikiImageCaption"
        );

    const confirmButton =
        document.getElementById(
            "wikiImageEditorConfirm"
        );

    const cancelButton =
        document.getElementById(
            "wikiImageEditorCancel"
        );

    const closeButton =
        document.getElementById(
            "wikiImageEditorClose"
        );

    const modalError =
        document.getElementById(
            "wikiImageEditorError"
        );

    const backdrop =
        modal
            ?.querySelector(
                "[data-wiki-image-close]"
            );


    const previewBody =
        document.getElementById(
            "wikiMarkdownPreview"
        );

    const previewTab =
        document.getElementById(
            "wikiPreviewTab"
        );

    const previewPanel =
        document.getElementById(
            "wikiPreviewPanel"
        );


    if (
        !imageButton
        || !fileInput
        || !modal
        || !modalPreview
        || !altInput
        || !captionInput
        || !confirmButton
    ) {
        return;
    }


    /* =====================================================
       STATE
       ===================================================== */

    let insertionStart = 0;

    let insertionEnd = 0;

    let selectedText = "";

    let pendingFile = null;

    let previewObjectUrl = null;

    let editingBlock = null;

    let mode = "insert";


    /* =====================================================
       OPEN FILE PICKER
       ===================================================== */

    imageButton.addEventListener(
        "click",
        function() {

            insertionStart =
                editor.selectionStart;

            insertionEnd =
                editor.selectionEnd;

            selectedText =
                editor.value.substring(
                    insertionStart,
                    insertionEnd
                );

            fileInput.value = "";

            fileInput.click();
        }
    );


    /* =====================================================
       FILE SELECTED
       ===================================================== */

    fileInput.addEventListener(
        "change",
        function() {

            const file =
                fileInput.files?.[0];

            if (!file) {
                return;
            }


            pendingFile =
                file;

            editingBlock =
                null;

            mode =
                "insert";


            const defaultAlt =
                selectedText.trim()
                || removeFileExtension(
                    file.name
                );


            resetImageModal();


            modalTitle.textContent =
                "Chèn ảnh";

            confirmButton.textContent =
                "Chèn ảnh";

            altInput.value =
                defaultAlt;

            captionInput.value =
                "";


            setRadioValue(
                "wikiImageSize",
                "medium"
            );

            setRadioValue(
                "wikiImageLayout",
                "block-center"
            );


            previewObjectUrl =
                URL.createObjectURL(
                    file
                );

            modalPreview.src =
                previewObjectUrl;


            openImageModal();
        }
    );


    /* =====================================================
       EDIT FROM PREVIEW
       ===================================================== */

    if (previewBody) {

        previewBody.addEventListener(
            "click",
            function(event) {

                const image =
                    event.target.closest(
                        "img.wiki-content-image"
                    );

                if (!image) {
                    return;
                }


                const imageUrl =
                    image.getAttribute(
                        "src"
                    );


                const block =
                    findWikiImageBlockByUrl(
                        editor.value,
                        imageUrl
                    );


                if (!block) {

                    console.warn(
                        "Không tìm thấy Markdown của ảnh:",
                        imageUrl
                    );

                    return;
                }


                mode =
                    "edit";

                pendingFile =
                    null;

                editingBlock =
                    block;


                resetImageModal();


                modalTitle.textContent =
                    "Chỉnh ảnh";

                confirmButton.textContent =
                    "Lưu thay đổi";

                modalPreview.src =
                    block.url;

                altInput.value =
                    block.alt;

                captionInput.value =
                    block.caption;


                setRadioValue(
                    "wikiImageSize",
                    block.size
                );

                setRadioValue(
                    "wikiImageLayout",
                    block.layout
                );


                openImageModal();
            }
        );
    }


    /* =====================================================
       CONFIRM
       ===================================================== */

    confirmButton.addEventListener(
        "click",
        async function() {

            hideImageModalError();


            const altText =
                altInput.value.trim();


            if (!altText) {

                showImageModalError(
                    "Vui lòng nhập mô tả ảnh."
                );

                altInput.focus();

                return;
            }


            const caption =
                captionInput.value.trim();

            const size =
                getRadioValue(
                    "wikiImageSize",
                    "medium"
                );

            let layout =
                getRadioValue(
                    "wikiImageLayout",
                    "block-center"
                );


            let normalizedSize =
                size;


            /*
             * Full width + wrap không có nghĩa
             * vì sẽ không còn chỗ cho chữ.
             */
            if (
                normalizedSize === "full"
                && (
                    layout === "wrap-left"
                    || layout === "wrap-right"
                )
            ) {
                normalizedSize =
                    "large";
            }


            history.flush();


            confirmButton.disabled =
                true;


            const oldLabel =
                confirmButton.textContent;


            try {

                let imageUrl;


                /*
                 * ============================
                 * INSERT NEW IMAGE
                 * ============================
                 */
                if (mode === "insert") {

                    if (!pendingFile) {

                        throw new Error(
                            "Không tìm thấy file ảnh."
                        );
                    }


                    confirmButton.textContent =
                        "Đang tải ảnh...";


                    imageUrl =
                        await uploadWikiImage(
                            pendingFile,
                            csrf
                        );
                }


                /*
                 * ============================
                 * EDIT EXISTING IMAGE
                 * ============================
                 */
                else {

                    if (!editingBlock) {

                        throw new Error(
                            "Không tìm thấy ảnh cần chỉnh."
                        );
                    }


                    imageUrl =
                        editingBlock.url;
                }


                const markdown =
                    buildWikiImageMarkdown(
                        altText,
                        imageUrl,
                        caption,
                        normalizedSize,
                        layout
                    );

                /*
                 * INSERT
                 */
                if (mode === "insert") {

                    insertImageMarkdown(
                        editor,
                        insertionStart,
                        insertionEnd,
                        markdown
                    );
                }


                /*
                 * EDIT
                 */
                else {

                    replaceWikiImageBlock(
                        editor,
                        editingBlock.start,
                        editingBlock.end,
                        markdown
                    );
                }


                history.record();


                closeImageModal();


                /*
                 * Nếu đang đứng ở Preview,
                 * render lại ngay để thấy thay đổi.
                 */
                if (
                    previewPanel
                    && !previewPanel.hidden
                    && previewTab
                ) {
                    previewTab.click();
                }
                else {
                    editor.focus();
                }

            }
            catch (error) {

                console.error(
                    "Wiki image editor failed:",
                    error
                );


                showImageModalError(
                    error.message
                    || "Không thể xử lý ảnh."
                );

            }
            finally {

                confirmButton.disabled =
                    false;

                confirmButton.textContent =
                    oldLabel;
            }
        }
    );


    /* =====================================================
       CLOSE
       ===================================================== */

    cancelButton?.addEventListener(
        "click",
        closeImageModal
    );


    closeButton?.addEventListener(
        "click",
        closeImageModal
    );


    backdrop?.addEventListener(
        "click",
        closeImageModal
    );


    document.addEventListener(
        "keydown",
        function(event) {

            if (
                event.key === "Escape"
                && !modal.hidden
            ) {
                closeImageModal();
            }
        }
    );


    /* =====================================================
       MODAL HELPERS
       ===================================================== */

    function openImageModal() {

        modal.hidden =
            false;

        document.body.classList.add(
            "wiki-modal-open"
        );

        altInput.focus();
    }


    function closeImageModal() {

        modal.hidden =
            true;

        document.body.classList.remove(
            "wiki-modal-open"
        );


        if (previewObjectUrl) {

            URL.revokeObjectURL(
                previewObjectUrl
            );

            previewObjectUrl =
                null;
        }


        pendingFile =
            null;

        editingBlock =
            null;

        fileInput.value =
            "";

        hideImageModalError();
    }


    function resetImageModal() {

        hideImageModalError();

        altInput.value =
            "";

        captionInput.value =
            "";
    }


    function showImageModalError(
        message
    ) {
        if (!modalError) {
            return;
        }


        modalError.textContent =
            message;

        modalError.hidden =
            false;
    }


    function hideImageModalError() {

        if (modalError) {
            modalError.hidden = true;
        }
    }
}


/* =========================================================
   IMAGE UPLOAD REQUEST
   ========================================================= */

async function uploadWikiImage(
    file,
    csrf
) {
    const formData =
        new FormData();


    formData.append(
        "file",
        file
    );


    const headers = {};


    if (
        csrf
        && csrf.dataset.token
        && csrf.dataset.header
    ) {
        headers[
            csrf.dataset.header
        ] =
            csrf.dataset.token;
    }


    const response =
        await fetch(
            "/admin/wiki/images",
            {
                method: "POST",
                headers: headers,
                body: formData
            }
        );


    const responseBody =
        await response.json();


    if (!response.ok) {

        throw new Error(
            responseBody.message
            || "Không thể upload ảnh."
        );
    }


    if (
        !responseBody.url
        || !responseBody.url.trim()
    ) {
        throw new Error(
            "Server không trả về URL ảnh."
        );
    }


    return responseBody.url;
}


/* =========================================================
   BUILD IMAGE MARKDOWN
   ========================================================= */

function buildWikiImageMarkdown(
    alt,
    url,
    caption,
    size,
    layout
) {
    const safeAlt =
        escapeMarkdownAltText(
            alt
        );


    let markdown =
        "!["
        + safeAlt
        + "]("
        + url
        + ' "wiki:size='
        + size
        + ";layout="
        + layout
        + '")';


    if (caption) {

        markdown +=
            "\n\n*"
            + escapeMarkdownCaption(
                caption
            )
            + "*";
    }


    return markdown;
}


/* =========================================================
   FIND IMAGE MARKDOWN FOR EDITING
   ========================================================= */

function findWikiImageBlockByUrl(
    markdown,
    url
) {
    /*
     * Hỗ trợ cả format mới:
     *
     * wiki:size=medium;layout=wrap-right
     *
     * và format cũ:
     *
     * wiki:size=medium;align=right
     */
    const pattern =
        /!\[((?:\\.|[^\]])*)\]\(([^\s)]+)(?:\s+"wiki:([^"]*)")?\)(?:\n\n\*((?:\\.|[^\n])*)\*)?/g;


    let match;


    while (
        (
            match =
            pattern.exec(
                markdown
            )
        ) !== null
    ) {

        if (
            match[2] !== url
        ) {
            continue;
        }


        const metadata =
            parseWikiImageMetadata(
                match[3] || ""
            );


        return {

            start:
                match.index,

            end:
                match.index
                + match[0].length,

            alt:
                unescapeMarkdownAltText(
                    match[1] || ""
                ),

            url:
                match[2],

            size:
                metadata.size,

            layout:
                metadata.layout,

            caption:
                unescapeMarkdownCaption(
                    match[4] || ""
                )
        };
    }


    return null;
}

function parseWikiImageMetadata(
    metadata
) {
    let size =
        "medium";

    let layout =
        "block-center";

    let legacyAlign =
        null;

    let hasLayout =
        false;


    metadata
        .split(";")
        .forEach(
            function(part) {

                const separator =
                    part.indexOf("=");


                if (
                    separator === -1
                ) {
                    return;
                }


                const key =
                    part
                        .substring(
                            0,
                            separator
                        )
                        .trim();


                const value =
                    part
                        .substring(
                            separator + 1
                        )
                        .trim();


                if (
                    key === "size"
                    && [
                        "small",
                        "medium",
                        "large",
                        "full"
                    ].includes(
                        value
                    )
                ) {

                    size =
                        value;
                }


                if (
                    key === "layout"
                    && [
                        "block-left",
                        "block-center",
                        "block-right",
                        "wrap-left",
                        "wrap-right"
                    ].includes(
                        value
                    )
                ) {

                    layout =
                        value;

                    hasLayout =
                        true;
                }


                if (
                    key === "align"
                    && [
                        "left",
                        "center",
                        "right"
                    ].includes(
                        value
                    )
                ) {

                    legacyAlign =
                        value;
                }
            }
        );


    /*
     * Markdown ảnh cũ.
     */
    if (
        !hasLayout
        && legacyAlign
    ) {

        switch (
        legacyAlign
        ) {

            case "left":
                layout =
                    "block-left";
                break;

            case "right":
                layout =
                    "block-right";
                break;

            default:
                layout =
                    "block-center";
        }
    }


    if (
        size === "full"
        && (
            layout === "wrap-left"
            || layout === "wrap-right"
        )
    ) {

        size =
            "large";
    }


    return {
        size,
        layout
    };
}


/* =========================================================
   RADIO HELPERS
   ========================================================= */

function getRadioValue(
    name,
    fallback
) {
    const checked =
        document.querySelector(
            'input[name="'
            + name
            + '"]:checked'
        );


    return checked
        ? checked.value
        : fallback;
}


function setRadioValue(
    name,
    value
) {
    const radio =
        document.querySelector(
            'input[name="'
            + name
            + '"][value="'
            + value
            + '"]'
        );


    if (radio) {
        radio.checked = true;
    }
}


/* =========================================================
   CAPTION ESCAPE
   ========================================================= */

function escapeMarkdownCaption(
    text
) {
    return text
        .replace(
            /\\/g,
            "\\\\"
        )
        .replace(
            /\*/g,
            "\\*"
        );
}


function unescapeMarkdownCaption(
    text
) {
    return text
        .replace(
            /\\\*/g,
            "*"
        )
        .replace(
            /\\\\/g,
            "\\"
        );
}


function unescapeMarkdownAltText(
    text
) {
    return text
        .replace(
            /\\\]/g,
            "]"
        )
        .replace(
            /\\\\/g,
            "\\"
        );
}

/* =========================================================
   NORMALIZE WIKI IMAGE BLOCK SPACING
   ========================================================= */

function replaceWikiImageBlock(
    editor,
    start,
    end,
    markdown
) {
    const value =
        editor.value;


    const before =
        value.substring(
            0,
            start
        );


    const after =
        value.substring(
            end
        );


    let prefix =
        "";


    let suffix =
        "";


    /*
     * Ảnh Wiki phải là một block riêng.
     *
     * Trước ảnh luôn phải có paragraph break.
     */
    if (
        before.length > 0
        && !before.endsWith(
            "\n\n"
        )
    ) {

        prefix =
            before.endsWith("\n")
                ? "\n"
                : "\n\n";
    }


    /*
     * Sau ảnh cũng phải có paragraph break.
     *
     * Đây chính là phần tránh:
     *
     * ![ảnh](...)
     * text
     *
     * bị CommonMark hiểu thành cùng paragraph.
     */
    if (
        after.length === 0
    ) {

        suffix =
            "\n\n";

    }
    else if (
        !after.startsWith(
            "\n\n"
        )
    ) {

        suffix =
            after.startsWith("\n")
                ? "\n"
                : "\n\n";
    }


    replaceRange(
        editor,
        start,
        end,
        prefix
        + markdown
        + suffix
    );
}


/* =========================================================
   INSERT IMAGE AT SAVED CURSOR
   ========================================================= */

function insertImageMarkdown(
    editor,
    start,
    end,
    markdown
) {
    replaceWikiImageBlock(
        editor,
        start,
        end,
        markdown
    );
}

/* =========================================================
   IMAGE HELPERS
   ========================================================= */

function removeFileExtension(
    filename
) {
    return filename.replace(
        /\.[^/.]+$/,
        ""
    );
}


function escapeMarkdownAltText(
    text
) {
    return text
        .replace(
            /\\/g,
            "\\\\"
        )
        .replace(
            /\]/g,
            "\\]"
        );
}


/* =========================================================
   WRITE / PREVIEW
   ========================================================= */

function setupMarkdownPreview(
    editor
) {
    const writeTab =
        document.getElementById(
            "wikiWriteTab"
        );

    const previewTab =
        document.getElementById(
            "wikiPreviewTab"
        );

    const writePanel =
        document.getElementById(
            "wikiWritePanel"
        );

    const previewPanel =
        document.getElementById(
            "wikiPreviewPanel"
        );

    const previewBody =
        document.getElementById(
            "wikiMarkdownPreview"
        );

    const loadingState =
        document.getElementById(
            "wikiPreviewLoading"
        );

    const emptyState =
        document.getElementById(
            "wikiPreviewEmpty"
        );

    const csrf =
        document.getElementById(
            "wikiCsrf"
        );


    if (
        !writeTab
        || !previewTab
        || !writePanel
        || !previewPanel
        || !previewBody
    ) {
        return;
    }


    let lastPreviewSource = null;


    /* =====================================================
       WRITE TAB
       ===================================================== */

    writeTab.addEventListener(
        "click",
        function() {

            activateTab(
                "write"
            );

            editor.focus();
        }
    );


    /* =====================================================
       PREVIEW TAB
       ===================================================== */

    previewTab.addEventListener(
        "click",
        async function() {

            activateTab(
                "preview"
            );

            await renderPreview();
        }
    );


    /* =====================================================
       TAB SWITCHING
       ===================================================== */

    function activateTab(
        mode
    ) {
        const writeActive =
            mode === "write";


        writeTab.classList.toggle(
            "is-active",
            writeActive
        );

        previewTab.classList.toggle(
            "is-active",
            !writeActive
        );


        writeTab.setAttribute(
            "aria-selected",
            String(writeActive)
        );

        previewTab.setAttribute(
            "aria-selected",
            String(!writeActive)
        );


        writePanel.hidden =
            !writeActive;

        previewPanel.hidden =
            writeActive;
    }


    /* =====================================================
       SERVER-SIDE PREVIEW
       ===================================================== */

    async function renderPreview() {

        const markdown =
            editor.value;


        hideState(
            loadingState
        );

        hideState(
            emptyState
        );


        if (!markdown.trim()) {

            previewBody.innerHTML = "";

            previewBody.dataset.rendered =
                "false";

            lastPreviewSource =
                markdown;

            showState(
                emptyState
            );

            return;
        }


        if (
            lastPreviewSource === markdown
            && previewBody.dataset.rendered === "true"
        ) {
            return;
        }


        previewBody.innerHTML = "";

        showState(
            loadingState
        );

        // phần try/catch giữ nguyên...


        try {

            const headers = {
                "Content-Type":
                    "text/plain;charset=UTF-8",

                "Accept":
                    "text/html"
            };


            /*
             * POST endpoint bị Spring Security CSRF bảo vệ.
             */
            if (
                csrf
                && csrf.dataset.token
                && csrf.dataset.header
            ) {
                headers[
                    csrf.dataset.header
                ] =
                    csrf.dataset.token;
            }


            const response =
                await fetch(
                    "/admin/wiki/articles/content-preview",
                    {
                        method: "POST",
                        headers: headers,
                        body: markdown
                    }
                );


            if (!response.ok) {

                throw new Error(
                    "Không thể tạo bản xem trước."
                );
            }


            const html =
                await response.text();


            /*
             * HTML này không đến trực tiếp từ Markdown.
             *
             * Nó đã đi qua WikiMarkdownRenderer của server,
             * nơi raw HTML được escape và URL được sanitize.
             */
            previewBody.innerHTML =
                html;

            previewBody
                .querySelectorAll(
                    "img.wiki-content-image"
                )
                .forEach(
                    function(image) {

                        image.setAttribute(
                            "title",
                            "Nhấp để chỉnh ảnh"
                        );

                        image.setAttribute(
                            "tabindex",
                            "0"
                        );
                    }
                );


            previewBody.dataset.rendered =
                "true";


            lastPreviewSource =
                markdown;

        }
        catch (error) {

            console.error(
                "Wiki Markdown preview failed:",
                error
            );


            previewBody.dataset.rendered =
                "false";


            previewBody.textContent =
                "Không thể tạo bản xem trước. "
                + "Vui lòng thử lại.";

        }
        finally {

            hideState(
                loadingState
            );
        }
    }


    /* =====================================================
       STATE HELPERS
       ===================================================== */

    function showState(
        element
    ) {
        if (element) {
            element.hidden = false;
        }
    }


    function hideState(
        element
    ) {
        if (element) {
            element.hidden = true;
        }
    }
}

/* =========================================================
   WIKI EDITOR DIAGNOSTICS
   ========================================================= */

function setupWikiEditorDiagnostics(
    editor,
    history
) {
    const container =
        document.getElementById(
            "wikiEditorDiagnostics"
        );

    const list =
        document.getElementById(
            "wikiEditorDiagnosticList"
        );


    if (
        !container
        || !list
    ) {
        return;
    }


    function validate() {

        const diagnostics =
            validateWikiMarkdown(
                editor.value
            );


        list.innerHTML =
            "";


        if (
            diagnostics.length === 0
        ) {

            container.hidden =
                true;

            return;
        }


        diagnostics.forEach(
            function(diagnostic) {

                const item =
                    document.createElement(
                        "li"
                    );


                item.className =
                    "wiki-editor-diagnostic-item";


                /*
                 * ===========================
                 * NÚT TÌM VỊ TRÍ
                 * ===========================
                 */

                const locateButton =
                    document.createElement(
                        "button"
                    );


                locateButton.type =
                    "button";


                locateButton.className =
                    "wiki-editor-diagnostic-link";


                locateButton.textContent =
                    diagnostic.message;


                locateButton.addEventListener(
                    "click",
                    function() {

                        editor.focus();


                        editor.setSelectionRange(
                            diagnostic.start,
                            diagnostic.end
                        );


                        requestAnimationFrame(
                            function() {

                                editor.focus();


                                editor.setSelectionRange(
                                    diagnostic.start,
                                    diagnostic.end
                                );
                            }
                        );
                    }
                );


                item.appendChild(
                    locateButton
                );


                /*
                 * ===========================
                 * NÚT SỬA TỰ ĐỘNG
                 * ===========================
                 */

                if (
                    canAutoFixWikiDiagnostic(
                        diagnostic
                    )
                ) {

                    const fixButton =
                        document.createElement(
                            "button"
                        );


                    fixButton.type =
                        "button";


                    fixButton.className =
                        "wiki-editor-diagnostic-fix";


                    fixButton.textContent =
                        "Sửa tự động";


                    fixButton.addEventListener(
                        "click",
                        function() {

                            /*
                             * Lưu trạng thái trước khi sửa
                             * để Undo được.
                             */
                            history?.flush();


                            const fixed =
                                fixWikiDiagnostic(
                                    editor,
                                    diagnostic
                                );


                            if (
                                fixed
                            ) {

                                history?.record();
                            }
                        }
                    );


                    item.appendChild(
                        fixButton
                    );
                }


                list.appendChild(
                    item
                );
            }
        );


        container.hidden =
            false;
    }


    /*
     * Validation này chỉ đọc text.
     * Không thay đổi textarea
     * nên không gây vòng lặp input.
     */
    editor.addEventListener(
        "input",
        validate
    );


    validate();
}


/* =========================================================
   VALIDATE ALL
   ========================================================= */

function validateWikiMarkdown(
    markdown
) {
    const diagnostics =
        [];


    validateClearWrapMarkers(
        markdown,
        diagnostics
    );


    validateMalformedWikiImages(
        markdown,
        diagnostics
    );


    validateWikiImageMetadata(
        markdown,
        diagnostics
    );


    return diagnostics;
}

/* =========================================================
   AUTO FIX SUPPORT
   ========================================================= */

function canAutoFixWikiDiagnostic(
    diagnostic
) {
    return [
        "CLEAR_WRAP_SPACING",
        "IMAGE_BLOCK_SPACING",
        "IMAGE_SPLIT_SYNTAX"
    ].includes(
        diagnostic.code
    );
}

function fixWikiDiagnostic(
    editor,
    diagnostic
) {
    switch (
    diagnostic.code
    ) {

        case "CLEAR_WRAP_SPACING":

            return fixClearWrapSpacing(
                editor,
                diagnostic
            );


        case "IMAGE_BLOCK_SPACING":

            return fixWikiImageBlockSpacing(
                editor,
                diagnostic
            );


        case "IMAGE_SPLIT_SYNTAX":

            return fixSplitWikiImageSyntax(
                editor,
                diagnostic
            );


        default:

            return false;
    }
}

function fixClearWrapSpacing(
    editor,
    diagnostic
) {
    const marker =
        "[[WIKI_CLEAR]]";


    const value =
        editor.value;


    /*
     * Diagnostic.start đang trỏ đúng
     * vị trí marker.
     *
     * Nhưng vẫn kiểm tra lại để tránh
     * sửa sai nếu nội dung vừa thay đổi.
     */
    const markerStart =
        value.indexOf(
            marker,
            Math.max(
                0,
                diagnostic.start - 2
            )
        );


    if (
        markerStart === -1
    ) {
        return false;
    }


    const markerEnd =
        markerStart
        + marker.length;


    let before =
        value.substring(
            0,
            markerStart
        );


    let after =
        value.substring(
            markerEnd
        );


    /*
     * Xóa khoảng trắng/newline thừa
     * ngay sát marker.
     *
     * Không đụng vào nội dung thật.
     */
    before =
        before.replace(
            /[ \t\r\n]+$/,
            ""
        );


    after =
        after.replace(
            /^[ \t\r\n]+/,
            ""
        );


    let replacement =
        "";


    if (
        before.length > 0
    ) {

        replacement +=
            before
            + "\n\n";
    }


    const newMarkerStart =
        replacement.length;


    replacement +=
        marker
        + "\n\n"
        + after;


    editor.value =
        replacement;


    editor.focus();


    editor.setSelectionRange(
        newMarkerStart,
        newMarkerStart
        + marker.length
    );


    /*
     * Kích hoạt lại:
     * - diagnostics
     * - history schedule
     */
    editor.dispatchEvent(
        new Event(
            "input",
            {
                bubbles: true
            }
        )
    );


    return true;
}

function fixWikiImageBlockSpacing(
    editor,
    diagnostic
) {
    const value =
        editor.value;


    if (
        diagnostic.start < 0
        || diagnostic.end
        > value.length
        || diagnostic.start
        >= diagnostic.end
    ) {
        return false;
    }


    const imageMarkdown =
        value.substring(
            diagnostic.start,
            diagnostic.end
        );


    /*
     * Safety check:
     * phải thực sự là ảnh Wiki.
     */
    if (
        !imageMarkdown.startsWith(
            "!["
        )
        || !imageMarkdown.includes(
            "wiki:"
        )
    ) {

        return false;
    }


    /*
     * Dùng helper đã có sẵn.
     *
     * Nó sẽ đảm bảo:
     *
     * paragraph trước
     *
     * ![ảnh](...)
     *
     * paragraph sau
     */
    replaceWikiImageBlock(
        editor,
        diagnostic.start,
        diagnostic.end,
        imageMarkdown
    );


    return true;
}

function fixSplitWikiImageSyntax(
    editor,
    diagnostic
) {
    const value =
        editor.value;


    if (
        diagnostic.start < 0
        || diagnostic.end
        > value.length
        || diagnostic.start
        >= diagnostic.end
    ) {
        return false;
    }


    const brokenMarkdown =
        value.substring(
            diagnostic.start,
            diagnostic.end
        );


    /*
     * Sai:
     *
     * ![anh]
     * (URL "wiki:...")
     *
     * Đúng:
     *
     * ![anh](URL "wiki:...")
     */
    const fixedMarkdown =
        brokenMarkdown.replace(
            /\]\s*\r?\n+\s*\(/,
            "]("
        );


    if (
        fixedMarkdown
        === brokenMarkdown
    ) {

        return false;
    }


    /*
     * Sau khi sửa cú pháp,
     * normalize luôn thành block riêng.
     */
    replaceWikiImageBlock(
        editor,
        diagnostic.start,
        diagnostic.end,
        fixedMarkdown
    );


    return true;
}

/* =========================================================
   WIKI CLEAR
   ========================================================= */

function validateClearWrapMarkers(
    markdown,
    diagnostics
) {
    const marker =
        "[[WIKI_CLEAR]]";


    let searchFrom =
        0;

    let occurrence =
        0;


    /*
     * Có giới hạn rõ ràng:
     * searchFrom luôn tăng.
     */
    while (
        searchFrom
        < markdown.length
    ) {

        const markerIndex =
            markdown.indexOf(
                marker,
                searchFrom
            );


        if (
            markerIndex === -1
        ) {
            break;
        }


        occurrence++;


        const before =
            markdown.substring(
                0,
                markerIndex
            );


        const after =
            markdown.substring(
                markerIndex
                + marker.length
            );


        const validBefore =
            before.length === 0
            || before.endsWith(
                "\n\n"
            );


        const validAfter =
            after.length === 0
            || after.startsWith(
                "\n\n"
            );


        if (
            !validBefore
            || !validAfter
        ) {

            let detail;


            if (
                !validBefore
                && !validAfter
            ) {

                detail =
                    "cần một dòng trống trước và sau.";

            }
            else if (
                !validBefore
            ) {

                detail =
                    "cần một dòng trống trước.";

            }
            else {

                detail =
                    "cần một dòng trống sau.";
            }


            diagnostics.push({
                code:
                    "CLEAR_WRAP_SPACING",

                message:
                    "Ngắt bọc ảnh #"
                    + occurrence
                    + ": "
                    + detail
                    + " Nhấn để tìm vị trí.",

                start:
                    markerIndex,

                end:
                    markerIndex
                    + marker.length
            });
        }


        /*
         * BẮT BUỘC tăng.
         */
        searchFrom =
            markerIndex
            + marker.length;
    }
}


/* =========================================================
   MALFORMED WIKI IMAGE
   ========================================================= */

function validateMalformedWikiImages(
    markdown,
    diagnostics
) {
    /*
     * Tìm tất cả metadata Wiki.
     *
     * Mỗi "wiki:" phải nằm trong một
     * Markdown image hợp lệ.
     */
    const metadataMatches =
        Array.from(
            markdown.matchAll(
                /wiki:/g
            )
        );


    let occurrence =
        0;


    metadataMatches.forEach(
        function(match) {

            occurrence++;


            const metadataIndex =
                match.index;


            /*
             * Tìm ảnh gần nhất chứa metadata này.
             */
            const imageStart =
                markdown.lastIndexOf(
                    "![",
                    metadataIndex
                );


            const imageEnd =
                markdown.indexOf(
                    ")",
                    metadataIndex
                );


            /*
             * Không tìm được đầu / cuối image.
             */
            if (
                imageStart === -1
                || imageEnd === -1
                || imageStart > metadataIndex
            ) {

                diagnostics.push({
                    message:
                        "Ảnh Wiki #"
                        + occurrence
                        + ": không xác định được cú pháp ảnh hoàn chỉnh. "
                        + "Nhấn để tìm vị trí.",

                    start:
                        Math.max(
                            0,
                            metadataIndex - 20
                        ),

                    end:
                        Math.min(
                            markdown.length,
                            metadataIndex + 80
                        )
                });

                return;
            }


            const candidate =
                markdown.substring(
                    imageStart,
                    imageEnd + 1
                );


            /*
             * =================================================
             * CASE 1
             *
             * ![alt]
             * (URL "wiki:...")
             *
             * ] và ( bị tách bởi newline thật.
             * =================================================
             */
            const splitBetweenAltAndUrl =
                /\]\s*\r?\n+\s*\(/.test(
                    candidate
                );


            if (
                splitBetweenAltAndUrl
            ) {

                diagnostics.push({
                    code:
                        "IMAGE_SPLIT_SYNTAX",
                    message:
                        "Ảnh Wiki #"
                        + occurrence
                        + ": mô tả ảnh và URL đang bị tách dòng. "
                        + "Phần ] và ( phải nằm liền nhau. "
                        + "Nhấn để tìm vị trí.",

                    start:
                        imageStart,

                    end:
                        imageEnd + 1
                });

                return;
            }


            /*
             * =================================================
             * CASE 2
             *
             * Kiểm tra cú pháp tổng thể.
             *
             * Đúng:
             *
             * ![alt](URL "wiki:size=small;layout=wrap-right")
             * =================================================
             */
            const validSyntax =
                /^!\[(?:\\.|[^\]])*\]\([^\s)\r\n]+[ \t]+"wiki:[^"\r\n]*"\)$/
                    .test(
                        candidate
                    );


            if (
                !validSyntax
            ) {

                diagnostics.push({
                    message:
                        "Ảnh Wiki #"
                        + occurrence
                        + ": cú pháp ảnh không hợp lệ. "
                        + "Nhấn để tìm vị trí.",

                    start:
                        imageStart,

                    end:
                        imageEnd + 1
                });

                return;
            }


            /*
             * =================================================
             * CASE 3
             *
             * Ảnh đúng cú pháp nhưng không đứng thành block.
             *
             * Ví dụ sai:
             *
             * text
             * ![ảnh](...)
             * text
             *
             * Ví dụ đúng:
             *
             * text
             *
             * ![ảnh](...)
             *
             * text
             * =================================================
             */

            const before =
                markdown.substring(
                    0,
                    imageStart
                );


            const after =
                markdown.substring(
                    imageEnd + 1
                );


            const validBefore =
                before.length === 0
                || /(?:\r?\n){2}$/.test(
                    before
                );


            const validAfter =
                after.length === 0
                || /^(?:\r?\n){2}/.test(
                    after
                );


            if (
                !validBefore
                || !validAfter
            ) {

                let detail;


                if (
                    !validBefore
                    && !validAfter
                ) {

                    detail =
                        "cần một dòng trống trước và sau ảnh.";

                }
                else if (
                    !validBefore
                ) {

                    detail =
                        "cần một dòng trống trước ảnh.";

                }
                else {

                    detail =
                        "cần một dòng trống sau ảnh.";
                }


                diagnostics.push({
                    code:
                        "IMAGE_BLOCK_SPACING",
                    message:
                        "Ảnh Wiki #"
                        + occurrence
                        + ": "
                        + detail
                        + " Nếu không, kích thước và bố trí ảnh "
                        + "có thể không được áp dụng đúng. "
                        + "Nhấn để tìm vị trí.",

                    start:
                        imageStart,

                    end:
                        imageEnd + 1
                });
            }
        }
    );
}


/* =========================================================
   WIKI IMAGE METADATA
   ========================================================= */

function validateWikiImageMetadata(
    markdown,
    diagnostics
) {
    const imagePattern =
        /!\[[^\]]*\]\([^\)]*"wiki:([^"]*)"\)/g;


    const matches =
        Array.from(
            markdown.matchAll(
                imagePattern
            )
        );


    matches.forEach(
        function(match) {

            const metadata =
                match[1];


            const sizeMatch =
                metadata.match(
                    /(?:^|;)size=([^;]+)/
                );


            const layoutMatch =
                metadata.match(
                    /(?:^|;)layout=([^;]+)/
                );


            if (
                sizeMatch
                && ![
                    "small",
                    "medium",
                    "large",
                    "full"
                ].includes(
                    sizeMatch[1]
                )
            ) {

                diagnostics.push({
                    message:
                        "Ảnh Wiki sử dụng kích thước không hợp lệ: "
                        + sizeMatch[1]
                        + ". Nhấn để tìm vị trí.",

                    start:
                        match.index,

                    end:
                        match.index
                        + match[0].length
                });
            }


            if (
                layoutMatch
                && ![
                    "block-left",
                    "block-center",
                    "block-right",
                    "wrap-left",
                    "wrap-right"
                ].includes(
                    layoutMatch[1]
                )
            ) {

                diagnostics.push({
                    message:
                        "Ảnh Wiki sử dụng bố trí không hợp lệ: "
                        + layoutMatch[1]
                        + ". Nhấn để tìm vị trí.",

                    start:
                        match.index,

                    end:
                        match.index
                        + match[0].length
                });
            }


            if (
                sizeMatch
                && layoutMatch
                && sizeMatch[1] === "full"
                && (
                    layoutMatch[1]
                    === "wrap-left"
                    || layoutMatch[1]
                    === "wrap-right"
                )
            ) {

                diagnostics.push({
                    message:
                        "Ảnh Wiki toàn chiều rộng không thể dùng bố trí bọc chữ. "
                        + "Nhấn để tìm vị trí.",

                    start:
                        match.index,

                    end:
                        match.index
                        + match[0].length
                });
            }
        }
    );
}