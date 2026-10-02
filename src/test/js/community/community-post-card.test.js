const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommunityPostCard = require(path.join(__dirname, '../../../main/resources/static/js/community/community-post-card.js'));

// Lightweight DOM Fixtures for Node test environment
class FakeClassList {
    constructor(el) {
        this.el = el;
        this.classes = new Set();
    }
    add(...names) {
        for (const n of names) if (n) this.classes.add(n);
        this._sync();
    }
    remove(...names) {
        for (const n of names) if (n) this.classes.delete(n);
        this._sync();
    }
    contains(name) { return this.classes.has(name); }
    _sync() {
        if (this.classes.size > 0) this.el.attributes['class'] = Array.from(this.classes).join(' ');
        else delete this.el.attributes['class'];
    }
}

class FakeElement {
    constructor(tagName, attrs = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.classList = new FakeClassList(this);
        this.disabled = false;
        this.value = '';
        this._textContent = '';
        this.listeners = {};
        this.ownerDocument = null;

        for (const [k, v] of Object.entries(attrs)) {
            this.setAttribute(k, v);
        }
    }

    get hidden() {
        return this.attributes['hidden'] !== undefined;
    }
    set hidden(val) {
        if (val) {
            this.attributes['hidden'] = '';
        } else {
            delete this.attributes['hidden'];
        }
    }

    get id() { return this.attributes['id'] || ''; }
    set id(val) {
        if (val) this.setAttribute('id', val);
        else this.removeAttribute('id');
    }

    get dataset() {
        const ds = {};
        for (const [k, v] of Object.entries(this.attributes)) {
            if (k.startsWith('data-')) {
                const camel = k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
                ds[camel] = v;
            }
        }
        return ds;
    }

    get children() { return this.childNodes; }

    get href() { return this.attributes['href'] || ''; }
    set href(val) {
        if (val) this.setAttribute('href', val);
        else this.removeAttribute('href');
    }

    get className() { return this.attributes['class'] || ''; }
    set className(val) {
        this.attributes['class'] = val || '';
        this.classList.classes.clear();
        if (val) {
            val.trim().split(/\s+/).forEach(p => { if (p) this.classList.classes.add(p); });
        }
    }

    get textContent() {
        if (this.childNodes.length === 0) return this._textContent;
        return this.childNodes.map(c => c.textContent || '').join('');
    }
    set textContent(val) {
        this.childNodes = [];
        this._textContent = String(val == null ? '' : val);
    }

    setAttribute(k, v) {
        this.attributes[k] = String(v);
        if (k === 'class') this.className = String(v);
        if (k === 'hidden') this.hidden = true;
    }
    getAttribute(k) { return this.attributes[k] !== undefined ? this.attributes[k] : null; }
    hasAttribute(k) { return this.attributes[k] !== undefined; }
    removeAttribute(k) {
        delete this.attributes[k];
        if (k === 'class') this.classList.classes.clear();
        if (k === 'hidden') this.hidden = false;
    }

    appendChild(child) {
        if (!child) return;
        child.parentNode = this;
        if (!child.ownerDocument && this.ownerDocument) {
            child.ownerDocument = this.ownerDocument;
        }
        this.childNodes.push(child);
        return child;
    }
    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx !== -1) {
            child.parentNode = null;
            this.childNodes.splice(idx, 1);
        }
        return child;
    }

    focus() {}

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }
    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }
    dispatchEvent(event) {
        const type = typeof event === 'string' ? event : event.type;
        const evtObj = typeof event === 'string' ? { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {} } : event;
        if (!evtObj.target) evtObj.target = this;
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(evtObj));
        }
        // Bubble to parent if not stopped
        if (this.parentNode) {
            this.parentNode.dispatchEvent(evtObj);
        } else if (this.ownerDocument && this.ownerDocument !== this) {
            this.ownerDocument.dispatchEvent(evtObj);
        }
    }

    querySelector(selector) {
        return findOne(this, selector);
    }
    querySelectorAll(selector) {
        const res = [];
        findAll(this, selector, res);
        return res;
    }

    closest(selector) {
        let curr = this;
        while (curr) {
            if (matches(curr, selector)) return curr;
            curr = curr.parentNode;
        }
        return null;
    }
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.head = new FakeElement('head');
        this.head.ownerDocument = this;
        this.listeners = {};
    }
    createElement(tag) {
        const el = new FakeElement(tag);
        el.ownerDocument = this;
        return el;
    }
    createTextNode(text) {
        const span = new FakeElement('span');
        span.textContent = text;
        span.ownerDocument = this;
        return span;
    }
    getElementById(id) {
        return findOne(this.body, '#' + id);
    }
    querySelector(selector) {
        let found = findOne(this.head, selector);
        if (found) return found;
        return findOne(this.body, selector);
    }
    querySelectorAll(selector) {
        const res = [];
        findAll(this.head, selector, res);
        findAll(this.body, selector, res);
        return res;
    }
    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }
    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }
    dispatchEvent(event) {
        const type = typeof event === 'string' ? event : event.type;
        const evtObj = typeof event === 'string' ? { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {} } : event;
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(evtObj));
        }
    }
}

