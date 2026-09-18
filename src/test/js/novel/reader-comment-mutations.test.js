const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommentMutations = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-mutations.js'));
const { createReply, editComment, deleteComment, getCommentRevisions, getCsrf } = CommentMutations;

describe('MS-05E5H2F2A Shared Novel Reader Comment Mutations Client', () => {

    const VALID_CSRF = { token: 'test-csrf-token-123', headerName: 'X-CSRF-TOKEN' };

    test('1. canonical Reply endpoint URL is preserved exactly', async () => {
        let capturedUrl = null;
        const fakeFetch = async (url) => {
            capturedUrl = url;
            return {
                status: 201,
                json: async () => ({ commentId: 'r-created-1' })
            };
        };

        await createReply({
            chapterId: 'chapter-uuid-456',
            parentCommentId: 'parent-comment-uuid-789',
            body: 'Hello reply'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(
            capturedUrl,
            '/api/novel/chapters/chapter-uuid-456/comments/parent-comment-uuid-789/replies'
        );
    });

    test('2. canonical HTTP method is preserved as POST', async () => {
        let capturedMethod = null;
        const fakeFetch = async (url, options) => {
            capturedMethod = options.method;
            return {
                status: 201,
                json: async () => ({ commentId: 'r-created-2' })
            };
        };

        await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'Method check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedMethod, 'POST');
    });

    test('3. request body is preserved exactly as trimmed { body: "..." }', async () => {
        let capturedBody = null;
        let capturedContentType = null;
        const fakeFetch = async (url, options) => {
            capturedContentType = options.headers['Content-Type'];
            capturedBody = JSON.parse(options.body);
            return {
                status: 201,
                json: async () => ({ commentId: 'r-created-3' })
            };
        };

        await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: '  Trimmed reply body  '
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedContentType, 'application/json');
        assert.deepStrictEqual(capturedBody, { body: 'Trimmed reply body' });
    });

    test('4. body does not include client-side author, userId, canEdit, canDelete, or fabricated DTO fields', async () => {
        let capturedBody = null;
        const fakeFetch = async (url, options) => {
            capturedBody = JSON.parse(options.body);
            return {
                status: 201,
                json: async () => ({ commentId: 'r-created-4' })
            };
        };

        await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'Pure payload check',
            // Pass extraneous properties to ensure they are NOT forwarded in payload
            author: 'Hacker',
            userId: 'fake-id',
            canEdit: true,
            canDelete: true,
            replyCount: 99
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(Object.keys(capturedBody).length, 1);
        assert.strictEqual(capturedBody.body, 'Pure payload check');
        assert.strictEqual(capturedBody.author, undefined);
        assert.strictEqual(capturedBody.userId, undefined);
        assert.strictEqual(capturedBody.canEdit, undefined);
        assert.strictEqual(capturedBody.canDelete, undefined);
        assert.strictEqual(capturedBody.replyCount, undefined);
    });

    test('5. canonical CSRF header is sent matching provided or extracted credentials', async () => {
        let capturedHeaders = null;
        const fakeFetch = async (url, options) => {
            capturedHeaders = options.headers;
            return {
                status: 201,
                json: async () => ({ commentId: 'r-created-5' })
            };
        };

        await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'CSRF header check'
        }, {
            fetch: fakeFetch,
            csrf: { token: 'csrf-xyz-secret', headerName: 'X-CSRF-TOKEN' }
        });

        assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'csrf-xyz-secret');
        assert.strictEqual(capturedHeaders['Accept'], 'application/json');
    });

    test('6. successful canonical 201 response resolves successfully with commentId and data', async () => {
        const fakeFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'new-uuid-001', author: { displayName: 'User' } })
        });

        const result = await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'Success check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.status, 201);
        assert.strictEqual(result.commentId, 'new-uuid-001');
        assert.strictEqual(result.data.author.displayName, 'User');
    });

    test('6b. 201 response with missing or non-json body resolves successfully without error', async () => {
        const fakeFetch = async () => ({
            status: 201
            // no json() method
        });

        const result = await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'Bodyless 201'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.status, 201);
        assert.strictEqual(result.commentId, null);
        assert.strictEqual(result.data, null);
    });

    test('7. non-success HTTP (401, 403, 404, 500) rejects deterministically with HTTP status error', async () => {
        for (const status of [400, 401, 403, 404, 500]) {
            const fakeFetch = async () => ({
                status: status,
                json: async () => ({ message: 'Error detail for ' + status })
            });

            await assert.rejects(
                () => createReply({
                    chapterId: 'c-1',
                    parentCommentId: 'p-1',
                    body: 'Error check'
                }, {
                    fetch: fakeFetch,
                    csrf: VALID_CSRF
                }),
                (err) => {
                    assert.strictEqual(err.status, status);
                    assert.strictEqual(err.message, 'HTTP ' + status);
                    assert.strictEqual(err.data.message, 'Error detail for ' + status);
                    return true;
                }
            );
        }
    });

    test('8. network failure rejects deterministically with original network error', async () => {
        const networkError = new Error('Connection reset by peer');
        const fakeFetch = async () => {
            throw networkError;
        };

        await assert.rejects(
            () => createReply({
                chapterId: 'c-1',
                parentCommentId: 'p-1',
                body: 'Network fail check'
            }, {
                fetch: fakeFetch,
                csrf: VALID_CSRF
            }),
            (err) => {
                assert.strictEqual(err.message, 'Connection reset by peer');
                return true;
            }
        );
    });

    test('9. client performs no DOM rendering', async () => {
        // Run in headless node environment without any mock DOM
        const fakeFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'r-no-dom' })
        });

        // Calling without window/document must not throw reference error
        const result = await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'No DOM check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
    });

    test('10. client does not refresh drawer or feed itself', async () => {
        let drawerRefreshed = false;
        let feedRefreshed = false;

        const fakeFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'r-no-side-effect' })
        });

        // Even if drawer/feed objects are passed or present on global, createReply does NOT touch them
        await createReply({
            chapterId: 'c-1',
            parentCommentId: 'p-1',
            body: 'No side effect check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF,
            drawerModule: { refreshActiveDiscussion: () => { drawerRefreshed = true; } },
            commentsModule: { refreshFromPageZero: () => { feedRefreshed = true; } }
        });

        assert.strictEqual(drawerRefreshed, false);
        assert.strictEqual(feedRefreshed, false);
    });

    test('11. same client can be called without drawer-specific DOM objects', async () => {
        const fakeFetch = async () => ({
            status: 201,
            json: async () => ({ commentId: 'r-clean-call' })
        });

        // Pure POJO call with explicit params and options
        const result = await createReply({
            chapterId: 'chap-100',
            parentCommentId: 'comm-200',
            body: 'Clean POJO call'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.commentId, 'r-clean-call');
    });

    test('12. client rejects when required parameters are missing or blank', async () => {
        await assert.rejects(
            () => createReply({ chapterId: '', parentCommentId: 'p-1', body: 'text' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => createReply({ chapterId: 'c-1', parentCommentId: '', body: 'text' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => createReply({ chapterId: 'c-1', parentCommentId: 'p-1', body: '   ' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_BODY' }
        );

        await assert.rejects(
            () => createReply({ chapterId: 'c-1', parentCommentId: 'p-1', body: 'valid' }, { csrf: null, fetch: async () => {} }),
            { code: 'CSRF_MISSING' }
        );
    });

    test('13. getCsrf extracts csrf meta tags from mock document', () => {
        const mockDoc = {
            querySelector: (sel) => {
                if (sel === 'meta[name="_csrf"]') {
                    return { getAttribute: (attr) => attr === 'content' ? 'doc-token-123' : null };
                }
                if (sel === 'meta[name="_csrf_header"]') {
                    return { getAttribute: (attr) => attr === 'content' ? 'X-DOC-CSRF' : null };
                }
                return null;
            }
        };

        const csrf = getCsrf(mockDoc);
        assert.deepStrictEqual(csrf, { token: 'doc-token-123', headerName: 'X-DOC-CSRF' });
    });

    // =========================================================================
    // MS-05E5H2F3A: editComment tests
    // =========================================================================

    test('14. canonical Edit endpoint URL is preserved exactly', async () => {
        let capturedUrl = null;
        const fakeFetch = async (url) => {
            capturedUrl = url;
            return { status: 204 };
        };

        await editComment({
            chapterId: 'chapter-uuid-456',
            commentId: 'comment-uuid-789',
            body: 'Updated text'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(
            capturedUrl,
            '/api/novel/chapters/chapter-uuid-456/comments/comment-uuid-789'
        );
    });

    test('15. canonical HTTP method for Edit is preserved as PATCH', async () => {
        let capturedMethod = null;
        const fakeFetch = async (url, options) => {
            capturedMethod = options.method;
            return { status: 204 };
        };

        await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'Method check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedMethod, 'PATCH');
    });

    test('16. request body for Edit is preserved exactly as trimmed { body: "..." }', async () => {
        let capturedBody = null;
        let capturedContentType = null;
        const fakeFetch = async (url, options) => {
            capturedContentType = options.headers['Content-Type'];
            capturedBody = JSON.parse(options.body);
            return { status: 204 };
        };

        await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: '  Trimmed edited body  '
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedContentType, 'application/json');
        assert.deepStrictEqual(capturedBody, { body: 'Trimmed edited body' });
    });

    test('17. Edit body does not include client-side author, userId, canEdit, canDelete, or fabricated DTO fields', async () => {
        let capturedBody = null;
        const fakeFetch = async (url, options) => {
            capturedBody = JSON.parse(options.body);
            return { status: 204 };
        };

        await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'Pure edit payload',
            author: 'Impostor',
            userId: 'fake-user-id',
            canEdit: true,
            canDelete: true,
            edited: true
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(Object.keys(capturedBody).length, 1);
        assert.strictEqual(capturedBody.body, 'Pure edit payload');
        assert.strictEqual(capturedBody.author, undefined);
        assert.strictEqual(capturedBody.userId, undefined);
        assert.strictEqual(capturedBody.canEdit, undefined);
        assert.strictEqual(capturedBody.canDelete, undefined);
        assert.strictEqual(capturedBody.edited, undefined);
    });

    test('18. canonical CSRF header is sent matching credentials on Edit', async () => {
        let capturedHeaders = null;
        const fakeFetch = async (url, options) => {
            capturedHeaders = options.headers;
            return { status: 204 };
        };

        await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'CSRF header check'
        }, {
            fetch: fakeFetch,
            csrf: { token: 'csrf-edit-secret', headerName: 'X-CSRF-TOKEN' }
        });

        assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'csrf-edit-secret');
        assert.strictEqual(capturedHeaders['Accept'], 'application/json');
        assert.strictEqual(capturedHeaders['Content-Type'], 'application/json');
    });

    test('19. successful canonical 204 response resolves successfully without requiring JSON body', async () => {
        const fakeFetch = async () => ({
            status: 204
        });

        const result = await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'Success 204 check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.status, 204);
        assert.strictEqual(result.data, null);
    });

    test('20. 200 response with JSON resolves successfully', async () => {
        const fakeFetch = async () => ({
            status: 200,
            json: async () => ({ success: true })
        });

        const result = await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'Success 200 check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.status, 200);
        assert.strictEqual(result.data.success, true);
    });

    test('21. Edit non-success HTTP (400, 401, 403, 404, 500) rejects deterministically with HTTP status error', async () => {
        for (const status of [400, 401, 403, 404, 500]) {
            const fakeFetch = async () => ({
                status: status,
                json: async () => ({ error: 'Edit error for ' + status })
            });

            await assert.rejects(
                () => editComment({
                    chapterId: 'c-1',
                    commentId: 'cm-1',
                    body: 'Error check'
                }, {
                    fetch: fakeFetch,
                    csrf: VALID_CSRF
                }),
                (err) => {
                    assert.strictEqual(err.status, status);
                    assert.strictEqual(err.message, 'HTTP ' + status);
                    assert.strictEqual(err.data.error, 'Edit error for ' + status);
                    return true;
                }
            );
        }
    });

    test('22. Edit network failure rejects deterministically with original network error', async () => {
        const networkError = new Error('Connection aborted');
        const fakeFetch = async () => {
            throw networkError;
        };

        await assert.rejects(
            () => editComment({
                chapterId: 'c-1',
                commentId: 'cm-1',
                body: 'Network fail'
            }, {
                fetch: fakeFetch,
                csrf: VALID_CSRF
            }),
            (err) => {
                assert.strictEqual(err.message, 'Connection aborted');
                return true;
            }
        );
    });

    test('23. Edit client performs no DOM rendering', async () => {
        const fakeFetch = async () => ({
            status: 204
        });

        const result = await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'No DOM check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
    });

    test('24. Edit client does not refresh drawer or feed itself', async () => {
        let drawerRefreshed = false;
        let feedRefreshed = false;

        const fakeFetch = async () => ({
            status: 204
        });

        await editComment({
            chapterId: 'c-1',
            commentId: 'cm-1',
            body: 'No side effect check'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF,
            drawerModule: { refreshActiveDiscussion: () => { drawerRefreshed = true; } },
            commentsModule: { refreshFromPageZero: () => { feedRefreshed = true; } }
        });

        assert.strictEqual(drawerRefreshed, false);
        assert.strictEqual(feedRefreshed, false);
    });

    test('25. editComment rejects when required parameters are missing or blank', async () => {
        await assert.rejects(
            () => editComment({ chapterId: '', commentId: 'cm-1', body: 'text' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => editComment({ chapterId: 'c-1', commentId: '', body: 'text' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => editComment({ chapterId: 'c-1', commentId: 'cm-1', body: '   ' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_BODY' }
        );

        await assert.rejects(
            () => editComment({ chapterId: 'c-1', commentId: 'cm-1', body: 'valid' }, { csrf: null, fetch: async () => {} }),
            { code: 'CSRF_MISSING' }
        );
    });

    // =========================================================================
    // MS-05E5H2F3A: deleteComment tests
    // =========================================================================

    test('26. canonical Delete endpoint URL is preserved exactly', async () => {
        let capturedUrl = null;
        const fakeFetch = async (url) => {
            capturedUrl = url;
            return { status: 204 };
        };

        await deleteComment({
            chapterId: 'chapter-uuid-456',
            commentId: 'comment-uuid-789'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(
            capturedUrl,
            '/api/novel/chapters/chapter-uuid-456/comments/comment-uuid-789'
        );
    });

    test('27. canonical HTTP method for Delete is preserved as DELETE', async () => {
        let capturedMethod = null;
        const fakeFetch = async (url, options) => {
            capturedMethod = options.method;
            return { status: 204 };
        };

        await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedMethod, 'DELETE');
    });

    test('28. Delete request body is omitted/undefined', async () => {
        let capturedBody = 'initial';
        const fakeFetch = async (url, options) => {
            capturedBody = options.body;
            return { status: 204 };
        };

        await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(capturedBody, undefined);
    });

    test('29. canonical CSRF header is sent matching credentials on Delete', async () => {
        let capturedHeaders = null;
        const fakeFetch = async (url, options) => {
            capturedHeaders = options.headers;
            return { status: 204 };
        };

        await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: { token: 'csrf-del-secret', headerName: 'X-CSRF-TOKEN' }
        });

        assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'csrf-del-secret');
        assert.strictEqual(capturedHeaders['Accept'], 'application/json');
    });

    test('30. successful canonical 204 response for Delete resolves with ok: true, status: 204', async () => {
        const fakeFetch = async () => ({
            status: 204
        });

        const result = await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
        assert.strictEqual(result.status, 204);
    });

    test('31. Delete non-success HTTP (401, 403, 404, 500) rejects deterministically with HTTP status error', async () => {
        for (const status of [401, 403, 404, 500]) {
            const fakeFetch = async () => ({
                status: status,
                json: async () => ({ error: 'Delete error for ' + status })
            });

            await assert.rejects(
                () => deleteComment({
                    chapterId: 'c-1',
                    commentId: 'cm-1'
                }, {
                    fetch: fakeFetch,
                    csrf: VALID_CSRF
                }),
                (err) => {
                    assert.strictEqual(err.status, status);
                    assert.strictEqual(err.message, 'HTTP ' + status);
                    assert.strictEqual(err.data.error, 'Delete error for ' + status);
                    return true;
                }
            );
        }
    });

    test('32. Delete network failure rejects deterministically with original network error', async () => {
        const networkError = new Error('Socket closed');
        const fakeFetch = async () => {
            throw networkError;
        };

        await assert.rejects(
            () => deleteComment({
                chapterId: 'c-1',
                commentId: 'cm-1'
            }, {
                fetch: fakeFetch,
                csrf: VALID_CSRF
            }),
            (err) => {
                assert.strictEqual(err.message, 'Socket closed');
                return true;
            }
        );
    });

    test('33. deleteComment does not fabricate tombstone DTOs or modify caller data', async () => {
        const fakeFetch = async () => ({
            status: 204
        });

        const input = {
            chapterId: 'c-1',
            commentId: 'cm-1',
            author: 'Original Author',
            isRoot: true,
            tombstone: false
        };

        const result = await deleteComment(input, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.tombstone, undefined);
        assert.strictEqual(result.commentId, undefined);
        assert.strictEqual(result.ok, true);
        // Input object must not be mutated
        assert.strictEqual(input.tombstone, false);
    });

    test('34. Delete client performs no DOM rendering', async () => {
        const fakeFetch = async () => ({
            status: 204
        });

        const result = await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF
        });

        assert.strictEqual(result.ok, true);
    });

    test('35. Delete client does not refresh drawer, feed, or indicators itself', async () => {
        let drawerRefreshed = false;
        let indicatorsRefreshed = false;

        const fakeFetch = async () => ({
            status: 204
        });

        await deleteComment({
            chapterId: 'c-1',
            commentId: 'cm-1'
        }, {
            fetch: fakeFetch,
            csrf: VALID_CSRF,
            drawerModule: { refreshActiveDiscussion: () => { drawerRefreshed = true; } },
            indicatorsModule: { refreshChapterIndicators: () => { indicatorsRefreshed = true; } }
        });

        assert.strictEqual(drawerRefreshed, false);
        assert.strictEqual(indicatorsRefreshed, false);
    });

    test('36. deleteComment rejects when required parameters are missing or blank', async () => {
        await assert.rejects(
            () => deleteComment({ chapterId: '', commentId: 'cm-1' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => deleteComment({ chapterId: 'c-1', commentId: '' }, { csrf: VALID_CSRF, fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => deleteComment({ chapterId: 'c-1', commentId: 'cm-1' }, { csrf: null, fetch: async () => {} }),
            { code: 'CSRF_MISSING' }
        );
    });

    // =========================================================================
    // MS-05E5G5E Revision History Transport Tests
    // =========================================================================

    test('37. getCommentRevisions constructs canonical GET URL with page and size defaults', async () => {
        let capturedUrl = null;
        let capturedMethod = null;
        let capturedHeaders = null;

        const fakeFetch = async (url, options) => {
            capturedUrl = url;
            capturedMethod = options.method;
            capturedHeaders = options.headers;
            return {
                status: 200,
                ok: true,
                json: async () => ({ items: [], page: 0, size: 20, hasNext: false })
            };
        };

        const res = await getCommentRevisions({
            chapterId: 'chap-100',
            commentId: 'comment-200'
        }, { fetch: fakeFetch });

        assert.strictEqual(
            capturedUrl,
            '/api/novel/chapters/chap-100/comments/comment-200/revisions?page=0&size=20'
        );
        assert.strictEqual(capturedMethod, 'GET');
        assert.strictEqual(capturedHeaders['Accept'], 'application/json');
        assert.strictEqual(res.ok, true);
        assert.strictEqual(res.data.hasNext, false);
    });

    test('38. getCommentRevisions supports positional arguments and explicit page/size', async () => {
        let capturedUrl = null;
        const fakeFetch = async (url) => {
            capturedUrl = url;
            return {
                status: 200,
                ok: true,
                json: async () => ({ items: [], page: 2, size: 10, hasNext: true })
            };
        };

        const res = await getCommentRevisions('chap-100', 'comment-200', 2, 10, { fetch: fakeFetch });

        assert.strictEqual(
            capturedUrl,
            '/api/novel/chapters/chap-100/comments/comment-200/revisions?page=2&size=10'
        );
        assert.strictEqual(res.status, 200);
        assert.strictEqual(res.data.hasNext, true);
    });

    test('39. getCommentRevisions rejects on 404 with error status 404', async () => {
        const fakeFetch = async () => ({
            status: 404,
            ok: false,
            json: async () => ({ message: 'Not found' })
        });

        await assert.rejects(
            () => getCommentRevisions({ chapterId: 'c-1', commentId: 'cm-1' }, { fetch: fakeFetch }),
            (err) => err.status === 404
        );
    });

    test('40. getCommentRevisions rejects on 500 with error status 500', async () => {
        const fakeFetch = async () => ({
            status: 500,
            ok: false,
            json: async () => ({ message: 'Server error' })
        });

        await assert.rejects(
            () => getCommentRevisions({ chapterId: 'c-1', commentId: 'cm-1' }, { fetch: fakeFetch }),
            (err) => err.status === 500
        );
    });

    test('41. getCommentRevisions rejects when required target parameters are missing', async () => {
        await assert.rejects(
            () => getCommentRevisions({ chapterId: '', commentId: 'cm-1' }, { fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );

        await assert.rejects(
            () => getCommentRevisions({ chapterId: 'c-1', commentId: '' }, { fetch: async () => {} }),
            { code: 'INVALID_TARGET' }
        );
    });
});
