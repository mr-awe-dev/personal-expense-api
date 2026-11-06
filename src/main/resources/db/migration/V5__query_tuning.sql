-- ============================================================================
-- Tuning query: indeks partisi ulang berjalan & pencarian ILIKE
-- ============================================================================

-- 1) Pencarian transaksi arsip untuk repo/DELETE data arsip.
--    Indeks lama `tx_user_closing (user_id, closing_id)` tidak dipakai karena
--    query repo/DELETE saring hanya `closing_id` (kolom utama user_id di depan).
create index tx_closing_open
  on transactions (closing_id)
  where closing_id is not null;

-- 2) Pencarian deskripsi dengan ILIKE '%kata%' tidak bisa memanfaatkan indeks
--    btree biasa; GIN trigram mempercepat pola di tengah/akhir kata.
create extension if not exists pg_trgm;
create index tx_desc_trgm on transactions using gin (description gin_trgm_ops);
