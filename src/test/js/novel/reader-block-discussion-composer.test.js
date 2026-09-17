const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const ComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-composer.js'));

const {
    COMPOSER_FORM_ID,
    INPUT_ID,
    STATUS_ID,
    SUBMIT_ID,
    EVENT_DISCUSSION_REQUESTED,
    EVENT_DISCUSSION_LOADED,
    EVENT_DISCUSSION_CLOSED,
    EVENT_CHAPTER_CHANGED,
    initReaderBlockDiscussionComposer,
    resetComposerState,
    handleSubmit,
    handleDiscussionRequested,
    handleDiscussionLoaded,
    handleDiscussionClosed,
    handleChapterChanged,
    isMutationContextCurrent,
    resolveDrawerModule,
    getAuthoritativeContext,
    isSubmitting
} = ComposerModule;

// ============================================================================
// Lightweight DOM Test Fixture Helpers
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
        this._textContent = '';
        this.isFocused = false;
        this.disabled = !!attributes.disabled;
        this._value = attributes.value !== undefined ? String(attributes.value) : '';

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get value() {
        return this._value;
    }

    set value(val) {
        this._value = String(val);
    }

    get textContent() {
        return this._textContent;
    }

    set textContent(val) {
        this._textContent = String(val);
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
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
        if (name === 'disabled') {
            this.disabled = true;
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
        if (name === 'disabled') {
            this.disabled = false;
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
        const promises = [];
        for (const fn of [...handlers]) {
            const res = fn.call(this, evt);
            if (res && typeof res.then === 'function') {
                promises.push(res);
            }
        }
        if (promises.length > 0) {
            return Promise.all(promises).then(() => !evt.defaultPrevented);
        }
        return !evt.defaultPrevented;
    }

    focus() {
        this.isFocused = true;
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
    }
}

class FakeDocument {
    constructor() {
        this.readyState = 'complete';
        this.listeners = {};
        this.elementsById = {};
        this.metaTags = [];
        this.activeElement = null;
        this.defaultView = this;
    }

    getElementById(id) {
        return this.elementsById[id] || null;
    }

    registerElement(id, el) {
        el.ownerDocument = this;
        this.elementsById[id] = el;
        return el;
    }

    addMeta(name, content) {
        const meta = new FakeElement('meta', { name, content });
        meta.ownerDocument = this;
        this.metaTags.push(meta);
        return meta;
    }

    querySelector(selector) {
        if (selector === 'meta[name="_csrf"]') {
            return this.metaTags.find(m => m.getAttribute('name') === '_csrf') || null;
        }
        if (selector === 'meta[name="_csrf_header"]') {
            return this.metaTags.find(m => m.getAttribute('name') === '_csrf_header') || null;
        }
        if (selector.startsWith('#')) {
            return this.getElementById(selector.slice(1));
        }
        return null;
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
        const promises = [];
        for (const fn of [...handlers]) {
            const res = fn.call(this, evt);
            if (res && typeof res.then === 'function') {
                promises.push(res);
            }
        }
        if (promises.length > 0) {
            return Promise.all(promises).then(() => !evt.defaultPrevented);
        }
        return !evt.defaultPrevented;
    }
}

function createComposerFixture(options = {}) {
    const doc = new FakeDocument();
    if (!options.omitCsrf) {
        doc.addMeta('_csrf', options.csrfToken || 'test-csrf-token-123');
        doc.addMeta('_csrf_header', options.csrfHeader || 'X-CSRF-TOKEN');
    }

    if (!options.anonymous) {
        const form = new FakeElement('form', { id: COMPOSER_FORM_ID });
        const input = new FakeElement('textarea', { id: INPUT_ID, disabled: 'true' });
        const statusEl = new FakeElement('div', { id: STATUS_ID });
        const submitBtn = new FakeElement('button', { id: SUBMIT_ID, type: 'submit', disabled: 'true' });

        form.appendChild(input);
        form.appendChild(statusEl);
        form.appendChild(submitBtn);

        doc.registerElement(COMPOSER_FORM_ID, form);
        doc.registerElement(INPUT_ID, input);
        doc.registerElement(STATUS_ID, statusEl);
        doc.registerElement(SUBMIT_ID, submitBtn);

        return { doc, form, input, statusEl, submitBtn };
    }

    return { doc, form: null, input: null, statusEl: null, submitBtn: null };
}

// ============================================================================
// Test Suite: MS-05E5G3 Novel Block Discussion Root Comment Composer
// ============================================================================

describe('MS-05E5G3 Novel Block Discussion Root Comment Composer', () => {

    beforeEach(() => {
        resetComposerState();
    });

    test('1. initialization idempotent: multiple init calls bind listeners only once', () => {
        const { doc, form } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);
        const submitCount1 = (form.listeners['submit'] || []).length;
        assert.strictEqual(submitCount1, 1);

        // Call again
        initReaderBlockDiscussionComposer(doc);
        const submitCount2 = (form.listeners['submit'] || []).length;
        assert.strictEqual(submitCount2, 1);
    });

    test('2. authenticated composer binds exactly once', async () => {
        const { doc, form, input } = createComposerFixture();
        let postCount = 0;
        const mockFetch = async () => {
            postCount++;
            return {
                status: 201,
                json: async () => ({ commentId: 'c-test' })
            };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });

        // Enable composer with loaded event
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1', threadCount: 0 }
        });

        input.value = 'Hợp lệ';
        const submitEvt = { type: 'submit', defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
        await form.dispatchEvent(submitEvt);

        assert.strictEqual(postCount, 1);
    });

    test('3. missing composer markup safely no-ops for anonymous page', () => {
        const { doc } = createComposerFixture({ anonymous: true });
        assert.doesNotThrow(() => {
            initReaderBlockDiscussionComposer(doc);
        });
        // Dispatching events does not throw
        assert.doesNotThrow(() => {
            doc.dispatchEvent({ type: EVENT_DISCUSSION_REQUESTED });
            doc.dispatchEvent({
                type: EVENT_DISCUSSION_LOADED,
                detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1', threadCount: 0 }
            });
            doc.dispatchEvent({ type: EVENT_CHAPTER_CHANGED, detail: { chapterId: 'ch-2' } });
        });
    });

    test('4. composer initially disabled', () => {
        const { doc, input, submitBtn } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);

        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
    });

    test('5. provisional drawer open does NOT enable composer', () => {
        const { doc, input, submitBtn } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);

        doc.dispatchEvent({
            type: EVENT_DISCUSSION_REQUESTED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(getAuthoritativeContext(), null);
    });

    test('6. block-discussion-loaded enables composer', () => {
        const { doc, input, submitBtn } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);

        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 2, blockKey: 'blk-10', threadCount: 3 }
        });

        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.deepStrictEqual(getAuthoritativeContext(), {
            chapterId: 'ch-1',
            contentVersion: 2,
            blockKey: 'blk-10',
            threadCount: 3
        });
    });

    test('7. whitespace-only body does not POST', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        let postCalled = false;
        const mockFetch = async () => { postCalled = true; };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = '   \n \t  ';
        const submitEvt = { type: 'submit', defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
        await form.dispatchEvent(submitEvt);

        assert.strictEqual(postCalled, false);
        assert.ok(statusEl.textContent.includes('Vui lòng nhập'));
        assert.strictEqual(input.isFocused, true);
    });

    test('8. exact POST URL', async () => {
        const { doc, form, input } = createComposerFixture();
        let requestedUrl = '';
        const mockFetch = async (url) => {
            requestedUrl = url;
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-uuid/special', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận thử';
        const submitEvt = { type: 'submit', defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
        await form.dispatchEvent(submitEvt);

        assert.strictEqual(requestedUrl, '/api/novel/chapters/ch-uuid%2Fspecial/comments/inline');
    });

    test('9. method POST', async () => {
        const { doc, form, input } = createComposerFixture();
        let requestedMethod = '';
        const mockFetch = async (url, opts) => {
            requestedMethod = opts.method;
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(requestedMethod, 'POST');
    });

    test('10. Accept header application/json', async () => {
        const { doc, form, input } = createComposerFixture();
        let acceptHeader = '';
        const mockFetch = async (url, opts) => {
            acceptHeader = opts.headers['Accept'];
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(acceptHeader, 'application/json');
    });

    test('11. Content-Type application/json', async () => {
        const { doc, form, input } = createComposerFixture();
        let contentTypeHeader = '';
        const mockFetch = async (url, opts) => {
            contentTypeHeader = opts.headers['Content-Type'];
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(contentTypeHeader, 'application/json');
    });

    test('12. actual CSRF header/token read from meta', async () => {
        const { doc, form, input } = createComposerFixture({
            csrfToken: 'my-custom-csrf-token',
            csrfHeader: 'X-MY-CSRF'
        });
        let sentHeaders = null;
        const mockFetch = async (url, opts) => {
            sentHeaders = opts.headers;
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentHeaders['X-MY-CSRF'], 'my-custom-csrf-token');
    });

    test('13. exact JSON body matches contract', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 2, blockKey: 'blk-test-7' }
        });

        input.value = 'Kiếm khí tung hoành';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.deepStrictEqual(sentBody, {
            body: 'Kiếm khí tung hoành',
            anchor: {
                contentVersion: 2,
                blockKey: 'blk-test-7'
            }
        });
    });

    test('13b. raw body whitespace preservation: leading and trailing whitespace preserved exactly in JSON body', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = '  nội dung có khoảng trắng  ';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentBody.body, '  nội dung có khoảng trắng  ');
    });

    test('14. no actorUserId sent in request payload', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Kiểm tra actorUserId';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentBody.actorUserId, undefined);
        assert.strictEqual('actorUserId' in sentBody, false);
    });

    test('15. no TEXT_RANGE fields in request payload', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Kiểm tra text range';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentBody.startOffset, undefined);
        assert.strictEqual(sentBody.endOffset, undefined);
        assert.strictEqual(sentBody.textRange, undefined);
        assert.strictEqual(sentBody.anchor.startOffset, undefined);
        assert.strictEqual(sentBody.anchor.endOffset, undefined);
        assert.strictEqual(sentBody.anchor.textRange, undefined);
        assert.strictEqual(sentBody.anchor.canonicalText, undefined);
    });

    test('16. POST uses SERVER-authoritative contentVersion', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        // Event passes server version 42
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 42, blockKey: 'blk-1' }
        });

        input.value = 'Phiên bản 42';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentBody.anchor.contentVersion, 42);
    });

    test('17. POST uses current blockKey', async () => {
        const { doc, form, input } = createComposerFixture();
        let sentBody = null;
        const mockFetch = async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-target-99' }
        });

        input.value = 'Khóa đoạn 99';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(sentBody.anchor.blockKey, 'blk-target-99');
    });

    test('18. double submit produces one request', async () => {
        const { doc, form, input } = createComposerFixture();
        let callCount = 0;
        let resolveInFlight;
        const inFlightPromise = new Promise(r => { resolveInFlight = r; });

        const mockFetch = async () => {
            callCount++;
            await inFlightPromise;
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Double submit test';

        const p1 = form.dispatchEvent({ type: 'submit', preventDefault() {} });
        const p2 = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(callCount, 1);
        assert.strictEqual(isSubmitting(), true);

        resolveInFlight();
        await Promise.all([p1, p2]);

        assert.strictEqual(callCount, 1);
        assert.strictEqual(isSubmitting(), false);
    });

    test('19. in-flight UI disabled', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        let resolveInFlight;
        const inFlightPromise = new Promise(r => { resolveInFlight = r; });

        const mockFetch = async () => {
            await inFlightPromise;
            return { status: 201, json: async () => ({ commentId: 'c-1' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Testing in flight';
        const submitPromise = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // While pending:
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.ok(statusEl.textContent.includes('Đang gửi'));

        resolveInFlight();
        await submitPromise;

        // After success:
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('20. HTTP 201 + valid commentId clears textarea', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'new-comment-uuid' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận thành công';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, '');
        assert.strictEqual(statusEl.textContent, '');
    });

    test('21. success refreshes drawer exactly once', async () => {
        const { doc, form, input } = createComposerFixture();
        let drawerRefreshCount = 0;
        const mockDrawer = {
            refreshActiveDiscussion: async () => { drawerRefreshCount++; }
        };
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'c-1' })
        });

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            drawerModule: mockDrawer
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Refresh drawer test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(drawerRefreshCount, 1);
    });

    test('22. success refreshes indicators exactly once', async () => {
        const { doc, form, input } = createComposerFixture();
        let indicatorsRefreshCount = 0;
        const mockIndicators = {
            refreshChapterIndicators: async () => { indicatorsRefreshCount++; }
        };
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'c-1' })
        });

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            indicatorsModule: mockIndicators
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Refresh indicators test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(indicatorsRefreshCount, 1);
    });

    test('23. success does not manually increment count', async () => {
        const { doc, form, input } = createComposerFixture();
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'c-1' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1', threadCount: 5 }
        });

        input.value = 'Count invariant test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Authoritative context threadCount was NOT mutated manually (remains 5 until next loaded event)
        assert.strictEqual(getAuthoritativeContext().threadCount, 5);
    });

    test('24. drawer-refresh failure after 201 does NOT restore draft or claim POST failed', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        const mockDrawer = {
            refreshActiveDiscussion: async () => { throw new Error('Drawer refresh failed'); }
        };
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'c-1' })
        });

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            drawerModule: mockDrawer
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận hợp lệ';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Textarea remains cleared
        assert.strictEqual(input.value, '');
        // Does not show error claiming post failed
        assert.strictEqual(statusEl.textContent, '');
    });

    test('25. indicator-refresh failure after 201 does NOT claim POST failed', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        const mockIndicators = {
            refreshChapterIndicators: async () => { throw new Error('Indicator refresh failed'); }
        };
        const mockFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'c-1' })
        });

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            indicatorsModule: mockIndicators
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận hợp lệ 2';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Textarea remains cleared
        assert.strictEqual(input.value, '');
        assert.strictEqual(statusEl.textContent, '');
    });

    test('26. 409 preserves draft', async () => {
        const { doc, form, input } = createComposerFixture();
        const mockFetch = async () => ({
            status: 409,
            json: async () => ({ error: 'Conflict' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bản nháp quý giá';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Bản nháp quý giá');
    });

    test('27. 409 refreshes drawer', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        let drawerRefreshCalled = false;
        const mockDrawer = {
            refreshActiveDiscussion: async () => { drawerRefreshCalled = true; }
        };
        const mockFetch = async () => ({
            status: 409,
            json: async () => ({ error: 'Conflict' })
        });

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            drawerModule: mockDrawer
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Bình luận gặp 409';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(drawerRefreshCalled, true);
        assert.ok(statusEl.textContent.includes('thay đổi'));
    });

    test('28. 409 does not auto-resubmit', async () => {
        const { doc, form, input } = createComposerFixture();
        let postCount = 0;
        const mockFetch = async () => {
            postCount++;
            return { status: 409, json: async () => ({ error: 'Conflict' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(postCount, 1);
        assert.strictEqual(isSubmitting(), false);
    });

    test('29. 401/403 preserves draft and shows session message', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        let statusToReturn = 401;
        const mockFetch = async () => ({
            status: statusToReturn,
            json: async () => ({ error: 'Unauthorized' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        // Test 401
        input.value = 'Draft session test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft session test');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.ok(statusEl.textContent.includes('hết'));

        // Test 403
        statusToReturn = 403;
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft session test');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.ok(statusEl.textContent.includes('hết'));
    });

    test('30. 400 preserves draft and shows validation error', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        const mockFetch = async () => ({
            status: 400,
            json: async () => ({ error: 'Bad Request' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft 400 test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft 400 test');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.ok(statusEl.textContent.includes('không hợp lệ'));
    });

    test('31. 500 preserves draft and shows generic error', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        const mockFetch = async () => ({
            status: 500,
            json: async () => ({ error: 'Internal Error' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft 500 test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft 500 test');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.ok(statusEl.textContent.includes('Vui lòng thử lại'));
    });

    test('32. network error preserves draft and shows error', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        const mockFetch = async () => {
            throw new Error('Connection refused');
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft network test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft network test');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.ok(statusEl.textContent.includes('Vui lòng thử lại'));
    });

    test('33. error paths re-enable composer where safe (404 disables it)', async () => {
        const { doc, form, input, submitBtn, statusEl } = createComposerFixture();
        const mockFetch = async () => ({
            status: 404,
            json: async () => ({ error: 'Not Found' })
        });

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft 404 test';
        await form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(input.value, 'Draft 404 test');
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.ok(statusEl.textContent.includes('không còn khả dụng'));
    });

    test('34. Block A pending POST then Block B opens: late A completion does not mutate B composer', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        let resolvePostA;
        const postAPromise = new Promise(r => { resolvePostA = r; });
        let drawerRefreshes = 0;

        const mockFetch = async (url) => {
            if (url.includes('ch-1')) {
                await postAPromise;
                return { status: 201, json: async () => ({ commentId: 'c-A' }) };
            }
            return { status: 201, json: async () => ({ commentId: 'c-B' }) };
        };

        const mockDrawer = {
            refreshActiveDiscussion: async () => { drawerRefreshes++; }
        };

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            drawerModule: mockDrawer
        });

        // 1. Open Block A and submit
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-A' }
        });

        input.value = 'Comment for Block A';
        const submitA = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // 2. While POST A is pending, user opens Block B
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_REQUESTED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });

        // User starts typing draft for Block B
        input.value = 'Draft for Block B';

        // 3. Now POST A resolves with 201
        resolvePostA();
        await submitA;

        // Verify: Block B's draft is NOT cleared!
        assert.strictEqual(input.value, 'Draft for Block B');
        assert.strictEqual(input.disabled, false);
        // And drawer was NOT refreshed for the stale A block
        assert.strictEqual(drawerRefreshes, 0);
    });

    test('35. chapter-changed invalidates local composer context and clears draft', () => {
        const { doc, input, submitBtn, statusEl } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);

        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        input.value = 'Draft before chapter change';
        assert.strictEqual(input.disabled, false);

        doc.dispatchEvent({
            type: EVENT_CHAPTER_CHANGED,
            detail: { chapterId: 'ch-2' }
        });

        assert.strictEqual(input.value, '');
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(getAuthoritativeContext(), null);
    });

    test('36. no POST mutation occurs for anonymous/no-form fixture', async () => {
        const { doc } = createComposerFixture({ anonymous: true });
        let postCalled = false;
        const mockFetch = async () => { postCalled = true; };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });

        // Dispatch events
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        await handleSubmit({ preventDefault() {} });

        assert.strictEqual(postCalled, false);
        assert.strictEqual(getAuthoritativeContext(), null);
    });

    test('34b. A pending -> B loaded -> B can submit', async () => {
        const { doc, form, input } = createComposerFixture();
        let resolvePostA;
        const postAPromise = new Promise(r => { resolvePostA = r; });
        const postUrls = [];

        const mockFetch = async (url, opts) => {
            postUrls.push(url);
            const reqBody = JSON.parse((opts && opts.body) || '{}');
            if (reqBody.anchor && reqBody.anchor.blockKey === 'blk-A') {
                await postAPromise;
                return { status: 201, json: async () => ({ commentId: 'c-A' }) };
            }
            return { status: 201, json: async () => ({ commentId: 'c-B' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });

        // 1. Open Block A and submit
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-A' }
        });
        input.value = 'Comment A';
        const submitA = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(isSubmitting(), true);
        assert.strictEqual(postUrls.length, 1);

        // 2. Open Block B
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_REQUESTED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });

        // Assert isSubmitting() is NO LONGER blocking B
        assert.strictEqual(isSubmitting(), false);
        assert.strictEqual(input.disabled, false);

        // 3. Type B draft and submit B
        input.value = 'Comment B';
        const submitB = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Assert a B POST is actually issued
        assert.strictEqual(postUrls.length, 2);

        // 4. Resolve responses
        resolvePostA();
        await Promise.all([submitA, submitB]);

        // A completion must not clear or change B-owned state
        assert.strictEqual(isSubmitting(), false);
        assert.strictEqual(input.value, ''); // B itself succeeded and cleared its own draft
    });

    test('34c. stale A network failure after B active does not alter B composer', async () => {
        const { doc, form, input, statusEl } = createComposerFixture();
        let rejectPostA;
        const postAPromise = new Promise((_, rej) => { rejectPostA = rej; });

        const mockFetch = async (url, opts) => {
            const reqBody = JSON.parse((opts && opts.body) || '{}');
            if (reqBody.anchor && reqBody.anchor.blockKey === 'blk-A') {
                return postAPromise;
            }
            return { status: 201, json: async () => ({ commentId: 'c-B' }) };
        };

        initReaderBlockDiscussionComposer(doc, { fetchFn: mockFetch });

        // 1. Submit A
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-A' }
        });
        input.value = 'Comment A';
        const submitA = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // 2. Switch and load B
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_REQUESTED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });

        // Set B draft and status
        input.value = 'Active draft for B';
        statusEl.textContent = 'Trạng thái B';

        // 3. Reject A with network error
        rejectPostA(new Error('Network disconnected'));
        try {
            await submitA;
        } catch (_) {}

        // Assert B draft, status, and enabled state are NOT overwritten by A failure
        assert.strictEqual(input.value, 'Active draft for B');
        assert.strictEqual(statusEl.textContent, 'Trạng thái B');
        assert.strictEqual(input.disabled, false);
    });

    test('34d. response JSON race: A json resolves after switch to B; A cannot clear or mutate B', async () => {
        const { doc, form, input } = createComposerFixture();
        let resolveJsonA;
        const jsonAPromise = new Promise(r => { resolveJsonA = r; });
        let drawerRefreshes = 0;

        const mockFetch = async (url, opts) => {
            const reqBody = JSON.parse((opts && opts.body) || '{}');
            if (reqBody.anchor && reqBody.anchor.blockKey === 'blk-A') {
                return {
                    status: 201,
                    json: () => jsonAPromise
                };
            }
            return { status: 201, json: async () => ({ commentId: 'c-B' }) };
        };

        const mockDrawer = {
            refreshActiveDiscussion: async () => { drawerRefreshes++; }
        };

        initReaderBlockDiscussionComposer(doc, {
            fetchFn: mockFetch,
            drawerModule: mockDrawer
        });

        // 1. Submit A
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-A' }
        });
        input.value = 'Comment A';
        const submitA = form.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Wait a tick so fetch resolves and is waiting on jsonAPromise
        await new Promise(r => setTimeout(r, 10));

        // 2. Before json resolves, switch and load B
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_REQUESTED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-B' }
        });

        // Type B draft
        input.value = 'Draft for B during A json in-flight';

        // 3. Now resolve A json
        resolveJsonA({ commentId: 'c-A' });
        await submitA;

        // Assert: A cannot clear or mutate B
        assert.strictEqual(input.value, 'Draft for B during A json in-flight');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(drawerRefreshes, 0);
    });

    test('37. drawer closed lifecycle: kiemlai:block-discussion-closed invalidates context and disables composer', () => {
        const { doc, input, submitBtn } = createComposerFixture();
        initReaderBlockDiscussionComposer(doc);

        doc.dispatchEvent({
            type: EVENT_DISCUSSION_LOADED,
            detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
        });

        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.notStrictEqual(getAuthoritativeContext(), null);

        // Drawer closed
        doc.dispatchEvent({
            type: EVENT_DISCUSSION_CLOSED,
            detail: { chapterId: 'ch-1', blockKey: 'blk-1' }
        });

        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(getAuthoritativeContext(), null);
        assert.strictEqual(isSubmitting(), false);
    });

    test('38. production global window.NovelReaderBlockDiscussionDrawer is refreshed on 201 and 409 without mock injection', async () => {
        const priorWindow = global.window;
        let drawer201RefreshCount = 0;
        let drawer409RefreshCount = 0;

        try {
            global.window = {
                NovelReaderBlockDiscussionDrawer: {
                    refreshActiveDiscussion: async () => { drawer201RefreshCount++; }
                }
            };

            // Verify resolveDrawerModule returns production global when un-injected
            assert.strictEqual(resolveDrawerModule(), global.window.NovelReaderBlockDiscussionDrawer);

            // Test 201
            const { doc: doc201, form: form201, input: input201 } = createComposerFixture();
            initReaderBlockDiscussionComposer(doc201, {
                fetchFn: async () => ({
                    status: 201,
                    json: async () => ({ commentId: 'c-global-201' })
                })
            });
            doc201.dispatchEvent({
                type: EVENT_DISCUSSION_LOADED,
                detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
            });
            input201.value = 'Comment triggering global drawer refresh on 201';
            await form201.dispatchEvent({ type: 'submit', preventDefault() {} });
            assert.strictEqual(drawer201RefreshCount, 1, 'window.NovelReaderBlockDiscussionDrawer must be called on 201');

            resetComposerState();

            // Test 409
            global.window.NovelReaderBlockDiscussionDrawer.refreshActiveDiscussion = async () => { drawer409RefreshCount++; };
            const { doc: doc409, form: form409, input: input409 } = createComposerFixture();
            initReaderBlockDiscussionComposer(doc409, {
                fetchFn: async () => ({
                    status: 409,
                    json: async () => ({ error: 'Conflict' })
                })
            });
            doc409.dispatchEvent({
                type: EVENT_DISCUSSION_LOADED,
                detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
            });
            input409.value = 'Comment triggering global drawer refresh on 409';
            await form409.dispatchEvent({ type: 'submit', preventDefault() {} });
            assert.strictEqual(drawer409RefreshCount, 1, 'window.NovelReaderBlockDiscussionDrawer must be called on 409');
        } finally {
            if (typeof priorWindow === 'undefined') {
                delete global.window;
            } else {
                global.window = priorWindow;
            }
        }
    });

    test('39. production fallback window.KiemLai.NovelReaderBlockDiscussionDrawer is refreshed on 201 and 409 when root global is absent', async () => {
        const priorWindow = global.window;
        let drawer201RefreshCount = 0;
        let drawer409RefreshCount = 0;

        try {
            global.window = {
                KiemLai: {
                    NovelReaderBlockDiscussionDrawer: {
                        refreshActiveDiscussion: async () => { drawer201RefreshCount++; }
                    }
                }
            };

            // Verify resolveDrawerModule returns KiemLai fallback when root global absent
            assert.strictEqual(resolveDrawerModule(), global.window.KiemLai.NovelReaderBlockDiscussionDrawer);

            // Test 201
            const { doc: doc201, form: form201, input: input201 } = createComposerFixture();
            initReaderBlockDiscussionComposer(doc201, {
                fetchFn: async () => ({
                    status: 201,
                    json: async () => ({ commentId: 'c-kiemlai-201' })
                })
            });
            doc201.dispatchEvent({
                type: EVENT_DISCUSSION_LOADED,
                detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
            });
            input201.value = 'Comment triggering fallback drawer refresh on 201';
            await form201.dispatchEvent({ type: 'submit', preventDefault() {} });
            assert.strictEqual(drawer201RefreshCount, 1, 'window.KiemLai.NovelReaderBlockDiscussionDrawer must be called on 201');

            resetComposerState();

            // Test 409
            global.window.KiemLai.NovelReaderBlockDiscussionDrawer.refreshActiveDiscussion = async () => { drawer409RefreshCount++; };
            const { doc: doc409, form: form409, input: input409 } = createComposerFixture();
            initReaderBlockDiscussionComposer(doc409, {
                fetchFn: async () => ({
                    status: 409,
                    json: async () => ({ error: 'Conflict' })
                })
            });
            doc409.dispatchEvent({
                type: EVENT_DISCUSSION_LOADED,
                detail: { chapterId: 'ch-1', contentVersion: 1, blockKey: 'blk-1' }
            });
            input409.value = 'Comment triggering fallback drawer refresh on 409';
            await form409.dispatchEvent({ type: 'submit', preventDefault() {} });
            assert.strictEqual(drawer409RefreshCount, 1, 'window.KiemLai.NovelReaderBlockDiscussionDrawer must be called on 409');
        } finally {
            if (typeof priorWindow === 'undefined') {
                delete global.window;
            } else {
                global.window = priorWindow;
            }
        }
    });

});
