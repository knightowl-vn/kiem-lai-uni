const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const composerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-composer.js'));

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
        this.disabled = false;
        this.value = attributes.value || '';
        this._textContent = '';
        this.isFocused = false;
        this.ownerDocument = null;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
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

    focus() {
        this.isFocused = true;
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        child.ownerDocument = this.ownerDocument;
        this.childNodes.push(child);
        return child;
    }

    setAttribute(name, value) {
        const valStr = String(value);
        this.attributes[name] = valStr;
        if (name === 'class') {
            this.classList.classes = new Set(valStr.split(/\s+/).filter(Boolean));
        }
        if (name === 'hidden') {
            this.hidden = true;
        }
        if (name === 'disabled') {
            this.disabled = true;
        }
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
        if (name === 'hidden') {
            this.hidden = false;
        }
        if (name === 'disabled') {
            this.disabled = false;
        }
    }

    hasAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }

    dispatchEvent(evt) {
        const eventType = typeof evt === 'string' ? evt : evt.type;
        const eventObj = typeof evt === 'string' ? { type: evt, target: this } : evt;
        if (!eventObj.target) {
            eventObj.target = this;
        }
        if (this.listeners[eventType]) {
            for (const listener of [...this.listeners[eventType]]) {
                listener.call(this, eventObj);
            }
        }
        return true;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this, selector);
    }
}

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    const bracketIdx = sel.indexOf('[');
    if (bracketIdx !== -1 && sel.endsWith(']')) {
        const tag = bracketIdx > 0 ? sel.slice(0, bracketIdx) : null;
        if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
            return false;
        }
        const raw = sel.slice(bracketIdx + 1, -1);
        const eqIdx = raw.indexOf('=');
        if (eqIdx === -1) {
            return el.getAttribute(raw) !== null;
        }
        const name = raw.slice(0, eqIdx);
        const val = raw.slice(eqIdx + 1).replace(/^["']|["']$/g, '');
        return el.getAttribute(name) === val;
    }
    let tag = null;
    let classPart = sel;
    if (!sel.startsWith('.')) {
        const dotIdx = sel.indexOf('.');
        if (dotIdx !== -1) {
            tag = sel.slice(0, dotIdx);
            classPart = sel.slice(dotIdx);
        } else {
            return el.tagName.toLowerCase() === sel.toLowerCase();
        }
    }
    if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
        return false;
    }
    const classes = classPart.split('.').filter(Boolean);
    return classes.every(c => el.classList.contains(c));
}

function querySelectorAllDeep(root, selector) {
    const results = [];
    function traverse(node) {
        if (!node || !node.childNodes) return;
        for (const child of node.childNodes) {
            if (matchesSingleSelector(child, selector)) {
                results.push(child);
            }
            traverse(child);
        }
    }
    traverse(root);
    return results;
}

class FakeDocument {
    constructor() {
        this.elementsById = new Map();
        this.listeners = {};
        this.documentElement = new FakeElement('html');
        this.documentElement.ownerDocument = this;
        this.head = new FakeElement('head');
        this.head.ownerDocument = this;
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.documentElement.appendChild(this.head);
        this.documentElement.appendChild(this.body);
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        return this.elementsById.get(id) || null;
    }

    registerElement(id, el) {
        el.setAttribute('id', id);
        el.ownerDocument = this;
        this.elementsById.set(id, el);
    }

    querySelector(selector) {
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
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }

    dispatchEvent(evt) {
        const eventType = typeof evt === 'string' ? evt : evt.type;
        const eventObj = typeof evt === 'string' ? { type: evt, target: this } : evt;
        if (!eventObj.target) {
            eventObj.target = this;
        }
        if (this.listeners[eventType]) {
            for (const listener of [...this.listeners[eventType]]) {
                listener.call(this, eventObj);
            }
        }
        return true;
    }
}