function matches(el, sel) {
    if (!el || !el.tagName || !sel) return false;
    let remaining = sel;

    const tagMatch = remaining.match(/^([a-zA-Z0-9_-]+)/);
    if (tagMatch) {
        if (el.tagName.toLowerCase() !== tagMatch[1].toLowerCase()) return false;
        remaining = remaining.slice(tagMatch[1].length);
    }

    while (remaining.length > 0) {
        if (remaining.startsWith('#')) {
            const idMatch = remaining.match(/^#([a-zA-Z0-9_-]+)/);
            if (!idMatch) return false;
            if (el.getAttribute('id') !== idMatch[1]) return false;
            remaining = remaining.slice(idMatch[0].length);
        } else if (remaining.startsWith('.')) {
            const classMatch = remaining.match(/^\.([a-zA-Z0-9_-]+)/);
            if (!classMatch) return false;
            if (!el.classList || !el.classList.contains(classMatch[1])) return false;
            remaining = remaining.slice(classMatch[0].length);
        } else if (remaining.startsWith('[')) {
            const attrMatch = remaining.match(/^\[([a-zA-Z0-9_-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]/);
            if (!attrMatch) return false;
            const attrName = attrMatch[1];
            const attrVal = attrMatch[2] !== undefined ? attrMatch[2] : (attrMatch[3] !== undefined ? attrMatch[3] : attrMatch[4]);
            if (attrVal !== undefined) {
                if (el.getAttribute(attrName) !== attrVal) return false;
            } else {
                if (!el.hasAttribute(attrName)) return false;
            }
            remaining = remaining.slice(attrMatch[0].length);
        } else {
            return false;
        }
    }
    return true;
}

function findOne(root, selector) {
    if (!root) return null;
    if (matches(root, selector)) return root;
    for (const child of root.childNodes || []) {
        const found = findOne(child, selector);
        if (found) return found;
    }
    return null;
}

function findAll(root, selector, result) {
    if (!root) return;
    if (matches(root, selector)) result.push(root);
    for (const child of root.childNodes || []) {
        findAll(child, selector, result);
    }
}

describe('CommunityPostCard Frontend Test Matrix (MS-07B8.3.1 Section 15)', () => {

    const OWNER_ID = '11111111-1111-1111-1111-111111111111';
    const OTHER_USER_ID = '99999999-9999-9999-9999-999999999999';
    const POST_A_ID = 'aaaa1111-1111-1111-1111-111111111111';
    const POST_B_ID = 'bbbb2222-2222-2222-2222-222222222222';

    let originalDoc;
    let originalFetch;
    let mockDoc;

    beforeEach(() => {
        originalDoc = global.document;
        originalFetch = global.fetch;

        mockDoc = new FakeDocument();
        global.document = mockDoc;

        // Default CSRF meta
        const csrfMeta = mockDoc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'test-csrf-token-xyz');
        mockDoc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = mockDoc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        mockDoc.head.appendChild(csrfHeaderMeta);

        // Default current user meta
        const userMeta = mockDoc.createElement('meta');
        userMeta.setAttribute('name', 'current-user-id');
        userMeta.setAttribute('content', OWNER_ID);
        mockDoc.head.appendChild(userMeta);

        CommunityPostCard.initDelegation(mockDoc);
    });

    afterEach(() => {
        global.document = originalDoc;
        global.fetch = originalFetch;
        CommunityPostCard.closeEditModal(mockDoc);
    });

    test('1. owner card renders action trigger', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Owner post caption',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, {
            isAuthenticated: true,
            currentUserId: OWNER_ID
        });

        const trigger = card.querySelector('[data-action="toggle-post-menu"]');
        assert.ok(trigger, 'Owner card must render action trigger');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(trigger.getAttribute('aria-haspopup'), 'menu');

        const menu = card.querySelector('.post-actions-menu');
        assert.ok(menu, 'Owner card must include post actions menu');
        assert.strictEqual(menu.hidden, true, 'Menu must be hidden by default');

        const editItem = card.querySelector('[data-action="edit-post"]');
        assert.ok(editItem, 'Owner menu must include edit button');
        assert.strictEqual(editItem.textContent.trim(), 'Chỉnh sửa bài viết');
    });

    test('2. non-owner card does not render action trigger', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OTHER_USER_ID,
            caption: 'Other user post',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, {
            isAuthenticated: true,
            currentUserId: OWNER_ID
        });

        const trigger = card.querySelector('[data-action="toggle-post-menu"]');
        assert.strictEqual(trigger, null, 'Non-owner card must NOT render action trigger');
        const menu = card.querySelector('.post-actions-menu');
        assert.strictEqual(menu, null, 'Non-owner card must NOT render post actions menu');
    });

    test('3. guest card does not render action trigger', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Guest viewing post',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, {
            isAuthenticated: false,
            currentUserId: null
        });

        const trigger = card.querySelector('[data-action="toggle-post-menu"]');
        assert.strictEqual(trigger, null, 'Guest card must NOT render action trigger');
        const menu = card.querySelector('.post-actions-menu');
        assert.strictEqual(menu, null, 'Guest card must NOT render post actions menu');
    });

    test('4. dynamic card has same owner affordance as SSR card', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Dynamic card test',
            createdAt: '2026-09-30T10:00:00Z'
        };

        // Context with data-current-user-id on feed list
        const feedList = mockDoc.createElement('div');
        feedList.id = 'communityFeedList';
        feedList.setAttribute('data-authenticated', 'true');
        feedList.setAttribute('data-current-user-id', OWNER_ID);
        mockDoc.body.appendChild(feedList);

        const card = CommunityPostCard.create(item);
        feedList.appendChild(card);

        const trigger = card.querySelector('[data-action="toggle-post-menu"]');
        assert.ok(trigger, 'Dynamic card must render action trigger via container context');
        const editBtn = card.querySelector('[data-action="edit-post"]');
        assert.ok(editBtn, 'Dynamic card must render edit button with correct action token');
        assert.strictEqual(editBtn.getAttribute('data-post-id'), POST_A_ID);
    });

    test('5. menu opens/closes correctly (toggle, outside click, escape)', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Menu toggle test',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        const trigger = card.querySelector('[data-action="toggle-post-menu"]');
        const menu = card.querySelector('.post-actions-menu');

        assert.strictEqual(menu.hidden, true);
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Toggle open
        CommunityPostCard.togglePostMenu(trigger);
        assert.strictEqual(menu.hidden, false, 'Menu should be visible after toggle');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');

        // Toggle close
        CommunityPostCard.togglePostMenu(trigger);
        assert.strictEqual(menu.hidden, true, 'Menu should be hidden after second toggle');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Open again, then close via closeAllPostMenus
        CommunityPostCard.togglePostMenu(trigger);
        assert.strictEqual(menu.hidden, false);
        CommunityPostCard.closeAllPostMenus(mockDoc);
        assert.strictEqual(menu.hidden, true);
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
    });

    test('6. edit opens reusable modal (singleton instance)', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Initial post caption',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        const modal1 = mockDoc.getElementById('communityEditPostModal');
        assert.ok(modal1, 'Modal must be created');
        assert.strictEqual(modal1.hidden, false, 'Modal should be shown');

        // Opening again returns identical modal instance (singleton)
        const modal2 = CommunityPostCard.getOrCreateEditModal(mockDoc);
        assert.strictEqual(modal1, modal2, 'Modal must be a reusable singleton');
    });

    test('7. textarea prefilled with exact current caption', () => {
        const initialCaption = 'Exact prefilled caption with\nnew lines and symbols!';
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: initialCaption,
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        assert.strictEqual(textarea.value, initialCaption, 'Textarea must be prefilled with exact current caption');
    });

    test('8. textarea enforces maxlength=2000', () => {
        const modal = CommunityPostCard.getOrCreateEditModal(mockDoc);
        const textarea = modal.querySelector('#communityEditCaptionInput');
        assert.strictEqual(textarea.maxLength, 2000, 'Textarea maxLength must be 2000');
    });

    test('9. character counter updates on input', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Hello',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        const counter = mockDoc.getElementById('communityEditCharCount');
        assert.strictEqual(counter.textContent, '5 / 2000');

        textarea.value = 'Hello World';
        textarea.dispatchEvent({ type: 'input' });
        assert.strictEqual(counter.textContent, '11 / 2000');
    });

    test('10. submit sends PATCH to correct post URL', async () => {
        let capturedUrl = null;
        let capturedMethod = null;
        let capturedBody = null;

        global.fetch = async (url, init) => {
            capturedUrl = url;
            capturedMethod = init.method;
            capturedBody = JSON.parse(init.body);
            return {
                ok: true,
                status: 200,
                headers: { 'content-type': 'application/json' },
                json: async () => ({
                    id: POST_A_ID,
                    authorUserId: OWNER_ID,
                    caption: capturedBody.caption,
                    contentVersion: 1
                })
            };
        };

        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Original caption',
            createdAt: '2026-09-30T10:00:00Z'
        };

        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Newly updated caption';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        assert.strictEqual(capturedUrl, '/api/community/posts/' + POST_A_ID);
        assert.strictEqual(capturedMethod, 'PATCH');
        assert.strictEqual(capturedBody.caption, 'Newly updated caption');
    });

    test('11. CSRF token and header included in request headers', async () => {
        let capturedHeaders = null;

        global.fetch = async (url, init) => {
            capturedHeaders = init.headers;
            return {
                ok: true,
                status: 200,
                headers: { 'content-type': 'application/json' },
                json: async () => ({
                    id: POST_A_ID,
                    caption: 'Valid caption',
                    contentVersion: 1
                })
            };
        };

        const item = { id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Caption', createdAt: '2026-09-30T10:00:00Z' };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        assert.ok(capturedHeaders, 'Headers must be present');
        assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'test-csrf-token-xyz');
        assert.strictEqual(capturedHeaders['Content-Type'], 'application/json');
    });

    test('12. duplicate submit is prevented while request is in flight', async () => {
        let fetchCallCount = 0;
        let resolvePromise;

        global.fetch = async () => {
            fetchCallCount++;
            return new Promise(resolve => {
                resolvePromise = resolve;
            });
        };

        const item = { id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Caption', createdAt: '2026-09-30T10:00:00Z' };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);

        // First submit starts in-flight request
        const firstPromise = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // Submit button should be disabled
        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, true, 'Submit button should be disabled while in flight');

        // Second submit attempt during flight
        const secondPromise = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        assert.strictEqual(fetchCallCount, 1, 'Only one fetch call should be initiated (double submit prevented)');

        // Resolve flight
        resolvePromise({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Caption', contentVersion: 1 })
        });
        await firstPromise;
        await secondPromise;
    });

    test('13. success updates originating card caption only', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 'Updated Caption for Post A',
                contentVersion: 1
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Updated Caption for Post A';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const captionA = cardA.querySelector('.post-caption');
        const captionB = cardB.querySelector('.post-caption');

        assert.strictEqual(captionA.textContent, 'Updated Caption for Post A', 'Card A caption must be updated');
        assert.strictEqual(captionB.textContent, 'Post B initial', 'Card B caption must remain unchanged');

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, true, 'Modal should close on success');
    });

    test('14. server canonical caption is used for card update (not raw client input)', async () => {
        // Server trims or normalizes
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 'Canonical normalized caption from server',
                contentVersion: 1
            })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Old caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = '   Raw client input with whitespace   ';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Canonical normalized caption from server', 'Card must use server canonical caption');
    });

    test('15. error leaves old card caption intact and displays concise message', async () => {
        global.fetch = async () => ({
            ok: false,
            status: 400,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                message: 'Post caption length exceeds maximum limit.'
            })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original untouched caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Too long caption...';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Original untouched caption', 'Old caption must remain intact on error');

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Modal should remain open on error');

        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert should be visible');
        assert.strictEqual(alertDiv.textContent, 'Post caption length exceeds maximum limit.');

        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button should be re-enabled after failure');
    });

    test('16. modal can be cancelled cleanly without mutation', () => {
        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Safe caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false);

        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Typed but aborted';

        // Cancel modal
        CommunityPostCard.closeEditModal(mockDoc);

        assert.strictEqual(modal.hidden, true, 'Modal should be hidden after cancel');
        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Safe caption', 'Card caption must not be mutated');
    });

    test('17. actions on Post A never mutate Post B', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 'Post A New Caption',
                contentVersion: 1
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Old', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B Old', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        // Edit Post A
        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A New Caption';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        assert.strictEqual(cardA.querySelector('.post-caption').textContent, 'Post A New Caption');
        assert.strictEqual(cardB.querySelector('.post-caption').textContent, 'Post B Old');
    });

    test('18. redirect to /login shows auth expiration message and preserves caption and form state', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            redirected: true,
            url: 'http://localhost:8080/login',
            headers: { 'content-type': 'text/html;charset=UTF-8' },
            json: async () => { throw new SyntaxError('Unexpected token <'); }
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Caption Untouched', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Attempted update without auth';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert should be visible');
        assert.strictEqual(alertDiv.textContent, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');

        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Original Caption Untouched', 'Card caption must NOT be modified');

        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button should be re-enabled');

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Modal remains open with error displayed');
    });

    test('19. redirect to /access-denied shows access/security failure message and preserves caption', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            redirected: true,
            url: 'http://localhost:8080/access-denied',
            headers: { 'content-type': 'text/html;charset=UTF-8' },
            json: async () => { throw new SyntaxError('Unexpected token <'); }
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Caption Untouched', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Attempted update with invalid CSRF';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert should be visible');
        assert.strictEqual(alertDiv.textContent, 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');

        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Original Caption Untouched', 'Card caption must NOT be modified');

        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button should be re-enabled');

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Modal remains open');
    });

    test('20. html response with ok: true (non-redirected wrong content-type) shows invalid server response error', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            redirected: false,
            url: 'http://localhost:8080/api/community/posts/' + POST_A_ID,
            headers: { 'content-type': 'text/html;charset=UTF-8' },
            json: async () => { throw new SyntaxError('Unexpected token < in JSON at position 0'); }
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Caption Untouched', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'New caption attempt';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert should be visible');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');

        const captionEl = card.querySelector('.post-caption');
        assert.strictEqual(captionEl.textContent, 'Original Caption Untouched', 'Card caption must NOT be modified');

        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button should be re-enabled');

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Modal remains open');
    });

    test('21. stale async response discard on modal switch: Post B unaffected by late Post A response', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async (url) => {
            if (url.includes(POST_A_ID)) {
                return fetchAPromise;
            }
            return {
                ok: true,
                status: 200,
                headers: { 'content-type': 'application/json' },
                json: async () => ({ id: POST_B_ID, caption: 'Post B Updated', contentVersion: 1 })
            };
        };

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B Initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        // 1. Open and submit Post A
        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A Edited';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // 2. In-flight edit strictly owns modal: close and switch attempts are rejected
        assert.strictEqual(CommunityPostCard.closeEditModal(mockDoc), false, 'Cannot close while submit is in flight');
        assert.strictEqual(CommunityPostCard.openEditModal(POST_B_ID, cardB, mockDoc), false, 'Cannot switch while submit is in flight');

        // 3. Resolve Post A with error to reach terminal state and release lock
        resolveFetchA({
            ok: false,
            status: 400,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ message: 'Post A update failed.' })
        });
        await submitPromiseA;

        // 4. Now that Post A reached terminal state, close and open Post B
        assert.strictEqual(CommunityPostCard.closeEditModal(mockDoc), true, 'Close succeeds after terminal state');
        assert.strictEqual(CommunityPostCard.openEditModal(POST_B_ID, cardB, mockDoc), true, 'Open Post B succeeds');

        assert.strictEqual(textarea.value, 'Post B Initial', 'Textarea should now hold Post B caption');
        const submitBtn = mockDoc.getElementById('communityEditSubmitBtn');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button for Post B should be enabled');

        // Verifications:
        // Post B caption must NOT be changed
        assert.strictEqual(cardB.querySelector('.post-caption').textContent, 'Post B Initial');
        // Post A caption must NOT be changed
        assert.strictEqual(cardA.querySelector('.post-caption').textContent, 'Post A Initial');
        // Modal for Post B is preserved
        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Post B modal remains open');
        assert.strictEqual(textarea.value, 'Post B Initial', 'Textarea still belongs to Post B');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button remains enabled');
    });

    test('22. stale async response discard on reopen of same post: session 2 unaffected by session 1 completion', async () => {
        let resolveSession1;
        const session1Promise = new Promise(resolve => {
            resolveSession1 = resolve;
        });

        global.fetch = async () => session1Promise;

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Base Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        // Session 1: Open and submit
        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Session 1 In-Flight Caption';

        const submitPromise1 = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // In-flight submit owns modal: close is rejected
        assert.strictEqual(CommunityPostCard.closeEditModal(mockDoc), false, 'Cannot close while submit is in flight');

        // Resolve Session 1 with error to reach terminal state and release lock
        resolveSession1({
            ok: false,
            status: 400,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ message: 'Session 1 failed' })
        });
        await submitPromise1;

        // Session 1 reached terminal state, user closes modal
        assert.strictEqual(CommunityPostCard.closeEditModal(mockDoc), true, 'Close succeeds after terminal state');

        // Session 2: Reopen same post
        assert.strictEqual(CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc), true, 'Session 2 reopen succeeds');
        textarea.value = 'Session 2 New Work';

        // Session 2 modal state must NOT be altered
        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Session 2 modal must remain open');
        assert.strictEqual(textarea.value, 'Session 2 New Work', 'Textarea must retain Session 2 content');
        assert.strictEqual(card.querySelector('.post-caption').textContent, 'Original Base Caption', 'Card must not have taken Session 1 content');
        assert.strictEqual(mockDoc.getElementById('communityEditSubmitBtn').disabled, false, 'Submit button enabled');
    });

    test('23. normal success in current session updates card caption and closes modal', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 'Canonical Updated Caption',
                contentVersion: 2
            })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Before Edit', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Canonical Updated Caption';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        assert.strictEqual(card.querySelector('.post-caption').textContent, 'Canonical Updated Caption', 'Card caption must update');
        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, true, 'Modal should close on success');
    });

    test('24. current session error (400 Bad Request) shows error alert, preserves caption, and re-enables submit', async () => {
        global.fetch = async () => ({
            ok: false,
            status: 400,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                message: 'Nội dung bài viết không được để trống.'
            })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Stays Intact', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(card);

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Some client input';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert should be visible');
        assert.strictEqual(alertDiv.textContent, 'Nội dung bài viết không được để trống.');

        assert.strictEqual(card.querySelector('.post-caption').textContent, 'Original Stays Intact', 'Caption remains unchanged');
        assert.strictEqual(mockDoc.getElementById('communityEditSubmitBtn').disabled, false, 'Submit button re-enabled');
        assert.strictEqual(mockDoc.getElementById('communityEditPostModal').hidden, false, 'Modal remains open');
    });

    test('25. submit Post A -> click Hủy while pending: modal stays open, active Post A preserved, request remains owner', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async () => fetchAPromise;

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Original Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A In-Flight Text';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // Verify submitting lock
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'isSubmitting must be true');

        const modal = mockDoc.getElementById('communityEditPostModal');
        const cancelBtn = modal.querySelector('#communityEditCancelBtn') || modal.querySelector('.btn-secondary');
        assert.strictEqual(cancelBtn.disabled, true, 'Cancel button must be disabled while submitting');

        // User or DOM click on Cancel button while pending
        cancelBtn.dispatchEvent('click');

        // Attempt direct closeEditModal while request is in flight
        const closeResult = CommunityPostCard.closeEditModal(mockDoc);
        assert.strictEqual(closeResult, false, 'closeEditModal must return false while request is in flight');

        // Verify modal stays open, request remains owner, Post A preserved
        assert.strictEqual(modal.hidden, false, 'Modal must remain open');
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'Request must remain owner');
        assert.strictEqual(textarea.value, 'Post A In-Flight Text', 'Post A edit content must be preserved');

        // Resolve fetch to cleanly finish
        resolveFetchA({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Post A In-Flight Text', contentVersion: 2 })
        });
        await submitPromiseA;

        assert.strictEqual(modal.hidden, true, 'Modal closes after completion');
        assert.strictEqual(CommunityPostCard.isSubmitting(), false, 'isSubmitting releases after completion');
    });

    test('26. submit Post A -> click X while pending: modal stays open', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async () => fetchAPromise;

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Original Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A In-Flight Text';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');
        const closeBtn = modal.querySelector('#communityEditCloseBtn') || modal.querySelector('.kl-modal-close');
        assert.strictEqual(closeBtn.disabled, true, 'X close button must be disabled while submitting');

        // User click on X while pending
        closeBtn.dispatchEvent('click');

        assert.strictEqual(modal.hidden, false, 'Modal must stay open');
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'isSubmitting remains true');

        resolveFetchA({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Post A In-Flight Text', contentVersion: 2 })
        });
        await submitPromiseA;
    });

    test('27. submit Post A -> backdrop while pending: modal stays open', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async () => fetchAPromise;

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Original Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A In-Flight Text';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');
        const backdrop = modal.querySelector('.kl-modal-backdrop');
        assert.ok(backdrop, 'Backdrop element must exist');

        // User click on backdrop while pending
        backdrop.dispatchEvent('click');

        assert.strictEqual(modal.hidden, false, 'Modal must stay open after backdrop click');
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'isSubmitting remains true');

        resolveFetchA({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Post A In-Flight Text', contentVersion: 2 })
        });
        await submitPromiseA;
    });

    test('28. submit Post A -> Escape while pending: modal stays open', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async () => fetchAPromise;

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Original Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A In-Flight Text';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');

        // Dispatch Escape keydown while pending
        mockDoc.dispatchEvent({ type: 'keydown', key: 'Escape' });

        assert.strictEqual(modal.hidden, false, 'Modal must stay open after Escape press while submitting');
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'isSubmitting remains true');

        resolveFetchA({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Post A In-Flight Text', contentVersion: 2 })
        });
        await submitPromiseA;
    });

    test('29. submit Post A -> attempt open Post B while pending: Post B does NOT replace active edit state', async () => {
        let resolveFetchA;
        const fetchAPromise = new Promise(resolve => {
            resolveFetchA = resolve;
        });

        global.fetch = async (url) => {
            if (url.includes(POST_A_ID)) {
                return fetchAPromise;
            }
            return {
                ok: true,
                status: 200,
                headers: { 'content-type': 'application/json' },
                json: async () => ({ id: POST_B_ID, caption: 'Post B Data', contentVersion: 1 })
            };
        };

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Original', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B Original', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        // Open and submit Post A
        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Post A Edited In-Flight';

        const submitPromiseA = CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // Attempt 1: Call openEditModal(POST_B_ID) directly
        const openResult = CommunityPostCard.openEditModal(POST_B_ID, cardB, mockDoc);
        assert.strictEqual(openResult, false, 'openEditModal must return false while submit is in flight');

        // Attempt 2: Click edit button on Card B
        const editBtnB = cardB.querySelector('[data-action="edit-post"]');
        assert.ok(editBtnB, 'Card B edit button must exist');
        editBtnB.dispatchEvent('click');

        // Verifications: Post B did NOT replace Post A
        assert.strictEqual(textarea.value, 'Post A Edited In-Flight', 'Textarea must retain Post A content');
        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, false, 'Modal remains open for Post A');
        assert.strictEqual(CommunityPostCard.isSubmitting(), true, 'Request ownership remains intact');

        resolveFetchA({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({ id: POST_A_ID, caption: 'Post A Edited In-Flight', contentVersion: 2 })
        });
        await submitPromiseA;
    });

    test('30. after Post A success: modal closes normally, caption updates from canonical server payload', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 'Canonical Server Payload Caption',
                contentVersion: 3
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Old Caption Before Save', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Typed Caption Input';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');
        assert.strictEqual(modal.hidden, true, 'Modal must close normally on success');
        assert.strictEqual(cardA.querySelector('.post-caption').textContent, 'Canonical Server Payload Caption', 'Caption updates from canonical server payload');
        assert.strictEqual(CommunityPostCard.isSubmitting(), false, 'isSubmitting releases after success');
    });

    test('31. after Post A error: submitting releases, user can cancel or open Post B normally', async () => {
        global.fetch = async () => ({
            ok: false,
            status: 400,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                message: 'Nội dung không hợp lệ.'
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A Initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B Initial', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Failed Caption Attempt';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        // Submitting releases after error
        assert.strictEqual(CommunityPostCard.isSubmitting(), false, 'isSubmitting must be false after error');

        const modal = mockDoc.getElementById('communityEditPostModal');
        const alertDiv = mockDoc.getElementById('communityEditModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert is displayed');
        assert.strictEqual(alertDiv.textContent, 'Nội dung không hợp lệ.');

        // User can now cancel normally via Hủy button
        const cancelBtn = modal.querySelector('#communityEditCancelBtn') || modal.querySelector('.btn-secondary');
        assert.strictEqual(cancelBtn.disabled, false, 'Cancel button is re-enabled');
        cancelBtn.dispatchEvent('click');

        assert.strictEqual(modal.hidden, true, 'Modal closes after cancel');

        // User can now open Post B normally
        const openBResult = CommunityPostCard.openEditModal(POST_B_ID, cardB, mockDoc);
        assert.strictEqual(openBResult, true, 'openEditModal for Post B succeeds');
        assert.strictEqual(modal.hidden, false, 'Modal opens for Post B');
        assert.strictEqual(textarea.value, 'Post B Initial', 'Textarea contains Post B caption');
    });

    test('32. 200 application/json with wrong post id: no caption mutation, modal remains open, invalid-response message', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_B_ID, // Mismatched ID!
                caption: 'Caption for different post',
                contentVersion: 1
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Post A Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Edited caption';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');
        const alertDiv = mockDoc.getElementById('communityEditModalAlert');

        // Fail-closed verification
        assert.strictEqual(modal.hidden, false, 'Modal must remain open');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be displayed');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
        assert.strictEqual(cardA.querySelector('.post-caption').textContent, 'Original Post A Caption', 'Card caption must NOT be mutated');
        assert.strictEqual(CommunityPostCard.isSubmitting(), false, 'isSubmitting state released');
    });

    test('33. 200 application/json with missing/non-string caption: same fail-closed behavior', async () => {
        global.fetch = async () => ({
            ok: true,
            status: 200,
            headers: { 'content-type': 'application/json' },
            json: async () => ({
                id: POST_A_ID,
                caption: 99999, // Non-string caption!
                contentVersion: 1
            })
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Original Post A Caption', createdAt: '2026-09-30T10:00:00Z' }, { isAuthenticated: true, currentUserId: OWNER_ID });
        mockDoc.body.appendChild(cardA);

        CommunityPostCard.openEditModal(POST_A_ID, cardA, mockDoc);
        const textarea = mockDoc.getElementById('communityEditCaptionInput');
        textarea.value = 'Edited caption';

        await CommunityPostCard.handleEditSubmit({
            preventDefault: () => {},
            target: mockDoc.getElementById('communityEditPostForm')
        });

        const modal = mockDoc.getElementById('communityEditPostModal');
        const alertDiv = mockDoc.getElementById('communityEditModalAlert');

        // Fail-closed verification
        assert.strictEqual(modal.hidden, false, 'Modal must remain open');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be displayed');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
        assert.strictEqual(cardA.querySelector('.post-caption').textContent, 'Original Post A Caption', 'Card caption must NOT be mutated');
        assert.strictEqual(CommunityPostCard.isSubmitting(), false, 'isSubmitting state released');
    });
});
