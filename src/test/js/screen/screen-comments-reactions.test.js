const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommentPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));
const RelativeTime = require(path.join(__dirname, '../../../main/resources/static/js/shared/relative-time.js'));
const InteractionReactions = require(path.join(__dirname, '../../../main/resources/static/js/shared/interaction-reactions.js'));
const ScreenTitleComments = require(path.join(__dirname, '../../../main/resources/static/js/screen/screen-comments.js'));

function createClassList(initialClasses = []) {
    const classes = new Set(initialClasses);
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        clear: () => classes.clear(),
        get classes() { return classes; },
        get value() { return Array.from(classes).join(' '); }
    };
}

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
        get className() { return Array.from(classList.classes).join(' '); },
        set className(v) {
            attrs['class'] = String(v);
            classList.clear();
            String(v).split(' ').filter(Boolean).forEach(c => classList.add(c));
        },
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
        removeEventListener: (evt, fn) => {
            if (!eventListeners[evt]) return;
            eventListeners[evt] = eventListeners[evt].filter(f => f !== fn);
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
        insertBefore: (newChild, refChild) => {
            newChild.parentNode = el;
            newChild.ownerDocument = el.ownerDocument;
            const idx = children.indexOf(refChild);
            if (idx !== -1) {
                children.splice(idx, 0, newChild);
            } else {
                children.push(newChild);
            }
            if (ownerDoc && ownerDoc._notifyMutation) {
                ownerDoc._notifyMutation({ addedNodes: [newChild], target: el });
            }
            return newChild;
        },
        removeChild: (child) => {
            const idx = children.indexOf(child);
            if (idx !== -1) {
                child.parentNode = null;
                children.splice(idx, 1);
            }
            return child;
        },
        replaceWith: (newEl) => {
            if (!el.parentNode) return;
            const parent = el.parentNode;
            const idx = parent.children.indexOf(el);
            if (idx !== -1) {
                el.parentNode = null;
                newEl.parentNode = parent;
                newEl.ownerDocument = parent.ownerDocument;
                parent.children[idx] = newEl;
                if (ownerDoc && ownerDoc._notifyMutation) {
                    ownerDoc._notifyMutation({ addedNodes: [newEl], target: parent });
                }
            }
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
        set innerHTML(val) {
            innerHtmlContent = String(val);
            if (val === '') {
                children.length = 0;
            }
        },
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
        getBoundingClientRect: () => ({
            top: 200, bottom: 232, left: 50, right: 150, width: 100, height: 32
        })
    };

    return el;
}

