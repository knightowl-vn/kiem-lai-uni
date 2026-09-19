const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
const draftsAdapter = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-drafts.js'));

const bottomRootComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-composer.js'));
const bottomReplyComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-reply-composer.js'));
const bottomEditComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-edit-composer.js'));

const drawerRootComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-composer.js'));
const drawerReplyComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-reply-composer.js'));
const drawerEditComposer = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-edit-composer.js'));
const drawerRestore = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-restore.js'));

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

class FakeClassList {
    constructor(el) {
        this.el = el;
        this.classes = new Set();
    }
    add(...names) {
        names.forEach(n => this.classes.add(n));
        this.el.attributes['class'] = Array.from(this.classes).join(' ');
    }
    remove(...names) {
        names.forEach(n => this.classes.delete(n));
        this.el.attributes['class'] = Array.from(this.classes).join(' ');
    }
    contains(name) {
        return this.classes.has(name);
    }
}

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = String(tagName).toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.parentElement = null;
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this._textContent = '';
        this._value = '';
        this.disabled = false;
        this.hidden = false;
        this.isFocused = false;
        this.dataset = {};
        this.scrolledIntoView = false;

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

    get value() {
        return this._value;
    }

    set value(val) {
        this._value = String(val);
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
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
    }

    blur() {
        this.isFocused = false;
    }

    scrollIntoView(options) {
        this.scrolledIntoView = true;
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
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
            oldChild.parentNode = null;
            oldChild.parentElement = null;
        }
        return oldChild;
    }

    insertBefore(newChild, refChild) {
        if (!refChild) return this.appendChild(newChild);
        const idx = this.childNodes.indexOf(refChild);
        if (idx !== -1) {
            this.childNodes.splice(idx, 0, newChild);
            newChild.parentNode = this;
            newChild.parentElement = this;
        } else {
            this.appendChild(newChild);
        }
        return newChild;
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
        if (name.startsWith('data-')) {
            const prop = name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
            this.dataset[prop] = String(value);
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
        if (name.startsWith('data-')) {
            const prop = name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
            delete this.dataset[prop];
        }
    }

    hasAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name);
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
        return querySelectorAllDeep(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this, selector);
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

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    const bracketIdx = sel.indexOf('[');
    if (bracketIdx !== -1 && sel.endsWith(']')) {
        const prefix = bracketIdx > 0 ? sel.slice(0, bracketIdx) : null;
        if (prefix) {
            if (prefix.startsWith('.')) {
                if (!el.classList || !el.classList.contains(prefix.slice(1))) return false;
            } else if (prefix.includes('.')) {
                const [t, ...cl] = prefix.split('.');
                if (t && el.tagName.toLowerCase() !== t.toLowerCase()) return false;
                if (!cl.every(c => el.classList && el.classList.contains(c))) return false;
            } else if (el.tagName.toLowerCase() !== prefix.toLowerCase()) {
                return false;
            }
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
    const parts = selector.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return [];
    if (parts.length === 1) {
        const results = [];
        function traverse(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (matchesSingleSelector(child, parts[0])) {
                    results.push(child);
                }
                traverse(child);
            }
        }
        traverse(root);
        return results;
    }

    let currentLevel = [root];
    for (const part of parts) {
        const nextLevel = [];
        for (const parent of currentLevel) {
            const found = querySelectorAllDeep(parent, part);
            for (const f of found) {
                if (!nextLevel.includes(f)) nextLevel.push(f);
            }
        }
        currentLevel = nextLevel;
    }
    return currentLevel;
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
        this.activeElement = null;
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    registerElement(id, el) {
        el.setAttribute('id', id);
        el.ownerDocument = this;
        this.elementsById.set(id, el);
    }

    getElementById(id) {
        return this.elementsById.get(id) || querySelectorAllDeep(this.documentElement, '#' + id)[0] || null;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this.documentElement, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this.documentElement, selector);
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
        try {
            if (!eventObj.target) {
                eventObj.target = this;
            }
        } catch (_) {}
        if (this.listeners[eventType]) {
            for (const listener of [...this.listeners[eventType]]) {
                listener.call(this, eventObj);
            }
        }
        return true;
    }
}

function createFakeWindow(initialSearch = '', initialPath = '/novel/chapters/quyen-1-chuong-1', initialHash = '') {
    const historyEntries = [];
    const win = {
        location: {
            pathname: initialPath,
            search: initialSearch,
            hash: initialHash,
            origin: 'https://kiemlai.vn'
        },
        history: {
            state: null,
            replaceState: function (state, title, url) {
                this.state = state;
                historyEntries.push({ state, title, url });
                const parsedUrl = new URL(url, 'https://kiemlai.vn');
                win.location.pathname = parsedUrl.pathname;
                win.location.search = parsedUrl.search;
                win.location.hash = parsedUrl.hash;
            },
            getHistoryEntries: function () {
                return historyEntries;
            }
        }
    };
    return win;
}

function setupCrossSurfaceFixture(chapterId = 'chap-cross-1', blockKey = 'blk-cross-1') {
    const doc = new FakeDocument();
    const win = createFakeWindow('', '/novel/chapters/quyen-1-chuong-1');
    doc.defaultView = win;
    win.document = doc;
    win.history.win = win;

    const { store, storageMap } = createMockDraftStore();

    // CSRF meta
    const csrfToken = doc.createElement('meta');
    csrfToken.setAttribute('name', '_csrf');
    csrfToken.setAttribute('content', 'token-cross-xyz');
    doc.head.appendChild(csrfToken);

    const csrfHeader = doc.createElement('meta');
    csrfHeader.setAttribute('name', '_csrf_header');
    csrfHeader.setAttribute('content', 'X-CSRF-TOKEN');
    doc.head.appendChild(csrfHeader);

    // Reader body
    const chapterBody = doc.createElement('article');
    chapterBody.setAttribute('class', 'novel-reader-chapter-body');
    chapterBody.setAttribute('data-chapter-id', chapterId);
    chapterBody.setAttribute('data-content-version', '1');
    chapterBody.setAttribute('data-chapter-path', '/novel/chapters/quyen-1-chuong-1');

    const blockEl1 = doc.createElement('p');
    blockEl1.setAttribute('data-reader-block-key', blockKey);
    blockEl1.setAttribute('data-comment-thread-count', '1');
    blockEl1.textContent = 'Đoạn văn 1';
    chapterBody.appendChild(blockEl1);

    const blockEl2 = doc.createElement('p');
    blockEl2.setAttribute('data-reader-block-key', 'blk-cross-2');
    blockEl2.setAttribute('data-comment-thread-count', '0');
    blockEl2.textContent = 'Đoạn văn 2';
    chapterBody.appendChild(blockEl2);

    doc.body.appendChild(chapterBody);

    // Block Discussion Drawer container
    const drawerAside = doc.createElement('aside');
    drawerAside.setAttribute('id', 'novelBlockDiscussionDrawer');
    drawerAside.setAttribute('class', 'novel-block-discussion-drawer');
    drawerAside.setAttribute('data-chapter-id', chapterId);
    drawerAside.setAttribute('data-content-version', '1');
    drawerAside.setAttribute('data-block-key', blockKey);
    drawerAside.setAttribute('data-authenticated', 'true');

    const drawerContent = doc.createElement('section');
    drawerContent.setAttribute('id', 'novelBlockDiscussionContent');
    drawerContent.setAttribute('class', 'novel-block-discussion-content');
    drawerAside.appendChild(drawerContent);

    // Drawer Root Composer
    const drawerForm = doc.createElement('form');
    drawerForm.setAttribute('id', 'novelBlockDiscussionComposer');
    const drawerInput = doc.createElement('textarea');
    drawerInput.setAttribute('id', 'novelBlockDiscussionComposerInput');
    const drawerStatus = doc.createElement('div');
    drawerStatus.setAttribute('id', 'novelBlockDiscussionComposerStatus');
    const drawerSubmit = doc.createElement('button');
    drawerSubmit.setAttribute('id', 'novelBlockDiscussionComposerSubmit');
    drawerSubmit.setAttribute('type', 'submit');
    drawerForm.appendChild(drawerInput);
    drawerForm.appendChild(drawerStatus);
    drawerForm.appendChild(drawerSubmit);
    drawerAside.appendChild(drawerForm);

    doc.body.appendChild(drawerAside);

    // Bottom Comments Section
    const bottomSection = doc.createElement('section');
    bottomSection.setAttribute('id', 'novelChapterComments');
    bottomSection.setAttribute('class', 'novel-chapter-comments');
    bottomSection.setAttribute('data-chapter-id', chapterId);
    bottomSection.setAttribute('data-authenticated', 'true');

    // Bottom Root Composer
    const bottomForm = doc.createElement('form');
    bottomForm.setAttribute('id', 'novelChapterCommentComposer');
    const bottomInput = doc.createElement('textarea');
    bottomInput.setAttribute('id', 'novelChapterCommentComposerInput');
    const bottomStatus = doc.createElement('div');
    bottomStatus.setAttribute('id', 'novelChapterCommentComposerStatus');
    const bottomSubmit = doc.createElement('button');
    bottomSubmit.setAttribute('id', 'novelChapterCommentComposerSubmit');
    bottomSubmit.setAttribute('type', 'submit');
    bottomForm.appendChild(bottomInput);
    bottomForm.appendChild(bottomStatus);
    bottomForm.appendChild(bottomSubmit);
    bottomSection.appendChild(bottomForm);

    const bottomSectionStatus = doc.createElement('div');
    bottomSectionStatus.setAttribute('id', 'novelChapterCommentsStatus');
    bottomSection.appendChild(bottomSectionStatus);

    const bottomList = doc.createElement('div');
    bottomList.setAttribute('id', 'novelChapterCommentsList');
    bottomSection.appendChild(bottomList);

    doc.body.appendChild(bottomSection);

    doc.registerElement('novelBlockDiscussionDrawer', drawerAside);
    doc.registerElement('novelBlockDiscussionContent', drawerContent);
    doc.registerElement('novelBlockDiscussionComposer', drawerForm);
    doc.registerElement('novelBlockDiscussionComposerInput', drawerInput);
    doc.registerElement('novelBlockDiscussionComposerStatus', drawerStatus);
    doc.registerElement('novelBlockDiscussionComposerSubmit', drawerSubmit);

    doc.registerElement('novelChapterComments', bottomSection);
    doc.registerElement('novelChapterCommentComposer', bottomForm);
    doc.registerElement('novelChapterCommentComposerForm', bottomForm);
    doc.registerElement('novelChapterCommentComposerInput', bottomInput);
    doc.registerElement('novelChapterCommentComposerStatus', bottomStatus);
    doc.registerElement('novelChapterCommentComposerSubmit', bottomSubmit);
    doc.registerElement('novelChapterCommentsStatus', bottomSectionStatus);
    doc.registerElement('novelChapterCommentsList', bottomList);

    return {
        doc,
        win,
        store,
        storageMap,
        chapterBody,
        drawerAside,
        drawerContent,
        drawerForm,
        drawerInput,
        drawerStatus,
        drawerSubmit,
        bottomSection,
        bottomForm,
        bottomInput,
        bottomStatus,
        bottomSubmit,
        bottomList
    };
}

