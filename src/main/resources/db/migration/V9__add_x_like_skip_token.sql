-- Identity of the skip that put a row in SKIPPED: a fresh UUID per skip request, cleared when the row is put back.
-- An undo names the skip it belongs to, so it never takes back a later skip of the same row or another window's skip,
-- even within the same millisecond. Rows skipped before this version keep NULL and can only be put back by hand.
ALTER TABLE x_like_item ADD COLUMN skip_token CHAR(36) NULL;
