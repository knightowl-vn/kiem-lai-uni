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
 */
(function () {
    'use strict';

    const PAGE_SIZE = 20;

    let currentFeed = 'NEWEST';
    let nextCursor = null;
    let nextPage = null;
    let hasNext = false;
    let isLoading = false;

    document.addEventListener('DOMContentLoaded', initFeed);

    function initFeed() {
        const feedListEl = document.getElementById('communityFeedList');
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
        const sortContainer = document.getElementById('communityFeedSortDropdown');
        const sortTrigger = document.getElementById('communityFeedSortTrigger');
        const sortMenu = document.getElementById('communityFeedSortMenu');

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

        if (sortTrigger) {
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

        if (sortMenu) {
            const sortItems = sortMenu.querySelectorAll('[data-action="change-feed-sort"]');
            sortItems.forEach(function (btn) {
                btn.addEventListener('click', function (e) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    const feedType = btn.getAttribute('data-feed');
                    closeFeedSort(false);
                    if (feedType && feedType !== currentFeed && !isLoading) {
                        switchFeed(feedType);
                    }
                });
            });
        }

        document.addEventListener('click', function (e) {
            if (sortContainer && !sortContainer.contains(e.target)) {
                closeFeedSort(false);
            }
        });

        document.addEventListener('keydown', function (e) {
            if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
                closeFeedSort(true);
            }
        });

        // Bind Load More button
        const loadMoreBtn = document.getElementById('communityLoadMoreBtn');
        if (loadMoreBtn) {
            loadMoreBtn.addEventListener('click', function () {
                if (!isLoading && hasNext) {
                    loadMorePosts();
                }
            });
        }

        // Expose public API
        window.CommunityFeed = {
            refreshFeed: function (feedType) {
                switchFeed(feedType || currentFeed);
            },
            switchFeed: switchFeed,
            getCurrentFeed: function () {
                return currentFeed;
            },
            init: initFeed
        };
    }

    function switchFeed(feedType) {
        currentFeed = feedType;
        nextCursor = null;
        nextPage = (feedType === 'FEATURED') ? 0 : null;
        hasNext = false;

        updateFeedSortUi(feedType);
        updateBrowserUrl(feedType);

        // Clear existing cards
        const feedListEl = document.getElementById('communityFeedList');
        if (feedListEl) {
            // Keep emptyFeedMessage element, remove article cards
            const articles = feedListEl.querySelectorAll('.community-post-card');
            articles.forEach(function (el) {
                el.remove();
            });
        }

        fetchFeedPage(true);
    }

    function updateFeedSortUi(feedType) {
        const sortLabel = document.getElementById('communityFeedSortLabel');
        if (sortLabel) {
            sortLabel.textContent = (feedType === 'FEATURED') ? 'Nổi bật' : 'Mới nhất';
        }
        const sortMenu = document.getElementById('communityFeedSortMenu');
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

    function updateTabUi(feedType) {
        updateFeedSortUi(feedType);
    }

    function updateBrowserUrl(feedType) {
        if (window.history && window.history.pushState) {
            const newUrl = '/community?feed=' + feedType;
            window.history.pushState({ feed: feedType }, '', newUrl);
        }
    }

    function fetchFeedPage(isInitial) {
        if (isLoading) {
            return;
        }

        setLoading(true);

        let url = '/api/community/posts?feed=' + encodeURIComponent(currentFeed) + '&size=' + PAGE_SIZE;
        if (!isInitial) {
            if (currentFeed === 'NEWEST' && nextCursor) {
                url += '&cursor=' + encodeURIComponent(nextCursor);
            } else if (currentFeed === 'FEATURED' && nextPage != null) {
                url += '&page=' + encodeURIComponent(nextPage);
            }
        }

        fetch(url)
            .then(function (res) {
                if (!res.ok) {
                    throw new Error('Không thể tải bài viết');
                }
                return res.json();
            })
            .then(function (data) {
                renderFeedData(data, isInitial);
            })
            .catch(function (err) {
                console.error('Error fetching feed:', err);
            })
            .finally(function () {
                setLoading(false);
            });
    }

    function loadMorePosts() {
        fetchFeedPage(false);
    }

    function renderFeedData(data, isInitial) {
        const feedListEl = document.getElementById('communityFeedList');
        const emptyMsgEl = document.getElementById('emptyFeedMessage');
        const loadMoreContainer = document.querySelector('.community-load-more-container');

        if (!feedListEl) {
            return;
        }

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

        items.forEach(function (item) {
            const card = createPostCardElement(item);
            if (emptyMsgEl) {
                feedListEl.insertBefore(card, emptyMsgEl);
            } else {
                feedListEl.appendChild(card);
            }

            // Format relative time if engine available
            if (window.RelativeTime && typeof window.RelativeTime.formatTree === 'function') {
                window.RelativeTime.formatTree(card);
            }

            // Hydrate reaction widget if engine available
            if (window.InteractionReactions && typeof window.InteractionReactions.hydrate === 'function') {
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

    function createPostCardElement(item) {
        if (window.CommunityPostCard && typeof window.CommunityPostCard.create === 'function') {
            const feedListEl = document.getElementById('communityFeedList');
            const isAuth = feedListEl ? feedListEl.getAttribute('data-authenticated') === 'true' : false;
            return window.CommunityPostCard.create(item, { isAuthenticated: isAuth });
        }
        throw new Error('CommunityPostCard module is required to render post cards.');
    }

    function setLoading(loading) {
        isLoading = loading;
        const spinner = document.getElementById('feedLoadingSpinner');
        const loadMoreBtn = document.getElementById('communityLoadMoreBtn');

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
            }
        }
    }
})();
