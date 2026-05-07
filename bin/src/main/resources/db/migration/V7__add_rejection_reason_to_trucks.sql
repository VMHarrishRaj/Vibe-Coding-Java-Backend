-- Phase 4.5 Fix C3: Persist truck rejection reason
ALTER TABLE trucks ADD COLUMN rejection_reason TEXT;