function addCommentToDrawer(drawerContent, { commentId, rootId, authorName, bodyText }) {
    const doc = drawerContent.ownerDocument;
    let thread = drawerContent.querySelector(`.novel-block-discussion-thread[data-root-id="${rootId}"]`);
    if (!thread) {
        thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);
        drawerContent.appendChild(thread);
    }
    const commentEl = doc.createElement('div');
    commentEl.className = 'novel-comment' + (commentId === rootId ? ' novel-comment--root' : ' novel-comment--reply');
    commentEl.setAttribute('data-comment-id', commentId);

    const authorEl = doc.createElement('div');
    authorEl.className = 'novel-comment-author';
    authorEl.textContent = authorName || 'Drawer User';
    commentEl.appendChild(authorEl);

    const bodyEl = doc.createElement('div');
    bodyEl.className = 'novel-comment-body';
    bodyEl.textContent = bodyText || 'Drawer body';
    commentEl.appendChild(bodyEl);

    const actionsEl = doc.createElement('div');
    actionsEl.className = 'novel-comment-actions';

    const replyBtn = doc.createElement('button');
    replyBtn.type = 'button';
    replyBtn.className = 'novel-comment-reply-btn';
    replyBtn.setAttribute('data-action', 'reply');
    replyBtn.setAttribute('data-comment-id', commentId);
    replyBtn.setAttribute('data-root-id', rootId);
    replyBtn.textContent = 'Phản hồi';
    actionsEl.appendChild(replyBtn);

    const editBtn = doc.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'novel-comment-edit-btn';
    editBtn.setAttribute('data-action', 'edit');
    editBtn.setAttribute('data-comment-id', commentId);
    editBtn.setAttribute('data-root-id', rootId);
    editBtn.textContent = 'Sửa';
    actionsEl.appendChild(editBtn);

    commentEl.appendChild(actionsEl);
    thread.appendChild(commentEl);
    return { thread, commentEl, replyBtn, editBtn, bodyEl };
}

function addCommentToBottom(bottomList, { commentId, rootId, authorName, bodyText }) {
    const doc = bottomList.ownerDocument;
    let thread = bottomList.querySelector(`.novel-block-discussion-thread[data-root-id="${rootId}"]`);
    if (!thread) {
        thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);
        bottomList.appendChild(thread);
    }
    const commentEl = doc.createElement('div');
    commentEl.className = 'novel-comment' + (commentId === rootId ? ' novel-comment--root' : ' novel-comment--reply');
    commentEl.setAttribute('data-comment-id', commentId);

    const authorEl = doc.createElement('div');
    authorEl.className = 'novel-comment-author';
    authorEl.textContent = authorName || 'Bottom User';
    commentEl.appendChild(authorEl);

    const bodyEl = doc.createElement('div');
    bodyEl.className = 'novel-comment-body';
    bodyEl.textContent = bodyText || 'Bottom body';
    commentEl.appendChild(bodyEl);

    const actionsEl = doc.createElement('div');
    actionsEl.className = 'novel-comment-actions';

    const replyBtn = doc.createElement('button');
    replyBtn.type = 'button';
    replyBtn.className = 'novel-comment-reply-btn';
    replyBtn.setAttribute('data-action', 'reply');
    replyBtn.setAttribute('data-comment-id', commentId);
    replyBtn.setAttribute('data-root-id', rootId);
    replyBtn.textContent = 'Phản hồi';
    actionsEl.appendChild(replyBtn);

    const editBtn = doc.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'novel-comment-edit-btn';
    editBtn.setAttribute('data-action', 'edit');
    editBtn.setAttribute('data-comment-id', commentId);
    editBtn.setAttribute('data-root-id', rootId);
    editBtn.textContent = 'Sửa';
    actionsEl.appendChild(editBtn);

    commentEl.appendChild(actionsEl);
    thread.appendChild(commentEl);
    return { thread, commentEl, replyBtn, editBtn, bodyEl };
}

