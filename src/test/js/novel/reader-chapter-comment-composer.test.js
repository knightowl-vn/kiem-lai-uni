const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const composerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-composer.js'));
const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
const draftsAdapter = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-drafts.js'));

function createMockDraftStore() {
    const storageMap = new Map();
    const mockStorage = {
        getItem: (k) => storageMap.has(k) ? storageMap.get(k) : null,
        setItem: (k, v) => { storageMap.set(k, String(v)); },
        removeItem: (k) => { storageMap.delete(k); },
        clear: () => { storageMap.clear(); }
    };
    return {
        store: EphemeralDraftStore.createStore({ storage: mockStorage }),
        storageMap
    };
}

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

describe('UX-DRAFT-01D1 Bottom Chapter Root Draft Persistence', () => {
    afterEach(() => {
        composerModule.destroy();
    });

    test('1. init restores exact draft for current chapter', () => {
        const { doc, input } = createComposerFixture('c-init-draft');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-init-draft');
        store.save(key, 'Persisted root draft on load');

        composerModule.init(doc, { draftStore: store });

        assert.strictEqual(input.value, 'Persisted root draft on load');
    });

    test('2. exact whitespace, Unicode, and multiline value round-trips without trimming', () => {
        const { doc, input } = createComposerFixture('c-whitespace-unicode');
        const { store } = createMockDraftStore();
        const rawContent = '   Đoạn văn có khoảng trắng đầu cuối\nvà nhiều dòng 🌸   \t\n';
        const key = draftsAdapter.getChapterRootDraftKey('c-whitespace-unicode');
        store.save(key, rawContent);

        composerModule.init(doc, { draftStore: store });

        assert.strictEqual(input.value, rawContent);
    });

    test('3. non-empty existing textarea is not overwritten on init', () => {
        const { doc, input } = createComposerFixture('c-no-overwrite');
        input.value = 'Existing server-rendered text';
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-no-overwrite');
        store.save(key, 'Stale stored draft');

        composerModule.init(doc, { draftStore: store });

        assert.strictEqual(input.value, 'Existing server-rendered text', 'Existing text must NOT be overwritten');
    });

    test('4. 400ms typing persistence: debounced save persists exact raw input', async () => {
        const { doc, input } = createComposerFixture('c-debounce');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-debounce');

        composerModule.init(doc, { draftStore: store });

        input.value = 'User typed content';
        input.dispatchEvent({ type: 'input' });

        // Immediately before debounce fires, store does not have the value yet
        assert.strictEqual(store.load(key), null);

        // Wait for 400ms debounce
        await new Promise(r => setTimeout(r, 450));

        assert.strictEqual(store.load(key), 'User typed content');
    });

    test('5. blank typing removes persisted draft via shared store', async () => {
        const { doc, input } = createComposerFixture('c-blank-removal');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-blank-removal');
        store.save(key, 'Existing draft');

        composerModule.init(doc, { draftStore: store });
        assert.strictEqual(input.value, 'Existing draft');

        // User clears input to whitespace only
        input.value = '    \n   ';
        input.dispatchEvent({ type: 'input' });

        await new Promise(r => setTimeout(r, 450));

        assert.strictEqual(store.load(key), null, 'Blank draft must be removed from storage');
    });

    test('6. flush event before debounce expires saves latest text immediately', () => {
        const { doc, input } = createComposerFixture('c-flush-event');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-flush-event');

        composerModule.init(doc, { draftStore: store });

        input.value = 'Draft typed right before pagehide';
        input.dispatchEvent({ type: 'input' });

        // Debounce timer is running, store is empty
        assert.strictEqual(store.load(key), null);

        // Flush event fires synchronously (via Novel Draft Adapter pagehide bridge)
        doc.dispatchEvent({ type: draftsAdapter.EVENT_FLUSH_DRAFTS });

        assert.strictEqual(store.load(key), 'Draft typed right before pagehide');
    });

    test('7. Chapter A -> B synchronously saves A before context changes, restores B draft, and prevents leak', () => {
        const { doc, input } = createComposerFixture('ch-alpha');
        const { store } = createMockDraftStore();
        const keyA = draftsAdapter.getChapterRootDraftKey('ch-alpha');
        const keyB = draftsAdapter.getChapterRootDraftKey('ch-beta');

        // Pre-seed B with a draft
        store.save(keyB, 'Draft for Chapter B');

        composerModule.init(doc, { draftStore: store });
        assert.strictEqual(input.value, '');

        // User types in Chapter A (without waiting for debounce)
        input.value = 'Unsaved draft in Chapter A';

        // Chapter changes to B
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'ch-beta' }
        });

        // Chapter A must be synchronously saved
        assert.strictEqual(store.load(keyA), 'Unsaved draft in Chapter A');

        // Textarea now displays Chapter B's draft
        assert.strictEqual(input.value, 'Draft for Chapter B');
        assert.strictEqual(composerModule.getActiveDraftChapterId(), 'ch-beta');

        // Switch back to Chapter A
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'ch-alpha' }
        });

        // Chapter A draft restored cleanly
        assert.strictEqual(input.value, 'Unsaved draft in Chapter A');
        assert.strictEqual(composerModule.getActiveDraftChapterId(), 'ch-alpha');
    });

    test('8. POST payload remains trimmed while stored pre-submit draft preserves exact raw text', async () => {
        const { doc, input } = createComposerFixture('c-raw-vs-trimmed');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-raw-vs-trimmed');

        let capturedPayload = null;
        composerModule.init(doc, {
            draftStore: store,
            fetch: (url, opts) => {
                capturedPayload = JSON.parse(opts.body);
                // Pre-submit storage check during in-flight fetch:
                assert.strictEqual(store.load(key), '   Raw multiline comment \n  with whitespace   ');
                return Promise.resolve({ ok: true, status: 201 });
            },
            commentsModule: {
                refreshFromPageZero: () => Promise.resolve()
            }
        });

        input.value = '   Raw multiline comment \n  with whitespace   ';
        await composerModule.handleSubmit();

        // The POST payload must be trimmed
        assert.strictEqual(capturedPayload.body, 'Raw multiline comment \n  with whitespace');
        // Upon 201 success, stored draft is cleared
        assert.strictEqual(store.load(key), null);
    });

    test('9. HTTP 500, network error, 400, 401, 403 preserve draft in textarea and storage', async () => {
        const errorCases = [
            { desc: 'HTTP 500', response: { ok: false, status: 500 } },
            { desc: 'HTTP 400', response: { ok: false, status: 400 } },
            { desc: 'HTTP 401', response: { ok: false, status: 401 } },
            { desc: 'HTTP 403', response: { ok: false, status: 403 } },
            { desc: 'Network Error', reject: new Error('Network failure') }
        ];

        for (const errCase of errorCases) {
            const chapterId = 'c-err-' + errCase.desc.replace(/\s+/g, '-');
            const { doc, input } = createComposerFixture(chapterId);
            const { store } = createMockDraftStore();
            const key = draftsAdapter.getChapterRootDraftKey(chapterId);

            composerModule.init(doc, {
                draftStore: store,
                fetch: () => errCase.reject ? Promise.reject(errCase.reject) : Promise.resolve(errCase.response)
            });

            input.value = 'Valuable user draft during ' + errCase.desc;
            await composerModule.handleSubmit();

            // Textarea must be preserved
            assert.strictEqual(input.value, 'Valuable user draft during ' + errCase.desc);
            // Stored draft must be preserved
            assert.strictEqual(store.load(key), 'Valuable user draft during ' + errCase.desc);

            composerModule.destroy();
        }
    });

    test('10. HTTP 201 removes submitted draft from storage and clears textarea', async () => {
        const { doc, input } = createComposerFixture('c-201-clear');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-201-clear');
        store.save(key, 'Draft to submit');

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => Promise.resolve({ ok: true, status: 201 }),
            commentsModule: {
                refreshFromPageZero: () => Promise.resolve()
            }
        });

        assert.strictEqual(input.value, 'Draft to submit');
        await composerModule.handleSubmit();

        assert.strictEqual(input.value, '');
        assert.strictEqual(store.load(key), null, 'Draft must be removed upon HTTP 201');
    });

    test('11. HTTP 201 + feed-refresh failure still leaves draft removed and shows error message', async () => {
        const { doc, input, statusEl } = createComposerFixture('c-refresh-fail');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-refresh-fail');
        store.save(key, 'Draft submitted successfully');

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => Promise.resolve({ ok: true, status: 201 }),
            commentsModule: {
                refreshFromPageZero: () => Promise.reject(new Error('Refresh failed'))
            }
        });

        await composerModule.handleSubmit();

        // Draft is removed because server accepted the comment
        assert.strictEqual(input.value, '');
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(statusEl.textContent, 'Bình luận đã được gửi, nhưng chưa thể tải lại danh sách.');
    });

    test('12. Stale Chapter A 201 after moving to B removes A stored draft but does not mutate B UI or refresh B', async () => {
        const { doc, input, statusEl } = createComposerFixture('ch-stale-a');
        const { store } = createMockDraftStore();
        const keyA = draftsAdapter.getChapterRootDraftKey('ch-stale-a');

        let resolvePostA;
        const postPromiseA = new Promise(r => { resolvePostA = r; });
        let refreshCountB = 0;

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => postPromiseA,
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCountB++;
                    return Promise.resolve();
                }
            }
        });

        input.value = 'Draft for A in flight';
        const submitPromiseA = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);

        // User navigates to Chapter B
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'ch-stale-b' }
        });
        input.value = 'Writing draft for B';

        // Old request A completes with HTTP 201
        resolvePostA({ ok: true, status: 201 });
        await submitPromiseA;

        // Chapter A stored draft must be cleaned up because server accepted it
        assert.strictEqual(store.load(keyA), null);

        // Chapter B UI must NOT be touched
        assert.strictEqual(input.value, 'Writing draft for B');
        assert.strictEqual(statusEl.textContent, '');
        assert.strictEqual(refreshCountB, 0, 'Chapter B feed must NOT be refreshed by stale Chapter A');
    });

    test('13. Stale Chapter A failure preserves A draft and does not mutate B UI', async () => {
        const { doc, input, statusEl } = createComposerFixture('ch-stale-fail-a');
        const { store } = createMockDraftStore();
        const keyA = draftsAdapter.getChapterRootDraftKey('ch-stale-fail-a');

        let resolvePostA;
        const postPromiseA = new Promise(r => { resolvePostA = r; });

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => postPromiseA
        });

        input.value = 'Draft A before failure';
        const submitPromiseA = composerModule.handleSubmit();

        // User navigates to Chapter B
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'ch-stale-fail-b' }
        });
        input.value = 'Draft B active';

        // Old request A fails with HTTP 500
        resolvePostA({ ok: false, status: 500 });
        await submitPromiseA;

        // Stored draft A is preserved so user can return to it
        assert.strictEqual(store.load(keyA), 'Draft A before failure');

        // B UI is untouched
        assert.strictEqual(input.value, 'Draft B active');
        assert.strictEqual(statusEl.textContent, '');
    });

    test('14. destroy cancels timers/listeners without deleting a valid stored draft', () => {
        const { doc, input } = createComposerFixture('c-destroy-safe');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('c-destroy-safe');

        composerModule.init(doc, { draftStore: store });
        input.value = 'Draft to keep on destroy';
        input.dispatchEvent({ type: 'input' });

        // Destroy the composer module
        composerModule.destroy();

        // Draft was flushed upon destroy and remains safely stored
        assert.strictEqual(store.load(key), 'Draft to keep on destroy');
    });

    test('15. Same-chapter ABA: stale 201 does NOT delete newer draft created under same chapter key after re-init', async () => {
        const { doc, input, statusEl } = createComposerFixture('ch-aba-x');
        const { store } = createMockDraftStore();
        const keyX = draftsAdapter.getChapterRootDraftKey('ch-aba-x');

        let resolvePostA;
        const postPromiseA = new Promise(r => { resolvePostA = r; });
        let refreshCount = 0;

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => postPromiseA,
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve();
                }
            }
        });

        // User types Draft A and submits
        input.value = 'Draft A';
        input.dispatchEvent({ type: 'input' });
        const submitPromiseA = composerModule.handleSubmit();
        assert.strictEqual(composerModule.isSubmitting(), true);

        // Composer is destroyed and re-initialized on the SAME Chapter X
        composerModule.destroy();
        composerModule.init(doc, {
            draftStore: store,
            fetch: () => Promise.resolve({ ok: true, status: 201 }),
            commentsModule: {
                refreshFromPageZero: () => {
                    refreshCount++;
                    return Promise.resolve();
                }
            }
        });

        // User types Draft B on the same chapter X
        input.value = 'Draft B';
        input.dispatchEvent({ type: 'input' });

        // Synchronously persist Draft B using flush event (e.g. pagehide bridge)
        doc.dispatchEvent({ type: draftsAdapter.EVENT_FLUSH_DRAFTS });
        assert.strictEqual(store.load(keyX), 'Draft B');

        // Old request A returns HTTP 201
        resolvePostA({ ok: true, status: 201 });
        await submitPromiseA;

        // Proves ABA safety: Draft B remains in storage exactly
        assert.strictEqual(store.load(keyX), 'Draft B');
        // Current textarea remains Draft B
        assert.strictEqual(input.value, 'Draft B');
        // Stale A does NOT refresh current feed or change status
        assert.strictEqual(refreshCount, 0);
        assert.strictEqual(statusEl.textContent, '');
    });

    test('16. Same-chapter stale 201 without newer user edit removes submitted draft and prevents resurrection on flush/destroy', async () => {
        const { doc, input } = createComposerFixture('ch-no-newer-edit');
        const { store } = createMockDraftStore();
        const key = draftsAdapter.getChapterRootDraftKey('ch-no-newer-edit');

        let resolvePostA;
        const postPromiseA = new Promise(r => { resolvePostA = r; });

        composerModule.init(doc, {
            draftStore: store,
            fetch: () => postPromiseA
        });

        input.value = 'Draft A to submit';
        input.dispatchEvent({ type: 'input' });
        const submitPromiseA = composerModule.handleSubmit();

        // Lifecycle becomes stale via destroy/re-init
        composerModule.destroy();
        composerModule.init(doc, {
            draftStore: store
        });

        // Old A returns HTTP 201
        resolvePostA({ ok: true, status: 201 });
        await submitPromiseA;

        // Draft A storage IS removed because server accepted it and no newer edit exists
        assert.strictEqual(store.load(key), null, 'Draft A storage is removed on HTTP 201');

        // Passive flush event (e.g. pagehide bridge) must NOT resurrect Draft A
        doc.dispatchEvent({ type: draftsAdapter.EVENT_FLUSH_DRAFTS });
        assert.strictEqual(store.load(key), null, 'Flush event must NOT resurrect accepted Draft A');

        // Destroy must NOT resurrect Draft A
        composerModule.destroy();
        assert.strictEqual(store.load(key), null, 'Destroy must NOT resurrect accepted Draft A');

        // Genuine newer user edit works and persists normally
        composerModule.init(doc, { draftStore: store });
        input.value = 'Draft B newer edit';
        input.dispatchEvent({ type: 'input' });
        doc.dispatchEvent({ type: draftsAdapter.EVENT_FLUSH_DRAFTS });
        assert.strictEqual(store.load(key), 'Draft B newer edit', 'Genuine newer edit is persisted normally');
    });

    test('17. Authoritative chapter-changed event context is used for submit even when DOM attribute lags', async () => {
        // Initial DOM section has data-chapter-id = "Chapter-A"
        const { doc, section, input } = createComposerFixture('Chapter-A');
        const { store } = createMockDraftStore();

        let capturedUrl = null;
        let capturedBody = null;

        composerModule.init(doc, {
            draftStore: store,
            fetch: (url, opts) => {
                capturedUrl = url;
                capturedBody = JSON.parse(opts.body);
                return Promise.resolve({ ok: true, status: 201 });
            },
            commentsModule: {
                refreshFromPageZero: () => Promise.resolve()
            }
        });

        // Dispatch kiemlai:chapter-changed to "Chapter-B"
        // Crucially: do NOT update DOM section data-chapter-id yet (simulating DOM lag)
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'Chapter-B' }
        });

        // Verify DOM still has old chapter ID
        assert.strictEqual(section.getAttribute('data-chapter-id'), 'Chapter-A');

        // Type comment on Chapter B
        input.value = 'Comment for B';
        input.dispatchEvent({ type: 'input' });

        await composerModule.handleSubmit();

        // POST URL must use Chapter-B, NOT Chapter-A
        assert.strictEqual(capturedUrl, '/api/novel/chapters/Chapter-B/comments');
        assert.strictEqual(capturedBody.body, 'Comment for B');

        // Pre-submit draft used Chapter-B key (and was cleared on 201)
        assert.strictEqual(store.load(draftsAdapter.getChapterRootDraftKey('Chapter-B')), null);
        // Chapter-A key was never touched with B's text
        assert.strictEqual(store.load(draftsAdapter.getChapterRootDraftKey('Chapter-A')), null);
    });

    test('18. DOM updated before chapter-changed event: Chapter A draft flushes to Chapter A key and does NOT poison Chapter B storage', () => {
        const { doc, section, input } = createComposerFixture('ch-race-alpha');
        const { store } = createMockDraftStore();
        const keyA = draftsAdapter.getChapterRootDraftKey('ch-race-alpha');
        const keyB = draftsAdapter.getChapterRootDraftKey('ch-race-beta');

        // Initial: Chapter B storage contains "Draft B"
        store.save(keyB, 'Draft B');

        // Initial: activeDraftChapterId = Chapter A
        composerModule.init(doc, { draftStore: store });
        assert.strictEqual(composerModule.getActiveDraftChapterId(), 'ch-race-alpha');

        // User typed "Draft A" in Chapter A textarea
        input.value = 'Draft A';
        input.dispatchEvent({ type: 'input' });

        // Before dispatching chapter-changed: mutate section[data-chapter-id] to Chapter B
        section.setAttribute('data-chapter-id', 'ch-race-beta');

        // Then dispatch kiemlai:chapter-changed with detail.chapterId = 'ch-race-beta'
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'ch-race-beta' }
        });

        // Chapter A key contains exactly "Draft A"
        assert.strictEqual(store.load(keyA), 'Draft A');

        // Chapter B key was NOT overwritten by Draft A
        assert.strictEqual(store.load(keyB), 'Draft B');

        // Textarea shows previously stored "Draft B"
        assert.strictEqual(input.value, 'Draft B');

        // activeDraftChapterId == Chapter B
        assert.strictEqual(composerModule.getActiveDraftChapterId(), 'ch-race-beta');
    });
});


