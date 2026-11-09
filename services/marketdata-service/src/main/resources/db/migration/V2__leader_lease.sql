-- marketdata 주 서버 임차권. 시각은 DB 시계만 쓴다(서버끼리 시계가 달라도 판정이 흔들리지 않게).
create table leader_lease (name text primary key, holder text not null, until_at timestamptz not null);
