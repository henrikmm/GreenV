-- The rodovia and the sentido a capture was driven along.
--
-- Nullable with no default: every session recorded before the capture app asked for them stays
-- honestly empty, and an empty value must never read as a real road. V4 and V5 belong to an
-- unmerged dashboard branch, so this numbers past them.
ALTER TABLE capture_sessions ADD COLUMN rodovia VARCHAR(32);
ALTER TABLE capture_sessions ADD COLUMN sentido VARCHAR(16);

-- Four values and no others, enforced where the data lives as well as at the HTTP edge: a second
-- spelling of one direction silently becomes a second road on the dashboard.
ALTER TABLE capture_sessions ADD CONSTRAINT capture_sessions_sentido_vocabulary
    CHECK (sentido IS NULL OR sentido IN ('norte', 'sul', 'leste', 'oeste'));
