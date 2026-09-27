const { describe, test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

describe('MS-05E5G3A2a Register Return Target Preservation Tests', () => {

    test('1. register.html contains returnTo preservation in login link', () => {
        const registerHtml = fs.readFileSync(
            path.resolve(__dirname, '../../../main/resources/templates/identity/register.html'),
            'utf-8'
        );
        assert.ok(registerHtml.includes('th:href="@{/login(returnTo=${param.returnTo})}"'));
    });

    test('2. register.html includes navbar-auth.js and calls computeRegistrationSuccessUrl', () => {
        const registerHtml = fs.readFileSync(
            path.resolve(__dirname, '../../../main/resources/templates/identity/register.html'),
            'utf-8'
        );
        assert.ok(registerHtml.includes('th:src="@{/js/navbar-auth.js}"'));
        assert.match(registerHtml, /computeRegistrationSuccessUrl/);
    });

    test('3. production helper computeRegistrationSuccessUrl preserves encoded returnTo with query params', () => {
        const NavbarAuth = require('../../../main/resources/static/js/navbar-auth.js');
        assert.strictEqual(typeof NavbarAuth.computeRegistrationSuccessUrl, 'function');

        const complexTarget = '/novel/chapters/quyen-1-chuong-10?discussionBlock=blk-1&intent=reply';
        const withReturnTo = NavbarAuth.computeRegistrationSuccessUrl(
            '?returnTo=' + encodeURIComponent(complexTarget),
            'http://localhost:8080'
        );
        assert.strictEqual(
            withReturnTo,
            'http://localhost:8080/login?registered&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-10%3FdiscussionBlock%3Dblk-1%26intent%3Dreply'
        );

        const withoutReturnTo = NavbarAuth.computeRegistrationSuccessUrl('', 'http://localhost:8080');
        assert.strictEqual(withoutReturnTo, 'http://localhost:8080/login?registered');

        const emptyReturnTo = NavbarAuth.computeRegistrationSuccessUrl('?returnTo=   ', 'http://localhost:8080');
        assert.strictEqual(emptyReturnTo, 'http://localhost:8080/login?registered');
    });
});
