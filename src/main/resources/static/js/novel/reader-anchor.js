/**
 * KiemLai Universe — Novel Reader Selection / Anchor Coordinate Contract (MS-05E1C)
 *
 * Responsibilities:
 * - Convert a canonical Reader semantic block into a deterministic BLOCK anchor.
 * - Convert a non-collapsed browser Selection/Range within a single canonical block into a TEXT_RANGE anchor.
 * - Extract authoritative chapterId and contentVersion from current DOM (.novel-reader-chapter-body).
 * - Extract authoritative blockKey from canonical semantic block (data-reader-block-key).
 * - Calculate UTF-16 code-unit offsets relative to the flattened canonical rendered text of the block.
 * - Capture bounded contextBefore and contextAfter (up to 64 UTF-16 code units).
 * - Strictly reject cross-block selections, collapsed selections, and selections outside canonical blocks.
 * - Never generate chapterId, contentVersion, or blockKey.
 * - Never depend on narration segment IDs.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderAnchor = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderAnchor = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Anchor kinds supported by Novel Reader inline anchors.
     */
    const ANCHOR_KIND = Object.freeze({
        BLOCK: 'BLOCK',
        TEXT_RANGE: 'TEXT_RANGE'
    });

    /**
     * Maximum UTF-16 code units captured for surrounding context.
     */
    const CONTEXT_WINDOW_SIZE = 64;

    /**
     * DOM selectors and attributes.
     */
    const READER_BODY_SELECTOR = '.novel-reader-chapter-body';
    const BLOCK_KEY_ATTR = 'data-reader-block-key';

    /**
     * Resolves the current Reader body element from options, node ancestor, or document.
     * Always resolves dynamically to ensure freshness across seamless chapter transitions.
     *
     * @param {Node|null} contextNode
     * @param {Object} [options]
     * @returns {Element|null}
     */
    function resolveReaderBody(contextNode, options) {
        if (options && options.readerBody) {
            return options.readerBody;
        }
        if (contextNode) {
            if (contextNode.nodeType === 1 && typeof contextNode.closest === 'function') {
                const closestBody = contextNode.closest(READER_BODY_SELECTOR);
                if (closestBody) {
                    return closestBody;
                }
            } else if (contextNode.nodeType === 3) {
                const parent = contextNode.parentElement || contextNode.parentNode;
                if (parent && typeof parent.closest === 'function') {
                    const closestBody = parent.closest(READER_BODY_SELECTOR);
                    if (closestBody) {
                        return closestBody;
                    }
                }
            }
        }
        const doc = (options && options.document) || (typeof document !== 'undefined' ? document : null);
        if (doc && typeof doc.querySelector === 'function') {
            return doc.querySelector(READER_BODY_SELECTOR);
        }
        return null;
    }

    /**
     * Extracts and validates authoritative chapter metadata from current Reader body.
     * Fail fast with descriptive errors; never fallback or generate values.
     *
     * @param {Element|null} bodyEl
     * @returns {{ chapterId: string, contentVersion: number }}
     */
    function extractChapterMetadata(bodyEl) {
        if (!bodyEl) {
            throw new Error('Reader body element (' + READER_BODY_SELECTOR + ') not found');
        }

        const chapterId = (
            (typeof bodyEl.getAttribute === 'function' ? bodyEl.getAttribute('data-chapter-id') : null) ||
            (bodyEl.dataset && bodyEl.dataset.chapterId) ||
            ''
        ).trim();

        if (!chapterId) {
            throw new Error('Missing or blank data-chapter-id on chapter body');
        }

        const rawContentVersion = (
            (typeof bodyEl.getAttribute === 'function' ? bodyEl.getAttribute('data-content-version') : null) ||
            (bodyEl.dataset && bodyEl.dataset.contentVersion) ||
            ''
        ).toString().trim();

        const contentVersion = Number(rawContentVersion);
        if (!rawContentVersion || !Number.isInteger(contentVersion) || contentVersion <= 0) {
            throw new Error('Missing or invalid data-content-version on chapter body: ' + rawContentVersion);
        }

        return { chapterId, contentVersion };
    }

    /**
     * Finds the enclosing canonical semantic Reader block for a given DOM node.
     *
     * @param {Node|null} node
     * @param {Element|null} readerBody
     * @returns {Element|null}
     */
    function findCanonicalBlock(node, readerBody) {
        if (!node) {
            return null;
        }
        let current = node.nodeType === 3 ? (node.parentElement || node.parentNode) : node;
        while (current && current !== readerBody) {
            if (current.nodeType === 1) {
                const hasKey = typeof current.hasAttribute === 'function'
                    ? current.hasAttribute(BLOCK_KEY_ATTR)
                    : Boolean(current.getAttribute && current.getAttribute(BLOCK_KEY_ATTR));
                if (hasKey) {
                    const key = (current.getAttribute(BLOCK_KEY_ATTR) || '').trim();
                    if (key) {
                        if (!readerBody || (typeof readerBody.contains === 'function' && readerBody.contains(current))) {
                            return current;
                        }
                        return null;
                    }
                }
            }
            current = current.parentElement || current.parentNode;
        }
        return null;
    }

    /**
     * Extracts and validates authoritative blockKey from canonical block element.
     *
     * @param {Element} blockEl
     * @returns {string}
     */
    function extractBlockKey(blockEl) {
        if (!blockEl || blockEl.nodeType !== 1) {
            throw new Error('Canonical block element not found or invalid');
        }
        const blockKey = (
            (typeof blockEl.getAttribute === 'function' ? blockEl.getAttribute(BLOCK_KEY_ATTR) : null) ||
            (blockEl.dataset && blockEl.dataset.readerBlockKey) ||
            ''
        ).trim();

        if (!blockKey) {
            throw new Error('Missing or blank ' + BLOCK_KEY_ATTR + ' on canonical block');
        }
        return blockKey;
    }

    /**
     * Returns the canonical rendered text of a block by concatenating all descendant Text nodes.
     *
     * @param {Element} blockEl
     * @returns {string}
     */
    function getCanonicalBlockText(blockEl) {
        if (!blockEl) {
            return '';
        }
        if (typeof blockEl.textContent === 'string') {
            return blockEl.textContent;
        }
        let text = '';
        function walk(node) {
            if (node.nodeType === 3) {
                text += node.nodeValue || node.data || '';
            } else if (node.childNodes) {
                for (let i = 0; i < node.childNodes.length; i++) {
                    walk(node.childNodes[i]);
                }
            }
        }
        walk(blockEl);
        return text;
    }

    /**
     * Calculates the UTF-16 code-unit offset of a boundary point (container, offset)
     * relative to the block's flattened textContent.
     *
     * Validates that boundary points are within legal ranges:
     * - Text node: integer offset, 0 <= offset <= nodeValue.length
     * - Element: integer offset, 0 <= offset <= childNodes.length
     *
     * @param {Element} blockEl
     * @param {Node} container
     * @param {number} offset
     * @returns {number} code-unit offset, or -1 if invalid/not found
     */
    function calculateCodeUnitOffset(blockEl, container, offset) {
        if (!blockEl || !container) {
            return -1;
        }

        if (!Number.isInteger(offset) || offset < 0) {
            return -1;
        }

        let targetNode = null;
        let targetIsAfterChild = false;

        if (container.nodeType === 1) { // ELEMENT_NODE
            const childNodes = container.childNodes || [];
            if (offset > childNodes.length) {
                return -1;
            }
            if (offset < childNodes.length) {
                targetNode = childNodes[offset];
            } else {
                targetIsAfterChild = true;
            }
        } else if (container.nodeType === 3) { // TEXT_NODE
            const textVal = container.nodeValue || container.data || '';
            if (offset > textVal.length) {
                return -1;
            }
        } else {
            // Unsupported container type
            return -1;
        }

        let charCount = 0;
        let found = false;

        function walk(node) {
            if (found) return;

            // Element boundary point before target child
            if (container.nodeType === 1 && node === targetNode) {
                found = true;
                return;
            }

            // Text node boundary point
            if (container.nodeType === 3 && node === container) {
                charCount += offset;
                found = true;
                return;
            }

            if (node.nodeType === 3) {
                const val = node.nodeValue || node.data || '';
                charCount += val.length;
            } else if (node.childNodes) {
                for (let i = 0; i < node.childNodes.length; i++) {
                    walk(node.childNodes[i]);
                    if (found) return;
                }
                // Element boundary point after all children
                if (container.nodeType === 1 && node === container && targetIsAfterChild) {
                    found = true;
                    return;
                }
            }
        }

        walk(blockEl);
        return found ? charCount : -1;
    }

    /**
     * Extracts the authoritative Range from a browser Selection or DOM Range input.
     *
     * @param {Selection|Range} selectionOrRange
     * @returns {Range}
     * @private
     */
    function extractRangeFromInput(selectionOrRange) {
        if (!selectionOrRange) {
            throw new Error('Missing selection or range for text range anchor');
        }

        // Browser Selection (has getRangeAt and rangeCount)
        if (typeof selectionOrRange.getRangeAt === 'function' && typeof selectionOrRange.rangeCount === 'number') {
            if (selectionOrRange.rangeCount === 0 || selectionOrRange.isCollapsed) {
                throw new Error('Cannot create text range anchor from collapsed or empty selection');
            }
            const range = selectionOrRange.getRangeAt(0);
            if (!range || range.collapsed) {
                throw new Error('Cannot create text range anchor from collapsed or missing range');
            }
            return range;
        }

        // Direct DOM Range (has startContainer and endContainer)
        if (selectionOrRange.startContainer && selectionOrRange.endContainer) {
            if (selectionOrRange.collapsed) {
                throw new Error('Cannot create text range anchor from collapsed range');
            }
            return selectionOrRange;
        }

        throw new Error('Invalid selection or range input for text range anchor');
    }

    /**
     * Creates a BLOCK anchor from one canonical Reader block.
     *
     * @param {Element} blockElement - Canonical block element or an element inside it
     * @param {Object} [options]
     * @param {Element} [options.readerBody] - Optional explicit reader body
     * @param {Document} [options.document] - Optional explicit document
     * @returns {Readonly<{ chapterId: string, contentVersion: number, blockKey: string, anchorKind: string, startOffset: null, endOffset: null, selectedText: string, contextBefore: string, contextAfter: string }>}
     */
    function createBlockAnchor(blockElement, options = {}) {
        if (!blockElement) {
            throw new Error('Missing block element for block anchor');
        }

        const readerBody = resolveReaderBody(blockElement, options);
        const blockEl = findCanonicalBlock(blockElement, readerBody);
        if (!blockEl) {
            throw new Error('Canonical block element not found or missing ' + BLOCK_KEY_ATTR);
        }

        const { chapterId, contentVersion } = extractChapterMetadata(readerBody);
        const blockKey = extractBlockKey(blockEl);
        const canonicalBlockText = getCanonicalBlockText(blockEl);

        return Object.freeze({
            chapterId,
            contentVersion,
            blockKey,
            anchorKind: ANCHOR_KIND.BLOCK,
            startOffset: null,
            endOffset: null,
            selectedText: canonicalBlockText,
            contextBefore: '',
            contextAfter: ''
        });
    }

    /**
     * Creates a TEXT_RANGE anchor from a browser Selection or DOM Range.
     * Consumes the authoritative Range boundaries directly without reconstructing from anchor/focus.
     *
     * @param {Selection|Range} selectionOrRange
     * @param {Object} [options]
     * @param {Element} [options.readerBody] - Optional explicit reader body
     * @param {Document} [options.document] - Optional explicit document
     * @returns {Readonly<{ chapterId: string, contentVersion: number, blockKey: string, anchorKind: string, startOffset: number, endOffset: number, selectedText: string, contextBefore: string, contextAfter: string }>}
     */
    function createTextRangeAnchor(selectionOrRange, options = {}) {
        const range = extractRangeFromInput(selectionOrRange);

        const startContainer = range.startContainer;
        const startOffsetInContainer = range.startOffset;
        const endContainer = range.endContainer;
        const endOffsetInContainer = range.endOffset;

        const readerBody = resolveReaderBody(startContainer, options);
        const startBlock = findCanonicalBlock(startContainer, readerBody);
        const endBlock = findCanonicalBlock(endContainer, readerBody);

        if (!startBlock || !endBlock) {
            throw new Error('Selection is outside canonical Reader block');
        }

        if (startBlock !== endBlock) {
            throw new Error('Cross-block selection is rejected; selection must be entirely inside one canonical block');
        }

        const { chapterId, contentVersion } = extractChapterMetadata(readerBody);
        const blockKey = extractBlockKey(startBlock);
        const canonicalBlockText = getCanonicalBlockText(startBlock);

        const startOffset = calculateCodeUnitOffset(startBlock, startContainer, startOffsetInContainer);
        const endOffset = calculateCodeUnitOffset(startBlock, endContainer, endOffsetInContainer);

        if (startOffset < 0 || endOffset < 0) {
            throw new Error('Failed to resolve selection offsets within canonical block');
        }

        // Strictly require: 0 <= startOffset < endOffset <= canonicalBlockText.length
        if (startOffset < 0 || endOffset > canonicalBlockText.length || startOffset >= endOffset) {
            throw new Error(
                'Inconsistent or impossible text range offsets: startOffset=' +
                startOffset + ', endOffset=' + endOffset + ', blockTextLength=' + canonicalBlockText.length
            );
        }

        const selectedText = canonicalBlockText.slice(startOffset, endOffset);
        const contextBefore = canonicalBlockText.slice(Math.max(0, startOffset - CONTEXT_WINDOW_SIZE), startOffset);
        const contextAfter = canonicalBlockText.slice(endOffset, Math.min(canonicalBlockText.length, endOffset + CONTEXT_WINDOW_SIZE));

        return Object.freeze({
            chapterId,
            contentVersion,
            blockKey,
            anchorKind: ANCHOR_KIND.TEXT_RANGE,
            startOffset,
            endOffset,
            selectedText,
            contextBefore,
            contextAfter
        });
    }

    /**
     * Convenience entry point: converts a canonical block element or Selection/Range into anchor evidence.
     *
     * @param {Element|Selection|Range} target
     * @param {Object} [options]
     * @returns {Object}
     */
    function createAnchor(target, options) {
        if (!target) {
            throw new Error('Target is required to create anchor');
        }
        if (target.nodeType === 1) {
            return createBlockAnchor(target, options);
        }
        return createTextRangeAnchor(target, options);
    }

    return {
        ANCHOR_KIND,
        CONTEXT_WINDOW_SIZE,
        createBlockAnchor,
        createTextRangeAnchor,
        createAnchor,
        findCanonicalBlock,
        getCanonicalBlockText,
        calculateCodeUnitOffset
    };
});
