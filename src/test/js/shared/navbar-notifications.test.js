const { describe, test, beforeEach } = require('node:test');
const assert = require('node:assert');
const NavbarNotifications = require('../../../main/resources/static/js/navbar-notifications.js');

/**
 * Creates a minimal DOM element mock for testing navbar notifications.
 */
function createMockElement(tagName = 'div', id = '', initialClassName = '') {
    const attributes = {};
    let classes = new Set(initialClassName ? initialClassName.split(/\s+/).filter(Boolean) : []);
    const eventListeners = {};
    const children = [];

    const el = {
        tagName: tagName.toUpperCase(),
        id: id,
        textContent: '',
        hidden: false,
        disabled: false,
        href: '',
        tabIndex: 0,
        get innerHTML() {
            return '';
        },
        set innerHTML(val) {
            if (val === '') {
                children.length = 0;
            }
        },
        get className() {
            return Array.from(classes).join(' ');
        },
        set className(val) {
            classes = new Set(val ? val.split(/\s+/).filter(Boolean) : []);
        },
        classList: {
            add: (cls) => { classes.add(cls); },
            remove: (cls) => { classes.delete(cls); },
            toggle: (cls, force) => {
                const shouldAdd = typeof force === 'boolean' ? force : !classes.has(cls);
                if (shouldAdd) classes.add(cls); else classes.delete(cls);
                return shouldAdd;
            },
            contains: (cls) => classes.has(cls)
        },
        setAttribute: (name, val) => {
            attributes[name] = String(val);
            if (name === 'hidden') el.hidden = true;
        },
        getAttribute: (name) => {
            if (name === 'hidden') return el.hidden ? '' : null;
            return attributes[name] !== undefined ? attributes[name] : null;
        },
        removeAttribute: (name) => {
            delete attributes[name];
            if (name === 'hidden') el.hidden = false;
        },
        hasAttribute: (name) => attributes[name] !== undefined,
        contains: (target) => {
            if (!target) return false;
            if (target === el) return true;
            for (const c of children) {
                if (c === target || (c.contains && c.contains(target))) {
                    return true;
                }
            }
            return false;
        },
        appendChild: (child) => {
            children.push(child);
            child.parentElement = el;
            return child;
        },
        removeChild: (child) => {
            const idx = children.indexOf(child);
            if (idx >= 0) {
                children.splice(idx, 1);
                child.parentElement = null;
            }
            return child;
        },
        children,
        querySelector: (sel) => {
            for (const c of children) {
                if (sel.startsWith('.') && c.classList.contains(sel.substring(1))) return c;
                if (sel.startsWith('#') && c.id === sel.substring(1)) return c;
                const match = c.querySelector && c.querySelector(sel);
                if (match) return match;
            }
            return null;
        },
        querySelectorAll: (sel) => {
            const results = [];
            for (const c of children) {
                if (sel.startsWith('.') && c.classList.contains(sel.substring(1))) results.push(c);
                if (sel.startsWith('#') && c.id === sel.substring(1)) results.push(c);
                if (c.querySelectorAll) results.push(...c.querySelectorAll(sel));
            }
            return results;
        },
        addEventListener: (event, fn) => {
            if (!eventListeners[event]) eventListeners[event] = [];
            eventListeners[event].push(fn);
        },
        removeEventListener: (event, fn) => {
            if (eventListeners[event]) {
                const idx = eventListeners[event].indexOf(fn);
                if (idx >= 0) eventListeners[event].splice(idx, 1);
            }
        },
        dispatchEvent: (evt) => {
            if (!evt.target) evt.target = el;
            let stopped = false;
            const originalStopPropagation = evt.stopPropagation;
            evt.stopPropagation = () => {
                stopped = true;
                if (originalStopPropagation) originalStopPropagation();
            };
            const fns = eventListeners[evt.type] || [];
            fns.forEach(fn => fn(evt));
            if (!stopped && el.parentElement) {
                el.parentElement.dispatchEvent(evt);
            } else if (!stopped && el.ownerDocument) {
                el.ownerDocument.dispatchEvent(evt);
            }
        },
        click: () => {
            el.dispatchEvent({ type: 'click', target: el, stopPropagation: () => {}, preventDefault: () => {} });
        },
        focus: () => {
            el.isFocused = true;
        }
    };
    return el;
}

