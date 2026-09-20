const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommentPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
// ============================================================================

class FakeClassList {
    constructor(element) {
        Object.defineProperty(this, 'element', { value: element, writable: true, configurable: true, enumerable: false });
        this.classes = new Set();
    }

    add(...names) {
        for (const name of names) {
            if (name) {
                const parts = name.split(/\s+/);
                for (const p of parts) {
                    if (p) this.classes.add(p);
                }
            }
        }
        this._sync();
    }

    remove(...names) {
        for (const name of names) {
            if (name) {
                const parts = name.split(/\s+/);
                for (const p of parts) {
                    if (p) this.classes.delete(p);
                }
            }
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
        Object.defineProperty(this, 'parentNode', { value: null, writable: true, configurable: true, enumerable: false });
        Object.defineProperty(this, 'parentElement', { value: null, writable: true, configurable: true, enumerable: false });
        Object.defineProperty(this, 'ownerDocument', { value: null, writable: true, configurable: true, enumerable: false });
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this._textContent = '';
        this.isFocused = false;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    focus() {
        this.isFocused = true;
    }

    blur() {
        this.isFocused = false;
    }

    get className() {
        return this.attributes['class'] || '';
    }

    set className(val) {
        this.attributes['class'] = val || '';
        this.classList.classes.clear();
        if (val) {
            const parts = val.trim().split(/\s+/);
            for (const p of parts) {
                if (p) this.classList.classes.add(p);
            }
        }
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this.childNodes = [];
        this._textContent = String(val == null ? '' : val);
    }

    get children() {
        return this.childNodes.filter(n => n.nodeType === 1);
    }

    get firstChild() {
        return this.childNodes[0] || null;
    }

    get lastChild() {
        return this.childNodes[this.childNodes.length - 1] || null;
    }

    get nodeType() {
        return 1;
    }

    setAttribute(k, v) {
        this.attributes[k] = String(v);
        if (k === 'class') {
            this.className = String(v);
        }
        if (k === 'hidden') {
            this.hidden = true;
        }
        if (k === 'disabled') {
            this.disabled = true;
        }
    }

    getAttribute(k) {
        return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null;
    }

    hasAttribute(k) {
        return Object.prototype.hasOwnProperty.call(this.attributes, k);
    }

    removeAttribute(k) {
        delete this.attributes[k];
        if (k === 'class') {
            this.classList.classes.clear();
        }
        if (k === 'hidden') {
            this.hidden = false;
        }
        if (k === 'disabled') {
            this.disabled = false;
        }
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        if (this.ownerDocument) {
            child.ownerDocument = this.ownerDocument;
        }
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

    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx !== -1) {
            this.childNodes[idx] = newChild;
            newChild.parentNode = this;
            newChild.parentElement = this;
            if (this.ownerDocument) {
                newChild.ownerDocument = this.ownerDocument;
            }
            oldChild.parentNode = null;
            oldChild.parentElement = null;
            return oldChild;
        }
        return null;
    }

    querySelector(selector) {
        const matchFn = createMatcher(selector);
        return this._findFirst(matchFn);
    }

    querySelectorAll(selector) {
        const matchFn = createMatcher(selector);
        const results = [];
        this._findAll(matchFn, results);
        return results;
    }

    _findFirst(matchFn) {
        for (const child of this.childNodes) {
            if (child.nodeType === 1) {
                if (matchFn(child)) return child;
                const found = child._findFirst(matchFn);
                if (found) return found;
            }
        }
        return null;
    }

    _findAll(matchFn, results) {
        for (const child of this.childNodes) {
            if (child.nodeType === 1) {
                if (matchFn(child)) results.push(child);
                child._findAll(matchFn, results);
            }
        }
    }

    closest(selector) {
        const matchFn = createMatcher(selector);
        let cur = this;
        while (cur) {
            if (matchFn(cur)) return cur;
            cur = cur.parentElement || cur.parentNode;
        }
        return null;
    }

    contains(other) {
        let cur = other;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(f => f !== fn);
    }

    dispatchEvent(event) {
        if (!event.target) event.target = this;
        event.currentTarget = this;
        if (event.defaultPrevented === undefined) {
            event.defaultPrevented = false;
        }
        const origPrevent = event.preventDefault;
        event.preventDefault = function () {
            event.defaultPrevented = true;
            if (typeof origPrevent === 'function') {
                origPrevent.call(this);
            }
        };

        let stopped = false;
        const origStop = event.stopPropagation;
        event.stopPropagation = function () {
            stopped = true;
            if (typeof origStop === 'function') {
                origStop.call(this);
            }
        };

        const list = this.listeners[event.type] || [];
        for (const fn of [...list]) {
            fn.call(this, event);
            if (stopped) break;
        }

        if (!stopped) {
            let cur = this.parentElement || this.parentNode;
            while (cur && !stopped) {
                event.currentTarget = cur;
                const curList = cur.listeners[event.type] || [];
                for (const fn of [...curList]) {
                    fn.call(cur, event);
                    if (stopped) break;
                }
                cur = cur.parentElement || cur.parentNode;
            }
            if (!stopped && this.ownerDocument) {
                event.currentTarget = this.ownerDocument;
                const docList = this.ownerDocument.listeners[event.type] || [];
                for (const fn of [...docList]) {
                    fn.call(this.ownerDocument, event);
                    if (stopped) break;
                }
            }
        }
        return !event.defaultPrevented;
    }

    click() {
        const event = {
            type: 'click',
            target: this,
            currentTarget: this,
            defaultPrevented: false,
            preventDefault() { this.defaultPrevented = true; },
            stopPropagation() {}
        };
        this.dispatchEvent(event);
    }
}

class FakeTextNode {
    constructor(text) {
        this.text = String(text);
        this.parentNode = null;
        this.parentElement = null;
    }
    get textContent() { return this.text; }
    set textContent(val) { this.text = String(val); }
    get nodeType() { return 3; }
    contains() { return false; }
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.listeners = {};
    }

