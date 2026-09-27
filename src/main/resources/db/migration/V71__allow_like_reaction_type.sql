-- =========================================================
-- V71__allow_like_reaction_type.sql
--
-- Content Reactions: Add LIKE ReactionType (MS-05I)
-- Extends reaction_type check constraint to include LIKE as default reaction.
-- =========================================================

ALTER TABLE interaction_reactions
    DROP CHECK chk_interaction_reactions_type;

ALTER TABLE interaction_reactions
    ADD CONSTRAINT chk_interaction_reactions_type
    CHECK (reaction_type IN ('LIKE', 'LOVE', 'FIRE', 'HAHA', 'SAD'));