function matchesSelector(el, sel) {
    if (!el || !sel) return false;
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
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

    if (!remaining) return true;

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
        head: null,
        activeElement: null,
        defaultView: null,
        _getListeners: () => docListeners,
        createElement: (tagName) => createMockElement(tagName, {}, doc),
        addEventListener: (evt, fn) => {
            if (!docListeners[evt]) docListeners[evt] = [];
            docListeners[evt].push(fn);
        },
        removeEventListener: (evt, fn) => {
            if (!docListeners[evt]) return;
            docListeners[evt] = docListeners[evt].filter(f => f !== fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const fns = docListeners[evtName] || [];
            for (const fn of fns) {
                await fn({ target: evtObj.target || doc.body, preventDefault: () => {}, stopPropagation: () => {}, ...evtObj });
            }
        },
        querySelectorAll: (sel) => {
            const results = [];
            if (doc.head) {
                if (matchesSelector(doc.head, sel)) results.push(doc.head);
                results.push(...doc.head.querySelectorAll(sel));
            }
            if (doc.body) {
                if (matchesSelector(doc.body, sel)) results.push(doc.body);
                results.push(...doc.body.querySelectorAll(sel));
            }
            return results;
        },
        querySelector: (sel) => {
            if (doc.head) {
                if (matchesSelector(doc.head, sel)) return doc.head;
                const found = doc.head.querySelector(sel);
                if (found) return found;
            }
            if (doc.body) {
                if (matchesSelector(doc.body, sel)) return doc.body;
                return doc.body.querySelector(sel);
            }
            return null;
        },
        getElementById: (id) => {
            return doc.querySelector('#' + id);
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
    doc.defaultView = {
        MutationObserver: MockMutationObserver,
        innerWidth: 1024,
        innerHeight: 768,
        InteractionReactions: InteractionReactions,
        CommentPresentation: CommentPresentation
    };
    doc.body = createMockElement('body', {}, doc);
    doc.head = createMockElement('head', {}, doc);
    return doc;
}

function setupScreenDiscussionDom(doc, titleId = '11111111-1111-1111-1111-111111111111', titleSlug = 'kiem-lai-phan-1', originEntryId = null) {
    const metaCsrf = createMockElement('meta', { name: '_csrf', content: 'test-csrf-token-12345' }, doc);
    const metaCsrfHeader = createMockElement('meta', { name: '_csrf_header', content: 'X-CSRF-TOKEN' }, doc);
    doc.head.appendChild(metaCsrf);
    doc.head.appendChild(metaCsrfHeader);

    const sectionAttrs = {
        id: 'screenDiscussion',
        class: 'screen-section-card screen-discussion-section',
        'data-title-id': titleId,
        'data-title-slug': titleSlug,
        'data-authenticated': 'true',
        'data-csrf-token': 'test-csrf-token-12345',
        'data-csrf-header': 'X-CSRF-TOKEN'
    };
    if (originEntryId) {
        sectionAttrs['data-entry-id'] = originEntryId;
    }
    const section = createMockElement('section', sectionAttrs, doc);

    const badge = createMockElement('span', { id: 'screenDiscussionCountBadge' }, doc);
    const status = createMockElement('div', { id: 'screenDiscussionStatus' }, doc);
    const threadList = createMockElement('div', { id: 'screenDiscussionThreadList' }, doc);
    const footer = createMockElement('div', { id: 'screenDiscussionFooter', hidden: '' }, doc);
    const loadMoreBtn = createMockElement('button', { id: 'screenDiscussionLoadMoreBtn' }, doc);
    footer.appendChild(loadMoreBtn);

    const composerForm = createMockElement('form', { id: 'screenRootComposerForm' }, doc);
    const composerInput = createMockElement('textarea', { id: 'screenRootComposerInput' }, doc);
    const composerSubmit = createMockElement('button', { id: 'screenRootComposerSubmit' }, doc);
    const composerError = createMockElement('span', { id: 'screenRootComposerError', hidden: '' }, doc);
    composerForm.appendChild(composerInput);
    composerForm.appendChild(composerSubmit);
    composerForm.appendChild(composerError);

    section.appendChild(badge);
    section.appendChild(composerForm);
    section.appendChild(status);
    section.appendChild(threadList);
    section.appendChild(footer);

    doc.body.appendChild(section);
    return section;
}

describe('Screen Comments Shared Reaction Integration Tests', () => {
    let doc;
    const TITLE_ID = '11111111-1111-1111-1111-111111111111';
    const ROOT_COMMENT_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const REPLY_COMMENT_ID = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

    beforeEach(() => {
        InteractionReactions.destroy();
        ScreenTitleComments.resetState();
        doc = createMockDocument();
        ScreenTitleComments.setCommentPresentation(CommentPresentation);
        ScreenTitleComments.setRelativeTime(RelativeTime);
    });

    afterEach(() => {
        InteractionReactions.destroy();
        ScreenTitleComments.destroy();
    });

    test('1. Root comment with zero-count reaction summary creates a COMMENT-target reaction widget host', () => {
        setupScreenDiscussionDom(doc, TITLE_ID);

        const comment = {
            id: ROOT_COMMENT_ID,
            author: { id: 'u1', displayName: 'Người Dùng A' },
            body: 'Bình luận mở đầu về phim Kiếm Lai',
            createdAt: '2026-11-03T10:00:00Z',
            tombstone: false,
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: ROOT_COMMENT_ID,
                counts: { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                totalCount: 0,
                currentUserReaction: null
            }
        };

        const commentEl = ScreenTitleComments.renderComment(comment, ROOT_COMMENT_ID, null, false, doc);
        assert.ok(commentEl, 'Comment element should be rendered');

        const widgetHost = commentEl.querySelector('[data-reaction-widget]');
        assert.ok(widgetHost, 'Reaction widget host must be created');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-type'), 'COMMENT');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-id'), ROOT_COMMENT_ID);
        assert.strictEqual(widgetHost.getAttribute('data-reaction-total'), '0');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-count-like'), '0');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-current'), null);
    });

    test('2. Active reply comment with summary creates its own reaction widget targeting reply UUID', () => {
        setupScreenDiscussionDom(doc, TITLE_ID);

        const thread = {
            root: { id: ROOT_COMMENT_ID, author: { id: 'u1', displayName: 'Root Author' }, tombstone: false },
            replies: []
        };

        const reply = {
            id: REPLY_COMMENT_ID,
            rootId: ROOT_COMMENT_ID,
            author: { id: 'u2', displayName: 'Reply Author' },
            body: 'Phản hồi cho bình luận gốc',
            createdAt: '2026-11-03T10:05:00Z',
            tombstone: false,
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: REPLY_COMMENT_ID,
                counts: { LIKE: 2, LOVE: 1, FIRE: 0, HAHA: 0, SAD: 0 },
                totalCount: 3,
                currentUserReaction: 'LIKE'
            }
        };

        const replyEl = ScreenTitleComments.renderComment(reply, ROOT_COMMENT_ID, thread, true, doc);
        assert.ok(replyEl, 'Reply element should be rendered');

        const widgetHost = replyEl.querySelector('[data-reaction-widget]');
        assert.ok(widgetHost, 'Reply reaction widget host must be created');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-type'), 'COMMENT');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-id'), REPLY_COMMENT_ID);
        assert.strictEqual(widgetHost.getAttribute('data-reaction-total'), '3');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-count-like'), '2');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-current'), 'LIKE');
    });

    test('3. Tombstones and deleted comments do not create an interactive reaction widget', () => {
        setupScreenDiscussionDom(doc, TITLE_ID);

        const deletedComment = {
            id: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
            tombstone: true,
            status: 'DELETED',
            body: 'Bình luận đã bị xóa',
            createdAt: '2026-11-03T10:00:00Z',
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
                counts: { LIKE: 1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                totalCount: 1,
                currentUserReaction: null
            }
        };

        const commentEl = ScreenTitleComments.renderComment(deletedComment, deletedComment.id, null, false, doc);
        assert.ok(commentEl, 'Tombstone card should be rendered');

        const widgetHost = commentEl.querySelector('[data-reaction-widget]');
        assert.strictEqual(widgetHost, null, 'Tombstone must NOT create an interactive reaction widget');
    });

    test('4. Dynamically appended thread with root and reply is automatically hydrated by MutationObserver', () => {
        setupScreenDiscussionDom(doc, TITLE_ID);
        InteractionReactions.init(doc);

        const threadData = {
            root: {
                id: ROOT_COMMENT_ID,
                author: { id: 'u1', displayName: 'Author 1' },
                body: 'Bình luận chính',
                createdAt: '2026-11-03T10:00:00Z',
                tombstone: false,
                reactionSummary: {
                    targetType: 'COMMENT',
                    targetId: ROOT_COMMENT_ID,
                    counts: { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 0,
                    currentUserReaction: null
                }
            },
            replies: [
                {
                    id: REPLY_COMMENT_ID,
                    rootId: ROOT_COMMENT_ID,
                    author: { id: 'u2', displayName: 'Author 2' },
                    body: 'Bình luận phản hồi',
                    createdAt: '2026-11-03T10:02:00Z',
                    tombstone: false,
                    reactionSummary: {
                        targetType: 'COMMENT',
                        targetId: REPLY_COMMENT_ID,
                        counts: { LIKE: 1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                        totalCount: 1,
                        currentUserReaction: 'LIKE'
                    }
                }
            ]
        };

        const threadEl = ScreenTitleComments.renderThread(threadData, doc);
        assert.ok(threadEl, 'Thread should be rendered');

        const threadList = doc.getElementById('screenDiscussionThreadList');
        threadList.appendChild(threadEl);

        const rootWidget = threadEl.querySelector(`[data-reaction-target-id="${ROOT_COMMENT_ID}"]`);
        assert.ok(rootWidget, 'Root widget must exist');
        assert.strictEqual(rootWidget.getAttribute('data-reaction-enhanced'), 'true', 'Root widget must be enhanced by MutationObserver');

        const replyWidget = threadEl.querySelector(`[data-reaction-target-id="${REPLY_COMMENT_ID}"]`);
        assert.ok(replyWidget, 'Reply widget must exist');
        assert.strictEqual(replyWidget.getAttribute('data-reaction-enhanced'), 'true', 'Reply widget must be enhanced by MutationObserver');

        const rootTrigger = rootWidget.querySelector('.kl-reaction-trigger');
        assert.ok(rootTrigger, 'Root trigger button must be present');
        assert.strictEqual(rootTrigger.classList.contains('has-reaction'), false);

        const replyTrigger = replyWidget.querySelector('.kl-reaction-trigger');
        assert.ok(replyTrigger, 'Reply trigger button must be present');
        assert.strictEqual(replyTrigger.classList.contains('has-reaction'), true);
        assert.strictEqual(replyTrigger.classList.contains('is-like'), true);
    });

    test('5. Title versus Episode detail pages use the identical COMMENT target ID and type', () => {
        setupScreenDiscussionDom(doc, TITLE_ID);

        const commentData = {
            id: ROOT_COMMENT_ID,
            author: { id: 'u1', displayName: 'Author A' },
            body: 'Cảm nhận về phim',
            createdAt: '2026-11-03T10:00:00Z',
            tombstone: false,
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: ROOT_COMMENT_ID,
                counts: { LIKE: 5, LOVE: 2, FIRE: 1, HAHA: 0, SAD: 0 },
                totalCount: 8,
                currentUserReaction: 'FIRE'
            }
        };

        // Render on title page (originScreenEntryId is null)
        ScreenTitleComments.init(doc);
        const titlePageComment = ScreenTitleComments.renderComment(commentData, ROOT_COMMENT_ID, null, false, doc);
        const titleWidget = titlePageComment.querySelector('[data-reaction-widget]');

        // Render on episode page (originScreenEntryId is present)
        const episodeEntryId = '99999999-9999-9999-9999-999999999999';
        doc.getElementById('screenDiscussion').setAttribute('data-entry-id', episodeEntryId);
        ScreenTitleComments.init(doc);
        const episodePageComment = ScreenTitleComments.renderComment(commentData, ROOT_COMMENT_ID, null, false, doc);
        const episodeWidget = episodePageComment.querySelector('[data-reaction-widget]');

        assert.strictEqual(titleWidget.getAttribute('data-reaction-target-type'), 'COMMENT');
        assert.strictEqual(episodeWidget.getAttribute('data-reaction-target-type'), 'COMMENT');
        assert.strictEqual(titleWidget.getAttribute('data-reaction-target-id'), ROOT_COMMENT_ID);
        assert.strictEqual(episodeWidget.getAttribute('data-reaction-target-id'), ROOT_COMMENT_ID);
        assert.strictEqual(titleWidget.getAttribute('data-reaction-total'), episodeWidget.getAttribute('data-reaction-total'));
        assert.strictEqual(titleWidget.getAttribute('data-reaction-current'), episodeWidget.getAttribute('data-reaction-current'));
    });

    test('6. Shared reaction update event kiemlai:reaction-updated invalidates FEATURED sort cache', async () => {
        setupScreenDiscussionDom(doc, TITLE_ID);
        ScreenTitleComments.init(doc);

        ScreenTitleComments._sortCaches.set('FEATURED', { items: [{ id: ROOT_COMMENT_ID }] });
        ScreenTitleComments._sortCaches.set('NEWEST', { items: [{ id: ROOT_COMMENT_ID }] });
        assert.ok(ScreenTitleComments._sortCaches.has('FEATURED'));
        assert.ok(ScreenTitleComments._sortCaches.has('NEWEST'));

        // Dispatch kiemlai:reaction-updated for a COMMENT target
        await doc.dispatchEvent('kiemlai:reaction-updated', {
            detail: {
                targetType: 'COMMENT',
                targetId: ROOT_COMMENT_ID,
                reactionType: 'LOVE'
            }
        });

        // FEATURED cache should be cleared, NEWEST should remain intact
        assert.strictEqual(ScreenTitleComments._sortCaches.has('FEATURED'), false, 'FEATURED cache must be invalidated');
        assert.strictEqual(ScreenTitleComments._sortCaches.has('NEWEST'), true, 'NEWEST cache should remain intact');

        // Dispatching for non-COMMENT (e.g. POST) does not invalidate
        ScreenTitleComments._sortCaches.set('FEATURED', { items: [] });
        await doc.dispatchEvent('kiemlai:reaction-updated', {
            detail: {
                targetType: 'POST',
                targetId: 'post-123',
                reactionType: 'LIKE'
            }
        });
        assert.strictEqual(ScreenTitleComments._sortCaches.has('FEATURED'), true, 'Non-COMMENT event must not invalidate');
    });

    test('7. Multiple init calls do not accumulate duplicate listeners on kiemlai:reaction-updated', async () => {
        setupScreenDiscussionDom(doc, TITLE_ID);

        ScreenTitleComments.init(doc);
        ScreenTitleComments.init(doc);
        ScreenTitleComments.init(doc);

        const listeners = (doc._getListeners()['kiemlai:reaction-updated'] || []);
        assert.strictEqual(listeners.length, 1, 'Exactly one kiemlai:reaction-updated listener should remain');
    });

    test('8. Quick click on unreacted trigger sends PUT /api/interaction/reactions with LIKE and CSRF', async () => {
        setupScreenDiscussionDom(doc, TITLE_ID);
        InteractionReactions.init(doc);

        let capturedRequest = null;
        InteractionReactions.setFetch(async (url, opts) => {
            capturedRequest = { url, opts };
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: ROOT_COMMENT_ID,
                    counts: { LIKE: 1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 1,
                    currentUserReaction: 'LIKE'
                })
            };
        });

        const threadData = {
            root: {
                id: ROOT_COMMENT_ID,
                author: { id: 'u1', displayName: 'Author 1' },
                body: 'Bình luận chính',
                createdAt: '2026-11-03T10:00:00Z',
                tombstone: false,
                reactionSummary: {
                    targetType: 'COMMENT',
                    targetId: ROOT_COMMENT_ID,
                    counts: { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 0,
                    currentUserReaction: null
                }
            },
            replies: []
        };

        const threadEl = ScreenTitleComments.renderThread(threadData, doc);
        doc.getElementById('screenDiscussionThreadList').appendChild(threadEl);

        const widget = threadEl.querySelector(`[data-reaction-target-id="${ROOT_COMMENT_ID}"]`);
        const trigger = widget.querySelector('[data-reaction-trigger]');

        // Simulate pointer click via delegated document event
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        assert.ok(capturedRequest, 'Fetch request should have been made');
        assert.strictEqual(capturedRequest.url, '/api/interaction/reactions');
        assert.strictEqual(capturedRequest.opts.method, 'PUT');
        assert.strictEqual(capturedRequest.opts.headers['X-CSRF-TOKEN'], 'test-csrf-token-12345');

        const parsedBody = JSON.parse(capturedRequest.opts.body);
        assert.strictEqual(parsedBody.targetType, 'COMMENT');
        assert.strictEqual(parsedBody.targetId, ROOT_COMMENT_ID);
        assert.strictEqual(parsedBody.reactionType, 'LIKE');
    });

    test('9. Quick click on active LIKE trigger sends PUT with reactionType null to remove reaction', async () => {
        setupScreenDiscussionDom(doc, TITLE_ID);
        InteractionReactions.init(doc);

        let capturedRequest = null;
        InteractionReactions.setFetch(async (url, opts) => {
            capturedRequest = { url, opts };
            return {
                ok: true,
                status: 200,
                headers: { get: () => 'application/json' },
                json: async () => ({
                    targetType: 'COMMENT',
                    targetId: ROOT_COMMENT_ID,
                    counts: { LIKE: 0, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 0,
                    currentUserReaction: null
                })
            };
        });

        const threadData = {
            root: {
                id: ROOT_COMMENT_ID,
                author: { id: 'u1', displayName: 'Author 1' },
                body: 'Bình luận đã được like',
                createdAt: '2026-11-03T10:00:00Z',
                tombstone: false,
                reactionSummary: {
                    targetType: 'COMMENT',
                    targetId: ROOT_COMMENT_ID,
                    counts: { LIKE: 1, LOVE: 0, FIRE: 0, HAHA: 0, SAD: 0 },
                    totalCount: 1,
                    currentUserReaction: 'LIKE'
                }
            },
            replies: []
        };

        const threadEl = ScreenTitleComments.renderThread(threadData, doc);
        doc.getElementById('screenDiscussionThreadList').appendChild(threadEl);

        const widget = threadEl.querySelector(`[data-reaction-target-id="${ROOT_COMMENT_ID}"]`);
        const trigger = widget.querySelector('[data-reaction-trigger]');

        // Simulate pointer click via delegated document event on active reaction
        await doc.dispatchEvent('click', { target: trigger, pointerType: 'mouse' });

        assert.ok(capturedRequest, 'Fetch request should have been made');
        assert.strictEqual(capturedRequest.url, '/api/interaction/reactions');
        assert.strictEqual(capturedRequest.opts.method, 'PUT');

        const parsedBody = JSON.parse(capturedRequest.opts.body);
        assert.strictEqual(parsedBody.targetType, 'COMMENT');
        assert.strictEqual(parsedBody.targetId, ROOT_COMMENT_ID);
        assert.strictEqual(parsedBody.reactionType, null, 'Removing reaction must send reactionType: null');
    });
});
