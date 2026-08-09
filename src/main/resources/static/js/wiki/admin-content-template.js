document.addEventListener(
    "DOMContentLoaded",
    () => {

        const typeSelect =
            document.getElementById(
                "wikiArticleType"
            );

        const contentInput =
            document.getElementById(
                "wikiContent"
            );

        const applyButton =
            document.getElementById(
                "applyContentTemplate"
            );

        if (
            !typeSelect
            || !contentInput
            || !applyButton
        ) {
            return;
        }

        applyButton.addEventListener(
            "click",
            async () => {

                const articleType =
                    typeSelect.value;

                if (!articleType) {
                    return;
                }

                /*
                 * Không được âm thầm ghi đè bài
                 * Admin đang soạn.
                 */
                if (
                    contentInput.value.trim()
                        .length > 0
                ) {
                    const confirmed =
                        window.confirm(
                            "Nội dung hiện tại sẽ được thay bằng "
                            + "mẫu của loại bài "
                            + articleType
                            + ". Bạn có muốn tiếp tục?"
                        );

                    if (!confirmed) {
                        return;
                    }
                }

                applyButton.disabled =
                    true;

                try {
                    const response =
                        await fetch(
                            "/admin/wiki/articles"
                            + "/content-template"
                            + "?type="
                            + encodeURIComponent(
                                articleType
                            )
                        );

                    if (!response.ok) {
                        throw new Error(
                            "Không thể tải mẫu nội dung."
                        );
                    }

                    const template =
                        await response.text();

                    contentInput.value =
                        template;

                    /*
                     * Báo cho browser/editor rằng
                     * textarea vừa thay đổi.
                     */
                    contentInput.dispatchEvent(
                        new Event(
                            "input",
                            {
                                bubbles: true
                            }
                        )
                    );

                    contentInput.focus();
                }
                catch (error) {
                    console.error(
                        error
                    );

                    window.alert(
                        "Không thể tải mẫu nội dung Wiki."
                    );
                }
                finally {
                    applyButton.disabled =
                        false;
                }
            }
        );
    }
);