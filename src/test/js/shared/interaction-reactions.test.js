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
    let innerHtmlContent = '';

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
        get innerHTML() { return innerHtmlContent; },
        set innerHTML(val) { innerHtmlContent = String(val); },
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

    const pseudoNotMatch = remaining.match(/:not\(([^)]+)\)/);
    if (pseudoNotMatch) {
        const innerSel = pseudoNotMatch[1];
        if (matchesSelector(el, innerSel)) {
            return false;
        }
        remaining = remaining.replace(pseudoNotMatch[0], '');
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

    return !remaining;
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

describe('InteractionReactions MS-05I Corrective Pass Tests', () => {
    let doc;
    const TARGET_COMMENT_ID = '22222222-2222-2222-2222-222222222222';

    beforeEach(() => {
        InteractionReactions.destroy();
        doc = createMockDocument();
        InteractionReactions.init(doc);
    });

    function makeValidResponse(currentUserReaction = null, counts = { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 }, totalCount = 0) {
        return {
            targetType: 'COMMENT',
            targetId: TARGET_COMMENT_ID,
            counts: { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0, ...counts },
            totalCount: totalCount,
            currentUserReaction: currentUserReaction
        };
    }

    test('1. Dynamic inserted empty reaction host is automatically hydrated by MutationObserver with LIKE outline icon', () => {
        const dynamicHost = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0',
            'data-reaction-count-like': '0',
            'data-reaction-count-love': '0',
            'data-reaction-count-fire': '0',
            'data-reaction-count-haha': '0',
            'data-reaction-count-sad': '0'
        }, doc);

        doc.body.appendChild(dynamicHost);

        assert.strictEqual(dynamicHost.getAttribute('data-reaction-enhanced'), 'true');
        const trigger = dynamicHost.querySelector('[data-reaction-trigger]');
        assert.ok(trigger, 'Trigger should be auto-created on dynamic insertion');
        const iconSpan = dynamicHost.querySelector('[data-reaction-trigger-icon]');
        assert.ok(iconSpan);
        assert.ok(iconSpan.innerHTML.includes('kl-reaction-icon'), 'Should render SVG outline LIKE icon');
        assert.strictEqual(trigger.classList.contains('has-reaction'), false);
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

    test('3. Desktop quick click on unreacted trigger sends PUT with LIKE', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('LIKE', { LIKE: 1 }, 1)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, 'LIKE');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'LIKE');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '1');
        assert.ok(trigger.classList.contains('has-reaction'));
        assert.ok(trigger.classList.contains('is-like'));
    });

    test('4. Desktop quick click on active reaction trigger removes reaction (sends null)', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse(null, { LIKE: 0 }, 0)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-current': 'LIKE',
            'data-reaction-total': '1',
            'data-reaction-count-like': '1'
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        assert.ok(trigger.classList.contains('has-reaction'));

        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, null);
        assert.strictEqual(widget.hasAttribute('data-reaction-current'), false);
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '0');
        assert.strictEqual(trigger.classList.contains('has-reaction'), false);
    });

    test('3b. Desktop click on unreacted trigger while hover-open closes palette AND sends PUT with LIKE', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('LIKE', { LIKE: 1 }, 1)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        // Open palette via hover or openPalette
        InteractionReactions.openPalette(widget);
        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, false, 'Palette must be open');

        const trigger = widget.querySelector('[data-reaction-trigger]');
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        // Palette must be closed AND LIKE must be set
        assert.strictEqual(palette.hidden, true, 'Palette must close after clicking trigger');
        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, 'LIKE');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'LIKE');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '1');
    });

    test('4b. Desktop click on active reaction trigger while hover-open closes palette AND removes reaction', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse(null, { LIKE: 0 }, 0)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-current': 'LIKE',
            'data-reaction-total': '1',
            'data-reaction-count-like': '1'
        }, doc);
        doc.body.appendChild(widget);

        // Open palette
        InteractionReactions.openPalette(widget);
        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, false, 'Palette must be open');

        const trigger = widget.querySelector('[data-reaction-trigger]');
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        // Palette must be closed AND reaction must be removed
        assert.strictEqual(palette.hidden, true, 'Palette must close after clicking trigger');
        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, null);
        assert.strictEqual(widget.hasAttribute('data-reaction-current'), false);
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '0');
    });

    test('5. Desktop hover intent opens palette after 550ms delay', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, true);

        await doc.dispatchEvent('pointerover', { target: widget, pointerType: 'mouse' });

        // Immediately after pointerover, palette must still be hidden (hover intent delay)
        assert.strictEqual(palette.hidden, true, 'Palette must not open immediately on mouseover');

        // Wait for 580ms
        await new Promise(resolve => setTimeout(resolve, 580));

        assert.strictEqual(palette.hidden, false, 'Palette must open after 550ms hover intent delay');
    });

    test('6. Desktop hover intent cancelled if pointer leaves before 550ms', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        const outsideEl = createMockElement('div', {}, doc);
        doc.body.appendChild(widget);
        doc.body.appendChild(outsideEl);

        const palette = widget.querySelector('[data-reaction-palette]');
        assert.strictEqual(palette.hidden, true);

        await doc.dispatchEvent('pointerover', { target: widget, pointerType: 'mouse' });
        // Move out after 200ms
        await new Promise(resolve => setTimeout(resolve, 200));
        await doc.dispatchEvent('pointerout', { target: widget, relatedTarget: outsideEl, pointerType: 'mouse' });

        // Wait remaining time past 550ms
        await new Promise(resolve => setTimeout(resolve, 400));

        assert.strictEqual(palette.hidden, true, 'Palette must remain hidden if pointer left before hover timer');
    });

    test('7. Delayed hover close (250ms grace period)', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        InteractionReactions.openPalette(widget);
        assert.strictEqual(palette.hidden, false);

        const outsideEl = createMockElement('div', {}, doc);
        doc.body.appendChild(outsideEl);

        await doc.dispatchEvent('pointerout', { target: widget, relatedTarget: outsideEl, pointerType: 'mouse' });

        // Still open immediately
        assert.strictEqual(palette.hidden, false, 'Palette must stay open during grace window');

        // Wait 280ms
        await new Promise(resolve => setTimeout(resolve, 280));

        assert.strictEqual(palette.hidden, true, 'Palette must close after 250ms close delay');
    });

    test('8. Touch quick tap on unreacted trigger sets LIKE', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('LIKE', { LIKE: 1 }, 1)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        await doc.dispatchEvent('pointerdown', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 100 });
        await doc.dispatchEvent('pointerup', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 100 });
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'touch' });

        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, 'LIKE');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'LIKE');
    });

    test('9. Touch long press (500ms) opens palette and suppresses subsequent release click', async () => {
        let fetchCalled = false;
        InteractionReactions.setFetch(async () => {
            fetchCalled = true;
            return { ok: true, json: async () => ({}) };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        // Touch start
        await doc.dispatchEvent('pointerdown', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 100 });

        // Wait 530ms for long-press timer to fire
        await new Promise(resolve => setTimeout(resolve, 530));

        assert.strictEqual(palette.hidden, false, 'Palette must open on 500ms long press');

        // Subsequent release click event
        await doc.dispatchEvent('pointerup', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 100 });
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'touch' });

        assert.strictEqual(fetchCalled, false, 'Long press release click must be suppressed and NOT trigger mutation');
        assert.strictEqual(palette.hidden, false, 'Palette must stay open');
    });

    test('10. Touch movement > 10px aborts long press timer', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');

        await doc.dispatchEvent('pointerdown', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 100 });
        // Move 20px down (e.g. scroll)
        await doc.dispatchEvent('pointermove', { target: trigger, pointerType: 'touch', clientX: 100, clientY: 120 });

        // Wait 530ms
        await new Promise(resolve => setTimeout(resolve, 530));

        assert.strictEqual(palette.hidden, true, 'Palette must NOT open if scroll/movement exceeded 10px');
    });

    test('11. Palette renders all 5 canonical reaction options in exact order', () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const palette = widget.querySelector('[data-reaction-palette]');
        const options = palette.querySelectorAll('[data-reaction-option]');

        assert.strictEqual(options.length, 5);
        const types = options.map(opt => opt.getAttribute('data-reaction-type'));
        assert.deepStrictEqual(types, ['LIKE', 'LOVE', 'FIRE', 'HAHA', 'SAD']);
    });

    test('12. Clicking option in palette sets that reaction type', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('FIRE', { FIRE: 1 }, 1)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const fireOption = widget.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption });

        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, 'FIRE');
        assert.strictEqual(widget.getAttribute('data-reaction-current'), 'FIRE');
        assert.strictEqual(widget.getAttribute('data-reaction-total'), '1');
    });

    test('13. Clicking already-active option in palette removes reaction', async () => {
        let sentBody = null;
        InteractionReactions.setFetch(async (url, opts) => {
            sentBody = JSON.parse(opts.body);
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse(null, { FIRE: 0 }, 0)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-current': 'FIRE',
            'data-reaction-total': '1',
            'data-reaction-count-fire': '1'
        }, doc);
        doc.body.appendChild(widget);

        const fireOption = widget.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption });

        assert.ok(sentBody);
        assert.strictEqual(sentBody.reactionType, null);
        assert.strictEqual(widget.hasAttribute('data-reaction-current'), false);
    });

    test('14. Keyboard ArrowDown on trigger opens palette without mutation and focuses first option', async () => {
        let fetchCalled = false;
        InteractionReactions.setFetch(async () => {
            fetchCalled = true;
            return { ok: true, json: async () => ({}) };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');
        const firstOption = widget.querySelector('[data-reaction-option]');

        await doc.dispatchEvent('keydown', { key: 'ArrowDown', target: trigger });

        assert.strictEqual(palette.hidden, false, 'ArrowDown must open palette');
        assert.strictEqual(fetchCalled, false, 'ArrowDown must not mutate reaction');
        assert.strictEqual(doc.activeElement, firstOption, 'Focus should move to first option');
    });

    test('15. Keyboard Escape closes palette and restores focus to trigger button', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');
        const palette = widget.querySelector('[data-reaction-palette]');
        InteractionReactions.openPalette(widget);
        assert.strictEqual(palette.hidden, false);

        await doc.dispatchEvent('keydown', { key: 'Escape' });

        assert.strictEqual(palette.hidden, true);
        assert.strictEqual(doc.activeElement, trigger);
    });

    test('16. Single open palette invariant: opening widget B closes widget A', () => {
        const widgetA = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'uuid-A'
        }, doc);
        const widgetB = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': 'uuid-B'
        }, doc);

        doc.body.appendChild(widgetA);
        doc.body.appendChild(widgetB);

        const paletteA = widgetA.querySelector('[data-reaction-palette]');
        const paletteB = widgetB.querySelector('[data-reaction-palette]');

        InteractionReactions.openPalette(widgetA);
        assert.strictEqual(paletteA.hidden, false);

        InteractionReactions.openPalette(widgetB);
        assert.strictEqual(paletteA.hidden, true);
        assert.strictEqual(paletteB.hidden, false);
    });

    test('17. Outside click dismisses open palette', async () => {
        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        const outsideEl = createMockElement('div', {}, doc);
        doc.body.appendChild(widget);
        doc.body.appendChild(outsideEl);

        const palette = widget.querySelector('[data-reaction-palette]');
        InteractionReactions.openPalette(widget);
        assert.strictEqual(palette.hidden, false);

        await doc.dispatchEvent('click', { target: outsideEl });

        assert.strictEqual(palette.hidden, true);
    });

    test('18. In-flight mutation lock prevents simultaneous duplicate requests', async () => {
        let callCount = 0;
        let resolveRequest;
        const requestPromise = new Promise(r => { resolveRequest = r; });

        InteractionReactions.setFetch(async () => {
            callCount++;
            await requestPromise;
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('LIKE', { LIKE: 1 }, 1)
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const trigger = widget.querySelector('[data-reaction-trigger]');

        // Dispatch 2 clicks in rapid succession
        const p1 = doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });
        const p2 = doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        resolveRequest();
        await Promise.all([p1, p2]);

        assert.strictEqual(callCount, 1, 'Second rapid click while in-flight must be ignored');
    });

    test('19. Multi-surface synchronization updates both main feed and drawer widgets for same target', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => makeValidResponse('FIRE', { LIKE: 2, FIRE: 5, HAHA: 1 }, 8)
            };
        });

        const widget1 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);

        const widget2 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);

        doc.body.appendChild(widget1);
        doc.body.appendChild(widget2);

        const fireOption1 = widget1.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption1 });

        assert.strictEqual(widget1.getAttribute('data-reaction-current'), 'FIRE');
        assert.strictEqual(widget1.getAttribute('data-reaction-total'), '8');
        assert.strictEqual(widget2.getAttribute('data-reaction-current'), 'FIRE');
        assert.strictEqual(widget2.getAttribute('data-reaction-total'), '8');
    });

    test('20. Negative count in response is rejected with status error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: TARGET_COMMENT_ID,
                    counts: { LIKE: -1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 0,
                    currentUserReaction: null
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('COMMENT', TARGET_COMMENT_ID, 'LIKE', doc);
        assert.strictEqual(res, null);

        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('21. Mismatched totalCount is rejected with status error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: TARGET_COMMENT_ID,
                    counts: { LIKE: 1, LOVE: 2, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 99, // Mismatch
                    currentUserReaction: 'LIKE'
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('COMMENT', TARGET_COMMENT_ID, 'LIKE', doc);
        assert.strictEqual(res, null);
    });

    test('22. Unknown currentUserReaction is rejected with status error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: TARGET_COMMENT_ID,
                    counts: { LIKE: 1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 1,
                    currentUserReaction: 'DISLIKE' // Unknown
                })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID,
            'data-reaction-total': '0'
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('COMMENT', TARGET_COMMENT_ID, 'LIKE', doc);
        assert.strictEqual(res, null);
    });

    test('23. 500 error announces accessible error in status live region', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: false,
                status: 500,
                headers: { get: () => 'application/json' },
                json: async () => ({ message: 'Server error' })
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const fireOption = widget.querySelector('[data-reaction-option][data-reaction-type="FIRE"]');
        await doc.dispatchEvent('click', { target: fireOption });

        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('24. Login redirect 401 navigates cleanly with returnTo', async () => {
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
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('COMMENT', TARGET_COMMENT_ID, 'LIKE', doc);
        assert.strictEqual(res, null);
    });

    test('25. Non-JSON response rejected with error', async () => {
        InteractionReactions.setFetch(async () => {
            return {
                ok: true,
                status: 200,
                redirected: false,
                headers: { get: () => 'text/html' },
                json: async () => ({})
            };
        });

        const widget = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        doc.body.appendChild(widget);

        const res = await InteractionReactions.setReaction('COMMENT', TARGET_COMMENT_ID, 'LIKE', doc);
        assert.strictEqual(res, null);
        const statusEl = widget.querySelector('[data-reaction-status]');
        assert.strictEqual(statusEl.textContent, 'Không thể cập nhật biểu cảm. Vui lòng thử lại.');
    });

    test('26. A11y semantics: trigger has no aria-haspopup, aria-controls matches unique palette id, and multi-widgets have distinct ids', () => {
        const widget1 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);
        const widget2 = createMockElement('div', {
            'data-reaction-widget': '',
            'data-reaction-target-type': 'COMMENT',
            'data-reaction-target-id': TARGET_COMMENT_ID
        }, doc);

        doc.body.appendChild(widget1);
        doc.body.appendChild(widget2);

        const trigger1 = widget1.querySelector('[data-reaction-trigger]');
        const palette1 = widget1.querySelector('[data-reaction-palette]');
        const trigger2 = widget2.querySelector('[data-reaction-trigger]');
        const palette2 = widget2.querySelector('[data-reaction-palette]');

        // aria-haspopup must NOT be present
        assert.strictEqual(trigger1.getAttribute('aria-haspopup'), null, 'trigger1 must not have aria-haspopup');
        assert.strictEqual(trigger2.getAttribute('aria-haspopup'), null, 'trigger2 must not have aria-haspopup');

        // aria-controls must match palette id
        assert.ok(palette1.id, 'palette1 must have an id');
        assert.ok(palette2.id, 'palette2 must have an id');
        assert.notStrictEqual(palette1.id, palette2.id, 'palette ids must be globally distinct');
        assert.strictEqual(trigger1.getAttribute('aria-controls'), palette1.id);
        assert.strictEqual(trigger2.getAttribute('aria-controls'), palette2.id);

        // palette role and options
        assert.strictEqual(palette1.getAttribute('role'), 'group');
        assert.strictEqual(palette1.getAttribute('aria-label'), 'Chọn cảm xúc');
        const options = palette1.querySelectorAll('[data-reaction-option]');
        assert.strictEqual(options.length, 5);
        options.forEach(opt => {
            assert.strictEqual(opt.tagName, 'BUTTON');
            assert.strictEqual(opt.hasAttribute('aria-pressed'), true);
        });
    });
});
