-- Passkey (WebAuthn) milik pengguna: kredensial public-key untuk login biometrik
-- (fingerprint/Face ID/Windows Hello). Resident key, sehingga login bisa tanpa isi
-- email; password tetap tersedia sebagai cadangan.
create table passkeys (
  id              uuid primary key,
  user_id         uuid not null references users(id) on delete cascade,
  credential_id   bytea not null unique,           -- id kredensial dari authenticator
  public_key      bytea not null,                  -- kunci publik COSE (CBOR terkomoditi)
  alg             integer not null,                -- algoritma COSE (mis. -7 = ES256)
  transports      text not null default '',        -- dipisah koma: internal,hybrid,...
  signature_count bigint not null default 0,
  uvi             bytea,                           -- user-verification-initialized (opsional)
  backup_eligible boolean not null default false,  -- boleh di-backup (sinkron antar perangkat)
  backup_state    boolean not null default false,  -- sedang dibackup saat registrasi
  discoverable    boolean not null default true,   -- passkey resident (bisa login tanpa email)
  label           text not null default '' check (char_length(label) <= 50),
  last_used_at    timestamptz,
  created_at      timestamptz not null default now()
);

create index passkeys_user_idx on passkeys (user_id);
