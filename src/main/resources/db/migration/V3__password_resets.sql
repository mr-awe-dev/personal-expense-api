-- Kode pemulihan password satu-kali. Diterbitkan lewat CLI (issue-reset-code),
-- disimpan sebagai SHA-256 hex dari kode mentah; dipakai lewat POST /auth/reset-password.
create table password_resets (
  id         uuid primary key,
  user_id    uuid not null references users(id) on delete cascade,
  token_hash text not null,
  expires_at timestamptz not null,
  used_at    timestamptz,
  created_at timestamptz not null default now()
);

create index password_resets_user_idx   on password_resets (user_id);
create index password_resets_expiry_idx on password_resets (expires_at);
