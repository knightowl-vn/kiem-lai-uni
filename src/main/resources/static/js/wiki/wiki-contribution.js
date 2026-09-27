/**
 * KiemLai Universe — Wiki Reader Contribution UX & Text-Selection Toolbar (MS-05H5)
 *
 * Responsibilities:
 * 1. Text-selection floating toolbar ([Tra cứu] & [Góp ý]) triggered strictly within .wiki-article-content.
 * 2. Immutable snapshot capture for text-selection contribution:
 *    - selectedText (NFC normalized, max 1000 chars; friendly warning if exceeded)
 *    - selectedPrefix (up to 100 normalized chars before selection)
 *    - selectedSuffix (up to 100 normalized chars after selection)
 *    - selectedHeadingAnchor (nearest preceding h2[id] or h3[id] <= 255 chars, or null; never truncated)
 *    - rect (frozen bounding client rect for positioning)
 * 3. General article contribution entry point (top utility toolbar [Góp ý]).
 * 4. Reader contribution modal lifecycle, accessible dialog markup, dynamic source inputs (0..5),
 *    character counters, validation, CSRF attachment, and in-flight guard.
 * 5. Safe submission to POST /wiki/articles/{articleId}/contributions with redirect inspection
 *    and exact H4 payload verification (data.contributionId, alreadySubmitted: false for 201, true for 200).
 * 6. Wiki-owned contextual lookup endpoint GET /wiki/contextual-lookup?q=... (zero Novel HTTP dependency).
 * 7. Invalidation of in-flight submissions when modal is closed or reopened.
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.WikiContribution = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.WikiContribution = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const MAX_SELECTED_TEXT_LENGTH = 1000;
    const MAX_LOOKUP_QUERY_LENGTH = 100;
    const MAX_SELECTED_PREFIX_LENGTH = 100;
    const MAX_SELECTED_SUFFIX_LENGTH = 100;
    const MAX_SELECTED_HEADING_ANCHOR_LENGTH = 255;
    const MIN_MESSAGE_LENGTH = 20;
    const MAX_MESSAGE_LENGTH = 5000;
    const MAX_SOURCES_COUNT = 5;
    const MAX_SOURCE_LENGTH = 2000;

    const THANK_YOU_MESSAGE = 'Cảm ơn bạn đã đóng góp! Đóng góp của bạn đã được gửi tới đội ngũ biên tập.';
    const LONG_SELECTION_WARNING = 'Vui lòng chọn đoạn văn bản ngắn hơn (tối đa 1000 ký tự).';
    const LOOKUP_LIMIT_WARNING = 'Tra cứu Wiki chỉ hỗ trợ tối đa 100 ký tự.';

    const VALID_CONTRIBUTION_TYPES = [
        'INCORRECT_INFORMATION',
        'MISSING_INFORMATION',
        'OUTDATED_INFORMATION',
        'WORDING',
        'SOURCE_REFERENCE',
        'OTHER'
    ];

    const VALID_CONTEXT_TYPES = ['GENERAL', 'TEXT_SELECTION'];

    const CONTRIBUTION_TYPES = [
        { value: 'INCORRECT_INFORMATION', label: 'Thông tin sai' },
        { value: 'MISSING_INFORMATION', label: 'Thiếu thông tin' },
        { value: 'OUTDATED_INFORMATION', label: 'Thông tin đã lỗi thời' },
        { value: 'WORDING', label: 'Câu chữ / cách diễn đạt' },
        { value: 'SOURCE_REFERENCE', label: 'Nguồn tham khảo' },
        { value: 'OTHER', label: 'Khác' }
    ];

    const ARTICLE_TYPE_LABELS = {
        CHARACTER: 'Nhân vật',
        REALM: 'Cảnh giới',
        CULTIVATION_PATH: 'Đạo thống',
        FACTION: 'Thế lực',
        ITEM: 'Bảo vật',
        TECHNIQUE: 'Công pháp',
        LOCATION: 'Địa danh',
        WORLD: 'Thế giới',
        TIMELINE_EVENT: 'Sự kiện'
    };

    const ARTICLE_TYPE_PATHS = {
        CHARACTER: 'character',
        REALM: 'realm',
        CULTIVATION_PATH: 'cultivation-path',
        FACTION: 'faction',
        ITEM: 'item',
        TECHNIQUE: 'technique',
        LOCATION: 'location',
        WORLD: 'world',
        TIMELINE_EVENT: 'timeline-event'
    };

    /**
     * Normalizes text for selection snapshot (NFC, trimmed, consecutive whitespace collapsed).
     */
    function normalizeAnchorText(text) {
        if (text === null || text === undefined) {
            return null;
        }
        const trimmed = String(text).normalize('NFC').trim();
        if (trimmed.length === 0) {
            return null;
        }
        return trimmed.replace(/\s+/g, ' ');
    }

    /**
     * Normalizes heading anchor string (trimmed, max 255 chars, or null).
     * If trimmed length exceeds 255 chars, returns null (never truncated).
     */
    function normalizeHeadingAnchor(anchor) {
        if (anchor === null || anchor === undefined) {
            return null;
        }
        const trimmed = String(anchor).trim();
        if (trimmed.length === 0 || trimmed.length > MAX_SELECTED_HEADING_ANCHOR_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * Validates contribution message (20..5000 characters after outer trim).
     * Internal newlines and whitespace are preserved.
     */
    function validateMessage(message) {
        if (message === null || message === undefined) {
            return {
                valid: false,
                length: 0,
                error: 'Nội dung đóng góp không được để trống.'
            };
        }
        const raw = String(message);
        const trimmed = raw.trim();
        const length = trimmed.length;

        if (length === 0) {
            return {
                valid: false,
                length: 0,
                error: 'Nội dung đóng góp không được để trống.'
            };
        }
        if (length < MIN_MESSAGE_LENGTH) {
            return {
                valid: false,
                length: length,
                error: `Nội dung đóng góp phải có tối thiểu ${MIN_MESSAGE_LENGTH} ký tự (hiện có ${length} ký tự).`
            };
        }
        if (length > MAX_MESSAGE_LENGTH) {
            return {
                valid: false,
                length: length,
                error: `Nội dung đóng góp không được vượt quá ${MAX_MESSAGE_LENGTH} ký tự (hiện có ${length} ký tự).`
            };
        }
        return {
            valid: true,
            length: length,
            trimmedMessage: trimmed
        };
    }

    /**
     * Validates selected text length for text-selection contributions.
     */
    function validateSelectedText(text) {
        const norm = normalizeAnchorText(text);
        if (!norm) {
            return {
                valid: false,
                length: 0,
                error: 'Đoạn văn bản trích dẫn không được để trống đối với đóng góp theo đoạn.'
            };
        }
        if (norm.length > MAX_SELECTED_TEXT_LENGTH) {
            return {
                valid: false,
                length: norm.length,
                error: LONG_SELECTION_WARNING
            };
        }
        return {
            valid: true,
            length: norm.length,
            normalizedText: norm
        };
    }

    /**
     * Validates source inputs list (0..5 inputs, max 2000 chars each, empty rows omitted).
     */
    function validateSources(rawSources) {
        if (!Array.isArray(rawSources)) {
            return { valid: true, sources: [] };
        }
        const cleanSources = [];
        for (const item of rawSources) {
            if (item === null || item === undefined) continue;
            const str = String(item).trim();
            if (str.length === 0) continue;
            if (str.length > MAX_SOURCE_LENGTH) {
                return {
                    valid: false,
                    sources: cleanSources,
                    error: `Độ dài mỗi nguồn tham khảo không được vượt quá ${MAX_SOURCE_LENGTH} ký tự.`
                };
            }
            cleanSources.push(str);
        }
        if (cleanSources.length > MAX_SOURCES_COUNT) {
            return {
                valid: false,
                sources: cleanSources,
                error: `Mỗi đóng góp chỉ được chứa tối đa ${MAX_SOURCES_COUNT} nguồn tham khảo.`
            };
        }
        return {
            valid: true,
            sources: cleanSources
        };
    }

    /**
     * Builds standard JSON payload for submission.
     * Fails closed: validates contextType, articleContentVersion, and contributionType.
     * Never silently defaults missing or invalid fields.
     */
    function buildContributionPayload(params) {
        if (!params) {
            throw new Error('Tham số không hợp lệ.');
        }

        if (!VALID_CONTEXT_TYPES.includes(params.contextType)) {
            throw new Error('Loại ngữ cảnh không hợp lệ: ' + params.contextType);
        }

        const version = Number(params.articleContentVersion);
        if (!Number.isInteger(version) || version < 1) {
            throw new Error('articleContentVersion không hợp lệ. Phải là số nguyên dương >= 1.');
        }

        if (!VALID_CONTRIBUTION_TYPES.includes(params.contributionType)) {
            throw new Error('Loại góp ý không hợp lệ: ' + params.contributionType);
        }

        const msgRes = validateMessage(params.message);
        if (!msgRes.valid) {
            throw new Error(msgRes.error);
        }

        const srcRes = validateSources(params.sources);
        if (!srcRes.valid) {
            throw new Error(srcRes.error);
        }

        const payload = {
            articleContentVersion: version,
            contextType: params.contextType,
            contributionType: params.contributionType,
            message: msgRes.trimmedMessage,
            selectedText: null,
            selectedPrefix: null,
            selectedSuffix: null,
            selectedHeadingAnchor: null,
            sources: srcRes.sources
        };

        if (params.contextType === 'TEXT_SELECTION') {
            const textRes = validateSelectedText(params.selectedText);
            if (!textRes.valid) {
                throw new Error(textRes.error);
            }
            payload.selectedText = textRes.normalizedText;

            const normPrefix = normalizeAnchorText(params.selectedPrefix);
            payload.selectedPrefix = normPrefix && normPrefix.length > MAX_SELECTED_PREFIX_LENGTH
                ? normPrefix.slice(-MAX_SELECTED_PREFIX_LENGTH)
                : normPrefix;

            const normSuffix = normalizeAnchorText(params.selectedSuffix);
            payload.selectedSuffix = normSuffix && normSuffix.length > MAX_SELECTED_SUFFIX_LENGTH
                ? normSuffix.slice(0, MAX_SELECTED_SUFFIX_LENGTH)
                : normSuffix;

            payload.selectedHeadingAnchor = normalizeHeadingAnchor(params.selectedHeadingAnchor);
        }

        return payload;
    }

    /**
     * Validates whether a redirect URL is an allowed same-origin security destination.
     * Navigation is permitted only when:
     * 1. URL parses safely relative to the current page.
     * 2. Origin strictly matches the current application origin.
     * 3. Pathname is exactly '/login' or '/access-denied' (query strings/fragments allowed).
     */
    function getValidSecurityRedirectUrl(targetUrl) {
        if (!targetUrl || typeof targetUrl !== 'string') {
            return null;
        }

        try {
            const currentOrigin = (typeof window !== 'undefined' && window.location && window.location.origin)
                ? window.location.origin
                : (typeof window !== 'undefined' && window.location && window.location.href ? new URL(window.location.href).origin : 'http://localhost');

            const base = (typeof window !== 'undefined' && window.location && window.location.href)
                ? window.location.href
                : 'http://localhost/';

            const parsed = new URL(targetUrl, base);

            if (parsed.origin !== currentOrigin) {
                return null;
            }

            if (parsed.pathname === '/login' || parsed.pathname === '/access-denied') {
                return parsed.href;
            }

            return null;
        } catch (e) {
            return null;
        }
    }

    /**
     * Pure helper to classify submission response using exact H4 contract semantics.
     *
     * Order of operations:
     * A. Security redirect inspection (checks same-origin /login and /access-denied).
     * B. HTTP status check.
     * C. Status 200/201: requires application/json and valid H4 response body with contributionId
     *    and alreadySubmitted (false for 201, true for 200). Bare status or only 'id' fails.
     * D. Status 400: safe validation message; body.message used if valid string, but JSON is not required.
     * E. Status 401: login redirection.
     * F. Status 403: safe session/auth message.
     * G. Status 404: article unavailable message (JSON not required).
     * H. Status 5xx: generic server message (JSON not required).
     */
    function classifySubmissionResponse(options) {
        const opts = options || {};
        const status = typeof opts.status === 'number' ? opts.status : 0;
        const redirected = Boolean(opts.redirected);
        const url = opts.url || '';
        const contentType = String(opts.contentType || '');
        const body = opts.body;

        // A. Security redirect handling
        if (redirected && url) {
            const validRedirect = getValidSecurityRedirectUrl(url);
            if (validRedirect) {
                const parsed = new URL(validRedirect);
                if (parsed.pathname === '/login') {
                    return {
                        type: 'REDIRECT_LOGIN',
                        status: status,
                        url: validRedirect
                    };
                }
                if (parsed.pathname === '/access-denied') {
                    return {
                        type: 'ACCESS_DENIED',
                        status: status,
                        message: 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền thực hiện. Vui lòng đăng nhập lại.'
                    };
                }
            }
            return {
                type: 'ERROR',
                status: status,
                message: 'Yêu cầu chuyển hướng không hợp lệ từ máy chủ.'
            };
        }

        // B. Inspect HTTP status

        // C. 201 Created: requires application/json and exact H4 payload (contributionId present, alreadySubmitted === false)
        if (status === 201) {
            if (!contentType.includes('application/json')) {
                return {
                    type: 'ERROR',
                    status: 201,
                    message: 'Phản hồi từ máy chủ không đúng định dạng. Vui lòng thử lại sau.'
                };
            }
            if (
                body &&
                typeof body === 'object' &&
                typeof body.contributionId === 'string' &&
                body.contributionId.trim().length > 0 &&
                body.alreadySubmitted === false
            ) {
                return {
                    type: 'SUCCESS',
                    status: 201,
                    isDuplicate: false,
                    contributionId: body.contributionId.trim(),
                    thankYouMessage: THANK_YOU_MESSAGE
                };
            }
            return {
                type: 'ERROR',
                status: 201,
                message: 'Phản hồi từ máy chủ không hợp lệ.'
            };
        }

        // 200 OK: requires application/json and exact H4 payload (contributionId present, alreadySubmitted === true)
        if (status === 200) {
            if (!contentType.includes('application/json')) {
                return {
                    type: 'ERROR',
                    status: 200,
                    message: 'Phản hồi từ máy chủ không đúng định dạng. Vui lòng thử lại sau.'
                };
            }
            if (
                body &&
                typeof body === 'object' &&
                typeof body.contributionId === 'string' &&
                body.contributionId.trim().length > 0 &&
                body.alreadySubmitted === true
            ) {
                return {
                    type: 'SUCCESS',
                    status: 200,
                    isDuplicate: true,
                    contributionId: body.contributionId.trim(),
                    thankYouMessage: THANK_YOU_MESSAGE
                };
            }
            return {
                type: 'ERROR',
                status: 200,
                message: 'Phản hồi từ máy chủ không hợp lệ.'
            };
        }

        // D. 400 Bad Request: safe validation message (JSON body optional)
        if (status === 400) {
            let msg = 'Dữ liệu đóng góp không hợp lệ. Vui lòng kiểm tra lại.';
            if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim().length > 0) {
                msg = body.message.trim();
            }
            return {
                type: 'ERROR',
                status: 400,
                message: msg
            };
        }

        // E. 401 Unauthorized: trusted login handling
        if (status === 401) {
            return {
                type: 'REDIRECT_LOGIN',
                status: 401
            };
        }

        // F. 403 Forbidden: safe security/session message
        if (status === 403) {
            return {
                type: 'ERROR',
                status: 403,
                message: 'Bạn cần đăng nhập để gửi đóng góp ý kiến.'
            };
        }

        // G. 404 Not Found: article unavailable message (JSON not required)
        if (status === 404) {
            return {
                type: 'ERROR',
                status: 404,
                message: 'Bài viết không tồn tại hoặc chưa xuất bản.'
            };
        }

        // H. 429 Too Many Requests: friendly rate-limiting message
        if (status === 429) {
            let msg = 'Bạn đang gửi đóng góp quá nhanh. Vui lòng thử lại sau ít phút.';
            if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim().length > 0) {
                msg = body.message.trim();
            }
            return {
                type: 'RATE_LIMITED',
                status: 429,
                message: msg
            };
        }

        // I. 5xx: generic server message (JSON not required)
        if (status >= 500) {
            return {
                type: 'ERROR',
                status: status,
                message: 'Có lỗi xảy ra từ máy chủ. Vui lòng thử lại sau.'
            };
        }

        return {
            type: 'ERROR',
            status: status,
            message: `Không thể gửi đóng góp (mã lỗi: ${status}).`
        };
    }

    /**
     * Finds nearest preceding heading (h2[id], h3[id]) before startNode inside articleContentEl.
     */
    function findPrecedingHeadingAnchor(articleContentEl, startNode) {
        if (!articleContentEl || !startNode || typeof articleContentEl.querySelectorAll !== 'function') {
            return null;
        }
        const headings = Array.from(articleContentEl.querySelectorAll('h2[id], h3[id]'));
        let nearest = null;
        for (const h of headings) {
            try {
                const pos = h.compareDocumentPosition(startNode);
                if ((pos & Node.DOCUMENT_POSITION_FOLLOWING) || h.contains(startNode)) {
                    nearest = h;
                } else {
                    break;
                }
            } catch (e) {
                // If node comparison fails, continue
            }
        }
        if (!nearest || !nearest.id) {
            return null;
        }
        return normalizeHeadingAnchor(nearest.id);
    }

    /**
     * Extracts full snapshot from active selection within articleContentEl.
     * Returns an immutable frozen snapshot including frozen bounding client rect.
     */
    function extractSelectionSnapshot(selection, articleContentEl) {
        if (!selection || selection.isCollapsed || !articleContentEl) {
            return null;
        }
        if (selection.rangeCount === 0) {
            return null;
        }
        const range = selection.getRangeAt(0);
        if (!range) {
            return null;
        }

        // Validate selection is strictly inside articleContentEl
        if (!articleContentEl.contains(range.startContainer) || !articleContentEl.contains(range.endContainer)) {
            return null;
        }

        const rawText = range.toString();
        const normalizedSelectedText = normalizeAnchorText(rawText);
        if (!normalizedSelectedText) {
            return null;
        }

        let selectedPrefix = null;
        let selectedSuffix = null;

        // Prefix extraction
        try {
            if (typeof document !== 'undefined' && typeof document.createRange === 'function') {
                const preRange = document.createRange();
                preRange.selectNodeContents(articleContentEl);
                preRange.setEnd(range.startContainer, range.startOffset);
                const preText = preRange.toString();
                const normPre = normalizeAnchorText(preText);
                if (normPre) {
                    selectedPrefix = normPre.length > MAX_SELECTED_PREFIX_LENGTH
                        ? normPre.slice(-MAX_SELECTED_PREFIX_LENGTH)
                        : normPre;
                }
            }
        } catch (e) {
            selectedPrefix = null;
        }

        // Suffix extraction
        try {
            if (typeof document !== 'undefined' && typeof document.createRange === 'function') {
                const postRange = document.createRange();
                postRange.selectNodeContents(articleContentEl);
                postRange.setStart(range.endContainer, range.endOffset);
                const postText = postRange.toString();
                const normPost = normalizeAnchorText(postText);
                if (normPost) {
                    selectedSuffix = normPost.length > MAX_SELECTED_SUFFIX_LENGTH
                        ? normPost.slice(0, MAX_SELECTED_SUFFIX_LENGTH)
                        : normPost;
                }
            }
        } catch (e) {
            selectedSuffix = null;
        }

        const selectedHeadingAnchor = findPrecedingHeadingAnchor(articleContentEl, range.startContainer);

        let clientRect = { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
        try {
            const r = range.getBoundingClientRect();
            if (r) {
                clientRect = {
                    left: r.left,
                    top: r.top,
                    right: r.right,
                    bottom: r.bottom,
                    width: r.width,
                    height: r.height
                };
            }
        } catch (e) {
            // ignore
        }

        return Object.freeze({
            rawText: rawText,
            selectedText: normalizedSelectedText,
            selectedPrefix: selectedPrefix,
            selectedSuffix: selectedSuffix,
            selectedHeadingAnchor: selectedHeadingAnchor,
            isTooLong: normalizedSelectedText.length > MAX_SELECTED_TEXT_LENGTH,
            isTooLongForLookup: normalizedSelectedText.length > MAX_LOOKUP_QUERY_LENGTH,
            rect: Object.freeze(clientRect)
        });
    }

    /**
     * Reads CSRF token and header name from document <meta> tags or container dataset.
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

        if (token && headerName) {
            return { token, headerName };
        }
        return null;
    }

    /**
     * Resolves article URL path for public wiki navigation.
     */
    function getArticleUrl(articleType, slug) {
        const typePath = (articleType && ARTICLE_TYPE_PATHS[articleType])
            ? ARTICLE_TYPE_PATHS[articleType]
            : (articleType ? articleType.toLowerCase().replace(/_/g, '-') : 'article');
        return `/wiki/${encodeURIComponent(typePath)}/${encodeURIComponent(slug)}`;
    }

    // =========================================================
    // CLIENT RUNTIME BINDING
    // =========================================================

    let activeSnapshot = null;
    let currentContextMode = 'GENERAL';
    let selectionTimeout = null;
    let toastTimeout = null;
    let currentLookupAbortController = null;
    let currentModalGeneration = 0;
    let lastFocusedElement = null;

    function getModalGeneration() {
        return currentModalGeneration;
    }

    function getActiveSnapshot() {
        return activeSnapshot;
    }

    function getCurrentContextMode() {
        return currentContextMode;
    }

    /**
     * Displays a restrained inline toast notification. Never uses window.alert.
     */
    function showToast(message) {
        if (typeof document === 'undefined') return;

        let toast = document.getElementById('wikiContributionToast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'wikiContributionToast';
            toast.className = 'wiki-contribution-toast';
            toast.setAttribute('role', 'alert');
            toast.setAttribute('aria-live', 'assertive');
            document.body.appendChild(toast);
        }
        toast.textContent = message;
        toast.classList.add('is-visible');

        if (toastTimeout) {
            clearTimeout(toastTimeout);
        }
        toastTimeout = setTimeout(() => {
            toast.classList.remove('is-visible');
        }, 3000);
    }

    function initWikiContribution() {
        if (typeof document === 'undefined') return;

        const articleContentEl = document.querySelector('.wiki-article-content');
        const topBtn = document.getElementById('wikiTopContributionBtn');

        if (!articleContentEl && !topBtn) {
            return;
        }

        // 1. Top toolbar [Góp ý] button
        if (topBtn) {
            topBtn.addEventListener('click', function (e) {
                e.preventDefault();
                const isAuthenticated = topBtn.dataset.authenticated === 'true';
                if (!isAuthenticated) {
                    const loginUrl = topBtn.dataset.loginUrl || '/login';
                    window.location.href = loginUrl;
                    return;
                }
                openModal('GENERAL');
            });
        }

        // 2. Selection listeners on document for .wiki-article-content
        if (articleContentEl) {
            document.addEventListener('selectionchange', handleSelectionChange);
            document.addEventListener('mouseup', handlePointerEnd);
            document.addEventListener('touchend', handlePointerEnd);

            window.addEventListener('scroll', handleWindowScroll, { passive: true });
            window.addEventListener('resize', handleWindowResize, { passive: true });
            document.addEventListener('keydown', handleKeyDown);
            document.addEventListener('mousedown', handleOutsideClick);
        }

        // 3. Selection toolbar buttons
        const selectionLookupBtn = document.getElementById('wikiSelectionLookupBtn');
        const selectionContributeBtn = document.getElementById('wikiSelectionContributeBtn');

        if (selectionContributeBtn) {
            selectionContributeBtn.addEventListener('click', onSelectionContributeClick);
        }
        if (selectionLookupBtn) {
            selectionLookupBtn.addEventListener('click', onSelectionLookupClick);
        }

        // 4. Modal event listeners
        bindModalEvents();

        // 5. Lookup container close listeners
        bindLookupEvents();
    }

    function handleSelectionChange() {
        if (selectionTimeout) {
            clearTimeout(selectionTimeout);
        }
        selectionTimeout = setTimeout(evaluateSelection, 80);
    }

    function handlePointerEnd() {
        setTimeout(evaluateSelection, 50);
    }

    function evaluateSelection() {
        const selection = window.getSelection();
        const articleContentEl = document.querySelector('.wiki-article-content');
        const toolbar = document.getElementById('wikiSelectionToolbar');

        if (!selection || selection.isCollapsed || !articleContentEl || !toolbar) {
            hideSelectionToolbar();
            return;
        }

        // Validate selection range inside articleContentEl
        if (!articleContentEl.contains(selection.anchorNode) || !articleContentEl.contains(selection.focusNode)) {
            hideSelectionToolbar();
            return;
        }

        const snapshot = extractSelectionSnapshot(selection, articleContentEl);
        if (!snapshot || !snapshot.selectedText) {
            hideSelectionToolbar();
            return;
        }

        activeSnapshot = snapshot;

        if (snapshot.rect.width === 0 && snapshot.rect.height === 0) {
            hideSelectionToolbar();
            return;
        }

        positionSelectionToolbar(toolbar, snapshot);
    }

    function positionSelectionToolbar(toolbar, snapshot) {
        if (!toolbar || !snapshot || !snapshot.rect) return;

        const rect = snapshot.rect;
        const lookupBtn = document.getElementById('wikiSelectionLookupBtn');
        const contributeBtn = document.getElementById('wikiSelectionContributeBtn');

        // Apply contract separation:
        // Selection 1..100 chars: Tra cứu enabled
        // Selection 101..1000 chars: Tra cứu disabled with hint, Góp ý enabled
        // Selection > 1000 chars: Both disabled or warning
        if (lookupBtn) {
            if (snapshot.isTooLongForLookup) {
                lookupBtn.setAttribute('disabled', '');
                lookupBtn.setAttribute('title', LOOKUP_LIMIT_WARNING);
                lookupBtn.classList.add('is-disabled');
            } else {
                lookupBtn.removeAttribute('disabled');
                lookupBtn.setAttribute('title', 'Tra cứu thông tin Wiki');
                lookupBtn.classList.remove('is-disabled');
            }
        }

        if (contributeBtn) {
            if (snapshot.isTooLong) {
                contributeBtn.setAttribute('disabled', '');
                contributeBtn.setAttribute('title', LONG_SELECTION_WARNING);
                contributeBtn.classList.add('is-disabled');
            } else {
                contributeBtn.removeAttribute('disabled');
                contributeBtn.setAttribute('title', 'Góp ý hoặc báo lỗi cho đoạn văn bản đã chọn');
                contributeBtn.classList.remove('is-disabled');
            }
        }

        toolbar.classList.add('is-visible');
        toolbar.removeAttribute('hidden');

        if (window.innerWidth <= 640) {
            toolbar.style.left = '';
            toolbar.style.top = '';
            return;
        }

        const barWidth = toolbar.offsetWidth || 180;
        const barHeight = toolbar.offsetHeight || 36;
        const gap = 8;

        let left = rect.left + rect.width / 2 - barWidth / 2;
        let top = rect.top - barHeight - gap;

        if (top < 10) {
            top = rect.bottom + gap;
        }

        const padding = 12;
        const maxLeft = window.innerWidth - barWidth - padding;
        if (left < padding) {
            left = padding;
        } else if (left > maxLeft) {
            left = maxLeft;
        }

        toolbar.style.left = `${Math.round(left)}px`;
        toolbar.style.top = `${Math.round(top)}px`;
    }

    function hideSelectionToolbar() {
        const toolbar = document.getElementById('wikiSelectionToolbar');
        if (toolbar) {
            toolbar.classList.remove('is-visible');
            toolbar.setAttribute('hidden', '');
        }
    }

    function handleWindowScroll() {
        hideSelectionToolbar();
        const lookupContainer = document.getElementById('wikiContextualLookupContainer');
        if (lookupContainer && lookupContainer.classList.contains('is-visible') && window.innerWidth > 640) {
            closeLookupContainer();
        }
    }

    function handleWindowResize() {
        hideSelectionToolbar();
    }

    function handleKeyDown(e) {
        if (e.key === 'Escape') {
            hideSelectionToolbar();
            closeLookupContainer();
            closeModal();
            return;
        }
        trapModalFocus(e);
    }

    function trapModalFocus(e) {
        if (e.key !== 'Tab') return;
        const modal = document.getElementById('wikiContributionModal');
        if (!modal || modal.hasAttribute('hidden')) return;

        const focusable = modal.querySelectorAll(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        if (focusable.length === 0) return;

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (e.shiftKey) {
            if (document.activeElement === first) {
                e.preventDefault();
                last.focus();
            }
        } else {
            if (document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
    }

    function handleOutsideClick(e) {
        const toolbar = document.getElementById('wikiSelectionToolbar');
        if (toolbar && !toolbar.contains(e.target)) {
            hideSelectionToolbar();
        }
        const lookupContainer = document.getElementById('wikiContextualLookupContainer');
        if (lookupContainer && lookupContainer.classList.contains('is-visible') && window.innerWidth > 640) {
            if (!lookupContainer.contains(e.target) && (!toolbar || !toolbar.contains(e.target))) {
                closeLookupContainer();
            }
        }
    }

    function onSelectionContributeClick(e) {
        e.preventDefault();
        e.stopPropagation();

        const topBtn = document.getElementById('wikiTopContributionBtn');
        const isAuthenticated = topBtn && topBtn.dataset.authenticated === 'true';
        if (!isAuthenticated) {
            const loginUrl = (topBtn && topBtn.dataset.loginUrl) || '/login';
            window.location.href = loginUrl;
            return;
        }

        if (!activeSnapshot) {
            return;
        }

        if (activeSnapshot.isTooLong) {
            showToast(LONG_SELECTION_WARNING);
            return;
        }

        const snapshot = activeSnapshot;
        hideSelectionToolbar();
        openModal('TEXT_SELECTION', snapshot);
    }

    function onSelectionLookupClick(e) {
        e.preventDefault();
        e.stopPropagation();

        if (!activeSnapshot || !activeSnapshot.selectedText) {
            return;
        }

        if (activeSnapshot.isTooLongForLookup) {
            showToast(LOOKUP_LIMIT_WARNING);
            return;
        }

        const snapshot = activeSnapshot;
        hideSelectionToolbar();
        openLookupContainer(snapshot.rect);
        executeLookup(snapshot.selectedText);
    }

    // =========================================================
    // MODAL LIFECYCLE & FORM INTERACTION
    // =========================================================

    function bindModalEvents() {
        const modal = document.getElementById('wikiContributionModal');
        if (!modal) return;

        const closeBtn = document.getElementById('wikiContributionCloseBtn');
        const cancelBtn = document.getElementById('wikiContributionCancelBtn');
        const backdrop = document.getElementById('wikiContributionBackdrop');
        const form = document.getElementById('wikiContributionForm');
        const messageInput = document.getElementById('wikiContributionMessageInput');
        const addSourceBtn = document.getElementById('wikiContributionAddSourceBtn');

        if (closeBtn) closeBtn.addEventListener('click', closeModal);
        if (cancelBtn) cancelBtn.addEventListener('click', closeModal);
        if (backdrop) backdrop.addEventListener('click', closeModal);

        if (messageInput) {
            messageInput.addEventListener('input', updateMessageCounter);
        }

        if (addSourceBtn) {
            addSourceBtn.addEventListener('click', function () {
                addSourceRow('');
            });
        }

        if (form) {
            form.addEventListener('submit', handleFormSubmit);
        }
    }

    /**
     * Opens modal dialog with explicit mode and fail-closed validation.
     * GENERAL: activeSnapshot is set to null.
     * TEXT_SELECTION: requires a valid snapshot object; activeSnapshot is set to snapshot.
     * Unknown mode: fails closed, returns false, does not open modal.
     */
    function openModal(mode, snapshot) {
        const modal = document.getElementById('wikiContributionModal');
        if (!modal) return false;

        if (mode === 'GENERAL') {
            currentContextMode = 'GENERAL';
            activeSnapshot = null;
        } else if (mode === 'TEXT_SELECTION') {
            if (!snapshot || !snapshot.selectedText) {
                return false;
            }
            currentContextMode = 'TEXT_SELECTION';
            activeSnapshot = snapshot;
        } else {
            // Unknown mode fails closed
            return false;
        }

        currentModalGeneration++;
        lastFocusedElement = (typeof document !== 'undefined' ? document.activeElement : null);

        const previewContainer = document.getElementById('wikiContributionSelectionPreview');
        const selectedTextEl = document.getElementById('wikiContributionSelectedText');
        const anchorHintEl = document.getElementById('wikiContributionAnchorHint');
        const messageInput = document.getElementById('wikiContributionMessageInput');
        const typeSelect = document.getElementById('wikiContributionTypeSelect');
        const sourcesList = document.getElementById('wikiContributionSourcesList');
        const submitBtn = document.getElementById('wikiContributionSubmitBtn');

        // Fresh state: reset all input fields and counters
        if (messageInput) {
            messageInput.value = '';
        }
        if (typeSelect) {
            typeSelect.value = '';
        }
        if (sourcesList) {
            sourcesList.innerHTML = '';
        }
        resetSubmitBtn(submitBtn);
        clearStatus();
        updateSourceCounter();
        updateMessageCounter();

        // Selection preview state
        if (currentContextMode === 'TEXT_SELECTION') {
            if (previewContainer) previewContainer.removeAttribute('hidden');
            if (selectedTextEl) selectedTextEl.textContent = `"${activeSnapshot.selectedText}"`;
            if (anchorHintEl) {
                if (activeSnapshot.selectedHeadingAnchor) {
                    anchorHintEl.textContent = `Mục: #${activeSnapshot.selectedHeadingAnchor}`;
                    anchorHintEl.removeAttribute('hidden');
                } else {
                    anchorHintEl.setAttribute('hidden', '');
                }
            }
        } else {
            if (previewContainer) previewContainer.setAttribute('hidden', '');
            if (selectedTextEl) selectedTextEl.textContent = '';
            if (anchorHintEl) anchorHintEl.setAttribute('hidden', '');
        }

        // Show modal dialog
        modal.removeAttribute('hidden');
        document.body.style.overflow = 'hidden';

        // Accessibility: focus initial field
        if (typeSelect) {
            typeSelect.focus();
        }
        return true;
    }

    /**
     * Closes modal dialog.
     * When closing an open modal, increments currentModalGeneration to invalidate in-flight submissions.
     * Does NOT increment generation if called while already hidden.
     */
    function closeModal() {
        const modal = document.getElementById('wikiContributionModal');
        if (!modal) return false;

        // If modal is already hidden, do not advance generation unnecessarily
        if (modal.hasAttribute('hidden')) {
            return false;
        }

        currentModalGeneration++;

        modal.setAttribute('hidden', '');
        document.body.style.overflow = '';
        clearStatus();

        // Accessibility: restore focus
        if (lastFocusedElement && typeof lastFocusedElement.focus === 'function') {
            if (typeof document !== 'undefined' && document.contains(lastFocusedElement)) {
                lastFocusedElement.focus();
            }
        }
        lastFocusedElement = null;
        return true;
    }

    function updateMessageCounter() {
        const messageInput = document.getElementById('wikiContributionMessageInput');
        const counter = document.getElementById('wikiContributionMessageCounter');
        if (!messageInput || !counter) return;

        const length = (messageInput.value || '').trim().length;
        counter.textContent = `${length}/${MAX_MESSAGE_LENGTH}`;
        if (length > MAX_MESSAGE_LENGTH) {
            counter.style.color = '#ef4444';
        } else {
            counter.style.color = '';
        }
    }

    function updateSourceCounter() {
        const sourcesList = document.getElementById('wikiContributionSourcesList');
        const counter = document.getElementById('wikiContributionSourceCounter');
        const addBtn = document.getElementById('wikiContributionAddSourceBtn');
        if (!sourcesList) return;

        const count = sourcesList.querySelectorAll('.wiki-contribution-source-row').length;
        if (counter) {
            counter.textContent = `${count}/${MAX_SOURCES_COUNT}`;
        }
        if (addBtn) {
            if (count >= MAX_SOURCES_COUNT) {
                addBtn.setAttribute('disabled', '');
            } else {
                addBtn.removeAttribute('disabled');
            }
        }
    }

    function addSourceRow(initialValue) {
        const sourcesList = document.getElementById('wikiContributionSourcesList');
        if (!sourcesList) return;

        const currentCount = sourcesList.querySelectorAll('.wiki-contribution-source-row').length;
        if (currentCount >= MAX_SOURCES_COUNT) {
            return;
        }

        const row = document.createElement('div');
        row.className = 'wiki-contribution-source-row';

        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'wiki-contribution-source-input';
        input.placeholder = 'https://... hoặc /wiki/... hoặc /novel/...';
        input.maxLength = MAX_SOURCE_LENGTH;
        input.value = initialValue || '';

        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'wiki-contribution-remove-source-btn';
        removeBtn.setAttribute('aria-label', 'Xóa nguồn tham khảo này');
        removeBtn.textContent = '✕';
        removeBtn.addEventListener('click', function () {
            row.remove();
            updateSourceCounter();
        });

        row.appendChild(input);
        row.appendChild(removeBtn);
        sourcesList.appendChild(row);

        updateSourceCounter();
        input.focus();
    }

    function getCollectedSources() {
        const sourcesList = document.getElementById('wikiContributionSourcesList');
        if (!sourcesList) return [];

        const inputs = sourcesList.querySelectorAll('.wiki-contribution-source-input');
        const sources = [];
        inputs.forEach(inp => {
            const val = inp.value.trim();
            if (val) {
                sources.push(val);
            }
        });
        return sources;
    }

    function showStatus(message, isError) {
        const statusEl = document.getElementById('wikiContributionStatus');
        if (!statusEl) return;

        statusEl.textContent = message;
        statusEl.removeAttribute('hidden');
        if (isError) {
            statusEl.className = 'wiki-contribution-status wiki-contribution-status--error';
        } else {
            statusEl.className = 'wiki-contribution-status wiki-contribution-status--success';
        }
    }

    function clearStatus() {
        const statusEl = document.getElementById('wikiContributionStatus');
        if (!statusEl) return;
        statusEl.setAttribute('hidden', '');
        statusEl.textContent = '';
        statusEl.className = 'wiki-contribution-status';
    }

    let isSubmitting = false;

    async function handleFormSubmit(e) {
        e.preventDefault();
        if (isSubmitting) {
            return;
        }
        clearStatus();

        const submissionGeneration = currentModalGeneration;

        const form = document.getElementById('wikiContributionForm');
        const submitBtn = document.getElementById('wikiContributionSubmitBtn');
        const typeSelect = document.getElementById('wikiContributionTypeSelect');
        const messageInput = document.getElementById('wikiContributionMessageInput');
        const topBtn = document.getElementById('wikiTopContributionBtn');

        const articleContentEl = document.querySelector('.wiki-article-content');
        const articleId = (topBtn && topBtn.dataset.articleId)
            || (articleContentEl && articleContentEl.dataset.articleId)
            || '';

        if (!articleId) {
            showStatus('Không xác định được bài viết. Vui lòng tải lại trang.', true);
            return;
        }

        // Fail-closed articleContentVersion check: strictly integer >= 1
        const rawVersion = (topBtn && topBtn.dataset.contentVersion)
            || (articleContentEl && articleContentEl.dataset.contentVersion);
        const parsedVersion = Number(rawVersion);
        if (!rawVersion || !Number.isInteger(parsedVersion) || parsedVersion < 1) {
            showStatus('Phiên bản bài viết không hợp lệ. Vui lòng tải lại trang.', true);
            return;
        }

        // Fail-closed authentication check
        const isAuthenticated = topBtn && topBtn.dataset.authenticated === 'true';
        const loginUrl = (topBtn && topBtn.dataset.loginUrl) || '/login';
        if (!isAuthenticated) {
            window.location.href = loginUrl;
            return;
        }

        // Fail-closed contribution type check: must select from approved list
        const contributionType = typeSelect ? typeSelect.value : '';
        if (!contributionType || !VALID_CONTRIBUTION_TYPES.includes(contributionType)) {
            showStatus('Vui lòng chọn loại góp ý.', true);
            if (typeSelect) typeSelect.focus();
            return;
        }

        // Validate message
        const messageVal = messageInput ? messageInput.value : '';
        const messageResult = validateMessage(messageVal);
        if (!messageResult.valid) {
            showStatus(messageResult.error, true);
            if (messageInput) messageInput.focus();
            return;
        }

        // Validate sources
        const rawSources = getCollectedSources();
        const sourcesResult = validateSources(rawSources);
        if (!sourcesResult.valid) {
            showStatus(sourcesResult.error, true);
            return;
        }

        // Validate text selection snapshot if mode is TEXT_SELECTION
        if (currentContextMode === 'TEXT_SELECTION') {
            if (!activeSnapshot || !activeSnapshot.selectedText) {
                showStatus('Văn bản trích dẫn không hợp lệ. Vui lòng chọn lại.', true);
                return;
            }
            const selResult = validateSelectedText(activeSnapshot.selectedText);
            if (!selResult.valid) {
                showStatus(selResult.error, true);
                return;
            }
        }

        let payload;
        try {
            payload = buildContributionPayload({
                articleContentVersion: parsedVersion,
                contextType: currentContextMode,
                contributionType: contributionType,
                message: messageVal,
                selectedText: currentContextMode === 'TEXT_SELECTION' ? activeSnapshot.selectedText : null,
                selectedPrefix: currentContextMode === 'TEXT_SELECTION' ? activeSnapshot.selectedPrefix : null,
                selectedSuffix: currentContextMode === 'TEXT_SELECTION' ? activeSnapshot.selectedSuffix : null,
                selectedHeadingAnchor: currentContextMode === 'TEXT_SELECTION' ? activeSnapshot.selectedHeadingAnchor : null,
                sources: sourcesResult.sources
            });
        } catch (payloadErr) {
            showStatus(payloadErr.message || 'Dữ liệu đóng góp không hợp lệ.', true);
            return;
        }

        // CSRF extraction
        const csrf = getCsrf(document) || {
            token: (topBtn && topBtn.dataset.csrfToken) || '',
            headerName: (topBtn && topBtn.dataset.csrfHeader) || 'X-CSRF-TOKEN'
        };

        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        if (csrf && csrf.token) {
            headers[csrf.headerName || 'X-CSRF-TOKEN'] = csrf.token;
        }

        // In-flight UI guard
        isSubmitting = true;
        if (submitBtn) {
            submitBtn.setAttribute('disabled', '');
            submitBtn.setAttribute('aria-busy', 'true');
            submitBtn.textContent = 'Đang gửi...';
        }

        try {
            const submitUrl = `/wiki/articles/${encodeURIComponent(articleId)}/contributions`;
            const response = await fetch(submitUrl, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(payload)
            });

            // Discard stale response if modal was closed/reopened while in-flight
            if (submissionGeneration !== currentModalGeneration) {
                return;
            }

            const contentType = response.headers ? (response.headers.get('content-type') || '') : '';
            let body = null;
            if (contentType.includes('application/json')) {
                try {
                    body = await response.json();
                } catch (jsonErr) {
                    body = null;
                }
            }

            if (submissionGeneration !== currentModalGeneration) {
                return;
            }

            const classified = classifySubmissionResponse({
                status: response.status,
                redirected: response.redirected,
                url: response.url,
                contentType: contentType,
                body: body
            });

            if (submissionGeneration !== currentModalGeneration) {
                return;
            }

            if (classified.type === 'REDIRECT_LOGIN') {
                window.location.href = classified.url || loginUrl;
                return;
            }

            if (classified.type === 'ACCESS_DENIED') {
                showStatus(classified.message, true);
                resetSubmitBtn(submitBtn);
                return;
            }

            if (classified.type === 'RATE_LIMITED') {
                showStatus(classified.message, true);
                resetSubmitBtn(submitBtn);
                return;
            }

            if (classified.type === 'SUCCESS') {
                showStatus(classified.thankYouMessage, false);
                if (form) form.reset();
                const sourcesListEl = document.getElementById('wikiContributionSourcesList');
                if (sourcesListEl) sourcesListEl.innerHTML = '';
                updateSourceCounter();
                updateMessageCounter();

                setTimeout(function () {
                    if (submissionGeneration === currentModalGeneration) {
                        closeModal();
                        resetSubmitBtn(submitBtn);
                    }
                }, 1600);
                return;
            }

            showStatus(classified.message, true);
            resetSubmitBtn(submitBtn);

        } catch (err) {
            if (submissionGeneration !== currentModalGeneration) return;
            showStatus('Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại kết nối mạng.', true);
            resetSubmitBtn(submitBtn);
        }
    }

    function resetSubmitBtn(submitBtn) {
        isSubmitting = false;
        if (submitBtn) {
            submitBtn.removeAttribute('disabled');
            submitBtn.removeAttribute('aria-busy');
            submitBtn.textContent = 'Gửi góp ý';
        }
    }

    // =========================================================
    // CONTEXTUAL WIKI LOOKUP
    // =========================================================

    function bindLookupEvents() {
        const closeBtn = document.getElementById('wikiContextualLookupCloseBtn');
        const backdrop = document.getElementById('wikiContextualLookupBackdrop');

        if (closeBtn) closeBtn.addEventListener('click', closeLookupContainer);
        if (backdrop) backdrop.addEventListener('click', closeLookupContainer);
    }

    function openLookupContainer(rect) {
        const container = document.getElementById('wikiContextualLookupContainer');
        const backdrop = document.getElementById('wikiContextualLookupBackdrop');
        if (!container) return;

        if (window.innerWidth <= 640) {
            if (backdrop) backdrop.classList.add('is-visible');
            container.style.left = '';
            container.style.top = '';
            container.classList.add('is-visible');
        } else {
            if (backdrop) backdrop.classList.remove('is-visible');
            positionDesktopLookup(container, rect);
            container.classList.add('is-visible');
        }
    }

    function positionDesktopLookup(container, rect) {
        if (!container || !rect) return;

        const popoverWidth = 390;
        const popoverHeight = 360;
        const gap = 10;

        let left = rect.left + rect.width / 2 - popoverWidth / 2;
        let top = rect.bottom + gap;

        if (top + popoverHeight > window.innerHeight - 20) {
            top = rect.top - popoverHeight - gap;
            if (top < 10) top = 10;
        }

        const padding = 16;
        const maxLeft = window.innerWidth - popoverWidth - padding;
        if (left < padding) left = padding;
        else if (left > maxLeft) left = maxLeft;

        container.style.left = `${Math.round(left)}px`;
        container.style.top = `${Math.round(top)}px`;
    }

    function closeLookupContainer() {
        const container = document.getElementById('wikiContextualLookupContainer');
        const backdrop = document.getElementById('wikiContextualLookupBackdrop');
        if (container) container.classList.remove('is-visible');
        if (backdrop) backdrop.classList.remove('is-visible');
    }

    async function executeLookup(query) {
        const bodyEl = document.getElementById('wikiContextualLookupBody');
        const titleEl = document.getElementById('wikiContextualLookupHeaderTitleText');

        if (titleEl) titleEl.textContent = `Tra cứu: "${query}"`;
        if (bodyEl) {
            bodyEl.innerHTML = '';
            const loadingDiv = document.createElement('div');
            loadingDiv.className = 'wiki-contextual-lookup-loading';
            const spinner = document.createElement('div');
            spinner.className = 'wiki-contextual-lookup-spinner';
            spinner.setAttribute('aria-hidden', 'true');
            const text = document.createElement('div');
            text.textContent = 'Đang tra cứu Wiki...';
            loadingDiv.appendChild(spinner);
            loadingDiv.appendChild(text);
            bodyEl.appendChild(loadingDiv);
        }

        if (currentLookupAbortController) {
            currentLookupAbortController.abort();
        }
        currentLookupAbortController = new AbortController();

        try {
            // Wiki-owned public read endpoint: /wiki/contextual-lookup?q=...
            const url = `/wiki/contextual-lookup?q=${encodeURIComponent(query)}`;
            const response = await fetch(url, {
                method: 'GET',
                signal: currentLookupAbortController.signal,
                headers: { 'Accept': 'application/json' }
            });

            if (response.ok) {
                const data = await response.json();
                renderLookupResults(data, query);
            } else {
                renderLookupError();
            }
        } catch (err) {
            if (err.name !== 'AbortError') {
                renderLookupError();
            }
        }
    }

    function renderLookupResults(data, query) {
        const bodyEl = document.getElementById('wikiContextualLookupBody');
        const titleEl = document.getElementById('wikiContextualLookupHeaderTitleText');
        if (!bodyEl) return;

        bodyEl.innerHTML = '';

        if (!data || !data.items || data.items.length === 0) {
            if (titleEl) titleEl.textContent = 'Kết quả tra cứu';

            const emptyContainer = document.createElement('div');
            emptyContainer.className = 'wiki-contextual-lookup-empty';

            emptyContainer.innerHTML = `
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <circle cx="11" cy="11" r="8"></circle>
                    <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
                </svg>
            `;

            const emptyMsg = document.createElement('div');
            emptyMsg.appendChild(document.createTextNode('Không tìm thấy thông tin Wiki phù hợp với từ khóa "'));
            const strong = document.createElement('strong');
            strong.textContent = query;
            emptyMsg.appendChild(strong);
            emptyMsg.appendChild(document.createTextNode('".'));

            emptyContainer.appendChild(emptyMsg);
            bodyEl.appendChild(emptyContainer);
            return;
        }

        const items = data.items;
        const primary = items[0];
        const secondary = items.slice(1);

        if (titleEl) titleEl.textContent = 'Thông tin Wiki';

        const typeLabel = ARTICLE_TYPE_LABELS[primary.articleType] || primary.articleType || 'Wiki';

        const primaryCard = document.createElement('div');
        primaryCard.className = 'wiki-contextual-lookup-primary-card';

        const cardMeta = document.createElement('div');
        cardMeta.className = 'wiki-contextual-lookup-card-meta';

        const typeBadge = document.createElement('span');
        typeBadge.className = 'wiki-contextual-lookup-type-badge';
        typeBadge.textContent = typeLabel;
        cardMeta.appendChild(typeBadge);

        if (primary.matchedAlias) {
            const aliasBadge = document.createElement('span');
            aliasBadge.className = 'wiki-contextual-lookup-alias-badge';
            aliasBadge.textContent = `Khớp danh xưng: "${primary.matchedAlias}"`;
            cardMeta.appendChild(aliasBadge);
        }
        primaryCard.appendChild(cardMeta);

        const cardTitle = document.createElement('h3');
        cardTitle.className = 'wiki-contextual-lookup-card-title';
        cardTitle.textContent = primary.title;
        primaryCard.appendChild(cardTitle);

        const cardSummary = document.createElement('p');
        cardSummary.className = 'wiki-contextual-lookup-card-summary';
        cardSummary.textContent = primary.summary || 'Chưa có tóm tắt chi tiết.';
        primaryCard.appendChild(cardSummary);

        const articleLink = document.createElement('a');
        articleLink.className = 'wiki-contextual-lookup-article-link';
        articleLink.href = getArticleUrl(primary.articleType, primary.slug);
        articleLink.target = '_blank';
        articleLink.rel = 'noopener noreferrer';

        const linkText = document.createElement('span');
        linkText.textContent = 'Xem bài viết chi tiết';
        articleLink.appendChild(linkText);

        const linkSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        linkSvg.setAttribute('width', '14');
        linkSvg.setAttribute('height', '14');
        linkSvg.setAttribute('viewBox', '0 0 24 24');
        linkSvg.setAttribute('fill', 'none');
        linkSvg.setAttribute('stroke', 'currentColor');
        linkSvg.setAttribute('stroke-width', '2');
        linkSvg.setAttribute('stroke-linecap', 'round');
        linkSvg.setAttribute('stroke-linejoin', 'round');
        linkSvg.setAttribute('aria-hidden', 'true');
        linkSvg.innerHTML = '<line x1="5" y1="12" x2="19" y2="12"></line><polyline points="12 5 19 12 12 19"></polyline>';
        articleLink.appendChild(linkSvg);

        primaryCard.appendChild(articleLink);
        bodyEl.appendChild(primaryCard);

        if (secondary.length > 0) {
            const secondarySection = document.createElement('div');
            secondarySection.className = 'wiki-contextual-lookup-secondary-section';

            const secondaryHeading = document.createElement('div');
            secondaryHeading.className = 'wiki-contextual-lookup-secondary-heading';
            secondaryHeading.textContent = `Kết quả liên quan khác (${secondary.length})`;
            secondarySection.appendChild(secondaryHeading);

            const secondaryList = document.createElement('ul');
            secondaryList.className = 'wiki-contextual-lookup-secondary-list';

            for (const item of secondary) {
                const sTypeLabel = ARTICLE_TYPE_LABELS[item.articleType] || item.articleType || 'Wiki';

                const li = document.createElement('li');
                const a = document.createElement('a');
                a.className = 'wiki-contextual-lookup-secondary-item';
                a.href = getArticleUrl(item.articleType, item.slug);
                a.target = '_blank';
                a.rel = 'noopener noreferrer';

                const titleSpan = document.createElement('span');
                titleSpan.textContent = item.title;

                const badgeSpan = document.createElement('span');
                badgeSpan.className = 'wiki-contextual-lookup-type-badge';
                badgeSpan.textContent = sTypeLabel;

                a.appendChild(titleSpan);
                a.appendChild(badgeSpan);
                li.appendChild(a);
                secondaryList.appendChild(li);
            }

            secondarySection.appendChild(secondaryList);
            bodyEl.appendChild(secondarySection);
        }
    }

    function renderLookupError() {
        const bodyEl = document.getElementById('wikiContextualLookupBody');
        if (!bodyEl) return;
        bodyEl.innerHTML = '';
        const errorDiv = document.createElement('div');
        errorDiv.className = 'wiki-contextual-lookup-error';
        errorDiv.innerHTML = `
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="12" y1="8" x2="12" y2="12"></line>
                <line x1="12" y1="16" x2="12.01" y2="16"></line>
            </svg>
            <div>Không thể tra cứu thông tin Wiki lúc này. Vui lòng thử lại sau.</div>
        `;
        bodyEl.appendChild(errorDiv);
    }

    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', initWikiContribution);
        } else {
            initWikiContribution();
        }
    }

    return {
        // Constants
        MAX_SELECTED_TEXT_LENGTH,
        MAX_LOOKUP_QUERY_LENGTH,
        MAX_SELECTED_PREFIX_LENGTH,
        MAX_SELECTED_SUFFIX_LENGTH,
        MAX_SELECTED_HEADING_ANCHOR_LENGTH,
        MIN_MESSAGE_LENGTH,
        MAX_MESSAGE_LENGTH,
        MAX_SOURCES_COUNT,
        MAX_SOURCE_LENGTH,
        THANK_YOU_MESSAGE,
        LONG_SELECTION_WARNING,
        LOOKUP_LIMIT_WARNING,
        CONTRIBUTION_TYPES,
        VALID_CONTRIBUTION_TYPES,
        VALID_CONTEXT_TYPES,

        // Pure functions for unit testing
        normalizeAnchorText,
        normalizeHeadingAnchor,
        validateMessage,
        validateSelectedText,
        validateSources,
        buildContributionPayload,
        classifySubmissionResponse,
        classifyResponse: classifySubmissionResponse,
        getValidSecurityRedirectUrl,
        getCsrf,
        findPrecedingHeadingAnchor,
        extractSelectionSnapshot,
        getArticleUrl,
        showToast,

        // Runtime lifecycle hooks and state inspection helpers
        initWikiContribution,
        openModal,
        closeModal,
        executeLookup,
        renderLookupResults,
        getModalGeneration,
        getActiveSnapshot,
        getCurrentContextMode
    };
});
