/**
 * KiemLai Universe — Novel Reader Narration Text Parser
 *
 * Responsibilities:
 * - Extract readable narration text from rendered chapter body DOM or plain text.
 * - Preserve sequential paragraph ordering.
 * - Prevent duplicate narration extraction when structural blocks are nested (e.g. blockquote > p, li > p).
 * - Exclude non-spoken and interactivity-only DOM elements (scripts, buttons, hidden nodes, tooltips).
 * - Segment paragraph text into manageable sentence/chunk units using natural boundary detection.
 * - Provide safe length-based fallback splitting for very long run-on sentences.
 * - Associate each chunk with metadata (global index, paragraph index, DOM element reference).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NarrationTextParser = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NarrationTextParser = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Default configuration for text parsing and chunking.
     */
    const DEFAULT_OPTIONS = {
        maxChunkLength: 220,
        minChunkLength: 2,
        excludedTags: [
            'SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'BUTTON', 'INPUT',
            'SELECT', 'TEXTAREA', 'SVG', 'IFRAME', 'AUDIO', 'VIDEO', 'OBJECT'
        ],
        excludedClasses: [
            'novel-wiki-lookup-action-btn',
            'novel-wiki-lookup-backdrop',
            'novel-wiki-lookup-container',
            'novel-reading-settings-popover',
            'novel-reading-settings-trigger',
            'sr-only',
            'visually-hidden'
        ]
    };

    /**
     * Normalizes whitespace and unpronounceable typography artifacts.
     *
     * @param {string} text - Raw input text
     * @returns {string} Cleaned text
     */
    function cleanText(text) {
        if (!text || typeof text !== 'string') {
            return '';
        }

        return text
            // Unicode NFC normalization
            .normalize('NFC')
            // Replace non-breaking spaces and special spaces with standard space
            .replace(/[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]/g, ' ')
            // Remove unpronounceable repeated decoration characters (e.g. ***, ---, ===, ___)
            .replace(/[*=_~-]{2,}/g, ' ')
            // Normalize excessive ellipsis / dots to standard ellipsis
            .replace(/\.{4,}/g, '...')
            // Normalize smart quotes and brackets for uniform spacing
            .replace(/[«»“”]/g, '"')
            .replace(/[‘’]/g, "'")
            // Collapse multiple whitespace characters into a single space
            .replace(/\s+/g, ' ')
            .trim();
    }

    /**
     * Checks whether a DOM node (or any ancestor up to container) should be ignored during extraction.
     *
     * @param {Node} node
     * @param {Object} options
     * @param {HTMLElement} [container]
     * @returns {boolean} True if the node should be excluded
     */
    function isExcludedNode(node, options, container) {
        if (!node) {
            return true;
        }

        if (node.nodeType === Node.COMMENT_NODE) {
            return true;
        }

        let current = (node.nodeType === Node.ELEMENT_NODE)
            ? /** @type {HTMLElement} */ (node)
            : node.parentElement;

        while (current && current !== container && current !== document.documentElement) {
            const tagName = current.tagName ? current.tagName.toUpperCase() : '';

            if (options.excludedTags.includes(tagName)) {
                return true;
            }

            if (current.hasAttribute && (current.hasAttribute('hidden') || current.getAttribute('aria-hidden') === 'true')) {
                return true;
            }

            if (current.classList) {
                for (let i = 0; i < options.excludedClasses.length; i++) {
                    if (current.classList.contains(options.excludedClasses[i])) {
                        return true;
                    }
                }
            }

            if (current.style) {
                if (current.style.display === 'none' || current.style.visibility === 'hidden') {
                    return true;
                }
            }

            current = current.parentElement;
        }

        return false;
    }

    /**
     * Extracts pure text content from a DOM node while skipping excluded child elements.
     *
     * @param {Node} node
     * @param {Object} options
     * @param {HTMLElement} [container]
     * @returns {string}
     */
    function extractNodeText(node, options, container) {
        if (isExcludedNode(node, options, container)) {
            return '';
        }

        if (node.nodeType === Node.TEXT_NODE) {
            return node.textContent || '';
        }

        let text = '';
        const childNodes = node.childNodes;
        for (let i = 0; i < childNodes.length; i++) {
            text += extractNodeText(childNodes[i], options, container);
        }
        return text;
    }

    /**
     * Splits a single long sentence into smaller chunks when it exceeds maxChunkLength.
     *
     * @param {string} text - The long sentence text
     * @param {number} maxLength - Maximum allowable characters per chunk
     * @returns {Array<string>} Array of smaller sub-chunks
     */
    function fallbackSplitLongChunk(text, maxLength) {
        if (!text || text.length <= maxLength) {
            return [text];
        }

        const subChunks = [];
        let remaining = text.trim();

        while (remaining.length > maxLength) {
            // Attempt to break at natural sub-clauses: comma, semicolon, colon, dash
            const clauseRegex = /[,;:\u2014-]\s+/g;
            let splitIndex = -1;
            let match;

            while ((match = clauseRegex.exec(remaining)) !== null) {
                const matchEnd = match.index + match[0].length;
                if (matchEnd <= maxLength) {
                    splitIndex = matchEnd;
                } else {
                    break;
                }
            }

            // Fallback: break at the nearest whitespace before maxLength
            if (splitIndex <= 0) {
                const lastSpace = remaining.lastIndexOf(' ', maxLength);
                if (lastSpace > 0) {
                    splitIndex = lastSpace + 1;
                } else {
                    // Hard cut if no whitespace exists
                    splitIndex = maxLength;
                }
            }

            const chunk = remaining.substring(0, splitIndex).trim();
            if (chunk) {
                subChunks.push(chunk);
            }
            remaining = remaining.substring(splitIndex).trim();
        }

        if (remaining.length > 0) {
            subChunks.push(remaining);
        }

        return subChunks;
    }

    /**
     * Segments a paragraph's clean text into a list of sentence chunks.
     *
     * @param {string} text - Paragraph text
     * @param {Object} [customOptions] - Custom parsing options
     * @returns {Array<string>} Array of sentence chunk strings
     */
    function segmentParagraph(text, customOptions) {
        const options = Object.assign({}, DEFAULT_OPTIONS, customOptions);
        const cleaned = cleanText(text);

        if (!cleaned || cleaned.length < options.minChunkLength) {
            return [];
        }

        // Sentence boundary regex: matches ., !, ?, ..., followed by optional closing quotes/brackets and whitespace
        const sentenceBoundaryRegex = /([^.!?…\n]+(?:[.!?…]+["'\)\]»”]*|$))/g;
        const rawSegments = cleaned.match(sentenceBoundaryRegex) || [cleaned];
        const chunks = [];

        for (let i = 0; i < rawSegments.length; i++) {
            const rawSegment = rawSegments[i].trim();
            if (!rawSegment || rawSegment.length < options.minChunkLength) {
                continue;
            }

            if (rawSegment.length > options.maxChunkLength) {
                const subChunks = fallbackSplitLongChunk(rawSegment, options.maxChunkLength);
                for (let j = 0; j < subChunks.length; j++) {
                    const sub = subChunks[j].trim();
                    if (sub.length >= options.minChunkLength) {
                        chunks.push(sub);
                    }
                }
            } else {
                chunks.push(rawSegment);
            }
        }

        return chunks;
    }

    /**
     * Extracts and segments text from a rendered chapter body container.
     *
     * Prevents duplicate extraction for nested structural blocks (e.g. blockquote > p, li > p)
     * by resolving innermost / leaf block elements so each text segment has exactly one structural owner.
     *
     * @param {HTMLElement|string} containerOrSelector - Container element or CSS selector
     * @param {Object} [customOptions] - Custom parsing options
     * @returns {Array<{index: number, text: string, paragraphIndex: number, element: HTMLElement|null}>}
     */
    function parseChapterBody(containerOrSelector, customOptions) {
        const options = Object.assign({}, DEFAULT_OPTIONS, customOptions);
        let container = null;

        if (typeof containerOrSelector === 'string') {
            container = document.querySelector(containerOrSelector);
        } else if (containerOrSelector && containerOrSelector.nodeType === Node.ELEMENT_NODE) {
            container = containerOrSelector;
        }

        if (!container) {
            return [];
        }

        // Identify structural paragraph/block elements
        const blockSelector = 'p, h1, h2, h3, h4, h5, h6, blockquote, li, pre, dt, dd';
        const rawBlocks = Array.from(container.querySelectorAll(blockSelector));

        // Filter out container blocks that contain other block elements so only innermost/leaf blocks are extracted
        let blockElements = rawBlocks.filter(blockEl => {
            const hasChildBlock = blockEl.querySelector(blockSelector) !== null;
            return !hasChildBlock;
        });

        // If no child block elements were found, treat container itself as a single block
        if (blockElements.length === 0) {
            blockElements = [container];
        }

        const allChunks = [];
        let globalChunkIndex = 0;
        let paragraphIndex = 0;

        for (let i = 0; i < blockElements.length; i++) {
            const blockEl = blockElements[i];

            if (isExcludedNode(blockEl, options, container)) {
                continue;
            }

            const rawText = extractNodeText(blockEl, options, container);
            const sentenceChunks = segmentParagraph(rawText, options);

            if (sentenceChunks.length === 0) {
                continue;
            }

            for (let j = 0; j < sentenceChunks.length; j++) {
                allChunks.push({
                    index: globalChunkIndex++,
                    text: sentenceChunks[j],
                    paragraphIndex: paragraphIndex,
                    element: blockEl
                });
            }

            paragraphIndex++;
        }

        return allChunks;
    }

    /**
     * Parses plain text or Markdown-like multiline text into structured chunks.
     *
     * @param {string} text - Raw input text
     * @param {Object} [customOptions] - Custom parsing options
     * @returns {Array<{index: number, text: string, paragraphIndex: number, element: null}>}
     */
    function parseText(text, customOptions) {
        if (!text || typeof text !== 'string') {
            return [];
        }

        const paragraphs = text.split(/\n+/);
        const allChunks = [];
        let globalChunkIndex = 0;
        let paragraphIndex = 0;

        for (let i = 0; i < paragraphs.length; i++) {
            const pText = paragraphs[i].trim();
            if (!pText) {
                continue;
            }

            const sentenceChunks = segmentParagraph(pText, customOptions);
            if (sentenceChunks.length === 0) {
                continue;
            }

            for (let j = 0; j < sentenceChunks.length; j++) {
                allChunks.push({
                    index: globalChunkIndex++,
                    text: sentenceChunks[j],
                    paragraphIndex: paragraphIndex,
                    element: null
                });
            }

            paragraphIndex++;
        }

        return allChunks;
    }

    return {
        cleanText: cleanText,
        segmentParagraph: segmentParagraph,
        parseChapterBody: parseChapterBody,
        parseText: parseText,
        DEFAULT_OPTIONS: Object.freeze(DEFAULT_OPTIONS)
    };
});
