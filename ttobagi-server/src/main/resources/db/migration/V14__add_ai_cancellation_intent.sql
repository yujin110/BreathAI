-- Persist cancellation intent separately from the public analysis status.
ALTER TABLE gold_analysis_job
  ADD COLUMN cancel_requested_at datetime NULL,
  ADD COLUMN ai_submission_state varchar(20) NOT NULL DEFAULT 'UNKNOWN';
