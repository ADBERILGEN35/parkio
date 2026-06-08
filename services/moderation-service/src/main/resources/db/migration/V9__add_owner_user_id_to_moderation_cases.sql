-- Store the owner of the targeted entity (spot owner for PARKING_SPOT cases, user for
-- USER cases) when known, so the moderator-rejection event can carry ownerUserId for
-- downstream owner penalties/notifications. Nullable: cases opened from a user report or
-- an AI/media signal don't know the owner. External reference (authUserId) — no
-- cross-service FK (ai-context/03). Forward-only.
ALTER TABLE moderation_cases ADD COLUMN owner_user_id UUID;
