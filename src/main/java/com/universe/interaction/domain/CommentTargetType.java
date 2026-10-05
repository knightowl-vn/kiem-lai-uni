package com.universe.interaction.domain;

/**
 * Supported target types for generic interaction comments.
 *
 * <p>Restricted to approved bounded context targets in MS-05E2:
 * <ul>
 *   <li>{@link #NOVEL_CHAPTER} - A chapter in the Novel bounded context</li>
 *   <li>{@link #WIKI_ARTICLE} - An article in the Wiki bounded context</li>
 *   <li>{@link #COMMUNITY_POST} - A post in the Community bounded context</li>
 * </ul>
 */
public enum CommentTargetType {
    NOVEL_CHAPTER,
    WIKI_ARTICLE,
    COMMUNITY_POST
}
