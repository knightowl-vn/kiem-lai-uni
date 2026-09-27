const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
const CommentReportModal = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-report-modal.js'));

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
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.hidden = false;
        this.disabled = false;
        this._value = '';
        this._checked = false;
        this._textContent = '';
        this.isFocused = false;
        this.style = {};

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get value() {
        return this._value;
    }

    set value(val) {
        this._value = val == null ? '' : String(val);
    }

    get checked() {
        return this._checked;
    }

    set checked(val) {
        this._checked = Boolean(val);
        // If this is a radio button being checked, uncheck sibling radios with same name
        if (this._checked && this.getAttribute('type') === 'radio' && this.getAttribute('name') && this.parentNode) {
            const root = this._findRoot();
            const sameNameRadios = root.querySelectorAll('input[name="' + this.getAttribute('name') + '"]');
            for (const r of sameNameRadios) {
                if (r !== this) {
                    r._checked = false;
                }
            }
        }
    }

    _findRoot() {
        let cur = this;
        while (cur.parentNode) {
            cur = cur.parentNode;
        }
        return cur;
    }

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
    }

    get id() {
        return this.getAttribute('id') || '';
    }

    set id(val) {
        this.setAttribute('id', val);
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this.childNodes = [];
        this._textContent = val == null ? '' : String(val);
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes = new Set(String(value).trim().split(/\s+/).filter(Boolean));
        }
        if (name === 'hidden') {
            this.hidden = true;
        }
        if (name === 'id') {
            this._id = String(value);
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

    hasAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name);
    }

    appendChild(child) {
        if (!child) return child;
        if (child.parentNode) {
            child.parentNode.removeChild(child);
        }
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx >= 0) {
            this.childNodes.splice(idx, 1);
            child.parentNode = null;
        }
        return child;
    }

    addEventListener(event, handler) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(handler);
    }

    removeEventListener(event, handler) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(handler);
        if (idx >= 0) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(event) {
        const evt = typeof event === 'string' ? { type: event } : event;
        if (!evt.target) {
            evt.target = this;
        }
        let cur = this;
        while (cur) {
            const handlers = cur.listeners[evt.type] || [];
            for (const h of handlers) {
                h(evt);
            }
            if (evt.cancelBubble) break;
            cur = cur.parentNode;
        }
    }

    focus() {
        this.isFocused = true;
    }

    querySelector(selector) {
        return this._findFirst(this, selector);
    }

    querySelectorAll(selector) {
        const matches = [];
        this._findAll(this, selector, matches);
        return matches;
    }

    _matchesSelector(selector) {
        if (!selector) return false;
        let s = selector.trim();

        // Compound tag[attr="val"] selector: e.g. input[value="SPAM"]
        const tagAttrMatch = s.match(/^([a-zA-Z0-9_-]+)\[(.*)\]$/);
        if (tagAttrMatch) {
            const tag = tagAttrMatch[1];
            if (this.tagName.toLowerCase() !== tag.toLowerCase()) {
                return false;
            }
            s = '[' + tagAttrMatch[2] + ']';
        }

        if (s.startsWith('.')) {
            return this.classList.contains(s.slice(1));
        }
        if (s.startsWith('#')) {
            return this.getAttribute('id') === s.slice(1);
        }
        if (s.startsWith('[') && s.endsWith(']')) {
            const raw = s.slice(1, -1);
            if (raw.includes('=')) {
                const parts = raw.split('=');
                const attr = parts[0].trim();
                const val = parts.slice(1).join('=').replace(/^["']|["']$/g, '').trim();
                return this.getAttribute(attr) === val;
            }
            return this.hasAttribute(raw);
        }
        return this.tagName.toLowerCase() === s.toLowerCase();
    }

    _findFirst(node, selector) {
        for (const child of node.childNodes) {
            if (child._matchesSelector && child._matchesSelector(selector)) {
                return child;
            }
            const found = this._findFirst(child, selector);
            if (found) return found;
        }
        return null;
    }

    _findAll(node, selector, matches) {
        for (const child of node.childNodes) {
            if (child._matchesSelector && child._matchesSelector(selector)) {
                matches.push(child);
            }
            this._findAll(child, selector, matches);
        }
    }
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('BODY');
        this.head = new FakeElement('HEAD');
        this.listeners = {};
        this.activeElement = null;
        this.defaultView = {
            listeners: {},
            addEventListener: (e, h) => {
                if (!this.defaultView.listeners[e]) this.defaultView.listeners[e] = [];
                this.defaultView.listeners[e].push(h);
            },
            removeEventListener: (e, h) => {
                if (!this.defaultView.listeners[e]) return;
                const idx = this.defaultView.listeners[e].indexOf(h);
                if (idx >= 0) this.defaultView.listeners[e].splice(idx, 1);
            },
            trigger: (event, arg) => {
                const handlers = this.defaultView.listeners[event] || [];
                for (const h of handlers) h(arg);
            }
        };
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

    getElementById(id) {
        return this.querySelector('#' + id);
    }

    querySelector(selector) {
        if (this.head && this.head._matchesSelector && this.head._matchesSelector(selector)) {
            return this.head;
        }
        const inHead = this.head ? this.head.querySelector(selector) : null;
        if (inHead) return inHead;
        if (this.body && this.body._matchesSelector && this.body._matchesSelector(selector)) {
            return this.body;
        }
        return this.body ? this.body.querySelector(selector) : null;
    }

    querySelectorAll(selector) {
        const results = [];
        if (this.head) results.push(...this.head.querySelectorAll(selector));
        if (this.body) results.push(...this.body.querySelectorAll(selector));
        return results;
    }

    addEventListener(event, handler) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(handler);
    }

    removeEventListener(event, handler) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(handler);
        if (idx >= 0) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(event) {
        const handlers = this.listeners[event.type || event] || [];
        for (const h of handlers) {
            h(event);
        }
    }
}

