const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

// ============================================================================
// Lightweight DOM Fixtures for Node Testing reader-accordion.js
// ============================================================================

class MockElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = { ...attributes };
        this.dataset = { ...attributes.dataset };
        this.childNodes = [];
        this._innerHTML = '';
        this.hidden = attributes.hidden || false;
        this.parentElement = null;
        this.eventListeners = {};
        this.scrollIntoViewCalls = [];
    }

    getAttribute(name) {
        if (name.startsWith('data-')) {
            const key = name.slice(5);
            return this.dataset[key] !== undefined ? this.dataset[key] : (this.attributes[name] || null);
        }
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name.startsWith('data-')) {
            const key = name.slice(5);
            this.dataset[key] = String(value);
        }
        if (name === 'hidden') {
            this.hidden = true;
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name.startsWith('data-')) {
            delete this.dataset[name.slice(5)];
        }
    }

    get innerHTML() {
        return this._innerHTML;
    }

    set innerHTML(html) {
        this._innerHTML = html;
        this.childNodes = [];
        // Parse simple tags with id and class for testing injection
        const idMatches = [...html.matchAll(/id="([^"]+)"/g)];
        for (const match of idMatches) {
            const child = new MockElement('A', { id: match[1], class: 'novel-reader-chapter-item' });
            child.parentElement = this;
            this.childNodes.push(child);
        }
    }

    scrollIntoView(options) {
        this.scrollIntoViewCalls.push(options);
    }

    addEventListener(type, listener, options) {
        if (!this.eventListeners[type]) {
            this.eventListeners[type] = [];
        }
        this.eventListeners[type].push({ listener, options });
    }

    matches(selector) {
        if (selector === '.novel-reader-volume-trigger') {
            return (this.attributes['class'] || '').includes('novel-reader-volume-trigger');
        }
        if (selector.startsWith('[data-volume-id="')) {
            const match = selector.match(/\[data-volume-id="([^"]+)"\]/);
            return match && this.getAttribute('data-volume-id') === match[1];
        }
        return false;
    }

    closest(selector) {
        let current = this;
        while (current) {
            if (selector === '.novel-reader-volume' && (current.attributes['class'] || '').includes('novel-reader-volume')) {
                return current;
            }
            current = current.parentElement;
        }
        return null;
    }

    querySelector(selector) {
        const results = this.querySelectorAll(selector);
        return results.length > 0 ? results[0] : null;
    }

    querySelectorAll(selector) {
        const results = [];
        const traverse = (node) => {
            for (const child of node.childNodes) {
                if (selector === '.novel-reader-volume-content' && (child.attributes['class'] || '').includes('novel-reader-volume-content')) {
                    results.push(child);
                } else if (selector === '.novel-reader-volume-trigger' && (child.attributes['class'] || '').includes('novel-reader-volume-trigger')) {
                    results.push(child);
                } else if (selector === '.novel-reader-volume-trigger[aria-expanded="true"]' && (child.attributes['class'] || '').includes('novel-reader-volume-trigger') && child.getAttribute('aria-expanded') === 'true') {
                    results.push(child);
                }
                traverse(child);
            }
        };
        traverse(this);
        return results;
    }
}

class MockDocument {
    constructor() {
        this.elementsById = new Map();
        this.root = new MockElement('BODY');
        this.readyState = 'complete';
        this.eventListeners = {};
    }

    register(element) {
        if (element.attributes && element.attributes.id) {
            this.elementsById.set(element.attributes.id, element);
        }
        for (const child of element.childNodes) {
            this.register(child);
        }
    }

    getElementById(id) {
        if (this.elementsById.has(id)) {
            return this.elementsById.get(id);
        }
        let found = null;
        const search = (node) => {
            if (found) return;
            if (node.attributes && node.attributes.id === id) {
                found = node;
                return;
            }
            for (const child of node.childNodes) {
                search(child);
            }
        };
        search(this.root);
        if (found) {
            this.elementsById.set(id, found);
        }
        return found;
    }

    querySelector(selector) {
        // Handle attribute selectors for volume lookup
        if (selector.includes('data-volume-sort-order="') || selector.includes('data-volume-id="')) {
            const sortOrderMatch = selector.match(/data-volume-sort-order="([^"]+)"/);
            const idMatch = selector.match(/data-volume-id="([^"]+)"/);
            const targetVal = sortOrderMatch ? sortOrderMatch[1] : (idMatch ? idMatch[1] : null);

