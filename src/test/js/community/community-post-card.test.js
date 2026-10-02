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
    get nextSibling() {
        if (!this.parentNode) return null;
        const siblings = this.parentNode.childNodes;
        const idx = siblings.indexOf(this);
        return idx !== -1 && idx + 1 < siblings.length ? siblings[idx + 1] : null;
    }
    insertBefore(newChild, refChild) {
        if (!newChild) return null;
        newChild.remove();
        newChild.parentNode = this;
        if (!newChild.ownerDocument && this.ownerDocument) {
            newChild.ownerDocument = this.ownerDocument;
        }
        if (!refChild) {
            this.childNodes.push(newChild);
        } else {
            const idx = this.childNodes.indexOf(refChild);
            if (idx === -1) {
                this.childNodes.push(newChild);
            } else {
                this.childNodes.splice(idx, 0, newChild);
            }
        }
        return newChild;
    }

    remove() {
        if (this.parentNode) {
            this.parentNode.removeChild(this);
        }
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
        const evtObj = typeof event === 'string' ? { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.propagationStopped = true; } } : event;
        if (!evtObj.target) evtObj.target = this;
        if (typeof evtObj.preventDefault !== 'function') {
            evtObj.preventDefault = function () { this.defaultPrevented = true; };
        }
        if (typeof evtObj.stopPropagation !== 'function') {
            evtObj.stopPropagation = function () { this.propagationStopped = true; };
        }
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(evtObj));
        }
        // Bubble to parent if not stopped
        if (!evtObj.propagationStopped) {
            if (this.parentNode) {
                this.parentNode.dispatchEvent(evtObj);
            } else if (this.ownerDocument && this.ownerDocument !== this) {
                this.ownerDocument.dispatchEvent(evtObj);
            }
        }
    }

    querySelector(selector) {
        return findOne(this, selector);
    }
    querySelectorAll(selector) {
        const res = [];
        for (const child of this.childNodes || []) {
            findAll(child, selector, res);
        }
        return res;
    }

    matches(selector) {
        return matches(this, selector);
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
        const evtObj = typeof event === 'string' ? { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.propagationStopped = true; } } : event;
        if (!evtObj.target) evtObj.target = this;
        if (typeof evtObj.preventDefault !== 'function') {
            evtObj.preventDefault = function () { this.defaultPrevented = true; };
        }
        if (typeof evtObj.stopPropagation !== 'function') {
            evtObj.stopPropagation = function () { this.propagationStopped = true; };
        }
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(evtObj));
        }
    }
    matches(sel) {
        return matches(this, sel);
    }
}

function matches(el, sel) {
    if (!el || !el.tagName || !sel) return false;
    if (sel.includes(',')) {
        return sel.split(',').some(part => matches(el, part.trim()));
    }
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
        } else if (remaining.startsWith(':not(')) {
            const notEnd = remaining.indexOf(')');
            if (notEnd === -1) return false;
            const innerSel = remaining.slice(5, notEnd);
            if (matches(el, innerSel)) return false;
            remaining = remaining.slice(notEnd + 1);
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
        CommunityPostCard.closeDeleteModal(mockDoc);
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

    test('34. Authenticated dynamic card with currentUserReaction sets data-reaction-current attribute on widget', () => {
        const item = {
            id: 'post-with-like-1',
            authorUserId: 'author-1',
            caption: 'Post with Like',
            createdAt: '2026-09-30T10:00:00Z',
            reactionCount: 1,
            currentUserReaction: 'LIKE'
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: 'user-1' }, mockDoc);
        const widget = card.querySelector('.kl-reaction-widget');
        assert.ok(widget, 'Reaction widget must exist for authenticated user');
        assert.strictEqual(widget.getAttribute('data-reaction-target-type'), 'COMMUNITY_POST');
        assert.strictEqual(widget.getAttribute('data-reaction-target-id'), 'post-with-like-1');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '1');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'LIKE', 'data-reaction-current must be LIKE');
    });

    test('35. Authenticated dynamic card without currentUserReaction does not set data-reaction-current attribute', () => {
        const item = {
            id: 'post-no-reaction-2',
            authorUserId: 'author-2',
            caption: 'Post without reaction',
            createdAt: '2026-09-30T10:00:00Z',
            reactionCount: 0,
            currentUserReaction: null
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: 'user-1' }, mockDoc);
        const widget = card.querySelector('.kl-reaction-widget');
        assert.ok(widget, 'Reaction widget must exist for authenticated user');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), null, 'data-reaction-current must not be set');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '0');
    });

    test('36. Guest dynamic card renders login link without reaction widget', () => {
        const item = {
            id: 'post-guest-3',
            authorUserId: 'author-3',
            caption: 'Guest post',
            createdAt: '2026-09-30T10:00:00Z',
            reactionCount: 3,
            currentUserReaction: null
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: false }, mockDoc);
        const widget = card.querySelector('.kl-reaction-widget');
        assert.strictEqual(widget, null, 'Reaction widget must NOT be rendered for guest');
        const loginLink = card.querySelector('.post-metric--login-link');
        assert.ok(loginLink, 'Login link must be rendered for guest');
        assert.ok(loginLink.textContent.includes('3'), 'Guest count should be 3');
    });

    test('37. InteractionReactions.hydrate hydrates card with currentUserReaction="LIKE" to active trigger without click', () => {
        const InteractionReactions = require(path.join(__dirname, '../../../main/resources/static/js/shared/interaction-reactions.js'));
        const item = {
            id: 'post-hydrate-4',
            authorUserId: 'author-4',
            caption: 'Post to hydrate',
            createdAt: '2026-09-30T10:00:00Z',
            reactionCount: 1,
            currentUserReaction: 'LIKE'
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: 'user-1' }, mockDoc);
        const widget = card.querySelector('.kl-reaction-widget');
        assert.ok(widget);
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'LIKE');

        // Hydrate widget using InteractionReactions
        InteractionReactions.hydrate(widget, mockDoc);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        assert.ok(trigger, 'Trigger button must be rendered by hydrate');
        assert.strictEqual(trigger.classList.contains('has-reaction'), true, 'Trigger must have has-reaction class immediately');
        assert.strictEqual(trigger.classList.contains('is-like'), true, 'Trigger must have is-like class immediately');
        assert.ok(trigger.getAttribute('aria-label').includes('Đã thích'), 'Aria-label must indicate already liked without requiring a click');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '1');
    });
});