describe('UX-DRAFT-01D4 Novel Comment Draft Cross-Surface Lifecycle & Regression', () => {

    afterEach(() => {
        bottomRootComposer.destroy();
        bottomReplyComposer.destroy();
        bottomEditComposer.destroy();
        drawerRootComposer.destroy();
        drawerReplyComposer.resetReplyComposerState();
        drawerEditComposer.resetEditComposerState();
        drawerRestore.resetRestoreState();
    });

    // ========================================================================
    // SECTION A: GLOBAL FLUSH
    // ========================================================================
    describe('A. Global Flush', () => {

        test('1. Bottom Root + Drawer Root dirty: both saved exactly on one flush', () => {
            const chapterId = 'chap-flush-1';
            const blockKey = 'blk-flush-1';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            bottomRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            // Enable drawer composer via loaded event
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            // Dirty both composers
            env.bottomInput.value = 'Dirty Bottom Root Value';
            env.bottomInput.dispatchEvent({ type: 'input' });

            env.drawerInput.value = 'Dirty Drawer Root Value';
            env.drawerInput.dispatchEvent({ type: 'input' });

            // Global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bottomKey = draftsAdapter.getChapterRootDraftKey(chapterId);
            const drawerKey = draftsAdapter.getBlockRootDraftKey(chapterId, blockKey);

            assert.strictEqual(env.store.load(bottomKey), 'Dirty Bottom Root Value', 'Bottom root saved exactly');
            assert.strictEqual(env.store.load(drawerKey), 'Dirty Drawer Root Value', 'Drawer root saved exactly');

            // Markers: Drawer root writes active-block, Bottom root writes no marker
            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            const activeInlineKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            assert.strictEqual(JSON.parse(env.store.load(activeBlockKey)).type, 'root');
            assert.strictEqual(env.store.load(activeInlineKey), null);
        });

        test('2. Bottom Reply + Drawer Reply dirty: both keys preserved independently', () => {
            const chapterId = 'chap-flush-2';
            const blockKey = 'blk-flush-2';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-comm-2', rootId: 'b-comm-2', authorName: 'Author B', bodyText: 'Bottom body'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-comm-2', rootId: 'd-comm-2', authorName: 'Author D', bodyText: 'Drawer body'
            });

            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });
            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            // Open bottom reply and dirty it
            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const bReplyInput = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            assert.ok(bReplyInput, 'Bottom reply composer mounted');
            bReplyInput.value = 'Dirty Bottom Reply Value';
            bReplyInput.dispatchEvent({ type: 'input' });

            // Open drawer reply and dirty it
            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const dReplyInput = env.drawerAside.querySelector('.novel-reply-composer-input');
            assert.ok(dReplyInput, 'Drawer reply composer mounted');
            dReplyInput.value = 'Dirty Drawer Reply Value';
            dReplyInput.dispatchEvent({ type: 'input' });

            // Global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bReplyKey = draftsAdapter.getChapterReplyDraftKey(chapterId, 'b-comm-2');
            const dReplyKey = draftsAdapter.getBlockReplyDraftKey(chapterId, blockKey, 'd-comm-2');

            assert.strictEqual(env.store.load(bReplyKey), 'Dirty Bottom Reply Value', 'Bottom reply saved');
            assert.strictEqual(env.store.load(dReplyKey), 'Dirty Drawer Reply Value', 'Drawer reply saved');

            const activeInlineKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            assert.strictEqual(JSON.parse(env.store.load(activeInlineKey)).targetCommentId, 'b-comm-2');
            assert.strictEqual(JSON.parse(env.store.load(activeBlockKey)).commentId, 'd-comm-2');
        });

        test('3. Bottom Edit + Drawer Edit dirty: both keys preserved independently', () => {
            const chapterId = 'chap-flush-3';
            const blockKey = 'blk-flush-3';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-comm-3', rootId: 'b-comm-3', authorName: 'Author B', bodyText: 'Bottom body original'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-comm-3', rootId: 'd-comm-3', authorName: 'Author D', bodyText: 'Drawer body original'
            });

            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter
            });

            // Open bottom edit and dirty it
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const bEditInput = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            assert.ok(bEditInput, 'Bottom edit composer mounted');
            bEditInput.value = 'Bottom Edited Text';
            bEditInput.dispatchEvent({ type: 'input' });

            // Open drawer edit and dirty it
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dEditInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(dEditInput, 'Drawer edit composer mounted');
            dEditInput.value = 'Drawer Edited Text';
            dEditInput.dispatchEvent({ type: 'input' });

            // Global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bEditKey = draftsAdapter.getChapterEditDraftKey(chapterId, 'b-comm-3');
            const dEditKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, 'd-comm-3');

            assert.strictEqual(env.store.load(bEditKey), 'Bottom Edited Text');
            assert.strictEqual(env.store.load(dEditKey), 'Drawer Edited Text');

            const activeInlineKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            assert.strictEqual(JSON.parse(env.store.load(activeInlineKey)).commentId, 'b-comm-3');
            assert.strictEqual(JSON.parse(env.store.load(activeBlockKey)).commentId, 'd-comm-3');
        });

        test('4. one accepted-stale composer + one genuinely dirty composer: stale one not resurrected, dirty one saved', async () => {
            const chapterId = 'chap-flush-4';
            const blockKey = 'blk-flush-4';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            let bottomFetchResolve;
            bottomRootComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                fetch: () => new Promise(res => { bottomFetchResolve = res; })
            });
            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            // Submit bottom root
            env.bottomInput.value = 'Accepted Root Text';
            env.bottomInput.dispatchEvent({ type: 'input' });
            const submitPromise = bottomRootComposer.handleSubmit();

            // Resolve bottom root 201
            assert.ok(bottomFetchResolve, 'Fetch resolver available');
            bottomFetchResolve({ ok: true, status: 201, json: () => Promise.resolve({ commentId: 'root-new-1' }) });
            await submitPromise;

            // Remount bottom root with old accepted text without typing
            env.bottomInput.value = 'Accepted Root Text';

            // Drawer root has genuinely dirty text
            env.drawerInput.value = 'Genuine Drawer Root Text';
            env.drawerInput.dispatchEvent({ type: 'input' });

            // Global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bottomKey = draftsAdapter.getChapterRootDraftKey(chapterId);
            const drawerKey = draftsAdapter.getBlockRootDraftKey(chapterId, blockKey);

            assert.strictEqual(env.store.load(bottomKey), null, 'Stale accepted text did NOT resurrect');
            assert.strictEqual(env.store.load(drawerKey), 'Genuine Drawer Root Text', 'Dirty drawer draft saved');
        });

        test('4b. Bottom Reply + Drawer Edit dirty pair: both saved on one global flush, markers isolated', () => {
            const chapterId = 'chap-flush-4b';
            const blockKey = 'blk-flush-4b';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-pair-1', rootId: 'b-pair-1', authorName: 'Author B', bodyText: 'Bottom'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-pair-1', rootId: 'd-pair-1', authorName: 'Author D', bodyText: 'Drawer'
            });

            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter
            });

            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const bReplyInput = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            bReplyInput.value = 'Bottom Reply Pair Text';
            bReplyInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dEditInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            dEditInput.value = 'Drawer Edit Pair Text';
            dEditInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getChapterReplyDraftKey(chapterId, 'b-pair-1')),
                'Bottom Reply Pair Text'
            );
            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, 'd-pair-1')),
                'Drawer Edit Pair Text'
            );

            const activeInline = JSON.parse(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey(chapterId)));
            const activeBlock = JSON.parse(env.store.load(draftsAdapter.getBlockActiveMarkerKey(chapterId)));

            assert.strictEqual(activeInline.type, 'reply');
            assert.strictEqual(activeInline.targetCommentId, 'b-pair-1');
            assert.strictEqual(activeBlock.type, 'edit');
            assert.strictEqual(activeBlock.commentId, 'd-pair-1');
        });

        test('4c. Bottom Edit + Drawer Reply dirty pair: both saved on one global flush, markers isolated', () => {
            const chapterId = 'chap-flush-4c';
            const blockKey = 'blk-flush-4c';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-pair-2', rootId: 'b-pair-2', authorName: 'Author B', bodyText: 'Bottom orig'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-pair-2', rootId: 'd-pair-2', authorName: 'Author D', bodyText: 'Drawer orig'
            });

            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const bEditInput = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            bEditInput.value = 'Bottom Edit Pair Text';
            bEditInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const dReplyInput = env.drawerAside.querySelector('.novel-reply-composer-input');
            dReplyInput.value = 'Drawer Reply Pair Text';
            dReplyInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getChapterEditDraftKey(chapterId, 'b-pair-2')),
                'Bottom Edit Pair Text'
            );
            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockReplyDraftKey(chapterId, blockKey, 'd-pair-2')),
                'Drawer Reply Pair Text'
            );

            const activeInline = JSON.parse(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey(chapterId)));
            const activeBlock = JSON.parse(env.store.load(draftsAdapter.getBlockActiveMarkerKey(chapterId)));

            assert.strictEqual(activeInline.type, 'edit');
            assert.strictEqual(activeInline.commentId, 'b-pair-2');
            assert.strictEqual(activeBlock.type, 'reply');
            assert.strictEqual(activeBlock.commentId, 'd-pair-2');
        });
    });

    // ========================================================================
    // SECTION B: MARKER ISOLATION
    // ========================================================================
    describe('B. Marker Isolation', () => {

        test('5. active-inline survives Drawer lifecycle (open, switch, close, flush)', () => {
            const chapterId = 'chap-iso-5';
            const blockKey = 'blk-iso-5';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const inlineMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const inlinePayload = JSON.stringify({ type: 'reply', targetCommentId: 'b-target-5' });
            env.store.save(inlineMarkerKey, inlinePayload);

            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            // Drawer loaded
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            // Drawer flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            // Drawer switch block
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-iso-5b' }
            });

            // Drawer closed
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-closed',
                detail: { chapterId, blockKey }
            });

            assert.strictEqual(env.store.load(inlineMarkerKey), inlinePayload, 'active-inline remained completely untouched');
        });

        test('6. active-block survives Bottom lifecycle (root submit, reply open/close, edit cancel, feed replace)', async () => {
            const chapterId = 'chap-iso-6';
            const blockKey = 'blk-iso-6';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            const blockPayload = JSON.stringify({ type: 'edit', blockKey, commentId: 'd-comment-6' });
            env.store.save(activeBlockKey, blockPayload);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-c-6', rootId: 'b-c-6', authorName: 'Author', bodyText: 'Body'
            });

            bottomRootComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter,
                fetch: () => Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ commentId: 'new-r' }) })
            });
            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });
            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            // Submit bottom root
            env.bottomInput.value = 'Bottom Root Content';
            env.bottomInput.dispatchEvent({ type: 'input' });
            await bottomRootComposer.handleSubmit();

            // Open bottom reply then cancel it
            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const cancelBtn = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-cancel');
            if (cancelBtn) env.doc.dispatchEvent({ type: 'click', target: cancelBtn });

            // Open bottom edit then cancel it
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const editCancelBtn = env.bottomSection.querySelector('.novel-chapter-comment-edit-cancel');
            if (editCancelBtn) env.doc.dispatchEvent({ type: 'click', target: editCancelBtn });

            // Feed replacing event
            env.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

            assert.strictEqual(env.store.load(activeBlockKey), blockPayload, 'active-block remained completely untouched');
        });

        test('7. Drawer marker cleanup never deletes Bottom marker', () => {
            const chapterId = 'chap-iso-7';
            const blockKey = 'blk-iso-7';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const inlineMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const blockMarkerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

            env.store.save(inlineMarkerKey, JSON.stringify({ type: 'edit', commentId: 'b-edit-7' }));
            env.store.save(blockMarkerKey, JSON.stringify({ type: 'root', blockKey }));

            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            // Close drawer -> cleans drawer active-block
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-closed',
                detail: { chapterId, blockKey }
            });

            assert.strictEqual(env.store.load(blockMarkerKey), null, 'Drawer active-block cleaned');
            assert.strictEqual(
                env.store.load(inlineMarkerKey),
                JSON.stringify({ type: 'edit', commentId: 'b-edit-7' }),
                'Bottom active-inline preserved'
            );
        });

        test('8. Bottom marker cleanup never deletes Drawer marker', () => {
            const chapterId = 'chap-iso-8';
            const blockKey = 'blk-iso-8';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const inlineMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const blockMarkerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

            env.store.save(inlineMarkerKey, JSON.stringify({ type: 'reply', targetCommentId: 'b-reply-8' }));
            env.store.save(blockMarkerKey, JSON.stringify({ type: 'reply', blockKey, commentId: 'd-reply-8' }));

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-reply-8', rootId: 'b-reply-8', authorName: 'Author', bodyText: 'Body'
            });

            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });

            // Restored from marker; cancel bottom reply -> cleans active-inline
            const cancelBtn = env.bottomSection.querySelector('.novel-chapter-comment-reply-close-btn');
            assert.ok(cancelBtn, 'Bottom reply composer mounted from marker');
            cancelBtn.dispatchEvent({ type: 'click' });

            assert.strictEqual(env.store.load(inlineMarkerKey), null, 'Bottom active-inline removed');
            assert.strictEqual(
                env.store.load(blockMarkerKey),
                JSON.stringify({ type: 'reply', blockKey, commentId: 'd-reply-8' }),
                'Drawer active-block strictly preserved'
            );
        });
    });

    // ========================================================================
    // SECTION C: CHAPTER CHANGE
    // ========================================================================
    describe('C. Chapter Change — Old Context Authority', () => {

        test('9. Bottom Root old chapter key: saved under A, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-9a', 'blk-1');
            bottomRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.bottomInput.value = 'Chap A Root Value';
            env.bottomInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-9b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getChapterRootDraftKey('chap-c-9a')), 'Chap A Root Value');
            assert.strictEqual(env.store.load(draftsAdapter.getChapterRootDraftKey('chap-c-9b')), null);
        });

        test('10. Bottom Reply old chapter key: saved under A, A marker preserved, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-10a', 'blk-1');
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'comm-10', rootId: 'comm-10', authorName: 'Author', bodyText: 'Body'
            });
            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });

            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            input.value = 'Chap A Reply Value';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-10b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getChapterReplyDraftKey('chap-c-10a', 'comm-10')), 'Chap A Reply Value');
            assert.strictEqual(env.store.load(draftsAdapter.getChapterReplyDraftKey('chap-c-10b', 'comm-10')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey('chap-c-10b')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey('chap-c-10a')), JSON.stringify({ type: 'reply', targetCommentId: 'comm-10' }));
        });

        test('11. Bottom Edit old chapter key: saved under A, A marker preserved, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-11a', 'blk-1');
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'comm-11', rootId: 'comm-11', authorName: 'Author', bodyText: 'Body'
            });
            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            input.value = 'Chap A Edit Value';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-11b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getChapterEditDraftKey('chap-c-11a', 'comm-11')), 'Chap A Edit Value');
            assert.strictEqual(env.store.load(draftsAdapter.getChapterEditDraftKey('chap-c-11b', 'comm-11')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey('chap-c-11b')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getChapterActiveInlineMarkerKey('chap-c-11a')), JSON.stringify({ type: 'edit', commentId: 'comm-11' }));
        });

        test('12. Drawer Root old chapter/block key: saved under A, A marker removed, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-12a', 'blk-c-12');
            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId: 'chap-c-12a', blockKey: 'blk-c-12', contentVersion: 1 }
            });

            env.drawerInput.value = 'Chap A Drawer Root';
            env.drawerInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-12b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getBlockRootDraftKey('chap-c-12a', 'blk-c-12')), 'Chap A Drawer Root');
            assert.strictEqual(env.store.load(draftsAdapter.getBlockRootDraftKey('chap-c-12b', 'blk-c-12')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey('chap-c-12a')), null);
        });

        test('13. Drawer Reply old chapter/block key: saved under A, A marker removed, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-13a', 'blk-c-13');
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-c-13', rootId: 'd-c-13', authorName: 'Author', bodyText: 'Body'
            });
            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const input = env.drawerAside.querySelector('.novel-reply-composer-input');
            input.value = 'Chap A Drawer Reply';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-13b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getBlockReplyDraftKey('chap-c-13a', 'blk-c-13', 'd-c-13')), 'Chap A Drawer Reply');
            assert.strictEqual(env.store.load(draftsAdapter.getBlockReplyDraftKey('chap-c-13b', 'blk-c-13', 'd-c-13')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey('chap-c-13a')), null);
        });

        test('14. Drawer Edit old chapter/block key: saved under A, A marker removed, B untouched', () => {
            const env = setupCrossSurfaceFixture('chap-c-14a', 'blk-c-14');
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-c-14', rootId: 'd-c-14', authorName: 'Author', bodyText: 'Body'
            });
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const input = env.drawerAside.querySelector('.novel-edit-composer-input');
            input.value = 'Chap A Drawer Edit';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'chap-c-14b' }
            });

            assert.strictEqual(env.store.load(draftsAdapter.getBlockEditDraftKey('chap-c-14a', 'blk-c-14', 'd-c-14')), 'Chap A Drawer Edit');
            assert.strictEqual(env.store.load(draftsAdapter.getBlockEditDraftKey('chap-c-14b', 'blk-c-14', 'd-c-14')), null);
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey('chap-c-14a')), null);
        });
    });

    // ========================================================================
    // SECTION D: BOTTOM FEED REPLACEMENT
    // ========================================================================
    describe('D. Bottom Feed Replacement', () => {

        test('15. Reply dirty draft preserved on feed replacing', () => {
            const chapterId = 'chap-feed-15';
            const env = setupCrossSurfaceFixture(chapterId);
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-feed-15', rootId: 'b-feed-15', authorName: 'Author', bodyText: 'Body'
            });
            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });

            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            input.value = 'Preserved Feed Reply Text';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getChapterReplyDraftKey(chapterId, 'b-feed-15')),
                'Preserved Feed Reply Text'
            );
        });

        test('16. Edit dirty draft preserved on feed replacing', () => {
            const chapterId = 'chap-feed-16';
            const env = setupCrossSurfaceFixture(chapterId);
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-feed-16', rootId: 'b-feed-16', authorName: 'Author', bodyText: 'Body original'
            });
            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            input.value = 'Preserved Feed Edit Text';
            input.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getChapterEditDraftKey(chapterId, 'b-feed-16')),
                'Preserved Feed Edit Text'
            );
        });

        test('17. post-render same target restores exact draft when reopened', () => {
            const chapterId = 'chap-feed-17';
            const env = setupCrossSurfaceFixture(chapterId);
            const replyDraftKey = draftsAdapter.getChapterReplyDraftKey(chapterId, 'b-feed-17');
            env.store.save(replyDraftKey, 'Draft Restored Post-Render');

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-feed-17', rootId: 'b-feed-17', authorName: 'Author', bodyText: 'Body'
            });

            bottomReplyComposer.init(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, authenticated: true, isAuthenticated: true
            });

            // Feed rendered event
            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-comments-feed-rendered',
                detail: { chapterId }
            });

            // User clicks reply on target
            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            assert.ok(input);
            assert.strictEqual(input.value, 'Draft Restored Post-Render');
        });

        test('18. Edit baseline uses new authoritative server body after rerender', () => {
            const chapterId = 'chap-feed-18';
            const env = setupCrossSurfaceFixture(chapterId);

            // Stored draft D != new server body O2
            const editDraftKey = draftsAdapter.getChapterEditDraftKey(chapterId, 'b-feed-18');
            env.store.save(editDraftKey, 'User Draft text');

            // Render comment with new server body O2
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-feed-18', rootId: 'b-feed-18', authorName: 'Author', bodyText: 'New Server Body O2'
            });

            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            // Reopen edit on target
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            assert.strictEqual(input.value, 'User Draft text', 'Restored draft');
            assert.strictEqual(bottomEditComposer.getActiveOriginalBody(), 'New Server Body O2', 'Baseline is O2');

            // Typing O2 reverts to clean baseline -> cleans draft from store
            input.value = 'New Server Body O2';
            input.dispatchEvent({ type: 'input' });
            assert.strictEqual(env.store.load(editDraftKey), null, 'Reverting to O2 cleans draft');
        });
    });

    // ========================================================================
    // SECTION E: DRAWER SWITCH/CLOSE
    // ========================================================================
    describe('E. Drawer Block Switch and Close', () => {

        test('19. Root Block A -> B preserves A, isolates B', () => {
            const chapterId = 'chap-dsw-19';
            const env = setupCrossSurfaceFixture(chapterId, 'blk-A');
            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey: 'blk-A', contentVersion: 1 }
            });

            env.drawerInput.value = 'Draft for Block A';
            env.drawerInput.dispatchEvent({ type: 'input' });

            // Switch to Block B
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-B' }
            });

            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockRootDraftKey(chapterId, 'blk-A')),
                'Draft for Block A'
            );
            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockRootDraftKey(chapterId, 'blk-B')),
                null
            );
        });

        test('20. Reply Block A -> B preserves A, isolates B', () => {
            const chapterId = 'chap-dsw-20';
            const env = setupCrossSurfaceFixture(chapterId, 'blk-A');
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'c-rep-20', rootId: 'c-rep-20', authorName: 'Author', bodyText: 'Body'
            });

            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const input = env.drawerAside.querySelector('.novel-reply-composer-input');
            input.value = 'Reply Block A Draft';
            input.dispatchEvent({ type: 'input' });

            // Switch to Block B
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-B' }
            });

            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockReplyDraftKey(chapterId, 'blk-A', 'c-rep-20')),
                'Reply Block A Draft'
            );
            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockReplyDraftKey(chapterId, 'blk-B', 'c-rep-20')),
                null
            );
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey(chapterId)), null);
        });

        test('21. Edit Block A -> B preserves A, isolates B', () => {
            const chapterId = 'chap-dsw-21';
            const env = setupCrossSurfaceFixture(chapterId, 'blk-A');
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'c-edit-21', rootId: 'c-edit-21', authorName: 'Author', bodyText: 'Orig'
            });

            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const input = env.drawerAside.querySelector('.novel-edit-composer-input');
            input.value = 'Edit Block A Draft';
            input.dispatchEvent({ type: 'input' });

            // Switch to Block B
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-B' }
            });

            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockEditDraftKey(chapterId, 'blk-A', 'c-edit-21')),
                'Edit Block A Draft'
            );
            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockEditDraftKey(chapterId, 'blk-B', 'c-edit-21')),
                null
            );
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey(chapterId)), null);
        });

        test('22. manual Drawer close preserves draft but removes active-block', () => {
            const chapterId = 'chap-dsw-22';
            const blockKey = 'blk-dsw-22';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            env.drawerInput.value = 'Dirty Drawer Draft to Close';
            env.drawerInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-closed',
                detail: { chapterId, blockKey }
            });

            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockRootDraftKey(chapterId, blockKey)),
                'Dirty Drawer Draft to Close'
            );
            assert.strictEqual(env.store.load(draftsAdapter.getBlockActiveMarkerKey(chapterId)), null);
        });

        test('23. reopen same interaction restores canonical draft', () => {
            const chapterId = 'chap-dsw-23';
            const blockKey = 'blk-dsw-23';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            env.store.save(draftsAdapter.getBlockRootDraftKey(chapterId, blockKey), 'Pre-existing Drawer Draft');

            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });

            assert.strictEqual(env.drawerInput.value, 'Pre-existing Drawer Draft');
        });
    });

    // ========================================================================
    // SECTION F: SAME-ID CROSS-SURFACE
    // ========================================================================
    describe('F. Same-ID Cross-Surface Isolation', () => {

        test('24. Bottom Edit(C) and Drawer Edit(C) different values coexist on mounted lifecycle', () => {
            const chapterId = 'chap-same-24';
            const blockKey = 'blk-same-24';
            const sharedCommentId = 'c-shared-uuid-24';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'SharedAuthor', bodyText: 'Original Body'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'SharedAuthor', bodyText: 'Original Body'
            });

            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            // 1. Mount Bottom Edit on sharedCommentId and type
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const bInput = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            assert.ok(bInput, 'Bottom edit composer mounted');
            bInput.value = 'Bottom Edit for C';
            bInput.dispatchEvent({ type: 'input' });

            // 2. Mount Drawer Edit on sharedCommentId and type
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(dInput, 'Drawer edit composer mounted');
            dInput.value = 'Drawer Edit for C';
            dInput.dispatchEvent({ type: 'input' });

            // Verify both are mounted simultaneously
            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-edit-composer'), 'Bottom edit active');
            assert.ok(env.drawerAside.querySelector('.novel-edit-composer'), 'Drawer edit active');

            // 3. Global flush saves both under independent keys
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bKey = draftsAdapter.getChapterEditDraftKey(chapterId, sharedCommentId);
            const dKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, sharedCommentId);
            const activeInlineKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

            assert.strictEqual(env.store.load(bKey), 'Bottom Edit for C');
            assert.strictEqual(env.store.load(dKey), 'Drawer Edit for C');
            assert.notStrictEqual(bKey, dKey);

            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'edit',
                commentId: sharedCommentId
            });

            assert.deepStrictEqual(JSON.parse(env.store.load(activeBlockKey)), {
                type: 'edit',
                blockKey: blockKey,
                commentId: sharedCommentId
            });

            // 4. Close Drawer surface: Drawer unmounts, Bottom remains mounted
            env.doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });
            assert.strictEqual(env.drawerAside.querySelector('.novel-edit-composer'), null, 'Drawer edit unmounted on close');
            assert.strictEqual(env.store.load(dKey), 'Drawer Edit for C', 'Drawer edit draft preserved in store');
            assert.strictEqual(env.store.load(activeBlockKey), null, 'active-block is null after drawer close');

            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-edit-composer'), 'Bottom edit remains mounted');
            assert.strictEqual(bInput.value, 'Bottom Edit for C', 'Bottom edit text preserved');
            assert.strictEqual(env.store.load(bKey), 'Bottom Edit for C', 'Bottom draft unchanged');
            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'edit',
                commentId: sharedCommentId
            }, 'active-inline still exactly owns Bottom Edit(C)');

            // 5. Reopen Drawer and click edit to restore
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dInputRestored = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(dInputRestored, 'Drawer edit composer remounted');
            assert.strictEqual(dInputRestored.value, 'Drawer Edit for C', 'Drawer exact draft restored');

            assert.deepStrictEqual(JSON.parse(env.store.load(activeBlockKey)), {
                type: 'edit',
                blockKey: blockKey,
                commentId: sharedCommentId
            }, 'active-block again owns Drawer Edit(C)');

            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'edit',
                commentId: sharedCommentId
            }, 'active-inline remains unchanged');
        });

        test('25. Bottom Reply(C) and Drawer Reply(C) different values coexist on mounted lifecycle', () => {
            const chapterId = 'chap-same-25';
            const blockKey = 'blk-same-25';
            const sharedCommentId = 'c-shared-uuid-25';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'SharedAuthor', bodyText: 'Orig'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'SharedAuthor', bodyText: 'Orig'
            });

            bottomReplyComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                authenticated: true,
                isAuthenticated: true
            });
            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                isAuthenticated: true
            });

            // 1. Mount Bottom Reply on sharedCommentId and type
            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const bInput = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            assert.ok(bInput, 'Bottom reply composer mounted');
            bInput.value = 'Bottom Reply for C';
            bInput.dispatchEvent({ type: 'input' });

            // 2. Mount Drawer Reply on sharedCommentId and type
            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const dInput = env.drawerAside.querySelector('.novel-reply-composer-input');
            assert.ok(dInput, 'Drawer reply composer mounted');
            dInput.value = 'Drawer Reply for C';
            dInput.dispatchEvent({ type: 'input' });

            // Verify both are mounted simultaneously
            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-reply-composer'), 'Bottom reply active');
            assert.ok(env.drawerAside.querySelector('.novel-reply-composer'), 'Drawer reply active');

            // 3. Global flush saves both under independent keys
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const bKey = draftsAdapter.getChapterReplyDraftKey(chapterId, sharedCommentId);
            const dKey = draftsAdapter.getBlockReplyDraftKey(chapterId, blockKey, sharedCommentId);
            const activeInlineKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const activeBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

            assert.strictEqual(env.store.load(bKey), 'Bottom Reply for C');
            assert.strictEqual(env.store.load(dKey), 'Drawer Reply for C');
            assert.notStrictEqual(bKey, dKey);

            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'reply',
                targetCommentId: sharedCommentId
            });

            assert.deepStrictEqual(JSON.parse(env.store.load(activeBlockKey)), {
                type: 'reply',
                blockKey: blockKey,
                commentId: sharedCommentId
            });

            // 4. Close Drawer surface: Drawer unmounts, Bottom remains mounted
            env.doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });
            assert.strictEqual(env.drawerAside.querySelector('.novel-reply-composer'), null, 'Drawer reply unmounted on close');
            assert.strictEqual(env.store.load(dKey), 'Drawer Reply for C', 'Drawer reply draft preserved in store');
            assert.strictEqual(env.store.load(activeBlockKey), null, 'active-block is removed on close');

            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-reply-composer'), 'Bottom reply remains mounted');
            assert.strictEqual(bInput.value, 'Bottom Reply for C', 'Bottom reply text preserved');
            assert.strictEqual(env.store.load(bKey), 'Bottom Reply for C', 'Bottom Reply draft unchanged');
            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'reply',
                targetCommentId: sharedCommentId
            }, 'active-inline unchanged');

            // 5. Reopen Drawer and click reply to restore
            env.doc.dispatchEvent({ type: 'click', target: dComm.replyBtn });
            const dInputRestored = env.drawerAside.querySelector('.novel-reply-composer-input');
            assert.ok(dInputRestored, 'Drawer reply composer remounted');
            assert.strictEqual(dInputRestored.value, 'Drawer Reply for C', 'Drawer draft restored');

            assert.deepStrictEqual(JSON.parse(env.store.load(activeBlockKey)), {
                type: 'reply',
                blockKey: blockKey,
                commentId: sharedCommentId
            }, 'active-block restored for Drawer Reply(C)');

            assert.deepStrictEqual(JSON.parse(env.store.load(activeInlineKey)), {
                type: 'reply',
                targetCommentId: sharedCommentId
            }, 'Bottom active-inline remains unchanged');
        });

        test('26. successful Drawer mutation does not directly clear Bottom draft', async () => {
            const chapterId = 'chap-same-26';
            const blockKey = 'blk-same-26';
            const sharedCommentId = 'c-shared-uuid-26';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bKey = draftsAdapter.getChapterEditDraftKey(chapterId, sharedCommentId);
            const dKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, sharedCommentId);

            env.store.save(bKey, 'Bottom Draft to Preserve');

            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'User', bodyText: 'Orig'
            });

            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                fetch: () => Promise.resolve({ ok: true, status: 204 })
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const input = env.drawerAside.querySelector('.novel-edit-composer-input');
            input.value = 'Drawer Edited Value';
            input.dispatchEvent({ type: 'input' });

            await drawerEditComposer.handleSubmit();

            assert.strictEqual(env.store.load(dKey), null, 'Drawer draft deleted after 204');
            assert.strictEqual(env.store.load(bKey), 'Bottom Draft to Preserve', 'Bottom draft strictly untouched');
        });

        test('27. successful Bottom mutation does not directly clear Drawer draft', async () => {
            const chapterId = 'chap-same-27';
            const blockKey = 'blk-same-27';
            const sharedCommentId = 'c-shared-uuid-27';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            const bKey = draftsAdapter.getChapterEditDraftKey(chapterId, sharedCommentId);
            const dKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, sharedCommentId);

            env.store.save(dKey, 'Drawer Draft to Preserve');

            const bComm = addCommentToBottom(env.bottomList, {
                commentId: sharedCommentId, rootId: sharedCommentId, authorName: 'User', bodyText: 'Orig'
            });

            bottomEditComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                mutations: {
                    editComment: () => Promise.resolve({ ok: true, status: 204 })
                },
                commentsModule: {
                    refreshRootThread: () => Promise.resolve()
                }
            });

            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            input.value = 'Bottom Edited Value';
            input.dispatchEvent({ type: 'input' });

            await bottomEditComposer.handleSubmit();

            assert.strictEqual(env.store.load(bKey), null, 'Bottom draft deleted after success');
            assert.strictEqual(env.store.load(dKey), 'Drawer Draft to Preserve', 'Drawer draft strictly untouched');
        });
    });

    // ========================================================================
    // SECTION G: AUTH RETURN
    // ========================================================================
    describe('G. Auth Return Precedence and Hygiene', () => {

        test('28. URL metadata contains no draft body', () => {
            const guestUrl = drawerReplyComposer.buildGuestReturnUrl(
                '/novel/chapters/chap-1', 'blk-auth-28', 'root-auth-28', 'c-auth-28'
            );
            assert.ok(!guestUrl.includes('draft'), 'No draft in guest return URL');
            assert.ok(!guestUrl.includes('body'), 'No body in guest return URL');
            assert.ok(guestUrl.includes('discussionBlock=blk-auth-28'));
            assert.ok(guestUrl.includes('threadId=root-auth-28'));
            assert.ok(guestUrl.includes('replyTo=c-auth-28'));
            assert.ok(guestUrl.includes('intent=reply'));
        });

        test('29. valid URL restore wins over active-block restore', () => {
            const chapterId = '11111111-1111-1111-1111-111111111111';
            const validRootId = '22222222-2222-2222-2222-222222222222';
            const validReplyId = '33333333-3333-3333-3333-333333333333';
            const doc = new FakeDocument();
            const win = createFakeWindow(
                '?discussionBlock=blk-0123456789abcdef-1&threadId=' + validRootId + '&replyTo=' + validReplyId + '&intent=reply'
            );
            win.history.win = win;

            const chapterBody = doc.createElement('article');
            chapterBody.className = 'novel-reader-chapter-body';
            chapterBody.setAttribute('data-chapter-id', chapterId);
            chapterBody.setAttribute('data-content-version', '1');
            const blockEl = doc.createElement('p');
            blockEl.setAttribute('data-reader-block-key', 'blk-0123456789abcdef-1');
            chapterBody.appendChild(blockEl);
            doc.body.appendChild(chapterBody);

            const { store } = createMockDraftStore();

            // Set active-block marker targeting a DIFFERENT block
            store.save(
                draftsAdapter.getBlockActiveMarkerKey(chapterId),
                JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-2' })
            );

            let openRequests = [];
            doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
                openRequests.push(e.detail);
            });

            drawerRestore.init(doc, win, { draftStore: store, draftAdapter: draftsAdapter });

            // Assert exactly 1 open request, targeting URL block, NOT marker block
            assert.strictEqual(openRequests.length, 1);
            assert.strictEqual(openRequests[0].blockKey, 'blk-0123456789abcdef-1');
            assert.strictEqual(drawerRestore.getActiveRestore().source, 'url');
        });

        test('30. local canonical draft still restores after URL target handoff', () => {
            const chapterId = '11111111-1111-1111-1111-111111111111';
            const blockKey = 'blk-0123456789abcdef-1';
            const commentId = 'c-auth-30';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            // Stored local draft
            env.store.save(
                draftsAdapter.getBlockReplyDraftKey(chapterId, blockKey, commentId),
                'Canonical Stored Reply Draft'
            );

            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId, rootId: commentId, authorName: 'Author', bodyText: 'Body'
            });

            drawerReplyComposer.initReaderBlockDiscussionReplyComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter, isAuthenticated: true
            });

            // Restore module emits reply resume requested with authoritative detail
            env.doc.dispatchEvent({
                type: 'kiemlai:comment-reply-resume-requested',
                detail: { commentId, rootId: commentId, chapterId, blockKey }
            });

            const replyInput = env.drawerAside.querySelector('.novel-reply-composer-input');
            assert.ok(replyInput, 'Reply composer mounted on resume');
            assert.strictEqual(replyInput.value, 'Canonical Stored Reply Draft', 'Local draft restored');
        });

        test('31. malformed transient URL: marker not restored same turn, draft preserved', () => {
            const chapterId = '11111111-1111-1111-1111-111111111111';
            const doc = new FakeDocument();
            const win = createFakeWindow('?discussionBlock=invalid-malformed');
            win.history.win = win;

            const chapterBody = doc.createElement('article');
            chapterBody.className = 'novel-reader-chapter-body';
            chapterBody.setAttribute('data-chapter-id', chapterId);
            chapterBody.setAttribute('data-content-version', '1');
            doc.body.appendChild(chapterBody);

            const { store } = createMockDraftStore();
            const markerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            const draftKey = draftsAdapter.getBlockRootDraftKey(chapterId, 'blk-0123456789abcdef-1');

            store.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' }));
            store.save(draftKey, 'Draft Preserved After Bad URL');

            let openRequests = [];
            doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
                openRequests.push(e.detail);
            });

            drawerRestore.init(doc, win, { draftStore: store, draftAdapter: draftsAdapter });

            // Zero open events dispatched in same turn
            assert.strictEqual(openRequests.length, 0);
            // URL cleaned
            assert.strictEqual(win.location.search, '');
            // Marker and draft preserved
            assert.strictEqual(store.load(draftKey), 'Draft Preserved After Bad URL');
            assert.notStrictEqual(store.load(markerKey), null);
        });
    });

    // ========================================================================
    // SECTION H: RACE SAFETY
    // ========================================================================
    describe('H. Stale Async Response Race Safety Across Surfaces', () => {

        test('32. stale Drawer success cannot mutate Bottom current state', async () => {
            const chapterId = 'chap-race-32';
            const blockKey = 'blk-race-32';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            let drawerResolve;
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-32', rootId: 'd-32', authorName: 'User', bodyText: 'Body'
            });
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-32', rootId: 'b-32', authorName: 'User', bodyText: 'Body'
            });

            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                fetch: () => new Promise(res => { drawerResolve = res; })
            });
            bottomReplyComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                authenticated: true,
                isAuthenticated: true
            });

            // Start Drawer submit
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            dInput.value = 'Drawer Pending Text';
            dInput.dispatchEvent({ type: 'input' });
            const drawerPromise = drawerEditComposer.handleSubmit();

            // Revoke Drawer ownership passively via real lifecycle (block discussion requested for another block)
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-other-32' }
            });

            // Drawer edit composer must be cleanly closed by discussion-requested
            assert.strictEqual(env.drawerAside.querySelector('.novel-edit-composer'), null);

            // Mount and type into Bottom Reply
            env.doc.dispatchEvent({ type: 'click', target: bComm.replyBtn });
            const bInput = env.bottomSection.querySelector('.novel-chapter-comment-reply-composer-input');
            assert.ok(bInput);
            bInput.value = 'Active Bottom Text';
            bInput.dispatchEvent({ type: 'input' });

            // Flush Bottom if necessary so its exact draft exists
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            // Before resolving OLD Drawer response, capture:
            // - Bottom Reply draft key
            // - Bottom active-inline raw marker
            // - Drawer OLD draft key
            const bReplyDraftKey = draftsAdapter.getChapterReplyDraftKey(chapterId, 'b-32');
            const bMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            const dOldDraftKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, 'd-32');

            const beforeBottomDraft = env.store.load(bReplyDraftKey);
            const beforeBottomMarker = env.store.load(bMarkerKey);

            assert.strictEqual(beforeBottomDraft, 'Active Bottom Text');
            assert.notStrictEqual(beforeBottomMarker, null);

            // Stale Drawer response resolves
            drawerResolve({ ok: true, status: 204 });
            await drawerPromise;

            // After OLD Drawer 204 resolves assert:
            // - Bottom Reply still mounted;
            // - Bottom textarea exact text unchanged;
            // - Bottom draft key exact text unchanged;
            // - Bottom active-inline byte-for-byte unchanged;
            // - no Bottom storage key was removed by Drawer completion.
            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-reply-composer'));
            assert.strictEqual(bInput.value, 'Active Bottom Text');
            assert.strictEqual(env.store.load(bReplyDraftKey), beforeBottomDraft);
            assert.strictEqual(env.store.load(bMarkerKey), beforeBottomMarker);
            assert.strictEqual(env.store.load(dOldDraftKey), null, 'Drawer OLD draft key removed on 204');
        });

        test('33. stale Bottom success cannot mutate Drawer current state', async () => {
            const chapterId = 'chap-race-33';
            const blockKey = 'blk-race-33';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            let bottomResolve;
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-33', rootId: 'b-33', authorName: 'User', bodyText: 'Body'
            });
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-33', rootId: 'd-33', authorName: 'User', bodyText: 'Body'
            });

            bottomEditComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                mutations: {
                    editComment: () => new Promise(res => { bottomResolve = res; })
                },
                commentsModule: { refreshRootThread: () => Promise.resolve() }
            });
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter
            });

            // Start Bottom submit
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const bInput = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            bInput.value = 'Bottom Pending Edit';
            bInput.dispatchEvent({ type: 'input' });
            const bottomPromise = bottomEditComposer.handleSubmit();

            // Revoke Bottom ownership passively via real lifecycle (feed replacing)
            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-comments-feed-replacing',
                detail: { chapterId }
            });

            // Bottom edit composer must be unmounted by feed-replacing
            assert.strictEqual(env.bottomSection.querySelector('.novel-chapter-comment-edit-composer'), null);

            // Mount and type into Drawer Edit
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(dInput);
            dInput.value = 'Active Drawer Edit';
            dInput.dispatchEvent({ type: 'input' });

            // Synchronously flush so Drawer draft exists
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            // Before resolving OLD Bottom response, capture:
            // - Drawer Edit draft key
            // - Drawer active-block raw marker
            // - Bottom OLD draft key
            const dEditDraftKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, 'd-33');
            const dMarkerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            const bOldDraftKey = draftsAdapter.getChapterEditDraftKey(chapterId, 'b-33');

            const beforeDrawerDraft = env.store.load(dEditDraftKey);
            const beforeDrawerMarker = env.store.load(dMarkerKey);

            assert.strictEqual(beforeDrawerDraft, 'Active Drawer Edit');
            assert.notStrictEqual(beforeDrawerMarker, null);

            // Stale Bottom response resolves
            bottomResolve({ ok: true, status: 204 });
            await bottomPromise;

            // After OLD Bottom 204 assert:
            // - Drawer composer still mounted;
            // - Drawer textarea exact value unchanged;
            // - Drawer draft exact value unchanged;
            // - active-block byte-for-byte unchanged;
            // - no Drawer storage was deleted by stale Bottom completion.
            assert.ok(env.drawerAside.querySelector('.novel-edit-composer'));
            assert.strictEqual(dInput.value, 'Active Drawer Edit');
            assert.strictEqual(env.store.load(dEditDraftKey), beforeDrawerDraft);
            assert.strictEqual(env.store.load(dMarkerKey), beforeDrawerMarker);
            assert.strictEqual(env.store.load(bOldDraftKey), null, 'Bottom OLD draft key removed on 204');
        });

        test('34. accepted stale Bottom key + dirty Drawer key on global flush: Bottom does not resurrect, Drawer persists', async () => {
            const chapterId = 'chap-race-34';
            const blockKey = 'blk-race-34';
            const commentId = 'b-comm-34';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            // 1. Create exact Bottom comment C
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: commentId,
                rootId: commentId,
                authorName: 'Author B',
                bodyText: 'Original Body 34'
            });

            // 2. init Bottom Edit with pending editComment Promise
            let bottomEditResolve;
            bottomEditComposer.init(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                mutations: {
                    editComment: () => new Promise(res => { bottomEditResolve = res; })
                },
                commentsModule: { refreshRootThread: () => Promise.resolve() }
            });

            // 3. open Bottom Edit C
            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const bInput = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            assert.ok(bInput, 'Bottom Edit C mounted');

            // 4. genuine input A1
            bInput.value = 'Bottom Edit A1 Draft';
            bInput.dispatchEvent({ type: 'input' });

            // 5. submit A1, Promise PENDING
            const bottomPromise = bottomEditComposer.handleSubmit();

            // 6. passive teardown using kiemlai:chapter-comments-feed-replacing
            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-comments-feed-replacing',
                detail: { chapterId }
            });

            // 7. assert:
            // - composer unmounted;
            // - A1 preserved under canonical Bottom Edit key;
            // - relevant Bottom marker/lifecycle state is preserved according to existing Bottom D2B contract.
            assert.strictEqual(env.bottomSection.querySelector('.novel-chapter-comment-edit-composer'), null, 'Composer unmounted on feed-replacing');
            const bEditDraftKey = draftsAdapter.getChapterEditDraftKey(chapterId, commentId);
            assert.strictEqual(env.store.load(bEditDraftKey), 'Bottom Edit A1 Draft', 'A1 preserved under canonical Bottom Edit key');
            const bMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            assert.deepStrictEqual(JSON.parse(env.store.load(bMarkerKey)), { type: 'edit', commentId: commentId }, 'Bottom active-inline marker preserved');

            // 8. trigger the REAL feed-rendered/restore path so SAME Bottom Edit C is genuinely remounted from store
            env.doc.dispatchEvent({
                type: 'kiemlai:chapter-comments-feed-rendered',
                detail: { chapterId }
            });

            // 9. assert restored textarea === A1
            const remountedComposer = env.bottomSection.querySelector('.novel-chapter-comment-edit-composer');
            assert.ok(remountedComposer, 'Remounted Bottom Edit composer present');
            const remountedInput = remountedComposer.querySelector('.novel-chapter-comment-edit-input');
            assert.ok(remountedInput, 'Remounted input present');
            assert.strictEqual(remountedInput.value, 'Bottom Edit A1 Draft');

            // 10. dispatch NO new input

            // 11. simultaneously make a Drawer interaction genuinely dirty (Drawer Root is acceptable as the OTHER surface)
            drawerRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey, contentVersion: 1 }
            });
            env.drawerInput.value = 'Genuine Dirty Drawer Value';
            env.drawerInput.dispatchEvent({ type: 'input' });

            // 12. resolve OLD Bottom edit success
            assert.ok(bottomEditResolve);
            bottomEditResolve({ ok: true, status: 204 });
            await bottomPromise;

            // 13. assert:
            // - old response is stale;
            // - remounted Bottom Edit remains current;
            // - stored accepted A1 is removed;
            // - current Bottom textarea is not overwritten/closed by OLD response.
            assert.ok(env.bottomSection.querySelector('.novel-chapter-comment-edit-composer'), 'Remounted Bottom Edit remains current in DOM');
            assert.strictEqual(remountedInput.value, 'Bottom Edit A1 Draft', 'Current Bottom textarea not overwritten/closed');
            assert.strictEqual(env.store.load(bEditDraftKey), null, 'Stored accepted A1 is removed');

            // 14. global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            // 15. assert:
            // - A1 does NOT resurrect;
            // - Drawer dirty draft DOES persist;
            // - Drawer active-block remains correct.
            const dRootKey = draftsAdapter.getBlockRootDraftKey(chapterId, blockKey);
            const dActiveBlockKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
            assert.strictEqual(env.store.load(bEditDraftKey), null, 'A1 does NOT resurrect');
            assert.strictEqual(env.store.load(dRootKey), 'Genuine Dirty Drawer Value', 'Drawer dirty draft DOES persist');
            assert.deepStrictEqual(JSON.parse(env.store.load(dActiveBlockKey)), { type: 'root', blockKey: blockKey }, 'Drawer active-block remains correct');
        });

        test('35. accepted stale Drawer key + dirty Bottom key on global flush: Drawer does not resurrect, Bottom persists', async () => {
            const chapterId = 'chap-race-35';
            const blockKey = 'blk-race-35';
            const commentId = 'd-comm-35';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            // 1. Create exact Drawer comment C with live thread/root/body/Edit action
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: commentId,
                rootId: commentId,
                authorName: 'Author D',
                bodyText: 'Orig Body 35'
            });

            // 2. init Drawer Edit with pending mutation Promise
            let drawerEditResolve;
            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store,
                draftAdapter: draftsAdapter,
                fetch: () => new Promise(res => { drawerEditResolve = res; })
            });

            // 3. open Drawer Edit C
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const dInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(dInput, 'Drawer Edit C mounted');

            // 4. genuine A1 input
            dInput.value = 'Drawer Edit A1 Draft';
            dInput.dispatchEvent({ type: 'input' });

            // 5. submit pending
            const drawerPromise = drawerEditComposer.handleSubmit();

            // 6. passive revoke via kiemlai:block-discussion-requested for another block
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-requested',
                detail: { chapterId, blockKey: 'blk-other-35', contentVersion: 1 }
            });

            // 7. assert:
            // - Edit composer unmounted;
            // - A1 stored under canonical OLD Drawer Edit key.
            assert.strictEqual(env.drawerAside.querySelector('.novel-edit-composer'), null, 'Drawer Edit unmounted on discussion-requested');
            const dEditDraftKey = draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, commentId);
            assert.strictEqual(env.store.load(dEditDraftKey), 'Drawer Edit A1 Draft', 'A1 stored under canonical OLD Drawer Edit key');

            // 8. restore/open SAME original block through existing lifecycle
            env.drawerAside.setAttribute('data-block-key', blockKey);
            env.doc.dispatchEvent({
                type: 'kiemlai:block-discussion-loaded',
                detail: { chapterId, blockKey: blockKey, contentVersion: 1 }
            });

            // 9. reopen SAME Drawer Edit C through canonical live path so A1 is loaded from store
            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });

            // 10. assert textarea === A1
            const remountedDInput = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.ok(remountedDInput, 'Remounted Drawer Edit composer present');
            assert.strictEqual(remountedDInput.value, 'Drawer Edit A1 Draft');

            // 11. NO new input

            // 12. make Bottom interaction genuinely dirty (Bottom Root is acceptable as OTHER surface)
            bottomRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.bottomInput.value = 'Genuine Dirty Bottom Value';
            env.bottomInput.dispatchEvent({ type: 'input' });

            // 13. resolve OLD Drawer Edit success
            assert.ok(drawerEditResolve);
            drawerEditResolve({ ok: true, status: 204 });
            await drawerPromise;

            // 14. assert:
            // - old response stale;
            // - remounted Drawer Edit remains current;
            // - accepted A1 removed from storage.
            assert.ok(env.drawerAside.querySelector('.novel-edit-composer'), 'Remounted Drawer Edit remains current in DOM');
            assert.strictEqual(remountedDInput.value, 'Drawer Edit A1 Draft', 'Remounted textarea value preserved');
            assert.strictEqual(env.store.load(dEditDraftKey), null, 'Accepted A1 removed from storage');

            // 15. global flush
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            // 16. assert:
            // - A1 does NOT resurrect;
            // - Bottom dirty draft persists;
            // - Bottom marker/state remains correct.
            const bRootKey = draftsAdapter.getChapterRootDraftKey(chapterId);
            const bMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);
            assert.strictEqual(env.store.load(dEditDraftKey), null, 'A1 does NOT resurrect');
            assert.strictEqual(env.store.load(bRootKey), 'Genuine Dirty Bottom Value', 'Bottom dirty draft persists');
            assert.strictEqual(env.store.load(bMarkerKey), null, 'Bottom root writes no active-inline marker');
        });
    });

    // ========================================================================
    // SECTION I: RAW / CLEAN EDIT
    // ========================================================================
    describe('I. Raw Unicode and Clean Edit States', () => {

        test('36. exact Unicode, newlines, and leading/trailing whitespace retained across debounce, flush, and restore', () => {
            const chapterId = 'chap-raw-36';
            const blockKey = 'blk-raw-36';
            const rawDraft = '  Kiếm Lai\n\nĐạo hữu ✨\t  ';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);

            bottomRootComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            env.bottomInput.value = rawDraft;
            env.bottomInput.dispatchEvent({ type: 'input' });

            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            const saved = env.store.load(draftsAdapter.getChapterRootDraftKey(chapterId));
            assert.strictEqual(saved, rawDraft, 'Exact raw string preserved');

            // Re-init restores exact string
            bottomRootComposer.destroy();
            const env2 = setupCrossSurfaceFixture(chapterId, blockKey);
            bottomRootComposer.init(env2.doc, { draftStore: env.store, draftAdapter: draftsAdapter });
            assert.strictEqual(env2.bottomInput.value, rawDraft, 'Restored exact raw string');
        });

        test('37. equal-original Edit on Bottom stays clean during global flush', () => {
            const chapterId = 'chap-raw-37';
            const env = setupCrossSurfaceFixture(chapterId);
            const bComm = addCommentToBottom(env.bottomList, {
                commentId: 'b-orig-37', rootId: 'b-orig-37', authorName: 'Author', bodyText: 'Clean Server Body'
            });

            bottomEditComposer.init(env.doc, { draftStore: env.store, draftAdapter: draftsAdapter });

            env.doc.dispatchEvent({ type: 'click', target: bComm.editBtn });
            const input = env.bottomSection.querySelector('.novel-chapter-comment-edit-input');
            assert.strictEqual(input.value, 'Clean Server Body');

            // Global flush without editing
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getChapterEditDraftKey(chapterId, 'b-orig-37')),
                null,
                'Clean server body does not generate a draft'
            );
        });

        test('38. equal-original Edit in Drawer stays clean during global flush', () => {
            const chapterId = 'chap-raw-38';
            const blockKey = 'blk-raw-38';
            const env = setupCrossSurfaceFixture(chapterId, blockKey);
            const dComm = addCommentToDrawer(env.drawerContent, {
                commentId: 'd-orig-38', rootId: 'd-orig-38', authorName: 'Author', bodyText: 'Drawer Clean Server Body'
            });

            drawerEditComposer.initReaderBlockDiscussionEditComposer(env.doc, {
                draftStore: env.store, draftAdapter: draftsAdapter
            });

            env.doc.dispatchEvent({ type: 'click', target: dComm.editBtn });
            const input = env.drawerAside.querySelector('.novel-edit-composer-input');
            assert.strictEqual(input.value, 'Drawer Clean Server Body');

            // Global flush without editing
            env.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

            assert.strictEqual(
                env.store.load(draftsAdapter.getBlockEditDraftKey(chapterId, blockKey, 'd-orig-38')),
                null,
                'Drawer clean server body does not generate a draft'
            );
        });
    });

    // ========================================================================
    // SECTION J: MULTI-BLOCK
    // ========================================================================
    describe('J. Multi-Block Independence Within Same Chapter', () => {

        test('39. same chapter Block A Root != Block B Root', () => {
            const chapterId = 'chap-mb-39';
            const kA = draftsAdapter.getBlockRootDraftKey(chapterId, 'blk-A');
            const kB = draftsAdapter.getBlockRootDraftKey(chapterId, 'blk-B');

            assert.notStrictEqual(kA, kB);
            assert.ok(kA.includes('blk-A'));
            assert.ok(kB.includes('blk-B'));
        });

        test('40. Block A Reply(C) != Block B Reply(C)', () => {
            const chapterId = 'chap-mb-40';
            const commentId = 'c-target-40';
            const kA = draftsAdapter.getBlockReplyDraftKey(chapterId, 'blk-A', commentId);
            const kB = draftsAdapter.getBlockReplyDraftKey(chapterId, 'blk-B', commentId);

            assert.notStrictEqual(kA, kB);
            assert.ok(kA.includes('blk-A'));
            assert.ok(kB.includes('blk-B'));
        });

        test('41. Block A Edit(C) != Block B Edit(C)', () => {
            const chapterId = 'chap-mb-41';
            const commentId = 'c-target-41';
            const kA = draftsAdapter.getBlockEditDraftKey(chapterId, 'blk-A', commentId);
            const kB = draftsAdapter.getBlockEditDraftKey(chapterId, 'blk-B', commentId);

            assert.notStrictEqual(kA, kB);
            assert.ok(kA.includes('blk-A'));
            assert.ok(kB.includes('blk-B'));
        });
    });
});
