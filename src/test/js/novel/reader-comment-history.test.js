const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const historyModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-history.js'));
const mutationsModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-mutations.js'));
const drawerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-drawer.js'));
const bottomCommentsModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comments.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
// ============================================================================

class FakeClassList {
    constructor(element) {
        this.element = element;
        this.classes = new Set();
    }

    add(...names) {
        for (const name of names) {
            this.classes.add(name);
        }
        this._sync();
    }

    remove(...names) {
        for (const name of names) {
            this.classes.delete(name);
        }
        this._sync();
    }

    contains(name) {
        return this.classes.has(name);
    }

    _sync() {
        if (this.classes.size > 0) {
            this.element.attributes['class'] = Array.from(this.classes).join(' ');
        } else {
            delete this.element.attributes['class'];
        }
    }
}

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.parentElement = null;
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this._textContent = '';
        this.isFocused = false;
        this.ownerDocument = null;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get id() {
        return this.getAttribute('id') || '';
    }

    set id(val) {
        this.setAttribute('id', val);
    }

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this._textContent = String(val);
        this.childNodes = [];
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        child.ownerDocument = this.ownerDocument;
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx !== -1) {
            this.childNodes.splice(idx, 1);
            child.parentNode = null;
            child.parentElement = null;
        }
        return child;
    }

    replaceChildren(...newChildren) {
        for (const c of this.childNodes) {
            c.parentNode = null;
            c.parentElement = null;
        }
        this.childNodes = [];
        this._textContent = '';
        for (const child of newChildren) {
            this.appendChild(child);
        }
    }

    contains(node) {
        let cur = node;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes.clear();
            String(value).trim().split(/\s+/).filter(Boolean).forEach(c => this.classList.classes.add(c));
        }
        if (name === 'hidden') {
            this.hidden = true;
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
        if (name === 'hidden') {
            this.hidden = false;
        }
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        try { evt.target = evt.target || this; } catch (_) {}
        try { evt.currentTarget = this; } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        // Propagate click events up to document
        if (evt.type === 'click' && !evt.propagationStopped) {
            if (this.parentElement) {
                this.parentElement.dispatchEvent(evt);
            } else if (this.ownerDocument) {
                this.ownerDocument.dispatchEvent(evt);
            }
        }
        return !evt.defaultPrevented;
    }

    focus() {
        this.isFocused = true;
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
    }

    closest(selector) {
        let cur = this;
        while (cur) {
            if (matchesSingleSelector(cur, selector)) {
                return cur;
            }
            cur = cur.parentElement;
        }
        return null;
    }

    querySelector(selector) {
        const results = this.querySelectorAll(selector);
        return results.length > 0 ? results[0] : null;
    }

    querySelectorAll(selector) {
        const matches = [];
        function walk(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (child instanceof FakeElement) {
                    if (matchesSingleSelector(child, selector)) {
                        matches.push(child);
                    }
                    walk(child);
                }
            }
        }
        walk(this);
        return matches;
    }
}

