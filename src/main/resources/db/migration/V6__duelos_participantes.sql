-- =============================================================================
--  V6 · Fase 4 de la reestructuración: duelos
--
--  · duel_participants: una fila por jugador (máximo dos: challenger y
--    opponent). Cada uno juega un intento (attempts) sobre el quiz del duelo,
--    así que sus respuestas quedan guardadas igual que las de un quiz normal.
--  · El puntaje y el tiempo de cada jugador ya no se guardan en duels: salen
--    de su intento (vista v_duels).
--  · duels.winner_id se mantiene: es el resultado oficial que decide el
--    servidor al terminar (incluye abandonos y desconexiones).
--  · Duelos anteriores: no tienen intentos, así que su puntaje y tiempo quedan
--    en legacy_score / legacy_time_secs del participante.
--
--  duel_answers queda sin uso hasta la limpieza final.
--  Sin begin/commit ni tablas temporales (SQL Editor de Supabase).
-- =============================================================================

create table duel_participants (
    duel_id          uuid    not null references duels (id) on delete cascade,
    user_id          uuid    not null references users (id) on delete cascade,
    role             text    not null check (role in ('challenger', 'opponent')),
    attempt_id       uuid    unique references attempts (id) on delete set null,
    xp_awarded       integer not null default 0,
    coins_awarded    integer not null default 0 check (coins_awarded >= 0),
    legacy_score     integer check (legacy_score >= 0),          -- solo duelos anteriores a la V6
    legacy_time_secs integer check (legacy_time_secs >= 0),      -- solo duelos anteriores a la V6
    primary key (duel_id, user_id),
    unique (duel_id, role)
);
create index on duel_participants (user_id);

-- El intento de un jugador tiene que ser suyo y sobre el quiz de ese duelo.
create function check_duel_attempt() returns trigger
language plpgsql as $$
begin
    if new.attempt_id is not null and not exists (
        select 1
        from attempts a
        join duels d on d.content_id = a.content_id
        where a.id = new.attempt_id and d.id = new.duel_id and a.user_id = new.user_id) then
        raise exception 'El intento % no corresponde al duelo % ni al jugador %',
            new.attempt_id, new.duel_id, new.user_id
            using errcode = 'foreign_key_violation';
    end if;
    return new;
end $$;

create trigger duel_participants_check_attempt
    before insert or update on duel_participants
    for each row execute function check_duel_attempt();

-- ── Migración ──────────────────────────────────────────────────────────────

insert into duel_participants (duel_id, user_id, role, legacy_score, legacy_time_secs)
select id, challenger_id, 'challenger',
       case when challenger_score is not null then greatest(0, round(challenger_score))::int end,
       case when challenger_score is not null then greatest(0, coalesce(challenger_time, 0)) end
from duels
union all
select id, opponent_id, 'opponent',
       case when opponent_score is not null then greatest(0, round(opponent_score))::int end,
       case when opponent_score is not null then greatest(0, coalesce(opponent_time, 0)) end
from duels;

-- Las columnas por jugador y la cantidad de preguntas ya no van en duels.
alter table duels
    drop column challenger_id,
    drop column opponent_id,
    drop column challenger_score,
    drop column opponent_score,
    drop column challenger_time,
    drop column opponent_time,
    drop column question_count;

alter table duels alter column created_at type timestamptz using created_at at time zone 'UTC';
alter table duels alter column finished_at type timestamptz using finished_at at time zone 'UTC';

-- ── Vista con la forma que usa la app ──────────────────────────────────────
-- Un jugador "ya jugó" si tiene intento o (en duelos viejos) puntaje legado.
-- score = respuestas correctas; time = segundos que duró su intento.

create view v_duels as
with p as (
    select dp.duel_id, dp.user_id, dp.role,
           case when dp.attempt_id is not null then coalesce(s.correct_items, 0)::int
                else dp.legacy_score end                                         as score,
           case when dp.attempt_id is not null
                then extract(epoch from a.finished_at - a.started_at)::int
                else dp.legacy_time_secs end                                     as time_secs
    from duel_participants dp
    left join attempts a         on a.id = dp.attempt_id
    left join v_attempt_scores s on s.attempt_id = dp.attempt_id
)
select d.id, d.content_id, d.status, d.topic, d.time_per_question, d.winner_id,
       d.created_at, d.finished_at,
       (select count(*) from content_items ci where ci.content_id = d.content_id)::int as question_count,
       c.user_id   as challenger_id,
       o.user_id   as opponent_id,
       c.score     as challenger_score,
       o.score     as opponent_score,
       c.time_secs as challenger_time,
       o.time_secs as opponent_time
from duels d
join p c on c.duel_id = d.id and c.role = 'challenger'
join p o on o.duel_id = d.id and o.role = 'opponent';

alter table duel_participants enable row level security;
