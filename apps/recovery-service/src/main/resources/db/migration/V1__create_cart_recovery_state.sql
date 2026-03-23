create table if not exists cart_recovery_state (
  cart_id text primary key,
  tenant_id text not null,
  anonymous_id text,
  user_id text,
  cart_status text not null,
  abandonment_status text not null,
  policy_id text,
  policy_version integer,
  last_cart_mutation_at timestamptz,
  last_critical_event_at timestamptz,
  last_purchase_at timestamptz,
  state_version bigint not null,
  cart_snapshot_json jsonb not null,
  stitched_identity_json jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_cart_recovery_state_tenant_updated
  on cart_recovery_state (tenant_id, updated_at desc);

create index if not exists idx_cart_recovery_state_user
  on cart_recovery_state (user_id)
  where user_id is not null;

