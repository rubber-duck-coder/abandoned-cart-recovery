alter table recovery_attempt
  add column if not exists lease_until timestamptz,
  add column if not exists dispatched_at timestamptz;

create index if not exists idx_recovery_attempt_status_lease
  on recovery_attempt (status, lease_until, scheduled_at);
