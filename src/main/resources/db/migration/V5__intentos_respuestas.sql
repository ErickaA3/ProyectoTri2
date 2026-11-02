-- =============================================================================
--  V5 · Fase 3 de la reestructuración: preguntas, intentos y respuestas
--
--  · content_items + item_options: cada pregunta de un quiz/examen y cada
--    tarjeta de un mazo es una fila. Se extraen del JSON de generated_content.
--  · attempts: cada vez que alguien estudia un contenido (todos los intentos,
--    no solo el primero). Guarda lo que se pagó en XP y monedas.
--  · attempt_answers: una fila por pregunta o tarjeta presentada en el intento.
--    La corrección no se guarda: sale de item_options.is_correct.
--  · v_attempt_scores: el puntaje de cada intento, calculado.
--
--  generated_content.body se conserva: las pantallas lo siguen usando para
--  dibujar. Las filas de content_items son las que se usan para calificar.
--
--  activity_results se migra a attempts (sin respuestas, porque no se
--  guardaban: su porcentaje queda en legacy_score_pct). activity_results NO se
--  borra acá: queda sin uso hasta la limpieza final.
--
--  Sin begin/commit ni tablas temporales: el SQL Editor de Supabase puede
--  ejecutar cada sentencia en una conexión distinta.
-- =============================================================================

-- ── Preguntas y tarjetas ───────────────────────────────────────────────────

create table content_items (
    id          uuid primary key default gen_random_uuid(),
    content_id  uuid    not null references generated_content (id) on delete cascade,
    position    integer not null check (position >= 0),
    prompt      text    not null,          -- pregunta / frente de la tarjeta
    answer      text,                      -- reverso de la tarjeta / explicación
    unique (content_id, position),
    unique (content_id, id)
);

create table item_options (
    id          uuid primary key default gen_random_uuid(),
    item_id     uuid    not null references content_items (id) on delete cascade,
    position    integer not null check (position >= 0),
    text        text    not null,
    is_correct  boolean not null,
    unique (item_id, position),
    unique (item_id, id)
);
create unique index item_options_one_correct on item_options (item_id) where is_correct;

-- ── Intentos y respuestas ──────────────────────────────────────────────────

create table attempts (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid        not null references users (id) on delete cascade,
    content_id       uuid        not null references generated_content (id) on delete cascade,
    status           text        not null
                     check (status in ('in_progress', 'completed', 'abandoned', 'expired')),
    started_at       timestamptz not null default now(),
    finished_at      timestamptz,
    xp_awarded       integer     not null default 0,
    coins_awarded    integer     not null default 0 check (coins_awarded >= 0),
    -- Solo intentos migrados de activity_results, que no tienen respuestas
    -- guardadas: el único dato de su puntaje es este porcentaje.
    legacy_score_pct numeric(5,2) check (legacy_score_pct between 0 and 100),
    check ((status = 'in_progress') = (finished_at is null)),
    check (finished_at is null or finished_at >= started_at)
);
create index on attempts (user_id, finished_at desc);
create index on attempts (content_id);

-- Una fila por pregunta o tarjeta presentada. selected_option_id para preguntas
-- con opciones, recall_rating (1 = no me acordé … 4 = fácil) para flashcards.
-- Las dos en null = no la respondió (se acabó el tiempo o la saltó).
create table attempt_answers (
    attempt_id         uuid        not null references attempts (id) on delete cascade,
    item_id            uuid        not null references content_items (id) on delete cascade,
    selected_option_id uuid,
    recall_rating      smallint    check (recall_rating between 1 and 4),
    response_ms        integer     check (response_ms >= 0),
    answered_at        timestamptz not null default now(),
    primary key (attempt_id, item_id),
    foreign key (item_id, selected_option_id) references item_options (item_id, id) on delete cascade,
    check (num_nonnulls(selected_option_id, recall_rating) <= 1)
);
create index on attempt_answers (item_id);
create index on attempt_answers (selected_option_id);

