const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const { ChapterAudioEngine, buildPlaybackUrl, buildPrepareUrl, resolveCsrfToken } =
    require(path.join(__dirname, '../../../main/resources/static/js/novel/chapter-audio-engine.js'));

function createFakeDoc({ metaToken = null, metaHeader = null, fallbackToken = null, fallbackHeader = null } = {}) {
    return {
        querySelector(selector) {
            if (selector === 'meta[name="_csrf"]') {
                return metaToken !== null ? { getAttribute: (attr) => attr === 'content' ? metaToken : null } : null;
            }
            if (selector === 'meta[name="_csrf_header"]') {
                return metaHeader !== null ? { getAttribute: (attr) => attr === 'content' ? metaHeader : null } : null;
            }
            if (selector === '[data-csrf-token][data-csrf-header]') {
                return (fallbackToken !== null && fallbackHeader !== null) ? {
                    getAttribute: (attr) => {
                        if (attr === 'data-csrf-token') return fallbackToken;
                        if (attr === 'data-csrf-header') return fallbackHeader;
                        return null;
                    }
                } : null;
            }
            return null;
        }
    };
}

class FakeAudio {
    constructor() {
        this.src = 'http://example.com/playing.mp3';
        this.paused = false;
        this.currentTime = 10;
        this.duration = 100;
        this.playCount = 0;
        this.stopCount = 0;
        this._listeners = [];
    }
    play() {
        this.playCount++;
        this.paused = false;
        return Promise.resolve();
    }
    pause() {
        this.paused = true;
    }
    removeAttribute(attr) {
        if (attr === 'src') this.src = '';
    }
    load() {}
    addEventListener(name, cb) { this._listeners.push([name, cb]); }
    removeEventListener() {}
}