describe('CommunityPostCard Owner Delete UX Test Matrix (MS-07B8.3.2)', () => {

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
        CommunityPostCard.closeDeleteModal(mockDoc);
    });

    test('1. Owner card renders both Edit and Delete actions in post actions menu', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Owner post caption',
            createdAt: '2026-09-30T10:00:00Z'
        };
        const card = CommunityPostCard.create(item, {
            isAuthenticated: true,
            currentUserId: OWNER_ID
        }, mockDoc);

        const editBtn = card.querySelector('[data-action="edit-post"]');
        assert.ok(editBtn, 'Edit action button must exist for owner');

        const deleteBtn = card.querySelector('[data-action="delete-post"]');
        assert.ok(deleteBtn, 'Delete action button must exist for owner');
        assert.strictEqual(deleteBtn.getAttribute('data-post-id'), POST_A_ID);
        assert.ok(deleteBtn.classList.contains('post-actions-item--danger'), 'Must have danger styling class');
        assert.ok(deleteBtn.classList.contains('text-danger'), 'Must have text-danger class');
        assert.ok(deleteBtn.textContent.includes('Xóa bài viết'), 'Must have correct Vietnamese label');
    });

    test('2. Non-owner card does not render actions dropdown, edit, or delete buttons', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Other user post',
            createdAt: '2026-09-30T10:00:00Z'
        };
        const card = CommunityPostCard.create(item, {
            isAuthenticated: true,
            currentUserId: OTHER_USER_ID
        }, mockDoc);

        const dropdown = card.querySelector('.post-actions-dropdown');
        assert.strictEqual(dropdown, null, 'Non-owner must not have actions dropdown');
        assert.strictEqual(card.querySelector('[data-action="delete-post"]'), null);
        assert.strictEqual(card.querySelector('[data-action="edit-post"]'), null);
    });

    test('3. Guest card does not render actions dropdown, edit, or delete buttons', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Guest viewed post',
            createdAt: '2026-09-30T10:00:00Z'
        };
        const card = CommunityPostCard.create(item, {
            isAuthenticated: false
        }, mockDoc);

        const dropdown = card.querySelector('.post-actions-dropdown');
        assert.strictEqual(dropdown, null, 'Guest must not have actions dropdown');
        assert.strictEqual(card.querySelector('[data-action="delete-post"]'), null);
        assert.strictEqual(card.querySelector('[data-action="edit-post"]'), null);
    });

    test('4. Clicking Delete action opens delete confirmation modal (singleton)', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Post A',
            createdAt: '2026-09-30T10:00:00Z'
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        const deleteBtn = card.querySelector('[data-action="delete-post"]');
        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });

        const modal = mockDoc.getElementById('communityDeletePostModal');
        assert.ok(modal, 'Delete modal must exist in DOM');
        assert.strictEqual(modal.hidden, false, 'Delete modal must be visible');

        const warning = modal.querySelector('.community-delete-warning');
        assert.ok(warning, 'Warning text must exist');
        assert.ok(warning.textContent.includes('Bài viết và toàn bộ bình luận, cảm xúc liên quan sẽ bị xóa vĩnh viễn'));

        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        assert.ok(alertDiv, 'Alert div must exist');
        assert.strictEqual(alertDiv.hidden, true, 'Alert div must be initially hidden');

        // Singleton check
        const modal2 = CommunityPostCard.getOrCreateDeleteModal(mockDoc);
        assert.strictEqual(modal, modal2, 'Must return the same singleton modal instance');
    });

    test('5. Clicking Cancel button closes delete modal without network request', () => {
        let fetchCalled = false;
        global.fetch = () => { fetchCalled = true; return Promise.resolve(); };

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        assert.strictEqual(modal.hidden, false);

        const cancelBtn = modal.querySelector('[data-action="cancel-delete"]');
        cancelBtn.dispatchEvent({ type: 'click', target: cancelBtn });

        assert.strictEqual(modal.hidden, true, 'Modal must be hidden after cancel');
        assert.strictEqual(fetchCalled, false, 'Fetch must never be called on cancel');
    });

    test('6. Clicking modal backdrop closes delete modal without network request', () => {
        let fetchCalled = false;
        global.fetch = () => { fetchCalled = true; return Promise.resolve(); };

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        assert.strictEqual(modal.hidden, false);

        const backdrop = modal.querySelector('.kl-modal-backdrop');
        backdrop.dispatchEvent({ type: 'click', target: backdrop });

        assert.strictEqual(modal.hidden, true, 'Modal must be hidden after backdrop click');
        assert.strictEqual(fetchCalled, false, 'Fetch must not be called');
    });

    test('7. Pressing Escape key closes delete modal when open and not deleting', () => {
        let fetchCalled = false;
        global.fetch = () => { fetchCalled = true; return Promise.resolve(); };

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        assert.strictEqual(modal.hidden, false);

        mockDoc.dispatchEvent({ type: 'keydown', key: 'Escape' });

        assert.strictEqual(modal.hidden, true, 'Modal must be hidden after Escape');
        assert.strictEqual(fetchCalled, false, 'Fetch must not be called');
    });

    test('8. Opening modal for Post B after Post A updates active delete post cleanly', async () => {
        let requestedUrl = null;
        global.fetch = (url) => {
            requestedUrl = url;
            return Promise.resolve({
                status: 204,
                redirected: false,
                headers: { get: () => 'application/json' }
            });
        };

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        // Open A then open B
        CommunityPostCard.openDeleteModal(POST_A_ID, cardA, mockDoc);
        CommunityPostCard.openDeleteModal(POST_B_ID, cardB, mockDoc);

        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');
        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(requestedUrl, '/api/community/posts/' + POST_B_ID, 'Must delete Post B, not Post A');
        assert.strictEqual(mockDoc.querySelector('[data-post-id="' + POST_B_ID + '"]'), null, 'Post B must be removed');
        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Post A must remain untouched');
    });

    test('9. Confirm delete sends DELETE request with CSRF token and header', async () => {
        let recordedUrl = null;
        let recordedOptions = null;
        global.fetch = (url, options) => {
            recordedUrl = url;
            recordedOptions = options;
            return Promise.resolve({
                status: 204,
                redirected: false,
                headers: { get: () => 'application/json' }
            });
        };

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(recordedUrl, '/api/community/posts/' + POST_A_ID);
        assert.strictEqual(recordedOptions.method, 'DELETE');
        assert.strictEqual(recordedOptions.headers['X-CSRF-TOKEN'], 'test-csrf-token-xyz');
    });

    test('10. Strict 204 No Content response removes target card from DOM and preserves unrelated cards', async () => {
        global.fetch = () => Promise.resolve({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' }
        });

        const cardA = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        const cardB = CommunityPostCard.create({ id: POST_B_ID, authorUserId: OWNER_ID, caption: 'Post B' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(cardA);
        mockDoc.body.appendChild(cardB);

        CommunityPostCard.openDeleteModal(POST_A_ID, cardA, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), null, 'Post A card must be removed');
        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_B_ID + '"]'), 'Post B card must be preserved');
        assert.strictEqual(modal.hidden, true, 'Modal must close on 204');
        assert.strictEqual(CommunityPostCard.isDeleting(), false, 'isDeleting state must be false');
    });

    test('11. Strict 204 response on final feed card unhides emptyFeedMessage', async () => {
        global.fetch = () => Promise.resolve({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' }
        });

        const feedList = mockDoc.createElement('div');
        feedList.id = 'communityFeedList';
        mockDoc.body.appendChild(feedList);

        const emptyFeedMsg = mockDoc.createElement('div');
        emptyFeedMsg.id = 'emptyFeedMessage';
        emptyFeedMsg.hidden = true;
        mockDoc.body.appendChild(emptyFeedMsg);

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Only Post' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        feedList.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(feedList.querySelector('.community-post-card'), null, 'Feed card must be removed');
        assert.strictEqual(emptyFeedMsg.hidden, false, 'emptyFeedMessage must be unhidden when no cards remain');
    });

    test('12. Strict 204 response on final profile card unhides empty-feed-card in community-profile-container', async () => {
        global.fetch = () => Promise.resolve({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' }
        });

        const profileContainer = mockDoc.createElement('div');
        profileContainer.className = 'community-profile-container';
        mockDoc.body.appendChild(profileContainer);

        const emptyCard = mockDoc.createElement('div');
        emptyCard.className = 'empty-feed-card';
        emptyCard.hidden = true;
        profileContainer.appendChild(emptyCard);

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Only Profile Post' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        profileContainer.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(profileContainer.querySelector('.community-post-card'), null, 'Profile card must be removed');
        assert.strictEqual(emptyCard.hidden, false, 'empty-feed-card must be unhidden');
    });

    test('13. Non-204 responses (200, 201, 202) are rejected as invalid server response and keep card in DOM', async () => {
        for (const statusCode of [200, 201, 202]) {
            global.fetch = () => Promise.resolve({
                status: statusCode,
                redirected: false,
                headers: { get: () => 'application/json' },
                json: () => Promise.resolve({ message: 'Unexpected success' })
            });

            const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
            mockDoc.body.appendChild(card);

            CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
            const modal = mockDoc.getElementById('communityDeletePostModal');
            const alertDiv = modal.querySelector('#communityDeleteModalAlert');
            const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

            await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

            assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), `Post must not be removed on status ${statusCode}`);
            assert.strictEqual(modal.hidden, false, `Modal must remain open on status ${statusCode}`);
            assert.strictEqual(alertDiv.hidden, false);
            assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
            assert.strictEqual(CommunityPostCard.isDeleting(), false);

            CommunityPostCard.closeDeleteModal(mockDoc);
            card.remove();
        }
    });

    test('14. Server 401 response displays session expired message and keeps card in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 401,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({ message: 'Unauthorized' })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('15. Server 403 response displays unauthorized message and keeps card in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 403,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({ message: 'Forbidden' })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Bạn không có quyền xóa bài viết này.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('16. Server 404 response displays not found / already deleted message and keeps card in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 404,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({ message: 'Not found' })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Bài viết không tồn tại hoặc đã bị xóa.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('17. Redirect to /login shows session expired message and keeps card in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: true,
            url: 'http://localhost:8080/login',
            headers: { get: () => 'text/html' }
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('18. Redirect to /access-denied shows access denied / expired message and keeps card in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: true,
            url: 'http://localhost:8080/access-denied',
            headers: { get: () => 'text/html' }
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('19. Error response with JSON message displays server error message', async () => {
        global.fetch = () => Promise.resolve({
            status: 400,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({ message: 'Custom server rejection reason.' })
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Custom server rejection reason.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('20. Error response with malformed JSON does not throw SyntaxError and displays fallback message', async () => {
        global.fetch = () => Promise.resolve({
            status: 500,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.reject(new SyntaxError('Unexpected token < in JSON at position 0'))
        });

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
    });

    test('21. Network error (fetch rejects) displays connection error and re-enables buttons', async () => {
        global.fetch = () => Promise.reject(new Error('Network connection failed'));

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const alertDiv = modal.querySelector('#communityDeleteModalAlert');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.ok(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), 'Card must remain in DOM');
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(alertDiv.hidden, false);
        assert.strictEqual(alertDiv.textContent, 'Lỗi kết nối máy chủ. Vui lòng thử lại.');
        assert.strictEqual(CommunityPostCard.isDeleting(), false);
        assert.strictEqual(confirmBtn.disabled, false, 'Confirm button must be re-enabled');
    });

    test('22. In-flight double-click guard prevents concurrent delete requests', async () => {
        let fetchCallCount = 0;
        let resolveFetch;
        const fetchPromise = new Promise(resolve => { resolveFetch = resolve; });

        global.fetch = () => {
            fetchCallCount++;
            return fetchPromise;
        };

        const card = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Post A' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        CommunityPostCard.openDeleteModal(POST_A_ID, card, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        // First click
        const firstSubmit = CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });
        assert.strictEqual(fetchCallCount, 1, 'First click initiates fetch');
        assert.strictEqual(CommunityPostCard.isDeleting(), true, 'isDeleting state is active');
        assert.strictEqual(confirmBtn.disabled, true, 'Confirm button is disabled during in-flight');

        // Second click while in-flight
        const secondSubmit = CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });
        assert.strictEqual(fetchCallCount, 1, 'Second click must be ignored by in-flight guard');

        // Close/Cancel attempt while in-flight
        const cancelResult = CommunityPostCard.closeDeleteModal(mockDoc);
        assert.strictEqual(cancelResult, false, 'closeDeleteModal must reject while deleting');
        assert.strictEqual(modal.hidden, false, 'Modal must remain open during in-flight deletion');

        // Resolve fetch
        resolveFetch({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' }
        });
        await firstSubmit;
        await secondSubmit;

        assert.strictEqual(fetchCallCount, 1, 'Total fetch calls must remain exactly 1');
        assert.strictEqual(mockDoc.querySelector('[data-post-id="' + POST_A_ID + '"]'), null, 'Card removed after resolution');
        assert.strictEqual(modal.hidden, true, 'Modal closed after resolution');
    });

    test('23. Strict 204 removes all matching cards if duplicate card elements exist in DOM', async () => {
        global.fetch = () => Promise.resolve({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' }
        });

        const card1 = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Card 1' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        const card2 = CommunityPostCard.create({ id: POST_A_ID, authorUserId: OWNER_ID, caption: 'Card 2 duplicate' }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card1);
        mockDoc.body.appendChild(card2);

        CommunityPostCard.openDeleteModal(POST_A_ID, card1, mockDoc);
        const modal = mockDoc.getElementById('communityDeletePostModal');
        const confirmBtn = modal.querySelector('[data-action="confirm-delete"]');

        await CommunityPostCard.handleDeleteSubmit({ preventDefault: () => {}, target: confirmBtn });

        assert.strictEqual(mockDoc.querySelectorAll('.community-post-card[data-post-id="' + POST_A_ID + '"]').length, 0, 'All matching cards must be removed');
    });
});

