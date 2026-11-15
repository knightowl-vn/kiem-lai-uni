const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommentPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));
const RelativeTime = require(path.join(__dirname, '../../../main/resources/static/js/shared/relative-time.js'));
const ScreenTitleComments = require(path.join(__dirname, '../../../main/resources/static/js/screen/screen-comments.js'));

function createClassList(initialClasses = []) {
    const classes = new Set(initialClasses);
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        clear: () => classes.clear(),
        get classes() { return classes; },
        get value() { return Array.from(classes).join(' '); }
    };
}

function createMockElement(tagName = 'div', initialAttrs = {}, ownerDoc = null) {
    const attrs = { ...initialAttrs };
    const classList = createClassList(initialAttrs.class ? initialAttrs.class.split(' ') : []);
    const eventListeners = {};
    const children = [];
    const style = { visibility: '', display: '' };
    let innerHtmlContent = '';

    let rawTextContent = '';

    const el = {
        nodeType: 1,
        tagName: tagName.toUpperCase(),
        classList,
        style,
        disabled: false,
        hidden: false,
        get textContent() {
            if (children.length > 0) {
                return children.map(c => c.textContent).join(' ');
            }
            return rawTextContent;
        },
        set textContent(v) {
            rawTextContent = String(v);
            children.length = 0;
        },
        parentNode: null,
        ownerDocument: ownerDoc,
        children,
        get className() { return Array.from(classList.classes).join(' '); },
        set className(v) {
            attrs['class'] = String(v);
            classList.clear();
            String(v).split(' ').filter(Boolean).forEach(c => classList.add(c));
        },
        getAttribute: (k) => (k in attrs ? attrs[k] : null),
        setAttribute: (k, v) => {
            attrs[k] = String(v);
            if (k === 'class') {
                v.split(' ').forEach(c => classList.add(c));
            }
        },
        removeAttribute: (k) => {
            delete attrs[k];
            if (k === 'class') {
                Array.from(classList.classes).forEach(c => classList.remove(c));
            }
        },
        hasAttribute: (k) => (k in attrs),
        addEventListener: (evt, fn) => {
            if (!eventListeners[evt]) eventListeners[evt] = [];
            eventListeners[evt].push(fn);
        },
        removeEventListener: (evt, fn) => {
            if (!eventListeners[evt]) return;
            eventListeners[evt] = eventListeners[evt].filter(f => f !== fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const eventTarget = evtObj.target || el;
            const eventPayload = {
                target: eventTarget,
                currentTarget: el,
                preventDefault: () => {},
                stopPropagation: () => {},
                ...evtObj
            };
            let curr = el;
            while (curr) {
                const fns = curr._getListeners ? (curr._getListeners()[evtName] || []) : [];
                for (const fn of fns) {
                    await fn(eventPayload);
                }
                curr = curr.parentNode;
            }
        },
        _getListeners: () => eventListeners,
        appendChild: (child) => {
            child.parentNode = el;
            child.ownerDocument = el.ownerDocument;
            children.push(child);
            return child;
        },
        insertBefore: (newChild, refChild) => {
            newChild.parentNode = el;
            newChild.ownerDocument = el.ownerDocument;
            const idx = children.indexOf(refChild);
            if (idx !== -1) {
                children.splice(idx, 0, newChild);
            } else {
                children.push(newChild);
            }
            return newChild;
        },
        removeChild: (child) => {
            const idx = children.indexOf(child);
            if (idx !== -1) {
                child.parentNode = null;
                children.splice(idx, 1);
            }
            return child;
        },
        replaceChildren: (...newChildren) => {
            for (const c of children) {
                c.parentNode = null;
            }
            children.length = 0;
            for (const nc of newChildren) {
                if (nc) {
                    nc.parentNode = el;
                    nc.ownerDocument = el.ownerDocument;
                    children.push(nc);
                }
            }
        },
        querySelectorAll: (sel) => {
            const results = [];
            function traverse(node) {
                for (const c of node.children) {
                    if (matchesSelector(c, sel)) {
                        results.push(c);
                    }
                    traverse(c);
                }
            }
            traverse(el);
            return results;
        },
        querySelector: (sel) => {
            function find(node) {
                for (const c of node.children) {
                    if (matchesSelector(c, sel)) {
                        return c;
                    }
                    const sub = find(c);
                    if (sub) return sub;
                }
                return null;
            }
            return find(el);
        },
        closest: (sel) => {
            let curr = el;
            while (curr) {
                if (matchesSelector(curr, sel)) {
                    return curr;
                }
                curr = curr.parentNode;
            }
            return null;
        },
        matches: (sel) => matchesSelector(el, sel),
        get id() { return attrs['id'] || ''; },
        set id(val) { attrs['id'] = String(val); },
        get firstChild() { return children[0] || null; },
        get childNodes() { return children; },
        focus: () => {
            if (ownerDoc) ownerDoc.activeElement = el;
        }
    };

    return el;
}

function matchesSelector(el, sel) {
    if (!el || !sel) return false;
    if (sel.includes(',')) {
        const parts = sel.split(',').map(s => s.trim());
        return parts.some(p => matchesSelector(el, p));
    }
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    if (sel.startsWith('.')) {
        return el.classList.contains(sel.slice(1));
    }

    let remaining = sel;
    const tagMatch = remaining.match(/^([a-zA-Z0-9_-]+)/);
    if (tagMatch) {
        const tag = tagMatch[1];
        remaining = remaining.slice(tag.length);
        if (el.tagName.toLowerCase() !== tag.toLowerCase()) {
            return false;
        }
    }

    if (!remaining) return true;

    const brackets = remaining.match(/\[[^\]]+\]/g);
    if (brackets && brackets.length > 0) {
        for (const b of brackets) {
            const inner = b.slice(1, -1);
            if (inner.includes('=')) {
                const eqIdx = inner.indexOf('=');
                const attrName = inner.slice(0, eqIdx);
                const rawVal = inner.slice(eqIdx + 1);
                const val = rawVal.replace(/^["']|["']$/g, '');
                if (el.getAttribute(attrName) !== val) {
                    return false;
                }
            } else {
                if (!el.hasAttribute(inner)) {
                    return false;
                }
            }
        }
        return true;
    }

    return !remaining;
}

function createMockDocument() {
    const docListeners = {};

    const doc = {
        body: null,
        head: null,
        activeElement: null,
        defaultView: null,
        _getListeners: () => docListeners,
        createElement: (tagName) => createMockElement(tagName, {}, doc),
        addEventListener: (evt, fn) => {
            if (!docListeners[evt]) docListeners[evt] = [];
            docListeners[evt].push(fn);
        },
        removeEventListener: (evt, fn) => {
            if (!docListeners[evt]) return;
            docListeners[evt] = docListeners[evt].filter(f => f !== fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const fns = docListeners[evtName] || [];
            for (const fn of fns) {
                await fn({ target: evtObj.target || doc.body, preventDefault: () => {}, stopPropagation: () => {}, ...evtObj });
            }
        },
        querySelectorAll: (sel) => {
            const results = [];
            if (doc.head) {
                if (matchesSelector(doc.head, sel)) results.push(doc.head);
                results.push(...doc.head.querySelectorAll(sel));
            }
            if (doc.body) {
                if (matchesSelector(doc.body, sel)) results.push(doc.body);
                results.push(...doc.body.querySelectorAll(sel));
            }
            return results;
        },
        querySelector: (sel) => {
            if (doc.head) {
                if (matchesSelector(doc.head, sel)) return doc.head;
                const found = doc.head.querySelector(sel);
                if (found) return found;
            }
            if (doc.body) {
                if (matchesSelector(doc.body, sel)) return doc.body;
                return doc.body.querySelector(sel);
            }
            return null;
        },
        getElementById: (id) => {
            return doc.querySelector('#' + id);
        }
    };

    doc.defaultView = {
        innerWidth: 1024,
        innerHeight: 768,
        CommentPresentation: CommentPresentation
    };
    doc.body = createMockElement('body', {}, doc);
    doc.head = createMockElement('head', {}, doc);
    return doc;
}

function setupDiscussionDomWithSkeleton(doc, titleId = '11111111-1111-1111-1111-111111111111') {
    const metaCsrf = createMockElement('meta', { name: '_csrf', content: 'test-csrf-token' }, doc);
    const metaCsrfHeader = createMockElement('meta', { name: '_csrf_header', content: 'X-CSRF-TOKEN' }, doc);
    doc.head.appendChild(metaCsrf);
    doc.head.appendChild(metaCsrfHeader);

    const section = createMockElement('section', {
        id: 'screenDiscussion',
        class: 'screen-section-card screen-discussion-section',
        'data-title-id': titleId,
        'data-title-slug': 'kiem-lai-phan-1',
        'data-authenticated': 'true',
        'data-csrf-token': 'test-csrf-token',
        'data-csrf-header': 'X-CSRF-TOKEN'
    }, doc);

    // Initial badge has pending text
    const badge = createMockElement('span', {
        id: 'screenDiscussionCountBadge',
        class: 'screen-discussion-count-badge',
        'aria-live': 'polite'
    }, doc);
    badge.textContent = 'Đang tải...';

    const status = createMockElement('div', {
        id: 'screenDiscussionStatus',
        class: 'screen-discussion-status',
        role: 'status',
        'aria-live': 'polite'
    }, doc);

    // Initial thread list with aria-busy="true" and skeleton placeholder
    const threadList = createMockElement('div', {
        id: 'screenDiscussionThreadList',
        class: 'screen-discussion-thread-list',
        role: 'feed',
        'aria-busy': 'true',
        'aria-label': 'Danh sách bình luận'
    }, doc);

    const skeletonContainer = createMockElement('div', {
        id: 'screenDiscussionSkeleton',
        class: 'screen-discussion-skeleton',
        'aria-hidden': 'true'
    }, doc);

    for (let i = 0; i < 2; i++) {
        const item = createMockElement('div', { class: 'screen-comment-skeleton-item' }, doc);
        const avatar = createMockElement('div', { class: 'screen-comment-skeleton-avatar' }, doc);
        const content = createMockElement('div', { class: 'screen-comment-skeleton-content' }, doc);
        const meta = createMockElement('div', { class: 'screen-comment-skeleton-meta' }, doc);
        const name = createMockElement('div', { class: 'screen-comment-skeleton-name' }, doc);
        const date = createMockElement('div', { class: 'screen-comment-skeleton-date' }, doc);
        meta.appendChild(name);
        meta.appendChild(date);
        content.appendChild(meta);
        const lineLong = createMockElement('div', { class: 'screen-comment-skeleton-line screen-comment-skeleton-line--long' }, doc);
        const lineShort = createMockElement('div', { class: 'screen-comment-skeleton-line screen-comment-skeleton-line--medium' }, doc);
        content.appendChild(lineLong);
        content.appendChild(lineShort);
        item.appendChild(avatar);
        item.appendChild(content);
        skeletonContainer.appendChild(item);
    }
    threadList.appendChild(skeletonContainer);

    const footer = createMockElement('div', { id: 'screenDiscussionFooter', hidden: '' }, doc);
    const loadMoreBtn = createMockElement('button', { id: 'screenDiscussionLoadMoreBtn' }, doc);
    footer.appendChild(loadMoreBtn);

    const composerForm = createMockElement('form', { id: 'screenRootComposerForm' }, doc);
    const composerInput = createMockElement('textarea', { id: 'screenRootComposerInput' }, doc);
    const composerSubmit = createMockElement('button', { id: 'screenRootComposerSubmit' }, doc);
    const composerError = createMockElement('span', { id: 'screenRootComposerError', hidden: '' }, doc);
    composerForm.appendChild(composerInput);
    composerForm.appendChild(composerSubmit);
    composerForm.appendChild(composerError);

    section.appendChild(badge);
    section.appendChild(composerForm);
    section.appendChild(status);
    section.appendChild(threadList);
    section.appendChild(footer);

    doc.body.appendChild(section);
    return { section, badge, status, threadList, footer, loadMoreBtn };
}

function delay(ms = 20) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

describe('Screen Discussion Loading UX & Skeleton Placeholders', () => {
    let doc;
    const TITLE_ID = '11111111-1111-1111-1111-111111111111';

    beforeEach(() => {
        ScreenTitleComments.resetState();
        doc = createMockDocument();
        ScreenTitleComments.setCommentPresentation(CommentPresentation);
        ScreenTitleComments.setRelativeTime(RelativeTime);
    });

    afterEach(() => {
        ScreenTitleComments.destroy();
    });

    test('1. formatCommentCount handles null, undefined, pending, zero, and positive numbers', () => {
        assert.strictEqual(ScreenTitleComments.formatCommentCount(null), 'Đang tải...');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(undefined), 'Đang tải...');
        assert.strictEqual(ScreenTitleComments.formatCommentCount('pending'), 'Đang tải...');
        assert.strictEqual(ScreenTitleComments.formatCommentCount('error'), 'Chưa tải được');
        assert.strictEqual(ScreenTitleComments.formatCommentCount('failed'), 'Chưa tải được');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(0), '0 bình luận');
        assert.strictEqual(ScreenTitleComments.formatCommentCount('0'), '0 bình luận');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(1), '1 bình luận');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(7), '7 bình luận');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(100), '100 bình luận');
        assert.strictEqual(ScreenTitleComments.formatCommentCount(-5), '0 bình luận');
    });

    test('2. Initial markup matches expected loading state before fetch completes', () => {
        const { badge, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        assert.strictEqual(badge.textContent, 'Đang tải...');
        assert.strictEqual(threadList.getAttribute('aria-busy'), 'true');

        const skeleton = threadList.querySelector('.screen-discussion-skeleton');
        assert.strictEqual(Boolean(skeleton), true, 'Initial skeleton should be present in DOM');
        assert.strictEqual(skeleton.getAttribute('aria-hidden'), 'true');
        const items = skeleton.querySelectorAll('.screen-comment-skeleton-item');
        assert.strictEqual(items.length, 2, 'Should have 2 skeleton comment placeholders');
    });

    test('3. Successful feed load with comments replaces skeleton and updates count badge authoritatively', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async (url) => {
            if (url.includes('/comments?')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 2,
                        commentCount: 7,
                        threads: [
                            {
                                root: { id: 'r1', author: { displayName: 'User 1' }, body: 'First comment', createdAt: '2026-11-01T00:00:00Z' },
                                replies: []
                            },
                            {
                                root: { id: 'r2', author: { displayName: 'User 2' }, body: 'Second comment', createdAt: '2026-11-02T00:00:00Z' },
                                replies: []
                            }
                        ]
                    })
                };
            }
            return { status: 404 };
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        assert.strictEqual(badge.textContent, '7 bình luận');
        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true, 'Skeleton must be removed');
        const renderedThreads = threadList.querySelectorAll('.screen-thread');
        assert.strictEqual(renderedThreads.length, 2, 'Real threads should be rendered');
    });

    test('4. Successful feed load with 0 comments removes skeleton and displays empty message & 0 count', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async (url) => {
            if (url.includes('/comments?')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 0,
                        commentCount: 0,
                        threads: []
                    })
                };
            }
            return { status: 404 };
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        assert.strictEqual(badge.textContent, '0 bình luận');
        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true, 'Skeleton must be removed');
        assert.strictEqual(status.textContent.includes('Chưa có bình luận nào'), true, 'Empty message must be displayed');
    });

    test('5. Non-200 HTTP failure removes skeleton, does not show false 0, and displays error with Retry button', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async () => {
            return { status: 500 };
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true, 'Skeleton must be removed on failure');
        assert.notStrictEqual(badge.textContent, '0 bình luận', 'Must not show false 0 count on error');
        assert.strictEqual(badge.textContent, 'Chưa tải được', 'Must show neutral failure text on error');
        assert.strictEqual(status.textContent.includes('Không thể tải bình luận'), true, 'Error message must be shown');

        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true, 'Retry button must be rendered in status area');
        if (retryBtn) {
            assert.strictEqual(retryBtn.textContent.trim(), 'Thử lại');
        }
    });

    test('6. Network exception removes skeleton, preserves non-zero badge, and provides Retry button', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async () => {
            throw new Error('Connection refused');
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true, 'Skeleton must be removed on network error');
        assert.notStrictEqual(badge.textContent, '0 bình luận', 'Must not show false 0 count on network error');
        assert.strictEqual(badge.textContent, 'Chưa tải được', 'Must show neutral failure text on network error');
        assert.strictEqual(status.textContent.includes('Lỗi kết nối khi tải thảo luận'), true, 'Connection error message must be shown');

        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true, 'Retry button must be rendered');
    });

    test('7. Clicking Retry re-initiates load, restores skeleton while waiting, and recovers on success', async () => {
        const { badge, status, threadList, section } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        let attempt = 0;
        let resolveSecondFetch;
        const secondFetchPromise = new Promise(resolve => {
            resolveSecondFetch = resolve;
            setTimeout(resolve, 300); // safety timeout
        });

        ScreenTitleComments.setFetchImplementation(async () => {
            attempt++;
            if (attempt === 1) {
                return { status: 500 };
            }
            await secondFetchPromise;
            return {
                status: 200,
                json: async () => ({
                    page: 0,
                    hasNext: false,
                    threadCount: 1,
                    commentCount: 3,
                    threads: [
                        {
                            root: { id: 'r1', author: { displayName: 'User 1' }, body: 'Recovered thread', createdAt: '2026-11-01T00:00:00Z' },
                            replies: []
                        }
                    ]
                })
            };
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true, 'Retry button exists after first failure');

        if (retryBtn) {
            // Click retry button (bubbles to section)
            const retryEventPromise = retryBtn.dispatchEvent('click', { target: retryBtn });

            // While second fetch is in flight, skeleton should be re-rendered and threadList aria-busy is true
            assert.strictEqual(threadList.getAttribute('aria-busy'), 'true');
            const reloadedSkeleton = threadList.querySelector('.screen-discussion-skeleton');
            assert.strictEqual(Boolean(reloadedSkeleton), true, 'Skeleton must be restored when retry is initiated');

            // Allow second fetch to complete
            resolveSecondFetch();
            await retryEventPromise;
            await delay(30);

            // Now recovered
            assert.strictEqual(badge.textContent, '3 bình luận');
            assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
            assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true);
            assert.strictEqual(threadList.querySelectorAll('.screen-thread').length, 1);
        }
    });

    test('8. switchSort on cache hit does not show skeleton; cache miss shows skeleton and updates correctly', async () => {
        const { badge, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        let fetchCallCount = 0;
        ScreenTitleComments.setFetchImplementation(async (url) => {
            fetchCallCount++;
            const isFeatured = url.includes('sort=FEATURED');
            return {
                status: 200,
                json: async () => ({
                    page: 0,
                    hasNext: false,
                    threadCount: 1,
                    commentCount: 1,
                    threads: [
                        {
                            root: { id: isFeatured ? 'feat-1' : 'new-1', author: { displayName: 'User' }, body: isFeatured ? 'Featured root' : 'Newest root', createdAt: '2026-11-01T00:00:00Z' },
                            replies: []
                        }
                    ]
                })
            };
        });

        ScreenTitleComments.init(doc);
        await delay(30);
        assert.strictEqual(fetchCallCount, 1, 'Initial load for default NEWEST sort');

        // Switch to FEATURED (cache miss)
        const sortPromise = ScreenTitleComments.switchSort('FEATURED', doc);
        await sortPromise;
        await delay(30);
        assert.strictEqual(fetchCallCount, 2, 'Fetched FEATURED sort');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true);

        // Switch back to NEWEST (cache hit: should not trigger fetch or show skeleton)
        await ScreenTitleComments.switchSort('NEWEST', doc);
        await delay(30);
        assert.strictEqual(fetchCallCount, 2, 'Cache hit should not trigger new fetch');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true);
    });

    test('9. Stale responses from slow requests are discarded and do not overwrite fresh state', async () => {
        const { badge, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        let resolveSlow;
        const slowPromise = new Promise(resolve => {
            resolveSlow = resolve;
            setTimeout(resolve, 300); // safety timeout
        });

        let call = 0;
        ScreenTitleComments.setFetchImplementation(async (url) => {
            call++;
            if (call === 1) {
                await slowPromise;
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 1,
                        commentCount: 999,
                        threads: [
                            {
                                root: { id: 'slow-root', author: { displayName: 'Slow' }, body: 'Slow body', createdAt: '2026-11-01T00:00:00Z' },
                                replies: []
                            }
                        ]
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    page: 0,
                    hasNext: false,
                    threadCount: 1,
                    commentCount: 5,
                    threads: [
                        {
                            root: { id: 'fast-root', author: { displayName: 'Fast' }, body: 'Fast body', createdAt: '2026-11-01T00:00:00Z' },
                            replies: []
                        }
                    ]
                })
            };
        });

        ScreenTitleComments.init(doc);
        await delay(10);

        // Trigger a fresh load before first completes
        const fastPromise = ScreenTitleComments.loadDiscussionFeed(0, false, doc, true);
        await fastPromise;
        await delay(10);

        assert.strictEqual(badge.textContent, '5 bình luận');

        // Now slow resolves late
        resolveSlow();
        await delay(30);

        // Badge and thread must still reflect the newer response, not 999
        assert.strictEqual(badge.textContent, '5 bình luận');
    });

    test('10. Load More (append = true) retains existing comments and does not insert skeleton', async () => {
        const { badge, threadList, loadMoreBtn } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async (url) => {
            if (url.includes('page=0')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: true,
                        threadCount: 2,
                        commentCount: 2,
                        threads: [
                            { root: { id: 't1', author: { displayName: 'U1' }, body: 'Page 0 thread', createdAt: '2026-11-01T00:00:00Z' }, replies: [] }
                        ]
                    })
                };
            }
            if (url.includes('page=1')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 1,
                        hasNext: false,
                        threadCount: 2,
                        commentCount: 2,
                        threads: [
                            { root: { id: 't2', author: { displayName: 'U2' }, body: 'Page 1 thread', createdAt: '2026-11-02T00:00:00Z' }, replies: [] }
                        ]
                    })
                };
            }
            return { status: 404 };
        });

        ScreenTitleComments.init(doc);
        await delay(30);
        assert.strictEqual(threadList.querySelectorAll('.screen-thread').length, 1);

        // Append page 1
        await ScreenTitleComments.loadDiscussionFeed(1, true, doc);
        await delay(30);

        assert.strictEqual(threadList.querySelectorAll('.screen-thread').length, 2);
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true, 'Load More must never add skeleton');
    });

    test('11. renderSkeleton creates accessible DOM placeholder with 2 items', () => {
        assert.strictEqual(typeof ScreenTitleComments.renderSkeleton, 'function', 'renderSkeleton must be exported');
        const skeleton = ScreenTitleComments.renderSkeleton(doc);
        assert.strictEqual(Boolean(skeleton), true, 'Skeleton element should be returned');
        assert.strictEqual(skeleton.getAttribute('aria-hidden'), 'true');
        assert.strictEqual(skeleton.classList.contains('screen-discussion-skeleton'), true);
        const items = skeleton.querySelectorAll('.screen-comment-skeleton-item');
        assert.strictEqual(items.length, 2);
    });

    test('12. Defect 1: Cached-sort switch invalidates in-flight request and prevents stale overwrite', async () => {
        const { badge, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        let resolveDelayedNewest;
        const delayedNewestPromise = new Promise(resolve => {
            resolveDelayedNewest = resolve;
            setTimeout(resolve, 500);
        });

        let callCount = 0;
        ScreenTitleComments.setFetchImplementation(async (url) => {
            callCount++;
            if (url.includes('sort=NEWEST') && callCount === 1) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 1,
                        commentCount: 10,
                        threads: [
                            { root: { id: 'new-root', author: { displayName: 'User New' }, body: 'Newest comment' }, replies: [] }
                        ]
                    })
                };
            }
            if (url.includes('sort=FEATURED')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 1,
                        commentCount: 10,
                        threads: [
                            { root: { id: 'feat-root', author: { displayName: 'User Feat' }, body: 'Featured comment' }, replies: [] }
                        ]
                    })
                };
            }
            if (url.includes('sort=NEWEST') && callCount > 2) {
                await delayedNewestPromise;
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 1,
                        commentCount: 99,
                        threads: [
                            { root: { id: 'stale-new', author: { displayName: 'Stale' }, body: 'Stale newest comment' }, replies: [] }
                        ]
                    })
                };
            }
            return { status: 404 };
        });

        // Step 1: Initial load (NEWEST)
        ScreenTitleComments.init(doc);
        await delay(30);
        assert.strictEqual(ScreenTitleComments.getState().currentThreads[0].root.id, 'new-root');

        // Step 2: Switch to FEATURED (loads and caches)
        await ScreenTitleComments.switchSort('FEATURED', doc);
        await delay(30);
        assert.strictEqual(ScreenTitleComments.getState().currentThreads[0].root.id, 'feat-root');

        // Step 3: Switch back to NEWEST (cache hit)
        await ScreenTitleComments.switchSort('NEWEST', doc);
        await delay(30);
        assert.strictEqual(ScreenTitleComments.getState().currentThreads[0].root.id, 'new-root');

        // Step 4: Trigger a fresh delayed NEWEST request
        const pendingRequestPromise = ScreenTitleComments.loadDiscussionFeed(0, false, doc, true);
        await delay(10);

        // Step 5: While pending, switch to FEATURED using cache
        await ScreenTitleComments.switchSort('FEATURED', doc);
        assert.strictEqual(ScreenTitleComments.getCurrentSort(), 'FEATURED');
        assert.strictEqual(ScreenTitleComments.getState().currentThreads[0].root.id, 'feat-root');

        // Step 6: Resolve delayed NEWEST request
        resolveDelayedNewest();
        await pendingRequestPromise;
        await delay(30);

        // Sort must still be FEATURED
        assert.strictEqual(ScreenTitleComments.getCurrentSort(), 'FEATURED');
        // Threads must NOT be overwritten by stale-new
        assert.strictEqual(
            ScreenTitleComments.getState().currentThreads[0].root.id,
            'feat-root',
            'Stale NEWEST response must not overwrite active FEATURED cached sort'
        );
        // Count must remain 10, not overwritten to 99
        assert.strictEqual(ScreenTitleComments.getState().commentCount, 10);
        assert.strictEqual(badge.textContent, '10 bình luận');
    });

    test('13. Defect 3: Failed Load More retry re-requests failed page with append=true', async () => {
        const { badge, status, threadList, loadMoreBtn } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        const requestedUrls = [];
        let page1Attempt = 0;

        ScreenTitleComments.setFetchImplementation(async (url) => {
            requestedUrls.push(url);
            if (url.includes('page=0')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: true,
                        threadCount: 2,
                        commentCount: 2,
                        threads: [
                            { root: { id: 'p0-root', author: { displayName: 'User 0' }, body: 'Page 0' }, replies: [] }
                        ]
                    })
                };
            }
            if (url.includes('page=1')) {
                page1Attempt++;
                if (page1Attempt === 1) {
                    return { status: 500 };
                }
                return {
                    status: 200,
                    json: async () => ({
                        page: 1,
                        hasNext: false,
                        threadCount: 2,
                        commentCount: 2,
                        threads: [
                            { root: { id: 'p1-root', author: { displayName: 'User 1' }, body: 'Page 1' }, replies: [] }
                        ]
                    })
                };
            }
            return { status: 404 };
        });

        // 1. Initial page 0 load succeeds
        ScreenTitleComments.init(doc);
        await delay(30);
        assert.strictEqual(ScreenTitleComments.getState().currentThreads.length, 1);
        assert.strictEqual(ScreenTitleComments.getState().currentThreads[0].root.id, 'p0-root');
        assert.strictEqual(badge.textContent, '2 bình luận');

        // 2. Load page 1 (append = true) fails with HTTP 500
        await ScreenTitleComments.loadDiscussionFeed(1, true, doc);
        await delay(30);

        // Page 0 comments must still be visible in thread list
        assert.strictEqual(ScreenTitleComments.getState().currentThreads.length, 1);
        assert.strictEqual(threadList.querySelectorAll('.screen-thread').length, 1);
        assert.strictEqual(badge.textContent, '2 bình luận');

        // Retry button exists
        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true, 'Retry button should be present in status');

        // 3. Click retry button
        requestedUrls.length = 0;
        await retryBtn.dispatchEvent('click', { target: retryBtn });
        await delay(30);

        // Assert: retry must have requested page=1 (NOT page=0)
        assert.strictEqual(requestedUrls.length, 1, 'Should have made 1 retry request');
        assert.strictEqual(requestedUrls[0].includes('page=1'), true, 'Retry request must be for page=1');

        // Both page 0 and page 1 comments must now be present
        const threads = ScreenTitleComments.getState().currentThreads;
        assert.strictEqual(threads.length, 2, 'Should have 2 threads after successful append retry');
        assert.strictEqual(threads[0].root.id, 'p0-root');
        assert.strictEqual(threads[1].root.id, 'p1-root');
        assert.strictEqual(threadList.querySelectorAll('.screen-thread').length, 2);
    });

    test('14. Defect 2: Initial HTTP failure and network error show "Chưa tải được", restored on retry', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        let attempt = 0;
        ScreenTitleComments.setFetchImplementation(async () => {
            attempt++;
            if (attempt === 1) {
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    page: 0,
                    hasNext: false,
                    threadCount: 1,
                    commentCount: 5,
                    threads: [
                        { root: { id: 'r1', author: { displayName: 'User' }, body: 'Body' }, replies: [] }
                    ]
                })
            };
        });

        // Step 1: Initial failure
        ScreenTitleComments.init(doc);
        await delay(30);

        assert.strictEqual(badge.textContent, 'Chưa tải được', 'Badge must not say "Đang tải..." or "0 bình luận" after failure');
        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(threadList.querySelector('.screen-discussion-skeleton') === null, true);

        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true);

        // Step 2: Retry restores "Đang tải..." then updates to authoritative count
        await retryBtn.dispatchEvent('click', { target: retryBtn });
        await delay(30);

        assert.strictEqual(badge.textContent, '5 bình luận');
        assert.strictEqual(threadList.getAttribute('aria-busy'), 'false');
        assert.strictEqual(ScreenTitleComments.getState().currentThreads.length, 1);
    });

    test('15. Sort change during pending retry ignores stale retry action', async () => {
        const { badge, status, threadList } = setupDiscussionDomWithSkeleton(doc, TITLE_ID);

        ScreenTitleComments.setFetchImplementation(async (url) => {
            if (url.includes('sort=NEWEST')) {
                return { status: 500 };
            }
            if (url.includes('sort=FEATURED')) {
                return {
                    status: 200,
                    json: async () => ({
                        page: 0,
                        hasNext: false,
                        threadCount: 1,
                        commentCount: 3,
                        threads: [
                            { root: { id: 'feat-1', author: { displayName: 'F' }, body: 'Featured' }, replies: [] }
                        ]
                    })
                };
            }
            return { status: 404 };
        });

        ScreenTitleComments.init(doc);
        await delay(30);

        const retryBtn = status.querySelector('[data-action="retry-discussion-load"]');
        assert.strictEqual(Boolean(retryBtn), true);

        // User switches to FEATURED sort
        await ScreenTitleComments.switchSort('FEATURED', doc);
        await delay(30);
        assert.strictEqual(ScreenTitleComments.getCurrentSort(), 'FEATURED');
        assert.strictEqual(badge.textContent, '3 bình luận');

        // Stale retry button from NEWEST sort should not overwrite FEATURED sort
        await retryBtn.dispatchEvent('click', { target: retryBtn });
        await delay(30);

        assert.strictEqual(ScreenTitleComments.getCurrentSort(), 'FEATURED');
        assert.strictEqual(badge.textContent, '3 bình luận');
    });
});
