const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const SCRIPT_PATH = path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-appreciation.js');
const WikiAppreciation = require(SCRIPT_PATH);

function createClassList(initialClasses = []) {
    const classes = new Set(initialClasses);
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        get value() { return Array.from(classes).join(' '); }
    };
}

function createMockElement(id, tagName = 'div', initialAttrs = {}) {
    const attrs = { ...initialAttrs };
    const classList = createClassList();
    const eventListeners = {};
    let children = [];

    const el = {
        id,
        tagName: tagName.toUpperCase(),
        classList,
        disabled: false,
        hidden: false,
        textContent: '',
        parentNode: null,
        ownerDocument: null,
        children,
        getAttribute: (k) => (k in attrs ? attrs[k] : null),
        setAttribute: (k, v) => { attrs[k] = String(v); },
        removeAttribute: (k) => { delete attrs[k]; },
        addEventListener: (evt, fn) => {
            if (!eventListeners[evt]) eventListeners[evt] = [];
            eventListeners[evt].push(fn);
        },
        dispatchEvent: async (evtName, evtObj = {}) => {
            const fns = eventListeners[evtName] || [];
            for (const fn of fns) {
                await fn({ preventDefault: () => {}, ...evtObj });
            }
        },
        querySelectorAll: (sel) => {
            if (sel === '.wiki-star-btn') {
                return children.filter(c => c.classList.contains('wiki-star-btn'));
            }
            return [];
        },
        querySelector: (sel) => {
            if (sel === '.wiki-appreciation-separator') {
                return children.find(c => c.classList.contains('wiki-appreciation-separator')) || null;
            }
            return null;
        },
        appendChild: (child) => {
            child.parentNode = el;
            children.push(child);
            return child;
        },
        insertBefore: (newChild, refChild) => {
            newChild.parentNode = el;
            const idx = children.indexOf(refChild);
            if (idx >= 0) {
                children.splice(idx, 0, newChild);
            } else {
                children.push(newChild);
            }
            return newChild;
        }
    };
    return el;
}

function createMockDocument({
    authenticated = 'true',
    viewerValue = '',
    articleId = '11111111-1111-1111-1111-111111111111',
    appreciationUrl = '/api/wiki/articles/11111111-1111-1111-1111-111111111111/appreciation',
    loginUrl = '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an',
    csrfToken = 'test-csrf-token-123',
    csrfHeader = 'X-CSRF-TOKEN',
    average = '4.67',
    count = '12'
} = {}) {
    const doc = {
        elements: {},
        getElementById: (id) => doc.elements[id] || null,
        createElement: (tag) => {
            const el = createMockElement('', tag);
            el.ownerDocument = doc;
            return el;
        },
        querySelector: (sel) => null,
        defaultView: {
            location: {
                href: 'http://localhost/wiki/character/tran-binh-an'
            }
        }
    };

    // 1. Widget Root
    const widgetEl = createMockElement('wikiAppreciationWidget', 'div', {
        'data-article-id': articleId,
        'data-appreciation-url': appreciationUrl,
        'data-viewer-value': viewerValue,
        'data-authenticated': authenticated,
        'data-login-url': loginUrl,
        'data-csrf-token': csrfToken,
        'data-csrf-header': csrfHeader
    });
    widgetEl.ownerDocument = doc;
    doc.elements['wikiAppreciationWidget'] = widgetEl;

    // 2. Stats
    const statsEl = createMockElement('wikiAppreciationStats', 'div');
    statsEl.ownerDocument = doc;
    doc.elements['wikiAppreciationStats'] = statsEl;

    const hasRatings = count && Number(count) > 0 && average != null;
    const averageEl = createMockElement('wikiAppreciationAverage', 'span');
    averageEl.textContent = hasRatings ? Number(average).toFixed(1) : '';
    averageEl.hidden = !hasRatings;
    statsEl.appendChild(averageEl);
    doc.elements['wikiAppreciationAverage'] = averageEl;

    const sepEl = createMockElement('', 'span', {});
    sepEl.classList.add('wiki-appreciation-separator');
    sepEl.textContent = '/ 5 •';
    sepEl.hidden = !hasRatings;
    statsEl.appendChild(sepEl);

    const countEl = createMockElement('wikiAppreciationCount', 'span');
    countEl.textContent = hasRatings ? (count + ' lượt đánh giá') : 'Chưa có đánh giá';
    statsEl.appendChild(countEl);
    doc.elements['wikiAppreciationCount'] = countEl;

    // 3. Nine Half-Star Buttons (1.0 to 5.0 with 0.5 step)
    const starValues = ['1.0', '1.5', '2.0', '2.5', '3.0', '3.5', '4.0', '4.5', '5.0'];
    const starButtons = [];
    for (const val of starValues) {
        const btn = createMockElement('', 'button', {
            'data-star-value': val,
            'aria-label': `Yêu thích ${val} trên 5`
        });
        btn.classList.add('wiki-star-btn');
        btn.ownerDocument = doc;
        widgetEl.appendChild(btn);
        starButtons.push(btn);
    }

    // 4. Feedback Live Region
    const feedbackEl = createMockElement('wikiAppreciationFeedback', 'div');
    feedbackEl.ownerDocument = doc;
    doc.elements['wikiAppreciationFeedback'] = feedbackEl;

    return { doc, widgetEl, statsEl, averageEl, sepEl, countEl, feedbackEl, starButtons };
}