function createComposerFixture(chapterId = 'chap-test-123') {
    const doc = new FakeDocument();

    // CSRF meta tags
    const csrfTokenMeta = doc.createElement('meta');
    csrfTokenMeta.setAttribute('name', '_csrf');
    csrfTokenMeta.setAttribute('content', 'test-csrf-token-abc');
    doc.head.appendChild(csrfTokenMeta);

    const csrfHeaderMeta = doc.createElement('meta');
    csrfHeaderMeta.setAttribute('name', '_csrf_header');
    csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
    doc.head.appendChild(csrfHeaderMeta);

    // Section container with data-chapter-id
    const section = doc.createElement('section');
    doc.registerElement(composerModule.COMMENTS_SECTION_ID, section);
    section.setAttribute('data-chapter-id', chapterId);
    doc.body.appendChild(section);

    // Composer elements
    const form = doc.createElement('form');
    doc.registerElement(composerModule.COMPOSER_FORM_ID, form);
    section.appendChild(form);

    const input = doc.createElement('textarea');
    doc.registerElement(composerModule.INPUT_ID, input);
    form.appendChild(input);

    const statusEl = doc.createElement('div');
    doc.registerElement(composerModule.STATUS_ID, statusEl);
    form.appendChild(statusEl);

    const submitBtn = doc.createElement('button');
    submitBtn.setAttribute('type', 'submit');
    doc.registerElement(composerModule.SUBMIT_ID, submitBtn);
    form.appendChild(submitBtn);

    return {
        doc,
        section,
        form,
        input,
        statusEl,
        submitBtn,
        csrfTokenMeta,
        csrfHeaderMeta
    };
}

