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
        const article = document.createElement('article');
        article.className = 'community-post-card';
        article.setAttribute('data-post-id', item.id);

        // Header
        const header = document.createElement('header');
        header.className = 'post-header';

        const authorInfo = document.createElement('div');
        authorInfo.className = 'post-author-info';

        const defaultAvatar = '/images/default_avatar.jpg';
        const hasHandle = item.authorPublicHandle && item.authorPublicHandle.trim().length > 0;
        const avatarUrl = (item.authorAvatarUrl && item.authorAvatarUrl.trim().length > 0)
            ? item.authorAvatarUrl
            : defaultAvatar;
        const displayName = (item.authorDisplayName && item.authorDisplayName.trim().length > 0)
            ? item.authorDisplayName
            : 'Người dùng';

        const avatarImg = document.createElement('img');
        avatarImg.className = 'post-author-avatar';
        avatarImg.src = avatarUrl;
        avatarImg.alt = 'Ảnh đại diện';
        avatarImg.onerror = function () {
            this.onerror = null;
            this.src = defaultAvatar;
        };

        if (hasHandle) {
            const avatarLink = document.createElement('a');
            avatarLink.className = 'post-author-avatar-link';
            avatarLink.href = '/community/@' + encodeURIComponent(item.authorPublicHandle);
            avatarLink.appendChild(avatarImg);
            authorInfo.appendChild(avatarLink);
        } else {
            authorInfo.appendChild(avatarImg);
        }

        const postMeta = document.createElement('div');
        postMeta.className = 'post-meta';

        const authorRow = document.createElement('div');
        authorRow.className = 'post-author-row';

        if (hasHandle) {
            const authorNameLink = document.createElement('a');
            authorNameLink.className = 'post-author-name';
            authorNameLink.href = '/community/@' + encodeURIComponent(item.authorPublicHandle);
            authorNameLink.textContent = displayName;
            authorRow.appendChild(authorNameLink);

            const handleSpan = document.createElement('span');
            handleSpan.className = 'post-author-handle';
            handleSpan.textContent = '@' + item.authorPublicHandle;
            authorRow.appendChild(handleSpan);
        } else {
            const authorNameSpan = document.createElement('span');
            authorNameSpan.className = 'post-author-name';
            authorNameSpan.textContent = displayName;
            authorRow.appendChild(authorNameSpan);
        }

        const timeEl = document.createElement('time');
        timeEl.className = 'post-time';
        timeEl.setAttribute('datetime', item.createdAt);
        timeEl.setAttribute('data-relative-time', '');
        timeEl.textContent = item.createdAt;

        postMeta.appendChild(authorRow);
        postMeta.appendChild(timeEl);
        authorInfo.appendChild(postMeta);
        header.appendChild(authorInfo);
        article.appendChild(header);

        // Caption (XSS-safe via textContent)
        const captionP = document.createElement('p');
        captionP.className = 'post-caption';
        captionP.textContent = item.caption;
        article.appendChild(captionP);

        // Image attachment
        if (item.imageUrl && item.imageUrl.trim().length > 0) {
            const imgContainer = document.createElement('div');
            imgContainer.className = 'post-image-container';

            const postImg = document.createElement('img');
            postImg.className = 'post-image';
            postImg.src = item.imageUrl;
            postImg.alt = 'Ảnh đính kèm';
            postImg.loading = 'lazy';

            imgContainer.appendChild(postImg);
            article.appendChild(imgContainer);
        }

        // Footer metrics
        const footer = document.createElement('footer');
        footer.className = 'post-footer';

        const likeMetric = document.createElement('span');
        likeMetric.className = 'post-metric';
        const likeIcon = document.createElement('i');
        likeIcon.className = 'fa-regular fa-thumbs-up me-1';
        likeMetric.appendChild(likeIcon);
        const likeCountSpan = document.createElement('span');
        likeCountSpan.textContent = item.reactionCount || 0;
        likeMetric.appendChild(likeCountSpan);
        likeMetric.appendChild(document.createTextNode(' lượt thích'));

        const commentMetric = document.createElement('span');
        commentMetric.className = 'post-metric';
        const commentIcon = document.createElement('i');
        commentIcon.className = 'fa-regular fa-comment me-1';
        commentMetric.appendChild(commentIcon);
        const commentCountSpan = document.createElement('span');
        commentCountSpan.textContent = item.commentCount || 0;
        commentMetric.appendChild(commentCountSpan);
        commentMetric.appendChild(document.createTextNode(' bình luận'));

        footer.appendChild(likeMetric);
        footer.appendChild(commentMetric);
        article.appendChild(footer);

        return article;
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
