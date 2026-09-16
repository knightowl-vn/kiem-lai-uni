const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const {
    ANCHOR_KIND,
    CONTEXT_WINDOW_SIZE,
    createBlockAnchor,
    createTextRangeAnchor,
    createAnchor,
    findCanonicalBlock,
    getCanonicalBlockText,
    calculateCodeUnitOffset
} = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-anchor.js'));

// ============================================================================
// Lightweight DOM Test Fixture Helpers
// ============================================================================

class FakeNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
        this.parentElement = null;
    }

    compareDocumentPosition(other) {
        if (this === other) return 0;
        let root = this;
        while (root.parentElement || root.parentNode) {
            root = root.parentElement || root.parentNode;
        }
        const order = [];
        function flatten(n) {
            order.push(n);
            if (n.childNodes) {
                for (const c of n.childNodes) flatten(c);
            }
        }
        flatten(root);
        const thisIdx = order.indexOf(this);
        const otherIdx = order.indexOf(other);
        if (thisIdx === -1 || otherIdx === -1) return 1; // disconnected
        if (thisIdx < otherIdx) return 4; // DOCUMENT_POSITION_FOLLOWING
        return 2; // DOCUMENT_POSITION_PRECEDING
    }
}

class FakeTextNode extends FakeNode {
    constructor(text) {
        super(3);
        this.nodeValue = text;
        this.data = text;
    }

    get length() {
        return this.nodeValue.length;
    }
}

class FakeElement extends FakeNode {
    constructor(tagName, attributes = {}) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        for (const [k, v] of Object.entries(attributes)) {
            this.attributes[k] = String(v);
        }
        this.childNodes = [];
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
    }

    removeAttribute(name) {
        delete this.attributes[name];
    }

    closest(selector) {
        let cur = this;
        while (cur) {
            if (cur._matchesSelector && cur._matchesSelector(selector)) {
                return cur;
            }
            cur = cur.parentElement;
        }
        return null;
    }

    contains(node) {
        let cur = node;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    _matchesSelector(selector) {
        if (selector === '.novel-reader-chapter-body') {
            return (this.attributes['class'] || '').split(/\s+/).includes('novel-reader-chapter-body');
        }
        if (selector === '[data-reader-block-key]') {
            return this.hasAttribute('data-reader-block-key');
        }
        return false;
    }

    querySelector(selector) {
        if (this._matchesSelector(selector)) return this;
        for (const child of this.childNodes) {
            if (child.nodeType === 1) {
                const found = child.querySelector(selector);
                if (found) return found;
            }
        }
        return null;
    }

    get textContent() {
        let str = '';
        for (const child of this.childNodes) {
            if (child.nodeType === 3) {
                str += child.nodeValue;
            } else if (child.nodeType === 1) {
                str += child.textContent;
            }
        }
        return str;
    }
}

class FakeRange {
    constructor(startContainer, startOffset, endContainer, endOffset) {
        this.startContainer = startContainer;
        this.startOffset = startOffset;
        this.endContainer = endContainer;
        this.endOffset = endOffset;
    }

    get collapsed() {
        return this.startContainer === this.endContainer && this.startOffset === this.endOffset;
    }
}

class FakeSelection {
    constructor(anchorNode, anchorOffset, focusNode, focusOffset, range = null) {
        this.anchorNode = anchorNode;
        this.anchorOffset = anchorOffset;
        this.focusNode = focusNode;
        this.focusOffset = focusOffset;
        // In real browsers, selection.getRangeAt(0) always returns a normalized Range in document order
        if (range) {
            this._range = range;
        } else {
            let start = anchorOffset;
            let end = focusOffset;
            if (anchorNode === focusNode && anchorOffset > focusOffset) {
                start = focusOffset;
                end = anchorOffset;
            }
            this._range = new FakeRange(anchorNode, start, focusNode, end);
        }
    }

    get isCollapsed() {
        return this.anchorNode === this.focusNode && this.anchorOffset === this.focusOffset;
    }

    get rangeCount() {
        return 1;
    }

    getRangeAt(index) {
        if (index === 0) return this._range;
        throw new Error('IndexSizeError');
    }
}

function createChapterBody(chapterId = 'chap-101', contentVersion = 3) {
    const body = new FakeElement('article', {
        class: 'novel-reader-chapter-body',
        'data-chapter-id': chapterId,
        'data-content-version': contentVersion
    });
    return body;
}

