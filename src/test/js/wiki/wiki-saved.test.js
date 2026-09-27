const { test, describe } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SCRIPT_PATH = path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-saved.js');
const scriptCode = fs.readFileSync(SCRIPT_PATH, 'utf8');

function createDOMClassList() {
    const classes = new Set();
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        get value() { return Array.from(classes).join(' '); }
    };
}

function createDetailEnvironment({
    authenticated = 'true',
    saved = 'false',
    articleId = '11111111-1111-1111-1111-111111111111',
    saveUrl = '/wiki/articles/11111111-1111-1111-1111-111111111111/save',
    loginUrl = '/login',
    csrfToken = 'csrf-val-123',
    csrfHeader = 'X-CSRF-TOKEN',
    fetchImpl = async () => ({ status: 204, ok: true, redirected: false })
} = {}) {
    const attrs = {
        'data-authenticated': authenticated,
        'data-saved': saved,
        'data-article-id': articleId,
        'data-save-url': saveUrl,
        'data-login-url': loginUrl,
        'data-csrf-token': csrfToken,
        'data-csrf-header': csrfHeader
    };

    const textSpan = {
        textContent: saved === 'true' ? 'Đã lưu' : 'Lưu bài viết'
    };

    const classList = createDOMClassList();
    if (saved === 'true') {
        classList.add('is-saved');
    }

    let clickHandler = null;

    const saveBtn = {
        disabled: false,
        classList,
        getAttribute: (k) => attrs[k] || null,
        setAttribute: (k, v) => { attrs[k] = String(v); },
        querySelector: (sel) => {
            if (sel === '.wiki-save-btn-text') return textSpan;
            return null;
        },
        addEventListener: (evt, fn) => {
            if (evt === 'click') clickHandler = fn;
        }
    };

    const appendedElements = [];
    const windowObj = {
        location: {
            href: 'http://localhost/wiki/character/tieu-hanh',
            origin: 'http://localhost'
        }
    };

    const documentObj = {
        body: {
            appendChild: (el) => appendedElements.push(el)
        },
        getElementById: (id) => {
            if (id === 'wikiSaveArticleBtn') return saveBtn;
            if (id === 'wikiSavedToast') return null;
            return null;
        },
        querySelectorAll: (sel) => {
            if (sel === '.js-wiki-unsave-btn') return [];
            return [];
        },
        createElement: (tag) => {
            const el = {
                tagName: tag.toUpperCase(),
                style: {},
                textContent: '',
                innerHTML: '',
                children: [],
                setAttribute: () => {},
                addEventListener: () => {},
                appendChild: (child) => {
                    el.children.push(child);
                },
                remove: () => {}
            };
            return el;
        },
        addEventListener: (evt, fn) => {
            if (evt === 'DOMContentLoaded') {
                fn();
            }
        }
    };

    const fetchCalls = [];
    const wrappedFetch = async (url, opts) => {
        fetchCalls.push({ url, opts });
        return fetchImpl(url, opts);
    };

    const sandbox = {
        document: documentObj,
        window: windowObj,
        fetch: wrappedFetch,
        console: console,
        requestAnimationFrame: (cb) => cb(),
        setTimeout: (cb) => setTimeout(cb, 0),
        clearTimeout: clearTimeout,
        URL: URL
    };

    vm.runInNewContext(scriptCode, sandbox);

    return {
        saveBtn,
        textSpan,
        window: windowObj,
        fetchCalls,
        appendedElements,
        clickSave: async () => {
            if (clickHandler) {
                const event = { preventDefault: () => {} };
                await clickHandler(event);
            }
        }
    };
}