describe('MS-05E5H2F1 Bottom Chapter Root Comment Composer', () => {
    afterEach(() => {
        composerModule.destroy();
    });

    test('1. missing composer DOM exits safely without errors', () => {
        const emptyDoc = new FakeDocument();
        const instance = composerModule.init(emptyDoc);
        assert.ok(instance);
        // Form submission or chapter change does not throw
        assert.doesNotThrow(() => instance.handleSubmit());
        assert.doesNotThrow(() => instance.handleChapterChanged());
    });

    test('2. blank or whitespace-only body prevents POST and shows validation message with focused input', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-blank');
        let postCalled = false;

        composerModule.init(doc, {
            fetch: () => {
                postCalled = true;
                return Promise.resolve({ ok: true, status: 201 });
            }
        });

        input.value = '   \n\t  ';
        await composerModule.handleSubmit();

        assert.strictEqual(postCalled, false);
        assert.strictEqual(statusEl.textContent, 'Vui lòng nhập nội dung bình luận.');
        assert.ok(statusEl.classList.contains('is-error'));
        assert.strictEqual(input.isFocused, true);
        assert.strictEqual(composerModule.isSubmitting(), false);
    });

    test('3 to 7. canonical POST contract: URL, trimmed body, no anchor data, CSRF headers', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-valid-1');
        let capturedUrl = null;
        let capturedOptions = null;

        composerModule.init(doc, {
            fetch: (url, opts) => {
                capturedUrl = url;
                capturedOptions = opts;
                return Promise.resolve({
                    ok: true,
                    status: 201,
                    json: () => Promise.resolve({ commentId: 'new-root-uuid-999' })
                });
            }
        });

        input.value = '  Bình luận toàn bộ chương 1!  ';
        await composerModule.handleSubmit();

        // 4. URL
        assert.strictEqual(capturedUrl, '/api/novel/chapters/c-valid-1/comments');

        // Method & Headers
        assert.strictEqual(capturedOptions.method, 'POST');
        assert.strictEqual(capturedOptions.headers['Content-Type'], 'application/json');
        // 7. CSRF Header
        assert.strictEqual(capturedOptions.headers['X-CSRF-TOKEN'], 'test-csrf-token-abc');

        // 3, 5, 6. Body contains only trimmed body, NO anchor, NO blockKey, NO contentVersion
        const parsedBody = JSON.parse(capturedOptions.body);
        assert.deepStrictEqual(parsedBody, { body: 'Bình luận toàn bộ chương 1!' });
        assert.strictEqual(parsedBody.anchor, undefined);
        assert.strictEqual(parsedBody.blockKey, undefined);
        assert.strictEqual(parsedBody.contentVersion, undefined);
        assert.strictEqual(parsedBody.author, undefined);
        assert.strictEqual(parsedBody.userId, undefined);
    });

    test('8. single-flight submission: double submit while pending issues only one POST', async () => {
        const { doc, input } = createComposerFixture('c-single-flight');
        let postCount = 0;
        let resolveFetch;

        composerModule.init(doc, {
            fetch: () => {
                postCount++;
                return new Promise(resolve => {
                    resolveFetch = resolve;
                });
            }
        });

        input.value = 'Hảo văn!';
        const firstSubmit = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);

        // Immediate second submit
        const secondSubmit = composerModule.handleSubmit();

        assert.strictEqual(postCount, 1);

        resolveFetch({
            ok: true,
            status: 201,
            json: () => Promise.resolve({ commentId: 'root-123' })
        });
        await firstSubmit;
        await secondSubmit;

        assert.strictEqual(postCount, 1);
        assert.strictEqual(composerModule.isSubmitting(), false);
    });

    test('9 & 10. POST failure preserves textarea draft and does not call feed refresh', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-post-fail');
        let refreshCalled = false;

        composerModule.init(doc, {
            fetch: () => Promise.resolve({ ok: false, status: 500 }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCalled = true;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Nội dung quan trọng không được mất khi lỗi';
        await composerModule.handleSubmit();

        // 9. Preserves draft
        assert.strictEqual(input.value, 'Nội dung quan trọng không được mất khi lỗi');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(statusEl.textContent, 'Không thể gửi bình luận. Vui lòng thử lại.');
        assert.ok(statusEl.classList.contains('is-error'));

        // 10. Does not call feed refresh
        assert.strictEqual(refreshCalled, false);
    });

    test('11 to 13. 201 success clears textarea, calls authoritative refresh exactly once, does not fabricate card', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-success-flow');
        let refreshCount = 0;

        composerModule.init(doc, {
            fetch: () => Promise.resolve({
                ok: true,
                status: 201,
                json: () => Promise.resolve({ commentId: 'root-success-id' })
            }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve({ items: [] });
                }
            }
        });

        input.value = 'Bình luận tuyệt vời!';
        await composerModule.handleSubmit();

        // 11. Clears textarea
        assert.strictEqual(input.value, '');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(statusEl.textContent, '');

        // 12. Calls authoritative page-0 refresh exactly once
        assert.strictEqual(refreshCount, 1);

        // 13. Does not locally fabricate root card: composer module itself performs 0 DOM card insertions
        assert.strictEqual(doc.querySelectorAll('.novel-comment').length, 0);
    });

    test('14 & 15. refresh failure after 201 success: does NOT retry POST, leaves textarea cleared, reports error', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-refresh-fail');
        let postCalls = 0;

        composerModule.init(doc, {
            fetch: () => {
                postCalls++;
                return Promise.resolve({
                    ok: true,
                    status: 201,
                    json: () => Promise.resolve({ commentId: 'root-saved-on-server' })
                });
            },
            commentsModule: {
                refreshFromPageZero: () => {
                    return Promise.reject(new Error('Network error during GET feed'));
                }
            }
        });

        input.value = 'Đã lưu trên DB nhưng mạng lag khi tải feed';
        await composerModule.handleSubmit();

        // 14. Does not retry POST
        assert.strictEqual(postCalls, 1);
        // Textarea remains cleared (comment was successfully created)
        assert.strictEqual(input.value, '');
        assert.strictEqual(input.disabled, false);

        // 15. Reports specific status
        assert.strictEqual(statusEl.textContent, 'Bình luận đã được gửi, nhưng chưa thể tải lại danh sách.');
        assert.ok(statusEl.classList.contains('is-error'));
    });

    test('16. chapter ID is resolved dynamically from current bottom section element', async () => {
        const { doc, section, input } = createComposerFixture('c-initial');
        let capturedChapterId = null;

        composerModule.init(doc, {
            fetch: (url) => {
                capturedChapterId = url.split('/')[4];
                return Promise.resolve({
                    ok: true,
                    status: 201,
                    json: () => Promise.resolve({ commentId: 'root-id' })
                });
            },
            commentsModule: { refreshFromPageZero: () => Promise.resolve() }
        });

        // Dynamic update of section data-chapter-id
        section.setAttribute('data-chapter-id', 'c-updated-999');

        input.value = 'Test dynamic chapter ID';
        await composerModule.handleSubmit();

        assert.strictEqual(capturedChapterId, 'c-updated-999');
    });

    test('17. Chapter A pending POST resolving after chapter change to B: stale A completion cannot mutate B composer or refresh B', async () => {
        const { doc, section, input, statusEl } = createComposerFixture('chapter-A');
        let resolvePostA;
        let refreshCalledOnB = false;

        composerModule.init(doc, {
            fetch: () => new Promise(resolve => {
                resolvePostA = resolve;
            }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCalledOnB = true;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Draft for Chapter A';
        const submitPromise = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);

        // Transition to Chapter B happens before A resolves
        section.setAttribute('data-chapter-id', 'chapter-B');
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'chapter-B' } });

        // Composer reset on chapter change
        assert.strictEqual(composerModule.isSubmitting(), false);
        input.value = 'Draft for Chapter B';

        // Now late Post A resolves
        resolvePostA({
            ok: true,
            status: 201,
            json: () => Promise.resolve({ commentId: 'post-A-id' })
        });
        await submitPromise;

        // Post A response was ignored: Chapter B draft is NOT cleared, B feed is NOT refreshed
        assert.strictEqual(input.value, 'Draft for Chapter B');
        assert.strictEqual(refreshCalledOnB, false);
        assert.strictEqual(statusEl.textContent, '');
    });

    test('18. destroy / re-init invalidates stale pending completion across token generations', async () => {
        const { doc, input, statusEl, submitBtn } = createComposerFixture('c-aba-token');
        let resolvePostA;
        let resolvePostB;
        let refreshCount = 0;

        composerModule.init(doc, {
            fetch: () => new Promise(resolve => {
                resolvePostA = resolve;
            }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Draft A';
        const submitPromiseA = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);

        // Component destroyed while request A is in flight
        composerModule.destroy();
        assert.strictEqual(composerModule.isSubmitting(), false);

        // Re-init same module/DOM and start request B
        composerModule.init(doc, {
            fetch: () => new Promise(resolve => {
                resolvePostB = resolve;
            }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Draft B';
        const submitPromiseB = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);

        // Old request A resolves late
        resolvePostA({
            ok: true,
            status: 201
        });
        await submitPromiseA;

        // Proves old A cannot clear B draft, change B status, toggle controls, or invoke refresh
        assert.strictEqual(input.value, 'Draft B');
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(refreshCount, 0);
        assert.strictEqual(composerModule.isSubmitting(), true);

        // Request B resolves normally
        resolvePostB({
            ok: true,
            status: 201
        });
        await submitPromiseB;

        // B authoritatively succeeds
        assert.strictEqual(input.value, '');
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        assert.strictEqual(statusEl.textContent, '');
        assert.strictEqual(refreshCount, 1);
        assert.strictEqual(composerModule.isSubmitting(), false);
    });

    test('19. HTTP 201 Created semantics: succeeds even with missing, empty, or malformed JSON body', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-201-bodyless');
        let refreshCount = 0;
        let postCount = 0;

        composerModule.init(doc, {
            fetch: () => {
                postCount++;
                return Promise.resolve({
                    ok: true,
                    status: 201
                    // no json() method
                });
            },
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Draft without json function';
        await composerModule.handleSubmit();

        // 201 means created on server: draft cleared, POST exactly once, refresh called, no error status
        assert.strictEqual(input.value, '');
        assert.strictEqual(postCount, 1);
        assert.strictEqual(refreshCount, 1);
        assert.strictEqual(statusEl.textContent, '');
        assert.strictEqual(doc.querySelectorAll('.novel-comment').length, 0);
    });
});