describe('CommunityPostCard Revision History UX Test Matrix (MS-07B8.3.3)', () => {

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
        CommunityPostCard.closeDeleteModal(mockDoc);
        CommunityPostCard.closeRevisionModal(mockDoc);
    });

    test('1. contentVersion 0 -> no indicator', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Original post caption',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 0
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        const indicator = card.querySelector('.post-edited-indicator');
        assert.strictEqual(indicator, null, 'Unedited post with contentVersion 0 must NOT have edited indicator');
    });

    test('2. contentVersion > 0 -> indicator exists', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Edited post caption',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 1
        };
        const card = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        const indicator = card.querySelector('.post-edited-indicator');
        assert.notStrictEqual(indicator, null, 'Edited post with contentVersion 1 must have edited indicator');
        assert.strictEqual(indicator.tagName, 'BUTTON');
        assert.strictEqual(indicator.getAttribute('data-action'), 'view-revisions');
        assert.strictEqual(indicator.getAttribute('data-post-id'), POST_A_ID);
        assert.strictEqual(indicator.getAttribute('title'), 'Xem lịch sử chỉnh sửa');
        assert.strictEqual(indicator.textContent, 'Đã chỉnh sửa', 'Visible text must be exactly "Đã chỉnh sửa"');
    });

    test('3. indicator public regardless owner status', () => {
        const item = {
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Public edited post',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 2
        };
        // Guest viewer
        const guestCard = CommunityPostCard.create(item, { isAuthenticated: false, currentUserId: null }, mockDoc);
        const guestIndicator = guestCard.querySelector('.post-edited-indicator');
        assert.notStrictEqual(guestIndicator, null, 'Guest viewer must see edited indicator');
        assert.strictEqual(guestIndicator.textContent, 'Đã chỉnh sửa', 'Guest indicator text must be exactly "Đã chỉnh sửa"');

        // Other authenticated user viewer (non-owner)
        const otherUserCard = CommunityPostCard.create(item, { isAuthenticated: true, currentUserId: OTHER_USER_ID }, mockDoc);
        const otherIndicator = otherUserCard.querySelector('.post-edited-indicator');
        assert.notStrictEqual(otherIndicator, null, 'Non-owner authenticated user must see edited indicator');
        assert.strictEqual(otherIndicator.textContent, 'Đã chỉnh sửa', 'Other user indicator text must be exactly "Đã chỉnh sửa"');
    });

    test('4. edit success contentVersion 1 -> indicator added', async () => {
        global.fetch = () => Promise.resolve({
            ok: true,
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({
                id: POST_A_ID,
                caption: 'Updated caption after edit',
                contentVersion: 1
            })
        });

        const card = CommunityPostCard.create({
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Original unedited caption',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 0
        }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        assert.strictEqual(card.querySelector('.post-edited-indicator'), null, 'Initially no indicator');

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const editModal = mockDoc.getElementById('communityEditPostModal');
        const submitBtn = editModal.querySelector('[data-action="save-edit"]');
        const textarea = editModal.querySelector('#communityEditCaptionInput');
        textarea.value = 'Updated caption after edit';

        await CommunityPostCard.handleEditSubmit({ preventDefault: () => {}, target: submitBtn });

        const indicator = card.querySelector('.post-edited-indicator');
        assert.notStrictEqual(indicator, null, 'Indicator must be dynamically appended on edit success');
        assert.strictEqual(indicator.getAttribute('data-action'), 'view-revisions');
        assert.strictEqual(indicator.getAttribute('data-post-id'), POST_A_ID);
        assert.strictEqual(indicator.textContent, 'Đã chỉnh sửa', 'Dynamically appended indicator text must be exactly "Đã chỉnh sửa"');
        assert.strictEqual(card.querySelector('.post-caption').textContent, 'Updated caption after edit');
    });

    test('5. second edit -> no duplicate indicator', async () => {
        global.fetch = () => Promise.resolve({
            ok: true,
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            json: () => Promise.resolve({
                id: POST_A_ID,
                caption: 'Second edit caption',
                contentVersion: 2
            })
        });

        const card = CommunityPostCard.create({
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'First edit caption',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 1
        }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        assert.strictEqual(card.querySelectorAll('.post-edited-indicator').length, 1, 'Initially 1 indicator');

        CommunityPostCard.openEditModal(POST_A_ID, card, mockDoc);
        const editModal = mockDoc.getElementById('communityEditPostModal');
        const submitBtn = editModal.querySelector('[data-action="save-edit"]');
        const textarea = editModal.querySelector('#communityEditCaptionInput');
        textarea.value = 'Second edit caption';

        await CommunityPostCard.handleEditSubmit({ preventDefault: () => {}, target: submitBtn });

        assert.strictEqual(card.querySelectorAll('.post-edited-indicator').length, 1, 'Must never duplicate indicator on later edits');
        assert.strictEqual(card.querySelector('.post-caption').textContent, 'Second edit caption');
    });

    test('6. click indicator -> singleton modal opens', () => {
        global.fetch = () => new Promise(() => {}); // Pending fetch

        const card = CommunityPostCard.create({
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Edited post',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 1
        }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        const indicator = card.querySelector('.post-edited-indicator');
        indicator.dispatchEvent('click');

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        assert.notStrictEqual(modal, null, 'Singleton revision modal must exist in DOM');
        assert.strictEqual(modal.hidden, false, 'Modal must be visible');
        assert.strictEqual(modal.querySelector('#communityRevisionModalTitle').textContent, 'Lịch sử chỉnh sửa');
        assert.strictEqual(modal.querySelector('#communityRevisionModalSpinner').hidden, false, 'Spinner must be visible while loading');
    });

    test('7. GET correct revisions endpoint', () => {
        let requestedUrl = null;
        let requestedOptions = null;
        global.fetch = (url, options) => {
            requestedUrl = url;
            requestedOptions = options;
            return new Promise(() => {});
        };

        const card = CommunityPostCard.create({
            id: POST_A_ID,
            authorUserId: OWNER_ID,
            caption: 'Edited post',
            createdAt: '2026-09-30T10:00:00Z',
            contentVersion: 1
        }, { isAuthenticated: true, currentUserId: OWNER_ID }, mockDoc);
        mockDoc.body.appendChild(card);

        const indicator = card.querySelector('.post-edited-indicator');
        CommunityPostCard.openRevisionModal(POST_A_ID, indicator, mockDoc);

        assert.strictEqual(requestedUrl, '/api/community/posts/' + POST_A_ID + '/revisions');
        assert.strictEqual(requestedOptions.method, 'GET');
        assert.strictEqual(requestedOptions.headers['Accept'], 'application/json');
    });

    test('8. revisions render in server-provided DESC order', async () => {
        const mockRevisions = [
            {
                revisionNumber: 2,
                editorUserId: OWNER_ID,
                previousCaption: 'Caption version 1',
                caption: 'Caption version 2',
                editedAt: '2026-10-01T15:00:00Z'
            },
            {
                revisionNumber: 1,
                editorUserId: OWNER_ID,
                previousCaption: 'Original caption v0',
                caption: 'Caption version 1',
                editedAt: '2026-10-01T10:00:00Z'
            }
        ];

        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify(mockRevisions))
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const items = modal.querySelectorAll('.community-history-item');
        assert.strictEqual(items.length, 2, 'Must render 2 revision history items');

        // First item = Revision 2 (DESC)
        const badge0 = items[0].querySelector('.community-history-version-badge');
        assert.strictEqual(badge0.textContent, 'Phiên bản #2');
        assert.strictEqual(items[0].querySelectorAll('.community-history-caption-box')[0].textContent, 'Caption version 1');
        assert.strictEqual(items[0].querySelectorAll('.community-history-caption-box')[1].textContent, 'Caption version 2');

        // Second item = Revision 1
        const badge1 = items[1].querySelector('.community-history-version-badge');
        assert.strictEqual(badge1.textContent, 'Phiên bản #1');
        assert.strictEqual(items[1].querySelectorAll('.community-history-caption-box')[0].textContent, 'Original caption v0');
        assert.strictEqual(items[1].querySelectorAll('.community-history-caption-box')[1].textContent, 'Caption version 1');
    });

    test('9. previousCaption/caption rendered via textContent semantics', async () => {
        const xssCaption = '<script>alert("hack")</script><img src=x onerror=alert(1)>';
        const xssPrevious = '<b onmouseover=evil()>bold text</b>';

        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                {
                    revisionNumber: 1,
                    previousCaption: xssPrevious,
                    caption: xssCaption,
                    editedAt: '2026-10-01T10:00:00Z'
                }
            ]))
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const boxes = modal.querySelectorAll('.community-history-caption-box');
        assert.strictEqual(boxes.length, 2);
        assert.strictEqual(boxes[0].textContent, xssPrevious, 'Previous caption textContent preserves exact string safely');
        assert.strictEqual(boxes[1].textContent, xssCaption, 'Current caption textContent preserves exact string safely');
    });

    test('10. 200 [] success renders empty history message without alert', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('[]')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, true, 'Alert must be hidden on empty array success');
        const listContainer = modal.querySelector('#communityRevisionList');
        assert.strictEqual(listContainer.textContent.includes('Chưa có lịch sử chỉnh sửa.'), true);
    });

    test('11. 200 malformed JSON -> invalid response (not network error)', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('{malformed-json')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('12. 200 JSON object -> invalid response (not empty history)', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify({ revisions: [] }))
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('13. 201 JSON array -> invalid response', async () => {
        global.fetch = () => Promise.resolve({
            status: 201,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('[]')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('14. 204 No Content -> invalid response', async () => {
        global.fetch = () => Promise.resolve({
            status: 204,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('15. 401 Unauthorized -> auth/session message', async () => {
        global.fetch = () => Promise.resolve({
            status: 401,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('{"message":"Unauthorized"}')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    });

    test('16. 403 Forbidden -> access message', async () => {
        global.fetch = () => Promise.resolve({
            status: 403,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('{"message":"Forbidden"}')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Bạn không có quyền xem lịch sử chỉnh sửa của bài viết này.');
    });

    test('17. 404 Not Found -> "Bài viết không còn tồn tại hoặc đã bị xóa."', async () => {
        global.fetch = () => Promise.resolve({
            status: 404,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify({ message: 'Post not found' }))
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Bài viết không còn tồn tại hoặc đã bị xóa.');
        assert.strictEqual(modal.querySelector('#communityRevisionModalSpinner').hidden, true, 'Spinner hidden on 404');
    });

    test('18. redirected /login -> auth/session message', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: true,
            url: 'https://kiemlai.vn/login?returnTo=/community',
            headers: { get: () => 'text/html' },
            text: () => Promise.resolve('<html>Login</html>')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    });

    test('19. redirected /access-denied -> access/session message', async () => {
        global.fetch = () => Promise.resolve({
            status: 200,
            redirected: true,
            url: 'https://kiemlai.vn/access-denied',
            headers: { get: () => 'text/html' },
            text: () => Promise.resolve('<html>Access Denied</html>')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
    });

    test('20. 500 Server Error -> server/history-load error', async () => {
        global.fetch = () => Promise.resolve({
            status: 500,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve('{"message":"Internal Server Error"}')
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại sau.');
    });

    test('21. actual fetch rejection -> network message', async () => {
        global.fetch = () => Promise.reject(new TypeError('Failed to fetch'));

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, false, 'Alert must be visible');
        assert.strictEqual(alertDiv.textContent, 'Không thể kết nối đến máy chủ. Vui lòng kiểm tra mạng.');
        assert.strictEqual(modal.querySelector('#communityRevisionModalSpinner').hidden, true, 'Spinner hidden on network failure');
    });

    test('22. close while request pending -> late response discarded', async () => {
        let resolveFetch;
        global.fetch = () => new Promise(resolve => {
            resolveFetch = resolve;
        });

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        assert.strictEqual(modal.hidden, false);

        // User closes modal before response arrives
        CommunityPostCard.closeRevisionModal(mockDoc);
        assert.strictEqual(modal.hidden, true);

        // Late response arrives
        resolveFetch({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 1, caption: 'Late Rev', previousCaption: 'Orig', editedAt: '2026-10-01T10:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        assert.strictEqual(modal.hidden, true, 'Modal remains closed');
        assert.strictEqual(modal.querySelectorAll('.community-history-item').length, 0, 'Late revisions must be discarded');
    });

    test('23. A pending -> B opened -> A late response cannot overwrite B', async () => {
        let resolveFetchA;
        let resolveFetchB;

        global.fetch = (url) => {
            if (url.includes(POST_A_ID)) {
                return new Promise(resolve => { resolveFetchA = resolve; });
            }
            if (url.includes(POST_B_ID)) {
                return new Promise(resolve => { resolveFetchB = resolve; });
            }
            return Promise.reject(new Error('Unknown url'));
        };

        // 1. Open modal for Post A
        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);

        // 2. Open modal for Post B before A finishes
        CommunityPostCard.openRevisionModal(POST_B_ID, null, mockDoc);

        // 3. Post B resolves first
        resolveFetchB({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 1, caption: 'Post B Revision', previousCaption: 'Post B Orig', editedAt: '2026-10-01T12:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        let boxes = modal.querySelectorAll('.community-history-caption-box');
        assert.strictEqual(boxes[1].textContent, 'Post B Revision', 'Modal renders revisions for Post B');

        // 4. Stale Post A response arrives later
        resolveFetchA({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 1, caption: 'Post A Revision', previousCaption: 'Post A Orig', editedAt: '2026-10-01T10:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        boxes = modal.querySelectorAll('.community-history-caption-box');
        assert.strictEqual(boxes[1].textContent, 'Post B Revision', 'Stale response for Post A must NOT overwrite Post B revisions');
    });

    test('24. close/reopen same post -> old response discarded', async () => {
        let resolveFetchSession1;
        let resolveFetchSession2;
        let callCount = 0;
        global.fetch = () => {
            callCount++;
            if (callCount === 1) {
                return new Promise(r => { resolveFetchSession1 = r; });
            }
            return new Promise(r => { resolveFetchSession2 = r; });
        };

        // Open session 1
        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        // Close session 1
        CommunityPostCard.closeRevisionModal(mockDoc);
        // Open session 2 for same post
        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);

        // Session 2 resolves
        resolveFetchSession2({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 2, caption: 'Session 2 Cap', previousCaption: 'Orig', editedAt: '2026-10-01T12:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        let boxes = modal.querySelectorAll('.community-history-caption-box');
        assert.strictEqual(boxes[1].textContent, 'Session 2 Cap');

        // Session 1 arrives late
        resolveFetchSession1({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 1, caption: 'Session 1 Stale Cap', previousCaption: 'Orig', editedAt: '2026-10-01T10:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        boxes = modal.querySelectorAll('.community-history-caption-box');
        assert.strictEqual(boxes[1].textContent, 'Session 2 Cap', 'Late session 1 response must not overwrite session 2');
    });

    test('25. stale failure responses cannot overwrite current modal state', async () => {
        let rejectFetchA;
        let resolveFetchB;
        global.fetch = (url) => {
            if (url.includes(POST_A_ID)) {
                return new Promise((_, reject) => { rejectFetchA = reject; });
            }
            if (url.includes(POST_B_ID)) {
                return new Promise(resolve => { resolveFetchB = resolve; });
            }
            return Promise.reject(new Error('Unknown url'));
        };

        // 1. Open modal for Post A (pending)
        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        // 2. Open modal for Post B before A finishes
        CommunityPostCard.openRevisionModal(POST_B_ID, null, mockDoc);

        // 3. Post B resolves successfully
        resolveFetchB({
            status: 200,
            redirected: false,
            headers: { get: () => 'application/json' },
            text: () => Promise.resolve(JSON.stringify([
                { revisionNumber: 1, caption: 'Post B Revision', previousCaption: 'Post B Orig', editedAt: '2026-10-01T12:00:00Z' }
            ]))
        });
        await new Promise(resolve => setImmediate(resolve));

        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        const alertDiv = modal.querySelector('#communityRevisionModalAlert');
        assert.strictEqual(alertDiv.hidden, true, 'Alert is hidden for successful Post B');

        // 4. Stale Post A rejects / fails late with network error
        rejectFetchA(new Error('Network error'));
        await new Promise(resolve => setImmediate(resolve));

        assert.strictEqual(alertDiv.hidden, true, 'Stale failure for Post A must NOT overwrite Post B state with an error');
    });

    test('26. ESC closes', () => {
        global.fetch = () => new Promise(() => {});

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        assert.strictEqual(modal.hidden, false);

        mockDoc.dispatchEvent({ type: 'keydown', key: 'Escape' });
        assert.strictEqual(modal.hidden, true, 'Escape keydown must close revision modal');
    });

    test('27. backdrop closes', () => {
        global.fetch = () => new Promise(() => {});

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        assert.strictEqual(modal.hidden, false);

        const backdrop = modal.querySelector('.kl-modal-backdrop');
        backdrop.dispatchEvent('click');
        assert.strictEqual(modal.hidden, true, 'Backdrop click must close revision modal');
    });

    test('28. close button closes', () => {
        global.fetch = () => new Promise(() => {});

        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        const modal = mockDoc.getElementById('communityRevisionHistoryModal');
        assert.strictEqual(modal.hidden, false);

        const closeBtn = modal.querySelector('#communityRevisionCloseBtn');
        closeBtn.dispatchEvent('click');
        assert.strictEqual(modal.hidden, true, 'Close button click must close revision modal');

        // Also verify footer close button
        CommunityPostCard.openRevisionModal(POST_A_ID, null, mockDoc);
        assert.strictEqual(modal.hidden, false);
        const footerCloseBtn = modal.querySelector('#communityRevisionFooterCloseBtn');
        footerCloseBtn.dispatchEvent('click');
        assert.strictEqual(modal.hidden, true, 'Footer close button click must close revision modal');
    });

    test('29. focus restored to trigger', () => {
        global.fetch = () => new Promise(() => {});

        let focusCalled = false;
        const fakeTrigger = mockDoc.createElement('button');
        fakeTrigger.focus = () => { focusCalled = true; };

        CommunityPostCard.openRevisionModal(POST_A_ID, fakeTrigger, mockDoc);
        assert.strictEqual(focusCalled, false);

        CommunityPostCard.closeRevisionModal(mockDoc);
        assert.strictEqual(focusCalled, true, 'Closing revision modal must restore focus to trigger button');
    });
});

describe('CommunityPostCard.classifyRevisionResponse Pure Classifier Unit Tests', () => {
    const classify = CommunityPostCard.classifyRevisionResponse;

    test('1. null response -> invalid response error', () => {
        const result = classify(null, '');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('2. redirected /login -> auth/session message', () => {
        const result = classify({
            redirected: true,
            url: 'https://kiemlai.vn/login?returnTo=/community',
            status: 200
        }, '<html>login</html>');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    });

    test('3. redirected /access-denied -> access/session message', () => {
        const result = classify({
            redirected: true,
            url: 'https://kiemlai.vn/access-denied',
            status: 200
        }, '<html>denied</html>');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
    });

    test('4. HTTP 401 -> auth/session message', () => {
        const result = classify({
            status: 401,
            headers: { get: () => 'application/json' }
        }, '{"message":"Unauthorized"}');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    });

    test('5. HTTP 403 -> access message', () => {
        const result = classify({
            status: 403,
            headers: { get: () => 'application/json' }
        }, '{"message":"Forbidden"}');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Bạn không có quyền xem lịch sử chỉnh sửa của bài viết này.');
    });

    test('6. HTTP 404 -> "Bài viết không còn tồn tại hoặc đã bị xóa."', () => {
        const result = classify({
            status: 404,
            headers: { get: () => 'application/json' }
        }, '{"message":"Not found"}');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Bài viết không còn tồn tại hoặc đã bị xóa.');
    });

    test('7. HTTP 500 / 503 -> "Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại sau."', () => {
        const res500 = classify({ status: 500, headers: { get: () => 'application/json' } }, '');
        assert.strictEqual(res500.success, false);
        assert.strictEqual(res500.message, 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại sau.');

        const res503 = classify({ status: 503, headers: { get: () => 'application/json' } }, '');
        assert.strictEqual(res503.success, false);
        assert.strictEqual(res503.message, 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại sau.');
    });

    test('8. Non-200 2xx (201, 202, 204) -> invalid response message', () => {
        const res201 = classify({ status: 201, headers: { get: () => 'application/json' } }, '[]');
        assert.strictEqual(res201.success, false);
        assert.strictEqual(res201.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');

        const res204 = classify({ status: 204, headers: { get: () => 'application/json' } }, '');
        assert.strictEqual(res204.success, false);
        assert.strictEqual(res204.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('9. HTTP 200 non-JSON content-type -> invalid response message', () => {
        const result = classify({
            status: 200,
            headers: { get: () => 'text/html' }
        }, '<html>some html</html>');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('10. HTTP 200 malformed JSON -> invalid response message', () => {
        const result = classify({
            status: 200,
            headers: { get: () => 'application/json' }
        }, '{not-json');
        assert.strictEqual(result.success, false);
        assert.strictEqual(result.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('11. HTTP 200 JSON object / non-array -> invalid response message', () => {
        const resObj = classify({
            status: 200,
            headers: { get: () => 'application/json' }
        }, '{"revisions":[]}');
        assert.strictEqual(resObj.success, false);
        assert.strictEqual(resObj.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');

        const resNum = classify({
            status: 200,
            headers: { get: () => 'application/json' }
        }, '123');
        assert.strictEqual(resNum.success, false);
        assert.strictEqual(resNum.message, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
    });

    test('12. HTTP 200 valid JSON array [] -> success with empty array', () => {
        const result = classify({
            status: 200,
            headers: { get: () => 'application/json' }
        }, '[]');
        assert.strictEqual(result.success, true);
        assert.deepStrictEqual(result.data, []);
    });

    test('13. HTTP 200 valid JSON array with items -> success with items', () => {
        const items = [{ revisionNumber: 1, caption: 'test' }];
        const result = classify({
            status: 200,
            headers: { get: () => 'application/json' }
        }, JSON.stringify(items));
        assert.strictEqual(result.success, true);
        assert.deepStrictEqual(result.data, items);
    });
});
