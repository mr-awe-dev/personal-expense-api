-- ============================================================================
-- Personal Expense Tracker — core schema (spesifikasi v5, bagian 6)
-- ============================================================================
create extension if not exists citext;

create type tx_type       as enum ('income', 'expense');
create type budget_period as enum ('daily', 'monthly');
create type user_role     as enum ('owner');            -- tambah nilai saat multi-role

create table users (
  id            uuid primary key,
  email         citext not null unique,
  password_hash text   not null,
  role          user_role not null default 'owner',
  display_name  text,
  timezone      text   not null default 'Asia/Jakarta',
  daily_limit   bigint check (daily_limit >= 0),
  disabled_at   timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create table categories (                      -- expense: kategori; income: sumber pemasukan
  id         uuid primary key,
  user_id    uuid not null references users(id) on delete cascade,
  type       tx_type not null,
  name       text not null check (char_length(name) between 1 and 50),
  name_key   text not null,                    -- lower(trim(name))
  archived   boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, type, name_key),
  unique (id, type)                            -- untuk FK komposit di transactions
);

create table budgets (                         -- hanya kategori bertipe expense (dicek di service)
  id          uuid primary key,
  user_id     uuid not null references users(id) on delete cascade,
  category_id uuid not null references categories(id),
  period      budget_period not null,
  amount      bigint not null check (amount >= 0),
  description text not null default '' check (char_length(description) <= 200),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (user_id, category_id)
);

create table closings (
  id            uuid primary key,
  user_id       uuid not null references users(id) on delete cascade,
  period_start  date not null,
  period_end    date not null,
  total_income  bigint not null,
  total_expense bigint not null,
  net           bigint not null,
  tx_count      integer not null,
  file_name     text not null,
  closed_at     timestamptz not null default now(),
  check (period_start <= period_end)
);

create table transactions (
  id          uuid primary key,                -- dibuat klien → idempotent
  user_id     uuid not null references users(id) on delete cascade,
  date        date not null,
  type        tx_type not null,
  category_id uuid,
  description text not null default '' check (char_length(description) <= 200),
  amount      bigint not null check (amount > 0 and amount <= 999999999999),
  closing_id  uuid references closings(id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  foreign key (category_id, type) references categories (id, type),   -- tipe harus cocok
  check (type = 'expense' or category_id is not null)                 -- income wajib punya sumber
);

-- indeks utama: hanya transaksi berjalan (belum ditutup)
create index tx_user_date   on transactions (user_id, date desc, created_at desc, id) where closing_id is null;
create index tx_user_type   on transactions (user_id, type, date)                     where closing_id is null;
create index tx_user_cat    on transactions (user_id, category_id, date)              where closing_id is null;
create index tx_user_closing on transactions (user_id, closing_id);