class MockStorage {
    constructor() {
        this.store = new Map();
    }
    getItem(k) {
        return this.store.has(k) ? this.store.get(k) : null;
    }
    setItem(k, v) {
        this.store.set(k, String(v));
    }
    removeItem(k) {
        this.store.delete(k);
    }
    clear() {
        this.store.clear();
    }
}

// ============================================================================
// Tests
// ============================================================================

describe('MS-05E / E8C2 — CommentReportModal Shared Component Tests', () => {

    const COMMENT_A = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const COMMENT_B = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    const SUBMIT_URL_A = '/api/novel/chapters/1111/comments/' + COMMENT_A + '/reports';
    const SUBMIT_URL_B = '/api/wiki/articles/2222/comments/' + COMMENT_B + '/reports';

    let fakeDoc;
    let mockStorage;
    let mockTime;
    let draftStore;
    let modal;
    let fetchCalls;

    beforeEach(() => {
        fakeDoc = new FakeDocument();
        mockStorage = new MockStorage();
        mockTime = 1_000_000;
        fetchCalls = [];

        // Attach Spring Security CSRF meta tags
        const csrfMeta = fakeDoc.createElement('META');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'test-csrf-token-xyz');
        fakeDoc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = fakeDoc.createElement('META');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        fakeDoc.head.appendChild(csrfHeaderMeta);

        draftStore = EphemeralDraftStore.createStore({
            storage: mockStorage,
            clock: () => mockTime,
            defaultTtlMs: 5 * 60 * 1000 // 5 minutes
        });

        const mockFetch = async (url, options) => {
            fetchCalls.push({ url, options });
            return {
                status: 201,
                json: async () => ({
                    reportId: '99999999-9999-9999-9999-999999999999',
                    status: 'PENDING',
                    createdAt: '2026-09-19T10:00:00Z'
                })
            };
        };

        modal = CommentReportModal.createCommentReportModal({
            doc: fakeDoc,
            draftStore: draftStore,
            fetch: mockFetch
        });
    });

    // ========================================================================
    // 1. KEY SPECIFICATION & ISOLATION
    // ========================================================================

    describe('1. Key Specification & Isolation', () => {
        test('canonical key matches kiemlai:draft:interaction-report:{commentId}', () => {
            const key = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.strictEqual(key, 'kiemlai:draft:interaction-report:' + COMMENT_A);
        });

        test('getReportDraftKey rejects non-string or blank commentId', () => {
            assert.strictEqual(CommentReportModal.getReportDraftKey(null), null);
            assert.strictEqual(CommentReportModal.getReportDraftKey(undefined), null);
            assert.strictEqual(CommentReportModal.getReportDraftKey(''), null);
            assert.strictEqual(CommentReportModal.getReportDraftKey('   '), null);
            assert.strictEqual(CommentReportModal.getReportDraftKey(12345), null);
        });

        test('comment A and comment B drafts are strictly isolated in storage', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            const spamRadio = els.modal.querySelector('input[value="SPAM"]');
            spamRadio.checked = true;
            els.textarea.value = 'Description for A';
            modal.flushDraft();
            modal.close(false); // passive close

            // Now open B: must be empty, not showing A
            modal.open({ commentId: COMMENT_B, submitUrl: SUBMIT_URL_B });
            const elsB = modal.getElements();
            const spamRadioB = elsB.modal.querySelector('input[value="SPAM"]');
            assert.strictEqual(spamRadioB.checked, false);
            assert.strictEqual(elsB.textarea.value, '');

            // Store B draft
            const harRadioB = elsB.modal.querySelector('input[value="HARASSMENT"]');
            harRadioB.checked = true;
            elsB.textarea.value = 'Description for B';
            modal.flushDraft();

            // Verify both keys exist separately in mockStorage
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            const keyB = CommentReportModal.getReportDraftKey(COMMENT_B);
            assert.notStrictEqual(keyA, keyB);
            assert.ok(mockStorage.getItem(keyA));
            assert.ok(mockStorage.getItem(keyB));

            const draftA = JSON.parse(mockStorage.getItem(keyA)).value;
            const draftB = JSON.parse(mockStorage.getItem(keyB)).value;
            assert.strictEqual(JSON.parse(draftA).description, 'Description for A');
            assert.strictEqual(JSON.parse(draftB).description, 'Description for B');
        });
    });

    // ========================================================================
    // 2. TTL & EXPIRATION
    // ========================================================================

    describe('2. TTL & Expiration', () => {
        test('restores draft within 5 minutes', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Unsent report text';
            modal.flushDraft();
            modal.close(false);

            // Advance clock by 4 minutes (240,000 ms)
            mockTime += 4 * 60 * 1000;

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const restoredEls = modal.getElements();
            assert.strictEqual(restoredEls.modal.querySelector('input[value="SPAM"]').checked, true);
            assert.strictEqual(restoredEls.textarea.value, 'Unsent report text');
        });

        test('expired draft (> 5 minutes) is purged and not restored', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Will expire soon';
            modal.flushDraft();
            modal.close(false);

            // Advance clock by 5 minutes + 1 second (301,000 ms)
            mockTime += 301_000;

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const restoredEls = modal.getElements();
            assert.strictEqual(restoredEls.modal.querySelector('input[value="SPAM"]').checked, false);
            assert.strictEqual(restoredEls.textarea.value, '');

            // Key is purged from storage
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.strictEqual(mockStorage.getItem(keyA), null);
        });
    });

    // ========================================================================
    // 3. RAW VALUE & UNICODE PRESERVATION
    // ========================================================================

    describe('3. Raw Value Preservation', () => {
        test('preserves exact multiline, Unicode, and whitespace text', () => {
            const rawDescription = '  Dòng 1: Vi phạm bản quyền 📚\n\nDòng 2: Nội dung khiêu dâm 🔞\n   Dòng 3 có thụt lề   \n';
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SEXUAL_OR_OBSCENE"]').checked = true;
            els.textarea.value = rawDescription;
            modal.flushDraft();
            modal.close(false);

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const restoredEls = modal.getElements();
            assert.strictEqual(restoredEls.textarea.value, rawDescription);
        });
    });

    // ========================================================================
    // 4. MALFORMED DATA RESILIENCE
    // ========================================================================

    describe('4. Malformed Data Resilience', () => {
        test('corrupted JSON in storage is safely purged without throwing', () => {
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            mockStorage.setItem(keyA, JSON.stringify({
                value: 'NOT_VALID_JSON{{{',
                savedAt: mockTime
            }));

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            assert.strictEqual(els.textarea.value, '');
            assert.strictEqual(mockStorage.getItem(keyA), null);
        });

        test('unknown reason in draft is discarded while description is preserved', () => {
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            mockStorage.setItem(keyA, JSON.stringify({
                value: JSON.stringify({
                    reason: 'INVALID_UNKNOWN_REASON',
                    description: 'Valid description text'
                }),
                savedAt: mockTime
            }));

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            // None of the radios should be checked
            for (const r of els.radios) {
                assert.strictEqual(r.checked, false);
            }
            // Description is preserved
            assert.strictEqual(els.textarea.value, 'Valid description text');
        });
    });

    // ========================================================================
    // 5. LIFECYCLE & DISCARD SEMANTICS
    // ========================================================================

    describe('5. Lifecycle Semantics (Passive Close vs Explicit Cancel)', () => {
        test('passive close via X button flushes and preserves draft', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Passive close test';

            // Click close button (X)
            els.closeBtn.dispatchEvent('click');
            assert.strictEqual(modal.isOpen(), false);

            // Verify draft is stored
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));

            // Reopen -> restored
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            assert.strictEqual(modal.getElements().textarea.value, 'Passive close test');
        });

        test('passive close via backdrop click preserves draft', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Backdrop click test';

            els.backdrop.dispatchEvent('click');
            assert.strictEqual(modal.isOpen(), false);

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            assert.strictEqual(modal.getElements().textarea.value, 'Backdrop click test');
        });

        test('passive close via Escape key preserves draft', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Escape key test';

            // Trigger Escape keydown on document
            fakeDoc.dispatchEvent({ type: 'keydown', key: 'Escape' });
            assert.strictEqual(modal.isOpen(), false);

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            assert.strictEqual(modal.getElements().textarea.value, 'Escape key test');
        });

        test('passive close via window pagehide flushes current draft', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Pagehide test';

            fakeDoc.defaultView.trigger('pagehide');

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));
        });

        test('explicit Cancel button clears draft, resets form, and closes modal', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Explicit cancel text';
            modal.flushDraft();

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));

            // Click Cancel button
            els.cancelBtn.dispatchEvent('click');
            assert.strictEqual(modal.isOpen(), false);

            // Draft must be removed from storage
            assert.strictEqual(mockStorage.getItem(keyA), null);

            // Reopening must show empty form
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const reopenedEls = modal.getElements();
            assert.strictEqual(reopenedEls.textarea.value, '');
            assert.strictEqual(reopenedEls.modal.querySelector('input[value="SPAM"]').checked, false);
        });

        test('successful submit (201) clears draft, resets form, and closes modal', async () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Spam content';
            modal.flushDraft();

            // Submit form
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });

            // Allow async promise to resolve
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(modal.isOpen(), false);
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.strictEqual(mockStorage.getItem(keyA), null);
        });

        test('failed submit preserves draft and keeps modal open', async () => {
            const failingFetch = async () => {
                return { status: 409, json: async () => ({}) };
            };
            const customModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: failingFetch
            });

            customModal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = customModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Keep my draft on error';
            customModal.flushDraft();

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            // Modal remains open and draft is preserved
            assert.strictEqual(customModal.isOpen(), true);
            assert.strictEqual(els.textarea.value, 'Keep my draft on error');
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));

            // Error message is displayed
            assert.strictEqual(els.status.hidden, false);
            assert.ok(els.status.textContent.includes('đang chờ xử lý'));
        });
    });

    // ========================================================================
    // 6. VALIDATION & DESCRIPTION RULES
    // ========================================================================

    describe('6. Validation & Description Rules', () => {
        test('submit button is disabled when no reason is selected', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            assert.strictEqual(els.submitBtn.disabled, true);
        });

        test('non-OTHER reason allows blank description and enables submit', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            const spamRadio = els.modal.querySelector('input[value="SPAM"]');
            spamRadio.checked = true;
            spamRadio.dispatchEvent('change');

            assert.strictEqual(els.submitBtn.disabled, false);
            assert.strictEqual(els.descRequired.hidden, true);
            assert.strictEqual(els.textarea.getAttribute('aria-required'), 'false');
        });

        test('OTHER reason requires non-blank description', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            const otherRadio = els.modal.querySelector('input[value="OTHER"]');
            otherRadio.checked = true;
            otherRadio.dispatchEvent('change');

            // With empty description -> disabled and required visible
            assert.strictEqual(els.submitBtn.disabled, true);
            assert.strictEqual(els.descRequired.hidden, false);
            assert.strictEqual(els.textarea.getAttribute('aria-required'), 'true');

            // With whitespace-only description -> still disabled
            els.textarea.value = '    \n\t  ';
            els.textarea.dispatchEvent('input');
            assert.strictEqual(els.submitBtn.disabled, true);

            // With non-blank text -> enabled
            els.textarea.value = 'Mô tả cụ thể vi phạm';
            els.textarea.dispatchEvent('input');
            assert.strictEqual(els.submitBtn.disabled, false);
        });

        test('character counter updates and enforces 500-character boundary', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;

            // Exactly 500 characters
            const text500 = 'x'.repeat(500);
            els.textarea.value = text500;
            els.textarea.dispatchEvent('input');

            assert.strictEqual(els.charCounter.textContent, '500 / 500');
            assert.strictEqual(els.submitBtn.disabled, false);

            // Exceeding 500 characters
            const text501 = 'x'.repeat(501);
            els.textarea.value = text501;
            els.textarea.dispatchEvent('input');

            assert.strictEqual(els.charCounter.textContent, '501 / 500');
            assert.strictEqual(els.submitBtn.disabled, true);
        });

        test('restoring draft never automatically submits form', () => {
            // Pre-seed a valid draft in storage
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            mockStorage.setItem(keyA, JSON.stringify({
                value: JSON.stringify({
                    reason: 'SPAM',
                    description: 'Valid pre-existing spam draft'
                }),
                savedAt: mockTime
            }));

            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            assert.strictEqual(fetchCalls.length, 0);
            assert.strictEqual(modal.isSubmitting(), false);
        });
    });

    // ========================================================================
    // 7. REQUEST CLIENT & BACKEND INTEGRATION
    // ========================================================================

    describe('7. Request Client & Protocol', () => {
        test('issues POST to exact consumer submitUrl with exact { reason, description } payload', async () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="HARASSMENT"]').checked = true;
            els.textarea.value = 'Exact text description without mutations';

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(fetchCalls.length, 1);
            const call = fetchCalls[0];
            assert.strictEqual(call.url, SUBMIT_URL_A);
            assert.strictEqual(call.options.method, 'POST');

            const payload = JSON.parse(call.options.body);
            assert.deepStrictEqual(payload, {
                reason: 'HARASSMENT',
                description: 'Exact text description without mutations'
            });

            // Assert forbidden fields are absent
            assert.strictEqual(payload.reporterUserId, undefined);
            assert.strictEqual(payload.actorUserId, undefined);
            assert.strictEqual(payload.reportedBodySnapshot, undefined);
            assert.strictEqual(payload.status, undefined);
        });

        test('includes Spring Security CSRF headers', async () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(fetchCalls.length, 1);
            const headers = fetchCalls[0].options.headers;
            assert.strictEqual(headers['X-CSRF-TOKEN'], 'test-csrf-token-xyz');
            assert.strictEqual(headers['Content-Type'], 'application/json');
            assert.strictEqual(headers['Accept'], 'application/json');
        });

        test('prevents double submission while request is in flight', async () => {
            let resolveFetch;
            const slowFetch = () => new Promise(resolve => {
                resolveFetch = resolve;
            });

            const customModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: slowFetch
            });

            customModal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = customModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;

            // Submit 1
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            assert.strictEqual(customModal.isSubmitting(), true);
            assert.strictEqual(els.submitBtn.disabled, true);

            // Submit 2 while in-flight -> ignored
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            assert.strictEqual(customModal.isSubmitting(), true);

            resolveFetch({ status: 201, json: async () => ({}) });
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(customModal.isSubmitting(), false);
        });

        test('notifies onSuccess callback and dispatches custom event once on 201', async () => {
            let callbackFired = false;
            let callbackData = null;
            let eventFired = false;

            fakeDoc.addEventListener('kiemlai:comment-report-submitted', (e) => {
                eventFired = true;
                assert.strictEqual(e.detail.commentId, COMMENT_A);
            });

            modal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onSuccess: (data, context) => {
                    callbackFired = true;
                    callbackData = data;
                    assert.strictEqual(context.commentId, COMMENT_A);
                }
            });

            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(callbackFired, true);
            assert.strictEqual(eventFired, true);
            assert.strictEqual(callbackData.status, 'PENDING');
        });

        test('maps backend HTTP 403, 404, 409 to user-friendly messages', async () => {
            const testCases = [
                { status: 403, expectedKeyword: 'bình luận của chính bạn' },
                { status: 404, expectedKeyword: 'không còn khả dụng' },
                { status: 409, expectedKeyword: 'đang chờ xử lý' },
                { status: 500, expectedKeyword: 'Không thể gửi báo cáo lúc này' }
            ];

            for (const tc of testCases) {
                const customModal = CommentReportModal.createCommentReportModal({
                    doc: fakeDoc,
                    draftStore: draftStore,
                    fetch: async () => ({ status: tc.status, json: async () => ({}) })
                });

                customModal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
                const els = customModal.getElements();
                els.modal.querySelector('input[value="SPAM"]').checked = true;
                els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
                await new Promise(r => setTimeout(r, 10));

                assert.ok(
                    els.status.textContent.includes(tc.expectedKeyword),
                    `Expected status message for HTTP ${tc.status} to contain "${tc.expectedKeyword}", got: "${els.status.textContent}"`
                );
            }
        });

        test('expired-session / followed-login HTTP 200 response is treated as error and preserves draft', async () => {
            let callbackFired = false;
            let errorCallbackFired = false;
            let capturedErrorStatus = null;

            const customModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: async () => ({
                    status: 200,
                    headers: { 'Content-Type': 'text/html' },
                    text: async () => '<!DOCTYPE html><html><body>Login Page</body></html>',
                    json: async () => { throw new Error('Unexpected token < in JSON'); }
                })
            });

            customModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onSuccess: () => {
                    callbackFired = true;
                },
                onError: (err, context) => {
                    errorCallbackFired = true;
                    capturedErrorStatus = context && context.status;
                }
            });

            const els = customModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'My pending report description';
            customModal.flushDraft();

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            // Must NOT treat as success
            assert.strictEqual(callbackFired, false);
            assert.strictEqual(customModal.isOpen(), true);
            assert.strictEqual(customModal.isSubmitting(), false);

            // Error callback fired with status 200
            assert.strictEqual(errorCallbackFired, true);
            assert.strictEqual(capturedErrorStatus, 200);

            // Error message displayed
            assert.ok(els.status.textContent.includes('Không thể gửi báo cáo lúc này'));

            // Form inputs preserved
            assert.strictEqual(els.modal.querySelector('input[value="SPAM"]').checked, true);
            assert.strictEqual(els.textarea.value, 'My pending report description');

            // Draft in storage remains preserved
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            const rawDraft = draftStore.load(keyA);
            assert.ok(rawDraft);
            const savedDraft = JSON.parse(rawDraft);
            assert.strictEqual(savedDraft.reason, 'SPAM');
            assert.strictEqual(savedDraft.description, 'My pending report description');
        });
    });

    // ========================================================================
    // 8. ACCESSIBILITY & FOCUS MANAGEMENT
    // ========================================================================

    describe('8. Accessibility & Focus Management', () => {
        test('modal markup adheres to Bootstrap accessibility attributes', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();

            assert.strictEqual(els.modal.getAttribute('role'), 'dialog');
            assert.strictEqual(els.modal.getAttribute('aria-modal'), 'true');
            assert.strictEqual(els.modal.getAttribute('aria-labelledby'), 'kiemlaiCommentReportTitle');
            assert.strictEqual(els.closeBtn.getAttribute('aria-label'), 'Đóng');
            assert.strictEqual(els.status.getAttribute('role'), 'status');
            assert.strictEqual(els.status.getAttribute('aria-live'), 'polite');
            assert.strictEqual(els.charCounter.getAttribute('aria-live'), 'polite');
        });

        test('returns focus to trigger element upon closing or completing report', async () => {
            const triggerEl = fakeDoc.createElement('BUTTON');
            triggerEl.textContent = 'Report Button';
            fakeDoc.body.appendChild(triggerEl);

            modal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                triggerEl: triggerEl
            });

            assert.strictEqual(triggerEl.isFocused, false);

            // Close modal
            modal.close(false);
            assert.strictEqual(triggerEl.isFocused, true);
        });
    });

    // ========================================================================
    // 9. EDGE CASES & ROBUSTNESS
    // ========================================================================

    describe('9. Edge Cases & Robustness', () => {
        test('open rejects missing or whitespace commentId / submitUrl', () => {
            assert.strictEqual(modal.open(null), false);
            assert.strictEqual(modal.open({}), false);
            assert.strictEqual(modal.open({ commentId: '', submitUrl: SUBMIT_URL_A }), false);
            assert.strictEqual(modal.open({ commentId: '   ', submitUrl: SUBMIT_URL_A }), false);
            assert.strictEqual(modal.open({ commentId: COMMENT_A, submitUrl: '' }), false);
            assert.strictEqual(modal.open({ commentId: COMMENT_A, submitUrl: '   ' }), false);
            assert.strictEqual(modal.isOpen(), false);
        });

        test('switching comments while open flushes prior comment draft automatically', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const elsA = modal.getElements();
            elsA.modal.querySelector('input[value="SPAM"]').checked = true;
            elsA.textarea.value = 'Auto-flush when switching to B';

            // Switch to comment B directly without manual close
            modal.open({ commentId: COMMENT_B, submitUrl: SUBMIT_URL_B });

            // Verify comment A draft was flushed
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));
            assert.ok(mockStorage.getItem(keyA).includes('Auto-flush when switching to B'));

            // Comment B starts clean
            const elsB = modal.getElements();
            assert.strictEqual(elsB.textarea.value, '');
        });

        test('debounced input auto-saves after 400ms delay', async () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Typing without explicit flush';
            els.textarea.dispatchEvent('input');

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            // Immediately after input, not saved yet
            assert.strictEqual(mockStorage.getItem(keyA), null);

            // Wait 450ms for debounce timer to fire
            await new Promise(r => setTimeout(r, 450));

            // Now saved
            assert.ok(mockStorage.getItem(keyA));
            assert.ok(mockStorage.getItem(keyA).includes('Typing without explicit flush'));
        });

        test('notifies custom onError callback with status and context on failure', async () => {
            let errorCaught = null;
            let errorContext = null;

            const failingFetch = async () => ({
                status: 400,
                json: async () => ({ error: 'Bad Request' })
            });

            const customModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: failingFetch
            });

            customModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onError: (err, ctx) => {
                    errorCaught = err;
                    errorContext = ctx;
                }
            });

            const els = customModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            assert.ok(errorCaught);
            assert.strictEqual(errorContext.commentId, COMMENT_A);
            assert.strictEqual(errorContext.submitUrl, SUBMIT_URL_A);
            assert.strictEqual(errorContext.status, 400);
        });

        test('destroy cleans up DOM and listeners safely', () => {
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            assert.strictEqual(modal.isOpen(), true);
            assert.ok(modal.getModalElement());

            modal.destroy();
            assert.strictEqual(modal.isOpen(), false);
            assert.strictEqual(modal.getModalElement(), null);
        });
    });

    // ========================================================================
    // 10. MONOTONIC GENERATION & CONCURRENCY RACE PROTECTIONS
    // ========================================================================

    describe('10. Monotonic Generation & Concurrency Race Protections', () => {
        test('1. stale 201 A -> B: B stays open, B form/draft unchanged, B callback not called for A', async () => {
            let resolveSubmitA;
            let bSuccessCalled = false;
            let aSuccessCalled = false;

            const controlledFetch = async (url) => {
                if (url === SUBMIT_URL_A) {
                    return new Promise(resolve => {
                        resolveSubmitA = resolve;
                    });
                }
                return {
                    status: 201,
                    json: async () => ({ reportId: 'bbbbbbbb-report' })
                };
            };

            const raceModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: controlledFetch
            });

            // 1. Open A and start submit A
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onSuccess: () => { aSuccessCalled = true; }
            });
            const elsA = raceModal.getElements();
            elsA.modal.querySelector('input[value="SPAM"]').checked = true;
            elsA.textarea.value = 'Report text A';
            elsA.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            assert.strictEqual(raceModal.isSubmitting(), true);

            // 2. Open B while A is pending
            raceModal.open({
                commentId: COMMENT_B,
                submitUrl: SUBMIT_URL_B,
                onSuccess: () => { bSuccessCalled = true; }
            });
            const elsB = raceModal.getElements();
            elsB.modal.querySelector('input[value="HARASSMENT"]').checked = true;
            elsB.textarea.value = 'Report text B';
            raceModal.flushDraft();

            // 3. Resolve old submit A with 201
            resolveSubmitA({
                status: 201,
                json: async () => ({ reportId: 'aaaaaaaa-report' })
            });
            await new Promise(r => setTimeout(r, 15));

            // 4. Assert B stays open, B form unchanged, B draft unchanged, B callback not called for A
            assert.strictEqual(raceModal.isOpen(), true);
            assert.strictEqual(raceModal.getActiveCommentId(), COMMENT_B);
            assert.strictEqual(raceModal.isSubmitting(), false);
            assert.strictEqual(elsB.modal.querySelector('input[value="HARASSMENT"]').checked, true);
            assert.strictEqual(elsB.textarea.value, 'Report text B');
            assert.strictEqual(bSuccessCalled, false);

            // Verify B draft in storage intact
            const keyB = CommentReportModal.getReportDraftKey(COMMENT_B);
            const draftB = JSON.parse(mockStorage.getItem(keyB)).value;
            assert.strictEqual(JSON.parse(draftB).description, 'Report text B');
        });

        test('2. stale failure A -> B: B gets no error, B onError not called, B state unchanged', async () => {
            let rejectSubmitA;
            let bErrorCalled = false;

            const controlledFetch = async (url) => {
                if (url === SUBMIT_URL_A) {
                    return new Promise((_, reject) => {
                        rejectSubmitA = reject;
                    });
                }
                return { status: 200, json: async () => ({}) };
            };

            const raceModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: controlledFetch
            });

            // 1. Open A and start submit A
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            const elsA = raceModal.getElements();
            elsA.modal.querySelector('input[value="SPAM"]').checked = true;
            elsA.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });

            // 2. Open B while A is pending
            raceModal.open({
                commentId: COMMENT_B,
                submitUrl: SUBMIT_URL_B,
                onError: () => { bErrorCalled = true; }
            });
            const elsB = raceModal.getElements();
            elsB.modal.querySelector('input[value="HATE_SPEECH"]').checked = true;
            elsB.textarea.value = 'B content';

            // 3. Stale submit A fails with network error / rejection
            rejectSubmitA(new Error('Network error on old A'));
            await new Promise(r => setTimeout(r, 15));

            // 4. Assert B gets no error, B onError not called, B state unchanged
            assert.strictEqual(bErrorCalled, false);
            assert.strictEqual(elsB.status.hidden, true);
            assert.strictEqual(elsB.status.textContent, '');
            assert.strictEqual(raceModal.isOpen(), true);
            assert.strictEqual(raceModal.isSubmitting(), false);
            assert.strictEqual(elsB.textarea.value, 'B content');
        });

        test('3. same-comment new generation: old 201 cannot clear/close/reset new A draft', async () => {
            let resolveOldA;

            const controlledFetch = async () => {
                return new Promise(resolve => {
                    resolveOldA = resolve;
                });
            };

            const raceModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: controlledFetch
            });

            // 1. Open A generation 1
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            const els = raceModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Draft 1 in old generation';

            // 2. Submit A pending
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            assert.strictEqual(raceModal.isSubmitting(), true);

            // 3. Close A passively
            raceModal.close(false);
            assert.strictEqual(raceModal.isOpen(), false);

            // 4. Reopen A (starts NEW generation for same comment)
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            assert.strictEqual(raceModal.isOpen(), true);

            // 5. Author new A draft in new generation
            els.modal.querySelector('input[value="OTHER"]').checked = true;
            els.textarea.value = 'Brand new draft 2 in new generation';
            raceModal.flushDraft();

            // 6. Old A returns 201 from previous generation
            resolveOldA({
                status: 201,
                json: async () => ({ reportId: 'stale-report' })
            });
            await new Promise(r => setTimeout(r, 15));

            // 7. Assert old 201 did NOT clear new A draft, did NOT close modal, did NOT reset form
            assert.strictEqual(raceModal.isOpen(), true);
            assert.strictEqual(els.modal.querySelector('input[value="OTHER"]').checked, true);
            assert.strictEqual(els.textarea.value, 'Brand new draft 2 in new generation');

            // Storage still contains new draft 2
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));
            const saved = JSON.parse(mockStorage.getItem(keyA)).value;
            assert.strictEqual(JSON.parse(saved).description, 'Brand new draft 2 in new generation');
        });

        test('4. stale debounce: A accepted success before timer -> B saved -> stale A timer cannot alter B', async () => {
            // 1. Open A and schedule debounce via typing
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const elsA = modal.getElements();
            elsA.modal.querySelector('input[value="SPAM"]').checked = true;
            elsA.textarea.value = 'Text A scheduled for debounce';
            elsA.textarea.dispatchEvent('input'); // starts 400ms debounce timer for A

            // 2. A accepted success before timer fires (submit succeeds immediately)
            elsA.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 15));
            assert.strictEqual(modal.isOpen(), false);

            // A draft should be removed
            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.strictEqual(mockStorage.getItem(keyA), null);

            // 3. Open B and save B
            modal.open({ commentId: COMMENT_B, submitUrl: SUBMIT_URL_B });
            const elsB = modal.getElements();
            elsB.modal.querySelector('input[value="HARASSMENT"]').checked = true;
            elsB.textarea.value = 'Text B authored in new session';
            modal.flushDraft();

            const keyB = CommentReportModal.getReportDraftKey(COMMENT_B);
            assert.ok(mockStorage.getItem(keyB));

            // 4. Wait > 400ms so old A debounce timer expires if it were still running
            await new Promise(r => setTimeout(r, 450));

            // 5. Assert B draft is intact and was not altered by A
            const draftB = JSON.parse(mockStorage.getItem(keyB)).value;
            assert.strictEqual(JSON.parse(draftB).description, 'Text B authored in new session');
            assert.strictEqual(JSON.parse(draftB).reason, 'HARASSMENT');

            // And A draft was not resurrected
            assert.strictEqual(mockStorage.getItem(keyA), null);
            assert.strictEqual(elsB.textarea.value, 'Text B authored in new session');
        });

        test('5. current-generation success regression: clears draft, closes modal, and notifies caller', async () => {
            let successContext = null;
            let eventDetail = null;

            fakeDoc.addEventListener('kiemlai:comment-report-submitted', (e) => {
                eventDetail = e.detail;
            });

            modal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onSuccess: (data, ctx) => {
                    successContext = ctx;
                }
            });

            const els = modal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Valid spam report';
            modal.flushDraft();

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 15));

            // Modal closed, form reset, draft removed
            assert.strictEqual(modal.isOpen(), false);
            assert.strictEqual(modal.isSubmitting(), false);
            assert.strictEqual(mockStorage.getItem(keyA), null);

            // Context delivered to callback and event
            assert.ok(successContext);
            assert.strictEqual(successContext.commentId, COMMENT_A);
            assert.strictEqual(successContext.submitUrl, SUBMIT_URL_A);
            assert.ok(eventDetail);
            assert.strictEqual(eventDetail.commentId, COMMENT_A);
        });

        test('6. current-generation failure regression: preserves draft, keeps modal open, and notifies caller', async () => {
            let errorCaught = null;
            let errorCtx = null;

            const failingFetch = async () => ({
                status: 409,
                json: async () => ({ error: 'Conflict' })
            });

            const failModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: failingFetch
            });

            failModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onError: (err, ctx) => {
                    errorCaught = err;
                    errorCtx = ctx;
                }
            });

            const els = failModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Preserve this on 409';
            failModal.flushDraft();

            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 15));

            // Modal stays open, submit re-enabled, draft preserved
            assert.strictEqual(failModal.isOpen(), true);
            assert.strictEqual(failModal.isSubmitting(), false);
            assert.strictEqual(els.submitBtn.disabled, false);

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));
            assert.ok(els.status.textContent.includes('đang chờ xử lý'));

            assert.ok(errorCaught);
            assert.strictEqual(errorCtx.status, 409);
            assert.strictEqual(errorCtx.commentId, COMMENT_A);
        });

        test('7. repeated open/close does not accumulate pagehide/keydown listeners', () => {
            // Perform 10 open and close cycles
            for (let i = 0; i < 10; i++) {
                modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
                modal.close(false);
            }

            // When closed, exactly 0 keydown and 0 pagehide listeners
            const keydownListenersClosed = (fakeDoc.listeners['keydown'] || []).length;
            const pagehideListenersClosed = (fakeDoc.defaultView.listeners['pagehide'] || []).length;
            assert.strictEqual(keydownListenersClosed, 0, `Expected 0 keydown listeners when closed, found ${keydownListenersClosed}`);
            assert.strictEqual(pagehideListenersClosed, 0, `Expected 0 pagehide listeners when closed, found ${pagehideListenersClosed}`);

            // When open, exactly 1 keydown and 1 pagehide listener
            modal.open({ commentId: COMMENT_A, submitUrl: SUBMIT_URL_A });
            const keydownListenersOpen = (fakeDoc.listeners['keydown'] || []).length;
            const pagehideListenersOpen = (fakeDoc.defaultView.listeners['pagehide'] || []).length;
            assert.strictEqual(keydownListenersOpen, 1, `Expected 1 keydown listener when open, found ${keydownListenersOpen}`);
            assert.strictEqual(pagehideListenersOpen, 1, `Expected 1 pagehide listener when open, found ${pagehideListenersOpen}`);

            // Close once more -> returns to 0
            modal.close(false);
            assert.strictEqual((fakeDoc.listeners['keydown'] || []).length, 0);
            assert.strictEqual((fakeDoc.defaultView.listeners['pagehide'] || []).length, 0);
        });

        test('8. post-headers cross-comment race: B stays open, form/draft unchanged when A response.json resolves late', async () => {
            let resolveJsonA;
            let bSuccessCalled = false;
            let aSuccessCalled = false;

            const controlledFetch = async (url) => {
                if (url === SUBMIT_URL_A) {
                    return {
                        status: 201,
                        json: () => new Promise(resolve => {
                            resolveJsonA = resolve;
                        })
                    };
                }
                return {
                    status: 201,
                    json: async () => ({ reportId: 'bbbbbbbb-report' })
                };
            };

            const raceModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: controlledFetch
            });

            // 1. Open A and start submit A
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A,
                onSuccess: () => { aSuccessCalled = true; }
            });
            const elsA = raceModal.getElements();
            elsA.modal.querySelector('input[value="SPAM"]').checked = true;
            elsA.textarea.value = 'Report text A';
            elsA.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });

            // Allow handleSubmit to execute through fetch and reach await response.json()
            await new Promise(r => setTimeout(r, 10));

            // 2. While A's response.json() is pending, open B
            raceModal.open({
                commentId: COMMENT_B,
                submitUrl: SUBMIT_URL_B,
                onSuccess: () => { bSuccessCalled = true; }
            });
            const elsB = raceModal.getElements();
            elsB.modal.querySelector('input[value="HARASSMENT"]').checked = true;
            elsB.textarea.value = 'Report text B';
            raceModal.flushDraft();

            // 3. Resolve A's pending response.json()
            resolveJsonA({ reportId: 'aaaaaaaa-report' });
            await new Promise(r => setTimeout(r, 15));

            // 4. Assert: B remains open, B form unchanged, B draft unchanged, B callback not called for A
            assert.strictEqual(raceModal.isOpen(), true);
            assert.strictEqual(raceModal.getActiveCommentId(), COMMENT_B);
            assert.strictEqual(raceModal.isSubmitting(), false);
            assert.strictEqual(elsB.modal.querySelector('input[value="HARASSMENT"]').checked, true);
            assert.strictEqual(elsB.textarea.value, 'Report text B');
            assert.strictEqual(bSuccessCalled, false);

            // Verify B draft in storage remains intact
            const keyB = CommentReportModal.getReportDraftKey(COMMENT_B);
            const draftB = JSON.parse(mockStorage.getItem(keyB)).value;
            assert.strictEqual(JSON.parse(draftB).description, 'Report text B');
        });

        test('9. post-headers same-comment reopen race: reopened A stays open, new draft intact when old response.json resolves late', async () => {
            let resolveJsonOldA;

            const controlledFetch = async () => {
                return {
                    status: 201,
                    json: () => new Promise(resolve => {
                        resolveJsonOldA = resolve;
                    })
                };
            };

            const raceModal = CommentReportModal.createCommentReportModal({
                doc: fakeDoc,
                draftStore: draftStore,
                fetch: controlledFetch
            });

            // 1. Open A generation 1
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            const els = raceModal.getElements();
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.textarea.value = 'Draft 1 in old generation';

            // 2. Submit A: fetch resolves 201 immediately, but response.json() remains pending
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 10));

            // 3. Close A passively
            raceModal.close(false);
            assert.strictEqual(raceModal.isOpen(), false);

            // 4. Reopen A as a NEW generation
            raceModal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            assert.strictEqual(raceModal.isOpen(), true);

            // 5. Author/save NEW A draft in new generation
            els.modal.querySelector('input[value="OTHER"]').checked = true;
            els.textarea.value = 'Brand new draft 2 in post-response race';
            raceModal.flushDraft();

            // 6. Resolve old A's response.json()
            resolveJsonOldA({ reportId: 'stale-report' });
            await new Promise(r => setTimeout(r, 15));

            // 7. Assert: reopened A remains open, new form unchanged, new A draft remains in storage
            assert.strictEqual(raceModal.isOpen(), true);
            assert.strictEqual(els.modal.querySelector('input[value="OTHER"]').checked, true);
            assert.strictEqual(els.textarea.value, 'Brand new draft 2 in post-response race');

            const keyA = CommentReportModal.getReportDraftKey(COMMENT_A);
            assert.ok(mockStorage.getItem(keyA));
            const saved = JSON.parse(mockStorage.getItem(keyA)).value;
            assert.strictEqual(JSON.parse(saved).description, 'Brand new draft 2 in post-response race');
        });
    });

    describe('11. Manual DOM Display Lifecycle', () => {
        test('open, passive close, reopen, and submit success correctly manage hidden, class show, and style.display', async () => {
            const doc = new FakeDocument();
            const modal = CommentReportModal.createCommentReportModal({
                doc: doc,
                draftStore: draftStore,
                fetch: async () => ({
                    status: 201,
                    json: async () => ({ id: 'rep-1', status: 'PENDING' })
                })
            });

            // 1. Initial open
            const opened = modal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            assert.strictEqual(opened, true);
            assert.strictEqual(modal.isOpen(), true);

            const els = modal.getElements();
            assert.strictEqual(els.modal.hidden, false);
            assert.strictEqual(els.modal.hasAttribute('hidden'), false);
            assert.strictEqual(els.modal.classList.contains('show'), true);
            assert.strictEqual(els.modal.style.display, 'block');

            assert.ok(els.backdrop, 'Backdrop element should exist');
            assert.strictEqual(els.backdrop.hidden, false);
            assert.strictEqual(els.backdrop.hasAttribute('hidden'), false);
            assert.strictEqual(els.backdrop.classList.contains('show'), true);
            assert.strictEqual(els.backdrop.style.display, 'block');
            assert.strictEqual(els.backdrop.style.zIndex, '0', 'Backdrop must have local zIndex 0');

            const dialogEl = els.dialog || els.modal.querySelector('.modal-dialog');
            assert.ok(dialogEl, 'Modal dialog element must exist');
            assert.strictEqual(dialogEl.style.position, 'relative', 'Dialog must have position relative');
            assert.strictEqual(dialogEl.style.zIndex, '1', 'Dialog must have local zIndex 1');

            // 2. Passive close
            modal.close(false);
            assert.strictEqual(modal.isOpen(), false);
            assert.strictEqual(els.modal.hidden, true);
            assert.strictEqual(els.modal.hasAttribute('hidden'), true);
            assert.strictEqual(els.modal.classList.contains('show'), false);
            assert.strictEqual(els.modal.style.display, 'none');

            assert.strictEqual(els.backdrop.hidden, true);
            assert.strictEqual(els.backdrop.hasAttribute('hidden'), true);
            assert.strictEqual(els.backdrop.classList.contains('show'), false);
            assert.strictEqual(els.backdrop.style.display, 'none');

            // 3. Reopen must become visible again
            const reopened = modal.open({
                commentId: COMMENT_A,
                submitUrl: SUBMIT_URL_A
            });
            assert.strictEqual(reopened, true);
            assert.strictEqual(modal.isOpen(), true);
            assert.strictEqual(els.modal.hidden, false);
            assert.strictEqual(els.modal.hasAttribute('hidden'), false);
            assert.strictEqual(els.modal.classList.contains('show'), true);
            assert.strictEqual(els.modal.style.display, 'block');

            assert.strictEqual(els.backdrop.hidden, false);
            assert.strictEqual(els.backdrop.hasAttribute('hidden'), false);
            assert.strictEqual(els.backdrop.classList.contains('show'), true);
            assert.strictEqual(els.backdrop.style.display, 'block');
            assert.strictEqual(els.backdrop.style.zIndex, '0', 'Backdrop must retain zIndex 0 on reopen');
            assert.strictEqual(dialogEl.style.position, 'relative');
            assert.strictEqual(dialogEl.style.zIndex, '1');

            // 4. Successful submit leaves it hidden / display none
            els.modal.querySelector('input[value="SPAM"]').checked = true;
            els.form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
            await new Promise(r => setTimeout(r, 20));

            assert.strictEqual(modal.isOpen(), false);
            assert.strictEqual(els.modal.hidden, true);
            assert.strictEqual(els.modal.hasAttribute('hidden'), true);
            assert.strictEqual(els.modal.classList.contains('show'), false);
            assert.strictEqual(els.modal.style.display, 'none');

            assert.strictEqual(els.backdrop.hidden, true);
            assert.strictEqual(els.backdrop.hasAttribute('hidden'), true);
            assert.strictEqual(els.backdrop.classList.contains('show'), false);
            assert.strictEqual(els.backdrop.style.display, 'none');
        });
    });
});
