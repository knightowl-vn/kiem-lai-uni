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
        module.exports = factory();
    } else {
        const exports = factory();
        root.CommentPresentation = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommentPresentation = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
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
     * Formats ISO timestamp to localized readable string (DD/MM/YYYY HH:mm).
     * Returns empty string for invalid/missing timestamp without throwing.
     *
     * @param {string|number} isoString
     * @returns {string}
     */
    function formatTimestamp(isoString) {
        if (!isoString) {
            return '';
        }
        try {
            const date = new Date(isoString);
            if (isNaN(date.getTime())) {
                return '';
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            return day + '/' + month + '/' + year + ' ' + hours + ':' + minutes;
        } catch (_) {
            return '';
        }
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
     * Renders a full comment card element (root or reply, active or tombstone).
     *
     * @param {Object} descriptor
     * @param {Document} [doc]
     * @returns {Element}
     */
    function renderComment(descriptor, doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        bindDocument(d);

        const desc = descriptor || {};
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

        // Avatar
        const avatarEl = renderAvatar(desc.author, d, { legacyPrefix: legacyPrefix });
        if (avatarEl) {
            header.appendChild(avatarEl);
        }

        // Author Name
        const authorObj = (desc.author && typeof desc.author === 'object') ? desc.author : null;
        const authorName = (authorObj && typeof authorObj.displayName === 'string' && authorObj.displayName.trim())
            ? authorObj.displayName.trim()
            : 'Người dùng';

        const authorSpan = d.createElement('span');
        let authorCls = 'kl-comment__author';
        if (legacyPrefix) {
            authorCls += ' ' + legacyPrefix + '-author';
        }
        authorSpan.className = authorCls;
        authorSpan.textContent = authorName;
        header.appendChild(authorSpan);

        // Timestamp
        const timeStr = desc.formattedTime || formatTimestamp(desc.createdAt);
        if (timeStr) {
            const timeEl = d.createElement('time');
            let timeCls = 'kl-comment__time';
            if (legacyPrefix) {
                timeCls += ' ' + legacyPrefix + '-time';
            }
            timeEl.className = timeCls;
            if (desc.createdAt) {
                timeEl.setAttribute('datetime', String(desc.createdAt));
            }
            timeEl.textContent = timeStr;
            header.appendChild(timeEl);
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

        // Active comment: Primary Actions (e.g. Reply button)
        if (Array.isArray(desc.primaryActions) && desc.primaryActions.length > 0) {
            const actionsEl = d.createElement('div');
            let actionsCls = 'kl-comment__primary-actions';
            if (legacyPrefix) {
                actionsCls += ' ' + legacyPrefix + '-actions';
            }
            actionsEl.className = actionsCls;

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

            commentEl.appendChild(actionsEl);
        }

        return commentEl;
    }

    return {
        sanitizeAvatarUrl: sanitizeAvatarUrl,
        createAvatarFallback: createAvatarFallback,
        renderAvatar: renderAvatar,
        formatTimestamp: formatTimestamp,
        isCommentEdited: isCommentEdited,
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
