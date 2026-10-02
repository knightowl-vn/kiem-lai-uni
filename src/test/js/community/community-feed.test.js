/**
 * Unit & Contract Tests for CommunityFeed Controller (MS-07B8.1 / MS-07B8-RETRO-CORRECTIVE)
 *
 * Tests:
 * 1. NEWEST load-more pending -> switch to FEATURED -> FEATURED starts -> old NEWEST response ignored
 * 2. FEATURED pending -> switch to NEWEST -> old FEATURED ignored
 * 3. NEWEST load-more pending -> composer success (refreshFeed('NEWEST')) -> fresh page0 starts -> old load-more ignored
 * 4. Stale response ignored (gen < currentFeedGeneration does not corrupt nextCursor/hasNext/DOM)
 * 5. Feed AJAX failure displays visible minimal error feedback with retry affordance
 * 6. Error on load-more preserves existing loaded cards
 * 7. Retry re-executes fetch and succeeds without duplicate cards or duplicate listeners
 */
const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');

class FakeClassList {
    constructor() {
        this.classes = new Set();
    }
    add(...cls) {
        cls.forEach(c => this.classes.add(c));
    }
    remove(...cls) {
        cls.forEach(c => this.classes.delete(c));
    }
    contains(c) {
        return this.classes.has(c);
    }
    toggle(c, force) {
        if (force === true) { this.classes.add(c); return true; }
        if (force === false) { this.classes.delete(c); return false; }
        if (this.classes.has(c)) { this.classes.delete(c); return false; }
        this.classes.add(c); return true;
    }
}

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName ? tagName.toUpperCase() : 'DIV';
        this.attributes = Object.create(null);
        this.childNodes = [];
        this.parentNode = null;
        this.listeners = Object.create(null);
        this.classList = new FakeClassList();
        this._textContent = '';
        this._innerHTML = '';
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

    get className() { return this.getAttribute('class') || ''; }
    set className(val) { this.setAttribute('class', val); }

    get id() { return this.attributes['id'] || ''; }
    set id(val) {
        if (val) this.attributes['id'] = String(val);
        else delete this.attributes['id'];
    }

    get textContent() { return this._textContent; }
    set textContent(val) {
        this._textContent = String(val);
        this.childNodes = [];
    }

    get innerHTML() { return this._innerHTML; }
    set innerHTML(val) {
        this._innerHTML = String(val);
        if (val === '') {
            this.childNodes = [];
            this._textContent = '';
        }
    }

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

    insertBefore(newChild, refChild) {
        if (!refChild) return this.appendChild(newChild);
        newChild.parentNode = this;
        const idx = this.childNodes.indexOf(refChild);
        if (idx !== -1) {
            this.childNodes.splice(idx, 0, newChild);
        } else {
            this.childNodes.push(newChild);
        }
        return newChild;
    }

    remove() {
        if (this.parentNode) {
            const idx = this.parentNode.childNodes.indexOf(this);
            if (idx !== -1) {
                this.parentNode.childNodes.splice(idx, 1);
            }
            this.parentNode = null;
        }
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

    addEventListener(event, fn) {
        this.body.addEventListener(event, fn);
    }

    dispatchEvent(evt) {
        this.body.dispatchEvent(evt);
    }
}