describe('H.9I5C1 ChapterAudioEngine Preparation Transport Tests', () => {

    test('1. buildPrepareUrl builds exact encoded chapter-level endpoint', () => {
        assert.strictEqual(
            buildPrepareUrl('chapter-123'),
            '/api/novel/chapters/chapter-123/narration/prepare'
        );
        assert.strictEqual(
            buildPrepareUrl('ch/1 2'),
            '/api/novel/chapters/ch%2F1%202/narration/prepare'
        );
        assert.strictEqual(
            ChapterAudioEngine.buildPrepareUrl('uuid-456'),
            '/api/novel/chapters/uuid-456/narration/prepare'
        );

        const engine = new ChapterAudioEngine();
        assert.strictEqual(
            engine.buildPrepareUrl('uuid-789'),
            '/api/novel/chapters/uuid-789/narration/prepare'
        );
    });

    test('2. Valid 202 BUILDING: POST URL, method, headers, no-store, exact body, CSRF, and result', async () => {
        let capturedUrl = null;
        let capturedOptions = null;

        const fakeFetch = async (url, opts) => {
            capturedUrl = url;
            capturedOptions = opts;
            return {
                status: 202,
                ok: true,
                json: async () => ({
                    chapterId: 'ch-1',
                    voiceKey: 'voice-alpha',
                    availability: 'BUILDING'
                })
            };
        };

        const doc = createFakeDoc({ metaToken: 'test-token', metaHeader: 'X-CSRF-TOKEN' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-alpha');

        assert.strictEqual(capturedUrl, '/api/novel/chapters/ch-1/narration/prepare');
        assert.strictEqual(capturedOptions.method, 'POST');
        assert.strictEqual(capturedOptions.cache, 'no-store');
        assert.strictEqual(capturedOptions.headers['Accept'], 'application/json');
        assert.strictEqual(capturedOptions.headers['Content-Type'], 'application/json');
        assert.strictEqual(capturedOptions.headers['X-CSRF-TOKEN'], 'test-token');
        assert.strictEqual(capturedOptions.body, JSON.stringify({ voiceKey: 'voice-alpha' }));

        assert.strictEqual(result.status, 'BUILDING');
        assert.deepStrictEqual(result.response, {
            chapterId: 'ch-1',
            voiceKey: 'voice-alpha',
            availability: 'BUILDING'
        });
    });

    test('3. Valid CSRF resolved from meta tags', async () => {
        let capturedHeaders = null;
        const fakeFetch = async (url, opts) => {
            capturedHeaders = opts.headers;
            return {
                status: 202,
                ok: true,
                json: async () => ({ chapterId: 'ch-1', voiceKey: 'voice-a', availability: 'BUILDING' })
            };
        };

        const doc = createFakeDoc({ metaToken: 'meta-token-val', metaHeader: 'X-XSRF-HEADER' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a', { document: doc });
        assert.strictEqual(result.status, 'BUILDING');
        assert.strictEqual(capturedHeaders['X-XSRF-HEADER'], 'meta-token-val');
    });

    test('4. Valid CSRF resolved from fallback element data attributes', async () => {
        let capturedHeaders = null;
        const fakeFetch = async (url, opts) => {
            capturedHeaders = opts.headers;
            return {
                status: 202,
                ok: true,
                json: async () => ({ chapterId: 'ch-1', voiceKey: 'voice-a', availability: 'BUILDING' })
            };
        };

        const doc = createFakeDoc({ fallbackToken: 'fallback-token-val', fallbackHeader: 'X-FALLBACK-CSRF' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'BUILDING');
        assert.strictEqual(capturedHeaders['X-FALLBACK-CSRF'], 'fallback-token-val');
    });

    test('5. Missing CSRF returns UNKNOWN without calling fetch', async () => {
        let fetchCalled = false;
        const fakeFetch = async () => {
            fetchCalled = true;
            return { status: 202 };
        };

        const doc = createFakeDoc(); // empty doc
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'UNKNOWN');
        assert.strictEqual(fetchCalled, false);
    });

    test('6. HTTP 503 returns REJECTED without throwing', async () => {
        const fakeFetch = async () => ({
            status: 503,
            ok: false
        });

        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'REJECTED');
    });

    test('7. HTTP 400 and 404 return UNAVAILABLE without exposing response bodies', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });

        const fakeFetch400 = async () => ({
            status: 400,
            ok: false,
            json: async () => ({ error: 'internal details that must not leak' })
        });
        const engine400 = new ChapterAudioEngine({ fetchFunction: fakeFetch400, document: doc });
        const res400 = await engine400.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(res400.status, 'UNAVAILABLE');
        assert.strictEqual(res400.response, undefined);

        const fakeFetch404 = async () => ({
            status: 404,
            ok: false
        });
        const engine404 = new ChapterAudioEngine({ fetchFunction: fakeFetch404, document: doc });
        const res404 = await engine404.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(res404.status, 'UNAVAILABLE');
        assert.strictEqual(res404.response, undefined);
    });

    test('8. Network failure returns UNKNOWN', async () => {
        const fakeFetch = async () => {
            throw new TypeError('Network connection lost');
        };

        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'UNKNOWN');
    });

    test('9. Malformed JSON / payload returns UNKNOWN', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });

        // Invalid JSON syntax
        const fakeFetchBadJson = async () => ({
            status: 202,
            ok: true,
            json: async () => { throw new SyntaxError('Unexpected token <'); }
        });
        const engine1 = new ChapterAudioEngine({ fetchFunction: fakeFetchBadJson, document: doc });
        assert.strictEqual((await engine1.requestPlaybackPreparation('ch-1', 'voice-a')).status, 'UNKNOWN');

        // null JSON payload
        const fakeFetchNull = async () => ({
            status: 202,
            ok: true,
            json: async () => null
        });
        const engine2 = new ChapterAudioEngine({ fetchFunction: fakeFetchNull, document: doc });
        assert.strictEqual((await engine2.requestPlaybackPreparation('ch-1', 'voice-a')).status, 'UNKNOWN');
    });

    test('10. ChapterId mismatch returns UNKNOWN', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const fakeFetch = async () => ({
            status: 202,
            ok: true,
            json: async () => ({
                chapterId: 'other-chapter-id',
                voiceKey: 'voice-a',
                availability: 'BUILDING'
            })
        });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });
        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'UNKNOWN');
    });

    test('11. VoiceKey mismatch returns UNKNOWN', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const fakeFetch = async () => ({
            status: 202,
            ok: true,
            json: async () => ({
                chapterId: 'ch-1',
                voiceKey: 'wrong-voice',
                availability: 'BUILDING'
            })
        });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });
        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'UNKNOWN');
    });

    test('12. Unexpected availability value returns UNKNOWN', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const fakeFetch = async () => ({
            status: 202,
            ok: true,
            json: async () => ({
                chapterId: 'ch-1',
                voiceKey: 'voice-a',
                availability: 'READY' // not BUILDING
            })
        });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });
        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'UNKNOWN');
    });

    test('13. AbortError propagates without conversion', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });

        // Pre-aborted signal
        const controller1 = new AbortController();
        controller1.abort();
        const engine1 = new ChapterAudioEngine({ fetchFunction: async () => ({ status: 202 }), document: doc });

        await assert.rejects(
            async () => {
                await engine1.requestPlaybackPreparation('ch-1', 'voice-a', { signal: controller1.signal });
            },
            (err) => err && (err.name === 'AbortError' || err.message === 'The operation was aborted')
        );

        // Fetch throws AbortError
        const fakeFetchAborted = async () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            throw err;
        };
        const engine2 = new ChapterAudioEngine({ fetchFunction: fakeFetchAborted, document: doc });

        await assert.rejects(
            async () => {
                await engine2.requestPlaybackPreparation('ch-1', 'voice-a');
            },
            (err) => err && (err.name === 'AbortError' || err.message === 'The operation was aborted')
        );
    });

    test('13b. HTTP 202 response.json() throws AbortError and requestPlaybackPreparation rejects with AbortError', async () => {
        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const fakeFetchJsonAborted = async () => ({
            status: 202,
            ok: true,
            json: async () => {
                const err = new Error('The operation was aborted');
                err.name = 'AbortError';
                throw err;
            }
        });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetchJsonAborted, document: doc });

        await assert.rejects(
            async () => {
                await engine.requestPlaybackPreparation('ch-1', 'voice-a');
            },
            (err) => {
                assert.ok(err, 'Expected error to be thrown');
                assert.strictEqual(err.name, 'AbortError');
                return true;
            }
        );
    });

    test('14. Method has zero playback mutation on active playback state', async () => {
        let newAudioInstances = 0;
        const fakeAudioFactory = () => {
            newAudioInstances++;
            return new FakeAudio();
        };

        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const fakeFetch = async () => ({
            status: 202,
            ok: true,
            json: async () => ({ chapterId: 'ch-1', voiceKey: 'voice-a', availability: 'BUILDING' })
        });

        const engine = new ChapterAudioEngine({
            fetchFunction: fakeFetch,
            document: doc,
            audioFactory: fakeAudioFactory
        });

        // Simulate active PLAYING state with audio and metadata
        const activeAudio = new FakeAudio();
        engine.audio = activeAudio;
        engine.state = 'PLAYING';
        engine.metadata = { chapterId: 'ch-0', voiceKey: 'voice-prev', audioUrl: 'http://example.com/audio.mp3' };
        engine.cues = [{ startMillis: 0, endMillis: 5000 }];
        engine._activeCueIndex = 0;

        // Perform preparation request for a different chapter
        const result = await engine.requestPlaybackPreparation('ch-1', 'voice-a');
        assert.strictEqual(result.status, 'BUILDING');

        // Verify zero playback state mutation
        assert.strictEqual(engine.getState(), 'PLAYING');
        assert.strictEqual(engine.audio, activeAudio);
        assert.strictEqual(activeAudio.src, 'http://example.com/playing.mp3');
        assert.strictEqual(activeAudio.paused, false);
        assert.strictEqual(engine.metadata.chapterId, 'ch-0');
        assert.strictEqual(engine.cues.length, 1);
        assert.strictEqual(engine._activeCueIndex, 0);
        assert.strictEqual(newAudioInstances, 0);
    });

    test('15. Existing probePlaybackMetadata behavior remains unchanged', async () => {
        const fakeFetch = async (url) => {
            if (url.includes('ready-ch')) {
                return {
                    status: 200,
                    ok: true,
                    json: async () => ({
                        chapterId: 'ready-ch',
                        voiceKey: 'voice-a',
                        availability: 'READY',
                        playable: true,
                        freshness: 'CURRENT',
                        audioUrl: '/audio.mp3',
                        cues: [{ startMillis: 0, endMillis: 1000 }]
                    })
                };
            }
            if (url.includes('stale-content-ch')) {
                return {
                    status: 200,
                    ok: true,
                    json: async () => ({
                        chapterId: 'stale-content-ch',
                        voiceKey: 'voice-a',
                        availability: 'READY',
                        playable: false,
                        freshness: 'STALE_CONTENT',
                        cues: []
                    })
                };
            }
            if (url.includes('missing-ch')) {
                return {
                    status: 200,
                    ok: true,
                    json: async () => ({
                        chapterId: 'missing-ch',
                        voiceKey: 'voice-a',
                        availability: 'MISSING',
                        playable: false,
                        cues: []
                    })
                };
            }
            return { status: 404, ok: false };
        };

        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch });

        const readyRes = await engine.probePlaybackMetadata('ready-ch', 'voice-a');
        assert.strictEqual(readyRes.status, 'ready');

        const staleRes = await engine.probePlaybackMetadata('stale-content-ch', 'voice-a');
        assert.strictEqual(staleRes.status, 'unavailable');

        const missingRes = await engine.probePlaybackMetadata('missing-ch', 'voice-a');
        assert.strictEqual(missingRes.status, 'unavailable');

        const notFoundRes = await engine.probePlaybackMetadata('not-found-ch', 'voice-a');
        assert.strictEqual(notFoundRes.status, 'unknown');
    });

    test('16. Input validation: null/empty/blank arguments return UNKNOWN without fetching', async () => {
        let fetchCalled = false;
        const fakeFetch = async () => {
            fetchCalled = true;
            return { status: 202 };
        };

        const doc = createFakeDoc({ metaToken: 'tok', metaHeader: 'X-CSRF' });
        const engine = new ChapterAudioEngine({ fetchFunction: fakeFetch, document: doc });

        assert.strictEqual((await engine.requestPlaybackPreparation(null, 'voice-a')).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('', 'voice-a')).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('   ', 'voice-a')).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('ch-1', null)).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('ch-1', '')).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('ch-1', '   ')).status, 'UNKNOWN');
        assert.strictEqual((await engine.requestPlaybackPreparation('ch-1', 123)).status, 'UNKNOWN');
        assert.strictEqual(fetchCalled, false);
    });
});
