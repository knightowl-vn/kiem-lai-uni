/**
 * KiemLai Community V1 — Feed Controller
 *
 * Responsibilities:
 * - NEWEST / FEATURED tab switching via AJAX (GET /api/community/posts?feed=...).
 * - Cursor-based (NEWEST) and Page-based (FEATURED) load-more pagination.
 * - Safe DOM post card rendering using textContent (zero XSS vulnerability).
 * - Empty-state and loading-spinner toggling.
 * - Integration with RelativeTime engine for dynamic timestamps.
 * - Global window.CommunityFeed.refreshFeed() hook for composer reload.
 * - Generation ownership (currentFeedGeneration) preventing mode-switch / composer-refresh async races (B81-02).
 * - Non-destructive error UI feedback and retry affordance for feed AJAX failures (B81-03).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        let postCard, relativeTime, interactionReactions;
        try { postCard = require('./community-post-card.js'); } catch (_) {}
        try { relativeTime = require('../shared/relative-time.js'); } catch (_) {}
        try { interactionReactions = require('../shared/interaction-reactions.js'); } catch (_) {}
        module.exports = factory(postCard, relativeTime, interactionReactions);
    } else {
        const exports = factory(root.CommunityPostCard, root.RelativeTime, root.InteractionReactions);
        root.CommunityFeed = exports;
        if (typeof window !== 'undefined') {
            window.CommunityFeed = exports;
        }
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommunityFeed = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function (injectedPostCard, injectedRelativeTime, injectedReactions) {
    'use strict';

    const PAGE_SIZE = 20;

    let currentDoc = null;
    let currentFeed = 'NEWEST';
    let nextCursor = null;
    let nextPage = null;
    let hasNext = false;
    let isLoading = false;
    let currentFeedGeneration = 0;
    let activeLoadingGeneration = 0;
    let initialized = false;

    function initFeed(doc) {
        currentDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!currentDoc) {
            return;
        }

        const feedListEl = currentDoc.getElementById('communityFeedList');
        if (!feedListEl) {
            return;
        }

        // Read initial SSR state
        currentFeed = feedListEl.getAttribute('data-selected-feed') || 'NEWEST';
        nextCursor = feedListEl.getAttribute('data-next-cursor') || null;
        const pageAttr = feedListEl.getAttribute('data-next-page');
        nextPage = (pageAttr && pageAttr !== '') ? parseInt(pageAttr, 10) : null;
        hasNext = feedListEl.getAttribute('data-has-next') === 'true';

        // Bind feed sort dropdown
        const sortContainer = currentDoc.getElementById('communityFeedSortDropdown');
        const sortTrigger = currentDoc.getElementById('communityFeedSortTrigger');
        const sortMenu = currentDoc.getElementById('communityFeedSortMenu');

        function openFeedSort() {
            if (sortTrigger && sortMenu) {
                sortTrigger.setAttribute('aria-expanded', 'true');
                sortMenu.hidden = false;
                sortMenu.removeAttribute('hidden');
                if (sortContainer) sortContainer.classList.add('is-open');
            }
        }

        function closeFeedSort(restoreFocus) {
            if (sortTrigger && sortMenu) {
                sortTrigger.setAttribute('aria-expanded', 'false');
                sortMenu.hidden = true;
                sortMenu.setAttribute('hidden', '');
                if (sortContainer) sortContainer.classList.remove('is-open');
                if (restoreFocus && typeof sortTrigger.focus === 'function') {
                    try { sortTrigger.focus(); } catch (_) {}
                }
            }
        }

        if (sortTrigger && !sortTrigger._feedBound) {
            sortTrigger._feedBound = true;
            sortTrigger.addEventListener('click', function (e) {
                if (e && typeof e.preventDefault === 'function') e.preventDefault();
                if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
                const isExpanded = sortTrigger.getAttribute('aria-expanded') === 'true';
                if (isExpanded) {
                    closeFeedSort(false);
                } else {
                    openFeedSort();
                }
            });
        }

        if (sortMenu && !sortMenu._feedBound) {
            sortMenu._feedBound = true;
            const sortItems = sortMenu.querySelectorAll('[data-action="change-feed-sort"]');
            sortItems.forEach(function (btn) {
                btn.addEventListener('click', function (e) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    const feedType = btn.getAttribute('data-feed');
                    closeFeedSort(false);
                    if (feedType && feedType !== currentFeed) {
                        switchFeed(feedType);
                    }
                });
            });
        }

        if (!currentDoc._feedGlobalBound) {
            currentDoc._feedGlobalBound = true;
            currentDoc.addEventListener('click', function (e) {
                if (sortContainer && !sortContainer.contains(e.target)) {
                    closeFeedSort(false);
                }
            });

            currentDoc.addEventListener('keydown', function (e) {
                if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
                    closeFeedSort(true);
                }
            });
        }

        // Bind Load More button
        const loadMoreBtn = currentDoc.getElementById('communityLoadMoreBtn');
        if (loadMoreBtn && !loadMoreBtn._feedBound) {
            loadMoreBtn._feedBound = true;
            loadMoreBtn.addEventListener('click', function () {
                if (!isLoading && hasNext) {
                    loadMorePosts();
                }
            });
        }

        if (typeof window !== 'undefined') {
            window.CommunityFeed = {
                refreshFeed: function (feedType) {
                    switchFeed(feedType || currentFeed);
                },
                switchFeed: switchFeed,
                loadMorePosts: loadMorePosts,
                getCurrentFeed: function () {
                    return currentFeed;
                },
                getCurrentGeneration: function () {
                    return currentFeedGeneration;
                },
                init: initFeed,
                resetForTesting: resetForTesting
            };
        }

        initialized = true;
    }

    function switchFeed(feedType, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return Promise.resolve(false);

        feedType = feedType || 'NEWEST';
        currentFeedGeneration++;
        const gen = currentFeedGeneration;

        currentFeed = feedType;
        nextCursor = null;
        nextPage = (feedType === 'FEATURED') ? 0 : null;
        hasNext = false;

        updateFeedSortUi(feedType, d);
        updateBrowserUrl(feedType);
        hideFeedError(d);

        // Clear existing cards immediately
        const feedListEl = d.getElementById('communityFeedList');
        if (feedListEl) {
            const articles = feedListEl.querySelectorAll('.community-post-card');
            articles.forEach(function (el) {
                el.remove();
            });
            const emptyMsgEl = d.getElementById('emptyFeedMessage');
            if (emptyMsgEl) {
                emptyMsgEl.setAttribute('hidden', '');
            }
        }

        return fetchFeedPage(true, gen, d);
    }

    function refreshFeed(feedType, doc) {
        return switchFeed(feedType || currentFeed, doc);
    }

    function loadMorePosts(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return Promise.resolve(false);

        if (isLoading && activeLoadingGeneration === currentFeedGeneration) {
            return Promise.resolve(false);
        }
        if (!hasNext) {
            return Promise.resolve(false);
        }

        hideFeedError(d);
        return fetchFeedPage(false, currentFeedGeneration, d);
    }

    function updateFeedSortUi(feedType, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        const sortLabel = d.getElementById('communityFeedSortLabel');
        if (sortLabel) {
            sortLabel.textContent = (feedType === 'FEATURED') ? 'Nổi bật' : 'Mới nhất';
        }
        const sortMenu = d.getElementById('communityFeedSortMenu');
        if (sortMenu) {
            const items = sortMenu.querySelectorAll('[data-action="change-feed-sort"]');
            items.forEach(function (btn) {
                const feed = btn.getAttribute('data-feed');
                const isSelected = (feed === feedType);
                if (isSelected) {
                    btn.classList.add('is-selected');
                    btn.setAttribute('aria-checked', 'true');
                } else {
                    btn.classList.remove('is-selected');
                    btn.setAttribute('aria-checked', 'false');
                }
            });
        }
    }

    function updateBrowserUrl(feedType) {
        if (typeof window !== 'undefined' && window.history && typeof window.history.pushState === 'function') {
            const newUrl = '/community?feed=' + feedType;
            try {
                window.history.pushState({ feed: feedType }, '', newUrl);
            } catch (_) {}
        }
    }

    function fetchFeedPage(isInitial, gen, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return Promise.resolve(false);

        activeLoadingGeneration = gen;
        setLoading(true, d);

        let url = '/api/community/posts?feed=' + encodeURIComponent(currentFeed) + '&size=' + PAGE_SIZE;
        if (!isInitial) {
            if (currentFeed === 'NEWEST' && nextCursor) {
                url += '&cursor=' + encodeURIComponent(nextCursor);
            } else if (currentFeed === 'FEATURED' && nextPage != null) {
                url += '&page=' + encodeURIComponent(nextPage);
            }
        }

        const fetchFn = (typeof fetch === 'function') ? fetch : (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchFn) {
            console.error('Fetch API not available');
            setLoading(false, d);
            return Promise.resolve(false);
        }

        return fetchFn(url)
            .then(function (res) {
                if (gen !== currentFeedGeneration) {
                    return null;
                }
                if (!res.ok) {
                    throw new Error('Không thể tải bài viết');
                }
                return res.json();
            })
            .then(function (data) {
                if (!data || gen !== currentFeedGeneration) {
                    return false;
                }
                renderFeedData(data, isInitial, d);
                return true;
            })
            .catch(function (err) {
                if (gen !== currentFeedGeneration) {
                    return false;
                }
                console.error('Error fetching feed:', err);
                showFeedError(d, 'Không thể tải bài viết. Vui lòng thử lại.', function () {
                    if (gen === currentFeedGeneration) {
                        fetchFeedPage(isInitial, currentFeedGeneration, d);
                    }
                });
                return false;
            })
            .finally(function () {
                if (gen === currentFeedGeneration) {
                    setLoading(false, d);
                }
            });
    }

    function renderFeedData(data, isInitial, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        const feedListEl = d.getElementById('communityFeedList');
        const emptyMsgEl = d.getElementById('emptyFeedMessage');
        const loadMoreContainer = d.querySelector('.community-load-more-container');

        if (!feedListEl) return;

        hideFeedError(d);

        const items = data.items || [];
        hasNext = !!data.hasNext;
        nextCursor = data.nextCursor || null;
        nextPage = (typeof data.nextPage === 'number') ? data.nextPage : null;

        if (isInitial && items.length === 0) {
            if (emptyMsgEl) {
                emptyMsgEl.removeAttribute('hidden');
            }
            if (loadMoreContainer) {
                loadMoreContainer.setAttribute('hidden', '');
            }
            return;
        }

        if (emptyMsgEl) {
            emptyMsgEl.setAttribute('hidden', '');
        }

        const postCardModule = (typeof window !== 'undefined' && window.CommunityPostCard) ? window.CommunityPostCard : injectedPostCard;
        const relTimeModule = (typeof window !== 'undefined' && window.RelativeTime) ? window.RelativeTime : injectedRelativeTime;
        const reactionsModule = (typeof window !== 'undefined' && window.InteractionReactions) ? window.InteractionReactions : injectedReactions;

        items.forEach(function (item) {
            const card = createPostCardElement(item, d, postCardModule);
            if (emptyMsgEl) {
                feedListEl.insertBefore(card, emptyMsgEl);
            } else {
                feedListEl.appendChild(card);
            }

            if (relTimeModule && typeof relTimeModule.formatTree === 'function') {
                relTimeModule.formatTree(card);
            } else if (typeof window !== 'undefined' && window.RelativeTime && typeof window.RelativeTime.formatTree === 'function') {
                window.RelativeTime.formatTree(card);
            }

            if (reactionsModule && typeof reactionsModule.hydrate === 'function') {
                reactionsModule.hydrate(card);
            } else if (typeof window !== 'undefined' && window.InteractionReactions && typeof window.InteractionReactions.hydrate === 'function') {
                window.InteractionReactions.hydrate(card);
            }
        });

        if (loadMoreContainer) {
            if (hasNext) {
                loadMoreContainer.removeAttribute('hidden');
            } else {
                loadMoreContainer.setAttribute('hidden', '');
            }
        }
    }

    function createPostCardElement(item, doc, postCardModule) {
        const mod = postCardModule || (typeof window !== 'undefined' ? window.CommunityPostCard : null) || injectedPostCard;
        if (mod && typeof mod.create === 'function') {
            const feedListEl = doc.getElementById('communityFeedList');
            const isAuth = feedListEl ? feedListEl.getAttribute('data-authenticated') === 'true' : false;
            return mod.create(item, { isAuthenticated: isAuth }, doc);
        }
        if (typeof window !== 'undefined' && window.CommunityPostCard && typeof window.CommunityPostCard.create === 'function') {
            const feedListEl = doc.getElementById('communityFeedList');
            const isAuth = feedListEl ? feedListEl.getAttribute('data-authenticated') === 'true' : false;
            return window.CommunityPostCard.create(item, { isAuthenticated: isAuth });
        }
        throw new Error('CommunityPostCard module is required to render post cards.');
    }

    function showFeedError(doc, message, retryCallback) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        let errEl = d.getElementById('feedErrorMessage');
        const feedListEl = d.getElementById('communityFeedList');
        if (!errEl && feedListEl) {
            errEl = d.createElement('div');
            errEl.id = 'feedErrorMessage';
            errEl.className = 'community-feed-error-card alert alert-warning text-center my-3';
            errEl.setAttribute('role', 'alert');
            feedListEl.appendChild(errEl);
        }

        if (errEl) {
            errEl.innerHTML = '';
            const msgSpan = d.createElement('span');
            msgSpan.className = 'feed-error-text me-2';
            msgSpan.textContent = message || 'Không thể tải bài viết. Vui lòng thử lại.';
            errEl.appendChild(msgSpan);

            const retryBtn = d.createElement('button');
            retryBtn.type = 'button';
            retryBtn.className = 'btn btn-sm btn-outline-danger feed-retry-btn';
            retryBtn.setAttribute('data-action', 'retry-feed');
            retryBtn.textContent = 'Thử lại';
            retryBtn.addEventListener('click', function () {
                hideFeedError(d);
                if (typeof retryCallback === 'function') {
                    retryCallback();
                }
            });
            errEl.appendChild(retryBtn);
            errEl.removeAttribute('hidden');
        }

        const emptyMsgEl = d.getElementById('emptyFeedMessage');
        if (emptyMsgEl) {
            emptyMsgEl.setAttribute('hidden', '');
        }

        const loadMoreBtn = d.getElementById('communityLoadMoreBtn');
        if (loadMoreBtn) {
            loadMoreBtn.setAttribute('hidden', '');
        }
    }

    function hideFeedError(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;
        const errEl = d.getElementById('feedErrorMessage');
        if (errEl) {
            errEl.setAttribute('hidden', '');
        }
    }

    function setLoading(loading, doc) {
        isLoading = loading;
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        const spinner = d.getElementById('feedLoadingSpinner');
        const loadMoreBtn = d.getElementById('communityLoadMoreBtn');

        if (spinner) {
            if (loading) {
                spinner.removeAttribute('hidden');
            } else {
                spinner.setAttribute('hidden', '');
            }
        }

        if (loadMoreBtn) {
            if (loading) {
                loadMoreBtn.setAttribute('hidden', '');
            } else if (hasNext) {
                loadMoreBtn.removeAttribute('hidden');
            } else {
                loadMoreBtn.setAttribute('hidden', '');
            }
        }
    }

    function resetForTesting() {
        currentDoc = null;
        currentFeed = 'NEWEST';
        nextCursor = null;
        nextPage = null;
        hasNext = false;
        isLoading = false;
        currentFeedGeneration = 0;
        activeLoadingGeneration = 0;
        initialized = false;
    }

    if (typeof document !== 'undefined' && document.addEventListener) {
        document.addEventListener('DOMContentLoaded', function () {
            initFeed();
        });
    }

    return {
        init: initFeed,
        switchFeed: switchFeed,
        refreshFeed: refreshFeed,
        loadMorePosts: loadMorePosts,
        getCurrentFeed: function () { return currentFeed; },
        getCurrentGeneration: function () { return currentFeedGeneration; },
        getIsLoading: function () { return isLoading; },
        getHasNext: function () { return hasNext; },
        getNextCursor: function () { return nextCursor; },
        getNextPage: function () { return nextPage; },
        showFeedError: showFeedError,
        hideFeedError: hideFeedError,
        resetForTesting: resetForTesting
    };
});
