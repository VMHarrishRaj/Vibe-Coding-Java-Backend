-- V24: Add engine description and towing capacity to trucks table
ALTER TABLE trucks ADD COLUMN IF NOT EXISTS engine VARCHAR(255);
ALTER TABLE trucks ADD COLUMN IF NOT EXISTS towing_capacity VARCHAR(100);