function createListEnvironment({
    currentPage = '0',
    itemCount = 1,
    csrfToken = 'csrf-val-123',
    csrfHeader = 'X-CSRF-TOKEN',
    fetchImpl = async () => ({ status: 204, ok: true, redirected: false })
} = {}) {
    const items = [];
    let reloadCalled = false;

    const windowObj = {
        location: {
            href: currentPage === '0' ? 'http://localhost/wiki/saved' : `http://localhost/wiki/saved?page=${currentPage}`,
            origin: 'http://localhost',
            reload: () => { reloadCalled = true; }
        }
    };

    const listContainer = {
        id: 'wikiSavedList',
        getAttribute: (k) => {
            if (k === 'data-current-page') return currentPage;
            return null;
        }
    };

    const buttons = [];

    for (let i = 0; i < itemCount; i++) {
        const articleId = `art-uuid-${i}`;
        const unsaveUrl = `/wiki/articles/${articleId}/save`;

        let btnClickHandler = null;
        const btn = {
            disabled: false,
            getAttribute: (k) => {
                if (k === 'data-unsave-url') return unsaveUrl;
                if (k === 'data-csrf-token') return csrfToken;
                if (k === 'data-csrf-header') return csrfHeader;
                return null;
            },
            addEventListener: (evt, fn) => {
                if (evt === 'click') btnClickHandler = fn;
            },
            closest: (sel) => {
                if (sel === '.wiki-saved-item') return itemCard;
                return null;
            }
        };

        const itemCard = {
            id: `item-${i}`,
            className: 'wiki-saved-item',
            remove: () => {
                const idx = items.indexOf(itemCard);
                if (idx !== -1) items.splice(idx, 1);
            }
        };

        items.push(itemCard);
        buttons.push({ btn, getClickHandler: () => btnClickHandler, itemCard });
    }

    const appendedElements = [];
    const documentObj = {
        body: {
            appendChild: (el) => appendedElements.push(el)
        },
        getElementById: (id) => {
            if (id === 'wikiSavedList') return listContainer;
            return null;
        },
        querySelectorAll: (sel) => {
            if (sel === '.js-wiki-unsave-btn') return buttons.map(b => b.btn);
            if (sel === '.wiki-saved-item') return items;
            return [];
        },
        createElement: (tag) => {
            const el = {
                tagName: tag.toUpperCase(),
                style: {},
                textContent: '',
                innerHTML: '',
                children: [],
                setAttribute: () => {},
                addEventListener: () => {},
                appendChild: (child) => {
                    el.children.push(child);
                },
                remove: () => {}
            };
            return el;
        },
        addEventListener: (evt, fn) => {
            if (evt === 'DOMContentLoaded') {
                fn();
            }
        }
    };

    const fetchCalls = [];
    const wrappedFetch = async (url, opts) => {
        fetchCalls.push({ url, opts });
        return fetchImpl(url, opts);
    };

    const sandbox = {
        document: documentObj,
        window: windowObj,
        fetch: wrappedFetch,
        console: console,
        requestAnimationFrame: (cb) => cb(),
        setTimeout: (cb) => setTimeout(cb, 0),
        clearTimeout: clearTimeout,
        URL: URL
    };

    vm.runInNewContext(scriptCode, sandbox);

    return {
        items,
        buttons,
        window: windowObj,
        fetchCalls,
        appendedElements,
        getReloadCalled: () => reloadCalled,
        clickUnsave: async (index = 0) => {
            const entry = buttons[index];
            if (entry && entry.getClickHandler()) {
                const event = { preventDefault: () => {} };
                await entry.getClickHandler()(event);
            }
        }
    };
}

