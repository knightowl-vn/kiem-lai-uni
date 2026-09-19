const { describe, test, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));

class MockStorage {
    constructor() {
        this.store = new Map();
        this.shouldThrow = false;
    }

    getItem(key) {
        if (this.shouldThrow) {
            throw new Error('Storage access restricted');
        }
        return this.store.has(key) ? this.store.get(key) : null;
    }

    setItem(key, value) {
        if (this.shouldThrow) {
            throw new Error('QuotaExceededError');
        }
        this.store.set(key, String(value));
    }

    removeItem(key) {
        if (this.shouldThrow) {
            throw new Error('Storage access restricted');
        }
        this.store.delete(key);
    }

    clear() {
        this.store.clear();
    }
}

describe('UX-DRAFT-01A EphemeralDraftStore Foundation Tests', () => {

    let mockStorage;
    let mockTime;
    let store;

    beforeEach(() => {
        mockStorage = new MockStorage();
        mockTime = 1_000_000;
        store = EphemeralDraftStore.createStore({
            storage: mockStorage,
            clock: () => mockTime,
            defaultTtlMs: 5 * 60 * 1000 // 5 minutes
        });
    });

    test('1. save/load within TTL returns exact stored text', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';
        const text = 'Nội dung văn bản đang soạn thảo dở dang';

        const saved = store.save(key, text);
        assert.strictEqual(saved, true);

        // Verify stored JSON structure
        const raw = mockStorage.getItem(key);
        assert.ok(raw);
        const parsed = JSON.parse(raw);
        assert.strictEqual(parsed.value, text);
        assert.strictEqual(parsed.savedAt, mockTime);

        // Advance 2 minutes (120,000 ms) -> within 5-minute TTL
        mockTime += 120_000;
        const loaded = store.load(key);
        assert.strictEqual(loaded, text);
    });

    test('2. blank input removes draft rather than storing empty records', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';
        store.save(key, 'Initial text');
        assert.strictEqual(store.load(key), 'Initial text');

        // Save empty string -> removes
        const resEmpty = store.save(key, '');
        assert.strictEqual(resEmpty, false);
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(mockStorage.getItem(key), null);

        // Save whitespace-only string -> removes
        store.save(key, 'Another draft');
        assert.strictEqual(store.load(key), 'Another draft');

        const resWhitespace = store.save(key, '   \t\n  ');
        assert.strictEqual(resWhitespace, false);
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(mockStorage.getItem(key), null);

        // Save null or undefined -> removes
        store.save(key, 'Third draft');
        store.save(key, null);
        assert.strictEqual(store.load(key), null);
    });

    test('3. expired draft is removed and not returned', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';
        store.save(key, 'Văn bản sắp hết hạn');

        // Advance 5 minutes + 1 ms (300,001 ms)
        mockTime += (5 * 60 * 1000) + 1;

        const loaded = store.load(key);
        assert.strictEqual(loaded, null);

        // Proves the expired storage entry was deleted on encounter
        assert.strictEqual(mockStorage.getItem(key), null);
    });

    test('4. malformed storage fails safely and clears corrupted entry', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';

        // Case A: not valid JSON
        mockStorage.setItem(key, '{ invalid json');
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(mockStorage.getItem(key), null);

        // Case B: missing value or savedAt
        mockStorage.setItem(key, JSON.stringify({ savedAt: mockTime }));
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(mockStorage.getItem(key), null);

        mockStorage.setItem(key, JSON.stringify({ value: 'hello' }));
        assert.strictEqual(store.load(key), null);
        assert.strictEqual(mockStorage.getItem(key), null);

        // Case C: storage throws exception (e.g. quota or privacy mode)
        mockStorage.shouldThrow = true;
        assert.doesNotThrow(() => {
            store.save('some-key', 'some text');
            store.load('some-key');
            store.remove('some-key');
        });
        mockStorage.shouldThrow = false;
    });

    test('5. remove is idempotent', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';
        // Remove non-existent key does not throw
        assert.doesNotThrow(() => store.remove(key));
        assert.doesNotThrow(() => store.remove(key));

        store.save(key, 'Some content');
        store.remove(key);
        assert.strictEqual(mockStorage.getItem(key), null);
        // Repeated remove
        assert.doesNotThrow(() => store.remove(key));
    });

    test('6. original whitespace and multiline content preserved exactly', () => {
        const key = 'kiemlai:draft:generic-scope:target-123:main';
        const multiline = '  Đoạn 1 với khoảng trắng đầu cuối  \n\nĐoạn 2 có tab:\t\thết đoạn.  ';

        store.save(key, multiline);
        const restored = store.load(key);

        assert.strictEqual(restored, multiline);
    });

    test('7. storage resolution SecurityError or access exception fails safely without throwing', () => {
        // Case A: opts.storage getter throws SecurityError
        const throwingGetterStore = EphemeralDraftStore.createStore({
            get storage() {
                const err = new Error('SecurityError: Access is denied for this document');
                err.name = 'SecurityError';
                throw err;
            }
        });

        assert.strictEqual(throwingGetterStore.save('key1', 'val1'), false);
        assert.strictEqual(throwingGetterStore.load('key1'), null);
        assert.doesNotThrow(() => throwingGetterStore.remove('key1'));

        // Case B: storage methods throw SecurityError / Access Denied
        const throwingMethodsStore = EphemeralDraftStore.createStore({
            storage: {
                getItem() {
                    const err = new Error('SecurityError: Access is denied for this document');
                    err.name = 'SecurityError';
                    throw err;
                },
                setItem() {
                    const err = new Error('SecurityError: Access is denied for this document');
                    err.name = 'SecurityError';
                    throw err;
                },
                removeItem() {
                    const err = new Error('SecurityError: Access is denied for this document');
                    err.name = 'SecurityError';
                    throw err;
                }
            }
        });

        assert.strictEqual(throwingMethodsStore.save('key2', 'val2'), false);
        assert.strictEqual(throwingMethodsStore.load('key2'), null);
        assert.doesNotThrow(() => throwingMethodsStore.remove('key2'));
    });

    test('8. default singleton works safely when sessionStorage is unavailable', () => {
        assert.doesNotThrow(() => {
            EphemeralDraftStore.save('key', 'val');
            EphemeralDraftStore.load('key');
            EphemeralDraftStore.remove('key');
        });
    });
});