            if (targetVal) {
                for (const elem of this.elementsById.values()) {
                    if (elem.getAttribute('data-volume-sort-order') === targetVal || elem.getAttribute('data-volume-id') === targetVal) {
                        return elem;
                    }
                }
            }
        }
        return this.root.querySelector(selector);
    }

    querySelectorAll(selector) {
        const results = [];
        for (const elem of this.elementsById.values()) {
            if (selector === '.novel-reader-volume-trigger[aria-expanded="true"]' && elem.getAttribute('aria-expanded') === 'true') {
                results.push(elem);
            }
        }
        return results;
    }

    addEventListener(type, listener) {
        if (!this.eventListeners[type]) {
            this.eventListeners[type] = [];
        }
        this.eventListeners[type].push(listener);
    }
}

// Load production script
const accordionModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-accordion.js'));

describe('reader-accordion.js — Contextual Chapter Locator & Accordion Tests', () => {

    let doc;
    let volume1Article, trigger1, panel1;
    let volume2Article, trigger2, panel2;
    let mockFetchCalls;

    beforeEach(() => {
        mockFetchCalls = [];
        global.fetch = async (url, options) => {
            mockFetchCalls.push({ url, options });
            if (url.includes('/volumes/vol-1/chapters')) {
                return {
                    ok: true,
                    status: 200,
                    text: async () => `
                        <div class="novel-reader-chapter-list">
                            <a id="chapter-1" class="novel-reader-chapter-item" href="/novel/chapters/chuong-1">Chương 1</a>
                            <a id="chapter-2" class="novel-reader-chapter-item" href="/novel/chapters/chuong-2">Chương 2</a>
                        </div>
                    `
                };
            }
            if (url.includes('/volumes/vol-2/chapters')) {
                return {
                    ok: true,
                    status: 200,
                    text: async () => `
                        <div class="novel-reader-chapter-list">
                            <a id="chapter-167" class="novel-reader-chapter-item" href="/novel/chapters/chuong-167">Chương 167</a>
                            <a id="chapter-168" class="novel-reader-chapter-item" href="/novel/chapters/chuong-168">Chương 168</a>
                        </div>
                    `
                };
            }
            return {
                ok: false,
                status: 404,
                text: async () => 'Not Found'
            };
        };

        doc = new MockDocument();
        global.document = doc;

        // Volume 1 (sortOrder = 1, id = vol-1)
        volume1Article = new MockElement('ARTICLE', {
            id: 'volume-article-1',
            class: 'novel-reader-volume',
            'data-volume-id': 'vol-1',
            'data-volume-sort-order': '1'
        });
        trigger1 = new MockElement('BUTTON', {
            id: 'trigger-1',
            class: 'novel-reader-volume-trigger',
            'data-volume-id': 'vol-1',
            'data-volume-sort-order': '1',
            'aria-controls': 'volume-chapters-vol-1',
            'aria-expanded': 'false'
        });
        panel1 = new MockElement('DIV', {
            id: 'volume-chapters-vol-1',
            class: 'novel-reader-volume-content',
            hidden: true
        });
        volume1Article.childNodes.push(trigger1, panel1);
        trigger1.parentElement = volume1Article;
        panel1.parentElement = volume1Article;

        // Volume 2 (sortOrder = 2, id = vol-2)
        volume2Article = new MockElement('ARTICLE', {
            id: 'volume-article-2',
            class: 'novel-reader-volume',
            'data-volume-id': 'vol-2',
            'data-volume-sort-order': '2'
        });
        trigger2 = new MockElement('BUTTON', {
            id: 'trigger-2',
            class: 'novel-reader-volume-trigger',
            'data-volume-id': 'vol-2',
            'data-volume-sort-order': '2',
            'aria-controls': 'volume-chapters-vol-2',
            'aria-expanded': 'false'
        });
        panel2 = new MockElement('DIV', {
            id: 'volume-chapters-vol-2',
            class: 'novel-reader-volume-content',
            hidden: true
        });
        volume2Article.childNodes.push(trigger2, panel2);
        trigger2.parentElement = volume2Article;
        panel2.parentElement = volume2Article;

        doc.root.childNodes.push(volume1Article, volume2Article);
        doc.register(volume1Article);
        doc.register(volume2Article);
    });

    test('1. Trang /novel thông thường không có params locator: không tự động fetch, không scroll', async () => {
        global.window = {
            location: { search: '' }
        };

        await accordionModule.initLocator();

        assert.strictEqual(mockFetchCalls.length, 0, 'Must not issue any AJAX fetch calls on ordinary landing');
        assert.strictEqual(trigger1.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(trigger2.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(panel1.hidden, true);
        assert.strictEqual(panel2.hidden, true);
    });

    test('2. Locator URL ?openVolume=2&locateChapter=167: mở Volume 2, lazy-load đúng 1 lần, scroll vào chapter-167', async () => {
        global.window = {
            location: { search: '?openVolume=2&locateChapter=167' }
        };

        await accordionModule.initLocator();

        // 1. Fetch volume 2 chapters
        assert.strictEqual(mockFetchCalls.length, 1, 'Must issue exactly 1 fetch call for target volume');
        assert.ok(mockFetchCalls[0].url.includes('/volumes/vol-2/chapters'));

        // 2. Volume 2 expanded
        assert.strictEqual(trigger2.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(panel2.hidden, false);
        assert.strictEqual(panel2.dataset.loaded, 'true');

        // 3. Registered children include chapter-167
        const chapterRow = doc.getElementById('chapter-167');
        assert.ok(chapterRow, 'Target chapter element must exist in DOM');

        // 4. scrollIntoView was called with block: center
        assert.strictEqual(chapterRow.scrollIntoViewCalls.length, 1);
        assert.deepStrictEqual(chapterRow.scrollIntoViewCalls[0], { block: 'center' });
    });

    test('3. Sai/không tồn tại volume (openVolume=999): fail-safe, không throw lỗi', async () => {
        global.window = {
            location: { search: '?openVolume=999&locateChapter=167' }
        };

        await assert.doesNotReject(async () => {
            await accordionModule.initLocator();
        }, 'Must fail safely without throwing on invalid volume ID');

        assert.strictEqual(mockFetchCalls.length, 0);
    });

    test('4. Volume tải thành công nhưng số chương không tồn tại trong danh sách: fail-safe, không throw lỗi', async () => {
        global.window = {
            location: { search: '?openVolume=1&locateChapter=999' }
        };

        await assert.doesNotReject(async () => {
            await accordionModule.initLocator();
        }, 'Must fail safely without throwing when chapter row is absent');

        assert.strictEqual(mockFetchCalls.length, 1);
        assert.strictEqual(trigger1.getAttribute('aria-expanded'), 'true');
    });

    test('5. Volume mục tiêu đã được nạp từ trước: không fetch trùng lặp, vẫn thực hiện scroll', async () => {
        // Preload volume 1
        panel1.dataset.loaded = 'true';
        panel1.innerHTML = '<a id="chapter-1" class="novel-reader-chapter-item" href="/novel/chapters/chuong-1">Chương 1</a>';

        global.window = {
            location: { search: '?openVolume=1&locateChapter=1' }
        };

        await accordionModule.initLocator();

        assert.strictEqual(mockFetchCalls.length, 0, 'Must NOT re-fetch already loaded volume');
        assert.strictEqual(trigger1.getAttribute('aria-expanded'), 'true');

        const chapter1 = doc.getElementById('chapter-1');
        assert.ok(chapter1);
        assert.strictEqual(chapter1.scrollIntoViewCalls.length, 1);
        assert.deepStrictEqual(chapter1.scrollIntoViewCalls[0], { block: 'center' });
    });

    test('6. Tuyệt đối không gắn class highlight/badge/selected lên thẻ chapter row', async () => {
        global.window = {
            location: { search: '?openVolume=2&locateChapter=167' }
        };

        await accordionModule.initLocator();

        const chapterRow = doc.getElementById('chapter-167');
        assert.ok(chapterRow);
        const classes = chapterRow.getAttribute('class') || '';
        assert.ok(!classes.includes('highlight'), 'No highlight class allowed');
        assert.ok(!classes.includes('selected'), 'No selected class allowed');
        assert.ok(!classes.includes('found'), 'No found class allowed');
        assert.ok(!classes.includes('search-chapter-exact-badge'), 'No search badge allowed');
    });
});
