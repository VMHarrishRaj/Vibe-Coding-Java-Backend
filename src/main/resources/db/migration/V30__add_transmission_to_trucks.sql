-- Add optional transmission field to trucks (e.g. "Automatic", "Manual", "Semi-Automatic")
ALTER TABLE trucks ADD COLUMN IF NOT EXISTS transmission VARCHAR(50);