-- Una respuesta solo puede ser de un ítem del contenido de ese intento.
create function check_answer_item() returns trigger
language plpgsql as $$
begin
    if not exists (select 1
                   from attempts a
                   join content_items i on i.content_id = a.content_id
                   where a.id = new.attempt_id and i.id = new.item_id) then
        raise exception 'El ítem % no pertenece al contenido del intento %', new.item_id, new.attempt_id
            using errcode = 'foreign_key_violation';
    end if;
    return new;
end $$;

create trigger attempt_answers_check_item
    before insert or update on attempt_answers
    for each row execute function check_answer_item();

-- ── Puntaje de cada intento (derivado) ─────────────────────────────────────

create view v_attempt_scores as
select a.id                                                           as attempt_id,
       count(aa.item_id)                                              as total_items,
       count(*) filter (where o.is_correct or aa.recall_rating >= 3)  as correct_items,
       coalesce(
           round(100.0 * count(*) filter (where o.is_correct or aa.recall_rating >= 3)
                 / nullif(count(aa.item_id), 0), 2),
           a.legacy_score_pct)                                        as score_pct
from attempts a
left join attempt_answers aa on aa.attempt_id = a.id
left join item_options o     on o.id = aa.selected_option_id
group by a.id, a.legacy_score_pct;

-- ── Migración: preguntas y tarjetas desde el JSON ──────────────────────────

insert into content_items (content_id, position, prompt, answer)
select gc.id, q.ord - 1,
       coalesce(q.value ->> 'question', ''),
       q.value ->> 'explanation'
from generated_content gc
cross join lateral jsonb_array_elements(
    case when jsonb_typeof(gc.body -> 'questions') = 'array' then gc.body -> 'questions' else '[]'::jsonb end
) with ordinality as q(value, ord)
where gc.content_type_code in ('quiz', 'expert_exam', 'duel_quiz');

insert into item_options (item_id, position, text, is_correct)
select ci.id, o.ord - 1, o.value #>> '{}',
       -- Si "correct" no es un número, ninguna opción queda como correcta.
       coalesce((o.ord - 1) = case when (q.value ->> 'correct') ~ '^\d+$'
                                   then (q.value ->> 'correct')::int end, false)
from generated_content gc
cross join lateral jsonb_array_elements(
    case when jsonb_typeof(gc.body -> 'questions') = 'array' then gc.body -> 'questions' else '[]'::jsonb end
) with ordinality as q(value, ord)
join content_items ci on ci.content_id = gc.id and ci.position = q.ord - 1
cross join lateral jsonb_array_elements(
    case when jsonb_typeof(q.value -> 'options') = 'array' then q.value -> 'options' else '[]'::jsonb end
) with ordinality as o(value, ord)
where gc.content_type_code in ('quiz', 'expert_exam', 'duel_quiz');

insert into content_items (content_id, position, prompt, answer)
select gc.id, c.ord - 1,
       coalesce(c.value ->> 'front', ''),
       c.value ->> 'back'
from generated_content gc
cross join lateral jsonb_array_elements(
    case when jsonb_typeof(gc.body -> 'cards') = 'array' then gc.body -> 'cards' else '[]'::jsonb end
) with ordinality as c(value, ord)
where gc.content_type_code = 'flashcard';

-- ── Migración: activity_results → attempts ─────────────────────────────────

insert into attempts (user_id, content_id, status, started_at, finished_at, legacy_score_pct)
select ar.user_id,
       ar.content_id,
       'completed',
       coalesce(ar.completed_at at time zone 'UTC', now())
           - make_interval(secs => greatest(coalesce(ar.time_taken_seconds, 0), 0)),
       coalesce(ar.completed_at at time zone 'UTC', now()),
       case when ar.max_score > 0
            then least(100, greatest(0, round(100.0 * ar.score / ar.max_score, 2))) end
from activity_results ar
where ar.content_id is not null;

-- ── RLS ─────────────────────────────────────────────────────────────────────

alter table content_items   enable row level security;
alter table item_options    enable row level security;
alter table attempts        enable row level security;
alter table attempt_answers enable row level security;
