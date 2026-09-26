/**
 * KiemLai Universe — Reusable Content Reaction Component
 *
 * Provides a lightweight, accessible, progressive-enhancement Reaction Picker
 * for Novel Chapters, Comments, and Universe content targets.
 *
 * Communicates with:
 *   PUT /api/interaction/reactions
 * Body:
 *   { "targetType": "...", "targetId": "...", "reactionType": "LOVE" | "FIRE" | "HAHA" | "SAD" | null }
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

    const VALID_REACTION_TYPES = Object.freeze(['LOVE', 'FIRE', 'HAHA', 'SAD']);
    const DEFAULT_EMOJI = '❤️';
    const HOVER_CLOSE_DELAY_MS = 250;
    const GENERIC_ERROR_MSG = 'Không thể cập nhật biểu cảm. Vui lòng thử lại.';
    const ACCESS_DENIED_MSG = 'Không có quyền thực hiện. Vui lòng thử lại.';

    // Module-level state
    let paletteIdSeq = 0;
    let activeOpenWidget = null;
    let hoverCloseTimer = null;
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
     * Checks page-level meta tags first (primary convention), then falls back to widget attributes.
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
            const sumOfCounts = counts.LOVE + counts.FIRE + counts.HAHA + counts.SAD;
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
        widgetEl.setAttribute('data-reaction-count-love', String(counts.LOVE));
        widgetEl.setAttribute('data-reaction-count-fire', String(counts.FIRE));
        widgetEl.setAttribute('data-reaction-count-haha', String(counts.HAHA));
        widgetEl.setAttribute('data-reaction-count-sad', String(counts.SAD));

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

            const emojiSpan = doc.createElement('span');
            emojiSpan.className = 'kl-reaction-trigger__emoji';
            emojiSpan.setAttribute('data-reaction-trigger-emoji', '');
            triggerBtn.appendChild(emojiSpan);

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
            paletteEl.setAttribute('aria-label', 'Chọn biểu cảm');
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
                optEmoji.textContent = item.emoji;
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
        const triggerEmojiEl = triggerBtn ? triggerBtn.querySelector('[data-reaction-trigger-emoji]') : null;
        const triggerCountEl = triggerBtn ? triggerBtn.querySelector('[data-reaction-trigger-count]') : null;

        if (triggerEmojiEl) {
            triggerEmojiEl.textContent = activeItem ? activeItem.emoji : DEFAULT_EMOJI;
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
                triggerBtn.setAttribute(
                    'aria-label',
                    'Biểu cảm: ' + activeItem.label + (totalCount > 0 ? ', tổng cộng ' + totalCount + ' lượt' : '')
                );
            } else {
                triggerBtn.classList.remove('has-reaction');
                triggerBtn.setAttribute(
                    'aria-label',
                    totalCount > 0 ? 'Thả cảm xúc, tổng cộng ' + totalCount + ' lượt' : 'Thả cảm xúc'
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
        if (!paletteWidth) paletteWidth = 140;

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
        if (hoverCloseTimer) {
            clearTimeout(hoverCloseTimer);
            hoverCloseTimer = null;
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

        // 1. Pointerdown tracking for actual input mode resolution
        d.addEventListener('pointerdown', function (e) {
            const target = e.target;
            if (!target) return;
            const triggerBtn = target.closest ? target.closest('[data-reaction-trigger]') : null;
            if (triggerBtn) {
                lastPointerType = e.pointerType || 'mouse';
            }
        });

        // 2. Delegated Pointer Hover Handling (Safe hover boundary for mouse only)
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
                    openPalette(widgetEl);
                }
            }
        });

        d.addEventListener('pointerout', function (e) {
            const target = e.target;
            if (!target) return;

            const pType = e.pointerType || lastPointerType || 'mouse';
            if (pType !== 'mouse') return;

            const widgetEl = target.closest ? target.closest('[data-reaction-widget]') : null;
            if (widgetEl && activeOpenWidget === widgetEl) {
                const related = e.relatedTarget;
                if (!related || !widgetEl.contains(related)) {
                    if (hoverCloseTimer) clearTimeout(hoverCloseTimer);
                    hoverCloseTimer = setTimeout(function () {
                        closePalette(widgetEl);
                        hoverCloseTimer = null;
                    }, HOVER_CLOSE_DELAY_MS);
                }
            }
        });

        // 3. Delegated Click & Pointer Events
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

                const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
                const isOpen = paletteEl && !paletteEl.hidden;

                // Determine activation modality
                const isKeyboard = (e.detail === 0 && (!e.pointerType || e.pointerType === '')) ||
                    (e.clientX === 0 && e.clientY === 0 && !e.pointerType);
                const resolvedPointerType = e.pointerType || lastPointerType;
                const isTouchOrPen = (resolvedPointerType === 'touch' || resolvedPointerType === 'pen') ||
                    (!isKeyboard && typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(pointer: coarse)').matches);

                if (isKeyboard) {
                    // Keyboard activation: toggle palette open/closed only, NEVER send immediate LOVE
                    if (isOpen) {
                        closePalette(widgetEl);
                    } else {
                        openPalette(widgetEl);
                        const firstOption = widgetEl.querySelector('[data-reaction-option]');
                        if (firstOption && typeof firstOption.focus === 'function') {
                            firstOption.focus();
                        }
                    }
                } else if (isTouchOrPen) {
                    // Mobile touch / Pen: first tap ALWAYS opens palette (never immediately creates LOVE)
                    if (isOpen) {
                        closePalette(widgetEl);
                    } else {
                        openPalette(widgetEl);
                    }
                } else {
                    // Desktop mouse:
                    if (isOpen) {
                        closePalette(widgetEl);
                    } else {
                        const current = widgetEl.getAttribute('data-reaction-current');
                        if (!current) {
                            // Default LOVE on unreacted mouse click
                            const targetType = widgetEl.getAttribute('data-reaction-target-type');
                            const targetId = widgetEl.getAttribute('data-reaction-target-id');
                            await setReaction(targetType, targetId, 'LOVE', d);
                        } else {
                            // If already reacted, open palette
                            openPalette(widgetEl);
                        }
                    }
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

        // 4. Delegated Keyboard Navigation
        d.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                if (activeOpenWidget) {
                    const tr = activeOpenWidget.querySelector('[data-reaction-trigger]');
                    closePalette(activeOpenWidget);
                    if (tr && typeof tr.focus === 'function') {
                        tr.focus();
                    }
                    e.preventDefault();
                }
                return;
            }

            if (e.key === 'Enter' || e.key === ' ') {
                const target = e.target;
                if (target && target.matches && target.matches('[data-reaction-trigger]')) {
                    const widgetEl = target.closest('[data-reaction-widget]');
                    if (widgetEl) {
                        e.preventDefault();
                        const paletteEl = widgetEl.querySelector('[data-reaction-palette]');
                        if (paletteEl && !paletteEl.hidden) {
                            closePalette(widgetEl);
                        } else {
                            openPalette(widgetEl);
                            const firstOption = widgetEl.querySelector('[data-reaction-option]');
                            if (firstOption && typeof firstOption.focus === 'function') {
                                firstOption.focus();
                            }
                        }
                    }
                }
            }
        });

        // 5. Dynamic MutationObserver for automatic DOM insertion hydration
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
        if (hoverCloseTimer) {
            clearTimeout(hoverCloseTimer);
            hoverCloseTimer = null;
        }
        activeOpenWidget = null;
        boundDocument = null;
        inFlightTargets.clear();
        injectedFetch = null;
        lastPointerType = null;
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
        _validateReactionSummary: validateReactionSummary
    };
}));
