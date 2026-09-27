/**
 * KiemLai Universe — Global Navbar Notification Surface (MS-05K2)
 *
 * Responsibilities:
 * - Fetches initial unread notification count on authenticated page load.
 * - Manages unread badge rendering (hidden if 0, exact count 1..99, '99+' if >= 100).
 * - Manages desktop/tablet anchored popover and mobile full-screen panel toggle.
 * - Lazy-loads notification feed on first open (size = 20).
 * - Supports ALL and UNREAD filter switching with page-0 reset.
 * - Supports load-more pagination without item duplication.
 * - Supports mark-one-read and mark-all-read with server-authoritative state transitions and CSRF protection.
 * - Renders relative timestamps via RelativeTime engine or localized fallback.
 * - Handles keyboard accessibility (Tab, Enter/Space, Escape focus restoration) and mobile body scroll locking.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NavbarNotifications = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NavbarNotifications = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const PAGE_SIZE = 20;

    /**
     * Extracts CSRF token and header name from document meta tags or container data attributes.
     *
     * @param {Document} doc
     * @param {HTMLElement} [container]
     * @returns {{token: string, headerName: string}|null}
     */
    function getCsrfToken(doc, container) {
        if (!doc) {
            return null;
        }
        const metaToken = doc.querySelector('meta[name="_csrf"]');
        const metaHeader = doc.querySelector('meta[name="_csrf_header"]');
        if (metaToken && metaToken.content) {
            return {
                token: metaToken.content,
                headerName: metaHeader && metaHeader.content ? metaHeader.content : 'X-CSRF-TOKEN'
            };
        }
        if (container) {
            const dataToken = container.getAttribute('data-csrf-token');
            const dataHeader = container.getAttribute('data-csrf-header');
            if (dataToken && dataToken.trim().length > 0) {
                return {
                    token: dataToken.trim(),
                    headerName: dataHeader && dataHeader.trim().length > 0 ? dataHeader.trim() : 'X-CSRF-TOKEN'
                };
            }
        }
        return null;
    }

    /**
     * Formats badge text and visibility from unread count.
     *
     * @param {number} count
     * @returns {{text: string, hidden: boolean, srText: string}}
     */
    function calculateBadgeState(count) {
        const safeCount = Math.max(0, parseInt(count, 10) || 0);
        if (safeCount === 0) {
            return {
                text: '0',
                hidden: true,
                srText: '0 thông báo chưa đọc'
            };
        }
        if (safeCount >= 100) {
            return {
                text: '99+',
                hidden: false,
                srText: safeCount + ' thông báo chưa đọc'
            };
        }
        return {
            text: String(safeCount),
            hidden: false,
            srText: safeCount + ' thông báo chưa đọc'
        };
    }

    /**
     * Resolves presentation copy for a notification item according to NotificationType.
     *
     * @param {Object} item
     * @returns {{title: string, subtitle: string, detail: string, iconType: string}}
     */
    function getItemPresentation(item) {
        if (!item) {
            return { title: 'Thông báo', subtitle: '', detail: '', iconType: 'default' };
        }
        const type = item.type;
        const actor = item.actorDisplayNameSnapshot;
        const targetTitle = item.targetTitleSnapshot || '';
        const detail = item.detailSnapshot || '';

        switch (type) {
            case 'COMMENT_REPLY': {
                const title = actor && actor.trim().length > 0
                    ? actor + ' đã phản hồi bình luận của bạn'
                    : 'Có người đã phản hồi bình luận của bạn';
                return {
                    title: title,
                    subtitle: targetTitle,
                    detail: '', // Hard-delete privacy: never render reply/comment body
                    iconType: 'comment'
                };
            }
            case 'WIKI_CONTRIBUTION_REVIEWING': {
                return {
                    title: 'Đóng góp của bạn đang được kiểm duyệt',
                    subtitle: targetTitle,
                    detail: detail,
                    iconType: 'wiki'
                };
            }
            case 'WIKI_CONTRIBUTION_RESOLVED': {
                const title = detail && detail.trim().length > 0
                    ? 'Ban biên tập đã phản hồi đóng góp của bạn'
                    : 'Đóng góp của bạn đã được duyệt';
                return {
                    title: title,
                    subtitle: targetTitle,
                    detail: detail,
                    iconType: 'resolved'
                };
            }
            case 'WIKI_CONTRIBUTION_REJECTED': {
                const title = detail && detail.trim().length > 0
                    ? 'Ban biên tập đã phản hồi đóng góp của bạn'
                    : 'Đóng góp của bạn đã bị từ chối';
                return {
                    title: title,
                    subtitle: targetTitle,
                    detail: detail,
                    iconType: 'rejected'
                };
            }
            default: {
                return {
                    title: 'Thông báo mới',
                    subtitle: targetTitle,
                    detail: detail,
                    iconType: 'default'
                };
            }
        }
    }

    /**
     * Formats relative timestamp using shared RelativeTime engine if present, or pure fallback.
     *
     * @param {string|Date} dateInput
     * @param {Object} [globalScope]
     * @returns {string}
     */
    function formatRelativeTime(dateInput, globalScope) {
        const root = globalScope || (typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
        if (root && root.RelativeTime && typeof root.RelativeTime.format === 'function') {
            return root.RelativeTime.format(dateInput);
        }
        if (!dateInput) {
            return '';
        }
        const d = new Date(dateInput);
        if (isNaN(d.getTime())) {
            return '';
        }
        const diffMs = Date.now() - d.getTime();
        if (diffMs < 60000) return 'Vừa xong';
        if (diffMs < 3600000) return Math.floor(diffMs / 60000) + ' phút trước';
        if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + ' giờ trước';
        if (diffMs < 604800000) return Math.floor(diffMs / 86400000) + ' ngày trước';
        if (diffMs < 2592000000) return Math.floor(diffMs / 604800000) + ' tuần trước';
        const day = String(d.getDate()).padStart(2, '0');
        const month = String(d.getMonth() + 1).padStart(2, '0');
        const year = d.getFullYear();
        const hours = String(d.getHours()).padStart(2, '0');
        const mins = String(d.getMinutes()).padStart(2, '0');
        return day + '/' + month + '/' + year + ' ' + hours + ':' + mins;
    }

    /**
     * Builds an SVG icon string based on notification icon type.
     *
     * @param {string} iconType
     * @returns {string}
     */
    function getIconSvg(iconType) {
        switch (iconType) {
            case 'comment':
                return '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>';
            case 'resolved':
                return '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>';
            case 'rejected':
                return '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line></svg>';
            case 'wiki':
            default:
                return '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>';
        }
    }

    /**
     * Initializes the Notification Bell navbar component.
     *
     * @param {Document} doc
     * @param {Window} win
     * @returns {Object|null} Controller instance for testing/interaction
     */
    function initNavbarNotifications(doc, win) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        const w = win || (typeof window !== 'undefined' ? window : null);
        if (!d) {
            return null;
        }

        const container = d.getElementById('navbarNotifications');
        const bellBtn = d.getElementById('navbarBellButton');
        const badge = d.getElementById('navbarBellBadge');
        const badgeSr = d.getElementById('navbarBellBadgeSr');
        const panel = d.getElementById('navbarNotificationsPanel');
        const backBtn = d.getElementById('notifMobileBackButton');
        const markAllBtn = d.getElementById('notifMarkAllBtn');
        const filterAllBtn = d.getElementById('notifFilterAll');
        const filterUnreadBtn = d.getElementById('notifFilterUnread');
        const feedList = d.getElementById('notifFeedList');
        const feedFooter = d.getElementById('notifFeedFooter');
        const loadMoreBtn = d.getElementById('notifLoadMoreBtn');

        if (!container || !bellBtn || !panel) {
            return null;
        }

        // Component State
        let isOpen = false;
        let unreadCount = 0;
        let currentFilter = 'all';
        let currentPage = 0;
        let hasNextPage = false;
        let isLoading = false;
        let hasFetchedFeed = false;
        let loadedItems = [];

        /**
         * Updates UI badge display.
         */
        function updateBadge(count) {
            unreadCount = Math.max(0, count);
            const state = calculateBadgeState(unreadCount);
            if (badge) {
                badge.textContent = state.text;
                if (state.hidden) {
                    badge.setAttribute('hidden', '');
                } else {
                    badge.removeAttribute('hidden');
                }
            }
            if (badgeSr) {
                badgeSr.textContent = state.srText;
            }
            if (markAllBtn) {
                markAllBtn.disabled = (unreadCount === 0);
            }
        }

        /**
         * Closes the notification panel and cleans up body scroll locks.
         */
        function closePanel() {
            if (!isOpen) return;
            isOpen = false;
            panel.setAttribute('hidden', '');
            bellBtn.setAttribute('aria-expanded', 'false');
            if (d.body) {
                d.body.classList.remove('notifications-panel-open');
            }
        }

        /**
         * Opens the notification panel, handles mobile body lock, and triggers lazy initial feed fetch.
         */
        function openPanel() {
            if (isOpen) return;
            isOpen = true;
            panel.removeAttribute('hidden');
            bellBtn.setAttribute('aria-expanded', 'true');

            // Responsive mobile body scroll lock
            const isMobile = w && w.innerWidth < 768;
            if (isMobile && d.body) {
                d.body.classList.add('notifications-panel-open');
            }

            // Mutual exclusivity: close Wiki search if open
            const wikiSearch = d.getElementById('navbarWikiSearch');
            if (wikiSearch && wikiSearch.classList.contains('is-open')) {
                const searchToggle = d.getElementById('navbarWikiSearchToggle');
                if (searchToggle) searchToggle.click();
            }

            // Mutual exclusivity: blur focus from profile menu
            const profileMenu = d.querySelector('.profile-menu');
            if (profileMenu && d.activeElement && profileMenu.contains(d.activeElement)) {
                d.activeElement.blur();
            }

            // Lazy initial fetch on first open
            if (!hasFetchedFeed) {
                fetchFeed('all', 0, false);
            }
        }

        /**
         * Fetches unread count from server on initial load.
         */
        function fetchUnreadCount() {
            const fetchFn = (w && w.fetch) ? w.fetch.bind(w) : (typeof fetch === 'function' ? fetch : null);
            if (!fetchFn) return;

            fetchFn('/api/notifications/unread-count', {
                method: 'GET',
                headers: { 'Accept': 'application/json' }
            })
                .then(res => {
                    if (res.ok) return res.json();
                    return null;
                })
                .then(data => {
                    if (data && typeof data.unreadCount === 'number') {
                        updateBadge(data.unreadCount);
                    }
                })
                .catch(() => {
                    // Silently preserve badge on error
                });
        }

        /**
         * Renders empty, loading, or error states.
         */
        function renderStateMessage(type, message) {
            if (!feedList) return;
            feedList.innerHTML = '';
            const msgDiv = d.createElement('div');
            msgDiv.className = 'notif-' + type;

            if (type === 'error') {
                const span = d.createElement('span');
                span.textContent = message || 'Không thể tải thông báo.';
                const retryBtn = d.createElement('button');
                retryBtn.type = 'button';
                retryBtn.className = 'notif-retry-btn';
                retryBtn.textContent = 'Thử lại';
                retryBtn.addEventListener('click', () => {
                    fetchFeed(currentFilter, 0, false);
                });
                msgDiv.appendChild(span);
                msgDiv.appendChild(retryBtn);
            } else {
                msgDiv.textContent = message;
            }
            feedList.appendChild(msgDiv);
        }

        /**
         * Creates a DOM element representing a single notification item.
         */
        function createItemElement(item) {
            const pres = getItemPresentation(item);
            const hasActionUrl = !!(item.actionUrl && item.actionUrl.trim().length > 0);
            const itemEl = d.createElement(hasActionUrl ? 'a' : 'div');
            itemEl.className = 'notif-item' + (item.unread ? ' is-unread' : '');
            itemEl.setAttribute('data-id', item.id);
            itemEl.setAttribute('data-unread', String(item.unread));
            itemEl.setAttribute('tabindex', '0');

            if (hasActionUrl) {
                itemEl.href = item.actionUrl.trim();
            } else {
                itemEl.setAttribute('role', 'button');
            }

            // Icon
            const iconDiv = d.createElement('div');
            iconDiv.className = 'notif-item-icon';
            iconDiv.innerHTML = getIconSvg(pres.iconType);
            itemEl.appendChild(iconDiv);

            // Content container
            const contentDiv = d.createElement('div');
            contentDiv.className = 'notif-item-content';

            // Title
            const titleDiv = d.createElement('div');
            titleDiv.className = 'notif-item-title';
            titleDiv.textContent = pres.title;
            contentDiv.appendChild(titleDiv);

            // Subtitle
            if (pres.subtitle) {
                const subDiv = d.createElement('div');
                subDiv.className = 'notif-item-subtitle';
                subDiv.textContent = pres.subtitle;
                contentDiv.appendChild(subDiv);
            }

            // Detail Snapshot
            if (pres.detail) {
                const detailDiv = d.createElement('div');
                detailDiv.className = 'notif-item-detail';
                detailDiv.textContent = pres.detail;
                contentDiv.appendChild(detailDiv);
            }

            // Relative Time
            const timeEl = d.createElement('time');
            timeEl.className = 'notif-item-time';
            timeEl.setAttribute('datetime', item.createdAt);
            timeEl.setAttribute('data-relative-time', '');
            timeEl.textContent = formatRelativeTime(item.createdAt, w);
            contentDiv.appendChild(timeEl);

            itemEl.appendChild(contentDiv);

            // Unread Dot
            const dotSpan = d.createElement('span');
            dotSpan.className = 'notif-unread-dot';
            dotSpan.setAttribute('aria-hidden', 'true');
            itemEl.appendChild(dotSpan);

            // Click Interaction
            function handleItemClick(e) {
                if (item.unread) {
                    const csrf = getCsrfToken(d, container);
                    const headers = { 'Accept': 'application/json' };
                    if (csrf) {
                        headers[csrf.headerName] = csrf.token;
                    }

                    const fetchFn = (w && w.fetch) ? w.fetch.bind(w) : fetch;
                    fetchFn('/api/notifications/' + encodeURIComponent(item.id) + '/read', {
                        method: 'PUT',
                        headers: headers
                    })
                        .then(res => {
                            if (res.status === 204 || res.ok) {
                                item.unread = false;
                                item.readAt = new Date().toISOString();
                                updateBadge(unreadCount - 1);

                                if (currentFilter === 'unread') {
                                    loadedItems = loadedItems.filter(i => i.id !== item.id);
                                    if (itemEl.parentElement) {
                                        itemEl.parentElement.removeChild(itemEl);
                                    }
                                    if (loadedItems.length === 0) {
                                        renderStateMessage('empty', 'Bạn đã xem hết thông báo.');
                                        if (feedFooter) feedFooter.setAttribute('hidden', '');
                                    }
                                } else {
                                    itemEl.classList.remove('is-unread');
                                    itemEl.setAttribute('data-unread', 'false');
                                }

                                if (hasActionUrl) {
                                    if (w && w.location) {
                                        w.location.href = item.actionUrl.trim();
                                    }
                                }
                            }
                        })
                        .catch(() => {
                            // Mutation failed; do not fake read state or navigate
                        });
                } else if (hasActionUrl) {
                    if (w && w.location) {
                        w.location.href = item.actionUrl.trim();
                    }
                }
            }

            itemEl.addEventListener('click', (e) => {
                if (hasActionUrl) {
                    e.preventDefault();
                }
                handleItemClick(e);
            });

            itemEl.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    handleItemClick(e);
                }
            });

            return itemEl;
        }

        /**
         * Fetches notification feed from server with pagination and active filter.
         */
        function fetchFeed(filter, page, isAppend) {
            if (isLoading) return;
            isLoading = true;

            if (!isAppend) {
                renderStateMessage('loading', 'Đang tải thông báo...');
                if (feedFooter) feedFooter.setAttribute('hidden', '');
            } else if (loadMoreBtn) {
                loadMoreBtn.disabled = true;
                loadMoreBtn.textContent = 'Đang tải...';
            }

            const fetchFn = (w && w.fetch) ? w.fetch.bind(w) : fetch;
            fetchFn('/api/notifications?filter=' + encodeURIComponent(filter) + '&page=' + page + '&size=' + PAGE_SIZE, {
                method: 'GET',
                headers: { 'Accept': 'application/json' }
            })
                .then(res => {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.json();
                })
                .then(pageData => {
                    isLoading = false;
                    hasFetchedFeed = true;
                    currentPage = pageData.page || 0;
                    hasNextPage = !!pageData.hasNext;

                    const newItems = pageData.items || [];
                    if (!isAppend) {
                        loadedItems = newItems;
                        feedList.innerHTML = '';
                    } else {
                        loadedItems = loadedItems.concat(newItems);
                    }

                    if (loadedItems.length === 0) {
                        const emptyMsg = filter === 'unread'
                            ? 'Bạn đã xem hết thông báo.'
                            : 'Chưa có thông báo nào.';
                        renderStateMessage('empty', emptyMsg);
                        if (feedFooter) feedFooter.setAttribute('hidden', '');
                    } else {
                        newItems.forEach(item => {
                            feedList.appendChild(createItemElement(item));
                        });

                        if (feedFooter) {
                            if (hasNextPage) {
                                feedFooter.removeAttribute('hidden');
                                if (loadMoreBtn) {
                                    loadMoreBtn.disabled = false;
                                    loadMoreBtn.textContent = 'Xem thêm';
                                }
                            } else {
                                feedFooter.setAttribute('hidden', '');
                            }
                        }
                    }
                })
                .catch(err => {
                    isLoading = false;
                    if (!isAppend) {
                        renderStateMessage('error', 'Không thể tải thông báo.');
                        if (feedFooter) feedFooter.setAttribute('hidden', '');
                    } else if (loadMoreBtn) {
                        loadMoreBtn.disabled = false;
                        loadMoreBtn.textContent = 'Thử lại tải thêm';
                    }
                });
        }

        /**
         * Switches feed filter between ALL and UNREAD.
         */
        function switchFilter(filter) {
            if (currentFilter === filter && hasFetchedFeed) return;
            currentFilter = filter;
            currentPage = 0;
            loadedItems = [];

            if (filterAllBtn) {
                filterAllBtn.classList.toggle('is-active', filter === 'all');
                filterAllBtn.setAttribute('aria-selected', String(filter === 'all'));
            }
            if (filterUnreadBtn) {
                filterUnreadBtn.classList.toggle('is-active', filter === 'unread');
                filterUnreadBtn.setAttribute('aria-selected', String(filter === 'unread'));
            }

            fetchFeed(filter, 0, false);
        }

        /**
         * Marks all unread notifications as read.
         */
        function markAllAsRead() {
            if (unreadCount === 0 || isLoading) return;
            const csrf = getCsrfToken(d, container);
            const headers = { 'Accept': 'application/json' };
            if (csrf) {
                headers[csrf.headerName] = csrf.token;
            }

            if (markAllBtn) {
                markAllBtn.disabled = true;
            }

            const fetchFn = (w && w.fetch) ? w.fetch.bind(w) : fetch;
            fetchFn('/api/notifications/read-all', {
                method: 'PUT',
                headers: headers
            })
                .then(res => {
                    if (res.status === 204 || res.ok) {
                        updateBadge(0);
                        loadedItems.forEach(i => { i.unread = false; });

                        if (currentFilter === 'unread') {
                            loadedItems = [];
                            renderStateMessage('empty', 'Bạn đã xem hết thông báo.');
                            if (feedFooter) feedFooter.setAttribute('hidden', '');
                        } else {
                            const renderedItems = feedList.querySelectorAll('.notif-item.is-unread');
                            renderedItems.forEach(el => {
                                el.classList.remove('is-unread');
                                el.setAttribute('data-unread', 'false');
                            });
                        }
                    } else if (markAllBtn) {
                        markAllBtn.disabled = (unreadCount === 0);
                    }
                })
                .catch(() => {
                    if (markAllBtn) {
                        markAllBtn.disabled = (unreadCount === 0);
                    }
                });
        }

        // Event Listeners
        bellBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (isOpen) {
                closePanel();
            } else {
                openPanel();
            }
        });

        if (backBtn) {
            backBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                closePanel();
                bellBtn.focus();
            });
        }

        if (markAllBtn) {
            markAllBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                markAllAsRead();
            });
        }

        if (filterAllBtn) {
            filterAllBtn.addEventListener('click', () => switchFilter('all'));
        }

        if (filterUnreadBtn) {
            filterUnreadBtn.addEventListener('click', () => switchFilter('unread'));
        }

        if (loadMoreBtn) {
            loadMoreBtn.addEventListener('click', () => {
                if (hasNextPage && !isLoading) {
                    fetchFeed(currentFilter, currentPage + 1, true);
                }
            });
        }

        // Keyboard navigation & Escape
        d.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && isOpen) {
                closePanel();
                bellBtn.focus();
            }
        });

        // Outside click on desktop
        d.addEventListener('click', (e) => {
            if (isOpen && container && !container.contains(e.target)) {
                closePanel();
            }
        });

        // Outside focus closes panel (mutual exclusivity with tabbing into avatar dropdown / search)
        d.addEventListener('focusin', (e) => {
            if (isOpen && container && !container.contains(e.target)) {
                closePanel();
            }
        });

        // Initial unread badge fetch
        fetchUnreadCount();

        return {
            openPanel,
            closePanel,
            updateBadge,
            switchFilter,
            markAllAsRead,
            fetchFeed,
            fetchUnreadCount,
            getState: () => ({
                isOpen,
                unreadCount,
                currentFilter,
                currentPage,
                hasNextPage,
                loadedItems: [...loadedItems]
            })
        };
    }

    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initNavbarNotifications(document, window);
            });
        } else {
            initNavbarNotifications(document, window);
        }
    }

    return {
        PAGE_SIZE,
        calculateBadgeState,
        getItemPresentation,
        formatRelativeTime,
        initNavbarNotifications
    };
});
