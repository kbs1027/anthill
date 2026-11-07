create table instrument (id text primary key, source text not null, symbol text not null,
  korean_name text not null, english_name text not null, active boolean not null, updated_at timestamptz not null);
create table candle (instrument_id text not null, unit text not null, start_at timestamptz not null,
  open numeric not null, high numeric not null, low numeric not null, close numeric not null,
  volume numeric not null, amount numeric not null, primary key (instrument_id, unit, start_at));
create table coverage (instrument_id text not null, unit text not null, from_at timestamptz not null,
  to_at timestamptz not null, primary key (instrument_id, unit, from_at), check (from_at < to_at));
create table sync_correction (id bigserial primary key, instrument_id text not null, unit text not null,
  start_at timestamptz not null, kind text not null, before jsonb, after jsonb, detected_at timestamptz not null);
create table outage (id bigserial primary key, source text not null, from_at timestamptz not null, to_at timestamptz);
