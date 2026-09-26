const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const SCRIPT_PATH = path.join(__dirname, '../../../main/resources/static/js/shared/interaction-reactions.js');
const InteractionReactions = require(SCRIPT_PATH);

// Helper to create mock classList
function createClassList(initialClasses = []) {
    const classes = new Set(initialClasses);
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        get value() { return Array.from(classes).join(' '); }
    };
}

// Helper to create a comprehensive mock DOM element
function createMockElement(tagName = 'div', initialAttrs = {}, ownerDoc = null) {
    const attrs = { ...initialAttrs };
    const classList = createClassList(initialAttrs.class ? initialAttrs.class.split(' ') : []);
    const eventListeners = {};
    const children = [];
    const style = { visibility: '', display: '' };
    let customRect = null;

    const el = {
        nodeType: 1,
        tagName: tagName.toUpperCase(),
        classList,
        style,
        disabled: false,
        hidden: false,
        textContent: '',
        parentNode: null,
        ownerDocument: ownerDoc,
        children,
        getAttribute: (k) => (k in attrs ? attrs[k] : null),
        setAttribute: (k, v) => {
            attrs[k] = String(v);
            if (k === 'class') {
                v.split(' ').forEach(c => classList.add(c));
            }
        },
        removeAttribute: (k) => {
            delete attrs[k];
            if (k === 'class') {
                Array.from(classList).forEach(c => classList.remove(c));
            }
        },
        hasAttribute: (k) => (k in attrs),
        addEventListener: (evt, fn) => {
            if (!eventListeners[evt]) eventListeners[evt] = [];
            eventListeners[evt].push(fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const fns = eventListeners[evtName] || [];
            for (const fn of fns) {
                await fn({ target: el, preventDefault: () => {}, stopPropagation: () => {}, ...evtObj });
            }
        },
        appendChild: (child) => {
            child.parentNode = el;
            child.ownerDocument = el.ownerDocument;
            children.push(child);
            if (ownerDoc && ownerDoc._notifyMutation) {
                ownerDoc._notifyMutation({ addedNodes: [child], target: el });
            }
            return child;
        },
        removeChild: (child) => {
            const idx = children.indexOf(child);
            if (idx !== -1) {
                children.splice(idx, 1);
                child.parentNode = null;
            }
            return child;
        },
        querySelectorAll: (sel) => {
            const results = [];
            function traverse(node) {
                for (const c of node.children) {
                    if (matchesSelector(c, sel)) {
                        results.push(c);
                    }
                    traverse(c);
                }
            }
            traverse(el);
            return results;
        },
        querySelector: (sel) => {
            function find(node) {
                for (const c of node.children) {
                    if (matchesSelector(c, sel)) {
                        return c;
                    }
                    const sub = find(c);
                    if (sub) return sub;
                }
                return null;
            }
            return find(el);
        },
        closest: (sel) => {
            let curr = el;
            while (curr) {
                if (matchesSelector(curr, sel)) {
                    return curr;
                }
                curr = curr.parentNode;
            }
            return null;
        },
        matches: (sel) => matchesSelector(el, sel),
        get id() { return attrs['id'] || ''; },
        set id(val) { attrs['id'] = String(val); },
        contains: (other) => {
            let curr = other;
            while (curr) {
                if (curr === el) return true;
                curr = curr.parentNode;
            }
            return false;
        },
        focus: () => {
            if (ownerDoc) ownerDoc.activeElement = el;
        },
        setCustomRect: (rect) => {
            customRect = rect;
        },
        getBoundingClientRect: () => {
            if (customRect) return customRect;
            return {
                top: 200,
                bottom: 232,
                left: 50,
                right: 150,
                width: 100,
                height: 32
            };
        }
    };

    return el;
}

function matchesSelector(el, sel) {
    if (!el || !sel) return false;
    if (sel.startsWith('.')) {
        return el.classList.contains(sel.slice(1));
    }

    let remaining = sel;
    const tagMatch = remaining.match(/^([a-zA-Z0-9_-]+)/);
    if (tagMatch) {
        const tag = tagMatch[1];
        remaining = remaining.slice(tag.length);
        if (el.tagName.toLowerCase() !== tag.toLowerCase()) {
            return false;
        }
    }

    if (!remaining) {
        return true;
    }

    const brackets = remaining.match(/\[[^\]]+\]/g);
    if (brackets && brackets.length > 0) {
        for (const b of brackets) {
            const inner = b.slice(1, -1);
            if (inner.includes('=')) {
                const eqIdx = inner.indexOf('=');
                const attrName = inner.slice(0, eqIdx);
                const rawVal = inner.slice(eqIdx + 1);
                const val = rawVal.replace(/^["']|["']$/g, '');
                if (el.getAttribute(attrName) !== val) {
                    return false;
                }
            } else {
                if (!el.hasAttribute(inner)) {
                    return false;
                }
            }
        }
        return true;
    }

    return false;
}

function createMockDocument() {
    const docListeners = {};
    const observers = [];

    const doc = {
        body: null,
        activeElement: null,
        defaultView: null,
        _getListeners: () => docListeners,
        createElement: (tagName) => createMockElement(tagName, {}, doc),
        addEventListener: (evt, fn) => {
            if (!docListeners[evt]) docListeners[evt] = [];
            docListeners[evt].push(fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const fns = docListeners[evtName] || [];
            for (const fn of fns) {
                await fn({ target: evtObj.target || doc.body, preventDefault: () => {}, stopPropagation: () => {}, ...evtObj });
            }
        },
        querySelectorAll: (sel) => {
            if (!doc.body) return [];
            const results = [];
            if (matchesSelector(doc.body, sel)) results.push(doc.body);
            results.push(...doc.body.querySelectorAll(sel));
            return results;
        },
        querySelector: (sel) => {
            if (!doc.body) return null;
            if (matchesSelector(doc.body, sel)) return doc.body;
            return doc.body.querySelector(sel);
        },
        _notifyMutation: (mutation) => {
            for (const obs of observers) {
                obs.callback([mutation]);
            }
        }
    };

    class MockMutationObserver {
        constructor(callback) {
            this.callback = callback;
            observers.push(this);
        }
        observe(target, options) {
            this.target = target;
            this.options = options;
        }
        disconnect() {
            const idx = observers.indexOf(this);
            if (idx !== -1) observers.splice(idx, 1);
        }
    }

    doc.MutationObserver = MockMutationObserver;
    doc.defaultView = { MutationObserver: MockMutationObserver, innerWidth: 1024, innerHeight: 768 };
    doc.body = createMockElement('body', {}, doc);
    return doc;
}

describe('InteractionReactions Corrective Pass Unit Tests', () => {
    let doc;
    const TARGET_CHAPTER_ID = '11111111-1111-1111-1111-111111111111';

    beforeEach(() => {
        InteractionReactions.destroy();
        doc = createMockDocument();
        InteractionReactions.init(doc);
    });

    test('1. Dynamic inserted empty reaction host is automatically hydrated by MutationObserver', () => {
        const dynamicHost = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': '22222222-2222-2222-2222-222222222222',
            'data-reaction-total': '5',
            'data-reaction-current': 'FIRE',
            'data-reaction-count-fire': '5'
        }, doc);

        // Appending to doc.body triggers the mock MutationObserver
        doc.body.appendChild(dynamicHost);

        assert.strictEqual(dynamicHost.getAttribute('data-reaction-enhanced'), 'true');
        const trigger = dynamicHost.querySelector('[data-reaction-trigger]');
        assert.ok(trigger, 'Trigger should be auto-created on dynamic insertion');
        const emoji = dynamicHost.querySelector('[data-reaction-trigger-emoji]');
        assert.strictEqual(emoji.textContent, '🔥');
        const count = dynamicHost.querySelector('[data-reaction-trigger-count]');
        assert.strictEqual(count.textContent, '5');
    });

    test('2. Dynamic hydration does not install per-widget listeners', () => {
        const initialListeners = Object.keys(doc._getListeners()).reduce((acc, k) => acc + doc._getListeners()[k].length, 0);

        for (let i = 0; i < 5; i++) {
            const w = createMockElement('div', {
                'data-reaction-widget': '',
                'data-reaction-target-type': 'COMMENT',
                'data-reaction-target-id': `uuid-${i}`
            }, doc);
            doc.body.appendChild(w);
        }

        const afterListeners = Object.keys(doc._getListeners()).reduce((acc, k) => acc + doc._getListeners()[k].length, 0);
        assert.strictEqual(afterListeners, initialListeners, 'Listener count must be constant and not increase per widget');
    });

    test('3. Touch pointerdown + click on hybrid/fine-pointer environment opens palette without LOVE mutation', async () => {
        let fetchCalled = false;
        InteractionReactions.setFetch(async () => {
            fetchCalled = true;
            return { ok: true, json: async () => ({}) };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        // Pointerdown with touch on hybrid device
        await doc.dispatchEvent('pointerdown', { target: trigger, pointerType: 'touch' });
        // Subsequent click event
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'touch' });

        assert.strictEqual(palette.hidden, false, 'Palette must open on touch tap');
        assert.strictEqual(fetchCalled, false, 'Touch tap must NEVER send immediate LOVE');
    });

    test('4. Pen opens palette without mutation', async () => {
        let fetchCalled = false;
        InteractionReactions.setFetch(async () => {
            fetchCalled = true;
            return { ok: true, json: async () => ({}) };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        await doc.dispatchEvent('pointerdown', { target: trigger, pointerType: 'pen' });
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'pen' });

        assert.strictEqual(palette.hidden, false, 'Palette must open on pen tap');
        assert.strictEqual(fetchCalled, false, 'Pen tap must not send mutation');
    });

    test('5. Keyboard-generated click cannot LOVE-react or immediately close picker', async () => {
        let fetchCalled = false;
        InteractionReactions.setFetch(async () => {
            fetchCalled = true;
            return { ok: true, json: async () => ({}) };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        // Synthetic keyboard click (detail === 0)
        await doc.dispatchEvent('click', { target: trigger, detail: 0, pointerType: '' });

        assert.strictEqual(palette.hidden, false, 'Keyboard click must open palette');
        assert.strictEqual(fetchCalled, false, 'Keyboard click must not send LOVE');
    });

    test('6. Pointerover with mouse opens palette', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, true);

        await doc.dispatchEvent('pointerover', { target: widget, pointerType: 'mouse' });

        assert.strictEqual(palette.hidden, false, 'Mouse pointerover should open palette');
    });

    test('7. Pointerover touch/pen does not hover-open palette', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, true);

        await doc.dispatchEvent('pointerover', { target: widget, pointerType: 'touch' });
        assert.strictEqual(palette.hidden, true, 'Touch pointerover must not open palette');

        await doc.dispatchEvent('pointerover', { target: widget, pointerType: 'pen' });
        assert.strictEqual(palette.hidden, true, 'Pen pointerover must not open palette');
    });

    test('8. Actual delayed hover close (timer expiration)', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        InteractionReactions.openPalette(widget);
        assert.strictEqual(palette.hidden, false);

        const outsideEl = createMockElement('div', {}, doc);
        doc.body.appendChild(outsideEl);

        // Pointerout with relatedTarget outside widget starts timer
        await doc.dispatchEvent('pointerout', { target: widget, relatedTarget: outsideEl, pointerType: 'mouse' });

        // Palette is still open immediately (during 250ms grace window)
        assert.strictEqual(palette.hidden, false, 'Palette must stay open immediately after pointerout');

        // Wait for 280ms for timer expiration
        await new Promise(resolve => setTimeout(resolve, 280));

        assert.strictEqual(palette.hidden, true, 'Palette must close after hover timer expires');
    });

    test('9. Escape restores focus to trigger button', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        InteractionReactions.openPalette(widget);

        await doc.dispatchEvent('keydown', { key: 'Escape' });

        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, true);
        assert.strictEqual(doc.activeElement, trigger, 'Focus must be restored to trigger on Escape');
    });

    test('10. Viewport flip uses measured palette height, not fixed 110 threshold', () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        // Custom rect: trigger near top (top: 30px, height: 32px), palette height 50px
        trigger.setCustomRect({ top: 30, bottom: 62, left: 100, right: 200, width: 100, height: 32 });
        palette.setCustomRect({ top: 0, bottom: 50, left: 100, right: 240, width: 140, height: 50 });

        InteractionReactions.openPalette(widget);

        assert.ok(palette.classList.contains('kl-reaction-palette--bottom'), 'Palette should flip to bottom when top space is less than palette height + 8');
    });

    test('11. Horizontal placement uses measured palette width', () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        // Trigger near right edge (window width 1024, trigger left: 950, palette width 160)
        trigger.setCustomRect({ top: 200, bottom: 232, left: 950, right: 1020, width: 70, height: 32 });
        palette.setCustomRect({ top: 150, bottom: 194, left: 950, right: 1110, width: 160, height: 44 });

        InteractionReactions.openPalette(widget);

        assert.ok(palette.classList.contains('kl-reaction-palette--align-right'), 'Palette should align right when right space is less than palette width + 8');
    });

    test('12. aria-haspopup is absent from trigger', () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        assert.strictEqual(trigger.hasAttribute('aria-haspopup'), false, 'aria-haspopup must be absent');
    });

    test('13. aria-controls points to unique palette ID', () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        const paletteId = palette.getAttribute('id');
        assert.ok(paletteId && paletteId.startsWith('kl-reaction-palette-'), 'Palette must have a unique id');
        assert.strictEqual(trigger.getAttribute('aria-controls'), paletteId, 'Trigger aria-controls must match palette id');
    });

    test('14. Duplicate target widgets have DIFFERENT DOM palette IDs', () => {
        const widget1 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'same-uuid-123'
        }, doc);
        const widget2 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'same-uuid-123'
        }, doc);

        doc.body.appendChild(widget1);
        doc.body.appendChild(widget2);

        const palette1 = widget1.querySelector('[data-reaction-palette]');
        const palette2 = widget2.querySelector('[data-reaction-palette]');

        assert.notStrictEqual(palette1.getAttribute('id'), palette2.getAttribute('id'), 'Duplicate target widgets must have distinct palette DOM IDs');
    });

    test('15. Network/500 failure announces accessible error in status live region', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: false,
                status: 500,
                headers: { get: () => 'application/json' },
                json: async () => ({ message: 'Error' })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const fireOption = widget.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption });

        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('16. Login redirect handling navigates to login with returnTo', async () => {
        let redirectedTo = null;
        InteractionReactions.setFetch(async () => {
            return {
                status: 401,
                redirected: true,
                url: '/login',
                headers: { get: () => 'text/html' }
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        const fireOption = widget.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        const res = await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'FIRE', doc);

        assert.strictEqual(res, null);
    });

    test('17. Access-denied/non-JSON redirect does not attempt to apply response and announces error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                status: 200,
                redirected: true,
                url: 'http://localhost/access-denied',
                headers: { get: () => 'text/html' }
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID,
            'data-reaction-total': '2'
        }, doc);
        doc.body.appendChild(widget);

        await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'LOVE', doc);

        // State remains intact
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '2');
        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không có quyền thực hiện. Vui lòng thử lại.');
    });

    test('18. Non-JSON 200 response rejected and announces error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                redirected: false,
                url: '/api/interaction/reactions',
                headers: { get: () => 'text/html' },
                json: async () => ({})
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID
        }, doc);
        doc.body.appendChild(widget);

        await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'FIRE', doc);

        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('19. Negative count response rejected', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'NOVEL_CHAPTER',
                    targetId: TARGET_CHAPTER_ID,
                    counts: { LOVE: -1, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 0,
                    currentUserReaction: null
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'LOVE', doc);
        assert.strictEqual(res, null, 'Negative count response must be rejected');
        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('20. Mismatched totalCount rejected', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'NOVEL_CHAPTER',
                    targetId: TARGET_CHAPTER_ID,
                    counts: { LOVE: 1, FIRE: 2, HAHA: 0, SAD: 0 },
                    totalCount: 99, // Mismatched! Expected 3
                    currentUserReaction: 'FIRE'
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'FIRE', doc);
        assert.strictEqual(res, null, 'Mismatched totalCount must be rejected');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '0');
    });

    test('21. Unknown currentUserReaction rejected', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'NOVEL_CHAPTER',
                    targetId: TARGET_CHAPTER_ID,
                    counts: { LOVE: 1, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 1,
                    currentUserReaction: 'DISLIKE' // Invalid unknown enum
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'NOVEL_CHAPTER',
            'data-reaction-target-id': TARGET_CHAPTER_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('NOVEL_CHAPTER', TARGET_CHAPTER_ID, 'LOVE', doc);
        assert.strictEqual(res, null, 'Unknown currentUserReaction must be rejected');
    });

    test('22. Valid authoritative response still synchronizes duplicate widgets', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: 'sync-comment-id-123',
                    counts: { LOVE: 0, FIRE: 5, HAHA: 1, SAD: 0 },
                    totalCount: 6,
                    currentUserReaction: 'FIRE'
                })
            };
        });

        const widget1 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'sync-comment-id-123',
            'data-reaction-total': '0'
        }, doc);

        const widget2 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'sync-comment-id-123',
            'data-reaction-total': '0'
        }, doc);

        doc.body.appendChild(widget1);
        doc.body.appendChild(widget2);

        const fireOption1 = widget1.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption1 });

        assert.strictEqual(widget1.getAttribute('data-reaction-current'), 'FIRE');
        assert.strictEqual(widget1.getAttribute('data-reaction-total'), '6');
        assert.strictEqual(widget2.getAttribute('data-reaction-current'), 'FIRE');
        assert.strictEqual(widget2.getAttribute('data-reaction-total'), '6');

        const trigger1Emoji = widget1.querySelector('[data-reaction-trigger-emoji]');
        const trigger2Emoji = widget2.querySelector('[data-reaction-trigger-emoji]');
        assert.strictEqual(trigger1Emoji.textContent, '🔥');
        assert.strictEqual(trigger2Emoji.textContent, '🔥');
    });
});