// ============================================================================
// Test Suite
// ============================================================================

describe('MS-05E1C Novel Reader Selection / Anchor Coordinate Contract', () => {

    test('1. Valid BLOCK anchor extraction from canonical block', () => {
        const body = createChapterBody('chap-101', 5);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-1a2b3c4d-1' });
        p.appendChild(new FakeTextNode('Đoạn văn mở đầu câu chuyện.'));
        body.appendChild(p);

        const anchor = createBlockAnchor(p, { readerBody: body });

        assert.strictEqual(anchor.chapterId, 'chap-101');
        assert.strictEqual(anchor.contentVersion, 5);
        assert.strictEqual(anchor.blockKey, 'blk-1a2b3c4d-1');
        assert.strictEqual(anchor.anchorKind, ANCHOR_KIND.BLOCK);
        assert.strictEqual(anchor.startOffset, null);
        assert.strictEqual(anchor.endOffset, null);
        assert.strictEqual(anchor.selectedText, 'Đoạn văn mở đầu câu chuyện.');
        assert.strictEqual(anchor.contextBefore, '');
        assert.strictEqual(anchor.contextAfter, '');
    });

    test('2. Simple TEXT_RANGE anchor inside single text node', () => {
        const body = createChapterBody('chap-101', 2);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-para-1' });
        const text = new FakeTextNode('Trần Bình An cất bước lên đường.');
        p.appendChild(text);
        body.appendChild(p);

        // Select "Bình An" (indices 5 to 12)
        const range = new FakeRange(text, 5, text, 12);
        const anchor = createTextRangeAnchor(range, { readerBody: body });

        assert.strictEqual(anchor.chapterId, 'chap-101');
        assert.strictEqual(anchor.contentVersion, 2);
        assert.strictEqual(anchor.blockKey, 'blk-para-1');
        assert.strictEqual(anchor.anchorKind, ANCHOR_KIND.TEXT_RANGE);
        assert.strictEqual(anchor.startOffset, 5);
        assert.strictEqual(anchor.endOffset, 12);
        assert.strictEqual(anchor.selectedText, 'Bình An');
        assert.strictEqual(anchor.contextBefore, 'Trần ');
        assert.strictEqual(anchor.contextAfter, ' cất bước lên đường.');

        // Invariant: selectedText === canonicalBlockText.slice(startOffset, endOffset)
        const blockText = getCanonicalBlockText(p);
        assert.strictEqual(anchor.selectedText, blockText.slice(anchor.startOffset, anchor.endOffset));
    });

    test('3. Range crossing inline Text nodes inside one block', () => {
        const body = createChapterBody('chap-202', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-inline-1' });

        const t1 = new FakeTextNode('Chào ');
        const strong = new FakeElement('strong');
        const t2 = new FakeTextNode('thế giới');
        strong.appendChild(t2);
        const t3 = new FakeTextNode(' tươi đẹp!');

        p.appendChild(t1);
        p.appendChild(strong);
        p.appendChild(t3);
        body.appendChild(p);

        // Canonical text: "Chào thế giới tươi đẹp!" (length 23)
        // t1: [0..5), t2 (inside strong): [5..13), t3: [13..23)
        // Select from t1 offset 2 ("ào ") through t2 offset 3 ("thế")
        const range = new FakeRange(t1, 2, t2, 3);
        const anchor = createTextRangeAnchor(range, { readerBody: body });

        assert.strictEqual(anchor.chapterId, 'chap-202');
        assert.strictEqual(anchor.contentVersion, 1);
        assert.strictEqual(anchor.blockKey, 'blk-inline-1');
        assert.strictEqual(anchor.anchorKind, ANCHOR_KIND.TEXT_RANGE);
        assert.strictEqual(anchor.startOffset, 2);
        assert.strictEqual(anchor.endOffset, 8);
        assert.strictEqual(anchor.selectedText, 'ào thế');
        assert.strictEqual(anchor.contextBefore, 'Ch');
        assert.strictEqual(anchor.contextAfter, ' giới tươi đẹp!');

        const blockText = getCanonicalBlockText(p);
        assert.strictEqual(anchor.selectedText, blockText.slice(anchor.startOffset, anchor.endOffset));
    });

    test('4. Equivalent forward and backward selection', () => {
        const body = createChapterBody('chap-101', 3);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-dir-1' });
        const text = new FakeTextNode('Kiếm Lai Thần Đạo Khởi');
        p.appendChild(text);
        body.appendChild(p);

        // Forward selection: anchor at 5, focus at 13 ("Lai Thần"), Range start 5 end 13
        const forwardSel = new FakeSelection(text, 5, text, 13, new FakeRange(text, 5, text, 13));
        // Backward selection: anchor at 13, focus at 5 ("Lai Thần"), Range start 5 end 13 (normalized by browser)
        const backwardSel = new FakeSelection(text, 13, text, 5, new FakeRange(text, 5, text, 13));

        const anchorFwd = createTextRangeAnchor(forwardSel, { readerBody: body });
        const anchorBwd = createTextRangeAnchor(backwardSel, { readerBody: body });

        assert.strictEqual(anchorFwd.startOffset, 5);
        assert.strictEqual(anchorFwd.endOffset, 13);
        assert.strictEqual(anchorFwd.selectedText, 'Lai Thần');

        assert.strictEqual(anchorBwd.startOffset, 5);
        assert.strictEqual(anchorBwd.endOffset, 13);
        assert.strictEqual(anchorBwd.selectedText, 'Lai Thần');

        assert.deepStrictEqual(anchorFwd, anchorBwd);
    });

    test('5. Cross-block selection rejection', () => {
        const body = createChapterBody('chap-101', 1);
        const p1 = new FakeElement('p', { 'data-reader-block-key': 'blk-01' });
        const t1 = new FakeTextNode('Đoạn một kết thúc ở đây.');
        p1.appendChild(t1);

        const p2 = new FakeElement('p', { 'data-reader-block-key': 'blk-02' });
        const t2 = new FakeTextNode('Đoạn hai bắt đầu từ đây.');
        p2.appendChild(t2);

        body.appendChild(p1);
        body.appendChild(p2);

        // Selection starts in p1 and ends in p2
        const crossRange = new FakeRange(t1, 5, t2, 8);

        assert.throws(
            () => createTextRangeAnchor(crossRange, { readerBody: body }),
            /Cross-block selection is rejected/
        );
    });

    test('6. Collapsed selection rejection', () => {
        const body = createChapterBody('chap-101', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-01' });
        const text = new FakeTextNode('Nội dung');
        p.appendChild(text);
        body.appendChild(p);

        const collapsedRange = new FakeRange(text, 3, text, 3);
        const collapsedSel = new FakeSelection(text, 3, text, 3, collapsedRange);

        assert.throws(
            () => createTextRangeAnchor(collapsedSel, { readerBody: body }),
            /collapsed/
        );
        assert.throws(
            () => createTextRangeAnchor(collapsedRange, { readerBody: body }),
            /collapsed/
        );
    });

    test('7. Outside-block selection rejection', () => {
        const body = createChapterBody('chap-101', 1);
        // An element inside body but without data-reader-block-key (or outside body)
        const header = new FakeElement('header', { class: 'novel-chapter-header' });
        const title = new FakeTextNode('Tiêu đề ngoài canonical block');
        header.appendChild(title);

        const range = new FakeRange(title, 0, title, 7);

        assert.throws(
            () => createTextRangeAnchor(range, { readerBody: body }),
            /outside canonical Reader block/
        );
    });

    test('8. chapterId and contentVersion validation with no fallback', () => {
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-val' });
        p.appendChild(new FakeTextNode('Văn bản'));

        // 8a. Missing chapter body
        assert.throws(
            () => createBlockAnchor(p, { readerBody: null, document: { querySelector: () => null } }),
            /Reader body element/
        );

        // 8b. Missing / blank data-chapter-id
        const bodyNoChap = new FakeElement('article', {
            class: 'novel-reader-chapter-body',
            'data-content-version': '1'
        });
        bodyNoChap.appendChild(p);
        assert.throws(
            () => createBlockAnchor(p, { readerBody: bodyNoChap }),
            /Missing or blank data-chapter-id/
        );

        const bodyBlankChap = new FakeElement('article', {
            class: 'novel-reader-chapter-body',
            'data-chapter-id': '   ',
            'data-content-version': '1'
        });
        bodyBlankChap.appendChild(p);
        assert.throws(
            () => createBlockAnchor(p, { readerBody: bodyBlankChap }),
            /Missing or blank data-chapter-id/
        );

        // 8c. Missing / invalid data-content-version
        const bodyNoVer = new FakeElement('article', {
            class: 'novel-reader-chapter-body',
            'data-chapter-id': 'c-1'
        });
        bodyNoVer.appendChild(p);
        assert.throws(
            () => createBlockAnchor(p, { readerBody: bodyNoVer }),
            /Missing or invalid data-content-version/
        );

        const invalidVersions = ['0', '-1', '1.5', 'NaN', 'abc', ''];
        for (const badVer of invalidVersions) {
            const bodyBadVer = new FakeElement('article', {
                class: 'novel-reader-chapter-body',
                'data-chapter-id': 'c-1',
                'data-content-version': badVer
            });
            bodyBadVer.appendChild(p);
            assert.throws(
                () => createBlockAnchor(p, { readerBody: bodyBadVer }),
                /Missing or invalid data-content-version/,
                `Should throw for invalid contentVersion: "${badVer}"`
            );
        }
    });

    test('9. blockKey validation', () => {
        const body = createChapterBody('chap-101', 1);

        // Missing blockKey attribute
        const pNoKey = new FakeElement('p');
        pNoKey.appendChild(new FakeTextNode('Không có key'));
        body.appendChild(pNoKey);

        assert.throws(
            () => createBlockAnchor(pNoKey, { readerBody: body }),
            /Canonical block element not found or missing/
        );

        // Blank blockKey attribute
        const pBlankKey = new FakeElement('p', { 'data-reader-block-key': '   ' });
        pBlankKey.appendChild(new FakeTextNode('Key rỗng'));
        body.appendChild(pBlankKey);

        assert.throws(
            () => createBlockAnchor(pBlankKey, { readerBody: body }),
            /Canonical block element not found or missing/
        );
    });

    test('10. Emoji / supplementary character UTF-16 code-unit offsets', () => {
        const body = createChapterBody('chap-101', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-emoji' });
        // '🐉' is surrogate pair \uD83D\uDC09 (length 2 in JS string UTF-16 code units)
        // "Thần thú 🐉 xuất hiện!"
        // "Thần thú " -> 9 code units (indices 0..8)
        // "🐉" -> 2 code units (indices 9..10)
        // " xuất hiện!" -> 11 code units (indices 11..21)
        const text = new FakeTextNode('Thần thú 🐉 xuất hiện!');
        p.appendChild(text);
        body.appendChild(p);

        // Select the dragon emoji
        const emojiRange = new FakeRange(text, 9, text, 11);
        const emojiAnchor = createTextRangeAnchor(emojiRange, { readerBody: body });

        assert.strictEqual(emojiAnchor.startOffset, 9);
        assert.strictEqual(emojiAnchor.endOffset, 11);
        assert.strictEqual(emojiAnchor.selectedText, '🐉');
        assert.strictEqual(emojiAnchor.contextBefore, 'Thần thú ');
        assert.strictEqual(emojiAnchor.contextAfter, ' xuất hiện!');

        // Select text immediately after emoji: " xuất" (indices 11..16)
        const afterRange = new FakeRange(text, 11, text, 16);
        const afterAnchor = createTextRangeAnchor(afterRange, { readerBody: body });

        assert.strictEqual(afterAnchor.startOffset, 11);
        assert.strictEqual(afterAnchor.endOffset, 16);
        assert.strictEqual(afterAnchor.selectedText, ' xuất');
        assert.strictEqual(afterAnchor.contextBefore, 'Thần thú 🐉');
        assert.strictEqual(afterAnchor.contextAfter, ' hiện!');
    });

    test('11. 64-unit context boundaries', () => {
        const body = createChapterBody('chap-101', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-ctx' });

        // 100 'A's + "TARGET" + 100 'B's
        const prefix = 'A'.repeat(100);
        const target = 'TARGET';
        const suffix = 'B'.repeat(100);
        const fullText = prefix + target + suffix;

        const text = new FakeTextNode(fullText);
        p.appendChild(text);
        body.appendChild(p);

        // Select "TARGET" (start: 100, end: 106)
        const range = new FakeRange(text, 100, text, 106);
        const anchor = createTextRangeAnchor(range, { readerBody: body });

        assert.strictEqual(anchor.startOffset, 100);
        assert.strictEqual(anchor.endOffset, 106);
        assert.strictEqual(anchor.selectedText, 'TARGET');

        // Bounded to exactly 64 code units
        assert.strictEqual(anchor.contextBefore.length, CONTEXT_WINDOW_SIZE);
        assert.strictEqual(anchor.contextBefore, 'A'.repeat(64));

        assert.strictEqual(anchor.contextAfter.length, CONTEXT_WINDOW_SIZE);
        assert.strictEqual(anchor.contextAfter, 'B'.repeat(64));

        // Short prefix/suffix test: only 5 characters before, 8 after
        const shortText = new FakeTextNode('12345TARGETabcdefgh');
        const pShort = new FakeElement('p', { 'data-reader-block-key': 'blk-ctx-short' });
        pShort.appendChild(shortText);
        body.appendChild(pShort);

        const shortRange = new FakeRange(shortText, 5, shortText, 11);
        const shortAnchor = createTextRangeAnchor(shortRange, { readerBody: body });

        assert.strictEqual(shortAnchor.selectedText, 'TARGET');
        assert.strictEqual(shortAnchor.contextBefore, '12345');
        assert.strictEqual(shortAnchor.contextAfter, 'abcdefgh');
    });

    test('12. No dependency on data-narration-segment-ids', () => {
        const body = createChapterBody('chap-101', 1);

        // Block with narration segment IDs
        const pWithNarration = new FakeElement('p', {
            'data-reader-block-key': 'blk-common-1',
            'data-narration-segment-ids': '00000000-0000-0000-0000-000000000001 00000000-0000-0000-0000-000000000002'
        });
        const t1 = new FakeTextNode('Đoạn văn có âm thanh');
        pWithNarration.appendChild(t1);

        // Block without narration segment IDs
        const pNoNarration = new FakeElement('p', {
            'data-reader-block-key': 'blk-common-1'
        });
        const t2 = new FakeTextNode('Đoạn văn có âm thanh');
        pNoNarration.appendChild(t2);

        body.appendChild(pWithNarration);
        body.appendChild(pNoNarration);

        // Block anchors
        const blockAnchorWith = createBlockAnchor(pWithNarration, { readerBody: body });
        const blockAnchorWithout = createBlockAnchor(pNoNarration, { readerBody: body });

        assert.deepStrictEqual(blockAnchorWith, blockAnchorWithout);

        // Text range anchors
        const range1 = new FakeRange(t1, 0, t1, 8);
        const range2 = new FakeRange(t2, 0, t2, 8);

        const rangeAnchorWith = createTextRangeAnchor(range1, { readerBody: body });
        const rangeAnchorWithout = createTextRangeAnchor(range2, { readerBody: body });

        assert.deepStrictEqual(rangeAnchorWith, rangeAnchorWithout);
        assert.strictEqual(rangeAnchorWith.selectedText, 'Đoạn văn');
    });

    test('13. createAnchor unified dispatcher works for both element and selection/range', () => {
        const body = createChapterBody('chap-303', 4);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-disp' });
        const t = new FakeTextNode('Thử nghiệm hàm điều phối chung.');
        p.appendChild(t);
        body.appendChild(p);

        // Dispatch with Element -> BLOCK anchor
        const blockAnchor = createAnchor(p, { readerBody: body });
        assert.strictEqual(blockAnchor.anchorKind, ANCHOR_KIND.BLOCK);
        assert.strictEqual(blockAnchor.blockKey, 'blk-disp');

        // Dispatch with Range -> TEXT_RANGE anchor
        const range = new FakeRange(t, 0, t, 10);
        const textAnchor = createAnchor(range, { readerBody: body });
        assert.strictEqual(textAnchor.anchorKind, ANCHOR_KIND.TEXT_RANGE);
        assert.strictEqual(textAnchor.selectedText, 'Thử nghiệm');
    });

    test('14. Element-container boundary points across inline markup', () => {
        const body = createChapterBody('chap-101', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-elem-1' });

        const t1 = new FakeTextNode('AA');
        const strong = new FakeElement('strong');
        const t2 = new FakeTextNode('BBB');
        strong.appendChild(t2);
        const t3 = new FakeTextNode('CC');

        p.appendChild(t1);     // child 0: "AA" [0..2)
        p.appendChild(strong); // child 1: "BBB" [2..5)
        p.appendChild(t3);     // child 2: "CC" [5..7)
        body.appendChild(p);

        // Flattened block text: "AABBBCC" (length 7)
        const blockText = getCanonicalBlockText(p);
        assert.strictEqual(blockText, 'AABBBCC');

        // Case A: startContainer = p, startOffset = 1 (before <strong>, at index 2)
        //         endContainer = p, endOffset = 2 (before t3, at index 5)
        // Selects <strong>"BBB"</strong>
        const rangeA = new FakeRange(p, 1, p, 2);
        const anchorA = createTextRangeAnchor(rangeA, { readerBody: body });
        assert.strictEqual(anchorA.startOffset, 2);
        assert.strictEqual(anchorA.endOffset, 5);
        assert.strictEqual(anchorA.selectedText, 'BBB');
        assert.strictEqual(anchorA.selectedText, blockText.slice(2, 5));

        // Case B: startContainer = p, startOffset = 0 (before child 0, at index 0)
        //         endContainer = strong, endOffset = 1 (after its 1 child t2, at index 5)
        // Selects "AABBB"
        const rangeB = new FakeRange(p, 0, strong, 1);
        const anchorB = createTextRangeAnchor(rangeB, { readerBody: body });
        assert.strictEqual(anchorB.startOffset, 0);
        assert.strictEqual(anchorB.endOffset, 5);
        assert.strictEqual(anchorB.selectedText, 'AABBB');
        assert.strictEqual(anchorB.selectedText, blockText.slice(0, 5));

        // Case C: startContainer = p, startOffset = 1 (at index 2)
        //         endContainer = t3 (Text node), endOffset = 1 (at index 6)
        // Selects "BBBC"
        const rangeC = new FakeRange(p, 1, t3, 1);
        const anchorC = createTextRangeAnchor(rangeC, { readerBody: body });
        assert.strictEqual(anchorC.startOffset, 2);
        assert.strictEqual(anchorC.endOffset, 6);
        assert.strictEqual(anchorC.selectedText, 'BBBC');
        assert.strictEqual(anchorC.selectedText, blockText.slice(2, 6));
    });

    test('15. Rejection of invalid boundary points (not clamped/coerced)', () => {
        const body = createChapterBody('chap-101', 1);
        const p = new FakeElement('p', { 'data-reader-block-key': 'blk-invalid' });

        const t1 = new FakeTextNode('Hello'); // length 5
        const t2 = new FakeTextNode(' World'); // length 6
        p.appendChild(t1); // child 0
        p.appendChild(t2); // child 1
        body.appendChild(p);

        // Total block text: "Hello World" (length 11)

        // A. Text node offset larger than node UTF-16 length (7 > 5),
        // even though 7 < total block text length (11)
        const rangeOversizedText = new FakeRange(t1, 7, t2, 2);
        assert.throws(
            () => createTextRangeAnchor(rangeOversizedText, { readerBody: body }),
            /Failed to resolve selection offsets/
        );

        // B. Negative Text node offset (-1)
        const rangeNegText = new FakeRange(t1, -1, t2, 2);
        assert.throws(
            () => createTextRangeAnchor(rangeNegText, { readerBody: body }),
            /Failed to resolve selection offsets/
        );

        // C. Element offset greater than childNodes.length (3 > 2)
        const rangeOversizedElem = new FakeRange(p, 3, t2, 2);
        assert.throws(
            () => createTextRangeAnchor(rangeOversizedElem, { readerBody: body }),
            /Failed to resolve selection offsets/
        );

        // D. Negative Element offset (-1)
        const rangeNegElem = new FakeRange(p, -1, t2, 2);
        assert.throws(
            () => createTextRangeAnchor(rangeNegElem, { readerBody: body }),
            /Failed to resolve selection offsets/
        );

        // E. Non-integer offset
        const rangeFloat = new FakeRange(t1, 1.5, t2, 2);
        assert.throws(
            () => createTextRangeAnchor(rangeFloat, { readerBody: body }),
            /Failed to resolve selection offsets/
        );
    });
});
