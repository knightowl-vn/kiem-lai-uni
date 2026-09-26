/**
 * KiemLai Universe — Wiki Reader Contribution UX & Selection Toolbar Frontend Tests (MS-05H5)
 *
 * Test suite executed via Node.js built-in runner (node --test).
 */
const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const SCRIPT_PATH = path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-contribution.js');
const WikiContribution = require(SCRIPT_PATH);

describe('MS-05H5 Wiki Reader Contribution Frontend Unit Tests', () => {

    describe('1. Message Length Validation (20..5000 chars, outer trim, internal whitespace preserved)', () => {
        test('1a. Message with 19 characters fails validation', () => {
            const msg19 = '1234567890123456789';
            const result = WikiContribution.validateMessage(msg19);
            assert.strictEqual(result.valid, false);
            assert.strictEqual(result.length, 19);
            assert.match(result.error, /tối thiểu 20 ký tự/);
        });

        test('1b. Message with 20 characters passes validation', () => {
            const msg20 = '12345678901234567890';
            const result = WikiContribution.validateMessage(msg20);
            assert.strictEqual(result.valid, true);
            assert.strictEqual(result.length, 20);
        });

        test('1c. Message with 5000 characters passes validation', () => {
            const msg5000 = 'a'.repeat(5000);
            const result = WikiContribution.validateMessage(msg5000);
            assert.strictEqual(result.valid, true);
            assert.strictEqual(result.length, 5000);
        });

        test('1d. Message with 5001 characters fails validation', () => {
            const msg5001 = 'a'.repeat(5001);
            const result = WikiContribution.validateMessage(msg5001);
            assert.strictEqual(result.valid, false);
            assert.strictEqual(result.length, 5001);
            assert.match(result.error, /không được vượt quá 5000 ký tự/);
        });

        test('1e. Outer whitespace is trimmed for length check, but internal newlines are preserved', () => {
            const internalNewlines = '   Dòng 1 của nội dung góp ý\nDòng 2 của nội dung góp ý   ';
            const result = WikiContribution.validateMessage(internalNewlines);
            assert.strictEqual(result.valid, true);
            assert.strictEqual(result.trimmedMessage.includes('\n'), true);
            assert.strictEqual(result.trimmedMessage.startsWith('Dòng 1'), true);
            assert.strictEqual(result.trimmedMessage.endsWith('góp ý'), true);
        });

        test('1f. Null, undefined, or empty/whitespace-only message fails', () => {
            assert.strictEqual(WikiContribution.validateMessage(null).valid, false);
            assert.strictEqual(WikiContribution.validateMessage(undefined).valid, false);
            assert.strictEqual(WikiContribution.validateMessage('   \n\t   ').valid, false);
        });
    });

    describe('2. Source References Validation (0..5 inputs, max 2000 chars, empty omitted)', () => {
        test('2a. Empty source array passes with empty list', () => {
            const result = WikiContribution.validateSources([]);
            assert.strictEqual(result.valid, true);
            assert.deepStrictEqual(result.sources, []);
        });

        test('2b. Empty/whitespace-only items are filtered out', () => {
            const raw = ['https://example.com/source1', '   ', '', 'https://example.com/source2', null];
            const result = WikiContribution.validateSources(raw);
            assert.strictEqual(result.valid, true);
            assert.deepStrictEqual(result.sources, [
                'https://example.com/source1',
                'https://example.com/source2'
            ]);
        });

        test('2c. Up to 5 valid sources pass', () => {
            const sources5 = [
                'https://example.com/1',
                'https://example.com/2',
                'https://example.com/3',
                'https://example.com/4',
                'https://example.com/5'
            ];
            const result = WikiContribution.validateSources(sources5);
            assert.strictEqual(result.valid, true);
            assert.strictEqual(result.sources.length, 5);
        });

        test('2d. More than 5 sources fails validation', () => {
            const sources6 = [
                'https://example.com/1',
                'https://example.com/2',
                'https://example.com/3',
                'https://example.com/4',
                'https://example.com/5',
                'https://example.com/6'
            ];
            const result = WikiContribution.validateSources(sources6);
            assert.strictEqual(result.valid, false);
            assert.match(result.error, /tối đa 5 nguồn tham khảo/);
        });

        test('2e. Source exceeding 2000 characters fails validation', () => {
            const longSource = 'https://example.com/' + 'x'.repeat(2000);
            const result = WikiContribution.validateSources([longSource]);
            assert.strictEqual(result.valid, false);
            assert.match(result.error, /không được vượt quá 2000 ký tự/);
        });
    });

    describe('3. Text-Selection Normalization & Truthful Heading Anchor', () => {
        test('3a. Anchor text normalization applies NFC and collapses consecutive whitespace', () => {
            const raw = '  Trần   Bình   \n\t  An   ';
            const normalized = WikiContribution.normalizeAnchorText(raw);
            assert.strictEqual(normalized, 'Trần Bình An');
        });

        test('3b. Selected text <= 1000 characters passes validation', () => {
            const text1000 = 'x'.repeat(1000);
            const result = WikiContribution.validateSelectedText(text1000);
            assert.strictEqual(result.valid, true);
            assert.strictEqual(result.length, 1000);
        });

        test('3c. Selected text > 1000 characters triggers friendly warning without silent truncation', () => {
            const text1001 = 'x'.repeat(1001);
            const result = WikiContribution.validateSelectedText(text1001);
            assert.strictEqual(result.valid, false);
            assert.strictEqual(result.error, WikiContribution.LONG_SELECTION_WARNING);
            assert.strictEqual(result.error, 'Vui lòng chọn đoạn văn bản ngắn hơn (tối đa 1000 ký tự).');
        });

        test('3d. Heading anchor <= 255 chars trims and returns exact string', () => {
            const anchor = '   tieu-de-chuong-1   ';
            assert.strictEqual(WikiContribution.normalizeHeadingAnchor(anchor), 'tieu-de-chuong-1');

            const anchor255 = 'h'.repeat(255);
            assert.strictEqual(WikiContribution.normalizeHeadingAnchor(anchor255), anchor255);
        });

        test('3e. Heading anchor > 255 chars returns null (never truncated)', () => {
            const longAnchor = 'h'.repeat(256);
            const normalizedLong = WikiContribution.normalizeHeadingAnchor(longAnchor);
            assert.strictEqual(normalizedLong, null);
        });

        test('3f. Null or blank heading anchor normalizes to null', () => {
            assert.strictEqual(WikiContribution.normalizeHeadingAnchor(null), null);
            assert.strictEqual(WikiContribution.normalizeHeadingAnchor('   '), null);
        });
    });

    describe('4. Fail-closed Payload Builder', () => {
        test('4a. GENERAL mode omits all text selection fields (null)', () => {
            const payload = WikiContribution.buildContributionPayload({
                articleContentVersion: 3,
                contextType: 'GENERAL',
                contributionType: 'MISSING_INFORMATION',
                message: '   Cần bổ sung thêm thông tin về cảnh giới của nhân vật.   ',
                selectedText: 'Không được có trong general',
                selectedPrefix: 'prefix',
                selectedSuffix: 'suffix',
                selectedHeadingAnchor: 'anchor',
                sources: ['https://example.com/source']
            });

            assert.strictEqual(payload.articleContentVersion, 3);
            assert.strictEqual(payload.contextType, 'GENERAL');
            assert.strictEqual(payload.contributionType, 'MISSING_INFORMATION');
            assert.strictEqual(payload.message, 'Cần bổ sung thêm thông tin về cảnh giới của nhân vật.');
            assert.strictEqual(payload.selectedText, null);
            assert.strictEqual(payload.selectedPrefix, null);
            assert.strictEqual(payload.selectedSuffix, null);
            assert.strictEqual(payload.selectedHeadingAnchor, null);
            assert.deepStrictEqual(payload.sources, ['https://example.com/source']);
        });

        test('4b. TEXT_SELECTION mode includes normalized selection snapshot fields bounded to max limits', () => {
            const longPrefix = 'p'.repeat(150);
            const longSuffix = 's'.repeat(150);
            const anchor200 = 'a'.repeat(200);

            const payload = WikiContribution.buildContributionPayload({
                articleContentVersion: 1,
                contextType: 'TEXT_SELECTION',
                contributionType: 'INCORRECT_INFORMATION',
                message: 'Thông tin nhân vật này bị sai ở đoạn này.',
                selectedText: '  Đoạn   văn   bản   chọn  ',
                selectedPrefix: longPrefix,
                selectedSuffix: longSuffix,
                selectedHeadingAnchor: anchor200,
                sources: []
            });

            assert.strictEqual(payload.contextType, 'TEXT_SELECTION');
            assert.strictEqual(payload.selectedText, 'Đoạn văn bản chọn');
            assert.strictEqual(payload.selectedPrefix.length, 100);
            assert.strictEqual(payload.selectedPrefix, 'p'.repeat(100));
            assert.strictEqual(payload.selectedSuffix.length, 100);
            assert.strictEqual(payload.selectedSuffix, 's'.repeat(100));
            assert.strictEqual(payload.selectedHeadingAnchor, anchor200);
        });

        test('4c. articleContentVersion fails closed on missing, zero, or non-integer', () => {
            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: null,
                    contextType: 'GENERAL',
                    contributionType: 'WORDING',
                    message: 'Nội dung kiểm tra phiên bản bài viết.',
                    sources: []
                });
            }, /articleContentVersion không hợp lệ/);

            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: 0,
                    contextType: 'GENERAL',
                    contributionType: 'WORDING',
                    message: 'Nội dung kiểm tra phiên bản bài viết.',
                    sources: []
                });
            }, /articleContentVersion không hợp lệ/);

            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: 'invalid',
                    contextType: 'GENERAL',
                    contributionType: 'WORDING',
                    message: 'Nội dung kiểm tra phiên bản bài viết.',
                    sources: []
                });
            }, /articleContentVersion không hợp lệ/);
        });

        test('4d. Unknown or missing contextType fails closed', () => {
            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: 1,
                    contextType: 'UNKNOWN',
                    contributionType: 'WORDING',
                    message: 'Nội dung kiểm tra contextType.',
                    sources: []
                });
            }, /Loại ngữ cảnh không hợp lệ/);
        });

        test('4e. Unknown or missing contributionType fails closed (no default)', () => {
            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: 1,
                    contextType: 'GENERAL',
                    contributionType: '',
                    message: 'Nội dung kiểm tra loại góp ý.',
                    sources: []
                });
            }, /Loại góp ý không hợp lệ/);

            assert.throws(() => {
                WikiContribution.buildContributionPayload({
                    articleContentVersion: 1,
                    contextType: 'GENERAL',
                    contributionType: 'MALICIOUS_TYPE',
                    message: 'Nội dung kiểm tra loại góp ý.',
                    sources: []
                });
            }, /Loại góp ý không hợp lệ/);
        });
    });

    describe('5. Real H4 Response Contract Classification (classifySubmissionResponse)', () => {
        const CONTRIBUTION_UUID = '550e8400-e29b-41d4-a716-446655440000';

        test('5a. 201 JSON with valid contributionId and alreadySubmitted=false returns SUCCESS', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 201,
                redirected: false,
                contentType: 'application/json',
                body: {
                    contributionId: CONTRIBUTION_UUID,
                    status: 'NEW',
                    alreadySubmitted: false,
                    message: 'Đóng góp đã được gửi thành công.'
                }
            });

            assert.strictEqual(classified.type, 'SUCCESS');
            assert.strictEqual(classified.status, 201);
            assert.strictEqual(classified.isDuplicate, false);
            assert.strictEqual(classified.contributionId, CONTRIBUTION_UUID);
            assert.strictEqual(classified.thankYouMessage, WikiContribution.THANK_YOU_MESSAGE);
        });

        test('5b. 200 JSON with valid contributionId and alreadySubmitted=true returns SUCCESS (duplicate detected)', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: false,
                contentType: 'application/json;charset=UTF-8',
                body: {
                    contributionId: CONTRIBUTION_UUID,
                    status: 'NEW',
                    alreadySubmitted: true,
                    message: 'Đóng góp trùng lặp trong 60 giây.'
                }
            });

            assert.strictEqual(classified.type, 'SUCCESS');
            assert.strictEqual(classified.status, 200);
            assert.strictEqual(classified.isDuplicate, true);
            assert.strictEqual(classified.contributionId, CONTRIBUTION_UUID);
            assert.strictEqual(classified.thankYouMessage, WikiContribution.THANK_YOU_MESSAGE);
        });

        test('5c. 201 with only "id" (legacy field) cannot substitute for contributionId and FAILS', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 201,
                redirected: false,
                contentType: 'application/json',
                body: {
                    id: CONTRIBUTION_UUID,
                    status: 'NEW',
                    alreadySubmitted: false,
                    message: '...'
                }
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 201);
            assert.match(classified.message, /Phản hồi từ máy chủ không hợp lệ/);
        });

        test('5d. 200 with only "id" (legacy field) cannot substitute for contributionId and FAILS', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: false,
                contentType: 'application/json',
                body: {
                    id: CONTRIBUTION_UUID,
                    status: 'NEW',
                    alreadySubmitted: true,
                    message: '...'
                }
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 200);
            assert.match(classified.message, /Phản hồi từ máy chủ không hợp lệ/);
        });

        test('5e. 201/200 missing contributionId FAILS', () => {
            const res201 = WikiContribution.classifySubmissionResponse({
                status: 201,
                contentType: 'application/json',
                body: { alreadySubmitted: false }
            });
            assert.strictEqual(res201.type, 'ERROR');
            assert.match(res201.message, /Phản hồi từ máy chủ không hợp lệ/);

            const res200 = WikiContribution.classifySubmissionResponse({
                status: 200,
                contentType: 'application/json',
                body: { alreadySubmitted: true }
            });
            assert.strictEqual(res200.type, 'ERROR');
            assert.match(res200.message, /Phản hồi từ máy chủ không hợp lệ/);
        });

        test('5f. HTML 200 FAILS (not success)', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                contentType: 'text/html;charset=UTF-8',
                body: '<!DOCTYPE html><html><body>Error</body></html>'
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.match(classified.message, /không đúng định dạng/);
        });

        test('5g. HTML 201 FAILS (not success)', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 201,
                contentType: 'text/html;charset=UTF-8',
                body: '<!DOCTYPE html><html><body>Created</body></html>'
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.match(classified.message, /không đúng định dạng/);
        });

        test('5h. Malformed success JSON (null or string body) FAILS', () => {
            const resNull = WikiContribution.classifySubmissionResponse({
                status: 201,
                contentType: 'application/json',
                body: null
            });
            assert.strictEqual(resNull.type, 'ERROR');

            const resString = WikiContribution.classifySubmissionResponse({
                status: 201,
                contentType: 'application/json',
                body: 'not a json object'
            });
            assert.strictEqual(resString.type, 'ERROR');
        });

        test('5i. 400 empty / non-JSON returns validation error (NOT format error)', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 400,
                contentType: '',
                body: null
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 400);
            assert.match(classified.message, /Dữ liệu đóng góp không hợp lệ/);
        });

        test('5j. 400 with JSON message preserves server error message', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 400,
                contentType: 'application/json',
                body: { message: 'Phiên bản bài viết đã lỗi thời.' }
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 400);
            assert.strictEqual(classified.message, 'Phiên bản bài viết đã lỗi thời.');
        });

        test('5k. 401 empty / non-JSON returns REDIRECT_LOGIN', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 401,
                contentType: '',
                body: null
            });

            assert.strictEqual(classified.type, 'REDIRECT_LOGIN');
            assert.strictEqual(classified.status, 401);
        });

        test('5l. 404 empty / non-JSON returns article unavailable error', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 404,
                contentType: '',
                body: null
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 404);
            assert.match(classified.message, /Bài viết không tồn tại hoặc chưa xuất bản/);
        });

        test('5m. 500 empty / non-JSON returns generic server error', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 500,
                contentType: '',
                body: null
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.strictEqual(classified.status, 500);
            assert.match(classified.message, /Có lỗi xảy ra từ máy chủ/);
        });

        test('5n. Followed redirect to same-origin /login returns REDIRECT_LOGIN', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: true,
                url: 'http://localhost/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an'
            });

            assert.strictEqual(classified.type, 'REDIRECT_LOGIN');
            assert.strictEqual(classified.url, 'http://localhost/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an');
        });

        test('5o. Followed redirect to same-origin /access-denied returns ACCESS_DENIED with friendly error', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: true,
                url: 'http://localhost/access-denied'
            });

            assert.strictEqual(classified.type, 'ACCESS_DENIED');
            assert.match(classified.message, /Phiên đăng nhập đã hết hạn/);
        });

        test('5p. Cross-origin redirect is rejected safely', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: true,
                url: 'https://attacker.com/evil'
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.match(classified.message, /Yêu cầu chuyển hướng không hợp lệ/);
        });

        test('5q. Unexpected same-origin redirect path is rejected safely', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 200,
                redirected: true,
                url: 'http://localhost/unexpected-endpoint'
            });

            assert.strictEqual(classified.type, 'ERROR');
            assert.match(classified.message, /Yêu cầu chuyển hướng không hợp lệ/);
        });

        test('5r. 429 empty / non-JSON returns RATE_LIMITED with default friendly message', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 429,
                contentType: '',
                body: null
            });

            assert.strictEqual(classified.type, 'RATE_LIMITED');
            assert.strictEqual(classified.status, 429);
            assert.match(classified.message, /Bạn đang gửi đóng góp quá nhanh/);
        });

        test('5s. 429 with JSON custom message preserves server rate-limit message', () => {
            const classified = WikiContribution.classifySubmissionResponse({
                status: 429,
                contentType: 'application/json',
                body: {
                    message: 'Bạn đã đạt giới hạn gửi đóng góp. Vui lòng thử lại sau 300 giây.'
                }
            });

            assert.strictEqual(classified.type, 'RATE_LIMITED');
            assert.strictEqual(classified.status, 429);
            assert.strictEqual(classified.message, 'Bạn đã đạt giới hạn gửi đóng góp. Vui lòng thử lại sau 300 giây.');
        });
    });

    describe('6. CSRF Meta Tag Extraction', () => {
        test('6a. getCsrf extracts token and headerName when meta tags exist', () => {
            const mockDoc = {
                querySelector: (sel) => {
                    if (sel === 'meta[name="_csrf"]') {
                        return { getAttribute: (k) => k === 'content' ? 'test-csrf-token-123' : null };
                    }
                    if (sel === 'meta[name="_csrf_header"]') {
                        return { getAttribute: (k) => k === 'content' ? 'X-XSRF-TOKEN' : null };
                    }
                    return null;
                }
            };
            const csrf = WikiContribution.getCsrf(mockDoc);
            assert.strictEqual(csrf.token, 'test-csrf-token-123');
            assert.strictEqual(csrf.headerName, 'X-XSRF-TOKEN');
        });

        test('6b. getCsrf returns null when meta tags are absent', () => {
            const mockDoc = { querySelector: () => null };
            assert.strictEqual(WikiContribution.getCsrf(mockDoc), null);
        });
    });

    describe('7. Article URL resolution', () => {
        test('7a. getArticleUrl resolves known types and slugs correctly', () => {
            assert.strictEqual(WikiContribution.getArticleUrl('CHARACTER', 'tran-binh-an'), '/wiki/character/tran-binh-an');
            assert.strictEqual(WikiContribution.getArticleUrl('CULTIVATION_PATH', 'kiem-tu'), '/wiki/cultivation-path/kiem-tu');
            assert.strictEqual(WikiContribution.getArticleUrl('TIMELINE_EVENT', 'dai-chien'), '/wiki/timeline-event/dai-chien');
        });
    });

    describe('8. Modal Lifecycle, State Isolation & Generation Invalidation', () => {
        function setupMockDom() {
            const elements = {};
            function getOrCreate(id) {
                if (!elements[id]) {
                    const attrs = {};
                    elements[id] = {
                        id,
                        value: '',
                        textContent: '',
                        innerHTML: '',
                        style: {},
                        setAttribute(k, v) { attrs[k] = String(v); },
                        getAttribute(k) { return attrs[k] !== undefined ? attrs[k] : null; },
                        hasAttribute(k) { return attrs[k] !== undefined; },
                        removeAttribute(k) { delete attrs[k]; },
                        querySelectorAll() { return []; },
                        focus() {}
                    };
                }
                return elements[id];
            }

            // Define modal as initially hidden
            const modal = getOrCreate('wikiContributionModal');
            modal.setAttribute('hidden', '');

            getOrCreate('wikiContributionSelectionPreview').setAttribute('hidden', '');
            getOrCreate('wikiContributionSelectedText');
            getOrCreate('wikiContributionAnchorHint').setAttribute('hidden', '');
            getOrCreate('wikiContributionMessageInput');
            getOrCreate('wikiContributionTypeSelect');
            getOrCreate('wikiContributionSourcesList');
            getOrCreate('wikiContributionSubmitBtn');
            getOrCreate('wikiContributionStatus').setAttribute('hidden', '');
            getOrCreate('wikiContributionMessageCounter');
            getOrCreate('wikiContributionSourceCounter');
            getOrCreate('wikiContributionAddSourceBtn');

            globalThis.document = {
                getElementById: (id) => elements[id] || null,
                body: { style: {} },
                activeElement: null
            };
        }

        beforeEach(() => {
            setupMockDom();
        });

        test('8a. GENERAL mode clears active selection context (activeSnapshot is null)', () => {
            const opened = WikiContribution.openModal('GENERAL');
            assert.strictEqual(opened, true);
            assert.strictEqual(WikiContribution.getCurrentContextMode(), 'GENERAL');
            assert.strictEqual(WikiContribution.getActiveSnapshot(), null);
        });

        test('8b. TEXT_SELECTION mode installs the supplied immutable snapshot', () => {
            const mockSnapshot = Object.freeze({
                selectedText: 'Đoạn trích dẫn kiểm tra',
                selectedPrefix: 'prefix',
                selectedSuffix: 'suffix',
                selectedHeadingAnchor: 'anchor-1'
            });

            const opened = WikiContribution.openModal('TEXT_SELECTION', mockSnapshot);
            assert.strictEqual(opened, true);
            assert.strictEqual(WikiContribution.getCurrentContextMode(), 'TEXT_SELECTION');
            assert.strictEqual(WikiContribution.getActiveSnapshot(), mockSnapshot);
            assert.strictEqual(WikiContribution.getActiveSnapshot().selectedText, 'Đoạn trích dẫn kiểm tra');
        });

        test('8c. Unknown modal mode does not silently become GENERAL and fails closed', () => {
            const opened = WikiContribution.openModal('UNKNOWN_MODE');
            assert.strictEqual(opened, false);
            assert.strictEqual(globalThis.document.getElementById('wikiContributionModal').hasAttribute('hidden'), true);
        });

        test('8d. TEXT_SELECTION with missing or empty snapshot fails closed and does not open modal', () => {
            const openedNull = WikiContribution.openModal('TEXT_SELECTION', null);
            assert.strictEqual(openedNull, false);

            const openedEmpty = WikiContribution.openModal('TEXT_SELECTION', { selectedText: '' });
            assert.strictEqual(openedEmpty, false);
            assert.strictEqual(globalThis.document.getElementById('wikiContributionModal').hasAttribute('hidden'), true);
        });

        test('8e. Closing an open modal invalidates pending generation', () => {
            const genBefore = WikiContribution.getModalGeneration();

            // Open modal -> generation advances
            WikiContribution.openModal('GENERAL');
            const genOpen = WikiContribution.getModalGeneration();
            assert.strictEqual(genOpen, genBefore + 1);

            // Close open modal -> generation advances (invalidating in-flight submission)
            const closed = WikiContribution.closeModal();
            assert.strictEqual(closed, true);
            const genClosed = WikiContribution.getModalGeneration();
            assert.strictEqual(genClosed, genOpen + 1);

            // Calling closeModal() again while already hidden does NOT advance generation
            const closedAgain = WikiContribution.closeModal();
            assert.strictEqual(closedAgain, false);
            assert.strictEqual(WikiContribution.getModalGeneration(), genClosed);
        });
    });
});
