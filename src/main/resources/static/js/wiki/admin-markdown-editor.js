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

        const toolbarButtons =
            document.querySelectorAll(
                "[data-markdown-action]"
            );

        toolbarButtons.forEach(
            function(button) {

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

    if (
        !imageButton
        || !fileInput
    ) {
        return;
    }

    let insertionStart = 0;
    let insertionEnd = 0;
    let selectedText = "";


    imageButton.addEventListener(
        "click",
        function () {

            /*
             * Lưu vị trí cursor trước khi
             * browser mở file picker.
             */
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


    fileInput.addEventListener(
        "change",
        async function () {

            const file =
                fileInput.files?.[0];

            if (!file) {
                return;
            }

            const defaultAlt =
                selectedText.trim()
                || removeFileExtension(
                    file.name
                );

            const altText =
                window.prompt(
                    "Nhập mô tả cho ảnh:",
                    defaultAlt
                );

            if (altText === null) {
                return;
            }

            history.flush();

            imageButton.disabled = true;

            const oldLabel =
                imageButton.textContent;

            imageButton.textContent =
                "Đang tải...";

            try {
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

                const safeAlt =
                    escapeMarkdownAltText(
                        altText.trim()
                        || defaultAlt
                    );

                const markdown =
                    "!["
                    + safeAlt
                    + "]("
                    + responseBody.url
                    + ")";

                insertImageMarkdown(
                    editor,
                    insertionStart,
                    insertionEnd,
                    markdown
                );

                history.record();

                editor.focus();

            } catch (error) {

                console.error(
                    "Wiki image upload failed:",
                    error
                );

                window.alert(
                    error.message
                    || "Không thể tải ảnh Wiki."
                );

            } finally {

                imageButton.disabled =
                    false;

                imageButton.textContent =
                    oldLabel;

                fileInput.value = "";
            }
        }
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
    const value =
        editor.value;

    let replacement =
        markdown;

    /*
     * Nếu trước ảnh chưa có dòng trống,
     * tạo block riêng.
     */
    if (
        start > 0
        && !value
                .substring(
                    0,
                    start
                )
                .endsWith("\n\n")
    ) {
        replacement =
            "\n\n"
            + replacement;
    }

   
    if (
        end < value.length
        && !value
                .substring(end)
                .startsWith("\n\n")
    ) {
        replacement =
            replacement
            + "\n\n";
    }

    replaceRange(
        editor,
        start,
        end,
        replacement
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