function createDeferred() {
    let resolve, reject;
    const promise = new Promise((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

describe('CommunityFeed Module Tests (MS-07B8.1 / MS-07B8-RETRO-CORRECTIVE)', () => {
    let doc;
    let feedListEl;
    let emptyMsgEl;
    let loadMoreContainer;
    let loadMoreBtn;
    let spinnerEl;
    let sortDropdown;
    let sortTrigger;
    let sortLabel;
    let sortMenu;
    let changeSortNewestBtn;
    let changeSortFeaturedBtn;
    let CommunityFeed;
    let originalFetch;

    beforeEach(() => {
        doc = new FakeDocument();

        // Feed sort dropdown
        sortDropdown = doc.createElement('div');
        sortDropdown.id = 'communityFeedSortDropdown';

        sortTrigger = doc.createElement('button');
        sortTrigger.id = 'communityFeedSortTrigger';
        sortLabel = doc.createElement('span');
        sortLabel.id = 'communityFeedSortLabel';
        sortLabel.textContent = 'Mới nhất';
        sortTrigger.appendChild(sortLabel);
        sortDropdown.appendChild(sortTrigger);

        sortMenu = doc.createElement('div');
        sortMenu.id = 'communityFeedSortMenu';
        sortMenu.hidden = true;

        changeSortNewestBtn = doc.createElement('button');
        changeSortNewestBtn.setAttribute('data-action', 'change-feed-sort');
        changeSortNewestBtn.setAttribute('data-feed', 'NEWEST');
        changeSortNewestBtn.classList.add('is-selected');
        sortMenu.appendChild(changeSortNewestBtn);

        changeSortFeaturedBtn = doc.createElement('button');
        changeSortFeaturedBtn.setAttribute('data-action', 'change-feed-sort');
        changeSortFeaturedBtn.setAttribute('data-feed', 'FEATURED');
        sortMenu.appendChild(changeSortFeaturedBtn);

        sortDropdown.appendChild(sortMenu);
        doc.body.appendChild(sortDropdown);

        // Feed list
        feedListEl = doc.createElement('div');
        feedListEl.id = 'communityFeedList';
        feedListEl.setAttribute('data-selected-feed', 'NEWEST');
        feedListEl.setAttribute('data-next-cursor', 'cursor-page-1');
        feedListEl.setAttribute('data-has-next', 'true');
        feedListEl.setAttribute('data-authenticated', 'true');

        emptyMsgEl = doc.createElement('div');
        emptyMsgEl.id = 'emptyFeedMessage';
        emptyMsgEl.hidden = true;
        feedListEl.appendChild(emptyMsgEl);
        doc.body.appendChild(feedListEl);

        // Load more container
        loadMoreContainer = doc.createElement('div');
        loadMoreContainer.className = 'community-load-more-container';

        loadMoreBtn = doc.createElement('button');
        loadMoreBtn.id = 'communityLoadMoreBtn';
        loadMoreContainer.appendChild(loadMoreBtn);

        spinnerEl = doc.createElement('div');
        spinnerEl.id = 'feedLoadingSpinner';
        spinnerEl.hidden = true;
        loadMoreContainer.appendChild(spinnerEl);

        doc.body.appendChild(loadMoreContainer);

        // Mock window / global
        originalFetch = globalThis.fetch;

        // Mock CommunityPostCard
        const mockPostCard = {
            create: function (item) {
                const el = doc.createElement('article');
                el.className = 'community-post-card';
                el.setAttribute('data-post-id', String(item.id));
                const p = doc.createElement('p');
                p.className = 'post-caption';
                p.textContent = item.caption;
                el.appendChild(p);
                return el;
            }
        };

        // Load factory
        delete require.cache[require.resolve('../../../main/resources/static/js/community/community-feed.js')];
        const factory = require('../../../main/resources/static/js/community/community-feed.js');
        CommunityFeed = factory;
        CommunityFeed.resetForTesting();

        // Inject window.CommunityPostCard and document
        globalThis.document = doc;
        globalThis.window = {
            CommunityPostCard: mockPostCard,
            history: { pushState: () => {} }
        };

        CommunityFeed.init(doc);
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
        delete globalThis.window;
        delete globalThis.document;
        if (CommunityFeed) CommunityFeed.resetForTesting();
    });

    it('1. NEWEST load-more pending -> switch to FEATURED -> FEATURED starts -> old NEWEST response ignored', async () => {
        const newestDeferred = createDeferred();
        const featuredDeferred = createDeferred();

        const requestedUrls = [];
        globalThis.fetch = (url) => {
            requestedUrls.push(url);
            if (url.includes('cursor=cursor-page-1')) {
                return newestDeferred.promise;
            }
            if (url.includes('feed=FEATURED')) {
                return featuredDeferred.promise;
            }
            return Promise.reject(new Error('Unexpected URL: ' + url));
        };

        // Start load more for NEWEST
        const loadMorePromise = CommunityFeed.loadMorePosts(doc);
        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('cursor=cursor-page-1'));

        // While pending, switch to FEATURED
        const switchPromise = CommunityFeed.switchFeed('FEATURED', doc);
        assert.strictEqual(requestedUrls.length, 2);
        assert.ok(requestedUrls[1].includes('feed=FEATURED'));
        assert.strictEqual(CommunityFeed.getCurrentFeed(), 'FEATURED');

        // Resolve old NEWEST response late
        newestDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 101, caption: 'Stale NEWEST post' }],
                hasNext: false,
                nextCursor: null
            })
        });
        await loadMorePromise;

        // Old NEWEST items must NOT be rendered into feed
        const staleCards = feedListEl.querySelectorAll('[data-post-id="101"]');
        assert.strictEqual(staleCards.length, 0);

        // Resolve FEATURED response
        featuredDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 201, caption: 'Featured post 1' }],
                hasNext: false,
                nextPage: null
            })
        });
        await switchPromise;

        // FEATURED item MUST be rendered
        const featuredCards = feedListEl.querySelectorAll('[data-post-id="201"]');
        assert.strictEqual(featuredCards.length, 1);
        assert.strictEqual(CommunityFeed.getCurrentFeed(), 'FEATURED');
    });

    it('2. FEATURED pending -> switch to NEWEST -> old FEATURED ignored', async () => {
        const featuredDeferred = createDeferred();
        const newestDeferred = createDeferred();

        const requestedUrls = [];
        globalThis.fetch = (url) => {
            requestedUrls.push(url);
            if (url.includes('feed=FEATURED')) {
                return featuredDeferred.promise;
            }
            if (url.includes('feed=NEWEST')) {
                return newestDeferred.promise;
            }
            return Promise.reject(new Error('Unexpected URL: ' + url));
        };

        // Switch to FEATURED
        const featuredPromise = CommunityFeed.switchFeed('FEATURED', doc);
        assert.strictEqual(requestedUrls.length, 1);

        // Immediately switch back to NEWEST while FEATURED is pending
        const newestPromise = CommunityFeed.switchFeed('NEWEST', doc);
        assert.strictEqual(requestedUrls.length, 2);
        assert.strictEqual(CommunityFeed.getCurrentFeed(), 'NEWEST');

        // Resolve FEATURED late
        featuredDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 999, caption: 'Late FEATURED post' }],
                hasNext: true,
                nextPage: 1
            })
        });
        await featuredPromise;

        // Must not contain 999
        assert.strictEqual(feedListEl.querySelectorAll('[data-post-id="999"]').length, 0);

        // Resolve NEWEST
        newestDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 1001, caption: 'Fresh NEWEST post' }],
                hasNext: false,
                nextCursor: null
            })
        });
        await newestPromise;

        // Must contain 1001
        assert.strictEqual(feedListEl.querySelectorAll('[data-post-id="1001"]').length, 1);
        assert.strictEqual(CommunityFeed.getCurrentFeed(), 'NEWEST');
    });

    it('3. NEWEST load-more pending -> composer success (refreshFeed("NEWEST")) -> fresh page0 starts -> old load-more ignored', async () => {
        const loadMoreDeferred = createDeferred();
        const refreshDeferred = createDeferred();

        const requestedUrls = [];
        globalThis.fetch = (url) => {
            requestedUrls.push(url);
            if (url.includes('cursor=cursor-page-1')) {
                return loadMoreDeferred.promise;
            }
            return refreshDeferred.promise;
        };

        // Trigger load-more
        const loadMorePromise = CommunityFeed.loadMorePosts(doc);
        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('cursor=cursor-page-1'));

        // Composer creates a post and triggers refreshFeed('NEWEST')
        const refreshPromise = CommunityFeed.refreshFeed('NEWEST', doc);
        assert.strictEqual(requestedUrls.length, 2);
        // Refresh must be fresh page 0 (no cursor parameter)
        assert.ok(!requestedUrls[1].includes('cursor='));

        // Resolve old loadMore
        loadMoreDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 555, caption: 'Old page 1 post' }],
                hasNext: true,
                nextCursor: 'cursor-page-2'
            })
        });
        await loadMorePromise;

        // Old page 1 post 555 must NOT exist
        assert.strictEqual(feedListEl.querySelectorAll('[data-post-id="555"]').length, 0);

        // Resolve fresh refresh
        refreshDeferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 777, caption: 'Brand new post from composer' }],
                hasNext: false,
                nextCursor: null
            })
        });
        await refreshPromise;

        // Fresh post 777 must be in feed
        assert.strictEqual(feedListEl.querySelectorAll('[data-post-id="777"]').length, 1);
    });

    it('4. Stale response ignored (gen < currentFeedGeneration does not corrupt nextCursor/hasNext/DOM)', async () => {
        const gen0Deferred = createDeferred();
        const gen1Deferred = createDeferred();

        globalThis.fetch = (url) => {
            if (CommunityFeed.getCurrentGeneration() === 0) {
                return gen0Deferred.promise;
            }
            return gen1Deferred.promise;
        };

        // Start request in gen 0
        const gen0Promise = CommunityFeed.loadMorePosts(doc);

        // Increment generation via switch
        const gen1Promise = CommunityFeed.switchFeed('FEATURED', doc);
        assert.strictEqual(CommunityFeed.getCurrentGeneration(), 1);

        // Resolve gen0 with cursor and items
        gen0Deferred.resolve({
            ok: true,
            json: async () => ({
                items: [{ id: 888, caption: 'Stale gen 0' }],
                hasNext: true,
                nextCursor: 'corrupting-cursor'
            })
        });
        await gen0Promise;

        // Assert state was NOT corrupted
        assert.notStrictEqual(CommunityFeed.getNextCursor(), 'corrupting-cursor');
        assert.strictEqual(feedListEl.querySelectorAll('[data-post-id="888"]').length, 0);

        // Clean up gen1
        gen1Deferred.resolve({
            ok: true,
            json: async () => ({
                items: [],
                hasNext: false,
                nextPage: null
            })
        });
        await gen1Promise;
    });

    it('5. Feed AJAX failure displays visible minimal error feedback with retry affordance', async () => {
        globalThis.fetch = () => Promise.resolve({
            ok: false,
            status: 500
        });

        const result = await CommunityFeed.switchFeed('NEWEST', doc);
        assert.strictEqual(result, false);

        // Error card should be present and visible
        const errEl = doc.getElementById('feedErrorMessage');
        assert.ok(errEl, 'feedErrorMessage element should exist');
        assert.strictEqual(errEl.hidden, false);

        const retryBtn = errEl.querySelector('[data-action="retry-feed"]');
        assert.ok(retryBtn, 'Retry button should exist');
        assert.strictEqual(retryBtn.textContent, 'Thử lại');
    });

    it('6. Error on load-more preserves existing loaded cards', async () => {
        // Pre-populate with initial post card
        const initialCard = doc.createElement('article');
        initialCard.className = 'community-post-card';
        initialCard.setAttribute('data-post-id', 'existing-1');
        feedListEl.insertBefore(initialCard, emptyMsgEl);

        assert.strictEqual(feedListEl.querySelectorAll('.community-post-card').length, 1);

        // Simulate load-more failure
        globalThis.fetch = () => Promise.reject(new Error('Network error'));

        const result = await CommunityFeed.loadMorePosts(doc);
        assert.strictEqual(result, false);

        // Existing card must NOT be wiped
        assert.strictEqual(feedListEl.querySelectorAll('.community-post-card').length, 1);
        assert.ok(feedListEl.querySelector('[data-post-id="existing-1"]'));

        // Error message is displayed
        const errEl = doc.getElementById('feedErrorMessage');
        assert.ok(errEl && !errEl.hidden);
    });

    it('7. Retry re-executes fetch and succeeds without duplicate cards or duplicate listeners', async () => {
        let fetchCount = 0;
        globalThis.fetch = (url) => {
            fetchCount++;
            if (fetchCount === 1) {
                return Promise.resolve({ ok: false, status: 500 });
            }
            return Promise.resolve({
                ok: true,
                json: async () => ({
                    items: [{ id: 9999, caption: 'Post after retry' }],
                    hasNext: false,
                    nextCursor: null
                })
            });
        };

        // First attempt fails
        await CommunityFeed.switchFeed('NEWEST', doc);
        assert.strictEqual(fetchCount, 1);

        const errEl = doc.getElementById('feedErrorMessage');
        assert.ok(errEl && !errEl.hidden);
        const retryBtn = errEl.querySelector('[data-action="retry-feed"]');
        assert.ok(retryBtn);

        // Click retry
        retryBtn.click();

        // Wait a microtask tick for promise resolution
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(fetchCount, 2);
        // Error should be hidden
        assert.strictEqual(errEl.hidden, true);
        // Card rendered
        const cards = feedListEl.querySelectorAll('[data-post-id="9999"]');
        assert.strictEqual(cards.length, 1);
    });
});
