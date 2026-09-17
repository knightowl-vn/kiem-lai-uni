const { describe, test } = require('node:test');
const assert = require('node:assert');
const NavbarAuth = require('../../../main/resources/static/js/navbar-auth.js');

describe('MS-05E5G3A2a Global Navbar Auth Return Link Helper Tests', () => {

    test('1. isTechnicalPage identifies technical and auth paths', () => {
        assert.strictEqual(NavbarAuth.isTechnicalPage('/login'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/login/oauth2/code/google'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/register'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/logout'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/error'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/access-denied'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/forgot-password'), true);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/reset-password'), true);

        // Non-technical public paths
        assert.strictEqual(NavbarAuth.isTechnicalPage('/'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/home'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/novel'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/novel/chapters/chuong-1'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/wiki'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/wiki/character/tran-binh-an'), false);
        assert.strictEqual(NavbarAuth.isTechnicalPage('/donghua/tap-1'), false);
    });

    test('2. getCurrentReturnTarget returns null on technical pages', () => {
        const fakeWin = {
            location: {
                pathname: '/login',
                search: '',
                hash: ''
            }
        };
        assert.strictEqual(NavbarAuth.getCurrentReturnTarget(fakeWin), null);
    });

    test('3. getCurrentReturnTarget constructs pathname + search + hash on user-facing pages', () => {
        const fakeWin = {
            location: {
                pathname: '/novel/chapters/quyen-1-chuong-10',
                search: '?discussionBlock=blk-1&intent=reply',
                hash: '#comment-123'
            }
        };
        const expected = '/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1&intent=reply#comment-123';
        assert.strictEqual(NavbarAuth.getCurrentReturnTarget(fakeWin), expected);
    });

    test('4. updateNavbarAuthLinks updates login and register links with properly encoded returnTo', () => {
        const fakeWin = {
            location: {
                pathname: '/wiki/character/tran-binh-an',
                search: '?tab=lore',
                hash: '#origin'
            }
        };

        const loginLink = {
            href: '/login',
            getAttribute: (attr) => attr === 'data-auth-type' ? 'login' : null
        };
        const registerLink = {
            href: '/register',
            getAttribute: (attr) => attr === 'data-auth-type' ? 'register' : null
        };

        const fakeDoc = {
            querySelectorAll: (sel) => {
                if (sel === '.js-navbar-auth-link') {
                    return [loginLink, registerLink];
                }
                return [];
            }
        };

        NavbarAuth.updateNavbarAuthLinks(fakeDoc, fakeWin);

        const expectedReturnTo = encodeURIComponent('/wiki/character/tran-binh-an?tab=lore#origin');
        assert.strictEqual(loginLink.href, '/login?returnTo=' + expectedReturnTo);
        assert.strictEqual(registerLink.href, '/register?returnTo=' + expectedReturnTo);
    });

    test('5. updateNavbarAuthLinks does not touch links on technical pages', () => {
        const fakeWin = {
            location: {
                pathname: '/register',
                search: '',
                hash: ''
            }
        };

        const loginLink = {
            href: '/login',
            getAttribute: (attr) => attr === 'data-auth-type' ? 'login' : null
        };

        const fakeDoc = {
            querySelectorAll: () => [loginLink]
        };

        NavbarAuth.updateNavbarAuthLinks(fakeDoc, fakeWin);
        assert.strictEqual(loginLink.href, '/login');
    });

    test('6. click handler recalculates return target dynamically to capture late hash updates', () => {
        const fakeWin = {
            location: {
                pathname: '/novel/chapters/chuong-1',
                search: '',
                hash: ''
            }
        };

        let clickListener = null;
        const fakeDoc = {
            querySelectorAll: () => [],
            addEventListener: (evt, fn) => {
                if (evt === 'click') clickListener = fn;
            }
        };

        NavbarAuth.initNavbarAuth(fakeDoc, fakeWin);
        assert.strictEqual(typeof clickListener, 'function');

        // User navigated / scrolled anchor dynamically
        fakeWin.location.hash = '#blk-anchor-99';

        const clickedLink = {
            href: '/login',
            getAttribute: (attr) => attr === 'data-auth-type' ? 'login' : null
        };

        const fakeEvent = {
            target: {
                closest: (sel) => sel === '.js-navbar-auth-link' ? clickedLink : null
            }
        };

        clickListener(fakeEvent);

        const expectedReturnTo = encodeURIComponent('/novel/chapters/chuong-1#blk-anchor-99');
        assert.strictEqual(clickedLink.href, '/login?returnTo=' + expectedReturnTo);
    });

    test('7. computeRegistrationSuccessUrl formats /login redirect with registered and optional returnTo', () => {
        const withTarget = NavbarAuth.computeRegistrationSuccessUrl(
            '?returnTo=' + encodeURIComponent('/wiki/character/tran-binh-an'),
            'https://kiemlai.vn'
        );
        assert.strictEqual(
            withTarget,
            'https://kiemlai.vn/login?registered&returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an'
        );

        const withoutTarget = NavbarAuth.computeRegistrationSuccessUrl('', 'https://kiemlai.vn');
        assert.strictEqual(withoutTarget, 'https://kiemlai.vn/login?registered');
    });
});