function matchesSingleSelector(element, selector) {
    if (!selector || !(element instanceof FakeElement)) return false;
    const s = selector.trim();

    if (s.startsWith('#')) {
        return element.getAttribute('id') === s.slice(1);
    }
    if (s.startsWith('.')) {
        return element.classList.contains(s.slice(1));
    }
    if (s.startsWith('[') && s.endsWith(']')) {
        const inner = s.slice(1, -1);
        if (inner.includes('=')) {
            const [attr, rawVal] = inner.split('=');
            const val = rawVal.replace(/^['"]|['"]$/g, '');
            return element.getAttribute(attr) === val;
        }
        return element.hasAttribute(inner);
    }
    return element.tagName.toLowerCase() === s.toLowerCase();
}

class FakeDocument {
    constructor() {
        this.documentElement = new FakeElement('html');
        this.documentElement.ownerDocument = this;
        this.head = new FakeElement('head');
        this.head.ownerDocument = this;
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.documentElement.appendChild(this.head);
        this.documentElement.appendChild(this.body);

        this.listeners = {};
        this.activeElement = null;
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        return this.querySelector('#' + id);
    }

    querySelector(selector) {
        if (matchesSingleSelector(this.documentElement, selector)) return this.documentElement;
        if (matchesSingleSelector(this.body, selector)) return this.body;
        return this.documentElement.querySelector(selector);
    }

    querySelectorAll(selector) {
        return this.documentElement.querySelectorAll(selector);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        try { evt.target = evt.target || this; } catch (_) {}
        try { evt.currentTarget = this; } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

function createFakeEvent(type, target) {
    return {
        type: type,
        target: target,
        currentTarget: target,
        defaultPrevented: false,
        propagationStopped: false,
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() { this.propagationStopped = true; }
    };
}

// ============================================================================
// Fixture Setup Helper
// ============================================================================

function createReaderFixture(chapterId = 'chap-test-123') {
    const doc = new FakeDocument();

    const chapterBody = doc.createElement('article');
    chapterBody.className = 'novel-reader-chapter-body';
    chapterBody.setAttribute('data-chapter-id', chapterId);
    doc.body.appendChild(chapterBody);

    const bottomSection = doc.createElement('section');
    bottomSection.id = 'novelChapterComments';
    bottomSection.className = 'novel-chapter-comments';
    bottomSection.setAttribute('data-chapter-id', chapterId);

    const statusEl = doc.createElement('div');
    statusEl.id = 'novelChapterCommentsStatus';
    bottomSection.appendChild(statusEl);

    const listEl = doc.createElement('div');
    listEl.id = 'novelChapterCommentsList';
    bottomSection.appendChild(listEl);

    const countEl = doc.createElement('span');
    countEl.id = 'novelChapterCommentsCount';
    bottomSection.appendChild(countEl);

    const moreEl = doc.createElement('div');
    moreEl.id = 'novelChapterCommentsMore';
    bottomSection.appendChild(moreEl);

    doc.body.appendChild(bottomSection);

    return { doc, chapterBody, bottomSection, listEl };
}

// ============================================================================
// MS-05E5G5E Reader Comment Revision History UI Test Suite
// ============================================================================

describe('MS-05E5G5E Reader Comment Revision History UI Suite', () => {

    beforeEach(() => {
        historyModule.destroy();
    });

    test('1. edited ACTIVE comment renders clickable history affordance button', async () => {
        const activeEditedRoot = {
            id: 'c-10',
            author: { userId: 'u-1', displayName: 'Author 1' },
            body: 'Edited root body',
            createdAt: '2026-09-18T10:00:00Z',
            updatedAt: '2026-09-18T11:00:00Z',
            status: 'ACTIVE'
        };

        const { doc, listEl } = createReaderFixture('c-1');
        bottomCommentsModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [activeEditedRoot], page: 0, size: 20, hasNext: false })
            })
        });
        await new Promise(r => setTimeout(r, 25));

        // A. Verify edited indicator is a non-interactive SPAN label
        const marker = listEl.querySelector('.novel-comment-edited');
        assert.notStrictEqual(marker, null, 'Marker must be present');
        assert.strictEqual(marker.tagName, 'SPAN', 'Marker must be a SPAN');
        assert.strictEqual(marker.getAttribute('data-action'), null, 'Marker must not have data-action trigger');
        assert.strictEqual(marker.textContent, 'đã chỉnh sửa');

        // B. Verify revision-history action is present in overflow menu
        const historyAction = listEl.querySelector('[data-action="view-revisions"]');
        assert.notStrictEqual(historyAction, null, 'Revision-history action must exist');
        assert.strictEqual(historyAction.getAttribute('data-comment-id'), 'c-10');
        assert.strictEqual(historyAction.textContent.trim(), 'Xem lịch sử chỉnh sửa');
    });

    test('2. unedited comment does NOT render history affordance', async () => {
        const uneditedRoot = {
            id: 'c-11',
            author: { userId: 'u-1', displayName: 'Author 1' },
            body: 'Unedited root body',
            createdAt: '2026-09-18T10:00:00Z',
            updatedAt: '2026-09-18T10:00:00Z',
            status: 'ACTIVE'
        };

        const { doc, listEl } = createReaderFixture('c-1');
        bottomCommentsModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [uneditedRoot], page: 0, size: 20, hasNext: false })
            })
        });
        await new Promise(r => setTimeout(r, 25));

        const marker = listEl.querySelector('.novel-comment-edited');
        assert.strictEqual(marker, null, 'Unedited comment must not render edited marker');
    });

    test('3. tombstone / deleted comment does NOT expose history affordance even if updatedAt > createdAt', async () => {
        const tombstoneRoot = {
            id: 'c-12',
            author: null,
            body: null,
            createdAt: '2026-09-18T10:00:00Z',
            updatedAt: '2026-09-18T12:00:00Z',
            status: 'DELETED',
            tombstone: true
        };

        const { doc, listEl } = createReaderFixture('c-1');
        bottomCommentsModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [tombstoneRoot], page: 0, size: 20, hasNext: false })
            })
        });
        await new Promise(r => setTimeout(r, 25));

        const marker = listEl.querySelector('.novel-comment-edited');
        assert.strictEqual(marker, null, 'Tombstone comment must not render edited marker');
    });

    test('4. Drawer edited marker opens shared history viewer', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
            })
        });

        const modal = historyModule.ensureModal(doc);
        assert.strictEqual(modal.hidden, true, 'Modal should be initially hidden');

        // Simulate edited marker button inside drawer
        const drawerMarker = doc.createElement('button');
        drawerMarker.className = 'novel-comment-edited';
        drawerMarker.setAttribute('data-action', 'view-revisions');
        drawerMarker.setAttribute('data-comment-id', 'drawer-c-1');
        drawerMarker.setAttribute('data-chapter-id', 'chap-d-1');
        doc.body.appendChild(drawerMarker);

        const clickEvt = createFakeEvent('click', drawerMarker);
        drawerMarker.dispatchEvent(clickEvt);

        assert.strictEqual(modal.hidden, false, 'Modal must open on drawer marker click');
    });

    test('5. Bottom-feed edited marker opens the SAME shared viewer', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
            })
        });

        const modal = historyModule.ensureModal(doc);

        const bottomMarker = doc.createElement('button');
        bottomMarker.className = 'novel-comment-edited';
        bottomMarker.setAttribute('data-action', 'view-revisions');
        bottomMarker.setAttribute('data-comment-id', 'bottom-c-2');
        bottomMarker.setAttribute('data-chapter-id', 'chap-b-1');
        doc.body.appendChild(bottomMarker);

        const clickEvt = createFakeEvent('click', bottomMarker);
        bottomMarker.dispatchEvent(clickEvt);

        assert.strictEqual(modal.hidden, false, 'Modal must open on bottom marker click');
        assert.strictEqual(modal.id, 'novelCommentHistoryModal');
    });

    test('6. click triggers correct endpoint with page=0&size=20', async () => {
        const doc = new FakeDocument();
        let requestedUrl = null;

        historyModule.init(doc, {
            fetch: async (url) => {
                requestedUrl = url;
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
                };
            }
        });

        await historyModule.open({
            chapterId: '11111111-1111-1111-1111-111111111111',
            commentId: '22222222-2222-2222-2222-222222222222'
        });

        assert.strictEqual(
            requestedUrl,
            '/api/novel/chapters/11111111-1111-1111-1111-111111111111/comments/22222222-2222-2222-2222-222222222222/revisions?page=0&size=20'
        );
    });

    test('7. revision body / number / timestamp render correctly', async () => {
        const doc = new FakeDocument();
        const fakeRevisions = [
            {
                revisionNumber: 2,
                body: 'Second revision content',
                createdAt: '2026-09-18T10:30:00Z'
            }
        ];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: false })
            })
        });

        const fakeRelativeTime = {
            formatAbsolute(value) {
                return 'EXACT:' + value;
            },
            formatElement(el) {
                const raw = el.getAttribute('datetime');
                el.textContent = 'RELATIVE:' + raw;
                el.setAttribute('title', 'EXACT:' + raw);
                el.setAttribute('aria-label', 'RELATIVE:' + raw + ', EXACT:' + raw);
                return true;
            }
        };
        historyModule.setRelativeTime(fakeRelativeTime);

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const entries = listEl.querySelectorAll('.novel-comment-history-entry');
        assert.strictEqual(entries.length, 1);

        const badge = entries[0].querySelector('.novel-comment-history-badge');
        assert.strictEqual(badge.textContent, 'Phiên bản #2');

        const bodyEl = entries[0].querySelector('.novel-comment-history-entry-body');
        assert.strictEqual(bodyEl.textContent, 'Second revision content');

        const timeEl = entries[0].querySelector('.novel-comment-history-time');
        assert.ok(timeEl, 'Must render .novel-comment-history-time');
        assert.strictEqual(timeEl.hasAttribute('data-relative-time'), true, 'Must have data-relative-time');
        assert.strictEqual(timeEl.getAttribute('datetime'), '2026-09-18T10:30:00Z', 'Must preserve datetime');
        assert.strictEqual(timeEl.textContent, 'RELATIVE:2026-09-18T10:30:00Z', 'Must format visible content with injected RelativeTime');
        assert.strictEqual(timeEl.getAttribute('title'), 'EXACT:2026-09-18T10:30:00Z', 'Must have exact title from injected RelativeTime');
        assert.strictEqual(timeEl.getAttribute('aria-label'), 'RELATIVE:2026-09-18T10:30:00Z, EXACT:2026-09-18T10:30:00Z', 'Must have aria-label from injected RelativeTime');
    });

    test('7b. invalid revision timestamp does not append empty time element', async () => {
        const doc = new FakeDocument();
        const fakeRevisions = [
            {
                revisionNumber: 1,
                body: 'Rev with invalid date',
                createdAt: 'invalid-date'
            }
        ];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: false })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const entries = listEl.querySelectorAll('.novel-comment-history-entry');
        assert.strictEqual(entries.length, 1);
        const timeEl = entries[0].querySelector('.novel-comment-history-time');
        assert.strictEqual(timeEl, null, 'Invalid revision timestamp must NOT append an empty <time> element');
    });

    test('8. entries preserve newest-first server order', async () => {
        const doc = new FakeDocument();
        const fakeRevisions = [
            { revisionNumber: 3, body: 'Revision 3 body', createdAt: '2026-09-18T12:00:00Z' },
            { revisionNumber: 2, body: 'Revision 2 body', createdAt: '2026-09-18T11:00:00Z' },
            { revisionNumber: 1, body: 'Revision 1 body', createdAt: '2026-09-18T10:00:00Z' }
        ];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: false })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const badges = listEl.querySelectorAll('.novel-comment-history-badge');
        assert.strictEqual(badges.length, 3);
        assert.strictEqual(badges[0].textContent, 'Phiên bản #3');
        assert.strictEqual(badges[1].textContent, 'Phiên bản #2');
        assert.strictEqual(badges[2].textContent, 'Phiên bản #1');
    });

    test('9. current Comment body is NOT injected into history', async () => {
        const doc = new FakeDocument();
        // Server delivers only prior revisions, never current body
        const fakeRevisions = [
            { revisionNumber: 1, body: 'Original historical body A', createdAt: '2026-09-18T09:00:00Z' }
        ];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: false })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(listEl.childNodes.length, 1);
        assert.strictEqual(listEl.textContent.includes('Current body C'), false);
    });

    test('10. hasNext=true exposes load-more button', async () => {
        const doc = new FakeDocument();
        const fakeRevisions = [{ revisionNumber: 2, body: 'B', createdAt: '2026-09-18T10:00:00Z' }];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: true })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const moreContainer = doc.querySelector('#novelCommentHistoryMore');
        const moreBtn = doc.querySelector('#novelCommentHistoryMoreBtn');
        assert.strictEqual(moreContainer.hidden, false, 'More container must be visible when hasNext=true');
        assert.strictEqual(moreBtn.textContent, 'Xem thêm');
    });

    test('11. load-more requests next page and appends new entries', async () => {
        const doc = new FakeDocument();
        let capturedUrls = [];

        historyModule.init(doc, {
            fetch: async (url) => {
                capturedUrls.push(url);
                if (url.includes('page=0')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Rev 2', createdAt: '2026-09-18T10:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                } else if (url.includes('page=1')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 1, body: 'Rev 1', createdAt: '2026-09-18T09:00:00Z' }],
                            page: 1,
                            size: 20,
                            hasNext: false
                        })
                    };
                }
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(listEl.querySelectorAll('.novel-comment-history-entry').length, 1);

        // Click load-more
        const moreBtn = doc.querySelector('#novelCommentHistoryMoreBtn');
        const clickEvt = createFakeEvent('click', moreBtn);
        moreBtn.dispatchEvent(clickEvt);
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(capturedUrls.length, 2);
        assert.ok(capturedUrls[1].includes('page=1&size=20'));
        assert.strictEqual(listEl.querySelectorAll('.novel-comment-history-entry').length, 2);
    });

    test('12. hasNext=false prevents further pagination and hides load-more', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    items: [{ revisionNumber: 1, body: 'Rev 1', createdAt: '2026-09-18T09:00:00Z' }],
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        const moreContainer = doc.querySelector('#novelCommentHistoryMore');
        assert.strictEqual(moreContainer.hidden, true, 'Load more must be hidden when hasNext=false');
    });

    test('13. opening Comment B resets state from Comment A', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('cm-A')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 1, body: 'Body A', createdAt: '2026-09-18T09:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: false
                        })
                    };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 5, body: 'Body B', createdAt: '2026-09-18T11:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-A' });
        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(listEl.textContent.includes('Body A'), true);

        // Open Comment B
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-B' });
        assert.strictEqual(listEl.textContent.includes('Body A'), false, 'Body A must be cleared');
        assert.strictEqual(listEl.textContent.includes('Body B'), true, 'Body B must be rendered');
    });

    test('14. late response for A cannot overwrite B', async () => {
        const doc = new FakeDocument();
        let resolveA;
        const pendingPromiseA = new Promise(r => { resolveA = r; });

        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('cm-A')) {
                    return pendingPromiseA;
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Authoritative B', createdAt: '2026-09-18T11:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
        });

        // 1. Trigger open A (pending)
        historyModule.open({ chapterId: 'ch-1', commentId: 'cm-A' });

        // 2. Trigger open B (resolves quickly)
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-B' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(listEl.textContent.includes('Authoritative B'), true);

        // 3. Late response A resolves
        resolveA({
            ok: true,
            status: 200,
            json: async () => ({
                items: [{ revisionNumber: 1, body: 'Late response A', createdAt: '2026-09-18T09:00:00Z' }],
                page: 0,
                size: 20,
                hasNext: false
            })
        });
        await new Promise(r => setTimeout(r, 15));

        // B must NOT be overwritten by A!
        assert.strictEqual(listEl.textContent.includes('Authoritative B'), true);
        assert.strictEqual(listEl.textContent.includes('Late response A'), false);
    });

    test('15. stale load-more response cannot append to another Comment', async () => {
        const doc = new FakeDocument();
        let resolveLoadMoreA;
        const pendingMoreA = new Promise(r => { resolveLoadMoreA = r; });

        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('cm-A') && url.includes('page=1')) {
                    return pendingMoreA;
                }
                if (url.includes('cm-A')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Rev A2', createdAt: '2026-09-18T10:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Rev B1', createdAt: '2026-09-18T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-A' });

        // Trigger load-more for A (pending)
        historyModule.loadMore();

        // Switch to Comment B
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-B' });

        // Late load-more for A resolves
        resolveLoadMoreA({
            ok: true,
            status: 200,
            json: async () => ({
                items: [{ revisionNumber: 1, body: 'Rev A1 (stale more)', createdAt: '2026-09-18T09:00:00Z' }],
                page: 1,
                size: 20,
                hasNext: false
            })
        });
        await new Promise(r => setTimeout(r, 15));

        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(listEl.textContent.includes('Rev A1 (stale more)'), false);
        assert.strictEqual(listEl.textContent.includes('Rev B1'), true);
    });

    test('15b. active load-more 404 clears previously rendered history, shows unavailable status, and hides load-more', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('page=0')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Initial historical body', createdAt: '2026-09-18T10:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                // page=1 returns 404
                return {
                    ok: false,
                    status: 404,
                    json: async () => ({ message: 'Not found' })
                };
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const statusEl = doc.querySelector('#novelCommentHistoryStatus');
        const moreContainer = doc.querySelector('#novelCommentHistoryMore');
        const moreBtn = doc.querySelector('#novelCommentHistoryMoreBtn');

        // Confirm page 0 rendered
        assert.strictEqual(listEl.textContent.includes('Initial historical body'), true);
        assert.strictEqual(moreContainer.hidden, false);

        // Click load-more (triggers page=1 -> 404)
        const clickEvt = createFakeEvent('click', moreBtn);
        moreBtn.dispatchEvent(clickEvt);
        await new Promise(r => setTimeout(r, 20));

        // 1. All previously rendered revision bodies must be cleared
        assert.strictEqual(listEl.childNodes.length, 0);
        assert.strictEqual(listEl.textContent.includes('Initial historical body'), false);

        // 2. Unavailable status is shown
        assert.strictEqual(statusEl.textContent, 'Lịch sử chỉnh sửa không còn khả dụng.');

        // 3. Load-more is hidden and retry action is NOT exposed
        assert.strictEqual(moreContainer.hidden, true);
    });

    test('15c. stale load-more 404 from old Comment cannot clear currently opened Comment', async () => {
        const doc = new FakeDocument();
        let resolveLoadMoreA;
        const pendingMoreA = new Promise(r => { resolveLoadMoreA = r; });

        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('cm-A') && url.includes('page=1')) {
                    return pendingMoreA;
                }
                if (url.includes('cm-A')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Rev A', createdAt: '2026-09-18T10:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Rev B', createdAt: '2026-09-18T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-A' });

        // Trigger load-more for A (pending)
        historyModule.loadMore();

        // Switch to Comment B
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-B' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const statusEl = doc.querySelector('#novelCommentHistoryStatus');
        assert.strictEqual(listEl.textContent.includes('Rev B'), true);

        // Late load-more for A rejects with 404
        resolveLoadMoreA({
            ok: false,
            status: 404,
            json: async () => ({ message: 'Not found' })
        });
        await new Promise(r => setTimeout(r, 20));

        // Comment B's revisions must NOT be cleared by A's stale 404!
        assert.strictEqual(listEl.textContent.includes('Rev B'), true);
        assert.notStrictEqual(statusEl.textContent, 'Lịch sử chỉnh sửa không còn khả dụng.');
    });

    test('15d. non-404 load-more failure preserves rendered history and allows retry', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async (url) => {
                if (url.includes('page=0')) {
                    return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Rev 2 preserved', createdAt: '2026-09-18T10:00:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                // HTTP 500 error on page 1
                return {
                    ok: false,
                    status: 500,
                    json: async () => ({ message: 'Server error' })
                };
            }
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const listEl = doc.querySelector('#novelCommentHistoryList');
        const moreContainer = doc.querySelector('#novelCommentHistoryMore');
        const moreBtn = doc.querySelector('#novelCommentHistoryMoreBtn');

        assert.strictEqual(listEl.textContent.includes('Rev 2 preserved'), true);

        // Trigger load-more
        const clickEvt = createFakeEvent('click', moreBtn);
        moreBtn.dispatchEvent(clickEvt);
        await new Promise(r => setTimeout(r, 20));

        // History must be preserved
        assert.strictEqual(listEl.textContent.includes('Rev 2 preserved'), true);
        // Retry button exposed
        assert.strictEqual(moreContainer.hidden, false);
        assert.strictEqual(moreBtn.textContent, 'Thử lại');
        assert.strictEqual(moreBtn.disabled, false);
    });

    test('16. 404 clears old history and shows neutral unavailable state', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: false,
                status: 404,
                json: async () => ({ message: 'Not found' })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-deleted' });

        const statusEl = doc.querySelector('#novelCommentHistoryStatus');
        const listEl = doc.querySelector('#novelCommentHistoryList');
        assert.strictEqual(statusEl.textContent, 'Lịch sử chỉnh sửa không còn khả dụng.');
        assert.strictEqual(listEl.childNodes.length, 0);
    });

    test('17. deleted/tombstoned Comment cannot reopen history', async () => {
        const tombstone = {
            id: 'c-tombstone',
            tombstone: true,
            status: 'DELETED'
        };

        const { doc, listEl } = createReaderFixture('c-1');
        bottomCommentsModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [tombstone], page: 0, size: 20, hasNext: false })
            })
        });
        await new Promise(r => setTimeout(r, 25));

        // No history affordance exists to click
        const trigger = listEl.querySelector('[data-action="view-revisions"]');
        assert.strictEqual(trigger, null, 'Deleted/tombstoned comment must not have history trigger');
    });

    test('18. no restore / revert / mutation control exists in viewer', async () => {
        const doc = new FakeDocument();
        const fakeRevisions = [
            { revisionNumber: 1, body: 'Read-only revision body', createdAt: '2026-09-18T10:00:00Z' }
        ];

        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: fakeRevisions, page: 0, size: 20, hasNext: false })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });

        const modal = doc.querySelector('#novelCommentHistoryModal');
        // Ensure no restore, revert, edit, delete, or mutation controls exist
        assert.strictEqual(modal.querySelector('[data-action="restore"]'), null);
        assert.strictEqual(modal.querySelector('[data-action="revert"]'), null);
        assert.strictEqual(modal.querySelector('[data-action="edit"]'), null);
        assert.strictEqual(modal.querySelector('[data-action="delete"]'), null);
        assert.strictEqual(modal.textContent.includes('Khôi phục'), false);
        assert.strictEqual(modal.textContent.includes('Quay lại bản này'), false);
    });

    test('19. Escape key and close button close the modal and restore focus', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
            })
        });

        const openerBtn = doc.createElement('button');
        doc.body.appendChild(openerBtn);
        openerBtn.focus();

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        const modal = doc.querySelector('#novelCommentHistoryModal');
        assert.strictEqual(modal.hidden, false);

        // Assert that on open, document.activeElement is the real close button, NOT backdrop
        const closeBtn = modal.querySelector('.novel-comment-history-modal-close');
        assert.notStrictEqual(closeBtn, null, 'Close button must exist in modal');
        assert.strictEqual(closeBtn.tagName, 'BUTTON', 'Close element must be a button');
        assert.strictEqual(doc.activeElement, closeBtn, 'document.activeElement on modal open must be the real close button');

        const backdrop = modal.querySelector('.novel-comment-history-modal-backdrop');
        assert.notStrictEqual(backdrop, null);
        assert.notStrictEqual(doc.activeElement, backdrop, 'Backdrop must NEVER receive focus on open');

        // Test 1: Escape key closes modal and restores focus
        const escEvt = createFakeEvent('keydown', doc);
        escEvt.key = 'Escape';
        doc.dispatchEvent(escEvt);
        assert.strictEqual(modal.hidden, true);
        assert.strictEqual(openerBtn.isFocused, true);

        // Re-open modal
        openerBtn.focus();
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(doc.activeElement, closeBtn);

        // Test 2: Close button click closes modal and restores focus
        const closeEvt = createFakeEvent('click', closeBtn);
        closeBtn.dispatchEvent(closeEvt);
        assert.strictEqual(modal.hidden, true);
        assert.strictEqual(openerBtn.isFocused, true);

        // Re-open modal
        openerBtn.focus();
        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        assert.strictEqual(modal.hidden, false);

        // Test 3: Backdrop click closes modal and restores focus
        const backdropEvt = createFakeEvent('click', backdrop);
        backdrop.dispatchEvent(backdropEvt);
        assert.strictEqual(modal.hidden, true);
        assert.strictEqual(openerBtn.isFocused, true);
    });

    test('20. kiemlai:chapter-changed closes modal and invalidates state', async () => {
        const doc = new FakeDocument();
        historyModule.init(doc, {
            fetch: async () => ({
                ok: true,
                status: 200,
                json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
            })
        });

        await historyModule.open({ chapterId: 'ch-1', commentId: 'cm-1' });
        const modal = doc.querySelector('#novelCommentHistoryModal');
        assert.strictEqual(modal.hidden, false);

        // Dispatch chapter changed
        const changeEvt = createFakeEvent('kiemlai:chapter-changed', doc);
        doc.dispatchEvent(changeEvt);

        assert.strictEqual(modal.hidden, true);
    });
});
