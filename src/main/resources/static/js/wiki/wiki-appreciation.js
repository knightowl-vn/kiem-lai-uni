/**
 * Kiem Lai Wiki — Appreciation Rating Frontend Module
 *
 * MS-05F5: Interactive 1-5 star appreciation rating on public Wiki detail.
 *
 * Communicates with:
 *   PUT /api/wiki/articles/{articleId}/appreciation
 * Body:
 *   { "value": 1..5 }
 */
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.WikiAppreciation = factory();
    }
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    const WIDGET_ID = 'wikiAppreciationWidget';
    const AVERAGE_ID = 'wikiAppreciationAverage';
    const COUNT_ID = 'wikiAppreciationCount';
    const STATS_ID = 'wikiAppreciationStats';
    const FEEDBACK_ID = 'wikiAppreciationFeedback';

    let currentDoc = null;
    let isMutating = false;
    let injectedFetch = null;

    /**
     * Resolves DOM elements for the Wiki appreciation widget.
     */
    function getElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return {};
        const widget = d.getElementById(WIDGET_ID);
        const stats = d.getElementById(STATS_ID);
        return {
            widgetEl: widget,
            averageEl: d.getElementById(AVERAGE_ID),
            separatorEl: stats ? stats.querySelector('.wiki-appreciation-separator') : (d.querySelector ? d.querySelector('.wiki-appreciation-separator') : null),
            dotEl: stats ? stats.querySelector('.wiki-appreciation-dot') : (d.querySelector ? d.querySelector('.wiki-appreciation-dot') : null),
            countEl: d.getElementById(COUNT_ID),
            statsEl: stats,
            feedbackEl: d.getElementById(FEEDBACK_ID),
            starButtons: widget ? Array.from(widget.querySelectorAll('.wiki-star-btn')) : []
        };
    }

    /**
     * Resolves CSRF token and header name from widget attributes or meta tags.
     */
    function resolveCsrf(widgetEl, doc) {
        let token = widgetEl.getAttribute('data-csrf-token');
        let header = widgetEl.getAttribute('data-csrf-header');

        if (!token || !header) {
            const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
            if (d) {
                const metaToken = d.querySelector('meta[name="_csrf"]');
                const metaHeader = d.querySelector('meta[name="_csrf_header"]');
                if (metaToken && metaHeader) {
                    token = metaToken.getAttribute('content');
                    header = metaHeader.getAttribute('content');
                }
            }
        }
        return { token: token || '', header: header || 'X-CSRF-TOKEN' };
    }

    /**
     * Updates the visual and accessible active state of star buttons.
     */
    function updateStarVisuals(starButtons, selectedValue) {
        const val = parseFloat(selectedValue) || 0;
        starButtons.forEach(btn => {
            const btnVal = parseFloat(btn.getAttribute('data-star-value')) || 0;
            if (btnVal > 0 && btnVal <= val + 0.001) {
                btn.classList.add('is-active');
            } else {
                btn.classList.remove('is-active');
            }
            btn.setAttribute('aria-pressed', (Math.abs(btnVal - val) < 0.01 && val > 0) ? 'true' : 'false');
        });
    }

    /**
     * Updates community stats display from authoritative server response.
     */
    function updateCommunityStats(elements, average, count, displayAverage) {
        const numCount = Number(count) || 0;

        if (numCount > 0 && average != null) {
            const formattedAvg = (displayAverage != null && displayAverage !== '')
                ? String(displayAverage)
                : String(average);
            if (elements.averageEl) {
                elements.averageEl.textContent = formattedAvg;
                elements.averageEl.hidden = false;
            }
            const sep = elements.separatorEl || (elements.statsEl ? elements.statsEl.querySelector('.wiki-appreciation-separator') : null);
            if (sep) {
                sep.hidden = false;
            }
            const dot = elements.dotEl || (elements.statsEl ? elements.statsEl.querySelector('.wiki-appreciation-dot') : null);
            if (dot) {
                dot.hidden = false;
            }
            if (elements.countEl) {
                elements.countEl.textContent = numCount + ' lượt đánh giá';
            }
        } else {
            if (elements.averageEl) {
                elements.averageEl.textContent = '';
                elements.averageEl.hidden = true;
            }
            const sep = elements.separatorEl || (elements.statsEl ? elements.statsEl.querySelector('.wiki-appreciation-separator') : null);
            if (sep) {
                sep.hidden = true;
            }
            const dot = elements.dotEl || (elements.statsEl ? elements.statsEl.querySelector('.wiki-appreciation-dot') : null);
            if (dot) {
                dot.hidden = true;
            }
            if (elements.countEl) {
                elements.countEl.textContent = 'Chưa có đánh giá';
            }
        }
    }

    /**
     * Displays a feedback/error message in the live region.
     */
    function showFeedback(feedbackEl, message, isSuccess = false) {
        if (!feedbackEl) return;
        feedbackEl.textContent = message;
        if (isSuccess) {
            feedbackEl.classList.add('is-success');
        } else {
            feedbackEl.classList.remove('is-success');
        }
    }

    /**
     * Clears feedback live region.
     */
    function clearFeedback(feedbackEl) {
        if (!feedbackEl) return;
        feedbackEl.textContent = '';
        feedbackEl.classList.remove('is-success');
    }

    /**
     * Disables or enables star buttons (in-flight request guard).
     */
    function setButtonsDisabled(starButtons, disabled) {
        starButtons.forEach(btn => {
            btn.disabled = disabled;
        });
    }

    /**
     * Initializes the Wiki appreciation rating component.
     */
    function init(doc, options = {}) {
        currentDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!currentDoc) return false;

        if (options.fetchImpl) {
            injectedFetch = options.fetchImpl;
        }

        const els = getElements(currentDoc);
        if (!els.widgetEl) return false;

        const widget = els.widgetEl;
        const starButtons = els.starButtons;
        const initialViewerValue = widget.getAttribute('data-viewer-value');
        const isAuthenticated = widget.getAttribute('data-authenticated') === 'true';
        const loginUrl = widget.getAttribute('data-login-url') || '/login';
        const appreciationUrl = widget.getAttribute('data-appreciation-url');

        // Apply initial visual active state
        updateStarVisuals(starButtons, initialViewerValue);

        // Hover preview handlers
        starButtons.forEach(btn => {
            btn.addEventListener('mouseenter', () => {
                if (isMutating) return;
                const hoverVal = parseFloat(btn.getAttribute('data-star-value')) || 0;
                starButtons.forEach(b => {
                    const bVal = parseFloat(b.getAttribute('data-star-value')) || 0;
                    if (bVal <= hoverVal + 0.001) {
                        b.classList.add('is-hover-preview');
                    } else {
                        b.classList.remove('is-hover-preview');
                    }
                });
            });
        });

        widget.addEventListener('mouseleave', () => {
            starButtons.forEach(b => b.classList.remove('is-hover-preview'));
        });

        // Click selection handler
        starButtons.forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.preventDefault();

                // Anonymous reader: redirect directly to login
                if (!isAuthenticated) {
                    const navTarget = options.onRedirect ? options.onRedirect(loginUrl) : null;
                    if (!options.onRedirect) {
                        const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : window;
                        win.location.href = loginUrl;
                    }
                    return;
                }

                if (isMutating) return;

                const starValue = parseFloat(btn.getAttribute('data-star-value'));
                if (!starValue || starValue < 1 || starValue > 5) return;

                const previousValue = widget.getAttribute('data-viewer-value');
                clearFeedback(els.feedbackEl);
                isMutating = true;
                setButtonsDisabled(starButtons, true);

                try {
                    const { token, header } = resolveCsrf(widget, currentDoc);
                    const headers = {
                        'Content-Type': 'application/json'
                    };
                    if (token && header) {
                        headers[header] = token;
                    }

                    const fetchFn = injectedFetch || (typeof fetch !== 'undefined' ? fetch : null);
                    if (!fetchFn) {
                        throw new Error('fetch is not available');
                    }

                    const response = await fetchFn(appreciationUrl, {
                        method: 'PUT',
                        headers: headers,
                        body: JSON.stringify({ value: starValue })
                    });

                    // 1. Detect if fetch followed a security/auth redirect (e.g. 302 to login or /access-denied)
                    if (response.redirected) {
                        updateStarVisuals(starButtons, previousValue);
                        const targetUrl = response.url || '';
                        if (targetUrl.includes('/login') || targetUrl.includes('/oauth2') || targetUrl.includes('/auth')) {
                            const navTarget = options.onRedirect ? options.onRedirect(loginUrl) : null;
                            if (!options.onRedirect) {
                                const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : window;
                                win.location.href = loginUrl;
                            }
                        } else if (targetUrl.includes('/access-denied')) {
                            showFeedback(els.feedbackEl, 'Yêu cầu bị từ chối hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
                        } else {
                            showFeedback(els.feedbackEl, 'Yêu cầu bị từ chối hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
                        }
                        return;
                    }

                    // 2. Direct HTTP status handling with content-type verification
                    const contentType = response.headers && typeof response.headers.get === 'function'
                        ? (response.headers.get('content-type') || '')
                        : '';

                    if (response.status === 200) {
                        // Guard against HTML error or login page returned with 200
                        if (contentType && !contentType.includes('application/json')) {
                            updateStarVisuals(starButtons, previousValue);
                            const targetUrl = response.url || '';
                            if (targetUrl.includes('/access-denied')) {
                                showFeedback(els.feedbackEl, 'Yêu cầu bị từ chối hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
                            } else if (targetUrl.includes('/login')) {
                                const navTarget = options.onRedirect ? options.onRedirect(loginUrl) : null;
                                if (!options.onRedirect) {
                                    const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : window;
                                    win.location.href = loginUrl;
                                }
                            } else {
                                showFeedback(els.feedbackEl, 'Phản hồi từ máy chủ không hợp lệ. Vui lòng thử lại sau.');
                            }
                            return;
                        }

                        const data = await response.json();
                        widget.setAttribute('data-viewer-value', String(data.value));
                        updateStarVisuals(starButtons, data.value);
                        updateCommunityStats(els, data.average, data.count, data.displayAverage);
                        showFeedback(els.feedbackEl, 'Đã lưu đánh giá của bạn.', true);
                    } else if (response.status === 401) {
                        updateStarVisuals(starButtons, previousValue);
                        const navTarget = options.onRedirect ? options.onRedirect(loginUrl) : null;
                        if (!options.onRedirect) {
                            const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : window;
                            win.location.href = loginUrl;
                        }
                    } else if (response.status === 403) {
                        updateStarVisuals(starButtons, previousValue);
                        showFeedback(els.feedbackEl, 'Yêu cầu bị từ chối hoặc bạn không có quyền thực hiện.');
                    } else if (response.status === 404) {
                        updateStarVisuals(starButtons, previousValue);
                        showFeedback(els.feedbackEl, 'Bài viết không còn khả dụng để đánh giá.');
                    } else {
                        updateStarVisuals(starButtons, previousValue);
                        showFeedback(els.feedbackEl, 'Có lỗi xảy ra khi lưu đánh giá. Vui lòng thử lại sau.');
                    }
                } catch (err) {
                    updateStarVisuals(starButtons, previousValue);
                    showFeedback(els.feedbackEl, 'Không thể kết nối đến máy chủ. Vui lòng thử lại sau.');
                } finally {
                    isMutating = false;
                    setButtonsDisabled(starButtons, false);
                    starButtons.forEach(b => b.classList.remove('is-hover-preview'));
                }
            });
        });

        return true;
    }

    // Auto-init in browser DOM environment
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => init(document));
        } else {
            init(document);
        }
    }

    return {
        init: init,
        getElements: getElements,
        updateStarVisuals: updateStarVisuals,
        updateCommunityStats: updateCommunityStats
    };
}));
