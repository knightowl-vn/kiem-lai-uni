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

        // Bind tab buttons
        const tabNewest = document.getElementById('tabNewest');
        const tabFeatured = document.getElementById('tabFeatured');

        if (tabNewest) {
            tabNewest.addEventListener('click', function () {
                if (currentFeed !== 'NEWEST' && !isLoading) {
                    switchFeed('NEWEST');
                }
            });
        }

        if (tabFeatured) {
            tabFeatured.addEventListener('click', function () {
                if (currentFeed !== 'FEATURED' && !isLoading) {
                    switchFeed('FEATURED');
                }
            });
        }

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
            }
        };
    }

    function switchFeed(feedType) {
        currentFeed = feedType;
        nextCursor = null;
        nextPage = (feedType === 'FEATURED') ? 0 : null;
        hasNext = false;

        updateTabUi(feedType);
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

    function updateTabUi(feedType) {
        const tabNewest = document.getElementById('tabNewest');
        const tabFeatured = document.getElementById('tabFeatured');

        if (tabNewest) {
            if (feedType === 'NEWEST') {
                tabNewest.classList.add('is-active');
                tabNewest.setAttribute('aria-selected', 'true');
            } else {
                tabNewest.classList.remove('is-active');
                tabNewest.setAttribute('aria-selected', 'false');
            }
        }

        if (tabFeatured) {
            if (feedType === 'FEATURED') {
                tabFeatured.classList.add('is-active');
                tabFeatured.setAttribute('aria-selected', 'true');
            } else {
                tabFeatured.classList.remove('is-active');
                tabFeatured.setAttribute('aria-selected', 'false');
            }
        }
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
