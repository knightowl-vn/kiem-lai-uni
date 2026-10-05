/**
 * KiemLai Universe — Reusable Content Reaction Component (MS-05I)
 *
 * Provides a lightweight, accessible, progressive-enhancement Reaction Picker
 * for Comments, Discussion Surfaces, and Universe content targets.
 *
 * Key behaviors:
 * - Neutral outline LIKE button by default when unreacted;
 * - Desktop quick click: unreacted -> LIKE; active -> remove;
 * - Desktop hover-intent (~550ms) opens reaction palette without premature flicker;
 * - Touch/Pen quick tap: unreacted -> LIKE; active -> remove;
 * - Touch/Pen long press (~500ms) opens reaction palette with strict tap suppression;
 * - Palette order: LIKE, LOVE, FIRE, HAHA, SAD;
 * - Keyboard navigation: Enter/Space for quick action; ArrowDown to open palette; Escape to close;
 * - Authoritative server response rendering with in-flight concurrency locks;
 * - Same-target multi-surface synchronization (main feed + drawer).
 *
 * API contract:
 *   PUT /api/interaction/reactions
 * Body:
 *   { "targetType": "...", "targetId": "...", "reactionType": "LIKE" | "LOVE" | "FIRE" | "HAHA" | "SAD" | null }
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        const exports = factory();
        root.InteractionReactions = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.InteractionReactions = exports;
    }
}(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const REACTION_CONFIG = [
        { type: 'LIKE', emoji: '👍', label: 'Thích' },
        { type: 'LOVE', emoji: '❤️', label: 'Yêu thích' },
        { type: 'FIRE', emoji: '🔥', label: 'Bùng cháy' },
        { type: 'HAHA', emoji: '😂', label: 'Hài hước' },
        { type: 'SAD',  emoji: '😭', label: 'Xúc động' }
    ];

    const REACTION_MAP = Object.freeze(
        REACTION_CONFIG.reduce((acc, item) => {
            acc[item.type] = item;
            return acc;
        }, {})
    );

    const VALID_REACTION_TYPES = Object.freeze(['LIKE', 'LOVE', 'FIRE', 'HAHA', 'SAD']);

    const HOVER_OPEN_DELAY_MS = 550;
    const HOVER_CLOSE_DELAY_MS = 250;
    const LONG_PRESS_DELAY_MS = 500;
    const MOVE_THRESHOLD_PX = 10;

    const GENERIC_ERROR_MSG = 'Không thể cập nhật biểu cảm. Vui lòng thử lại.';
    const ACCESS_DENIED_MSG = 'Không có quyền thực hiện. Vui lòng thử lại.';

    const SVG_LIKE_OUTLINE = '<svg class="kl-reaction-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"></path></svg>';
    const SVG_LIKE_FILLED = '<svg class="kl-reaction-icon kl-reaction-icon--filled" viewBox="0 0 24 24" width="16" height="16" fill="currentColor" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"></path></svg>';

    // Module-level state
    let paletteIdSeq = 0;
    let activeOpenWidget = null;
    let hoverOpenTimer = null;
    let hoverCloseTimer = null;
    let longPressTimer = null;
    let longPressConsumed = false;
    let touchStartX = 0;
    let touchStartY = 0;
    let boundDocument = null;
    let mutationObserver = null;
    let inFlightTargets = new Set();
    let injectedFetch = null;
    let lastPointerType = null;

    /**
     * Resolves fetch implementation (injected or global).
     */
    function getFetch() {
        if (injectedFetch) return injectedFetch;
        if (typeof fetch === 'function') return fetch;
        return null;
    }

    /**
     * Sets a custom fetch function (useful for unit testing).
     */
    function setFetch(fn) {
        injectedFetch = fn;
    }

    /**
     * Resolves the target key string for in-flight target synchronization.
     */
    function getTargetKey(targetType, targetId) {
        return (targetType || '') + ':' + (targetId || '');
    }

    /**
     * Extracts CSRF token and header name.
     */
    function resolveCsrf(widgetEl, doc) {
        let token = null;
        let header = null;

        const d = doc || (widgetEl ? widgetEl.ownerDocument : null) || (typeof document !== 'undefined' ? document : null);
        if (d && typeof d.querySelector === 'function') {
            const metaToken = d.querySelector('meta[name="_csrf"]');
            const metaHeader = d.querySelector('meta[name="_csrf_header"]');
            if (metaToken) {
                token = metaToken.getAttribute('content') || metaToken.content;
            }
            if (metaHeader) {
                header = metaHeader.getAttribute('content') || metaHeader.content;
            }
        }

        if (!token && widgetEl) {
            token = widgetEl.getAttribute('data-csrf-token');
        }
        if (!header && widgetEl) {
            header = widgetEl.getAttribute('data-csrf-header');
        }

        return { token: token || '', header: header || 'X-CSRF-TOKEN' };
    }

    /**
     * Sets an accessible status message across all matching DOM widgets for a target.
     */
    function setTargetStatus(targetType, targetId, message, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.querySelectorAll !== 'function') return;

        const selector = '[data-reaction-widget][data-reaction-target-type="' + targetType + '"][data-reaction-target-id="' + targetId + '"]';
        const widgets = d.querySelectorAll(selector);
        if (widgets) {
            Array.from(widgets).forEach(w => {
                const statusEl = w.querySelector('[data-reaction-status]');
                if (statusEl) {
                    statusEl.textContent = message || '';
                }
            });
        }
    }

    /**
     * Clears accessible status message across all matching DOM widgets for a target.
     */
    function clearTargetStatus(targetType, targetId, doc) {
        setTargetStatus(targetType, targetId, '', doc);
    }

    /**
     * Validates authoritative server ReactionSummary payload against strict invariants.
     */
    function validateReactionSummary(data, targetType, targetId) {
        if (!data || typeof data !== 'object') return false;
        if (data.targetType !== targetType) return false;
        if (data.targetId !== targetId) return false;
        if (!data.counts || typeof data.counts !== 'object') return false;

        let sum = 0;
        for (let i = 0; i < VALID_REACTION_TYPES.length; i++) {
            const typeKey = VALID_REACTION_TYPES[i];
            const countVal = data.counts[typeKey];
            if (typeof countVal !== 'number' || !Number.isInteger(countVal) || countVal < 0) {
                return false;
            }
            sum += countVal;
        }

        if (typeof data.totalCount !== 'number' || !Number.isInteger(data.totalCount) || data.totalCount < 0) {
            return false;
        }

        if (data.totalCount !== sum) {
            return false;
        }

        if (data.currentUserReaction !== null && !VALID_REACTION_TYPES.includes(data.currentUserReaction)) {
            return false;
        }

        return true;
    }

    /**
     * Sets icon inner content on an element (SVG or Emoji text).
     */
    function setTriggerIcon(iconEl, currentReaction) {
        if (!iconEl) return;
        if (!currentReaction) {
            // Idle state: Outline Thumbs Up
            if (iconEl.innerHTML !== undefined) {
                iconEl.innerHTML = SVG_LIKE_OUTLINE;
            } else {
                iconEl.textContent = '👍';
            }
        } else if (currentReaction === 'LIKE') {
            // Active LIKE: Filled Thumbs Up
            if (iconEl.innerHTML !== undefined) {
                iconEl.innerHTML = SVG_LIKE_FILLED;
            } else {
                iconEl.textContent = '👍';
            }
        } else {
            // Active other emoji: ❤️, 🔥, 😂, 😭
            const item = REACTION_MAP[currentReaction];
            iconEl.textContent = item ? item.emoji : '👍';
        }
    }

    /**
     * Builds or synchronizes the inner DOM of a reaction widget.
     */
    function renderWidget(widgetEl, summaryData) {
        if (!widgetEl) return;

        widgetEl.setAttribute('data-reaction-enhanced', 'true');

        const targetType = (summaryData && summaryData.targetType) || widgetEl.getAttribute('data-reaction-target-type') || '';
        const targetId = (summaryData && summaryData.targetId) || widgetEl.getAttribute('data-reaction-target-id') || '';

        let currentReaction = (summaryData && summaryData.currentUserReaction !== undefined)
            ? summaryData.currentUserReaction
            : widgetEl.getAttribute('data-reaction-current');

        if (currentReaction && !VALID_REACTION_TYPES.includes(currentReaction)) {
            currentReaction = null;
        }

        const counts = (summaryData && summaryData.counts) || {
            LIKE: Math.max(0, parseInt(widgetEl.getAttribute('data-reaction-count-like'), 10) || 0),
            LOVE: Math.max(0, parseInt(widgetEl.getAttribute('data-reaction-count-love'), 10) || 0),
            FIRE: Math.max(0, parseInt(widgetEl.getAttribute('data-reaction-count-fire'), 10) || 0),
            HAHA: Math.max(0, parseInt(widgetEl.getAttribute('data-reaction-count-haha'), 10) || 0),
            SAD:  Math.max(0, parseInt(widgetEl.getAttribute('data-reaction-count-sad'), 10) || 0)
        };

        let totalCount = 0;
        if (summaryData && typeof summaryData.totalCount === 'number') {
            totalCount = Math.max(0, summaryData.totalCount);
        } else {
            const rawTotal = parseInt(widgetEl.getAttribute('data-reaction-total'), 10);
            const sumOfCounts = (counts.LIKE || 0) + (counts.LOVE || 0) + (counts.FIRE || 0) + (counts.HAHA || 0) + (counts.SAD || 0);
            totalCount = (!isNaN(rawTotal) && rawTotal >= 0) ? rawTotal : sumOfCounts;
        }

        // Synchronize widget dataset attributes
        widgetEl.setAttribute('data-reaction-target-type', targetType);
        widgetEl.setAttribute('data-reaction-target-id', targetId);
        if (currentReaction) {
            widgetEl.setAttribute('data-reaction-current', currentReaction);
        } else {
            widgetEl.removeAttribute('data-reaction-current');
        }
        widgetEl.setAttribute('data-reaction-total', String(totalCount));
        widgetEl.setAttribute('data-reaction-count-like', String(counts.LIKE || 0));
        widgetEl.setAttribute('data-reaction-count-love', String(counts.LOVE || 0));
        widgetEl.setAttribute('data-reaction-count-fire', String(counts.FIRE || 0));
        widgetEl.setAttribute('data-reaction-count-haha', String(counts.HAHA || 0));
        widgetEl.setAttribute('data-reaction-count-sad', String(counts.SAD || 0));

        const doc = widgetEl.ownerDocument || (typeof document !== 'undefined' ? document : null);

        let triggerBtn = widgetEl.querySelector('[data-reaction-trigger]');
        let paletteEl = widgetEl.querySelector('[data-reaction-palette]');
        let statusEl = widgetEl.querySelector('[data-reaction-status]');

        // Resolve or assign a unique DOM id for the palette element
        let paletteId = paletteEl ? paletteEl.getAttribute('id') : null;
        if (!paletteId) {
            paletteIdSeq++;
            paletteId = 'kl-reaction-palette-' + paletteIdSeq;
        }

        // Construct trigger if absent
        if (!triggerBtn && doc && typeof doc.createElement === 'function') {
            triggerBtn = doc.createElement('button');
            triggerBtn.type = 'button';
            triggerBtn.className = 'kl-reaction-trigger';
            triggerBtn.setAttribute('data-reaction-trigger', '');
            triggerBtn.setAttribute('aria-expanded', 'false');
            triggerBtn.setAttribute('aria-controls', paletteId);

            const iconSpan = doc.createElement('span');
            iconSpan.className = 'kl-reaction-trigger__icon';
            iconSpan.setAttribute('data-reaction-trigger-icon', '');
            triggerBtn.appendChild(iconSpan);

            const countSpan = doc.createElement('span');
            countSpan.className = 'kl-reaction-trigger__count';
            countSpan.setAttribute('data-reaction-trigger-count', '');
            triggerBtn.appendChild(countSpan);

            widgetEl.appendChild(triggerBtn);
        } else if (triggerBtn) {
            triggerBtn.removeAttribute('aria-haspopup');
            triggerBtn.setAttribute('aria-controls', paletteId);
            if (!triggerBtn.hasAttribute('aria-expanded')) {
                triggerBtn.setAttribute('aria-expanded', 'false');
            }
        }

        // Construct accessible status live region if absent
        if (!statusEl && doc && typeof doc.createElement === 'function') {
            statusEl = doc.createElement('span');
            statusEl.className = 'kl-reaction-status';
            statusEl.setAttribute('data-reaction-status', '');
            statusEl.setAttribute('role', 'status');
            statusEl.setAttribute('aria-live', 'polite');
            widgetEl.appendChild(statusEl);
        }

        // Construct palette if absent
        if (!paletteEl && doc && typeof doc.createElement === 'function') {
            paletteEl = doc.createElement('div');
            paletteEl.id = paletteId;
            paletteEl.setAttribute('id', paletteId);
            paletteEl.className = 'kl-reaction-palette';
            paletteEl.setAttribute('data-reaction-palette', '');
            paletteEl.setAttribute('role', 'group');
            paletteEl.setAttribute('aria-label', 'Chọn cảm xúc');
            paletteEl.hidden = true;

            REACTION_CONFIG.forEach(item => {
                const optBtn = doc.createElement('button');
                optBtn.type = 'button';
                optBtn.className = 'kl-reaction-option';
                optBtn.setAttribute('data-reaction-option', '');
                optBtn.setAttribute('data-reaction-type', item.type);
                optBtn.setAttribute('aria-pressed', 'false');

                const optEmoji = doc.createElement('span');
                optEmoji.className = 'kl-reaction-option__emoji';
                if (item.type === 'LIKE' && optEmoji.innerHTML !== undefined) {
                    optEmoji.innerHTML = SVG_LIKE_FILLED;
                } else {
                    optEmoji.textContent = item.emoji;
                }
                optBtn.appendChild(optEmoji);

                const optCount = doc.createElement('span');
                optCount.className = 'kl-reaction-option__count';
                optCount.setAttribute('data-reaction-option-count', item.type);
                optBtn.appendChild(optCount);

                paletteEl.appendChild(optBtn);
            });

            widgetEl.appendChild(paletteEl);
        } else if (paletteEl) {
            if (!paletteEl.getAttribute('id')) {
                paletteEl.setAttribute('id', paletteId);
            }
        }

        // Update trigger visuals
        const activeItem = currentReaction ? REACTION_MAP[currentReaction] : null;
        const triggerIconEl = triggerBtn ? (triggerBtn.querySelector('[data-reaction-trigger-icon]') || triggerBtn.querySelector('[data-reaction-trigger-emoji]')) : null;
        const triggerCountEl = triggerBtn ? triggerBtn.querySelector('[data-reaction-trigger-count]') : null;

        if (triggerIconEl) {
            setTriggerIcon(triggerIconEl, currentReaction);
        }

        if (triggerCountEl) {
            if (totalCount > 0) {
                triggerCountEl.textContent = String(totalCount);
                triggerCountEl.hidden = false;
            } else {
                triggerCountEl.textContent = '';
                triggerCountEl.hidden = true;
            }
        }

        if (triggerBtn) {
            if (activeItem) {
                triggerBtn.classList.add('has-reaction');
                if (currentReaction === 'LIKE') {
                    triggerBtn.classList.add('is-like');
                    triggerBtn.setAttribute(
                        'aria-label',
                        'Đã thích. Nhấn để gỡ; mũi tên xuống để đổi cảm xúc.' + (totalCount > 0 ? ', tổng cộng ' + totalCount + ' lượt' : '')
                    );
                } else {
                    triggerBtn.classList.remove('is-like');
                    triggerBtn.setAttribute(
                        'aria-label',
                        'Đã chọn ' + activeItem.label + '. Nhấn để gỡ; mũi tên xuống để đổi cảm xúc.' + (totalCount > 0 ? ', tổng cộng ' + totalCount + ' lượt' : '')
                    );
                }
            } else {
                triggerBtn.classList.remove('has-reaction');
                triggerBtn.classList.remove('is-like');
                triggerBtn.setAttribute(
                    'aria-label',
                    'Thích. Nhấn để thích; mũi tên xuống để chọn cảm xúc.' + (totalCount > 0 ? ', tổng cộng ' + totalCount + ' lượt' : '')
                );
            }
        }

        // Update palette option buttons
        if (paletteEl) {
            const optionBtns = paletteEl.querySelectorAll('[data-reaction-option]');
            if (optionBtns) {
                Array.from(optionBtns).forEach(opt => {
                    const optType = opt.getAttribute('data-reaction-type');
                    const config = REACTION_MAP[optType];
                    const optCountVal = (counts && counts[optType] != null) ? counts[optType] : 0;
                    const isActive = currentReaction === optType;

                    opt.setAttribute('aria-pressed', isActive ? 'true' : 'false');
                    if (isActive) {
                        opt.classList.add('is-active');
                    } else {
                        opt.classList.remove('is-active');
                    }

                    const optLabel = config ? config.label : optType;
                    opt.setAttribute('aria-label', optLabel + ', ' + optCountVal + ' lượt');

                    const countSpan = opt.querySelector('[data-reaction-option-count]');
                    if (countSpan) {
                        countSpan.textContent = String(optCountVal);
                    }
                });
            }
        }
    }

    /**
     * Updates all matching DOM widgets for a target with authoritative server response.
     */
    function updateAllWidgetsForTarget(targetType, targetId, summary, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.querySelectorAll !== 'function') return;

        const selector = '[data-reaction-widget][data-reaction-target-type="' + targetType + '"][data-reaction-target-id="' + targetId + '"]';
        const widgets = d.querySelectorAll(selector);
        if (widgets) {
            Array.from(widgets).forEach(w => renderWidget(w, summary));
        }
    }

    /**
     * Sets or clears busy / in-flight state across all matching DOM widgets.
     */
    function setTargetBusyState(targetType, targetId, isBusy, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.querySelectorAll !== 'function') return;

        const selector = '[data-reaction-widget][data-reaction-target-type="' + targetType + '"][data-reaction-target-id="' + targetId + '"]';
        const widgets = d.querySelectorAll(selector);
        if (widgets) {
            Array.from(widgets).forEach(w => {
                if (isBusy) {
                    w.setAttribute('aria-busy', 'true');
                    w.classList.add('is-pending');
                } else {
                    w.removeAttribute('aria-busy');
                    w.classList.remove('is-pending');
                }
            });
        }
    }

    /**
     * Measures actual palette and trigger geometry and applies viewport collision classes.
     */
    function adjustPalettePosition(widgetEl) {
        if (!widgetEl) return;
        const triggerBtn = widgetEl.querySelector('[data-reaction-trigger]');
        const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
        if (!triggerBtn || !paletteEl || typeof triggerBtn.getBoundingClientRect !== 'function') return;

        const doc = widgetEl.ownerDocument || (typeof document !== 'undefined' ? document : null);
        const winWidth = (typeof window !== 'undefined' && window.innerWidth) ? window.innerWidth
            : (doc && doc.documentElement && doc.documentElement.clientWidth ? doc.documentElement.clientWidth : 1024);
        const winHeight = (typeof window !== 'undefined' && window.innerHeight) ? window.innerHeight
            : (doc && doc.documentElement && doc.documentElement.clientHeight ? doc.documentElement.clientHeight : 768);

        // Measure palette rendered dimensions safely
        let paletteWidth = 0;
        let paletteHeight = 0;
        const wasHidden = paletteEl.hidden;

        if (wasHidden && typeof paletteEl.getBoundingClientRect === 'function') {
            const prevVisibility = paletteEl.style.visibility;
            const prevDisplay = paletteEl.style.display;
            paletteEl.style.visibility = 'hidden';
            paletteEl.style.display = 'inline-flex';
            paletteEl.hidden = false;

            const rect = paletteEl.getBoundingClientRect();
            paletteWidth = rect.width || 0;
            paletteHeight = rect.height || 0;

            paletteEl.hidden = true;
            paletteEl.style.visibility = prevVisibility;
            paletteEl.style.display = prevDisplay;
        } else if (typeof paletteEl.getBoundingClientRect === 'function') {
            const rect = paletteEl.getBoundingClientRect();
            paletteWidth = rect.width || 0;
            paletteHeight = rect.height || 0;
        }

        if (!paletteHeight) paletteHeight = 44;
        if (!paletteWidth) paletteWidth = 175;

        const triggerRect = triggerBtn.getBoundingClientRect();
        const spaceAbove = triggerRect.top;
        const spaceBelow = winHeight - triggerRect.bottom;
        const neededVertical = paletteHeight + 8;

        // Vertical collision: prefer above, flip below if insufficient above and more space below
        if (spaceAbove < neededVertical && spaceBelow >= spaceAbove) {
            paletteEl.classList.add('kl-reaction-palette--bottom');
        } else {
            paletteEl.classList.remove('kl-reaction-palette--bottom');
        }

        // Horizontal collision: keep palette within viewport safety margins
        const spaceLeft = triggerRect.left;
        const spaceRight = winWidth - triggerRect.left;

        if (spaceLeft < 8) {
            paletteEl.classList.add('kl-reaction-palette--align-left');
            paletteEl.classList.remove('kl-reaction-palette--align-right');
        } else if (spaceRight < (paletteWidth + 8)) {
            paletteEl.classList.add('kl-reaction-palette--align-right');
            paletteEl.classList.remove('kl-reaction-palette--align-left');
        } else {
            paletteEl.classList.remove('kl-reaction-palette--align-left');
            paletteEl.classList.remove('kl-reaction-palette--align-right');
        }
    }

    /**
     * Opens the palette for a specific widget.
     */
    function openPalette(widgetEl) {
        if (!widgetEl) return;
        if (activeOpenWidget && activeOpenWidget !== widgetEl) {
            closePalette(activeOpenWidget);
        }

        const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
        const triggerBtn = widgetEl.querySelector('[data-reaction-trigger]');
        if (paletteEl) {
            adjustPalettePosition(widgetEl);
            paletteEl.hidden = false;
        }
        if (triggerBtn) {
            triggerBtn.setAttribute('aria-expanded', 'true');
        }
        activeOpenWidget = widgetEl;
    }

    /**
     * Closes the palette for a specific widget.
     */
    function closePalette(widgetEl) {
        if (!widgetEl) return;
        const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
        const triggerBtn = widgetEl.querySelector('[data-reaction-trigger]');
        if (paletteEl) {
            paletteEl.hidden = true;
        }
        if (triggerBtn) {
            triggerBtn.setAttribute('aria-expanded', 'false');
        }
        if (activeOpenWidget === widgetEl) {
            activeOpenWidget = null;
        }
    }

    /**
     * Closes all open palettes in the document.
     */
    function closeAllPalettes(doc) {
        if (hoverOpenTimer) {
            clearTimeout(hoverOpenTimer);
            hoverOpenTimer = null;
        }
        if (hoverCloseTimer) {
            clearTimeout(hoverCloseTimer);
            hoverCloseTimer = null;
        }
        if (longPressTimer) {
            clearTimeout(longPressTimer);
            longPressTimer = null;
        }
        if (activeOpenWidget) {
            closePalette(activeOpenWidget);
        }
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (d && typeof d.querySelectorAll === 'function') {
            const openPalettes = d.querySelectorAll('[data-reaction-palette]:not([hidden])');
            if (openPalettes) {
                Array.from(openPalettes).forEach(p => {
                    p.hidden = true;
                    const w = p.closest ? p.closest('[data-reaction-widget]') : p.parentNode;
                    if (w && w.querySelector) {
                        const tr = w.querySelector('[data-reaction-trigger]');
                        if (tr) tr.setAttribute('aria-expanded', 'false');
                    }
                });
            }
        }
        activeOpenWidget = null;
    }

    /**
     * Sends the reaction mutation request to the backend.
     */
    async function setReaction(targetType, targetId, reactionType, doc) {
        if (!targetType || !targetId) return null;

        const targetKey = getTargetKey(targetType, targetId);
        if (inFlightTargets.has(targetKey)) {
            return null; // Prevent duplicate concurrent in-flight requests
        }

        const d = doc || (typeof document !== 'undefined' ? document : null);
        const fetchFn = getFetch();
        if (!fetchFn) {
            return null;
        }

        inFlightTargets.add(targetKey);
        setTargetBusyState(targetType, targetId, true, d);

        const sampleWidget = d && typeof d.querySelector === 'function'
            ? d.querySelector('[data-reaction-widget][data-reaction-target-type="' + targetType + '"][data-reaction-target-id="' + targetId + '"]')
            : null;

        const csrf = resolveCsrf(sampleWidget, d);

        const payload = {
            targetType: targetType,
            targetId: targetId,
            reactionType: reactionType || null
        };

        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        if (csrf.token) {
            headers[csrf.header] = csrf.token;
        }

        try {
            const response = await fetchFn('/api/interaction/reactions', {
                method: 'PUT',
                headers: headers,
                body: JSON.stringify(payload)
            });

            // 1. Unauthenticated -> redirect to /login
            if (response.status === 401 || (response.redirected && response.url && response.url.includes('/login'))) {
                if (typeof window !== 'undefined' && window.location) {
                    const returnTo = encodeURIComponent(window.location.pathname + window.location.search);
                    window.location.href = '/login?returnTo=' + returnTo;
                }
                return null;
            }

            // 2. Access Denied redirect
            if (response.redirected && response.url && response.url.includes('/access-denied')) {
                setTargetStatus(targetType, targetId, ACCESS_DENIED_MSG, d);
                return null;
            }

            // 3. HTTP Error
            if (!response.ok) {
                setTargetStatus(targetType, targetId, GENERIC_ERROR_MSG, d);
                return null;
            }

            // 4. Content-Type validation
            const contentType = response.headers ? (response.headers.get('content-type') || response.headers.get('Content-Type') || '') : '';
            if (contentType && !contentType.includes('application/json')) {
                setTargetStatus(targetType, targetId, GENERIC_ERROR_MSG, d);
                return null;
            }

            const data = await response.json();

            // 5. Strict schema & invariant validation
            if (validateReactionSummary(data, targetType, targetId)) {
                clearTargetStatus(targetType, targetId, d);
                updateAllWidgetsForTarget(targetType, targetId, data, d);
                try {
                    if (d && typeof d.dispatchEvent === 'function') {
                        const eventDetail = { targetType: targetType, targetId: targetId, summary: data };
                        if (typeof CustomEvent === 'function') {
                            d.dispatchEvent(new CustomEvent('kiemlai:reaction-updated', { bubbles: true, detail: eventDetail }));
                        } else if (typeof d.createEvent === 'function') {
                            const ev = d.createEvent('CustomEvent');
                            ev.initCustomEvent('kiemlai:reaction-updated', true, true, eventDetail);
                            d.dispatchEvent(ev);
                        }
                    }
                } catch (_) {}
                return data;
            } else {
                setTargetStatus(targetType, targetId, GENERIC_ERROR_MSG, d);
                return null;
            }
        } catch (err) {
            setTargetStatus(targetType, targetId, GENERIC_ERROR_MSG, d);
            return null;
        } finally {
            inFlightTargets.delete(targetKey);
            setTargetBusyState(targetType, targetId, false, d);
        }
    }

    /**
     * Hydrates unenhanced reaction widgets under a given root container node.
     */
    function hydrate(rootNode, doc) {
        if (!rootNode) return;
        const d = doc || (rootNode.ownerDocument) || (typeof document !== 'undefined' ? document : null);

        if (rootNode.matches && rootNode.matches('[data-reaction-widget]')) {
            if (rootNode.getAttribute('data-reaction-enhanced') !== 'true') {
                renderWidget(rootNode);
            }
        }
        if (rootNode.querySelectorAll) {
            const unenhanced = rootNode.querySelectorAll('[data-reaction-widget]:not([data-reaction-enhanced="true"])');
            if (unenhanced) {
                Array.from(unenhanced).forEach(w => renderWidget(w));
            }
        }
    }

    /**
     * Initializes delegated event listeners and dynamic MutationObserver on the document.
     */
    function init(doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        if (boundDocument === d) return;

        if (mutationObserver) {
            mutationObserver.disconnect();
            mutationObserver = null;
        }

        boundDocument = d;

        // 1. Pointerdown: track pointer modality and initiate long press for touch/pen
        d.addEventListener('pointerdown', function (e) {
            const target = e.target;
            if (!target) return;

            const triggerBtn = target.closest ? target.closest('[data-reaction-trigger]') : null;
            if (triggerBtn) {
                lastPointerType = e.pointerType || 'mouse';

                if (lastPointerType === 'touch' || lastPointerType === 'pen') {
                    const widgetEl = triggerBtn.closest('[data-reaction-widget]');
                    if (widgetEl) {
                        touchStartX = e.clientX || 0;
                        touchStartY = e.clientY || 0;
                        longPressConsumed = false;

                        if (longPressTimer) {
                            clearTimeout(longPressTimer);
                        }

                        longPressTimer = setTimeout(function () {
                            openPalette(widgetEl);
                            longPressConsumed = true;
                            longPressTimer = null;
                        }, LONG_PRESS_DELAY_MS);
                    }
                }
            }
        });

        // 2. Pointermove: cancel long-press if movement exceeds scroll tolerance
        d.addEventListener('pointermove', function (e) {
            if (longPressTimer) {
                const currentX = e.clientX || 0;
                const currentY = e.clientY || 0;
                const dist = Math.hypot(currentX - touchStartX, currentY - touchStartY);
                if (dist > MOVE_THRESHOLD_PX) {
                    clearTimeout(longPressTimer);
                    longPressTimer = null;
                }
            }
        });

        // 3. Pointerup / Pointercancel: clear long-press timer
        d.addEventListener('pointerup', function () {
            if (longPressTimer) {
                clearTimeout(longPressTimer);
                longPressTimer = null;
            }
        });

        d.addEventListener('pointercancel', function () {
            if (longPressTimer) {
                clearTimeout(longPressTimer);
                longPressTimer = null;
            }
            longPressConsumed = false;
        });

        // 4. Delegated Pointer Hover (Mouse only hover-intent ~550ms)
        d.addEventListener('pointerover', function (e) {
            const target = e.target;
            if (!target) return;

            const pType = e.pointerType || lastPointerType || 'mouse';
            if (pType !== 'mouse') return;

            const widgetEl = target.closest ? target.closest('[data-reaction-widget]') : null;
            if (widgetEl) {
                if (hoverCloseTimer) {
                    clearTimeout(hoverCloseTimer);
                    hoverCloseTimer = null;
                }

                if (activeOpenWidget !== widgetEl) {
                    if (hoverOpenTimer) {
                        clearTimeout(hoverOpenTimer);
                    }
                    hoverOpenTimer = setTimeout(function () {
                        openPalette(widgetEl);
                        hoverOpenTimer = null;
                    }, HOVER_OPEN_DELAY_MS);
                }
            }
        });

        d.addEventListener('pointerout', function (e) {
            const target = e.target;
            if (!target) return;

            const pType = e.pointerType || lastPointerType || 'mouse';
            if (pType !== 'mouse') return;

            const widgetEl = target.closest ? target.closest('[data-reaction-widget]') : null;
            if (widgetEl) {
                const related = e.relatedTarget;
                if (!related || !widgetEl.contains(related)) {
                    if (hoverOpenTimer) {
                        clearTimeout(hoverOpenTimer);
                        hoverOpenTimer = null;
                    }

                    if (activeOpenWidget === widgetEl) {
                        if (hoverCloseTimer) clearTimeout(hoverCloseTimer);
                        hoverCloseTimer = setTimeout(function () {
                            closePalette(widgetEl);
                            hoverCloseTimer = null;
                        }, HOVER_CLOSE_DELAY_MS);
                    }
                }
            }
        });

        // 5. Delegated Click Events
        d.addEventListener('click', async function (e) {
            const target = e.target;
            if (!target) return;

            // A. Click on a reaction option button in the palette
            const optionBtn = target.closest ? target.closest('[data-reaction-option]') : null;
            if (optionBtn) {
                const widgetEl = optionBtn.closest('[data-reaction-widget]');
                if (!widgetEl) return;

                const targetType = widgetEl.getAttribute('data-reaction-target-type');
                const targetId = widgetEl.getAttribute('data-reaction-target-id');
                const currentReaction = widgetEl.getAttribute('data-reaction-current');
                const selectedType = optionBtn.getAttribute('data-reaction-type');

                const desiredType = (currentReaction === selectedType) ? null : selectedType;

                closePalette(widgetEl);
                await setReaction(targetType, targetId, desiredType, d);
                return;
            }

            // B. Click on trigger button
            const triggerBtn = target.closest ? target.closest('[data-reaction-trigger]') : null;
            if (triggerBtn) {
                const widgetEl = triggerBtn.closest('[data-reaction-widget]');
                if (!widgetEl) return;

                // Long-press suppression on touch/pen
                if (longPressConsumed) {
                    longPressConsumed = false;
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    if (typeof e.stopPropagation === 'function') e.stopPropagation();
                    return;
                }

                const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
                const isOpen = paletteEl && !paletteEl.hidden;

                if (isOpen) {
                    closePalette(widgetEl);
                }

                const currentReaction = widgetEl.getAttribute('data-reaction-current');
                const targetType = widgetEl.getAttribute('data-reaction-target-type');
                const targetId = widgetEl.getAttribute('data-reaction-target-id');

                if (!currentReaction) {
                    // Quick click / tap on unreacted: SET LIKE immediately
                    await setReaction(targetType, targetId, 'LIKE', d);
                } else {
                    // Quick click / tap on active reaction: REMOVE reaction immediately
                    await setReaction(targetType, targetId, null, d);
                }

                lastPointerType = null;
                return;
            }

            // C. Outside click dismissal
            const insideWidget = target.closest ? target.closest('[data-reaction-widget]') : null;
            if (!insideWidget && activeOpenWidget) {
                closeAllPalettes(d);
            }
        });

        // 6. Delegated Keyboard Navigation
        d.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                if (activeOpenWidget) {
                    const tr = activeOpenWidget.querySelector('[data-reaction-trigger]');
                    closePalette(activeOpenWidget);
                    if (tr && typeof tr.focus === 'function') {
                        tr.focus();
                    }
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                }
                return;
            }

            // Enter / Space on trigger: Quick Action (LIKE or Remove)
            if (e.key === 'Enter' || e.key === ' ') {
                const target = e.target;
                if (target && target.matches && target.matches('[data-reaction-trigger]')) {
                    const widgetEl = target.closest('[data-reaction-widget]');
                    if (widgetEl) {
                        if (typeof e.preventDefault === 'function') e.preventDefault();
                        const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
                        if (paletteEl && !paletteEl.hidden) {
                            closePalette(widgetEl);
                        }

                        const currentReaction = widgetEl.getAttribute('data-reaction-current');
                        const targetType = widgetEl.getAttribute('data-reaction-target-type');
                        const targetId = widgetEl.getAttribute('data-reaction-target-id');

                        if (!currentReaction) {
                            setReaction(targetType, targetId, 'LIKE', d);
                        } else {
                            setReaction(targetType, targetId, null, d);
                        }
                    }
                }
                return;
            }

            // ArrowDown on trigger: Open palette without mutating & focus first option
            if (e.key === 'ArrowDown' || e.key === 'Down') {
                const target = e.target;
                if (target && target.matches && target.matches('[data-reaction-trigger]')) {
                    const widgetEl = target.closest('[data-reaction-widget]');
                    if (widgetEl) {
                        if (typeof e.preventDefault === 'function') e.preventDefault();
                        openPalette(widgetEl);
                        const firstOption = widgetEl.querySelector('[data-reaction-option]');
                        if (firstOption && typeof firstOption.focus === 'function') {
                            firstOption.focus();
                        }
                    }
                }
            }
        });

        // 7. Dynamic MutationObserver for automatic DOM insertion hydration
        const ObserverClass = (d.defaultView && d.defaultView.MutationObserver) ||
            (typeof MutationObserver !== 'undefined' ? MutationObserver : null) ||
            d.MutationObserver;

        const observeTarget = d.body || d.documentElement || d;
        if (ObserverClass && observeTarget && typeof ObserverClass === 'function') {
            mutationObserver = new ObserverClass(function (mutations) {
                for (let i = 0; i < mutations.length; i++) {
                    const mutation = mutations[i];
                    if (mutation.addedNodes) {
                        for (let j = 0; j < mutation.addedNodes.length; j++) {
                            const node = mutation.addedNodes[j];
                            if (node.nodeType === 1) { // Element node
                                hydrate(node, d);
                            }
                        }
                    }
                }
            });
            if (typeof mutationObserver.observe === 'function') {
                mutationObserver.observe(observeTarget, { childList: true, subtree: true });
            }
        }

        // Hydrate all SSR widgets present on init
        hydrate(observeTarget, d);
    }

    /**
     * Cleans up observers and listeners (useful for test resets).
     */
    function destroy() {
        if (mutationObserver) {
            mutationObserver.disconnect();
            mutationObserver = null;
        }
        if (hoverOpenTimer) {
            clearTimeout(hoverOpenTimer);
            hoverOpenTimer = null;
        }
        if (hoverCloseTimer) {
            clearTimeout(hoverCloseTimer);
            hoverCloseTimer = null;
        }
        if (longPressTimer) {
            clearTimeout(longPressTimer);
            longPressTimer = null;
        }
        longPressConsumed = false;
        activeOpenWidget = null;
        boundDocument = null;
        inFlightTargets.clear();
        injectedFetch = null;
        lastPointerType = null;
    }

    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                init(document);
            });
        } else {
            init(document);
        }
    }

    return {
        init: init,
        destroy: destroy,
        hydrate: hydrate,
        renderWidget: renderWidget,
        updateAllWidgetsForTarget: updateAllWidgetsForTarget,
        setReaction: setReaction,
        openPalette: openPalette,
        closePalette: closePalette,
        closeAllPalettes: closeAllPalettes,
        setFetch: setFetch,
        getConfig: function () { return REACTION_CONFIG.slice(); },
        _getInFlightTargets: function () { return inFlightTargets; },
        _getActiveOpenWidget: function () { return activeOpenWidget; },
        _validateReactionSummary: validateReactionSummary,
        _getDelays: function () {
            return {
                hoverOpen: HOVER_OPEN_DELAY_MS,
                hoverClose: HOVER_CLOSE_DELAY_MS,
                longPress: LONG_PRESS_DELAY_MS,
                moveThreshold: MOVE_THRESHOLD_PX
            };
        }
    };
}));
