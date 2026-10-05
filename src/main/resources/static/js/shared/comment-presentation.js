/**
 * KiemLai Universe — Shared Comment Presentation Primitive (MS-05E / E8C4-UX1)
 *
 * Responsibilities:
 * - Authoritative presentation primitive for comments across KiemLai surfaces.
 * - Pure presentation renderer: accepts normalized action descriptor arrays.
 * - Does NOT infer domain capabilities (originNavigable, canEdit, canDelete,
 *   canReport, isEdited, blockKey, anchorStatus, rootCommentId).
 * - Consumer decides WHAT actions exist; Shared presentation only renders them.
 * - Standardizes the three-dot overflow actions menu grammar (.kl-comment__overflow).
 * - Manages active menu state, outside click dismissal, Escape key handling,
 *   focus restoration, and single-open-menu invariants.
 * - Menu item activation: Individual menu item buttons receive NO direct click
 *   listeners. A single generic presentation listener on the menu container closes
 *   the menu when an enabled menu item is activated, allowing the click event to
 *   continue bubbling to the document for consumer delegation.
 * - Pure frontend presentation module: no network I/O, no backend dependencies.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        let relativeTime;
        try {
            relativeTime = require('./relative-time.js');
        } catch (_) {
            relativeTime = root && root.RelativeTime;
        }
        module.exports = factory(relativeTime);
    } else {
        const exports = factory(root.RelativeTime);
        root.CommentPresentation = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommentPresentation = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function (injectedRelativeTime) {
    'use strict';

    // Active menu singleton state
    let activeOpenMenu = null; // { triggerEl, menuEl, containerEl }
    let activeBoundDocument = null;
    let boundDocClickHandler = null;
    let boundDocKeydownHandler = null;

    /**
     * Sanitizes an avatar URL ensuring safe protocols (http, https, or same-origin path).
     * Disallows dangerous protocols (javascript:, data:, vbscript:, blob:, etc.)
     * and disallows protocol-relative URLs (//evil.com).
     *
     * @param {string} url
     * @returns {string|null}
     */
    function sanitizeAvatarUrl(url) {
        if (typeof url !== 'string') {
            return null;
        }
        const trimmed = url.trim();
        if (!trimmed) {
            return null;
        }
        const lower = trimmed.toLowerCase();
        if (lower.startsWith('https://') || lower.startsWith('http://')) {
            return trimmed;
        }
        if (lower.startsWith('/') && !lower.startsWith('//')) {
            return trimmed;
        }
        return null;
    }

    /**
     * Creates an avatar fallback element with the first initial of the display name.
     *
     * @param {string} displayName
     * @param {Document} doc
     * @param {Object} [options]
     * @returns {Element}
     */
    function createAvatarFallback(displayName, doc, options) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        const legacyPrefix = options && options.legacyPrefix;
        const fallback = d.createElement('span');
        let cls = 'kl-comment__avatar kl-comment__avatar--fallback';
        if (legacyPrefix) {
            cls += ' ' + legacyPrefix + '-avatar ' + legacyPrefix + '-avatar--fallback';
        }
        fallback.className = cls;
        fallback.setAttribute('aria-hidden', 'true');

        const trimmed = (typeof displayName === 'string') ? displayName.trim() : '';
        const firstChar = trimmed ? trimmed.charAt(0).toUpperCase() : 'U';
        fallback.textContent = firstChar;
        return fallback;
    }

    /**
     * Renders an avatar element (img or fallback) for an author.
     * Handles image load error by replacing img with the initial fallback.
     *
     * @param {Object|null} author
     * @param {Document} doc
     * @param {Object} [options]
     * @returns {Element}
     */
    function renderAvatar(author, doc, options) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        const authorObj = (author && typeof author === 'object') ? author : null;
        const rawName = (authorObj && typeof authorObj.displayName === 'string') ? authorObj.displayName.trim() : '';
        const displayName = rawName || 'Người dùng';
        const rawAvatar = (authorObj && typeof authorObj.avatarUrl === 'string') ? authorObj.avatarUrl.trim() : '';
        const sanitized = sanitizeAvatarUrl(rawAvatar);
        const legacyPrefix = options && options.legacyPrefix;

        if (sanitized) {
            const img = d.createElement('img');
            let cls = 'kl-comment__avatar';
            if (legacyPrefix) {
                cls += ' ' + legacyPrefix + '-avatar';
            }
            img.className = cls;
            img.src = sanitized;
            img.setAttribute('src', sanitized);
            img.alt = displayName;
            img.setAttribute('alt', displayName);
            img.setAttribute('referrerpolicy', 'no-referrer');
            img.onerror = function () {
                const parent = img.parentNode;
                if (parent) {
                    const fallback = createAvatarFallback(displayName, d, options);
                    if (typeof parent.replaceChild === 'function') {
                        parent.replaceChild(fallback, img);
                    } else if (typeof parent.removeChild === 'function') {
                        parent.removeChild(img);
                        parent.appendChild(fallback);
                    }
                }
            };
            return img;
        }

        return createAvatarFallback(displayName, d, options);
    }

    /**
     * Resolves the shared RelativeTime engine instance.
     *
     * @returns {Object|null}
     */
    function resolveRelativeTime() {
        if (injectedRelativeTime) {
            return injectedRelativeTime;
        }
        if (typeof RelativeTime !== 'undefined') {
            return RelativeTime;
        }
        if (typeof window !== 'undefined' && window.RelativeTime) {
            return window.RelativeTime;
        }
        if (typeof globalThis !== 'undefined' && globalThis.RelativeTime) {
            return globalThis.RelativeTime;
        }
        return null;
    }

    /**
     * Formats ISO timestamp to localized readable string (DD/MM/YYYY HH:mm).
     * Returns empty string for invalid/missing timestamp without throwing.
     *
     * @param {string|number} isoString
     * @returns {string}
     */
    function formatTimestamp(isoString) {
        const rt = resolveRelativeTime();
        if (rt && typeof rt.formatAbsolute === 'function') {
            return rt.formatAbsolute(isoString);
        }
        return '';
    }

    /**
     * Checks if a comment has been edited by comparing createdAt and updatedAt timestamps.
     *
     * @param {Object} comment
     * @returns {boolean}
     */
    function isCommentEdited(comment) {
        if (!comment || typeof comment !== 'object') {
            return false;
        }
        if (comment.edited === true) {
            return true;
        }
        if (!comment.createdAt || !comment.updatedAt) {
            return false;
        }
        try {
            const createdMs = new Date(comment.createdAt).getTime();
            const updatedMs = new Date(comment.updatedAt).getTime();
            return Number.isFinite(createdMs) && Number.isFinite(updatedMs) && updatedMs > createdMs;
        } catch (_) {
            return false;
        }
    }

    /**
     * Closes the currently active overflow menu and optionally restores focus.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeActiveMenu(restoreFocus) {
        if (!activeOpenMenu) {
            return;
        }
        const { triggerEl, menuEl, containerEl } = activeOpenMenu;
        activeOpenMenu = null;

        if (triggerEl) {
            triggerEl.setAttribute('aria-expanded', 'false');
            if (restoreFocus && typeof triggerEl.focus === 'function') {
                try {
                    triggerEl.focus();
                } catch (_) {}
            }
        }
        if (menuEl) {
            menuEl.hidden = true;
            menuEl.setAttribute('hidden', '');
        }
        if (containerEl && containerEl.classList && typeof containerEl.classList.remove === 'function') {
            containerEl.classList.remove('is-open');
        }
    }

    /**
     * Opens a specific overflow menu, closing any previously open menu first.
     *
     * @param {Element} triggerEl
     * @param {Element} menuEl
     * @param {Element} containerEl
     */
    function openMenu(triggerEl, menuEl, containerEl) {
        if (activeOpenMenu && activeOpenMenu.triggerEl === triggerEl) {
            closeActiveMenu(false);
            return;
        }
        closeActiveMenu(false);

        activeOpenMenu = { triggerEl: triggerEl, menuEl: menuEl, containerEl: containerEl };
        triggerEl.setAttribute('aria-expanded', 'true');
        menuEl.hidden = false;
        menuEl.removeAttribute('hidden');
        if (containerEl && containerEl.classList && typeof containerEl.classList.add === 'function') {
            containerEl.classList.add('is-open');
        }
    }

    /**
     * Handles document click events to dismiss active menu on outside click.
     *
     * @param {Event} e
     */
    function onDocumentClick(e) {
        if (!activeOpenMenu) {
            return;
        }
        const target = (e && e.target) ? e.target : null;
        const container = activeOpenMenu.containerEl;
        if (container && target) {
            if (typeof container.contains === 'function' && container.contains(target)) {
                return;
            }
        }
        closeActiveMenu(false);
    }

    /**
     * Handles document keydown events (Escape dismisses active menu and restores focus).
     *
     * @param {KeyboardEvent} e
     */
    function onDocumentKeydown(e) {
        if (!activeOpenMenu) {
            return;
        }
        if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
            if (typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            closeActiveMenu(true);
        }
    }

    /**
     * Binds document-level event listeners for outside click and Escape dismissal.
     *
     * @param {Document} doc
     */
    function bindDocument(doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || d === activeBoundDocument) {
            return;
        }
        unbindDocument(activeBoundDocument);

        activeBoundDocument = d;
        boundDocClickHandler = onDocumentClick;
        boundDocKeydownHandler = onDocumentKeydown;

        if (typeof d.addEventListener === 'function') {
            d.addEventListener('click', boundDocClickHandler);
            d.addEventListener('keydown', boundDocKeydownHandler);
        }
    }

    /**
     * Unbinds document-level event listeners and closes any open menu.
     *
     * @param {Document} [doc]
     */
    function unbindDocument(doc) {
        const d = doc || activeBoundDocument;
        if (d && typeof d.removeEventListener === 'function') {
            if (boundDocClickHandler) {
                d.removeEventListener('click', boundDocClickHandler);
            }
            if (boundDocKeydownHandler) {
                d.removeEventListener('keydown', boundDocKeydownHandler);
            }
        }
        if (d === activeBoundDocument) {
            activeBoundDocument = null;
            boundDocClickHandler = null;
            boundDocKeydownHandler = null;
        }
        closeActiveMenu(false);
    }

    /**
     * Renders the standard three-dot overflow actions menu from normalized action descriptors.
     *
     * Accepts either:
     * - An array of normalized descriptor objects:
     *   [ { key, label, className, attributes, danger, disabled, separatorBefore }, ... ]
     * - An options object:
     *   { items: [ ... ], legacyPrefix: string }
     *
     * Shared presentation does NOT infer domain capabilities. It renders descriptors strictly
     * in the supplied array order.
     *
     * @param {Array|Object} options
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function renderActionsMenu(options, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        if (!options) {
            return null;
        }

        const items = Array.isArray(options)
            ? options
            : (typeof options === 'object' && Array.isArray(options.items) ? options.items : null);

        if (!items || items.length === 0) {
            return null;
        }

        const legacyPrefix = (!Array.isArray(options) && options && options.legacyPrefix)
            ? options.legacyPrefix
            : null;

        bindDocument(d);

        const container = d.createElement('div');
        let containerCls = 'kl-comment__overflow';
        if (legacyPrefix) {
            containerCls += ' ' + legacyPrefix + '-actions-menu';
        }
        container.className = containerCls;

        const triggerBtn = d.createElement('button');
        triggerBtn.type = 'button';
        let triggerCls = 'kl-comment__menu-trigger';
        if (legacyPrefix) {
            triggerCls += ' ' + legacyPrefix + '-menu-trigger';
        }
        triggerBtn.className = triggerCls;
        triggerBtn.setAttribute('aria-label', 'Mở menu bình luận');
        triggerBtn.setAttribute('aria-haspopup', 'menu');
        triggerBtn.setAttribute('aria-expanded', 'false');

        const dotsSpan = d.createElement('span');
        let dotsCls = 'kl-comment__menu-dots';
        if (legacyPrefix) {
            dotsCls += ' ' + legacyPrefix + '-menu-dots';
        }
        dotsSpan.className = dotsCls;
        dotsSpan.setAttribute('aria-hidden', 'true');
        dotsSpan.textContent = '⋯';
        triggerBtn.appendChild(dotsSpan);

        const menuEl = d.createElement('div');
        let menuCls = 'kl-comment__menu';
        if (legacyPrefix) {
            menuCls += ' ' + legacyPrefix + '-menu-popover';
        }
        menuEl.className = menuCls;
        menuEl.setAttribute('role', 'menu');
        menuEl.hidden = true;
        menuEl.setAttribute('hidden', '');

        for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (!item) continue;

            if (item.separatorBefore) {
                const sep = d.createElement('div');
                let sepCls = 'kl-comment__menu-separator';
                if (legacyPrefix) {
                    sepCls += ' ' + legacyPrefix + '-menu-separator';
                }
                sep.className = sepCls;
                sep.setAttribute('role', 'separator');
                menuEl.appendChild(sep);
            }

            const itemBtn = d.createElement('button');
            itemBtn.type = 'button';
            let itemCls = 'kl-comment__menu-item';
            if (legacyPrefix) {
                itemCls += ' ' + legacyPrefix + '-menu-item';
            }
            if (item.danger) {
                itemCls += ' kl-comment__menu-item--danger';
            }
            if (item.className) {
                itemCls += ' ' + item.className;
            }
            itemBtn.className = itemCls;
            itemBtn.setAttribute('role', 'menuitem');

            if (item.attributes && typeof item.attributes === 'object') {
                for (const [k, v] of Object.entries(item.attributes)) {
                    if (v !== undefined && v !== null) {
                        itemBtn.setAttribute(k, String(v));
                    }
                }
            }

            if (item.disabled) {
                itemBtn.disabled = true;
            }

            itemBtn.textContent = item.label || '';

            menuEl.appendChild(itemBtn);
        }

        // Generic presentation-level menu listener:
        // Closes the menu when an enabled menu item button is activated.
        // Does NOT attach business listeners directly to individual buttons.
        // Does NOT call preventDefault(), stopPropagation(), or domain callbacks.
        // The click event continues bubbling to document for consumer delegation.
        menuEl.addEventListener('click', function (event) {
            const target = (event && event.target) ? event.target : null;
            if (!target) return;
            let itemBtn = null;
            if (typeof target.closest === 'function') {
                itemBtn = target.closest('[role="menuitem"]') || target.closest('button');
            } else if (target.getAttribute && (target.getAttribute('role') === 'menuitem' || target.tagName === 'BUTTON')) {
                itemBtn = target;
            }
            if (itemBtn && !itemBtn.disabled && (typeof menuEl.contains === 'function' ? menuEl.contains(itemBtn) : true)) {
                closeActiveMenu(false);
            }
        });

        triggerBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (e && typeof e.stopPropagation === 'function') {
                e.stopPropagation();
            }
            if (activeOpenMenu && activeOpenMenu.triggerEl === triggerBtn) {
                closeActiveMenu(false);
            } else {
                openMenu(triggerBtn, menuEl, container);
            }
        });

        container.appendChild(triggerBtn);
        container.appendChild(menuEl);

        return container;
    }

    /**
     * Creates a reaction widget host element for an active comment.
     *
     * @param {string|UUID} commentId
     * @param {Object} reactionSummary
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function createReactionHost(commentId, reactionSummary, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || !commentId || !reactionSummary || typeof reactionSummary !== 'object') {
            return null;
        }

        const host = d.createElement('div');
        host.className = 'kl-reaction-widget';
        host.setAttribute('data-reaction-widget', '');
        host.setAttribute('data-reaction-target-type', 'COMMENT');
        host.setAttribute('data-reaction-target-id', String(commentId));

        if (reactionSummary.currentUserReaction) {
            host.setAttribute('data-reaction-current', reactionSummary.currentUserReaction);
        }
        if (reactionSummary.totalCount !== undefined && reactionSummary.totalCount !== null) {
            host.setAttribute('data-reaction-total', String(reactionSummary.totalCount));
        }
        const counts = reactionSummary.counts || {};
        host.setAttribute('data-reaction-count-like', String(counts.LIKE || 0));
        host.setAttribute('data-reaction-count-love', String(counts.LOVE || 0));
        host.setAttribute('data-reaction-count-fire', String(counts.FIRE || 0));
        host.setAttribute('data-reaction-count-haha', String(counts.HAHA || 0));
        host.setAttribute('data-reaction-count-sad', String(counts.SAD || 0));

        return host;
    }

    /**
     * Renders a full comment card element (root or reply, active or tombstone).
     *
     * @param {Object} descriptor
     * @param {Document} [doc]
     * @param {Object} [options]
     * @param {number|Date} [options.now]
     * @returns {Element}
     */
    function renderComment(descriptor, doc, options) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        bindDocument(d);

        const desc = descriptor || {};
        const opts = options || {};
        const renderNow = opts.now || desc.now;
        const legacyPrefix = desc.legacyPrefix;
        const tag = desc.tag || 'article';
        const commentEl = d.createElement(tag);

        let baseCls = 'kl-comment';
        if (legacyPrefix) {
            baseCls += ' ' + legacyPrefix;
        }
        if (desc.className) {
            baseCls += ' ' + desc.className;
        }
        commentEl.className = baseCls;

        // Attach custom HTML / data attributes
        if (desc.attributes && typeof desc.attributes === 'object') {
            for (const [k, v] of Object.entries(desc.attributes)) {
                if (v !== undefined && v !== null) {
                    commentEl.setAttribute(k, String(v));
                }
            }
        }

        // Tombstone branch
        if (desc.tombstone === true) {
            if (commentEl.classList && typeof commentEl.classList.add === 'function') {
                commentEl.classList.add('kl-comment--tombstone');
                commentEl.classList.add('is-tombstone');
            }

            const tombBody = d.createElement('div');
            let bodyCls = 'kl-comment__body kl-comment__body--tombstone';
            if (legacyPrefix) {
                bodyCls += ' ' + legacyPrefix + '-body ' + legacyPrefix + '-body--tombstone';
            }
            tombBody.className = bodyCls;

            if (desc.tombstoneContent) {
                if (typeof desc.tombstoneContent === 'string') {
                    tombBody.textContent = desc.tombstoneContent;
                } else if (Array.isArray(desc.tombstoneContent)) {
                    for (let i = 0; i < desc.tombstoneContent.length; i++) {
                        const node = desc.tombstoneContent[i];
                        if (typeof node === 'string') {
                            if (typeof d.createTextNode === 'function') {
                                tombBody.appendChild(d.createTextNode(node));
                            } else {
                                const s = d.createElement('span');
                                s.textContent = node;
                                tombBody.appendChild(s);
                            }
                        } else if (node && node.nodeType) {
                            tombBody.appendChild(node);
                        }
                    }
                } else if (desc.tombstoneContent.nodeType) {
                    tombBody.appendChild(desc.tombstoneContent);
                }
            } else {
                tombBody.textContent = 'Bình luận đã bị xóa.';
            }

            commentEl.appendChild(tombBody);
            return commentEl;
        }

        // Active comment: Header
        const header = d.createElement('header');
        let headerCls = 'kl-comment__header';
        if (legacyPrefix) {
            headerCls += ' ' + legacyPrefix + '-header';
        }
        header.className = headerCls;

        // Author details & links
        const authorObj = (desc.author && typeof desc.author === 'object') ? desc.author : null;
        const authorName = (authorObj && typeof authorObj.displayName === 'string' && authorObj.displayName.trim())
            ? authorObj.displayName.trim()
            : 'Người dùng';
        const publicHandle = (authorObj && typeof authorObj.publicHandle === 'string')
            ? authorObj.publicHandle.trim()
            : '';
        const hasHandle = publicHandle.length > 0;
        const profileUrl = hasHandle ? '/community/@' + encodeURIComponent(publicHandle) : null;

        // Avatar
        const avatarEl = renderAvatar(desc.author, d, { legacyPrefix: legacyPrefix });
        if (avatarEl) {
            if (hasHandle) {
                const avatarLink = d.createElement('a');
                let linkCls = 'kl-comment__avatar-link';
                if (legacyPrefix) {
                    linkCls += ' ' + legacyPrefix + '-avatar-link';
                }
                avatarLink.className = linkCls;
                avatarLink.href = profileUrl;
                avatarLink.setAttribute('href', profileUrl);
                avatarLink.appendChild(avatarEl);
                header.appendChild(avatarLink);
            } else {
                header.appendChild(avatarEl);
            }
        }

        // Author Name
        const authorEl = d.createElement(hasHandle ? 'a' : 'span');
        let authorCls = 'kl-comment__author';
        if (legacyPrefix) {
            authorCls += ' ' + legacyPrefix + '-author';
        }
        authorEl.className = authorCls;
        authorEl.textContent = authorName;
        if (hasHandle) {
            authorEl.href = profileUrl;
            authorEl.setAttribute('href', profileUrl);
        }
        header.appendChild(authorEl);

        // Timestamp
        const rt = resolveRelativeTime();
        if (desc.createdAt || desc.formattedTime) {
            const timeEl = d.createElement('time');
            let timeCls = 'kl-comment__time';
            if (legacyPrefix) {
                timeCls += ' ' + legacyPrefix + '-time';
            }
            timeEl.className = timeCls;
            timeEl.setAttribute('data-relative-time', '');
            if (desc.createdAt) {
                timeEl.setAttribute('datetime', String(desc.createdAt));
            }
            if (rt && typeof rt.formatElement === 'function') {
                const formatted = rt.formatElement(timeEl, renderNow);
                if (!formatted && desc.formattedTime) {
                    timeEl.textContent = desc.formattedTime;
                }
            } else {
                timeEl.textContent = desc.formattedTime || formatTimestamp(desc.createdAt);
            }
            if (timeEl.textContent) {
                header.appendChild(timeEl);
            }
        }

        // Edited Indicator
        if (desc.edited) {
            const editedSpan = d.createElement('span');
            let editedCls = 'kl-comment__edited';
            if (legacyPrefix) {
                editedCls += ' ' + legacyPrefix + '-edited';
            }
            editedSpan.className = editedCls;
            editedSpan.textContent = 'đã chỉnh sửa';
            header.appendChild(editedSpan);
        }

        // Overflow Actions Menu (expects normalized action descriptor array or options object)
        if (desc.overflowActions) {
            let menuEl = null;
            if (Array.isArray(desc.overflowActions)) {
                menuEl = renderActionsMenu({
                    items: desc.overflowActions,
                    legacyPrefix: legacyPrefix
                }, d);
            } else if (typeof desc.overflowActions === 'object' && Array.isArray(desc.overflowActions.items)) {
                menuEl = renderActionsMenu(Object.assign({ legacyPrefix: legacyPrefix }, desc.overflowActions), d);
            }
            if (menuEl) {
                header.appendChild(menuEl);
            }
        }

        commentEl.appendChild(header);

        // Active comment: Body
        const bodyEl = d.createElement('div');
        let bodyCls = 'kl-comment__body';
        if (legacyPrefix) {
            bodyCls += ' ' + legacyPrefix + '-body';
        }
        bodyEl.className = bodyCls;

        if (typeof desc.body === 'string') {
            bodyEl.textContent = desc.body;
        } else if (typeof desc.body === 'function') {
            desc.body(bodyEl, d);
        } else if (Array.isArray(desc.body)) {
            for (let b = 0; b < desc.body.length; b++) {
                const bNode = desc.body[b];
                if (typeof bNode === 'string') {
                    if (typeof d.createTextNode === 'function') {
                        bodyEl.appendChild(d.createTextNode(bNode));
                    } else {
                        const s = d.createElement('span');
                        s.textContent = bNode;
                        bodyEl.appendChild(s);
                    }
                } else if (bNode && bNode.nodeType) {
                    bodyEl.appendChild(bNode);
                }
            }
        } else if (desc.body && desc.body.nodeType) {
            bodyEl.appendChild(desc.body);
        }

        commentEl.appendChild(bodyEl);

        // Active comment: Primary Actions & Reactions (e.g. Reaction picker, Reply button)
        const hasPrimaryActions = Array.isArray(desc.primaryActions) && desc.primaryActions.length > 0;
        const hasReactions = !desc.tombstone && desc.id && desc.reactionSummary != null;

        if (hasPrimaryActions || hasReactions) {
            const actionsEl = d.createElement('div');
            let actionsCls = 'kl-comment__primary-actions';
            if (legacyPrefix) {
                actionsCls += ' ' + legacyPrefix + '-actions';
            }
            actionsEl.className = actionsCls;

            if (hasReactions) {
                const reactionHost = createReactionHost(desc.id, desc.reactionSummary, d);
                if (reactionHost) {
                    actionsEl.appendChild(reactionHost);
                }
            }

            if (hasPrimaryActions) {
                for (let a = 0; a < desc.primaryActions.length; a++) {
                    const act = desc.primaryActions[a];
                    if (!act) continue;

                    const actBtn = d.createElement('button');
                    actBtn.type = 'button';
                    let actCls = 'kl-comment__primary-action';
                    if (act.className) {
                        actCls += ' ' + act.className;
                    }
                    actBtn.className = actCls;

                    if (act.attributes && typeof act.attributes === 'object') {
                        for (const [ak, av] of Object.entries(act.attributes)) {
                            if (av !== undefined && av !== null) {
                                actBtn.setAttribute(ak, String(av));
                            }
                        }
                    }

                    if (act.disabled) {
                        actBtn.disabled = true;
                    }
                    actBtn.textContent = act.label || '';

                    actionsEl.appendChild(actBtn);
                }
            }

            commentEl.appendChild(actionsEl);
        }

        return commentEl;
    }

    const SORT_MODES = Object.freeze({
        FEATURED: 'FEATURED',
        NEWEST: 'NEWEST'
    });

    /**
     * Renders canonical comment/feed sort dropdown.
     *
     * @param {Object} options
     * @param {string} [options.currentValue] - 'NEWEST' or 'FEATURED' (defaults to 'NEWEST')
     * @param {string} [options.currentSort] - fallback alias for currentValue
     * @param {Array<{value: string, label: string}>} [options.options] - options list
     * @param {string} [options.targetId] - target entity ID (e.g. postId, articleId, chapterId)
     * @param {string} [options.actionName] - action name on options, defaults to 'change-comment-sort'
     * @param {string} [options.ariaLabel] - aria-label, defaults to 'Sắp xếp bình luận'
     * @param {string} [options.className] - optional container className
     * @param {string} [options.id] - optional container ID
     * @param {string} [options.triggerId] - optional trigger ID
     * @param {Function} [options.onSelect] - optional callback(value)
     * @param {Document} [doc] - optional document
     * @returns {HTMLElement|null}
     */
    function renderSortDropdown(options, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        bindDocument(d);

        const opts = options || {};
        const rawCurrent = opts.currentValue || opts.currentSort;
        const current = (rawCurrent === SORT_MODES.FEATURED) ? SORT_MODES.FEATURED : SORT_MODES.NEWEST;
        const targetId = opts.targetId ? String(opts.targetId) : '';
        const customClass = opts.className ? ' ' + opts.className : '';
        const actionName = opts.actionName || 'change-comment-sort';
        const ariaLabel = opts.ariaLabel || 'Sắp xếp bình luận';

        const dropdownOptions = Array.isArray(opts.options) && opts.options.length > 0
            ? opts.options
            : [
                { value: SORT_MODES.NEWEST, label: 'Mới nhất' },
                { value: SORT_MODES.FEATURED, label: 'Nổi bật' }
            ];

        const container = d.createElement('div');
        container.className = 'kl-sort-dropdown' + customClass;
        container.setAttribute('data-sort-dropdown', '');
        container.setAttribute('role', 'region');
        container.setAttribute('aria-label', ariaLabel);
        if (opts.id) {
            container.id = String(opts.id);
        }
        if (targetId) {
            container.setAttribute('data-target-id', targetId);
            container.setAttribute('data-post-id', targetId);
        }

        const currentOpt = dropdownOptions.find(function (o) { return o.value === current; }) || dropdownOptions[0];
        const currentLabel = currentOpt ? currentOpt.label : 'Mới nhất';

        const trigger = d.createElement('button');
        trigger.type = 'button';
        trigger.className = 'kl-sort-dropdown__trigger';
        trigger.setAttribute('aria-haspopup', 'menu');
        trigger.setAttribute('aria-expanded', 'false');
        trigger.setAttribute('data-action', 'toggle-sort-dropdown');
        if (opts.triggerId) {
            trigger.id = String(opts.triggerId);
        }

        const labelSpan = d.createElement('span');
        labelSpan.className = 'kl-sort-dropdown__label';
        labelSpan.textContent = currentLabel;
        trigger.appendChild(labelSpan);

        const chevron = d.createElement('i');
        chevron.className = 'fa-solid fa-chevron-down kl-sort-dropdown__chevron';
        chevron.setAttribute('aria-hidden', 'true');
        trigger.appendChild(chevron);

        const menu = d.createElement('div');
        menu.className = 'kl-sort-dropdown__menu';
        menu.setAttribute('role', 'menu');
        menu.hidden = true;
        menu.setAttribute('hidden', '');

        dropdownOptions.forEach(function (opt) {
            const itemBtn = d.createElement('button');
            itemBtn.type = 'button';
            const isSelected = (opt.value === current);
            itemBtn.className = 'kl-sort-dropdown__item' + (isSelected ? ' is-selected' : '');
            itemBtn.setAttribute('role', 'menuitemradio');
            itemBtn.setAttribute('aria-checked', isSelected ? 'true' : 'false');
            itemBtn.setAttribute('data-action', actionName);
            itemBtn.setAttribute('data-sort-mode', opt.value);
            if (targetId) {
                itemBtn.setAttribute('data-target-id', targetId);
                itemBtn.setAttribute('data-post-id', targetId);
            }

            const check = d.createElement('i');
            check.className = 'fa-solid fa-check kl-sort-dropdown__check';
            check.setAttribute('aria-hidden', 'true');
            itemBtn.appendChild(check);

            const textSpan = d.createElement('span');
            textSpan.className = 'kl-sort-dropdown__text';
            textSpan.textContent = opt.label;
            itemBtn.appendChild(textSpan);

            menu.appendChild(itemBtn);
        });

        // Trigger click toggle
        trigger.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
            if (activeOpenMenu && activeOpenMenu.triggerEl === trigger) {
                closeActiveMenu(false);
            } else {
                openMenu(trigger, menu, container);
            }
        });

        // Menu item click listener: close menu and update presentation state
        menu.addEventListener('click', function (event) {
            const target = event && event.target ? event.target : null;
            if (!target) return;
            let itemBtn = null;
            if (typeof target.closest === 'function') {
                itemBtn = target.closest('.kl-sort-dropdown__item');
            } else if (target.getAttribute && target.getAttribute('role') === 'menuitemradio') {
                itemBtn = target;
            }
            if (itemBtn && !itemBtn.disabled) {
                const val = itemBtn.getAttribute('data-sort-mode');
                const optObj = dropdownOptions.find(function (o) { return o.value === val; });
                if (optObj) {
                    labelSpan.textContent = optObj.label;
                    const items = menu.querySelectorAll('.kl-sort-dropdown__item');
                    items.forEach(function (btn) {
                        const isThis = btn.getAttribute('data-sort-mode') === val;
                        if (isThis) {
                            btn.classList.add('is-selected');
                            btn.setAttribute('aria-checked', 'true');
                        } else {
                            btn.classList.remove('is-selected');
                            btn.setAttribute('aria-checked', 'false');
                        }
                    });
                }
                closeActiveMenu(false);
                if (typeof opts.onSelect === 'function') {
                    opts.onSelect(val);
                }
            }
        });

        container.appendChild(trigger);
        container.appendChild(menu);

        return container;
    }

    /**
     * Updates an existing sort dropdown's active selection and label.
     *
     * @param {HTMLElement} dropdownEl
     * @param {string} activeSort
     */
    function updateSortDropdown(dropdownEl, activeSort) {
        if (!dropdownEl) return;
        const isFeatured = (activeSort === SORT_MODES.FEATURED);
        const labelEl = dropdownEl.querySelector('.kl-sort-dropdown__label');
        if (labelEl) {
            labelEl.textContent = isFeatured ? 'Nổi bật' : 'Mới nhất';
        }
        const items1 = dropdownEl.querySelectorAll('.kl-sort-dropdown__item');
        const items2 = dropdownEl.querySelectorAll('.kl-comment-sort-btn');
        const items = (Array.isArray(items1) ? items1 : Array.from(items1))
            .concat(Array.isArray(items2) ? items2 : Array.from(items2));
        items.forEach(function (btn) {
            const mode = btn.getAttribute('data-sort-mode');
            const isActive = (mode === activeSort);
            if (isActive) {
                btn.classList.add('is-selected');
                btn.classList.add('is-active');
                btn.setAttribute('aria-checked', 'true');
                btn.setAttribute('aria-pressed', 'true');
            } else {
                btn.classList.remove('is-selected');
                btn.classList.remove('is-active');
                btn.setAttribute('aria-checked', 'false');
                btn.setAttribute('aria-pressed', 'false');
            }
        });
    }

    /**
     * Renders canonical comment sort controls. Delegates to renderSortDropdown.
     *
     * @param {Object} options
     * @param {Document} [doc]
     * @returns {HTMLElement}
     */
    function renderSortControls(options, doc) {
        return renderSortDropdown(options, doc);
    }

    return {
        SORT_MODES: SORT_MODES,
        renderSortDropdown: renderSortDropdown,
        updateSortDropdown: updateSortDropdown,
        renderSortControls: renderSortControls,
        sanitizeAvatarUrl: sanitizeAvatarUrl,
        createAvatarFallback: createAvatarFallback,
        renderAvatar: renderAvatar,
        formatTimestamp: formatTimestamp,
        isCommentEdited: isCommentEdited,
        createReactionHost: createReactionHost,
        renderActionsMenu: renderActionsMenu,
        createActionsMenu: renderActionsMenu, // backward compatibility alias
        renderComment: renderComment,
        openMenu: openMenu,
        closeActiveMenu: closeActiveMenu,
        getActiveOpenMenu: function () { return activeOpenMenu; },
        bindDocument: bindDocument,
        unbindDocument: unbindDocument
    };
});
