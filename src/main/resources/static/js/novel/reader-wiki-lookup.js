/**
 * Kiem Lai Novel Reader — Contextual Wiki Lookup Presentation Script
 *
 * Handles:
 * 1. Text selection evaluation inside .novel-reader-chapter-body
 * 2. Floating "Tra Wiki" trigger button
 * 3. Desktop Popover / Mobile Bottom Sheet presentation
 * 4. Loading, Empty, Error, Single-result, and Multi-result rendering
 * 5. Keyboard accessibility and dismissal mechanics
 */
(function () {
    'use strict';

    const ARTICLE_TYPE_LABELS = {
        CHARACTER: 'Nhân vật',
        REALM: 'Cảnh giới',
        CULTIVATION_PATH: 'Đạo thống',
        FACTION: 'Thế lực',
        ITEM: 'Bảo vật',
        TECHNIQUE: 'Công pháp',
        LOCATION: 'Địa danh',
        WORLD: 'Thế giới',
        TIMELINE_EVENT: 'Sự kiện'
    };

    const ARTICLE_TYPE_PATHS = {
        CHARACTER: 'character',
        REALM: 'realm',
        CULTIVATION_PATH: 'cultivation-path',
        FACTION: 'faction',
        ITEM: 'item',
        TECHNIQUE: 'technique',
        LOCATION: 'location',
        WORLD: 'world',
        TIMELINE_EVENT: 'timeline-event'
    };

    let actionBtn = null;
    let backdropEl = null;
    let resultContainer = null;
    let resultHeaderTitle = null;
    let resultBody = null;

    let currentSelectionText = '';
    let currentSelectionRect = null;
    let currentAbortController = null;
    let selectionTimeout = null;

    document.addEventListener('DOMContentLoaded', function () {
        initContextualWikiLookup();
    });

    function initContextualWikiLookup() {
        const chapterBody = document.querySelector('.novel-reader-chapter-body');
        if (!chapterBody) {
            return;
        }

        buildDomElements();

        // Selection listeners
        document.addEventListener('selectionchange', handleSelectionChange);
        document.addEventListener('mouseup', handlePointerEnd);
        document.addEventListener('touchend', handlePointerEnd);

        // Global dismissal listeners
        window.addEventListener('scroll', handleWindowScroll, { passive: true });
        window.addEventListener('resize', handleWindowResize, { passive: true });
        document.addEventListener('keydown', handleKeyDown);
        document.addEventListener('mousedown', handleOutsideClick);

        // Trigger action click
        actionBtn.addEventListener('click', onActionButtonClick);
    }

    function buildDomElements() {
        // 1. Floating trigger button
        actionBtn = document.createElement('button');
        actionBtn.type = 'button';
        actionBtn.id = 'novelWikiLookupActionBtn';
        actionBtn.className = 'novel-wiki-lookup-action-btn';
        actionBtn.setAttribute('aria-label', 'Tra cứu Wiki cho đoạn văn bản đã chọn');
        actionBtn.innerHTML = `
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
            <span>Tra Wiki</span>
        `;
        document.body.appendChild(actionBtn);

        // 2. Mobile backdrop
        backdropEl = document.createElement('div');
        backdropEl.id = 'novelWikiLookupBackdrop';
        backdropEl.className = 'novel-wiki-lookup-backdrop';
        backdropEl.setAttribute('aria-hidden', 'true');
        backdropEl.addEventListener('click', closeResultContainer);
        document.body.appendChild(backdropEl);

        // 3. Result presentation container
        resultContainer = document.createElement('div');
        resultContainer.id = 'novelWikiLookupResultContainer';
        resultContainer.className = 'novel-wiki-lookup-container';
        resultContainer.setAttribute('role', 'dialog');
        resultContainer.setAttribute('aria-label', 'Kết quả tra cứu Wiki');
        resultContainer.setAttribute('tabindex', '-1');

        resultContainer.innerHTML = `
            <div class="novel-wiki-lookup-header">
                <div class="novel-wiki-lookup-header-title">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path>
                        <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path>
                    </svg>
                    <span id="novelWikiLookupHeaderTitleText">Tra cứu Wiki</span>
                </div>
                <button type="button" class="novel-wiki-lookup-close-btn" id="novelWikiLookupCloseBtn" aria-label="Đóng kết quả tra cứu Wiki">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>
            <div class="novel-wiki-lookup-body" id="novelWikiLookupBody"></div>
        `;
        document.body.appendChild(resultContainer);

        resultHeaderTitle = resultContainer.querySelector('#novelWikiLookupHeaderTitleText');
        resultBody = resultContainer.querySelector('#novelWikiLookupBody');

        const closeBtn = resultContainer.querySelector('#novelWikiLookupCloseBtn');
        if (closeBtn) {
            closeBtn.addEventListener('click', closeResultContainer);
        }
    }

    function handleSelectionChange() {
        if (selectionTimeout) {
            clearTimeout(selectionTimeout);
        }
        selectionTimeout = setTimeout(evaluateSelection, 80);
    }

    function handlePointerEnd() {
        setTimeout(evaluateSelection, 50);
    }

    function evaluateSelection() {
        const selection = window.getSelection();
        if (!selection || selection.isCollapsed) {
            hideActionButton();
            return;
        }

        const chapterBody = document.querySelector('.novel-reader-chapter-body');
        if (!chapterBody) {
            hideActionButton();
            return;
        }

        // Validate that the selection is strictly inside the chapter body
        const anchorNode = selection.anchorNode;
        const focusNode = selection.focusNode;
        if (!anchorNode || !focusNode || !chapterBody.contains(anchorNode) || !chapterBody.contains(focusNode)) {
            hideActionButton();
            return;
        }

        const rawText = selection.toString();
        const normalizedText = rawText ? rawText.trim() : '';

        // Length validation: must be 1..100 characters
        if (normalizedText.length === 0 || normalizedText.length > 100) {
            hideActionButton();
            return;
        }

        currentSelectionText = normalizedText;

        if (selection.rangeCount > 0) {
            const range = selection.getRangeAt(0);
            const rect = range.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) {
                hideActionButton();
                return;
            }
            currentSelectionRect = rect;
            positionActionButton(rect);
        }
    }

    function positionActionButton(rect) {
        if (!actionBtn) {
            return;
        }

        actionBtn.classList.add('is-visible');

        if (isMobileViewport()) {
            actionBtn.style.left = '';
            actionBtn.style.top = '';
            return;
        }

        const btnWidth = actionBtn.offsetWidth || 100;
        const btnHeight = actionBtn.offsetHeight || 32;
        const gap = 8;

        let left = rect.left + rect.width / 2 - btnWidth / 2;
        let top = rect.top - btnHeight - gap;

        if (top < 10) {
            top = rect.bottom + gap;
        }

        const padding = 12;
        const maxLeft = window.innerWidth - btnWidth - padding;
        if (left < padding) {
            left = padding;
        } else if (left > maxLeft) {
            left = maxLeft;
        }

        actionBtn.style.left = `${Math.round(left)}px`;
        actionBtn.style.top = `${Math.round(top)}px`;
    }

    function hideActionButton() {
        if (actionBtn) {
            actionBtn.classList.remove('is-visible');
        }
    }

    function isMobileViewport() {
        return window.innerWidth <= 640;
    }

    function openResultContainer(rect) {
        hideActionButton();

        if (!resultContainer) {
            return;
        }

        if (isMobileViewport()) {
            if (backdropEl) {
                backdropEl.classList.add('is-visible');
            }
            resultContainer.style.left = '';
            resultContainer.style.top = '';
            resultContainer.classList.add('is-visible');
        } else {
            if (backdropEl) {
                backdropEl.classList.remove('is-visible');
            }
            positionDesktopPopover(rect);
            resultContainer.classList.add('is-visible');
        }

        resultContainer.focus();
    }

    function positionDesktopPopover(rect) {
        if (!resultContainer || !rect) {
            return;
        }

        const popoverWidth = 390;
        const popoverHeight = 360;
        const gap = 10;

        let left = rect.left + rect.width / 2 - popoverWidth / 2;
        let top = rect.bottom + gap;

        // If overflowing bottom of viewport, position above selection
        if (top + popoverHeight > window.innerHeight - 20) {
            top = rect.top - popoverHeight - gap;
            if (top < 10) {
                top = 10;
            }
        }

        const padding = 16;
        const maxLeft = window.innerWidth - popoverWidth - padding;
        if (left < padding) {
            left = padding;
        } else if (left > maxLeft) {
            left = maxLeft;
        }

        resultContainer.style.left = `${Math.round(left)}px`;
        resultContainer.style.top = `${Math.round(top)}px`;
    }

    function closeResultContainer() {
        if (resultContainer) {
            resultContainer.classList.remove('is-visible');
        }
        if (backdropEl) {
            backdropEl.classList.remove('is-visible');
        }
    }

    function handleWindowScroll() {
        hideActionButton();
        if (!isMobileViewport() && resultContainer && resultContainer.classList.contains('is-visible')) {
            closeResultContainer();
        }
    }

    function handleWindowResize() {
        hideActionButton();
        if (resultContainer && resultContainer.classList.contains('is-visible')) {
            if (!isMobileViewport() && currentSelectionRect) {
                positionDesktopPopover(currentSelectionRect);
            }
        }
    }

    function handleKeyDown(e) {
        if (e.key === 'Escape') {
            hideActionButton();
            closeResultContainer();
        }
    }

    function handleOutsideClick(e) {
        if (actionBtn && !actionBtn.contains(e.target)) {
            hideActionButton();
        }
        if (!isMobileViewport() && resultContainer && resultContainer.classList.contains('is-visible')) {
            if (!resultContainer.contains(e.target) && (!actionBtn || !actionBtn.contains(e.target))) {
                closeResultContainer();
            }
        }
    }

    async function onActionButtonClick(e) {
        e.preventDefault();
        e.stopPropagation();

        const query = currentSelectionText;
        if (!query) {
            hideActionButton();
            return;
        }

        const selectionRect = currentSelectionRect;
        openResultContainer(selectionRect);
        renderLoading(query);

        if (currentAbortController) {
            currentAbortController.abort();
        }
        currentAbortController = new AbortController();

        try {
            const url = `/novel/api/wiki/lookup?q=${encodeURIComponent(query)}`;
            const response = await fetch(url, {
                method: 'GET',
                signal: currentAbortController.signal,
                headers: {
                    'Accept': 'application/json'
                }
            });

            if (response.ok) {
                const data = await response.json();
                renderResults(data, query);
                document.dispatchEvent(new CustomEvent('novel:wiki-lookup-success', {
                    detail: data
                }));
            } else {
                renderError(query);
            }
        } catch (err) {
            if (err.name !== 'AbortError') {
                console.error('Lỗi khi tra cứu Wiki:', err);
                renderError(query);
            }
        }
    }

    function getArticleUrl(articleType, slug) {
        const typePath = (articleType && ARTICLE_TYPE_PATHS[articleType])
            ? ARTICLE_TYPE_PATHS[articleType]
            : (articleType ? articleType.toLowerCase().replace(/_/g, '-') : 'article');
        return `/wiki/${encodeURIComponent(typePath)}/${encodeURIComponent(slug)}`;
    }

    function getArticleTypeLabel(articleType) {
        return ARTICLE_TYPE_LABELS[articleType] || articleType || 'Wiki';
    }

    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function renderLoading(query) {
        if (resultHeaderTitle) {
            resultHeaderTitle.textContent = `Tra cứu: "${query}"`;
        }
        if (resultBody) {
            resultBody.innerHTML = `
                <div class="novel-wiki-lookup-loading">
                    <div class="novel-wiki-lookup-spinner" aria-hidden="true"></div>
                    <div>Đang tra cứu Wiki...</div>
                </div>
            `;
        }
    }

    function renderEmpty(query) {
        if (resultHeaderTitle) {
            resultHeaderTitle.textContent = 'Kết quả tra cứu';
        }
        if (resultBody) {
            resultBody.innerHTML = `
                <div class="novel-wiki-lookup-empty">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <circle cx="11" cy="11" r="8"></circle>
                        <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
                    </svg>
                    <div>Không tìm thấy thông tin Wiki phù hợp với từ khóa <span class="novel-wiki-lookup-empty-query">"${escapeHtml(query)}"</span>.</div>
                </div>
            `;
        }
    }

    function renderError(query) {
        if (resultHeaderTitle) {
            resultHeaderTitle.textContent = 'Tra cứu Wiki';
        }
        if (resultBody) {
            resultBody.innerHTML = `
                <div class="novel-wiki-lookup-error">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <circle cx="12" cy="12" r="10"></circle>
                        <line x1="12" y1="8" x2="12" y2="12"></line>
                        <line x1="12" y1="16" x2="12.01" y2="16"></line>
                    </svg>
                    <div>Không thể tra cứu thông tin Wiki lúc này. Vui lòng thử lại sau.</div>
                </div>
            `;
        }
    }

    function renderResults(data, query) {
        if (!data || !data.items || data.items.length === 0) {
            renderEmpty(query);
            return;
        }

        const items = data.items;
        const primaryItem = items[0];
        const secondaryItems = items.slice(1);

        if (resultHeaderTitle) {
            resultHeaderTitle.textContent = 'Thông tin Wiki';
        }

        let html = `
            <div class="novel-wiki-lookup-primary-card">
                <div class="novel-wiki-lookup-card-meta">
                    <span class="novel-wiki-lookup-type-badge">${escapeHtml(getArticleTypeLabel(primaryItem.articleType))}</span>
                    ${primaryItem.matchedAlias ? `<span class="novel-wiki-lookup-alias-badge">Khớp danh xưng: "${escapeHtml(primaryItem.matchedAlias)}"</span>` : ''}
                </div>
                <h3 class="novel-wiki-lookup-card-title">${escapeHtml(primaryItem.title)}</h3>
                <p class="novel-wiki-lookup-card-summary">${escapeHtml(primaryItem.summary || 'Chưa có tóm tắt chi tiết.')}</p>
                <div class="novel-wiki-lookup-card-actions">
                    <a href="${escapeHtml(getArticleUrl(primaryItem.articleType, primaryItem.slug))}" target="_blank" rel="noopener noreferrer" class="novel-wiki-lookup-article-link">
                        <span>Xem bài viết chi tiết</span>
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <line x1="5" y1="12" x2="19" y2="12"></line>
                            <polyline points="12 5 19 12 12 19"></polyline>
                        </svg>
                    </a>
                </div>
            </div>
        `;

        if (secondaryItems.length > 0) {
            html += `
                <div class="novel-wiki-lookup-secondary-section">
                    <div class="novel-wiki-lookup-secondary-heading">Kết quả liên quan khác (${secondaryItems.length})</div>
                    <ul class="novel-wiki-lookup-secondary-list">
            `;

            for (const item of secondaryItems) {
                html += `
                    <li>
                        <a href="${escapeHtml(getArticleUrl(item.articleType, item.slug))}" target="_blank" rel="noopener noreferrer" class="novel-wiki-lookup-secondary-item">
                            <div class="novel-wiki-lookup-secondary-content">
                                <span class="novel-wiki-lookup-secondary-title">${escapeHtml(item.title)}</span>
                                ${item.matchedAlias ? `<span class="novel-wiki-lookup-secondary-alias">Khớp danh xưng: "${escapeHtml(item.matchedAlias)}"</span>` : ''}
                            </div>
                            <span class="novel-wiki-lookup-secondary-meta">
                                <span class="novel-wiki-lookup-type-badge">${escapeHtml(getArticleTypeLabel(item.articleType))}</span>
                            </span>
                        </a>
                    </li>
                `;
            }

            html += `
                    </ul>
                </div>
            `;
        }

        if (resultBody) {
            resultBody.innerHTML = html;
        }
    }
})();