describe('MS-05D4 wiki-saved.js Frontend Behavior Tests', () => {

    test('1. 204 save toggles detail UI to saved state', async () => {
        const env = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });

        await env.clickSave();

        assert.strictEqual(env.saveBtn.getAttribute('data-saved'), 'true');
        assert.strictEqual(env.saveBtn.classList.contains('is-saved'), true);
        assert.strictEqual(env.textSpan.textContent, 'Đã lưu');
        assert.strictEqual(env.fetchCalls.length, 1);
        assert.strictEqual(env.fetchCalls[0].opts.method, 'POST');
    });

    test('2. 204 unsave toggles detail UI to unsaved state', async () => {
        const env = createDetailEnvironment({
            saved: 'true',
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });

        await env.clickSave();

        assert.strictEqual(env.saveBtn.getAttribute('data-saved'), 'false');
        assert.strictEqual(env.saveBtn.classList.contains('is-saved'), false);
        assert.strictEqual(env.textSpan.textContent, 'Lưu bài viết');
        assert.strictEqual(env.fetchCalls.length, 1);
        assert.strictEqual(env.fetchCalls[0].opts.method, 'DELETE');
    });

    test('3. Followed redirect response (e.g. 200 from /login or /access-denied) is NOT treated as success', async () => {
        // Detail page test
        const detailEnv = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({
                status: 200,
                ok: true,
                redirected: true,
                url: 'http://localhost/login'
            })
        });

        await detailEnv.clickSave();

        // Must NOT toggle to saved
        assert.strictEqual(detailEnv.saveBtn.getAttribute('data-saved'), 'false');
        assert.strictEqual(detailEnv.saveBtn.classList.contains('is-saved'), false);

        // List page test
        const listEnv = createListEnvironment({
            currentPage: '0',
            itemCount: 2,
            fetchImpl: async () => ({
                status: 200,
                ok: true,
                redirected: true,
                url: 'http://localhost/login'
            })
        });

        await listEnv.clickUnsave(0);

        // Item must NOT be removed
        assert.strictEqual(listEnv.items.length, 2);
    });

    test('4. Redirect to login navigates browser to login', async () => {
        const env = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({
                status: 200,
                ok: true,
                redirected: true,
                url: 'http://localhost/login'
            })
        });

        await env.clickSave();

        assert.strictEqual(env.window.location.href, 'http://localhost/login');
    });

    test('5. Redirect/access-denied is NOT treated as save/unsave success', async () => {
        // Detail page check
        const detailEnv = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({
                status: 403,
                ok: false,
                redirected: true,
                url: 'http://localhost/access-denied'
            })
        });

        await detailEnv.clickSave();

        assert.strictEqual(detailEnv.saveBtn.getAttribute('data-saved'), 'false');
        assert.strictEqual(detailEnv.saveBtn.classList.contains('is-saved'), false);
        assert.strictEqual(detailEnv.window.location.href, 'http://localhost/access-denied');

        // List page check
        const listEnv = createListEnvironment({
            currentPage: '1',
            itemCount: 1,
            fetchImpl: async () => ({
                status: 403,
                ok: false,
                redirected: true,
                url: 'http://localhost/access-denied'
            })
        });

        await listEnv.clickUnsave(0);

        assert.strictEqual(listEnv.items.length, 1);
        assert.strictEqual(listEnv.window.location.href, 'http://localhost/access-denied');
    });

    test('6. 404 response does not toggle UI; displays unavailable message', async () => {
        const env = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({
                status: 404,
                ok: false,
                redirected: false
            })
        });

        await env.clickSave();

        assert.strictEqual(env.saveBtn.getAttribute('data-saved'), 'false');
        assert.strictEqual(env.saveBtn.classList.contains('is-saved'), false);
        assert.strictEqual(env.textSpan.textContent, 'Lưu bài viết');

        // Verify toast was appended with the unavailable message
        assert.strictEqual(env.appendedElements.length, 1);
        const toast = env.appendedElements[0];
        assert.ok(toast.children.some(c => c.textContent === 'Bài viết không còn khả dụng để lưu.'));
    });

    test('7. No userId is sent in URL, headers, or JSON body', async () => {
        // Detail save
        const detailSaveEnv = createDetailEnvironment({
            saved: 'false',
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });
        await detailSaveEnv.clickSave();

        const saveCall = detailSaveEnv.fetchCalls[0];
        assert.doesNotMatch(saveCall.url, /user/i);
        assert.strictEqual(saveCall.opts.body, undefined);
        for (const [headerName, headerVal] of Object.entries(saveCall.opts.headers || {})) {
            assert.doesNotMatch(headerName, /user/i);
            assert.doesNotMatch(String(headerVal), /user/i);
        }

        // List unsave
        const listEnv = createListEnvironment({
            currentPage: '0',
            itemCount: 1,
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });
        await listEnv.clickUnsave(0);

        const unsaveCall = listEnv.fetchCalls[0];
        assert.doesNotMatch(unsaveCall.url, /user/i);
        assert.strictEqual(unsaveCall.opts.body, undefined);
        for (const [headerName, headerVal] of Object.entries(unsaveCall.opts.headers || {})) {
            assert.doesNotMatch(headerName, /user/i);
            assert.doesNotMatch(String(headerVal), /user/i);
        }
    });

    test('8. Last item unsave on page > 0 navigates browser to previous page (/wiki/saved?page=N-1)', async () => {
        const env = createListEnvironment({
            currentPage: '2',
            itemCount: 1,
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });

        await env.clickUnsave(0);

        assert.strictEqual(env.items.length, 0);
        assert.strictEqual(env.window.location.href, '/wiki/saved?page=1');
        assert.strictEqual(env.getReloadCalled(), false);
    });

    test('8b. Last item unsave on page 0 reloads the page to show empty state', async () => {
        const env = createListEnvironment({
            currentPage: '0',
            itemCount: 1,
            fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
        });

        await env.clickUnsave(0);

        assert.strictEqual(env.items.length, 0);
        assert.strictEqual(env.getReloadCalled(), true);
    });

    // =========================================================================
    // Explicit Coverage for Security Redirect Destination Validation
    // =========================================================================

    describe('Security Redirect Destination Validation', () => {

        test('A. same-origin /login redirect navigates (with or without query parameters)', async () => {
            // Detail page
            const detailEnv = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'http://localhost/login?expired=true'
                })
            });
            await detailEnv.clickSave();
            assert.strictEqual(detailEnv.window.location.href, 'http://localhost/login?expired=true');

            // List page
            const listEnv = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: '/login'
                })
            });
            await listEnv.clickUnsave(0);
            assert.strictEqual(listEnv.window.location.href, 'http://localhost/login');
        });

        test('B. same-origin /access-denied redirect navigates', async () => {
            // Detail page
            const detailEnv = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({
                    status: 403,
                    ok: false,
                    redirected: true,
                    url: '/access-denied'
                })
            });
            await detailEnv.clickSave();
            assert.strictEqual(detailEnv.window.location.href, 'http://localhost/access-denied');

            // List page
            const listEnv = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({
                    status: 403,
                    ok: false,
                    redirected: true,
                    url: 'http://localhost/access-denied?reason=csrf'
                })
            });
            await listEnv.clickUnsave(0);
            assert.strictEqual(listEnv.window.location.href, 'http://localhost/access-denied?reason=csrf');
        });

        test('C. cross-origin redirect is NOT navigated to', async () => {
            // Detail page: evil.example
            const detailEnv = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'https://evil.example/login'
                })
            });
            await detailEnv.clickSave();
            // Location must remain original, NOT navigate to evil.example
            assert.strictEqual(detailEnv.window.location.href, 'http://localhost/wiki/character/tieu-hanh');
            assert.strictEqual(detailEnv.appendedElements.length, 1);

            // List page: evil.example
            const listEnv = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'https://evil.example/access-denied'
                })
            });
            await listEnv.clickUnsave(0);
            assert.strictEqual(listEnv.window.location.href, 'http://localhost/wiki/saved');
            assert.strictEqual(listEnv.appendedElements.length, 1);
        });

        test('D. unrelated same-origin redirect is NOT navigated to', async () => {
            // Detail page: /some-unrelated-page
            const detailEnv = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'http://localhost/some-unrelated-page'
                })
            });
            await detailEnv.clickSave();
            assert.strictEqual(detailEnv.window.location.href, 'http://localhost/wiki/character/tieu-hanh');
            assert.strictEqual(detailEnv.appendedElements.length, 1);

            // List page: /dashboard
            const listEnv = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: '/dashboard'
                })
            });
            await listEnv.clickUnsave(0);
            assert.strictEqual(listEnv.window.location.href, 'http://localhost/wiki/saved');
            assert.strictEqual(listEnv.appendedElements.length, 1);
        });

        test('E. rejected redirect does NOT toggle saved state', async () => {
            // Case 1: un-saved item attempts save with cross-origin redirect
            const detailSaveEnv = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'https://evil.example/login'
                })
            });
            await detailSaveEnv.clickSave();
            assert.strictEqual(detailSaveEnv.saveBtn.getAttribute('data-saved'), 'false');
            assert.strictEqual(detailSaveEnv.saveBtn.classList.contains('is-saved'), false);
            assert.strictEqual(detailSaveEnv.textSpan.textContent, 'Lưu bài viết');

            // Case 2: saved item attempts unsave with unrelated redirect
            const detailUnsaveEnv = createDetailEnvironment({
                saved: 'true',
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: '/some-unrelated-page'
                })
            });
            await detailUnsaveEnv.clickSave();
            assert.strictEqual(detailUnsaveEnv.saveBtn.getAttribute('data-saved'), 'true');
            assert.strictEqual(detailUnsaveEnv.saveBtn.classList.contains('is-saved'), true);
            assert.strictEqual(detailUnsaveEnv.textSpan.textContent, 'Đã lưu');
        });

        test('F. rejected redirect does NOT remove saved-list item', async () => {
            // Case 1: cross-origin redirect
            const listEnv1 = createListEnvironment({
                currentPage: '0',
                itemCount: 2,
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: 'https://evil.example/login'
                })
            });
            await listEnv1.clickUnsave(0);
            assert.strictEqual(listEnv1.items.length, 2);

            // Case 2: unrelated same-origin redirect
            const listEnv2 = createListEnvironment({
                currentPage: '0',
                itemCount: 2,
                fetchImpl: async () => ({
                    status: 200,
                    ok: true,
                    redirected: true,
                    url: '/home'
                })
            });
            await listEnv2.clickUnsave(0);
            assert.strictEqual(listEnv2.items.length, 2);
        });

        test('G. HTTP 204 remains the only success status', async () => {
            // HTTP 200 without redirect on detail save -> does NOT toggle
            const env200 = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({ status: 200, ok: true, redirected: false })
            });
            await env200.clickSave();
            assert.strictEqual(env200.saveBtn.getAttribute('data-saved'), 'false');

            // HTTP 201 on detail save -> does NOT toggle
            const env201 = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({ status: 201, ok: true, redirected: false })
            });
            await env201.clickSave();
            assert.strictEqual(env201.saveBtn.getAttribute('data-saved'), 'false');

            // HTTP 200 without redirect on list unsave -> does NOT remove
            const listEnv200 = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({ status: 200, ok: true, redirected: false })
            });
            await listEnv200.clickUnsave(0);
            assert.strictEqual(listEnv200.items.length, 1);

            // HTTP 204 on detail save -> DOES toggle
            const env204 = createDetailEnvironment({
                saved: 'false',
                fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
            });
            await env204.clickSave();
            assert.strictEqual(env204.saveBtn.getAttribute('data-saved'), 'true');

            // HTTP 204 on list unsave -> DOES remove
            const listEnv204 = createListEnvironment({
                currentPage: '0',
                itemCount: 1,
                fetchImpl: async () => ({ status: 204, ok: true, redirected: false })
            });
            await listEnv204.clickUnsave(0);
            assert.strictEqual(listEnv204.items.length, 0);
        });
    });
});
