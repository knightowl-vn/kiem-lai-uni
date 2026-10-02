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
        if (val) this.attributes['id'] = String(val);
        else delete this.attributes['id'];
    }

    get textContent() { return this._textContent; }
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

    appendChild(child) {
        if (!child) return child;
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
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

        doc.body.appendChild(form);

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
});