    createElement(tag) {
        const el = new FakeElement(tag);
        el.ownerDocument = this;
        return el;
    }

    createTextNode(text) {
        return new FakeTextNode(text);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(f => f !== fn);
    }

    dispatchEvent(event) {
        const list = this.listeners[event.type] || [];
        for (const fn of list) {
            fn.call(this, event);
        }
        return !event.defaultPrevented;
    }

    querySelector(selector) {
        return this.body.querySelector(selector);
    }

    querySelectorAll(selector) {
        return this.body.querySelectorAll(selector);
    }
}

function createMatcher(selector) {
    if (selector.startsWith('.')) {
        const className = selector.slice(1);
        return el => el.classList && el.classList.contains(className);
    }
    if (selector.startsWith('[')) {
        const inner = selector.slice(1, -1);
        if (inner.includes('=')) {
            const [attr, val] = inner.split('=');
            const cleanVal = val.replace(/["']/g, '');
            return el => el.getAttribute(attr) === cleanVal;
        }
        return el => el.hasAttribute(inner);
    }
    const tag = selector.toUpperCase();
    return el => el.tagName === tag;
}

// ============================================================================
// Test Suite: CommentPresentation (Normalized Action Descriptors Contract)
// ============================================================================

describe('CommentPresentation Module', () => {
    let doc;

    beforeEach(() => {
        doc = new FakeDocument();
    });

    afterEach(() => {
        CommentPresentation.unbindDocument(doc);
    });

    // A: Module exports / UMD wrapper
    test('A. module exports authoritative presentation API and UMD wrapper', () => {
        assert.ok(CommentPresentation);
        assert.strictEqual(typeof CommentPresentation.sanitizeAvatarUrl, 'function');
        assert.strictEqual(typeof CommentPresentation.createAvatarFallback, 'function');
        assert.strictEqual(typeof CommentPresentation.renderAvatar, 'function');
        assert.strictEqual(typeof CommentPresentation.formatTimestamp, 'function');
        assert.strictEqual(typeof CommentPresentation.isCommentEdited, 'function');
        assert.strictEqual(typeof CommentPresentation.renderActionsMenu, 'function');
        assert.strictEqual(typeof CommentPresentation.renderComment, 'function');
        assert.strictEqual(typeof CommentPresentation.openMenu, 'function');
        assert.strictEqual(typeof CommentPresentation.closeActiveMenu, 'function');
        assert.strictEqual(typeof CommentPresentation.bindDocument, 'function');
        assert.strictEqual(typeof CommentPresentation.unbindDocument, 'function');
    });

    // B: sanitizeAvatarUrl
    test('B. sanitizeAvatarUrl: validates safe URLs and disallows dangerous or protocol-relative schemes', () => {
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('https://example.com/avatar.jpg'), 'https://example.com/avatar.jpg');
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('http://example.com/avatar.jpg'), 'http://example.com/avatar.jpg');
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('/images/avatar.jpg'), '/images/avatar.jpg');
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('   /images/avatar.png   '), '/images/avatar.png');

        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('//evil.com/avatar.jpg'), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('javascript:alert(1)'), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('data:image/png;base64,123'), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('vbscript:msgbox'), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl(''), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl('   '), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl(null), null);
        assert.strictEqual(CommentPresentation.sanitizeAvatarUrl(123), null);
    });

    // C: createAvatarFallback
    test('C. createAvatarFallback: renders span with first initial capitalized and aria-hidden', () => {
        const fallback = CommentPresentation.createAvatarFallback('nguyễn văn a', doc);
        assert.ok(fallback);
        assert.strictEqual(fallback.tagName, 'SPAN');
        assert.ok(fallback.classList.contains('kl-comment__avatar'));
        assert.ok(fallback.classList.contains('kl-comment__avatar--fallback'));
        assert.strictEqual(fallback.getAttribute('aria-hidden'), 'true');
        assert.strictEqual(fallback.textContent, 'N');

        const fallbackDefault = CommentPresentation.createAvatarFallback('', doc);
        assert.strictEqual(fallbackDefault.textContent, 'U');

        const legacyFallback = CommentPresentation.createAvatarFallback('Bob', doc, { legacyPrefix: 'novel-comment' });
        assert.ok(legacyFallback.classList.contains('novel-comment-avatar'));
        assert.ok(legacyFallback.classList.contains('novel-comment-avatar--fallback'));
        assert.strictEqual(legacyFallback.textContent, 'B');
    });

    // D: renderAvatar with valid URL
    test('D. renderAvatar: renders img with referrerpolicy and alt when URL is valid', () => {
        const author = { displayName: 'Alice', avatarUrl: 'https://cdn.example.com/alice.jpg' };
        const el = CommentPresentation.renderAvatar(author, doc);
        assert.strictEqual(el.tagName, 'IMG');
        assert.ok(el.classList.contains('kl-comment__avatar'));
        assert.strictEqual(el.getAttribute('src'), 'https://cdn.example.com/alice.jpg');
        assert.strictEqual(el.getAttribute('alt'), 'Alice');
        assert.strictEqual(el.getAttribute('referrerpolicy'), 'no-referrer');
    });

    // E: renderAvatar with invalid or missing URL
    test('E. renderAvatar: falls back to initial span if avatar URL is missing or invalid', () => {
        const authorNull = { displayName: 'Charlie', avatarUrl: null };
        const elNull = CommentPresentation.renderAvatar(authorNull, doc);
        assert.strictEqual(elNull.tagName, 'SPAN');
        assert.ok(elNull.classList.contains('kl-comment__avatar--fallback'));
        assert.strictEqual(elNull.textContent, 'C');

        const authorBad = { displayName: 'David', avatarUrl: 'javascript:void(0)' };
        const elBad = CommentPresentation.renderAvatar(authorBad, doc);
        assert.strictEqual(elBad.tagName, 'SPAN');
        assert.strictEqual(elBad.textContent, 'D');
    });

    // F: renderAvatar onerror fallback
    test('F. renderAvatar: replaces image with fallback element on image onerror', () => {
        const author = { displayName: 'Eve', avatarUrl: 'https://cdn.example.com/eve.jpg' };
        const container = doc.createElement('div');
        const img = CommentPresentation.renderAvatar(author, doc);
        container.appendChild(img);

        assert.strictEqual(container.childNodes.length, 1);
        assert.strictEqual(container.childNodes[0].tagName, 'IMG');

        // Trigger onerror
        img.onerror();

        assert.strictEqual(container.childNodes.length, 1);
        assert.strictEqual(container.childNodes[0].tagName, 'SPAN');
        assert.ok(container.childNodes[0].classList.contains('kl-comment__avatar--fallback'));
        assert.strictEqual(container.childNodes[0].textContent, 'E');
    });

    // G: formatTimestamp
    test('G. formatTimestamp: formats ISO string correctly and returns empty string for invalid dates', () => {
        const formatted = CommentPresentation.formatTimestamp('2026-09-20T10:30:00Z');
        assert.ok(formatted.includes('/2026'));
        assert.ok(formatted.includes(':'));

        assert.strictEqual(CommentPresentation.formatTimestamp(''), '');
        assert.strictEqual(CommentPresentation.formatTimestamp(null), '');
        assert.strictEqual(CommentPresentation.formatTimestamp('invalid-date-string'), '');
    });

    // H: isCommentEdited
    test('H. isCommentEdited: detects edited state via boolean or timestamp comparison', () => {
        assert.strictEqual(CommentPresentation.isCommentEdited({ edited: true }), true);
        assert.strictEqual(CommentPresentation.isCommentEdited({
            createdAt: '2026-09-20T10:00:00Z',
            updatedAt: '2026-09-20T10:05:00Z'
        }), true);
        assert.strictEqual(CommentPresentation.isCommentEdited({
            createdAt: '2026-09-20T10:00:00Z',
            updatedAt: '2026-09-20T10:00:00Z'
        }), false);
        assert.strictEqual(CommentPresentation.isCommentEdited({
            createdAt: '2026-09-20T10:00:00Z'
        }), false);
        assert.strictEqual(CommentPresentation.isCommentEdited(null), false);
    });

    // I: renderActionsMenu returns null when input is empty array or missing items
    test('I. renderActionsMenu: returns null when input is empty array, empty items, or null/undefined', () => {
        assert.strictEqual(CommentPresentation.renderActionsMenu([], doc), null);
        assert.strictEqual(CommentPresentation.renderActionsMenu({ items: [] }, doc), null);
        assert.strictEqual(CommentPresentation.renderActionsMenu(null, doc), null);
        assert.strictEqual(CommentPresentation.renderActionsMenu(undefined, doc), null);
        assert.strictEqual(CommentPresentation.renderActionsMenu({}, doc), null);
    });

    // J: renderActionsMenu trigger accessibility
    test('J. renderActionsMenu: renders trigger with aria-haspopup="menu" and aria-expanded="false"', () => {
        const items = [
            { key: 'report', label: 'Báo cáo', attributes: { 'data-action': 'report' } }
        ];
        const menu = CommentPresentation.renderActionsMenu(items, doc);
        assert.ok(menu);
        assert.ok(menu.classList.contains('kl-comment__overflow'));

        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        assert.ok(trigger);
        assert.strictEqual(trigger.getAttribute('aria-haspopup'), 'menu');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(trigger.getAttribute('aria-label'), 'Mở menu bình luận');

        const dots = trigger.querySelector('.kl-comment__menu-dots');
        assert.ok(dots);
        assert.strictEqual(dots.textContent, '⋯');
    });

    // K: renderActionsMenu popover accessibility
    test('K. renderActionsMenu: renders popover with role="menu" and hidden', () => {
        const items = [
            { key: 'report', label: 'Báo cáo', attributes: { 'data-action': 'report' } }
        ];
        const menu = CommentPresentation.renderActionsMenu(items, doc);
        const popover = menu.querySelector('.kl-comment__menu');
        assert.ok(popover);
        assert.strictEqual(popover.getAttribute('role'), 'menu');
        assert.strictEqual(popover.hidden, true);
    });

    // L: renderActionsMenu renders normalized descriptors in exact order
    test('L. renderActionsMenu: renders normalized action descriptors strictly in supplied array order', () => {
        const descriptors = [
            { key: 'view-origin', label: 'Xem bình luận gốc', attributes: { 'data-action': 'view-origin', 'data-block-key': 'bk-1' } },
            { key: 'view-revisions', label: 'Xem lịch sử chỉnh sửa', attributes: { 'data-action': 'view-revisions' } },
            { key: 'edit', label: 'Chỉnh sửa', attributes: { 'data-action': 'edit' } },
            { key: 'delete', label: 'Xóa', danger: true, attributes: { 'data-action': 'delete' } },
            { key: 'report', label: 'Báo cáo', danger: true, separatorBefore: true, attributes: { 'data-action': 'report' } }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        assert.ok(menu);

        const popover = menu.querySelector('.kl-comment__menu');
        const itemButtons = popover.querySelectorAll('.kl-comment__menu-item');
        assert.strictEqual(itemButtons.length, 5);

        assert.strictEqual(itemButtons[0].textContent, 'Xem bình luận gốc');
        assert.strictEqual(itemButtons[0].getAttribute('data-action'), 'view-origin');
        assert.strictEqual(itemButtons[0].getAttribute('data-block-key'), 'bk-1');

        assert.strictEqual(itemButtons[1].textContent, 'Xem lịch sử chỉnh sửa');
        assert.strictEqual(itemButtons[1].getAttribute('data-action'), 'view-revisions');

        assert.strictEqual(itemButtons[2].textContent, 'Chỉnh sửa');
        assert.strictEqual(itemButtons[2].getAttribute('data-action'), 'edit');

        assert.strictEqual(itemButtons[3].textContent, 'Xóa');
        assert.strictEqual(itemButtons[3].getAttribute('data-action'), 'delete');

        assert.strictEqual(itemButtons[4].textContent, 'Báo cáo');
        assert.strictEqual(itemButtons[4].getAttribute('data-action'), 'report');
    });

    // M: renderActionsMenu danger styling, disabled state, and attributes
    test('M. renderActionsMenu: applies danger styling, disabled state, and custom attributes', () => {
        const descriptors = [
            {
                key: 'delete',
                label: 'Xóa',
                danger: true,
                disabled: true,
                className: 'custom-delete-class',
                attributes: {
                    'data-action': 'delete',
                    'data-comment-id': 'c-100',
                    'data-root-id': 'r-100'
                }
            }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        const btn = menu.querySelector('.kl-comment__menu-item');
        assert.ok(btn);
        assert.ok(btn.classList.contains('kl-comment__menu-item--danger'));
        assert.ok(btn.classList.contains('custom-delete-class'));
        assert.strictEqual(btn.disabled, true);
        assert.strictEqual(btn.getAttribute('data-action'), 'delete');
        assert.strictEqual(btn.getAttribute('data-comment-id'), 'c-100');
        assert.strictEqual(btn.getAttribute('data-root-id'), 'r-100');
    });

    // N: renderActionsMenu separatorBefore rendering
    test('N. renderActionsMenu: renders separator before items with separatorBefore: true', () => {
        const descriptors = [
            { key: 'edit', label: 'Chỉnh sửa' },
            { key: 'report', label: 'Báo cáo', separatorBefore: true }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        const sep = menu.querySelector('.kl-comment__menu-separator');
        assert.ok(sep, 'Separator element must be rendered');
        assert.strictEqual(sep.getAttribute('role'), 'separator');

        // Solo item without separatorBefore: NO separator
        const soloMenu = CommentPresentation.renderActionsMenu([{ key: 'report', label: 'Báo cáo' }], doc);
        assert.strictEqual(soloMenu.querySelector('.kl-comment__menu-separator'), null);
    });

    // O: renderActionsMenu: all menu items receive ZERO direct click listeners
    test('O. renderActionsMenu: all menu items receive ZERO direct click listeners (taxonomy-agnostic)', () => {
        const descriptors = [
            { key: 'report', label: 'Báo cáo', attributes: { 'data-action': 'report' } },
            { key: 'edit', label: 'Chỉnh sửa', attributes: { 'data-action': 'edit' } },
            { key: 'view-origin', label: 'Xem bình luận gốc', attributes: { 'data-action': 'view-origin' } },
            { key: 'custom', label: 'Tùy biến', attributes: { 'data-action': 'custom' } }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        const buttons = menu.querySelectorAll('.kl-comment__menu-item');
        assert.strictEqual(buttons.length, 4);
        for (const btn of buttons) {
            const clickListeners = btn.listeners['click'] || [];
            assert.strictEqual(clickListeners.length, 0, 'Menu item buttons must have ZERO direct click listeners');
        }
    });

    // P: renderActionsMenu item click triggers generic menuEl listener to close menu while bubbling to document
    test('P. renderActionsMenu: clicking menu item triggers generic menuEl listener to close menu while bubbling to document', () => {
        const descriptors = [
            { key: 'view-origin', label: 'Xem bình luận gốc', attributes: { 'data-action': 'view-origin' } },
            { key: 'report', label: 'Báo cáo', attributes: { 'data-action': 'report' } }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        doc.body.appendChild(menu);

        let docClickReceived = null;
        doc.addEventListener('click', (e) => {
            docClickReceived = e;
        });

        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        const popover = menu.querySelector('.kl-comment__menu');
        const originBtn = menu.querySelector('[data-action="view-origin"]');

        // Open menu
        trigger.click();
        assert.strictEqual(popover.hidden, false);
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');

        // Click origin item: generic listener on menuEl closes menu and event bubbles to document
        originBtn.click();
        assert.strictEqual(popover.hidden, true, 'Item click must close menu via generic container listener');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.ok(docClickReceived, 'Click event must bubble to document');
        assert.strictEqual(docClickReceived.target, originBtn);
        assert.strictEqual(docClickReceived.defaultPrevented, false);

        // Re-open and test report item
        docClickReceived = null;
        trigger.click();
        assert.strictEqual(popover.hidden, false);

        const reportBtn = menu.querySelector('[data-action="report"]');
        reportBtn.click();
        assert.strictEqual(popover.hidden, true, 'Report item click must close menu via generic container listener');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.ok(docClickReceived, 'Report click event must bubble to document');
        assert.strictEqual(docClickReceived.target, reportBtn);
        assert.strictEqual(docClickReceived.defaultPrevented, false);
    });

    // P2: renderActionsMenu disabled item does NOT close active menu
    test('P2. renderActionsMenu: clicking disabled menu item does NOT close active menu', () => {
        const descriptors = [
            { key: 'del', label: 'Xóa', disabled: true, attributes: { 'data-action': 'delete' } }
        ];

        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        doc.body.appendChild(menu);

        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        const popover = menu.querySelector('.kl-comment__menu');
        const delBtn = menu.querySelector('[data-action="delete"]');

        trigger.click();
        assert.strictEqual(popover.hidden, false);

        delBtn.click();
        assert.strictEqual(popover.hidden, false, 'Disabled item click must NOT close menu');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
    });

    // Q: Menu trigger open/close toggle
    test('Q. trigger click: toggles aria-expanded and hidden states', () => {
        const descriptors = [
            { key: 'edit', label: 'Chỉnh sửa' }
        ];
        const menu = CommentPresentation.renderActionsMenu(descriptors, doc);
        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        const popover = menu.querySelector('.kl-comment__menu');

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(popover.hidden, true);

        // Click to open
        trigger.click();
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popover.hidden, false);
        assert.ok(menu.classList.contains('is-open'));

        // Click to close
        trigger.click();
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(popover.hidden, true);
        assert.strictEqual(menu.classList.contains('is-open'), false);
    });

    // R: Single open menu guarantee
    test('R. single open menu guarantee: opening menu B automatically closes menu A', () => {
        const menuA = CommentPresentation.renderActionsMenu([{ key: 'a', label: 'Action A' }], doc);
        const menuB = CommentPresentation.renderActionsMenu([{ key: 'b', label: 'Action B' }], doc);

        const triggerA = menuA.querySelector('.kl-comment__menu-trigger');
        const popoverA = menuA.querySelector('.kl-comment__menu');
        const triggerB = menuB.querySelector('.kl-comment__menu-trigger');
        const popoverB = menuB.querySelector('.kl-comment__menu');

        // Open menu A
        triggerA.click();
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popoverA.hidden, false);

        // Open menu B -> menu A must close
        triggerB.click();
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(popoverA.hidden, true);
        assert.strictEqual(triggerB.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popoverB.hidden, false);
    });

    // S: Escape key dismisses menu and restores focus
    test('S. escape key: dismisses active menu and restores focus to trigger button', () => {
        const menu = CommentPresentation.renderActionsMenu([{ key: 'act', label: 'Action' }], doc);
        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        const popover = menu.querySelector('.kl-comment__menu');

        trigger.click();
        assert.strictEqual(popover.hidden, false);

        // Send Escape key event
        const escEvent = {
            type: 'keydown',
            key: 'Escape',
            defaultPrevented: false,
            preventDefault() { this.defaultPrevented = true; }
        };
        doc.dispatchEvent(escEvent);

        assert.strictEqual(popover.hidden, true);
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(trigger.isFocused, true);
    });

    // T: Outside click dismisses menu
    test('T. outside click: dismisses active menu', () => {
        const menu = CommentPresentation.renderActionsMenu([{ key: 'act', label: 'Action' }], doc);
        doc.body.appendChild(menu);

        const outsideEl = doc.createElement('div');
        doc.body.appendChild(outsideEl);

        const trigger = menu.querySelector('.kl-comment__menu-trigger');
        const popover = menu.querySelector('.kl-comment__menu');

        trigger.click();
        assert.strictEqual(popover.hidden, false);

        // Click outside
        const outsideClickEvent = {
            type: 'click',
            target: outsideEl,
            defaultPrevented: false,
            preventDefault() {}
        };
        doc.dispatchEvent(outsideClickEvent);

        assert.strictEqual(popover.hidden, true);
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
    });

    // U: renderComment builds complete card with normalized action descriptor array
    test('U. renderComment: renders complete comment card, actions with descriptor array, tombstone, and attaches legacyPrefix classes', () => {
        const overflowDescriptors = [
            {
                key: 'report',
                label: 'Báo cáo',
                className: 'novel-comment-menu-item novel-comment-report-btn',
                attributes: {
                    'data-action': 'report',
                    'data-comment-id': '101'
                }
            }
        ];

        // Active Root Comment with legacyPrefix 'novel-comment'
        const rootCommentEl = CommentPresentation.renderComment({
            id: '101',
            legacyPrefix: 'novel-comment',
            className: 'novel-comment--root',
            author: { displayName: 'Author 1', avatarUrl: '/img/a1.png', userId: 'u1' },
            createdAt: '2026-09-20T11:00:00Z',
            edited: true,
            body: 'Hello world comment content',
            attributes: {
                'data-comment-id': '101',
                'data-author-user-id': 'u1'
            },
            overflowActions: overflowDescriptors,
            primaryActions: [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'novel-comment-reply-btn',
                    attributes: { 'data-action': 'reply', 'data-comment-id': '101' }
                }
            ]
        }, doc);

        assert.ok(rootCommentEl);
        assert.ok(rootCommentEl.classList.contains('kl-comment'));
        assert.ok(rootCommentEl.classList.contains('novel-comment'));
        assert.ok(rootCommentEl.classList.contains('novel-comment--root'));
        assert.strictEqual(rootCommentEl.getAttribute('data-comment-id'), '101');
        assert.strictEqual(rootCommentEl.getAttribute('data-author-user-id'), 'u1');

        // Header & elements with both kl-comment and novel-comment classes
        const header = rootCommentEl.querySelector('.kl-comment__header');
        assert.ok(header);
        assert.ok(header.classList.contains('novel-comment-header'));

        const avatar = rootCommentEl.querySelector('.kl-comment__avatar');
        assert.ok(avatar);
        assert.ok(avatar.classList.contains('novel-comment-avatar'));

        const author = rootCommentEl.querySelector('.kl-comment__author');
        assert.ok(author);
        assert.ok(author.classList.contains('novel-comment-author'));
        assert.strictEqual(author.textContent, 'Author 1');

        const time = rootCommentEl.querySelector('.kl-comment__time');
        assert.ok(time);
        assert.ok(time.classList.contains('novel-comment-time'));

        const edited = rootCommentEl.querySelector('.kl-comment__edited');
        assert.ok(edited);
        assert.ok(edited.classList.contains('novel-comment-edited'));
        assert.strictEqual(edited.textContent, 'đã chỉnh sửa');

        const menu = rootCommentEl.querySelector('.kl-comment__overflow');
        assert.ok(menu);
        assert.ok(menu.classList.contains('novel-comment-actions-menu'));

        const reportBtn = menu.querySelector('[data-action="report"]');
        assert.ok(reportBtn);
        assert.strictEqual(reportBtn.textContent, 'Báo cáo');
        assert.ok(reportBtn.classList.contains('novel-comment-report-btn'));
        assert.strictEqual((reportBtn.listeners['click'] || []).length, 0, 'Report button must have 0 click listeners in shared presentation');

        // Body
        const body = rootCommentEl.querySelector('.kl-comment__body');
        assert.ok(body);
        assert.ok(body.classList.contains('novel-comment-body'));
        assert.strictEqual(body.textContent, 'Hello world comment content');

        // Reply primary action
        const actions = rootCommentEl.querySelector('.kl-comment__primary-actions');
        assert.ok(actions);
        assert.ok(actions.classList.contains('novel-comment-actions'));

        const replyBtn = actions.querySelector('.kl-comment__primary-action');
        assert.ok(replyBtn);
        assert.ok(replyBtn.classList.contains('novel-comment-reply-btn'));
        assert.strictEqual(replyBtn.textContent, 'Phản hồi');
        assert.strictEqual(replyBtn.getAttribute('data-action'), 'reply');

        // Tombstone Comment
        const tombstoneEl = CommentPresentation.renderComment({
            id: '102',
            legacyPrefix: 'novel-comment',
            className: 'novel-comment--reply',
            tombstone: true,
            tombstoneContent: 'Bình luận đã bị xóa.'
        }, doc);

        assert.ok(tombstoneEl);
        assert.ok(tombstoneEl.classList.contains('kl-comment--tombstone'));
        assert.ok(tombstoneEl.classList.contains('is-tombstone'));

        const tombBody = tombstoneEl.querySelector('.kl-comment__body--tombstone');
        assert.ok(tombBody);
        assert.ok(tombBody.classList.contains('novel-comment-body--tombstone'));
        assert.strictEqual(tombBody.textContent, 'Bình luận đã bị xóa.');
    });

    // V: renderComment primary actions do not bind or execute onClick callbacks
    test('V. renderComment: primary actions render attributes and classes but do NOT bind or execute onClick callbacks', () => {
        let clicked = false;
        const commentEl = CommentPresentation.renderComment({
            id: '200',
            primaryActions: [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'custom-reply-btn',
                    attributes: { 'data-action': 'reply', 'data-comment-id': '200' },
                    onClick: () => { clicked = true; }
                }
            ]
        }, doc);

        const btn = commentEl.querySelector('.kl-comment__primary-action');
        assert.ok(btn);
        assert.strictEqual(btn.getAttribute('data-action'), 'reply');
        assert.strictEqual(btn.getAttribute('data-comment-id'), '200');
        assert.strictEqual((btn.listeners['click'] || []).length, 0, 'Primary action must have ZERO click listeners');

        btn.click();
        assert.strictEqual(clicked, false, 'act.onClick must NOT be called by shared presentation');
    });
});
