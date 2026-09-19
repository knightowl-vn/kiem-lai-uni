/**
 * KiemLai Universe — Shared Comment Report Modal & Draft Foundation (MS-05E / E8C2)
 *
 * Responsibilities:
 * - Reusable modal component for reporting interaction comments across surfaces (Wiki, Novel reader, Novel drawer).
 * - Manages single reusable modal instance per application surface.
 * - Integrates EphemeralDraftStore for ephemeral unsent report draft persistence (5-minute TTL).
 * - Canonical storage key: kiemlai:draft:interaction-report:{commentId}.
 * - Debounced draft auto-saving (~400ms) on reason change and description input; flushes on pagehide/passive close.
 * - Passive close (X, backdrop click, Escape, pagehide) preserves draft.
 * - Explicit Cancel clears draft and resets form.
 * - Successful submit (201 Created) clears draft, resets form, closes modal, and notifies caller.
 * - Failed submit preserves draft and displays user-friendly error feedback.
 * - Validates reason selection and description rules (max 500 chars, OTHER requires non-blank description).
 * - Extracts Spring Security CSRF meta tags from document.
 * - Submits POST request with exact payload { reason, description } to the consumer-supplied submitUrl.
 * - Prevents double submission while request is in flight.
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define(['./ephemeral-draft-store'], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory(require('./ephemeral-draft-store.js'));
    } else {
        const draftStore = root.EphemeralDraftStore || (root.KiemLai && root.KiemLai.EphemeralDraftStore);
        const exports = factory(draftStore);
        root.CommentReportModal = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommentReportModal = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function (injectedDefaultStore) {
    'use strict';

    const DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
    const DEBOUNCE_DELAY_MS = 400;
    const MAX_DESCRIPTION_LENGTH = 500;

    const MODAL_ID = 'kiemlaiCommentReportModal';
    const TITLE_ID = 'kiemlaiCommentReportTitle';
    const FORM_ID = 'kiemlaiCommentReportForm';
    const STATUS_ID = 'kiemlaiCommentReportStatus';
    const DESCRIPTION_ID = 'kiemlaiCommentReportDescription';
    const CHAR_COUNTER_ID = 'kiemlaiCommentReportCharCounter';
    const DESC_REQUIRED_ID = 'kiemlaiCommentReportDescRequired';
    const DESC_HELP_ID = 'kiemlaiCommentReportDescHelp';
    const SUBMIT_BTN_ID = 'kiemlaiCommentReportSubmit';

    const REPORT_REASONS = [
        { value: 'SPAM', label: 'Nội dung rác hoặc quảng cáo' },
        { value: 'HARASSMENT', label: 'Quấy rối hoặc đe dọa' },
        { value: 'HATE_SPEECH', label: 'Ngôn từ kích động thù hận' },
        { value: 'SEXUAL_OR_OBSCENE', label: 'Nội dung khiêu dâm hoặc phản cảm' },
        { value: 'SPOILER', label: 'Tiết lộ nội dung trước (Spoiler)' },
        { value: 'OTHER', label: 'Lý do khác' }
    ];

    const VALID_REASON_VALUES = REPORT_REASONS.map(function (r) {
        return r.value;
    });

    /**
     * Builds canonical storage key for a comment report draft.
     * Schema: kiemlai:draft:interaction-report:{commentId}
     *
     * @param {*} commentId
     * @returns {string|null}
     */
    function getReportDraftKey(commentId) {
        if (typeof commentId !== 'string') {
            return null;
        }
        const trimmed = commentId.trim();
        if (!trimmed) {
            return null;
        }
        return 'kiemlai:draft:interaction-report:' + encodeURIComponent(trimmed);
    }

    /**
     * Extracts CSRF token and header name from document <meta> tags.
     *
     * @param {Document} doc
     * @returns {{token: string, headerName: string}|null}
     */
    function getCsrf(doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.querySelector !== 'function') {
            return null;
        }
        const tokenMeta = d.querySelector('meta[name="_csrf"]');
        const headerMeta = d.querySelector('meta[name="_csrf_header"]');

        const token = tokenMeta && typeof tokenMeta.getAttribute === 'function'
            ? (tokenMeta.getAttribute('content') || '').trim()
            : '';
        const headerName = headerMeta && typeof headerMeta.getAttribute === 'function'
            ? (headerMeta.getAttribute('content') || '').trim()
            : '';

        if (!token || !headerName) {
            return null;
        }
        return { token: token, headerName: headerName };
    }

    /**
     * Creates a new CommentReportModal instance with optional dependency injection.
     *
     * @param {Object} [options]
     * @param {Document} [options.doc] - Document context
     * @param {Object} [options.draftStore] - EphemeralDraftStore instance
     * @param {Function} [options.fetch] - Fetch implementation
     * @param {Function} [options.onSuccess] - Global success handler
     * @param {Function} [options.onError] - Global error handler
     * @returns {Object} Modal controller instance
     */
    function createCommentReportModal(options) {
        const opts = options || {};
        let doc = opts.doc || (typeof document !== 'undefined' ? document : null);
        let customDraftStore = opts.draftStore || null;
        let customFetch = opts.fetch || null;
        let defaultOnSuccess = typeof opts.onSuccess === 'function' ? opts.onSuccess : null;
        let defaultOnError = typeof opts.onError === 'function' ? opts.onError : null;

        // Instance State
        let activeCommentId = null;
        let activeSubmitUrl = null;
        let activeContextLabel = '';
        let activeOnSuccess = null;
        let activeOnError = null;
        let previousFocusedElement = null;

        let isOpen = false;
        let isSubmitting = false;
        let debounceTimer = null;
        let debounceGeneration = 0;
        let debounceCommentId = null;

        let currentGeneration = 0;

        let boundKeydownHandler = null;
        let boundPagehideHandler = null;

        // Cached DOM elements
        let modalEl = null;
        let formEl = null;
        let statusEl = null;
        let textareaEl = null;
        let charCounterEl = null;
        let descRequiredEl = null;
        let descHelpEl = null;
        let submitBtnEl = null;
        let cancelBtnEl = null;
        let closeBtnEl = null;
        let backdropEl = null;
        let radioInputs = [];

        function resolveDraftStore() {
            if (customDraftStore) {
                return customDraftStore;
            }
            if (injectedDefaultStore) {
                return injectedDefaultStore;
            }
            if (typeof window !== 'undefined') {
                if (window.EphemeralDraftStore) return window.EphemeralDraftStore;
                if (window.KiemLai && window.KiemLai.EphemeralDraftStore) return window.KiemLai.EphemeralDraftStore;
            }
            if (typeof globalThis !== 'undefined') {
                if (globalThis.EphemeralDraftStore) return globalThis.EphemeralDraftStore;
                if (globalThis.KiemLai && globalThis.KiemLai.EphemeralDraftStore) return globalThis.KiemLai.EphemeralDraftStore;
            }
            return null;
        }

        function resolveFetch() {
            if (typeof customFetch === 'function') {
                return customFetch;
            }
            if (typeof fetch === 'function') {
                return fetch;
            }
            if (typeof window !== 'undefined' && typeof window.fetch === 'function') {
                return window.fetch;
            }
            if (typeof globalThis !== 'undefined' && typeof globalThis.fetch === 'function') {
                return globalThis.fetch;
            }
            return null;
        }

        /**
         * Resolves the selected report reason from radio buttons.
         */
        function getSelectedReason() {
            for (let i = 0; i < radioInputs.length; i++) {
                const radio = radioInputs[i];
                if (radio && radio.checked) {
                    return radio.value;
                }
            }
            return null;
        }

        /**
         * Sets the selected reason radio button.
         */
        function setSelectedReason(value) {
            for (let i = 0; i < radioInputs.length; i++) {
                const radio = radioInputs[i];
                if (radio) {
                    radio.checked = Boolean(value && radio.value === value);
                }
            }
        }

        /**
         * Saves form state into draft storage for a specific comment and generation.
         * Stale generations or mismatched comments are rejected.
         */
        function saveDraftFor(commentId, generation) {
            const cid = commentId || activeCommentId;
            if (!cid) {
                return false;
            }
            if (generation !== undefined && generation !== currentGeneration) {
                return false;
            }
            if (cid !== activeCommentId) {
                return false;
            }

            const key = getReportDraftKey(cid);
            if (!key) {
                return false;
            }

            const store = resolveDraftStore();
            if (!store || typeof store.save !== 'function') {
                return false;
            }

            const selectedReason = getSelectedReason();
            const description = textareaEl ? textareaEl.value : '';

            // If neither reason is selected nor description has any text -> remove draft
            if (!selectedReason && (!description || description.trim().length === 0)) {
                if (typeof store.remove === 'function') {
                    store.remove(key);
                }
                return true;
            }

            const draftData = {
                reason: selectedReason || null,
                description: description
            };

            return store.save(key, JSON.stringify(draftData));
        }

        /**
         * Saves current form state into draft storage using active context.
         */
        function saveDraft() {
            return saveDraftFor(activeCommentId, currentGeneration);
        }

        /**
         * Synchronously flushes debounced draft.
         */
        function flushDraft() {
            if (debounceTimer) {
                clearTimeout(debounceTimer);
                debounceTimer = null;
            }
            return saveDraftFor(activeCommentId, currentGeneration);
        }

        /**
         * Schedules debounced draft save with strict generation and comment ownership.
         */
        function scheduleDraftSave() {
            if (debounceTimer) {
                clearTimeout(debounceTimer);
                debounceTimer = null;
            }

            const scheduledGeneration = currentGeneration;
            const scheduledCommentId = activeCommentId;
            debounceGeneration = scheduledGeneration;
            debounceCommentId = scheduledCommentId;

            debounceTimer = setTimeout(function () {
                debounceTimer = null;
                // Stale timer must no-op: verify generation and comment ownership
                if (scheduledGeneration !== currentGeneration || scheduledCommentId !== activeCommentId || !isOpen) {
                    return;
                }
                saveDraftFor(scheduledCommentId, scheduledGeneration);
            }, DEBOUNCE_DELAY_MS);
        }

        /**
         * Loads and parses draft for the specified comment ID.
         */
        function loadDraft(commentId) {
            if (!commentId) {
                return null;
            }
            const key = getReportDraftKey(commentId);
            if (!key) {
                return null;
            }
            const store = resolveDraftStore();
            if (!store || typeof store.load !== 'function') {
                return null;
            }

            const raw = store.load(key);
            if (!raw || typeof raw !== 'string') {
                return null;
            }

            try {
                const parsed = JSON.parse(raw);
                if (!parsed || typeof parsed !== 'object') {
                    if (typeof store.remove === 'function') {
                        store.remove(key);
                    }
                    return null;
                }

                // Check reason validity: unknown reasons are discarded
                const reason = (typeof parsed.reason === 'string' && VALID_REASON_VALUES.includes(parsed.reason))
                    ? parsed.reason
                    : null;
                const description = (typeof parsed.description === 'string')
                    ? parsed.description
                    : '';

                return { reason: reason, description: description };
            } catch (_) {
                if (typeof store.remove === 'function') {
                    store.remove(key);
                }
                return null;
            }
        }

        /**
         * Removes draft from storage for the specified comment ID.
         */
        function removeDraft(commentId) {
            const id = commentId || activeCommentId;
            if (!id) {
                return;
            }
            const key = getReportDraftKey(id);
            if (!key) {
                return;
            }
            const store = resolveDraftStore();
            if (store && typeof store.remove === 'function') {
                store.remove(key);
            }
        }

        /**
         * Evaluates form validity and returns validation state.
         */
        function evaluateValidity() {
            const reason = getSelectedReason();
            const description = textareaEl ? textareaEl.value : '';

            if (!reason || !VALID_REASON_VALUES.includes(reason)) {
                return { isValid: false, reasonRequired: true, otherRequired: false, lengthExceeded: false };
            }

            if (description.length > MAX_DESCRIPTION_LENGTH) {
                return { isValid: false, reasonRequired: false, otherRequired: false, lengthExceeded: true };
            }

            if (reason === 'OTHER' && (!description || description.trim().length === 0)) {
                return { isValid: false, reasonRequired: false, otherRequired: true, lengthExceeded: false };
            }

            return { isValid: true, reasonRequired: false, otherRequired: false, lengthExceeded: false };
        }

        /**
         * Updates character counter, required indicator, and submit button state.
         */
        function updateUIState() {
            const reason = getSelectedReason();
            const description = textareaEl ? textareaEl.value : '';
            const length = description.length;

            // Character counter
            if (charCounterEl) {
                charCounterEl.textContent = length + ' / ' + MAX_DESCRIPTION_LENGTH;
                if (charCounterEl.classList) {
                    if (length > MAX_DESCRIPTION_LENGTH) {
                        charCounterEl.classList.add('text-danger');
                        charCounterEl.classList.remove('text-muted');
                    } else {
                        charCounterEl.classList.add('text-muted');
                        charCounterEl.classList.remove('text-danger');
                    }
                }
            }

            // Required state for OTHER
            const isOther = reason === 'OTHER';
            if (descRequiredEl) {
                descRequiredEl.hidden = !isOther;
                if (isOther) {
                    descRequiredEl.removeAttribute('hidden');
                } else {
                    descRequiredEl.setAttribute('hidden', '');
                }
            }

            if (textareaEl) {
                textareaEl.setAttribute('aria-required', isOther ? 'true' : 'false');
            }

            if (descHelpEl) {
                if (isOther) {
                    descHelpEl.textContent = 'Bắt buộc nhập mô tả khi chọn Lý do khác (tối đa 500 ký tự).';
                } else {
                    descHelpEl.textContent = 'Tối đa 500 ký tự.';
                }
            }

            // Submit button enabled/disabled
            if (submitBtnEl) {
                if (isSubmitting) {
                    submitBtnEl.disabled = true;
                    submitBtnEl.setAttribute('disabled', '');
                } else {
                    const validity = evaluateValidity();
                    submitBtnEl.disabled = !validity.isValid;
                    if (validity.isValid) {
                        submitBtnEl.removeAttribute('disabled');
                    } else {
                        submitBtnEl.setAttribute('disabled', '');
                    }
                }
            }
        }

        /**
         * Displays a status message in the modal.
         */
        function showStatus(type, message) {
            if (!statusEl) return;
            statusEl.textContent = message || '';
            statusEl.hidden = false;
            statusEl.removeAttribute('hidden');

            if (statusEl.classList) {
                statusEl.classList.remove('alert-success', 'alert-danger', 'alert-info', 'd-none');
                if (type === 'error') {
                    statusEl.classList.add('alert-danger');
                } else if (type === 'success') {
                    statusEl.classList.add('alert-success');
                } else {
                    statusEl.classList.add('alert-info');
                }
            }
        }

        /**
         * Clears status message.
         */
        function clearStatus() {
            if (!statusEl) return;
            statusEl.textContent = '';
            statusEl.hidden = true;
            statusEl.setAttribute('hidden', '');
            if (statusEl.classList) {
                statusEl.classList.add('d-none');
                statusEl.classList.remove('alert-success', 'alert-danger', 'alert-info');
            }
        }

        /**
         * Resets the form inputs to initial blank state.
         */
        function resetForm() {
            setSelectedReason(null);
            if (textareaEl) {
                textareaEl.value = '';
            }
            updateUIState();
            clearStatus();
        }

        /**
         * Constructs or resolves the single shared modal DOM structure.
         */
        function ensureModal(d) {
            if (!d) return null;

            if (modalEl && modalEl.parentNode) {
                return modalEl;
            }

            let existing = d.getElementById ? d.getElementById(MODAL_ID) : null;
            if (!existing && typeof d.querySelector === 'function') {
                existing = d.querySelector('#' + MODAL_ID);
            }
            if (existing) {
                modalEl = existing;
                cacheElements(d);
                attachInternalListeners();
                return modalEl;
            }

            // Create Modal Container
            modalEl = d.createElement('div');
            modalEl.id = MODAL_ID;
            modalEl.setAttribute('id', MODAL_ID);
            modalEl.className = 'modal fade comment-report-modal';
            modalEl.setAttribute('role', 'dialog');
            modalEl.setAttribute('aria-modal', 'true');
            modalEl.setAttribute('aria-labelledby', TITLE_ID);
            modalEl.setAttribute('tabindex', '-1');
            modalEl.hidden = true;
            modalEl.setAttribute('hidden', '');

            // Backdrop
            backdropEl = d.createElement('div');
            backdropEl.className = 'modal-backdrop fade comment-report-backdrop';
            backdropEl.setAttribute('data-action', 'passive-close');

            // Dialog & Content
            const dialog = d.createElement('div');
            dialog.className = 'modal-dialog modal-dialog-centered';
            dialog.setAttribute('role', 'document');

            const content = d.createElement('div');
            content.className = 'modal-content';

            // Header
            const header = d.createElement('header');
            header.className = 'modal-header';

            const title = d.createElement('h5');
            title.id = TITLE_ID;
            title.setAttribute('id', TITLE_ID);
            title.className = 'modal-title';
            title.textContent = 'Báo cáo bình luận';

            closeBtnEl = d.createElement('button');
            closeBtnEl.type = 'button';
            closeBtnEl.className = 'btn-close comment-report-btn-close';
            closeBtnEl.setAttribute('data-action', 'passive-close');
            closeBtnEl.setAttribute('aria-label', 'Đóng');
            closeBtnEl.textContent = '✕';

            header.appendChild(title);
            header.appendChild(closeBtnEl);

            // Form
            formEl = d.createElement('form');
            formEl.id = FORM_ID;
            formEl.setAttribute('id', FORM_ID);
            formEl.setAttribute('novalidate', '');

            // Body
            const body = d.createElement('div');
            body.className = 'modal-body';

            statusEl = d.createElement('div');
            statusEl.id = STATUS_ID;
            statusEl.setAttribute('id', STATUS_ID);
            statusEl.className = 'alert alert-danger comment-report-status d-none';
            statusEl.setAttribute('role', 'status');
            statusEl.setAttribute('aria-live', 'polite');
            statusEl.hidden = true;
            statusEl.setAttribute('hidden', '');

            // Reasons Group
            const fieldset = d.createElement('fieldset');
            fieldset.className = 'comment-report-reasons-group mb-3';

            const legend = d.createElement('legend');
            legend.className = 'comment-report-legend form-label fs-6 fw-bold mb-2';
            legend.textContent = 'Lý do báo cáo ';

            const legendRequired = d.createElement('span');
            legendRequired.className = 'text-danger';
            legendRequired.setAttribute('aria-hidden', 'true');
            legendRequired.textContent = '*';
            legend.appendChild(legendRequired);
            fieldset.appendChild(legend);

            const reasonsList = d.createElement('div');
            reasonsList.className = 'comment-report-reasons-list';

            radioInputs = [];
            for (let i = 0; i < REPORT_REASONS.length; i++) {
                const item = REPORT_REASONS[i];
                const itemDiv = d.createElement('div');
                itemDiv.className = 'form-check comment-report-reason-item mb-2';

                const radio = d.createElement('input');
                radio.type = 'radio';
                radio.className = 'form-check-input comment-report-reason-input';
                radio.name = 'commentReportReason';
                radio.value = item.value;
                radio.id = 'commentReportReason_' + item.value;
                radio.setAttribute('name', 'commentReportReason');
                radio.setAttribute('value', item.value);
                radio.setAttribute('id', 'commentReportReason_' + item.value);

                const label = d.createElement('label');
                label.className = 'form-check-label';
                label.setAttribute('for', 'commentReportReason_' + item.value);
                label.textContent = item.label;

                itemDiv.appendChild(radio);
                itemDiv.appendChild(label);
                reasonsList.appendChild(itemDiv);
                radioInputs.push(radio);
            }
            fieldset.appendChild(reasonsList);

            // Description Group
            const descGroup = d.createElement('div');
            descGroup.className = 'comment-report-description-group mb-3';

            const descLabel = d.createElement('label');
            descLabel.setAttribute('for', DESCRIPTION_ID);
            descLabel.className = 'form-label fw-bold mb-1';
            descLabel.textContent = 'Mô tả chi tiết ';

            descRequiredEl = d.createElement('span');
            descRequiredEl.id = DESC_REQUIRED_ID;
            descRequiredEl.setAttribute('id', DESC_REQUIRED_ID);
            descRequiredEl.className = 'text-danger comment-report-desc-required';
            descRequiredEl.setAttribute('aria-hidden', 'true');
            descRequiredEl.textContent = '*';
            descRequiredEl.hidden = true;
            descRequiredEl.setAttribute('hidden', '');
            descLabel.appendChild(descRequiredEl);

            textareaEl = d.createElement('textarea');
            textareaEl.id = DESCRIPTION_ID;
            textareaEl.setAttribute('id', DESCRIPTION_ID);
            textareaEl.className = 'form-control comment-report-description-input';
            textareaEl.setAttribute('rows', '4');
            textareaEl.setAttribute('maxlength', String(MAX_DESCRIPTION_LENGTH));
            textareaEl.setAttribute('placeholder', 'Cung cấp thêm thông tin về vi phạm...');
            textareaEl.setAttribute('aria-describedby', CHAR_COUNTER_ID);
            textareaEl.setAttribute('aria-required', 'false');

            const metaRow = d.createElement('div');
            metaRow.className = 'd-flex justify-content-between align-items-center mt-1';

            descHelpEl = d.createElement('small');
            descHelpEl.id = DESC_HELP_ID;
            descHelpEl.setAttribute('id', DESC_HELP_ID);
            descHelpEl.className = 'text-muted comment-report-desc-help';
            descHelpEl.textContent = 'Tối đa 500 ký tự.';

            charCounterEl = d.createElement('small');
            charCounterEl.id = CHAR_COUNTER_ID;
            charCounterEl.setAttribute('id', CHAR_COUNTER_ID);
            charCounterEl.className = 'text-muted comment-report-char-counter';
            charCounterEl.setAttribute('aria-live', 'polite');
            charCounterEl.textContent = '0 / ' + MAX_DESCRIPTION_LENGTH;

            metaRow.appendChild(descHelpEl);
            metaRow.appendChild(charCounterEl);

            descGroup.appendChild(descLabel);
            descGroup.appendChild(textareaEl);
            descGroup.appendChild(metaRow);

            body.appendChild(statusEl);
            body.appendChild(fieldset);
            body.appendChild(descGroup);

            // Footer
            const footer = d.createElement('div');
            footer.className = 'modal-footer';

            cancelBtnEl = d.createElement('button');
            cancelBtnEl.type = 'button';
            cancelBtnEl.className = 'btn btn-outline-secondary comment-report-btn-cancel';
            cancelBtnEl.setAttribute('data-action', 'explicit-cancel');
            cancelBtnEl.textContent = 'Hủy';

            submitBtnEl = d.createElement('button');
            submitBtnEl.type = 'submit';
            submitBtnEl.id = SUBMIT_BTN_ID;
            submitBtnEl.setAttribute('id', SUBMIT_BTN_ID);
            submitBtnEl.className = 'btn btn-danger comment-report-btn-submit';
            submitBtnEl.textContent = 'Gửi báo cáo';
            submitBtnEl.disabled = true;
            submitBtnEl.setAttribute('disabled', '');

            footer.appendChild(cancelBtnEl);
            footer.appendChild(submitBtnEl);

            formEl.appendChild(body);
            formEl.appendChild(footer);

            content.appendChild(header);
            content.appendChild(formEl);
            dialog.appendChild(content);

            modalEl.appendChild(backdropEl);
            modalEl.appendChild(dialog);

            attachInternalListeners();

            if (d.body && typeof d.body.appendChild === 'function') {
                d.body.appendChild(modalEl);
            } else if (typeof d.appendChild === 'function') {
                d.appendChild(modalEl);
            }

            return modalEl;
        }

        /**
         * Resolves and caches element references from existing DOM.
         */
        function cacheElements(d) {
            if (!modalEl) return;
            formEl = modalEl.querySelector('#' + FORM_ID) || (d.getElementById ? d.getElementById(FORM_ID) : null);
            statusEl = modalEl.querySelector('#' + STATUS_ID) || (d.getElementById ? d.getElementById(STATUS_ID) : null);
            textareaEl = modalEl.querySelector('#' + DESCRIPTION_ID) || (d.getElementById ? d.getElementById(DESCRIPTION_ID) : null);
            charCounterEl = modalEl.querySelector('#' + CHAR_COUNTER_ID) || (d.getElementById ? d.getElementById(CHAR_COUNTER_ID) : null);
            descRequiredEl = modalEl.querySelector('#' + DESC_REQUIRED_ID) || (d.getElementById ? d.getElementById(DESC_REQUIRED_ID) : null);
            descHelpEl = modalEl.querySelector('#' + DESC_HELP_ID) || (d.getElementById ? d.getElementById(DESC_HELP_ID) : null);
            submitBtnEl = modalEl.querySelector('#' + SUBMIT_BTN_ID) || (d.getElementById ? d.getElementById(SUBMIT_BTN_ID) : null);
            cancelBtnEl = modalEl.querySelector('.comment-report-btn-cancel') || modalEl.querySelector('[data-action="explicit-cancel"]');
            closeBtnEl = modalEl.querySelector('.comment-report-btn-close') || modalEl.querySelector('[data-action="passive-close"]');
            backdropEl = modalEl.querySelector('.comment-report-backdrop') || modalEl.querySelector('.modal-backdrop');

            const queriedRadios = modalEl.querySelectorAll ? modalEl.querySelectorAll('input[name="commentReportReason"]') : [];
            radioInputs = Array.from(queriedRadios);
        }

        /**
         * Attaches internal event listeners to modal elements.
         */
        function attachInternalListeners() {
            // Reason radio buttons change
            for (let i = 0; i < radioInputs.length; i++) {
                const radio = radioInputs[i];
                if (radio && typeof radio.addEventListener === 'function') {
                    if (radio._reportChangeHandler && typeof radio.removeEventListener === 'function') {
                        radio.removeEventListener('change', radio._reportChangeHandler);
                    }
                    radio._reportChangeHandler = function () {
                        updateUIState();
                        scheduleDraftSave();
                    };
                    radio.addEventListener('change', radio._reportChangeHandler);
                }
            }

            // Description textarea input
            if (textareaEl && typeof textareaEl.addEventListener === 'function') {
                if (textareaEl._reportInputHandler && typeof textareaEl.removeEventListener === 'function') {
                    textareaEl.removeEventListener('input', textareaEl._reportInputHandler);
                }
                textareaEl._reportInputHandler = function () {
                    updateUIState();
                    scheduleDraftSave();
                };
                textareaEl.addEventListener('input', textareaEl._reportInputHandler);
            }

            // Form submission
            if (formEl && typeof formEl.addEventListener === 'function') {
                if (formEl._reportSubmitHandler && typeof formEl.removeEventListener === 'function') {
                    formEl.removeEventListener('submit', formEl._reportSubmitHandler);
                }
                formEl._reportSubmitHandler = function (e) {
                    handleSubmit(e);
                };
                formEl.addEventListener('submit', formEl._reportSubmitHandler);
            }

            // Explicit Cancel button -> clears draft, resets form, closes modal
            if (cancelBtnEl && typeof cancelBtnEl.addEventListener === 'function') {
                if (cancelBtnEl._reportCancelHandler && typeof cancelBtnEl.removeEventListener === 'function') {
                    cancelBtnEl.removeEventListener('click', cancelBtnEl._reportCancelHandler);
                }
                cancelBtnEl._reportCancelHandler = function (e) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    closeModal(true);
                };
                cancelBtnEl.addEventListener('click', cancelBtnEl._reportCancelHandler);
            }

            // Passive close button (X) -> preserves draft, closes modal
            if (closeBtnEl && typeof closeBtnEl.addEventListener === 'function') {
                if (closeBtnEl._reportCloseHandler && typeof closeBtnEl.removeEventListener === 'function') {
                    closeBtnEl.removeEventListener('click', closeBtnEl._reportCloseHandler);
                }
                closeBtnEl._reportCloseHandler = function (e) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    closeModal(false);
                };
                closeBtnEl.addEventListener('click', closeBtnEl._reportCloseHandler);
            }

            // Backdrop click -> passive close
            if (backdropEl && typeof backdropEl.addEventListener === 'function') {
                if (backdropEl._reportBackdropHandler && typeof backdropEl.removeEventListener === 'function') {
                    backdropEl.removeEventListener('click', backdropEl._reportBackdropHandler);
                }
                backdropEl._reportBackdropHandler = function (e) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    closeModal(false);
                };
                backdropEl.addEventListener('click', backdropEl._reportBackdropHandler);
            }
        }

        /**
         * Submits report to active submitUrl.
         */
        /**
         * Submits report to active submitUrl.
         */
        async function handleSubmit(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }

            if (isSubmitting) {
                return;
            }

            const validity = evaluateValidity();
            if (!validity.isValid) {
                if (validity.reasonRequired) {
                    showStatus('error', 'Vui lòng chọn lý do báo cáo.');
                } else if (validity.otherRequired) {
                    showStatus('error', 'Vui lòng nhập mô tả chi tiết khi chọn Lý do khác.');
                } else if (validity.lengthExceeded) {
                    showStatus('error', 'Mô tả không được vượt quá ' + MAX_DESCRIPTION_LENGTH + ' ký tự.');
                }
                return;
            }

            const reason = getSelectedReason();
            const description = textareaEl ? textareaEl.value : '';

            // Capture immutable submission context
            const submissionGeneration = currentGeneration;
            const submissionCommentId = activeCommentId;
            const submissionSubmitUrl = activeSubmitUrl;
            const submissionOnSuccess = activeOnSuccess;
            const submissionOnError = activeOnError;
            const submissionReason = reason;
            const submissionDescription = description;

            isSubmitting = true;
            updateUIState();
            clearStatus();

            const payload = {
                reason: submissionReason,
                description: submissionDescription
            };

            const headers = {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            };
            const csrf = getCsrf(doc);
            if (csrf) {
                headers[csrf.headerName] = csrf.token;
            }

            const fetchImpl = resolveFetch();
            if (!fetchImpl) {
                isSubmitting = false;
                updateUIState();
                showStatus('error', 'Trình duyệt không hỗ trợ gửi dữ liệu.');
                return;
            }

            try {
                const response = await fetchImpl(submissionSubmitUrl, {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify(payload)
                });

                // Compare submissionGeneration with current generation BEFORE mutating current modal UI
                if (submissionGeneration !== currentGeneration) {
                    // Stale completion:
                    // If emitting a stale-success callback/event at all, use ONLY the original captured submission context, never current active state.
                    if (response && response.status === 201) {
                        let data = null;
                        try {
                            data = await response.json();
                        } catch (_) {}

                        notifyCapturedSuccess(data, submissionCommentId, submissionSubmitUrl, submissionOnSuccess);
                    }
                    // A stale completion must NEVER clear current draft, reset form, close modal, alter isSubmitting, alter submit button, show error in current modal, or call newer session callback.
                    return;
                }

                if (response && response.status === 201) {
                    let data = null;
                    try {
                        data = await response.json();
                    } catch (_) {}

                    // Second generation check: re-verify generation AFTER response.json() has finished parsing
                    if (submissionGeneration !== currentGeneration) {
                        // Became stale while response.json() was pending; do not touch current modal UI or draft
                        notifyCapturedSuccess(data, submissionCommentId, submissionSubmitUrl, submissionOnSuccess);
                        return;
                    }

                    // Accepted current-generation success MUST cancel pending debounce before removing draft / resetting form
                    if (debounceTimer) {
                        clearTimeout(debounceTimer);
                        debounceTimer = null;
                    }

                    // Success: clear draft, reset form, close modal
                    removeDraft(submissionCommentId);
                    resetForm();

                    hideDOMModal();
                    isOpen = false;
                    isSubmitting = false;
                    currentGeneration++; // Successful completion closes modal and invalidates generation

                    notifyCapturedSuccess(data, submissionCommentId, submissionSubmitUrl, submissionOnSuccess);

                    teardownDocumentListeners();
                    restoreFocus();
                } else if (response && response.status === 400) {
                    isSubmitting = false;
                    updateUIState();
                    showStatus('error', 'Yêu cầu không hợp lệ. Vui lòng kiểm tra lại thông tin báo cáo.');
                    notifyError(response, 400, submissionCommentId, submissionSubmitUrl, submissionOnError);
                } else if (response && response.status === 403) {
                    isSubmitting = false;
                    updateUIState();
                    showStatus('error', 'Bạn không thể báo cáo bình luận này (bình luận của chính bạn hoặc phiên đăng nhập đã hết hạn).');
                    notifyError(response, 403, submissionCommentId, submissionSubmitUrl, submissionOnError);
                } else if (response && response.status === 404) {
                    isSubmitting = false;
                    updateUIState();
                    showStatus('error', 'Bình luận hoặc nội dung được báo cáo không còn khả dụng.');
                    notifyError(response, 404, submissionCommentId, submissionSubmitUrl, submissionOnError);
                } else if (response && response.status === 409) {
                    isSubmitting = false;
                    updateUIState();
                    showStatus('error', 'Bạn đã gửi một báo cáo đang chờ xử lý cho bình luận này.');
                    notifyError(response, 409, submissionCommentId, submissionSubmitUrl, submissionOnError);
                } else {
                    isSubmitting = false;
                    updateUIState();
                    showStatus('error', 'Không thể gửi báo cáo lúc này. Vui lòng thử lại sau.');
                    notifyError(response, response ? response.status : 500, submissionCommentId, submissionSubmitUrl, submissionOnError);
                }
            } catch (err) {
                if (submissionGeneration !== currentGeneration) {
                    // Stale failure must not modify current modal UI and must not call activeOnError
                    return;
                }
                // Network failure: preserve draft, keep form usable
                isSubmitting = false;
                updateUIState();
                showStatus('error', 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.');
                notifyError(err, 0, submissionCommentId, submissionSubmitUrl, submissionOnError);
            }
        }

        function notifyCapturedSuccess(data, commentId, submitUrl, onSuccess) {
            if (typeof onSuccess === 'function') {
                try {
                    onSuccess(data, {
                        commentId: commentId,
                        submitUrl: submitUrl
                    });
                } catch (_) {}
            }

            if (doc && typeof doc.dispatchEvent === 'function' && typeof CustomEvent === 'function') {
                try {
                    doc.dispatchEvent(new CustomEvent('kiemlai:comment-report-submitted', {
                        detail: {
                            commentId: commentId,
                            report: data
                        }
                    }));
                } catch (_) {}
            }
        }

        function notifyError(responseOrError, statusCode, commentId, submitUrl, onError) {
            if (typeof onError === 'function') {
                try {
                    onError(responseOrError, {
                        commentId: commentId,
                        submitUrl: submitUrl,
                        status: statusCode
                    });
                } catch (_) {}
            }
        }

        function showDOMModal() {
            if (!modalEl) return;
            modalEl.hidden = false;
            modalEl.removeAttribute('hidden');
            if (modalEl.classList) {
                modalEl.classList.add('show');
            }
            if (backdropEl) {
                backdropEl.hidden = false;
                backdropEl.removeAttribute('hidden');
                if (backdropEl.classList) {
                    backdropEl.classList.add('show');
                }
            }
        }

        function hideDOMModal() {
            if (!modalEl) return;
            modalEl.hidden = true;
            modalEl.setAttribute('hidden', '');
            if (modalEl.classList) {
                modalEl.classList.remove('show');
            }
            if (backdropEl) {
                backdropEl.hidden = true;
                backdropEl.setAttribute('hidden', '');
                if (backdropEl.classList) {
                    backdropEl.classList.remove('show');
                }
            }
        }

        function restoreFocus() {
            if (previousFocusedElement && typeof previousFocusedElement.focus === 'function') {
                try {
                    previousFocusedElement.focus();
                } catch (_) {}
            }
            previousFocusedElement = null;
        }

        function focusInitialElement() {
            if (radioInputs.length > 0 && typeof radioInputs[0].focus === 'function') {
                try {
                    radioInputs[0].focus();
                    return;
                } catch (_) {}
            }
            if (closeBtnEl && typeof closeBtnEl.focus === 'function') {
                try {
                    closeBtnEl.focus();
                } catch (_) {}
            }
        }

        /**
         * Closes the modal.
         *
         * @param {boolean} [isExplicitCancel=false] - If true, clears draft; if false (passive), flushes draft.
         */
        function closeModal(isExplicitCancel) {
            if (!isOpen) {
                return;
            }

            if (debounceTimer) {
                clearTimeout(debounceTimer);
                debounceTimer = null;
            }

            if (isExplicitCancel === true) {
                removeDraft(activeCommentId);
                resetForm();
            } else {
                saveDraftFor(activeCommentId, currentGeneration);
            }

            hideDOMModal();
            isOpen = false;
            isSubmitting = false;
            currentGeneration++;
            clearStatus();

            teardownDocumentListeners();
            restoreFocus();
        }

        function setupDocumentListeners() {
            teardownDocumentListeners();

            boundKeydownHandler = function (e) {
                if (e && (e.key === 'Escape' || e.keyCode === 27)) {
                    if (isOpen) {
                        if (typeof e.preventDefault === 'function') e.preventDefault();
                        closeModal(false); // passive close preserves draft
                    }
                }
            };
            if (doc && typeof doc.addEventListener === 'function') {
                doc.addEventListener('keydown', boundKeydownHandler);
            }

            const win = (doc && doc.defaultView) ? doc.defaultView : (typeof window !== 'undefined' ? window : null);
            if (win && typeof win.addEventListener === 'function') {
                boundPagehideHandler = function () {
                    if (isOpen) {
                        flushDraft();
                    }
                };
                win.addEventListener('pagehide', boundPagehideHandler);
            }
        }

        function teardownDocumentListeners() {
            if (doc && typeof doc.removeEventListener === 'function' && boundKeydownHandler) {
                doc.removeEventListener('keydown', boundKeydownHandler);
            }
            boundKeydownHandler = null;

            const win = (doc && doc.defaultView) ? doc.defaultView : (typeof window !== 'undefined' ? window : null);
            if (win && typeof win.removeEventListener === 'function' && boundPagehideHandler) {
                win.removeEventListener('pagehide', boundPagehideHandler);
            }
            boundPagehideHandler = null;
        }

        /**
         * Opens the report modal for the target comment.
         *
         * @param {Object} params
         * @param {string} params.commentId - Target comment ID
         * @param {string} params.submitUrl - Authoritative POST endpoint
         * @param {string} [params.contextLabel] - Optional consumer label
         * @param {Element} [params.triggerEl] - Element that triggered open (for focus return)
         * @param {Function} [params.onSuccess] - Optional per-open success handler
         * @param {Function} [params.onError] - Optional per-open error handler
         * @returns {boolean} true if opened, false if params invalid
         */
        function openModal(params) {
            const p = params || {};
            const commentId = p.commentId;
            const submitUrl = p.submitUrl;

            if (!commentId || typeof commentId !== 'string' || !submitUrl || typeof submitUrl !== 'string') {
                return false;
            }

            const cleanCommentId = commentId.trim();
            const cleanSubmitUrl = submitUrl.trim();
            if (!cleanCommentId || !cleanSubmitUrl) {
                return false;
            }

            // If switching from another comment while open, synchronously flush old comment's draft first
            if (isOpen && activeCommentId && activeCommentId !== cleanCommentId) {
                flushDraft();
            }

            currentGeneration++;

            if (debounceTimer) {
                clearTimeout(debounceTimer);
                debounceTimer = null;
            }

            activeCommentId = cleanCommentId;
            activeSubmitUrl = cleanSubmitUrl;
            activeContextLabel = p.contextLabel || '';
            activeOnSuccess = typeof p.onSuccess === 'function' ? p.onSuccess : defaultOnSuccess;
            activeOnError = typeof p.onError === 'function' ? p.onError : defaultOnError;
            previousFocusedElement = p.triggerEl || (doc && doc.activeElement ? doc.activeElement : null);

            ensureModal(doc);
            clearStatus();
            isSubmitting = false;

            // Restore draft if present, otherwise clean form
            const draft = loadDraft(cleanCommentId);
            if (draft) {
                setSelectedReason(draft.reason);
                if (textareaEl) {
                    textareaEl.value = draft.description;
                }
            } else {
                setSelectedReason(null);
                if (textareaEl) {
                    textareaEl.value = '';
                }
            }

            updateUIState();

            isOpen = true;
            showDOMModal();
            setupDocumentListeners();
            focusInitialElement();

            return true;
        }

        /**
         * Cleans up all DOM and listeners.
         */
        function destroy() {
            closeModal(false);
            currentGeneration++;
            teardownDocumentListeners();
            if (debounceTimer) {
                clearTimeout(debounceTimer);
                debounceTimer = null;
            }
            if (modalEl && modalEl.parentNode && typeof modalEl.parentNode.removeChild === 'function') {
                modalEl.parentNode.removeChild(modalEl);
            }
            modalEl = null;
            formEl = null;
            statusEl = null;
            textareaEl = null;
            charCounterEl = null;
            descRequiredEl = null;
            descHelpEl = null;
            submitBtnEl = null;
            cancelBtnEl = null;
            closeBtnEl = null;
            backdropEl = null;
            radioInputs = [];
            activeCommentId = null;
            activeSubmitUrl = null;
        }

        return {
            open: openModal,
            close: closeModal,
            flushDraft: flushDraft,
            saveDraft: saveDraft,
            loadDraft: loadDraft,
            removeDraft: removeDraft,
            destroy: destroy,
            getCurrentGeneration: function () {
                return currentGeneration;
            },
            isOpen: function () {
                return isOpen;
            },
            isSubmitting: function () {
                return isSubmitting;
            },
            getActiveCommentId: function () {
                return activeCommentId;
            },
            getModalElement: function () {
                return modalEl;
            },
            getElements: function () {
                return {
                    modal: modalEl,
                    form: formEl,
                    status: statusEl,
                    textarea: textareaEl,
                    charCounter: charCounterEl,
                    descRequired: descRequiredEl,
                    descHelp: descHelpEl,
                    submitBtn: submitBtnEl,
                    cancelBtn: cancelBtnEl,
                    closeBtn: closeBtnEl,
                    backdrop: backdropEl,
                    radios: radioInputs
                };
            }
        };
    }

    // Default global singleton
    let defaultInstance = null;

    function getDefaultInstance() {
        if (!defaultInstance) {
            defaultInstance = createCommentReportModal();
        }
        return defaultInstance;
    }

    return {
        REPORT_REASONS: REPORT_REASONS,
        VALID_REASON_VALUES: VALID_REASON_VALUES,
        DEFAULT_TTL_MS: DEFAULT_TTL_MS,
        MAX_DESCRIPTION_LENGTH: MAX_DESCRIPTION_LENGTH,
        getReportDraftKey: getReportDraftKey,
        getCsrf: getCsrf,
        createCommentReportModal: createCommentReportModal,
        open: function (params) {
            return getDefaultInstance().open(params);
        },
        close: function (isExplicitCancel) {
            return getDefaultInstance().close(isExplicitCancel);
        },
        flushDraft: function () {
            return getDefaultInstance().flushDraft();
        },
        isOpen: function () {
            return getDefaultInstance().isOpen();
        }
    };
});
