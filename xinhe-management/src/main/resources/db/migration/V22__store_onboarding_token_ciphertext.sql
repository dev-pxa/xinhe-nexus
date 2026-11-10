ALTER TABLE hr_onboarding_invitation
    ADD COLUMN token_ciphertext TEXT NULL AFTER token_hash;
