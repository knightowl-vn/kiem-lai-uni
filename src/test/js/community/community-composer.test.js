const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommunityComposer = require(path.join(__dirname, '../../../main/resources/static/js/community/community-composer.js'));

// Lightweight Fake DOM for Composer Tests
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
        this.files = [];
        this.src = '';
        this.listeners = {};

        this.focused = false;

        for (const [k, v] of Object.entries(attrs)) {
            this.setAttribute(k, v);
        }
    }

    focus() {
        this.focused = true;
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
        if (val) this.attributes['id'] = String(val);
        else delete this.attributes['id'];
    }

    get className() { return this.getAttribute('class') || ''; }
    set className(val) {
        if (val) this.setAttribute('class', String(val));
        else this.removeAttribute('class');
    }

    get textContent() {
        if (this.childNodes.length > 0) {
            return this.childNodes.map(c => c.textContent).join('');
        }
        return this._textContent;
    }
    set textContent(val) { this._textContent = String(val); }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes = new Set(String(value).trim().split(/\s+/).filter(Boolean));
        }
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') this.classList.classes.clear();
    }

    get firstChild() {
        return this.childNodes[0] || null;
    }

    appendChild(child) {
        if (!child) return child;
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    insertBefore(newChild, refChild) {
        if (!newChild) return newChild;
        newChild.parentNode = this;
        const idx = this.childNodes.indexOf(refChild);
        if (idx !== -1) {
            this.childNodes.splice(idx, 0, newChild);
        } else {
            this.childNodes.push(newChild);
        }
        return newChild;
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }

    dispatchEvent(evt) {
        const type = typeof evt === 'string' ? evt : evt.type;
        const list = this.listeners[type] || [];
        const eventObj = typeof evt === 'string' ? { type: evt, target: this, defaultPrevented: false } : evt;
        if (!eventObj.target) eventObj.target = this;
        for (const fn of list) {
            fn.call(this, eventObj);
        }
    }

    click() {
        this.dispatchEvent({ type: 'click', target: this, defaultPrevented: false });
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    querySelectorAll(selector) {
        const matches = [];
        const check = (node) => {
            if (!node || !node.attributes) return;
            if (selector.startsWith('#')) {
                if (node.id === selector.slice(1)) matches.push(node);
            } else if (selector.startsWith('.')) {
                if (node.classList.contains(selector.slice(1))) matches.push(node);
            } else if (selector.includes('[') && selector.endsWith(']')) {
                const tagPart = selector.slice(0, selector.indexOf('['));
                const inner = selector.slice(selector.indexOf('[') + 1, -1);
                const tagMatches = !tagPart || node.tagName.toLowerCase() === tagPart.toLowerCase();
                if (tagMatches) {
                    if (inner.includes('=')) {
                        const [k, v] = inner.split('=');
                        const cleanV = v.replace(/["']/g, '');
                        if (node.getAttribute(k) === cleanV) matches.push(node);
                    } else {
                        if (node.hasAttribute(inner)) matches.push(node);
                    }
                }
            } else if (node.tagName.toLowerCase() === selector.toLowerCase()) {
                matches.push(node);
            }

            for (const c of (node.childNodes || [])) {
                check(c);
            }
        };

        for (const c of this.childNodes) {
            check(c);
        }
        return matches;
    }
}

class FakeDocument {
    constructor() {
        this.head = new FakeElement('head');
        this.body = new FakeElement('body');
        this.elementsById = new Map();
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

    getElementById(id) {
        const search = (node) => {
            if (!node) return null;
            if (node.id === id) return node;
            for (const c of (node.childNodes || [])) {
                const found = search(c);
                if (found) return found;
            }
            return null;
        };
        return search(this.head) || search(this.body);
    }

    querySelector(selector) {
        if (selector.startsWith('#')) return this.getElementById(selector.slice(1));
        return this.body.querySelector(selector) || this.head.querySelector(selector);
    }

    querySelectorAll(selector) {
        const inHead = this.head.querySelectorAll(selector);
        const inBody = this.body.querySelectorAll(selector);
        return inHead.concat(inBody);
    }
}

describe('CommunityComposer Module Tests (MS-07B8.1 / MS-07B8-RETRO-CORRECTIVE)', () => {
    let doc;
    let form;
    let composerTrigger;
    let composerPanel;
    let collapseBtn;
    let preModNotice;
    let captionInput;
    let charCountEl;
    let imageInput;
    let addImageBtn;
    let imagePreviewContainer;
    let imagePreviewImg;
    let removeImageBtn;
    let submitBtn;
    let submitSpinner;
    let errorAlert;
    let successAlert;
    let refreshedFeedType = null;
    let createdReaders = [];

    class MockFileReader {
        constructor() {
            this.onload = null;
            this.result = '';
            createdReaders.push(this);
        }
        readAsDataURL(file) {
            this.file = file;
            this.result = 'data:image/jpeg;base64,' + (file.name || 'img');
        }
    }

    beforeEach(() => {
        createdReaders = [];
        refreshedFeedType = null;
        doc = new FakeDocument();

        // Meta tags for CSRF
        const csrfMeta = doc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'token-123');
        doc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = doc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        doc.head.appendChild(csrfHeaderMeta);

        // Composer Trigger (Collapsed state)
        composerTrigger = doc.createElement('button');
        composerTrigger.id = 'communityComposerTrigger';
        composerTrigger.className = 'community-composer-trigger';
        composerTrigger.setAttribute('type', 'button');
        composerTrigger.setAttribute('aria-expanded', 'false');
        composerTrigger.setAttribute('aria-controls', 'communityComposerPanel');

        const triggerAvatar = doc.createElement('img');
        triggerAvatar.className = 'composer-trigger-avatar';
        triggerAvatar.src = '/images/default_avatar.jpg';
        composerTrigger.appendChild(triggerAvatar);

        const triggerPrompt = doc.createElement('span');
        triggerPrompt.className = 'composer-trigger-prompt';
        triggerPrompt.textContent = 'Chia sẻ suy nghĩ của bạn về Kiếm Lai...';
        composerTrigger.appendChild(triggerPrompt);

        const triggerIcon = doc.createElement('i');
        triggerIcon.className = 'fa-solid fa-pen-to-square composer-trigger-icon';
        composerTrigger.appendChild(triggerIcon);

        doc.body.appendChild(composerTrigger);

        // Composer Panel (Expanded in place)
        composerPanel = doc.createElement('div');
        composerPanel.id = 'communityComposerPanel';
        composerPanel.className = 'community-composer-card';
        composerPanel.hidden = true;
        composerPanel.setAttribute('hidden', '');

        preModNotice = doc.createElement('div');
        preModNotice.className = 'alert alert-info';
        preModNotice.textContent = 'Chế độ kiểm duyệt trước đang bật.';
        composerPanel.appendChild(preModNotice);

        collapseBtn = doc.createElement('button');
        collapseBtn.id = 'composerCollapseBtn';
        collapseBtn.className = 'composer-collapse-btn';
        collapseBtn.setAttribute('type', 'button');
        collapseBtn.setAttribute('aria-label', 'Thu gọn trình đăng bài');
        composerPanel.appendChild(collapseBtn);

        // Composer Form
        form = doc.createElement('form');
        form.id = 'communityComposerForm';

        captionInput = doc.createElement('textarea');
        captionInput.id = 'composerCaption';
        captionInput.setAttribute('maxlength', '2000');
        form.appendChild(captionInput);

        charCountEl = doc.createElement('span');
        charCountEl.id = 'composerCharCount';
        charCountEl.textContent = '0/2000';
        form.appendChild(charCountEl);

        imageInput = doc.createElement('input');
        imageInput.id = 'composerImageInput';
        imageInput.setAttribute('type', 'file');
        form.appendChild(imageInput);

        addImageBtn = doc.createElement('button');
        addImageBtn.id = 'composerAddImageBtn';
        form.appendChild(addImageBtn);

        imagePreviewContainer = doc.createElement('div');
        imagePreviewContainer.id = 'composerImagePreview';
        imagePreviewContainer.hidden = true;
        imagePreviewContainer.setAttribute('hidden', '');

        imagePreviewImg = doc.createElement('img');
        imagePreviewImg.id = 'composerImagePreviewImg';
        imagePreviewContainer.appendChild(imagePreviewImg);

        removeImageBtn = doc.createElement('button');
        removeImageBtn.id = 'composerRemoveImageBtn';
        imagePreviewContainer.appendChild(removeImageBtn);
        form.appendChild(imagePreviewContainer);

        errorAlert = doc.createElement('div');
        errorAlert.id = 'composerError';
        errorAlert.hidden = true;
        errorAlert.setAttribute('hidden', '');
        form.appendChild(errorAlert);

        submitBtn = doc.createElement('button');
        submitBtn.id = 'composerSubmitBtn';
        submitBtn.setAttribute('type', 'submit');
        form.appendChild(submitBtn);

        submitSpinner = doc.createElement('div');
        submitSpinner.id = 'composerSubmitSpinner';
        submitSpinner.hidden = true;
        submitSpinner.setAttribute('hidden', '');
        form.appendChild(submitSpinner);

        composerPanel.appendChild(form);

        // Success Alert outside panel so it remains visible when collapsed
        successAlert = doc.createElement('div');
        successAlert.id = 'composerSuccess';
        successAlert.hidden = true;
        successAlert.setAttribute('hidden', '');
        doc.body.appendChild(successAlert);

        doc.body.appendChild(composerPanel);

        globalThis.document = doc;
        globalThis.FileReader = MockFileReader;
        globalThis.FormData = class {
            constructor() { this.entries = {}; }
            append(k, v) { this.entries[k] = v; }
        };
        globalThis.window = {
            CommunityFeed: {
                refreshFeed: (feed) => {
                    refreshedFeedType = feed;
                }
            }
        };
    });

    afterEach(() => {
        delete globalThis.document;
        delete globalThis.FileReader;
        delete globalThis.FormData;
        delete globalThis.window;
    });

    test('1. /login redirected response shows auth expiration and preserves caption', async () => {
        const mockFetch = async () => ({
            ok: true,
            redirected: true,
            url: 'http://localhost:8080/login?returnTo=%2Fcommunity',
            headers: { get: () => 'text/html;charset=UTF-8' }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Important draft about cultivation';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(captionInput.value, 'Important draft about cultivation');
        assert.strictEqual(errorAlert.hidden, false);
        assert.ok(errorAlert.textContent.includes('Phiên đăng nhập đã hết hạn'));
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('2. /access-denied redirected response shows access error and preserves caption', async () => {
        const mockFetch = async () => ({
            ok: true,
            redirected: true,
            url: 'http://localhost:8080/access-denied',
            headers: { get: () => 'text/html;charset=UTF-8' }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Draft with invalid CSRF session';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(captionInput.value, 'Draft with invalid CSRF session');
        assert.strictEqual(errorAlert.hidden, false);
        assert.ok(errorAlert.textContent.includes('Yêu cầu không hợp lệ'));
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('3. 201 non-JSON response shows invalid server response error and preserves caption', async () => {
        const mockFetch = async () => ({
            status: 201,
            ok: true,
            headers: { get: (h) => h === 'content-type' ? 'text/html;charset=UTF-8' : '' }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Draft received 201 HTML';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(captionInput.value, 'Draft received 201 HTML');
        assert.strictEqual(errorAlert.hidden, false);
        assert.ok(errorAlert.textContent.includes('Phản hồi máy chủ không hợp lệ'));
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('4. JSON validation error (400) displays server message and preserves caption', async () => {
        const mockFetch = async () => ({
            status: 400,
            ok: false,
            headers: { get: () => 'application/json' },
            json: async () => ({ message: 'Nội dung bài viết chứa từ ngữ vi phạm quy định.' })
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Draft with forbidden keywords';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(captionInput.value, 'Draft with forbidden keywords');
        assert.strictEqual(errorAlert.hidden, false);
        assert.strictEqual(errorAlert.textContent, 'Nội dung bài viết chứa từ ngữ vi phạm quy định.');
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('5. network failure displays friendly network error and preserves draft', async () => {
        const mockFetch = async () => {
            const err = new TypeError('Failed to fetch');
            throw err;
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Draft written before disconnection';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(captionInput.value, 'Draft written before disconnection');
        assert.strictEqual(errorAlert.hidden, false);
        assert.ok(errorAlert.textContent.includes('Không thể kết nối đến máy chủ'));
        assert.strictEqual(submitBtn.disabled, false);
    });

    test('6. caption and attached file are preserved on any submission failure', async () => {
        const mockFetch = async () => ({
            status: 500,
            ok: false,
            headers: { get: () => 'text/html' }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        const dummyFile = { name: 'photo.jpg', type: 'image/jpeg', size: 1024 };
        captionInput.value = 'Draft with image attached';
        imageInput.files = [dummyFile];

        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        // Both caption and file input are untouched
        assert.strictEqual(captionInput.value, 'Draft with image attached');
        assert.strictEqual(imageInput.files.length, 1);
        assert.strictEqual(imageInput.files[0].name, 'photo.jpg');
        assert.strictEqual(errorAlert.hidden, false);
    });

    test('7. select image A -> B -> A reader resolves last: preview remains B', async () => {
        CommunityComposer.init({ document: doc });

        const fileA = { name: 'photo-A.jpg', type: 'image/jpeg', size: 2048 };
        const fileB = { name: 'photo-B.png', type: 'image/png', size: 4096 };

        // Select Image A
        imageInput.files = [fileA];
        imageInput.dispatchEvent({ type: 'change', target: imageInput });
        assert.strictEqual(createdReaders.length, 1);
        const readerA = createdReaders[0];

        // Select Image B before reader A finishes
        imageInput.files = [fileB];
        imageInput.dispatchEvent({ type: 'change', target: imageInput });
        assert.strictEqual(createdReaders.length, 2);
        const readerB = createdReaders[1];

        // Reader B finishes first
        readerB.onload({ target: { result: 'data:image/png;base64,photoB' } });
        assert.strictEqual(imagePreviewContainer.hidden, false);
        assert.strictEqual(imagePreviewImg.src, 'data:image/png;base64,photoB');

        // Reader A finishes last
        readerA.onload({ target: { result: 'data:image/jpeg;base64,photoA' } });

        // Stale reader A must be discarded: preview remains B
        assert.strictEqual(imagePreviewImg.src, 'data:image/png;base64,photoB');
        assert.strictEqual(imagePreviewContainer.hidden, false);
    });

    test('8. select image -> remove -> old reader resolves: preview remains cleared/hidden', async () => {
        CommunityComposer.init({ document: doc });

        const fileA = { name: 'photo-A.jpg', type: 'image/jpeg', size: 2048 };

        // Select Image A
        imageInput.files = [fileA];
        imageInput.dispatchEvent({ type: 'change', target: imageInput });
        assert.strictEqual(createdReaders.length, 1);
        const readerA = createdReaders[0];

        // User clicks remove image before reader A finishes
        removeImageBtn.click();
        assert.strictEqual(imagePreviewContainer.hidden, true);
        assert.strictEqual(imagePreviewImg.src, '');

        // Stale reader A finishes after removal
        readerA.onload({ target: { result: 'data:image/jpeg;base64,photoA' } });

        // Preview remains empty and hidden
        assert.strictEqual(imagePreviewImg.src, '');
        assert.strictEqual(imagePreviewContainer.hidden, true);
    });

    test('9. normal success resets composer and refreshes NEWEST feed', async () => {
        let sentHeaders = null;
        let sentBody = null;

        const mockFetch = async (url, opts) => {
            sentHeaders = opts.headers;
            sentBody = opts.body;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-new-123', caption: 'Clean success post' })
            };
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Clean success post';
        captionInput.dispatchEvent({ type: 'input', target: captionInput });
        assert.strictEqual(charCountEl.textContent, '18/2000');

        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        // Form reset on success
        assert.strictEqual(captionInput.value, '');
        assert.strictEqual(charCountEl.textContent, '0/2000');
        assert.strictEqual(imagePreviewContainer.hidden, true);
        assert.strictEqual(errorAlert.hidden, true);
        assert.strictEqual(submitBtn.disabled, false);

        // Headers verified
        assert.strictEqual(sentHeaders['X-CSRF-TOKEN'], 'token-123');

        // Feed refresh triggered on NEWEST
        assert.strictEqual(refreshedFeedType, 'NEWEST');
    });

    test('10. 200 application/json is NOT accepted as create success (draft preserved, feed refresh NOT called)', async () => {
        let refreshCalled = false;
        globalThis.CommunityFeed = {
            refreshFeed: () => { refreshCalled = true; }
        };

        const mockFetch = async () => ({
            status: 200,
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({ id: 'post-should-fail-200' })
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Preserved caption on 200 response';
        const dummyFile = { name: 'photo-200.jpg', type: 'image/jpeg', size: 1024 };
        imageInput.files = [dummyFile];

        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        // Draft must be preserved
        assert.strictEqual(captionInput.value, 'Preserved caption on 200 response');
        assert.strictEqual(imageInput.files.length, 1);
        assert.strictEqual(imageInput.files[0].name, 'photo-200.jpg');

        // Feed refresh must NOT be called
        assert.strictEqual(refreshCalled, false);
        assert.strictEqual(refreshedFeedType, null);

        // Error message displayed
        assert.strictEqual(errorAlert.hidden, false);
        assert.strictEqual(errorAlert.textContent, 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
        assert.strictEqual(submitBtn.disabled, false);

        delete globalThis.CommunityFeed;
    });

    test('11. 500 application/json with malformed body shows generic server error without raw SyntaxError and preserves draft', async () => {
        let refreshCalled = false;
        globalThis.CommunityFeed = {
            refreshFeed: () => { refreshCalled = true; }
        };

        const mockFetch = async () => ({
            status: 500,
            ok: false,
            headers: { get: () => 'application/json' },
            json: async () => {
                throw new SyntaxError('Unexpected token < in JSON at position 0');
            }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Preserved caption on 500 malformed JSON';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        // Draft preserved
        assert.strictEqual(captionInput.value, 'Preserved caption on 500 malformed JSON');

        // Feed refresh must NOT be called
        assert.strictEqual(refreshCalled, false);
        assert.strictEqual(refreshedFeedType, null);

        // Error alert displayed with generic message, no parser SyntaxError
        assert.strictEqual(errorAlert.hidden, false);
        assert.strictEqual(errorAlert.textContent, 'Đã xảy ra lỗi khi đăng bài. Vui lòng thử lại.');
        assert.strictEqual(errorAlert.textContent.includes('Unexpected'), false);
        assert.strictEqual(errorAlert.textContent.includes('SyntaxError'), false);
        assert.strictEqual(errorAlert.textContent.includes('JSON'), false);
        assert.strictEqual(submitBtn.disabled, false);

        delete globalThis.CommunityFeed;
    });

    test('12. parseComposerResponse classifier rejects arbitrary 2xx and malformed 500 JSON', async () => {
        // HTTP 200 + application/json must throw invalid server response
        const resp200 = {
            status: 200,
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({ id: 'post-1' })
        };
        await assert.rejects(
            async () => { await CommunityComposer.parseComposerResponse(resp200); },
            { message: 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.' }
        );

        // HTTP 500 + application/json + malformed body must throw generic error, NOT SyntaxError
        const resp500Malformed = {
            status: 500,
            ok: false,
            headers: { get: () => 'application/json' },
            json: async () => { throw new SyntaxError('Unexpected token < in JSON at position 0'); }
        };
        await assert.rejects(
            async () => { await CommunityComposer.parseComposerResponse(resp500Malformed); },
            (err) => {
                assert.strictEqual(err.message, 'Đã xảy ra lỗi khi đăng bài. Vui lòng thử lại.');
                assert.ok(!(err instanceof SyntaxError));
                return true;
            }
        );
    });

    test('13. browser-safe file input reset: when input.files throws TypeError on array assignment, POST 201 clears composer without false network error', async () => {
        let postFetchCount = 0;
        const mockFetch = async () => {
            postFetchCount++;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-browser-safe-1', caption: 'Post with strict files setter' })
            };
        };

        // Simulate browser HTMLInputElement.files setter throwing TypeError when assigned an Array
        const mockFileList = [{ name: 'test.jpg', type: 'image/jpeg', size: 1024 }];
        Object.defineProperty(imageInput, 'files', {
            configurable: true,
            get() { return mockFileList; },
            set(v) {
                if (!v || v.constructor?.name !== 'FileList') {
                    throw new TypeError("Failed to set the 'files' property on 'HTMLInputElement': The provided value is not of type 'FileList'.");
                }
            }
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Post with strict files setter';
        imagePreviewContainer.hidden = false;
        imagePreviewImg.src = 'data:image/jpeg;base64,abc';

        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(postFetchCount, 1, 'POST fetch must be called exactly once');
        assert.strictEqual(captionInput.value, '', 'Caption must be reset');
        assert.strictEqual(imagePreviewContainer.hidden, true, 'Image preview must be hidden');
        assert.strictEqual(imagePreviewImg.src, '', 'Image preview src must be cleared');
        assert.strictEqual(errorAlert.hidden, true, 'Error alert must remain hidden');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button must be re-enabled');
        assert.strictEqual(refreshedFeedType, 'NEWEST', 'Feed refresh must be triggered on NEWEST');
    });

    test('14. canonical POST 201 when CommunityFeed.refreshFeed rejects: composer STILL resets, no composer error shown, fetch called once', async () => {
        let postFetchCount = 0;
        let refreshFeedCallCount = 0;

        globalThis.window.CommunityFeed = {
            refreshFeed: (feed) => {
                refreshFeedCallCount++;
                return Promise.reject(new TypeError('Failed to fetch feed'));
            }
        };

        const mockFetch = async () => {
            postFetchCount++;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-refresh-reject-1', caption: 'Post when feed refresh rejects' })
            };
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Post when feed refresh rejects';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(postFetchCount, 1, 'POST fetch must be called exactly once');
        assert.strictEqual(refreshFeedCallCount, 1, 'refreshFeed must be called once');
        assert.strictEqual(captionInput.value, '', 'Composer caption must still be reset');
        assert.strictEqual(errorAlert.hidden, true, 'Composer error alert must NOT be displayed');
        assert.strictEqual(errorAlert.textContent, '', 'No network error message in composer');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button must be unlocked');
    });

    test('15. canonical POST 201 when CommunityFeed.refreshFeed throws synchronously: composer STILL resets without false failure', async () => {
        let postFetchCount = 0;

        globalThis.window.CommunityFeed = {
            refreshFeed: () => {
                throw new Error('Synchronous DOM error in feed refresh');
            }
        };

        const mockFetch = async () => {
            postFetchCount++;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-refresh-throw-1', caption: 'Post when feed refresh throws' })
            };
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Post when feed refresh throws';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(postFetchCount, 1, 'POST fetch called once');
        assert.strictEqual(captionInput.value, '', 'Composer caption must still be reset');
        assert.strictEqual(errorAlert.hidden, true, 'Composer error alert must NOT be displayed');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button unlocked');
    });

    test('16. canonical POST 201 when CommunityFeed.refreshFeed returns false: create remains successful and composer resets', async () => {
        let postFetchCount = 0;
        let refreshFeedCallCount = 0;

        globalThis.window.CommunityFeed = {
            refreshFeed: (feed) => {
                refreshFeedCallCount++;
                return Promise.resolve(false);
            }
        };

        const mockFetch = async () => {
            postFetchCount++;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-refresh-false-1', caption: 'Post when feed refresh returns false' })
            };
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Post when feed refresh returns false';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(postFetchCount, 1, 'POST fetch called once');
        assert.strictEqual(refreshFeedCallCount, 1, 'refreshFeed called once');
        assert.strictEqual(captionInput.value, '', 'Composer caption reset');
        assert.strictEqual(errorAlert.hidden, true, 'Error alert hidden');
        assert.strictEqual(submitBtn.disabled, false, 'Submit button unlocked');
    });

    test('17. stale error state: previous visible network error is cleared on retry and subsequent canonical 201', async () => {
        let fetchAttempt = 0;

        const mockFetch = async () => {
            fetchAttempt++;
            if (fetchAttempt === 1) {
                throw new TypeError('Failed to fetch');
            }
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({ id: 'post-retry-success-1', caption: 'Retry draft' })
            };
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Retry draft';

        // Attempt 1: Network failure
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(fetchAttempt, 1);
        assert.strictEqual(errorAlert.hidden, false, 'Error alert shown on attempt 1');
        assert.ok(errorAlert.textContent.includes('Không thể kết nối đến máy chủ'));
        assert.strictEqual(captionInput.value, 'Retry draft', 'Draft preserved after network failure');

        // Attempt 2: Success
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(fetchAttempt, 2);
        assert.strictEqual(errorAlert.hidden, true, 'Error alert hidden on attempt 2 success');
        assert.strictEqual(errorAlert.textContent, '', 'Error alert text cleared');
        assert.strictEqual(captionInput.value, '', 'Draft cleared on attempt 2 success');
        assert.strictEqual(refreshedFeedType, 'NEWEST', 'Feed refreshed on attempt 2 success');
    });

    test('18. PENDING_REVIEW create success resets composer, shows pending feedback, and skips feed refresh', async () => {
        let fetchCalled = false;
        const mockFetch = async (url, options) => {
            fetchCalled = true;
            return {
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    id: 'post-pending-1',
                    status: 'PENDING_REVIEW',
                    caption: 'Draft awaiting moderation'
                })
            };
        };

        globalThis.CommunityFeed = {
            refreshFeed: async (feedType) => {
                refreshedFeedType = feedType;
                return true;
            }
        };

        CommunityComposer.init({ document: doc, fetch: mockFetch });

        captionInput.value = 'Draft awaiting moderation';

        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(fetchCalled, true, 'Fetch was called');
        assert.strictEqual(captionInput.value, '', 'Composer was reset');
        assert.strictEqual(successAlert.hidden, false, 'Success alert is shown');
        assert.ok(successAlert.textContent.includes('Bài viết đã được gửi và đang chờ quản trị viên duyệt.'));
        assert.strictEqual(refreshedFeedType, null, 'Feed refresh was NOT called for pending review post');

        // Verify own-pending section and pending card rendered in DOM
        const pendingSection = doc.getElementById('communityOwnPendingSection');
        assert.ok(pendingSection, 'Pending section was rendered in DOM');
        const pendingList = doc.getElementById('communityOwnPendingList');
        assert.ok(pendingList, 'Pending list exists');
        const pendingCard = pendingList.querySelector('.community-post-card--pending');
        assert.ok(pendingCard, 'Pending card is present in pending list');
        assert.strictEqual(pendingCard.querySelector('.post-caption').textContent, 'Draft awaiting moderation');
        assert.ok(pendingCard.querySelector('.post-footer').textContent.includes('Đang chờ duyệt'));
        assert.strictEqual(pendingCard.querySelector('.post-pending-badge-group'), null, 'No duplicate badge in header');
        assert.strictEqual(pendingCard.querySelector('.post-metric'), null, 'Pending card has no reaction or comment controls');
        assert.strictEqual(pendingCard.querySelector('a.post-caption'), null, 'Pending card caption is not a permalink anchor');
    });

    test('19. PENDING_REVIEW create with image renders attached image in pending card', async () => {
        const mockFetch = async () => ({
            status: 201,
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({
                id: 'post-pending-img-1',
                status: 'PENDING_REVIEW',
                caption: 'Draft with image',
                imageUrl: '/media/assets/img-123/content'
            })
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });
        captionInput.value = 'Draft with image';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        const pendingCard = doc.querySelector('.community-post-card--pending');
        assert.ok(pendingCard, 'Pending card is rendered');
        const img = pendingCard.querySelector('.post-image');
        assert.ok(img, 'Image is rendered in pending card');
        assert.strictEqual(img.src, '/media/assets/img-123/content');
    });

    test('20. PENDING_REVIEW with publishedAt renders Đang chờ duyệt lại in post footer', async () => {
        const mockFetch = async () => ({
            status: 201,
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({
                id: 'post-pending-re-review',
                status: 'PENDING_REVIEW',
                caption: 'Edited draft awaiting re-review',
                publishedAt: '2026-10-01T10:00:00Z'
            })
        });

        CommunityComposer.init({ document: doc, fetch: mockFetch });
        captionInput.value = 'Edited draft awaiting re-review';
        form.dispatchEvent({ type: 'submit', defaultPrevented: false });
        await new Promise(r => setTimeout(r, 20));

        const pendingCard = doc.querySelector('.community-post-card--pending');
        assert.ok(pendingCard, 'Pending card is rendered');
        assert.ok(pendingCard.querySelector('.post-footer').textContent.includes('Đang chờ duyệt lại'));
        assert.strictEqual(pendingCard.querySelector('.post-pending-badge-group'), null, 'No duplicate badge in header');
    });

    describe('CommunityComposer Collapsible UX Test Matrix (MS-07B8.5.4)', () => {
        test('1. Initial state: compact trigger is visible, composer starts collapsed, aria-expanded=false, pre-mod notice hidden', () => {
            const composer = CommunityComposer.init({ document: doc });

            assert.strictEqual(composerTrigger.hidden, false, 'Trigger is visible initially');
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'false', 'aria-expanded is false initially');
            assert.strictEqual(composerTrigger.getAttribute('aria-controls'), 'communityComposerPanel');
            assert.strictEqual(composerPanel.hidden, true, 'Composer panel is collapsed/hidden initially');
            assert.strictEqual(composer.isExpanded(), false, 'isExpanded reports false');
            // Pre-mod notice is inside the collapsed composerPanel, so it is not visible to user
            assert.strictEqual(preModNotice.parentNode, composerPanel);
        });

        test('2. Expand: clicking trigger expands composer panel, hides trigger, sets aria-expanded=true, focuses caption', () => {
            const composer = CommunityComposer.init({ document: doc });

            composerTrigger.click();

            assert.strictEqual(composerTrigger.hidden, true, 'Trigger is hidden when expanded');
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'true', 'aria-expanded is true when expanded');
            assert.strictEqual(composerPanel.hidden, false, 'Composer panel is visible when expanded');
            assert.strictEqual(composer.isExpanded(), true, 'isExpanded reports true');
            assert.strictEqual(captionInput.focused, true, 'Caption input received focus');
            assert.strictEqual(preModNotice.parentNode, composerPanel, 'Pre-mod notice is visible inside expanded panel');
        });

        test('3. Keyboard expand: Enter key on trigger expands composer', () => {
            const composer = CommunityComposer.init({ document: doc });

            composerTrigger.dispatchEvent({ type: 'keydown', key: 'Enter', keyCode: 13, defaultPrevented: false });

            assert.strictEqual(composerTrigger.hidden, true);
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(composerPanel.hidden, false);
            assert.strictEqual(composer.isExpanded(), true);
        });

        test('4. Keyboard expand: Space key on trigger expands composer', () => {
            const composer = CommunityComposer.init({ document: doc });

            composerTrigger.dispatchEvent({ type: 'keydown', key: ' ', keyCode: 32, defaultPrevented: false });

            assert.strictEqual(composerTrigger.hidden, true);
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(composerPanel.hidden, false);
            assert.strictEqual(composer.isExpanded(), true);
        });

        test('5. Manual collapse: clicking collapse button collapses panel, unhides trigger, sets aria-expanded=false, focuses trigger', () => {
            const composer = CommunityComposer.init({ document: doc });

            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);

            collapseBtn.click();

            assert.strictEqual(composerPanel.hidden, true, 'Panel is collapsed');
            assert.strictEqual(composerTrigger.hidden, false, 'Trigger is unhidden');
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(composer.isExpanded(), false);
            assert.strictEqual(composerTrigger.focused, true, 'Focus returned to trigger');
        });

        test('6. Manual collapse: Escape key inside composer panel collapses composer', () => {
            const composer = CommunityComposer.init({ document: doc });

            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);

            composerPanel.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27 });

            assert.strictEqual(composerPanel.hidden, true);
            assert.strictEqual(composerTrigger.hidden, false);
            assert.strictEqual(composerTrigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(composer.isExpanded(), false);
        });

        test('7. Manual collapse strictly preserves entered caption draft and image selection on reopen', () => {
            const composer = CommunityComposer.init({ document: doc });

            // 1. Expand
            composerTrigger.click();

            // 2. Author types draft caption
            captionInput.value = 'Unfinished thoughts about Kiếm Lai...';
            captionInput.dispatchEvent('input');

            // 3. Author attaches image
            const mockFile = { name: 'draft.jpg', type: 'image/jpeg', size: 1024 };
            imageInput.files = [mockFile];
            imageInput.dispatchEvent('change');
            if (createdReaders.length > 0 && createdReaders[createdReaders.length - 1].onload) {
                createdReaders[createdReaders.length - 1].onload({ target: { result: 'data:image/jpeg;base64,draft' } });
            }
            assert.strictEqual(imagePreviewContainer.hidden, false, 'Image preview is visible');

            // 4. Author manually collapses
            collapseBtn.click();
            assert.strictEqual(composer.isExpanded(), false);

            // 5. Verify draft is NOT cleared
            assert.strictEqual(captionInput.value, 'Unfinished thoughts about Kiếm Lai...', 'Draft caption preserved while collapsed');
            assert.strictEqual(imagePreviewImg.src, 'data:image/jpeg;base64,draft', 'Image preview preserved while collapsed');

            // 6. Author reopens
            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);
            assert.strictEqual(captionInput.value, 'Unfinished thoughts about Kiếm Lai...', 'Draft caption restored on reopen');
            assert.strictEqual(imagePreviewContainer.hidden, false, 'Image preview still active on reopen');
            assert.strictEqual(imagePreviewImg.src, 'data:image/jpeg;base64,draft');
        });

        test('8. Successful create under AUTO_PUBLISH clears draft and collapses composer', async () => {
            const mockFetch = async () => ({
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    id: 'post-auto-1',
                    status: 'PUBLISHED',
                    caption: 'Auto publish caption'
                })
            });

            const composer = CommunityComposer.init({ document: doc, fetch: mockFetch });

            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);

            captionInput.value = 'Auto publish caption';
            form.dispatchEvent({ type: 'submit', defaultPrevented: false });
            await new Promise(r => setTimeout(r, 20));

            // Must clear draft and collapse
            assert.strictEqual(captionInput.value, '', 'Caption cleared');
            assert.strictEqual(composer.isExpanded(), false, 'Composer collapsed after auto-publish');
            assert.strictEqual(composerTrigger.hidden, false, 'Trigger visible after auto-publish');
            assert.strictEqual(composerPanel.hidden, true, 'Panel hidden after auto-publish');
        });

        test('9. Successful create under PRE_MODERATION clears draft, collapses composer, and renders pending card', async () => {
            const mockFetch = async () => ({
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    id: 'post-pending-1',
                    status: 'PENDING_REVIEW',
                    caption: 'Pending review caption'
                })
            });

            const composer = CommunityComposer.init({ document: doc, fetch: mockFetch });

            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);

            captionInput.value = 'Pending review caption';
            form.dispatchEvent({ type: 'submit', defaultPrevented: false });
            await new Promise(r => setTimeout(r, 20));

            // Must clear draft and collapse
            assert.strictEqual(captionInput.value, '', 'Caption cleared');
            assert.strictEqual(composer.isExpanded(), false, 'Composer collapsed after pre-moderation submit');
            assert.strictEqual(composerTrigger.hidden, false, 'Trigger visible');
            assert.strictEqual(composerPanel.hidden, true, 'Panel hidden');

            // Success notice visible outside collapsed composer
            assert.strictEqual(successAlert.hidden, false, 'Success alert is visible');
            assert.ok(successAlert.textContent.includes('Bài viết đã được gửi và đang chờ quản trị viên duyệt.'));

            // Pending card rendered
            const pendingCard = doc.querySelector('.community-post-card--pending');
            assert.ok(pendingCard, 'Pending card rendered in DOM');
        });

        test('10. Failed create keeps composer expanded, preserves caption and selected image, and displays error alert', async () => {
            const mockFetch = async () => ({
                status: 400,
                ok: false,
                headers: { get: () => 'application/json' },
                json: async () => ({ message: 'Nội dung không hợp lệ.' })
            });

            const composer = CommunityComposer.init({ document: doc, fetch: mockFetch });

            composerTrigger.click();
            assert.strictEqual(composer.isExpanded(), true);

            captionInput.value = 'Invalid content causing error';
            form.dispatchEvent({ type: 'submit', defaultPrevented: false });
            await new Promise(r => setTimeout(r, 20));

            // Composer must REMAIN EXPANDED
            assert.strictEqual(composer.isExpanded(), true, 'Composer remains expanded on error');
            assert.strictEqual(composerPanel.hidden, false, 'Panel remains visible on error');
            assert.strictEqual(composerTrigger.hidden, true, 'Trigger remains hidden on error');

            // Input must be preserved
            assert.strictEqual(captionInput.value, 'Invalid content causing error', 'Caption preserved on error');

            // Error must be visible
            assert.strictEqual(errorAlert.hidden, false, 'Error alert visible');
            assert.strictEqual(errorAlert.textContent, 'Nội dung không hợp lệ.');

            // No pending card fabricated
            assert.strictEqual(doc.querySelector('.community-post-card--pending'), null, 'No fake pending card rendered');
        });

        test('11. Repeated open/close cycles do not attach duplicate submit handlers (submits exactly once)', async () => {
            let fetchCount = 0;
            const mockFetch = async () => {
                fetchCount++;
                return {
                    status: 201,
                    ok: true,
                    headers: { get: () => 'application/json' },
                    json: async () => ({ id: 'post-single-submit', status: 'PUBLISHED', caption: 'Single' })
                };
            };

            const composer = CommunityComposer.init({ document: doc, fetch: mockFetch });

            // Cycle 1: open -> close
            composerTrigger.click();
            collapseBtn.click();

            // Cycle 2: open -> close
            composerTrigger.click();
            collapseBtn.click();

            // Cycle 3: open and submit
            composerTrigger.click();
            captionInput.value = 'Single submit test';
            form.dispatchEvent({ type: 'submit', defaultPrevented: false });
            await new Promise(r => setTimeout(r, 20));

            assert.strictEqual(fetchCount, 1, 'Submit handler executed exactly once despite multiple open/close cycles');
        });
    });

    describe('CommunityComposer Pending Tray UX Matrix (MS-07B8.5.4)', () => {
        function setupPendingTrayDOM(count = 2) {
            const section = doc.createElement('section');
            section.id = 'communityOwnPendingSection';
            section.className = 'community-own-pending-section mb-4';

            const trigger = doc.createElement('button');
            trigger.type = 'button';
            trigger.id = 'communityOwnPendingTrigger';
            trigger.className = 'community-pending-tray-trigger';
            trigger.setAttribute('aria-expanded', 'false');
            trigger.setAttribute('aria-controls', 'communityOwnPendingList');

            const leftDiv = doc.createElement('div');
            leftDiv.className = 'pending-tray-left';
            const title = doc.createElement('span');
            title.className = 'pending-tray-title';
            title.textContent = 'Bài viết đang chờ duyệt';
            leftDiv.appendChild(title);

            const rightDiv = doc.createElement('div');
            rightDiv.className = 'pending-tray-right';
            const countBadge = doc.createElement('span');
            countBadge.id = 'communityOwnPendingCount';
            countBadge.className = 'pending-tray-count-badge';
            countBadge.textContent = String(count);
            rightDiv.appendChild(countBadge);

            trigger.appendChild(leftDiv);
            trigger.appendChild(rightDiv);
            section.appendChild(trigger);

            const list = doc.createElement('div');
            list.id = 'communityOwnPendingList';
            list.className = 'community-pending-list d-flex flex-column gap-3 mt-3';
            list.setAttribute('hidden', '');

            for (let i = 1; i <= count; i++) {
                const card = doc.createElement('article');
                card.className = 'community-post-card community-post-card--pending';
                card.setAttribute('data-post-id', 'pending-' + i);
                const p = doc.createElement('p');
                p.className = 'post-caption';
                p.textContent = 'Pending post caption ' + i;
                card.appendChild(p);

                const footer = doc.createElement('footer');
                footer.className = 'post-footer';
                const statusDiv = doc.createElement('div');
                statusDiv.className = 'post-pending-status';
                statusDiv.textContent = i === 1 ? '⏳ Đang chờ duyệt' : '⏳ Đang chờ duyệt chỉnh sửa';
                footer.appendChild(statusDiv);
                card.appendChild(footer);

                list.appendChild(card);
            }

            section.appendChild(list);
            doc.body.appendChild(section);
            return { section, trigger, list, countBadge };
        }

        test('1. ZERO PENDING: tray is absent on initial load (not rendered in DOM)', () => {
            const composer = CommunityComposer.init({ document: doc });
            const section = doc.getElementById('communityOwnPendingSection');
            assert.strictEqual(section, null, 'Pending tray section is absent when zero pending');
            assert.strictEqual(composer.isPendingTrayExpanded(), false);
        });

        test('2. ONE OR MORE PENDING: tray summary rendered, correct count, collapsed by default, aria-expanded=false', () => {
            const { trigger, list, countBadge } = setupPendingTrayDOM(3);
            const composer = CommunityComposer.init({ document: doc });

            assert.ok(trigger, 'Trigger is rendered');
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false', 'aria-expanded is false initially');
            assert.strictEqual(trigger.getAttribute('aria-controls'), 'communityOwnPendingList');
            assert.strictEqual(countBadge.textContent, '3', 'Count badge displays correct pending count');
            assert.strictEqual(list.hidden, true, 'Cards list is hidden initially');
            assert.strictEqual(composer.isPendingTrayExpanded(), false);
            assert.strictEqual(composer.getPendingCount(), 3);
        });

        test('3. EXPAND: clicking trigger expands cards in place, sets aria-expanded=true, unhides cards list', () => {
            const { trigger, list } = setupPendingTrayDOM(2);
            const composer = CommunityComposer.init({ document: doc });

            trigger.click();
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(list.hidden, false);
            assert.strictEqual(composer.isPendingTrayExpanded(), true);
        });

        test('4. KEYBOARD EXPAND: pressing Enter or Space on trigger expands tray', () => {
            const { trigger, list } = setupPendingTrayDOM(2);
            CommunityComposer.init({ document: doc });

            trigger.dispatchEvent({ type: 'keydown', key: 'Enter', keyCode: 13, defaultPrevented: false });
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(list.hidden, false);

            trigger.dispatchEvent({ type: 'keydown', key: ' ', keyCode: 32, defaultPrevented: false });
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(list.hidden, true);
        });

        test('5. COLLAPSE: clicking trigger when expanded collapses cards in place, sets aria-expanded=false, hides cards list', () => {
            const { trigger, list } = setupPendingTrayDOM(2);
            const composer = CommunityComposer.init({ document: doc });

            trigger.click(); // Expand
            assert.strictEqual(list.hidden, false);

            trigger.click(); // Collapse
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(list.hidden, true);
            assert.strictEqual(composer.isPendingTrayExpanded(), false);
        });

        test('6. SEMANTICS: new pending card shows "Đang chờ duyệt", pending edit shows "Đang chờ duyệt chỉnh sửa"', () => {
            const { list } = setupPendingTrayDOM(2);
            CommunityComposer.init({ document: doc });

            const cards = list.querySelectorAll('.community-post-card--pending');
            assert.strictEqual(cards.length, 2);
            assert.ok(cards[0].querySelector('.post-pending-status').textContent.includes('Đang chờ duyệt'));
            assert.ok(cards[1].querySelector('.post-pending-status').textContent.includes('Đang chờ duyệt chỉnh sửa'));
        });

        test('7. CREATE: successful PRE_MODERATION create increments pending count, adds card, keeps tray collapsed', async () => {
            const { trigger, list, countBadge } = setupPendingTrayDOM(1);
            const mockFetch = async () => ({
                status: 201,
                ok: true,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    id: 'new-pending-post',
                    status: 'PENDING_REVIEW',
                    caption: 'New pending submission'
                })
            });

            const composer = CommunityComposer.init({ document: doc, fetch: mockFetch });

            captionInput.value = 'New pending submission';
            form.dispatchEvent({ type: 'submit', defaultPrevented: false });
            await new Promise(r => setTimeout(r, 20));

            // Count incremented to 2
            assert.strictEqual(countBadge.textContent, '2', 'Count updated from 1 to 2');
            assert.strictEqual(composer.getPendingCount(), 2);

            // Tray remains collapsed
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false', 'Tray remains collapsed');
            assert.strictEqual(list.hidden, true, 'Cards list remains hidden');

            // Card added to list
            const cards = list.querySelectorAll('.community-post-card--pending');
            assert.strictEqual(cards.length, 2);
            assert.strictEqual(cards[0].querySelector('.post-caption').textContent, 'New pending submission');
            assert.ok(cards[0].querySelector('.post-pending-status').textContent.includes('Đang chờ duyệt'));
        });

        test('8. STABILITY: repeated toggles do not duplicate handlers or re-trigger actions', () => {
            const { trigger, list } = setupPendingTrayDOM(2);
            CommunityComposer.init({ document: doc });

            // Toggle multiple times
            trigger.click(); // expanded
            trigger.click(); // collapsed
            trigger.click(); // expanded
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(list.hidden, false);

            trigger.click(); // collapsed
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(list.hidden, true);
        });
    });
});