/**
 * Creates a mock document with required navbar notifications DOM elements.
 */
function createMockDocument() {
    const elementsById = {};
    const docListeners = {};

    const container = createMockElement('div', 'navbarNotifications', 'navbar-notifications');
    container.setAttribute('data-csrf-token', 'test-token-123');
    container.setAttribute('data-csrf-header', 'X-CSRF-TOKEN');

    const bellBtn = createMockElement('button', 'navbarBellButton', 'navbar-bell-button');
    bellBtn.setAttribute('aria-expanded', 'false');
    const badge = createMockElement('span', 'navbarBellBadge', 'navbar-bell-badge');
    badge.hidden = true;
    const badgeSr = createMockElement('span', 'navbarBellBadgeSr', 'visually-hidden');
    const panel = createMockElement('div', 'navbarNotificationsPanel', 'navbar-notifications-panel');
    panel.hidden = true;

    const backBtn = createMockElement('button', 'notifMobileBackButton', 'notif-mobile-back-button');
    const markAllBtn = createMockElement('button', 'notifMarkAllBtn', 'notif-mark-all-btn');
    markAllBtn.disabled = true;

    const filterAll = createMockElement('button', 'notifFilterAll', 'notif-filter-btn is-active');
    const filterUnread = createMockElement('button', 'notifFilterUnread', 'notif-filter-btn');

    const feedList = createMockElement('div', 'notifFeedList', 'notif-feed-list');
    const feedFooter = createMockElement('div', 'notifFeedFooter', 'notif-feed-footer');
    feedFooter.hidden = true;
    const loadMoreBtn = createMockElement('button', 'notifLoadMoreBtn', 'notif-load-more-btn');

    container.appendChild(bellBtn);
    bellBtn.appendChild(badge);
    bellBtn.appendChild(badgeSr);
    container.appendChild(panel);
    panel.appendChild(backBtn);
    panel.appendChild(markAllBtn);
    panel.appendChild(filterAll);
    panel.appendChild(filterUnread);
    panel.appendChild(feedList);
    panel.appendChild(feedFooter);
    feedFooter.appendChild(loadMoreBtn);

    const body = createMockElement('body', 'mockBody', '');

    const doc = {
        body: body,
        getElementById: (id) => {
            if (id === 'navbarNotifications') return container;
            if (id === 'navbarBellButton') return bellBtn;
            if (id === 'navbarBellBadge') return badge;
            if (id === 'navbarBellBadgeSr') return badgeSr;
            if (id === 'navbarNotificationsPanel') return panel;
            if (id === 'notifMobileBackButton') return backBtn;
            if (id === 'notifMarkAllBtn') return markAllBtn;
            if (id === 'notifFilterAll') return filterAll;
            if (id === 'notifFilterUnread') return filterUnread;
            if (id === 'notifFeedList') return feedList;
            if (id === 'notifFeedFooter') return feedFooter;
            if (id === 'notifLoadMoreBtn') return loadMoreBtn;
            return elementsById[id] || null;
        },
        createElement: (tag) => {
            const el = createMockElement(tag);
            el.ownerDocument = doc;
            return el;
        },
        querySelector: (sel) => {
            if (sel === '#navbarNotifications') return container;
            if (sel === 'meta[name="_csrf"]') return null;
            if (sel === 'meta[name="_csrf_header"]') return null;
            return null;
        },
        querySelectorAll: () => [],
        addEventListener: (event, fn) => {
            if (!docListeners[event]) docListeners[event] = [];
            docListeners[event].push(fn);
        },
        dispatchEvent: (evt) => {
            const fns = docListeners[evt.type] || [];
            fns.forEach(fn => fn(evt));
        }
    };

    [container, bellBtn, badge, badgeSr, panel, backBtn, markAllBtn, filterAll, filterUnread, feedList, feedFooter, loadMoreBtn, body].forEach(el => {
        el.ownerDocument = doc;
    });

    return { doc, container, bellBtn, badge, badgeSr, panel, backBtn, markAllBtn, filterAll, filterUnread, feedList, feedFooter, loadMoreBtn };
}