describe('MS-05F9 wiki-appreciation.js Frontend Tests (Half-Star Support)', () => {

    test('1. Initial render updates visual active state for integer rated viewer (3.0 stars)', () => {
        const env = createMockDocument({ viewerValue: '3.0' });
        WikiAppreciation.init(env.doc);

        // 1.0, 1.5, 2.0, 2.5, 3.0 should be active
        assert.strictEqual(env.starButtons[0].classList.contains('is-active'), true); // 1.0
        assert.strictEqual(env.starButtons[1].classList.contains('is-active'), true); // 1.5
        assert.strictEqual(env.starButtons[2].classList.contains('is-active'), true); // 2.0
        assert.strictEqual(env.starButtons[3].classList.contains('is-active'), true); // 2.5
        assert.strictEqual(env.starButtons[4].classList.contains('is-active'), true); // 3.0
        assert.strictEqual(env.starButtons[5].classList.contains('is-active'), false); // 3.5
        assert.strictEqual(env.starButtons[6].classList.contains('is-active'), false); // 4.0
        assert.strictEqual(env.starButtons[7].classList.contains('is-active'), false); // 4.5
        assert.strictEqual(env.starButtons[8].classList.contains('is-active'), false); // 5.0

        assert.strictEqual(env.starButtons[4].getAttribute('aria-pressed'), 'true');
        assert.strictEqual(env.starButtons[3].getAttribute('aria-pressed'), 'false');
    });

    test('1b. Initial render updates visual active state for half-star rated viewer (3.5 stars)', () => {
        const env = createMockDocument({ viewerValue: '3.5' });
        WikiAppreciation.init(env.doc);

        // 1.0, 1.5, 2.0, 2.5, 3.0, 3.5 should be active
        assert.strictEqual(env.starButtons[0].classList.contains('is-active'), true); // 1.0
        assert.strictEqual(env.starButtons[1].classList.contains('is-active'), true); // 1.5
        assert.strictEqual(env.starButtons[2].classList.contains('is-active'), true); // 2.0
        assert.strictEqual(env.starButtons[3].classList.contains('is-active'), true); // 2.5
        assert.strictEqual(env.starButtons[4].classList.contains('is-active'), true); // 3.0
        assert.strictEqual(env.starButtons[5].classList.contains('is-active'), true); // 3.5
        assert.strictEqual(env.starButtons[6].classList.contains('is-active'), false); // 4.0
        assert.strictEqual(env.starButtons[7].classList.contains('is-active'), false); // 4.5
        assert.strictEqual(env.starButtons[8].classList.contains('is-active'), false); // 5.0

        assert.strictEqual(env.starButtons[5].getAttribute('aria-pressed'), 'true');
        assert.strictEqual(env.starButtons[4].getAttribute('aria-pressed'), 'false');
    });

    test('2. Initial render for unrated viewer has no active stars', () => {
        const env = createMockDocument({ viewerValue: '' });
        WikiAppreciation.init(env.doc);

        for (const btn of env.starButtons) {
            assert.strictEqual(btn.classList.contains('is-active'), false);
            assert.strictEqual(btn.getAttribute('aria-pressed'), 'false');
        }
    });

    test('3. Half-star click (4.5) sends PUT request with CSRF token and selected float value', async () => {
        let sentUrl = '';
        let sentOptions = null;

        const fetchImpl = async (url, options) => {
            sentUrl = url;
            sentOptions = options;
            return {
                status: 200,
                json: async () => ({
                    wikiArticleId: '11111111-1111-1111-1111-111111111111',
                    value: 4.5,
                    average: 4.75,
                    displayAverage: '4.8',
                    count: 15,
                    changed: true
                })
            };
        };

        const env = createMockDocument({ viewerValue: '3.0' });
        WikiAppreciation.init(env.doc, { fetchImpl });

        // Click star 4.5 (index 7)
        await env.starButtons[7].dispatchEvent('click');

        assert.strictEqual(sentUrl, '/api/wiki/articles/11111111-1111-1111-1111-111111111111/appreciation');
        assert.strictEqual(sentOptions.method, 'PUT');
        assert.strictEqual(sentOptions.headers['Content-Type'], 'application/json');
        assert.strictEqual(sentOptions.headers['X-CSRF-TOKEN'], 'test-csrf-token-123');
        assert.strictEqual(sentOptions.body, JSON.stringify({ value: 4.5 }));

        // UI state updated from server response
        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '4.5');
        assert.strictEqual(env.starButtons[7].classList.contains('is-active'), true);
        assert.strictEqual(env.starButtons[7].getAttribute('aria-pressed'), 'true');
        assert.strictEqual(env.starButtons[8].classList.contains('is-active'), false);
        // Authoritative displayAverage used directly without JS rounding calculation
        assert.strictEqual(env.averageEl.textContent, '4.8');
        assert.strictEqual(env.countEl.textContent, '15 lượt đánh giá');
        assert.strictEqual(env.feedbackEl.textContent, 'Đã lưu đánh giá của bạn.');
        assert.strictEqual(env.starButtons[0].disabled, false);
    });

    test('4. Same-value click (changed=false) updates cleanly with 200 OK', async () => {
        const fetchImpl = async () => ({
            status: 200,
            json: async () => ({
                wikiArticleId: '11111111-1111-1111-1111-111111111111',
                value: 4.0,
                average: 4.25,
                displayAverage: '4.3',
                count: 10,
                changed: false
            })
        });

        const env = createMockDocument({ viewerValue: '4.0' });
        WikiAppreciation.init(env.doc, { fetchImpl });

        // Click same star 4.0 (index 6)
        await env.starButtons[6].dispatchEvent('click');

        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '4');
        assert.strictEqual(env.starButtons[6].classList.contains('is-active'), true);
        assert.strictEqual(env.averageEl.textContent, '4.3');
        assert.strictEqual(env.countEl.textContent, '10 lượt đánh giá');
        assert.strictEqual(env.feedbackEl.textContent, 'Đã lưu đánh giá của bạn.');
    });

    test('5. Anonymous user clicking star redirects to login without sending PUT', async () => {
        let fetchCalled = false;
        let redirectedUrl = null;

        const fetchImpl = async () => {
            fetchCalled = true;
            return { status: 200 };
        };

        const env = createMockDocument({
            authenticated: 'false',
            loginUrl: '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an'
        });
        WikiAppreciation.init(env.doc, {
            fetchImpl,
            onRedirect: (url) => { redirectedUrl = url; }
        });

        await env.starButtons[5].dispatchEvent('click');

        assert.strictEqual(fetchCalled, false);
        assert.strictEqual(redirectedUrl, '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an');
    });

    test('6. Server error (500) reverts stars to previous viewer value and shows error', async () => {
        const fetchImpl = async () => ({
            status: 500
        });

        const env = createMockDocument({ viewerValue: '2.0' });
        WikiAppreciation.init(env.doc, { fetchImpl });

        // Click star 5.0 (index 8)
        await env.starButtons[8].dispatchEvent('click');

        // Reverted to 2.0 (indices 0, 1, 2 active)
        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '2.0');
        assert.strictEqual(env.starButtons[0].classList.contains('is-active'), true); // 1.0
        assert.strictEqual(env.starButtons[1].classList.contains('is-active'), true); // 1.5
        assert.strictEqual(env.starButtons[2].classList.contains('is-active'), true); // 2.0
        assert.strictEqual(env.starButtons[3].classList.contains('is-active'), false); // 2.5
        assert.strictEqual(env.feedbackEl.textContent, 'Có lỗi xảy ra khi lưu đánh giá. Vui lòng thử lại sau.');
        assert.strictEqual(env.starButtons[0].disabled, false);
    });

    test('7. 404 response displays article unavailable message', async () => {
        const fetchImpl = async () => ({
            status: 404
        });

        const env = createMockDocument({ viewerValue: '' });
        WikiAppreciation.init(env.doc, { fetchImpl });

        await env.starButtons[0].dispatchEvent('click');

        assert.strictEqual(env.feedbackEl.textContent, 'Bài viết không còn khả dụng để đánh giá.');
    });

    test('8. Bug A Regression: First rating from empty summary reveals average and updates count', async () => {
        const env = createMockDocument({
            count: '0',
            average: null,
            viewerValue: ''
        });

        assert.strictEqual(env.averageEl.hidden, true);
        assert.strictEqual(env.averageEl.textContent, '');
        assert.strictEqual(env.sepEl.hidden, true);
        assert.strictEqual(env.countEl.textContent, 'Chưa có đánh giá');
        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '');

        const fetchImpl = async () => ({
            status: 200,
            headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
            json: async () => ({
                wikiArticleId: '11111111-1111-1111-1111-111111111111',
                value: 5.0,
                average: 5.0,
                displayAverage: '5.0',
                count: 1,
                changed: true
            })
        });

        WikiAppreciation.init(env.doc, { fetchImpl });

        await env.starButtons[8].dispatchEvent('click');

        assert.strictEqual(env.averageEl.hidden, false);
        assert.strictEqual(env.averageEl.textContent, '5.0');
        assert.strictEqual(env.sepEl.hidden, false);
        assert.strictEqual(env.countEl.textContent, '1 lượt đánh giá');
        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '5');
        assert.strictEqual(env.starButtons[8].classList.contains('is-active'), true);
        assert.strictEqual(env.starButtons[8].getAttribute('aria-pressed'), 'true');
        assert.strictEqual(env.feedbackEl.textContent, 'Đã lưu đánh giá của bạn.');
    });

    test('9. Bug B Regression A: Session-expired / login redirect does not consume JSON and redirects to login', async () => {
        let redirectTarget = null;
        const fetchImpl = async () => ({
            status: 200,
            redirected: true,
            url: 'http://localhost/login?returnTo=%2Fwiki%2Fcharacters%2Ftran-binh-an',
            headers: { get: () => 'text/html;charset=UTF-8' },
            json: async () => { throw new Error('HTML redirect response must NOT be parsed as JSON'); }
        });

        const env = createMockDocument({
            viewerValue: '3.0',
            count: '10',
            average: '4.50'
        });

        WikiAppreciation.init(env.doc, {
            fetchImpl,
            onRedirect: (url) => { redirectTarget = url; }
        });

        await env.starButtons[8].dispatchEvent('click');

        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '3.0');
        assert.strictEqual(env.starButtons[4].classList.contains('is-active'), true);
        assert.strictEqual(env.starButtons[8].classList.contains('is-active'), false);
        assert.strictEqual(env.averageEl.textContent, '4.5');
        assert.strictEqual(env.countEl.textContent, '10 lượt đánh giá');
        assert.strictEqual(env.starButtons[0].disabled, false);
        assert.strictEqual(redirectTarget, env.widgetEl.getAttribute('data-login-url'));
    });

    test('10. Bug B Regression B: Access-denied redirect preserves previous state and shows security message', async () => {
        const fetchImpl = async () => ({
            status: 200,
            redirected: true,
            url: 'http://localhost/access-denied',
            headers: { get: () => 'text/html;charset=UTF-8' },
            json: async () => { throw new Error('HTML redirect response must NOT be parsed as JSON'); }
        });

        const env = createMockDocument({
            viewerValue: '2.0',
            count: '5',
            average: '4.00'
        });

        WikiAppreciation.init(env.doc, { fetchImpl });

        await env.starButtons[6].dispatchEvent('click');

        assert.strictEqual(env.widgetEl.getAttribute('data-viewer-value'), '2.0');
        assert.strictEqual(env.starButtons[2].classList.contains('is-active'), true);
        assert.strictEqual(env.starButtons[6].classList.contains('is-active'), false);
        assert.strictEqual(env.averageEl.textContent, '4.0');
        assert.strictEqual(env.countEl.textContent, '5 lượt đánh giá');
        assert.strictEqual(env.starButtons[0].disabled, false);
        assert.strictEqual(env.feedbackEl.textContent, 'Yêu cầu bị từ chối hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
    });

    test('11. Server-owned displayAverage strings ("4.8", "4.7", "5.0") are rendered directly without client math', () => {
        const env = createMockDocument({ count: '10', average: '4.75' });
        const els = WikiAppreciation.getElements(env.doc);

        // Case A: 4.75 average, server displayAverage "4.8"
        WikiAppreciation.updateCommunityStats(els, 4.75, 10, '4.8');
        assert.strictEqual(els.averageEl.textContent, '4.8');

        // Case B: 4.65 average, server displayAverage "4.7"
        WikiAppreciation.updateCommunityStats(els, 4.65, 10, '4.7');
        assert.strictEqual(els.averageEl.textContent, '4.7');

        // Case C: 5.0 average, server displayAverage "5.0"
        WikiAppreciation.updateCommunityStats(els, 5.0, 10, '5.0');
        assert.strictEqual(els.averageEl.textContent, '5.0');
    });

    test('12. Client source file does not contain toFixed or Math.round for average display', () => {
        const fs = require('fs');
        const scriptSource = fs.readFileSync(SCRIPT_PATH, 'utf8');

        assert.strictEqual(scriptSource.includes('.toFixed('), false, 'Must not use .toFixed() in wiki-appreciation.js');
        assert.strictEqual(scriptSource.includes('Math.round('), false, 'Must not use Math.round() in wiki-appreciation.js');
        assert.strictEqual(scriptSource.includes('EPSILON'), false, 'Must not use EPSILON in wiki-appreciation.js');
    });

    test('13. Zero occurrences of "lượt yêu thích" remain in wiki-appreciation.js', () => {
        const fs = require('fs');
        const scriptSource = fs.readFileSync(SCRIPT_PATH, 'utf8');

        assert.strictEqual(scriptSource.includes('lượt yêu thích'), false, 'Must not use "lượt yêu thích" in wiki-appreciation.js');
        assert.strictEqual(scriptSource.includes('Đã lưu mức độ yêu thích'), false, 'Must not use old success feedback in wiki-appreciation.js');
    });
});
