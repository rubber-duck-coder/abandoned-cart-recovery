create table if not exists recovery_attempt (
  attempt_id text primary key,
  cart_id text not null,
  tenant_id text not null,
  user_id text,
  experiment_id text,
  experiment_name text,
  variant_id text,
  policy_id text not null,
  policy_version integer not null,
  touch_index integer not null,
  scheduled_at timestamptz not null,
  executed_at timestamptz,
  channel text not null,
  template_key text not null,
  status text not null,
  suppression_reason text,
  send_idempotency_key text not null,
  frequency_cap_result text,
  provider_result_json jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists uq_recovery_attempt_schedule
  on recovery_attempt (cart_id, policy_version, touch_index, scheduled_at);

create index if not exists idx_recovery_attempt_status_scheduled
  on recovery_attempt (status, scheduled_at);

create index if not exists idx_recovery_attempt_experiment_variant
  on recovery_attempt (experiment_id, variant_id, scheduled_at);

create index if not exists idx_recovery_attempt_cart_scheduled
  on recovery_attempt (cart_id, scheduled_at);