describe('MS-05K2 Navbar Notifications Tests', () => {

    describe('1. calculateBadgeState', () => {
        test('formats 0 as hidden with screen-reader text', () => {
            const res = NavbarNotifications.calculateBadgeState(0);
            assert.strictEqual(res.text, '0');
            assert.strictEqual(res.hidden, true);
            assert.strictEqual(res.srText, '0 thông báo chưa đọc');
        });

        test('formats negative count as 0 hidden', () => {
            const res = NavbarNotifications.calculateBadgeState(-5);
            assert.strictEqual(res.text, '0');
            assert.strictEqual(res.hidden, true);
        });

        test('formats 1..99 as visible exact count', () => {
            const res1 = NavbarNotifications.calculateBadgeState(1);
            assert.strictEqual(res1.text, '1');
            assert.strictEqual(res1.hidden, false);
            assert.strictEqual(res1.srText, '1 thông báo chưa đọc');

            const res42 = NavbarNotifications.calculateBadgeState(42);
            assert.strictEqual(res42.text, '42');
            assert.strictEqual(res42.hidden, false);

            const res99 = NavbarNotifications.calculateBadgeState(99);
            assert.strictEqual(res99.text, '99');
            assert.strictEqual(res99.hidden, false);
        });

        test('formats >= 100 as 99+ with exact count in srText', () => {
            const res100 = NavbarNotifications.calculateBadgeState(100);
            assert.strictEqual(res100.text, '99+');
            assert.strictEqual(res100.hidden, false);
            assert.strictEqual(res100.srText, '100 thông báo chưa đọc');

            const res500 = NavbarNotifications.calculateBadgeState(500);
            assert.strictEqual(res500.text, '99+');
            assert.strictEqual(res500.hidden, false);
            assert.strictEqual(res500.srText, '500 thông báo chưa đọc');
        });
    });

    describe('2. getItemPresentation', () => {
        test('COMMENT_REPLY formats actor copy and never leaks comment body', () => {
            const item = {
                type: 'COMMENT_REPLY',
                actorDisplayNameSnapshot: 'Trần Bình An',
                targetTitleSnapshot: 'Chương 123: Kiếm Khí',
                detailSnapshot: 'Secret raw comment text that must not leak'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Trần Bình An đã phản hồi bình luận của bạn');
            assert.strictEqual(pres.subtitle, 'Chương 123: Kiếm Khí');
            assert.strictEqual(pres.detail, ''); // Body never displayed
            assert.strictEqual(pres.iconType, 'comment');
        });

        test('COMMENT_REPLY without actor uses fallback actor string', () => {
            const item = {
                type: 'COMMENT_REPLY',
                actorDisplayNameSnapshot: '',
                targetTitleSnapshot: 'Chương 1'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Có người đã phản hồi bình luận của bạn');
            assert.strictEqual(pres.subtitle, 'Chương 1');
            assert.strictEqual(pres.detail, '');
        });

        test('WIKI_CONTRIBUTION_REVIEWING formats review status', () => {
            const item = {
                type: 'WIKI_CONTRIBUTION_REVIEWING',
                targetTitleSnapshot: 'Tề Tĩnh Xuân',
                detailSnapshot: 'Đang chờ hội đồng duyệt'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Đóng góp của bạn đang được kiểm duyệt');
            assert.strictEqual(pres.subtitle, 'Tề Tĩnh Xuân');
            assert.strictEqual(pres.detail, 'Đang chờ hội đồng duyệt');
            assert.strictEqual(pres.iconType, 'wiki');
        });

        test('WIKI_CONTRIBUTION_RESOLVED formats resolved status', () => {
            const item = {
                type: 'WIKI_CONTRIBUTION_RESOLVED',
                targetTitleSnapshot: 'Đại Ly Triều Đình',
                detailSnapshot: 'Đã hợp nhất thông tin nhân vật'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Ban biên tập đã phản hồi đóng góp của bạn');
            assert.strictEqual(pres.subtitle, 'Đại Ly Triều Đình');
            assert.strictEqual(pres.detail, 'Đã hợp nhất thông tin nhân vật');
            assert.strictEqual(pres.iconType, 'resolved');
        });

        test('WIKI_CONTRIBUTION_REJECTED formats rejected status', () => {
            const item = {
                type: 'WIKI_CONTRIBUTION_REJECTED',
                targetTitleSnapshot: 'Phi kiếm',
                detailSnapshot: 'Thông tin chưa đủ nguồn kiểm chứng'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Ban biên tập đã phản hồi đóng góp của bạn');
            assert.strictEqual(pres.subtitle, 'Phi kiếm');
            assert.strictEqual(pres.detail, 'Thông tin chưa đủ nguồn kiểm chứng');
            assert.strictEqual(pres.iconType, 'rejected');
        });

        test('Unknown type falls back to safe generic copy', () => {
            const item = {
                type: 'FUTURE_UNKNOWN_TYPE',
                targetTitleSnapshot: 'Mục nào đó',
                detailSnapshot: 'Chi tiết'
            };
            const pres = NavbarNotifications.getItemPresentation(item);
            assert.strictEqual(pres.title, 'Thông báo mới');
            assert.strictEqual(pres.subtitle, 'Mục nào đó');
            assert.strictEqual(pres.detail, 'Chi tiết');
            assert.strictEqual(pres.iconType, 'default');
        });
    });

    describe('3. formatRelativeTime', () => {
        test('delegates to RelativeTime when present on global scope', () => {
            const mockGlobal = {
                RelativeTime: {
                    format: (dt) => 'mock-relative-time'
                }
            };
            const result = NavbarNotifications.formatRelativeTime('2026-09-27T08:00:00Z', mockGlobal);
            assert.strictEqual(result, 'mock-relative-time');
        });

        test('formats recent times with pure fallback logic', () => {
            const now = Date.now();
            const justNow = new Date(now - 30 * 1000).toISOString();
            assert.strictEqual(NavbarNotifications.formatRelativeTime(justNow, {}), 'Vừa xong');

            const fiveMinsAgo = new Date(now - 5 * 60 * 1000).toISOString();
            assert.strictEqual(NavbarNotifications.formatRelativeTime(fiveMinsAgo, {}), '5 phút trước');

            const twoHoursAgo = new Date(now - 2 * 3600 * 1000).toISOString();
            assert.strictEqual(NavbarNotifications.formatRelativeTime(twoHoursAgo, {}), '2 giờ trước');

            const threeDaysAgo = new Date(now - 3 * 86400 * 1000).toISOString();
            assert.strictEqual(NavbarNotifications.formatRelativeTime(threeDaysAgo, {}), '3 ngày trước');
        });
    });

    describe('4. Component Initialization & Lifecycle', () => {
        let dom;
        let mockWin;
        let fetchCalls;

        beforeEach(() => {
            dom = createMockDocument();
            fetchCalls = [];
            mockWin = {
                innerWidth: 1024,
                location: { href: 'http://localhost/' },
                fetch: async (url, options) => {
                    fetchCalls.push({ url, options });
                    if (url.includes('/api/notifications/unread-count')) {
                        return {
                            ok: true,
                            status: 200,
                            json: async () => ({ unreadCount: 3 })
                        };
                    }
                    if (url.includes('/api/notifications?')) {
                        return {
                            ok: true,
                            status: 200,
                            json: async () => ({
                                items: [
                                    {
                                        id: 'notif-1',
                                        type: 'COMMENT_REPLY',
                                        actorDisplayNameSnapshot: 'Ninh Dao',
                                        targetTitleSnapshot: 'Chương 50',
                                        unread: true,
                                        createdAt: new Date().toISOString(),
                                        actionUrl: null
                                    },
                                    {
                                        id: 'notif-2',
                                        type: 'WIKI_CONTRIBUTION_RESOLVED',
                                        targetTitleSnapshot: 'Kiếm Các',
                                        unread: false,
                                        createdAt: new Date().toISOString(),
                                        actionUrl: '/wiki/kiem-cac'
                                    }
                                ],
                                page: 0,
                                size: 20,
                                totalElements: 2,
                                totalPages: 1,
                                hasNext: false
                            })
                        };
                    }
                    if (url.includes('/read-all')) {
                        return { ok: true, status: 204 };
                    }
                    if (url.includes('/read')) {
                        return { ok: true, status: 204 };
                    }
                    return { ok: false, status: 404 };
                }
            };
        });

        test('fetches unread count on startup and updates badge & markAll button', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            assert.ok(ctrl);

            // Wait for initial unread fetch promise resolution
            await new Promise(r => setImmediate(r));

            assert.strictEqual(dom.badge.textContent, '3');
            assert.strictEqual(dom.badge.hidden, false);
            assert.strictEqual(dom.markAllBtn.disabled, false);
            assert.strictEqual(ctrl.getState().unreadCount, 3);
        });

        test('opens panel on bell button click, lazy loads feed, and sets aria-expanded', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            assert.strictEqual(dom.panel.hidden, true);
            assert.strictEqual(dom.bellBtn.getAttribute('aria-expanded'), 'false');

            // Click bell
            dom.bellBtn.click();

            assert.strictEqual(dom.panel.hidden, false);
            assert.strictEqual(dom.bellBtn.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(ctrl.getState().isOpen, true);

            // Feed fetch triggered
            await new Promise(r => setImmediate(r));

            const feedCalls = fetchCalls.filter(c => c.url.includes('/api/notifications?'));
            assert.strictEqual(feedCalls.length, 1);
            assert.ok(feedCalls[0].url.includes('filter=all'));
            assert.ok(feedCalls[0].url.includes('page=0'));

            assert.strictEqual(dom.feedList.children.length, 2);
        });

        test('applies mobile body scroll lock class on small screen viewport (< 768px)', async () => {
            mockWin.innerWidth = 375;
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);

            dom.bellBtn.click();
            assert.strictEqual(dom.doc.body.classList.contains('notifications-panel-open'), true);

            dom.bellBtn.click();
            assert.strictEqual(dom.doc.body.classList.contains('notifications-panel-open'), false);
        });

        test('Escape key closes panel and restores focus to bell button', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            dom.bellBtn.click();
            assert.strictEqual(dom.panel.hidden, false);

            dom.doc.dispatchEvent({ type: 'keydown', key: 'Escape' });

            assert.strictEqual(dom.panel.hidden, true);
            assert.strictEqual(dom.bellBtn.isFocused, true);
            assert.strictEqual(ctrl.getState().isOpen, false);
        });

        test('switching to unread filter resets page to 0 and refetches unread items', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            // Click unread filter
            dom.filterUnread.click();

            assert.strictEqual(dom.filterUnread.classList.contains('is-active'), true);
            assert.strictEqual(dom.filterAll.classList.contains('is-active'), false);
            assert.strictEqual(ctrl.getState().currentFilter, 'unread');

            await new Promise(r => setImmediate(r));

            const unreadCalls = fetchCalls.filter(c => c.url.includes('filter=unread'));
            assert.strictEqual(unreadCalls.length, 1);
        });

        test('markAllAsRead calls PUT /api/notifications/read-all with CSRF and resets badge to 0', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            dom.markAllBtn.click();
            await new Promise(r => setImmediate(r));

            const readAllCalls = fetchCalls.filter(c => c.url.includes('/read-all'));
            assert.strictEqual(readAllCalls.length, 1);
            assert.strictEqual(readAllCalls[0].options.method, 'PUT');
            assert.strictEqual(readAllCalls[0].options.headers['X-CSRF-TOKEN'], 'test-token-123');

            assert.strictEqual(dom.badge.hidden, true);
            assert.strictEqual(ctrl.getState().unreadCount, 0);
            assert.strictEqual(dom.markAllBtn.disabled, true);
        });

        test('ALL + mark-one keeps item in feed, marks it read, decrements badge, and preserves order', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(ctrl.getState().currentFilter, 'all');
            const initialCount = dom.feedList.children.length;
            assert.strictEqual(initialCount, 2);

            const unreadItemEl = dom.feedList.children[0];
            assert.ok(unreadItemEl.classList.contains('is-unread'));

            unreadItemEl.click();
            await new Promise(r => setImmediate(r));

            const readItemCalls = fetchCalls.filter(c => c.url.includes('/api/notifications/notif-1/read'));
            assert.strictEqual(readItemCalls.length, 1);
            assert.strictEqual(readItemCalls[0].options.headers['X-CSRF-TOKEN'], 'test-token-123');

            // Item remains in feed
            assert.strictEqual(dom.feedList.children.length, 2);
            assert.strictEqual(unreadItemEl.classList.contains('is-unread'), false);
            assert.strictEqual(unreadItemEl.getAttribute('data-unread'), 'false');
            assert.strictEqual(ctrl.getState().unreadCount, 2);
            assert.strictEqual(dom.badge.textContent, '2');
        });

        test('UNREAD + mark-one removes item from feed and loadedItems upon server success', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            // Switch to UNREAD
            dom.filterUnread.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(ctrl.getState().currentFilter, 'unread');
            assert.strictEqual(dom.feedList.children.length, 2);

            const firstItem = dom.feedList.children[0];
            firstItem.click();
            await new Promise(r => setImmediate(r));

            // First item is removed from DOM and loadedItems
            assert.strictEqual(dom.feedList.children.length, 1);
            assert.strictEqual(ctrl.getState().loadedItems.length, 1);
            assert.strictEqual(ctrl.getState().loadedItems[0].id, 'notif-2');
            assert.strictEqual(ctrl.getState().unreadCount, 2);
        });

        test('UNREAD last item marked read renders unread empty state and hides footer', async () => {
            // Override fetch to return exactly 1 unread item
            mockWin.fetch = async (url) => {
                if (url.includes('/api/notifications/unread-count')) {
                    return { ok: true, status: 200, json: async () => ({ unreadCount: 1 }) };
                }
                if (url.includes('/api/notifications?')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{
                                id: 'notif-lone',
                                type: 'COMMENT_REPLY',
                                actorDisplayNameSnapshot: 'Trần Bình An',
                                targetTitleSnapshot: 'Chương 10',
                                unread: true,
                                createdAt: new Date().toISOString(),
                                actionUrl: null
                            }],
                            page: 0,
                            size: 20,
                            totalElements: 1,
                            totalPages: 1,
                            hasNext: false
                        })
                    };
                }
                if (url.includes('/read')) {
                    return { ok: true, status: 204 };
                }
                return { ok: false, status: 404 };
            };

            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            dom.filterUnread.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(dom.feedList.children.length, 1);
            const loneItem = dom.feedList.children[0];
            loneItem.click();
            await new Promise(r => setImmediate(r));

            // Empty state is rendered
            assert.strictEqual(dom.feedList.children.length, 1);
            assert.strictEqual(dom.feedList.children[0].className, 'notif-empty');
            assert.strictEqual(dom.feedList.children[0].textContent, 'Bạn đã xem hết thông báo.');
            assert.strictEqual(dom.feedFooter.hidden, true);
            assert.strictEqual(ctrl.getState().unreadCount, 0);
            assert.strictEqual(dom.badge.hidden, true);
        });

        test('UNREAD + mark-all empties feed, renders empty state, and disables mark-all button', async () => {
            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            dom.filterUnread.click();
            await new Promise(r => setImmediate(r));

            dom.markAllBtn.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(dom.feedList.children.length, 1);
            assert.strictEqual(dom.feedList.children[0].className, 'notif-empty');
            assert.strictEqual(dom.feedList.children[0].textContent, 'Bạn đã xem hết thông báo.');
            assert.strictEqual(ctrl.getState().loadedItems.length, 0);
            assert.strictEqual(ctrl.getState().unreadCount, 0);
            assert.strictEqual(dom.badge.hidden, true);
            assert.strictEqual(dom.markAllBtn.disabled, true);
        });

        test('failed mark-one preserves item unread state, visibility, and badge count', async () => {
            mockWin.fetch = async (url) => {
                if (url.includes('/api/notifications/unread-count')) {
                    return { ok: true, status: 200, json: async () => ({ unreadCount: 5 }) };
                }
                if (url.includes('/api/notifications?')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{
                                id: 'notif-fail',
                                type: 'COMMENT_REPLY',
                                actorDisplayNameSnapshot: 'Lưu Tiện Dương',
                                targetTitleSnapshot: 'Chương 1',
                                unread: true,
                                createdAt: new Date().toISOString(),
                                actionUrl: null
                            }],
                            page: 0,
                            size: 20,
                            totalElements: 1,
                            totalPages: 1,
                            hasNext: false
                        })
                    };
                }
                if (url.includes('/read')) {
                    return { ok: false, status: 500 }; // Mutation failure
                }
                return { ok: false, status: 404 };
            };

            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            const failItem = dom.feedList.children[0];
            failItem.click();
            await new Promise(r => setImmediate(r));

            // State is preserved on server failure
            assert.strictEqual(failItem.classList.contains('is-unread'), true);
            assert.strictEqual(failItem.getAttribute('data-unread'), 'true');
            assert.strictEqual(ctrl.getState().unreadCount, 5);
            assert.strictEqual(dom.badge.textContent, '5');
        });

        test('null actionUrl never mutates window.location', async () => {
            const initialLocation = mockWin.location.href;

            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            // Item 1 has actionUrl == null
            const unreadNullActionItem = dom.feedList.children[0];
            unreadNullActionItem.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(mockWin.location.href, initialLocation);
        });

        test('non-null actionUrl navigates only after successful mutation, and navigates directly when already read', async () => {
            let mutationSucceeded = false;
            mockWin.fetch = async (url) => {
                if (url.includes('/api/notifications/unread-count')) {
                    return { ok: true, status: 200, json: async () => ({ unreadCount: 1 }) };
                }
                if (url.includes('/api/notifications?')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [
                                {
                                    id: 'notif-nav-unread',
                                    type: 'WIKI_CONTRIBUTION_RESOLVED',
                                    targetTitleSnapshot: 'Bài viết 1',
                                    unread: true,
                                    createdAt: new Date().toISOString(),
                                    actionUrl: '/wiki/bai-viet-1'
                                },
                                {
                                    id: 'notif-nav-read',
                                    type: 'WIKI_CONTRIBUTION_RESOLVED',
                                    targetTitleSnapshot: 'Bài viết 2',
                                    unread: false,
                                    createdAt: new Date().toISOString(),
                                    actionUrl: '/wiki/bai-viet-2'
                                }
                            ],
                            page: 0,
                            size: 20,
                            totalElements: 2,
                            totalPages: 1,
                            hasNext: false
                        })
                    };
                }
                if (url.includes('/read')) {
                    mutationSucceeded = true;
                    return { ok: true, status: 204 };
                }
                return { ok: false, status: 404 };
            };

            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            await new Promise(r => setImmediate(r));

            dom.bellBtn.click();
            await new Promise(r => setImmediate(r));

            const unreadNavigable = dom.feedList.children[0];
            unreadNavigable.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(mutationSucceeded, true);
            assert.strictEqual(mockWin.location.href, '/wiki/bai-viet-1');

            // Already-read navigable item navigates directly without /read mutation
            mutationSucceeded = false;
            const readNavigable = dom.feedList.children[1];
            readNavigable.click();
            await new Promise(r => setImmediate(r));

            assert.strictEqual(mutationSucceeded, false);
            assert.strictEqual(mockWin.location.href, '/wiki/bai-viet-2');
        });

        test('bell and profile surfaces cannot remain open simultaneously', async () => {
            const parentEl = dom.doc.createElement('div');
            const profileMenuEl = dom.doc.createElement('div');
            profileMenuEl.className = 'profile-menu';
            const avatarBtn = dom.doc.createElement('button');
            avatarBtn.className = 'profile-avatar-button';
            profileMenuEl.appendChild(avatarBtn);
            parentEl.appendChild(dom.container);
            parentEl.appendChild(profileMenuEl);

            const ctrl = NavbarNotifications.initNavbarNotifications(dom.doc, mockWin);
            dom.bellBtn.click();
            assert.strictEqual(ctrl.getState().isOpen, true);

            // User clicks avatar button outside notification container
            avatarBtn.click();
            assert.strictEqual(ctrl.getState().isOpen, false);
            assert.strictEqual(dom.panel.hidden, true);

            // User opens bell again
            dom.bellBtn.click();
            assert.strictEqual(ctrl.getState().isOpen, true);

            // User focuses outside notification container (e.g. keyboard tabbing into avatar dropdown)
            dom.doc.dispatchEvent({ type: 'focusin', target: avatarBtn });
            assert.strictEqual(ctrl.getState().isOpen, false);
            assert.strictEqual(dom.panel.hidden, true);
        });
    });
});
