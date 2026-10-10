-- 0010 (2026-10-10): automatic controller-token reissue for an already-activated car that lost its
-- token (reinstall, UI5->UI6 format) and never bound a hardware fingerprint.
--
-- A reissue on the strength of the public hardware id alone cannot tell the real car from someone
-- who copied its id, so the token it replaces is NOT killed at once: it stays valid for 30 days in
-- prev_token_hash. A stranger who triggers a reissue therefore cannot lock the real car out, and a
-- second reissue inside those 30 days keeps the ORIGINAL token there (it is never overwritten by
-- a token a reissue handed out). Only the automatic reissue writes these columns; a rotation by the
-- typed code or by a matching fingerprint (strong proofs) clears them.
ALTER TABLE device_tokens ADD COLUMN prev_token_hash TEXT;
ALTER TABLE device_tokens ADD COLUMN prev_valid_until TEXT;
