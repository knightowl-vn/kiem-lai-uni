const test = require('node:test');
const assert = require('node:assert');
const { ManagedAudioEngine, isSupported, buildVoiceCatalogUrl } = require('../../../main/resources/static/js/novel/managed-audio-engine.js');

test('ManagedAudioEngine Voice Catalog Client Tests', async (t) => {
    await t.test('1. isSupported describes fetch capability without HTMLAudioElement dependency', () => {
        assert.strictEqual(typeof isSupported, 'function');
        const engine = new ManagedAudioEngine();
        assert.strictEqual(engine.isSupported(), true);
        assert.strictEqual(buildVoiceCatalogUrl(), '/api/novel/narration/voices');
        assert.strictEqual(engine.audio, undefined, 'engine must not own an audio element');
    });

    await t.test('2. loadVoiceCatalog loads, caches, and exposes voices without Audio object', async () => {
        const mockVoices = [
            { voiceKey: 'v1', displayName: 'Voice 1', defaultVoice: true },
            { voiceKey: 'v2', displayName: 'Voice 2', defaultVoice: false }
        ];
        let fetchedUrl = null;
        let fetchedSignal = null;
        const engine = new ManagedAudioEngine({
            fetchFunction: async (url, opts) => {
                fetchedUrl = url;
                fetchedSignal = opts.signal;
                return {
                    ok: true,
                    json: async () => ({ voices: mockVoices })
                };
            }
        });

        const result = await engine.loadVoiceCatalog();
        assert.strictEqual(fetchedUrl, '/api/novel/narration/voices');
        assert.deepStrictEqual(result.voices, mockVoices);
        assert.deepStrictEqual(engine.getVoices(), mockVoices);

        // Safe copy test
        const returnedVoices = engine.getVoices();
        returnedVoices.push({ voiceKey: 'malicious' });
        assert.strictEqual(engine.getVoices().length, 2, 'getVoices() must return safe copies');
    });

    await t.test('3. late/superseded catalog request cannot overwrite newer catalog request', async () => {
        let resolveReq1;
        const req1Promise = new Promise(resolve => { resolveReq1 = resolve; });

        let reqCount = 0;
        const engine = new ManagedAudioEngine({
            fetchFunction: async () => {
                reqCount++;
                if (reqCount === 1) {
                    await req1Promise;
                    return {
                        ok: true,
                        json: async () => ({ voices: [{ voiceKey: 'stale-v1' }] })
                    };
                }
                return {
                    ok: true,
                    json: async () => ({ voices: [{ voiceKey: 'fresh-v2' }] })
                };
            }
        });

        // Start request 1 (will hang)
        const p1 = engine.loadVoiceCatalog();

        // Start request 2 (resolves immediately and supersedes req 1)
        const result2 = await engine.loadVoiceCatalog();
        assert.deepStrictEqual(result2.voices, [{ voiceKey: 'fresh-v2' }]);
        assert.deepStrictEqual(engine.getVoices(), [{ voiceKey: 'fresh-v2' }]);

        // Now let request 1 finish
        resolveReq1();
        let p1Error = null;
        try {
            await p1;
        } catch (e) {
            p1Error = e;
        }
        assert.ok(p1Error, 'Superseded request 1 must reject with AbortError');
        assert.strictEqual(p1Error.name, 'AbortError');
        assert.deepStrictEqual(engine.getVoices(), [{ voiceKey: 'fresh-v2' }], 'Fresh voices must remain untouched');
    });

    await t.test('4. cancelVoiceCatalogLoad aborts in-flight request and advances sequence', async () => {
        let aborted = false;
        const engine = new ManagedAudioEngine({
            fetchFunction: async (url, opts) => {
                if (opts.signal) {
                    opts.signal.addEventListener('abort', () => { aborted = true; });
                }
                return new Promise(() => {}); // never resolves
            }
        });

        engine.loadVoiceCatalog().catch(() => {});
        assert.strictEqual(aborted, false);

        engine.cancelVoiceCatalogLoad();
        assert.strictEqual(aborted, true, 'Active AbortController must be aborted');
    });

    await t.test('5. destroy cancels catalog discovery and clears cached voices', () => {
        const engine = new ManagedAudioEngine();
        engine.availableVoices = [{ voiceKey: 'v1' }];
        let cancelCalled = false;
        engine.cancelVoiceCatalogLoad = () => { cancelCalled = true; };

        engine.destroy();
        assert.strictEqual(cancelCalled, true);
        assert.deepStrictEqual(engine.getVoices(), []);
    });

    await t.test('6. ManagedAudioEngine has zero legacy playback methods', () => {
        const engine = new ManagedAudioEngine();
        assert.strictEqual(engine.play, undefined);
        assert.strictEqual(engine.pause, undefined);
        assert.strictEqual(engine.resume, undefined);
        assert.strictEqual(engine.stop, undefined);
        assert.strictEqual(engine.loadManifest, undefined);
        assert.strictEqual(engine.setManifest, undefined);
        assert.strictEqual(engine.prepareSegmentPlayback, undefined);
        assert.strictEqual(engine.seekToSegment, undefined);
        assert.strictEqual(engine.seekBySeconds, undefined);
        assert.strictEqual(engine.getState, undefined);
        assert.strictEqual(engine.getProgress, undefined);
        assert.strictEqual(engine.getSegments, undefined);
        assert.strictEqual(engine.getCurrentChunkIndex, undefined);
    });

    await t.test('7. valid { voices: [] } succeeds and updates cached voices', async () => {
        const engine = new ManagedAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => ({ voices: [] })
            })
        });
        // Seed with cached voices to prove valid empty catalog genuinely updates cached voices
        engine.availableVoices = [{ voiceKey: 'prior-v1' }];

        const result = await engine.loadVoiceCatalog();
        assert.deepStrictEqual(result.voices, []);
        assert.deepStrictEqual(engine.getVoices(), []);
    });

    await t.test('8. malformed {} rejects and preserves cached voices', async () => {
        const engine = new ManagedAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => ({})
            })
        });
        const initialVoices = [{ voiceKey: 'existing-v1', displayName: 'Voice 1' }];
        engine.availableVoices = initialVoices.slice();

        await assert.rejects(
            async () => { await engine.loadVoiceCatalog(); },
            /Invalid or malformed Managed voice catalog response/
        );
        assert.deepStrictEqual(engine.getVoices(), initialVoices, 'Cached voices must be preserved on malformed {}');
    });

    await t.test('9. malformed { voices: null } rejects and preserves cached voices', async () => {
        const engine = new ManagedAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => ({ voices: null })
            })
        });
        const initialVoices = [{ voiceKey: 'existing-v1', displayName: 'Voice 1' }];
        engine.availableVoices = initialVoices.slice();

        await assert.rejects(
            async () => { await engine.loadVoiceCatalog(); },
            /Invalid or malformed Managed voice catalog response/
        );
        assert.deepStrictEqual(engine.getVoices(), initialVoices, 'Cached voices must be preserved on malformed { voices: null }');
    });

    await t.test('10. malformed non-array voices rejects and preserves cached voices', async () => {
        const initialVoices = [{ voiceKey: 'existing-v1', displayName: 'Voice 1' }];
        const malformedPayloads = [
            null,
            { voices: {} },
            { voices: 'invalid' },
            { voices: 123 },
            { voices: true }
        ];

        for (const payload of malformedPayloads) {
            const engine = new ManagedAudioEngine({
                fetchFunction: async () => ({
                    ok: true,
                    json: async () => payload
                })
            });
            engine.availableVoices = initialVoices.slice();

            await assert.rejects(
                async () => { await engine.loadVoiceCatalog(); },
                /Invalid or malformed Managed voice catalog response/,
                `Payload ${JSON.stringify(payload)} must reject`
            );
            assert.deepStrictEqual(engine.getVoices(), initialVoices, `Cached voices must be preserved for payload ${JSON.stringify(payload)}`);
        }
    });

    await t.test('11. HTTP failure and JSON parse failure reject and preserve cached voices', async () => {
        const initialVoices = [{ voiceKey: 'existing-v1', displayName: 'Voice 1' }];

        // HTTP failure
        const httpEngine = new ManagedAudioEngine({
            fetchFunction: async () => ({
                ok: false,
                status: 500,
                json: async () => ({})
            })
        });
        httpEngine.availableVoices = initialVoices.slice();
        await assert.rejects(
            async () => { await httpEngine.loadVoiceCatalog(); },
            /HTTP 500/
        );
        assert.deepStrictEqual(httpEngine.getVoices(), initialVoices, 'Cached voices preserved on HTTP failure');

        // JSON parse failure
        const parseEngine = new ManagedAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => { throw new SyntaxError('Unexpected token < in JSON'); }
            })
        });
        parseEngine.availableVoices = initialVoices.slice();
        await assert.rejects(
            async () => { await parseEngine.loadVoiceCatalog(); },
            SyntaxError
        );
        assert.deepStrictEqual(parseEngine.getVoices(), initialVoices, 'Cached voices preserved on JSON parse failure');
    });
});
