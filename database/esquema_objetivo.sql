-- =============================================================================
--  POLARIS · Esquema objetivo (3FN)
--  PostgreSQL 17 + pgvector 0.8 (Supabase)
--
--  Este archivo describe la base de datos a la que se quiere llegar.
--  NO es una migración: no se corre contra producción. Las migraciones
--  (Flyway, database/migrations/V*.sql) se escriben a partir de este diseño.
--  Las decisiones están explicadas en database/DISENO.md.
--
--  Convenciones
--    · Tablas en inglés, snake_case, plural. Columnas en snake_case.
--    · Llaves: uuid (gen_random_uuid) para entidades; text "code" para
--      catálogos; llaves compuestas para tablas de relación.
--    · Fechas y horas: timestamptz. Días "locales" (rachas, misiones):
--      date calculada con la zona horaria del usuario (user_profiles.time_zone).
--    · Nada derivable se guarda: puntajes, XP, monedas, nivel, racha,
--      progreso de misiones y ganador de un duelo salen de vistas (v_*).
--      La única excepción documentada es review_states (repaso espaciado).
-- =============================================================================

create extension if not exists vector;
create extension if not exists citext;


-- =============================================================================
--  1. CATÁLOGOS
-- =============================================================================

create table roles (
    code        text primary key,
    description text not null
);

create table languages (
    code text primary key check (code ~ '^[a-z]{2}$'),          -- ISO 639-1
    name text not null
);

create table countries (
    code char(2) primary key check (code ~ '^[A-Z]{2}$'),       -- ISO 3166-1 alfa-2
    name text not null
);

-- Lo que un estudiante hace y que puede dar recompensa o avanzar una misión.
create table activity_types (
    code        text primary key,
    description text    not null,
    base_xp     integer not null,
    base_coins  integer not null check (base_coins >= 0)
);

create table content_types (
    code                   text primary key,
    description            text    not null,
    is_answerable          boolean not null,          -- tiene ítems que se responden
    completion_activity    text    not null references activity_types (code)
);

create table item_types (
    code           text primary key,                  -- también es el "slot" al equipar
    description    text    not null,
    is_consumable  boolean not null,
    is_equippable  boolean not null,
    check (not (is_consumable and is_equippable))
);

create table levels (
    level   integer primary key check (level >= 1),
    min_xp  integer not null unique check (min_xp >= 0)
);


-- =============================================================================
--  2. USUARIOS
-- =============================================================================

create table users (
    id            uuid primary key default gen_random_uuid(),
    username      citext      not null unique check (username ~ '^[A-Za-z0-9_.]{3,30}$'),
    email         citext      not null unique check (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    password_hash text        not null,
    created_at    timestamptz not null default now()
);

create table user_roles (
    user_id   uuid not null references users (id) on delete cascade,
    role_code text not null references roles (code),
    primary key (user_id, role_code)
);

-- Antes: tabla "clientes". Relación 1:1 con users.
create table user_profiles (
    user_id       uuid primary key references users (id) on delete cascade,
    full_name     text,
    country_code  char(2) references countries (code),
    language_code text    not null default 'es' references languages (code),
    birthdate     date    check (birthdate > date '1900-01-01'),
    time_zone     text    not null default 'America/Costa_Rica'
);

-- Antes: professor_config. Lo visual (avatar, fondo) se maneja en user_equipped.
create table assistant_settings (
    user_id     uuid primary key references users (id) on delete cascade,
    name        text not null default 'Poli' check (length(name) between 1 and 50),
    personality text not null default 'friendly'
                check (personality in ('strict', 'friendly', 'funny'))
);


-- =============================================================================
--  3. MATERIAS, TEMAS Y DOCUMENTOS (carpetas + base del RAG)
-- =============================================================================

create table subjects (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid        not null references users (id) on delete cascade,
    name        text        not null check (length(name) between 1 and 100),
    color       text        check (color ~ '^#[0-9A-Fa-f]{6}$'),
    created_at  timestamptz not null default now(),
    archived_at timestamptz,
    unique (owner_id, name)
);

create table topics (
    id          uuid primary key default gen_random_uuid(),
    subject_id  uuid        not null references subjects (id) on delete cascade,
    name        text        not null check (length(name) between 1 and 100),
    position    integer     not null check (position >= 0),
    created_at  timestamptz not null default now(),
    archived_at timestamptz,
    unique (subject_id, name),
    unique (subject_id, position) deferrable initially deferred
);

create table documents (
    id           uuid primary key default gen_random_uuid(),
    topic_id     uuid        not null references topics (id) on delete cascade,
    uploaded_by  uuid        not null references users (id) on delete cascade,
    file_name    text        not null,
    mime_type    text        not null,
    storage_path text        unique,                  -- Supabase Storage; null si fue texto pegado
    created_at   timestamptz not null default now()
);

-- El texto completo vive en los fragmentos (no se repite en ningún otro lado).
create table document_chunks (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid    not null references documents (id) on delete cascade,
    chunk_index integer not null check (chunk_index >= 0),
    content     text    not null,
    token_count integer not null check (token_count > 0),
    embedding   vector(1536),                         -- null mientras se genera
    unique (document_id, chunk_index)
);


-- =============================================================================
--  4. CONTENIDO GENERADO
-- =============================================================================

-- Antes: study_content. topic_id es null solo para contenido fuera de la
-- biblioteca (el quiz de un duelo generado a partir de un tema escrito a mano).
create table generated_content (
    id                uuid primary key default gen_random_uuid(),
    topic_id          uuid        references topics (id) on delete cascade,
    created_by        uuid        not null references users (id) on delete cascade,
    content_type_code text        not null references content_types (code),
    title             text        not null check (length(title) between 1 and 200),
    body              jsonb,                          -- solo resúmenes y esquemas (documentos de lectura)
    created_at        timestamptz not null default now(),
    deleted_at        timestamptz                     -- borrado lógico: conserva intentos y duelos
);

-- De qué fragmentos salió cada contenido (trazabilidad y citas del profesor IA).
create table content_sources (
    content_id uuid not null references generated_content (id) on delete cascade,
    chunk_id   uuid not null references document_chunks (id) on delete cascade,
    primary key (content_id, chunk_id)
);

-- Cada pregunta de un quiz/examen o cada tarjeta de un mazo.
create table content_items (
    id          uuid primary key default gen_random_uuid(),
    content_id  uuid    not null references generated_content (id) on delete cascade,
    position    integer not null check (position >= 0),
    prompt      text    not null,                     -- pregunta / frente de la tarjeta
    answer      text,                                 -- reverso de la tarjeta / explicación
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

create table favorites (
    user_id    uuid        not null references users (id) on delete cascade,
    content_id uuid        not null references generated_content (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, content_id)
);


-- =============================================================================
--  5. INTENTOS Y RESPUESTAS (lo que alimenta progreso, repaso y profesor)
-- =============================================================================

-- Cada vez que alguien estudia un contenido: sesión de flashcards, quiz,
-- examen, lectura de un resumen o su parte en un duelo.
-- xp_awarded / coins_awarded: lo que se pagó en ese momento (las reglas
-- pueden cambiar después, por eso es un hecho y no un dato derivable).
create table attempts (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid        not null references users (id) on delete cascade,
    content_id         uuid        not null references generated_content (id) on delete cascade,
    status             text        not null default 'in_progress'
                       check (status in ('in_progress', 'completed', 'abandoned', 'expired')),
    time_limit_seconds integer     check (time_limit_seconds > 0),
    started_at         timestamptz not null default now(),
    finished_at        timestamptz,
    xp_awarded         integer     not null default 0,
    coins_awarded      integer     not null default 0 check (coins_awarded >= 0),
    check ((status = 'in_progress') = (finished_at is null)),
    check (finished_at is null or finished_at >= started_at)
);

-- Una fila por ítem respondido. selected_option_id para preguntas con
-- opciones; recall_rating (1 = no me acordé … 4 = fácil) para flashcards.
-- Las dos en null = se acabó el tiempo sin respuesta.
-- La corrección NO se guarda: sale de item_options.is_correct.
create table attempt_answers (
    attempt_id         uuid        not null references attempts (id) on delete cascade,
    item_id            uuid        not null,
    selected_option_id uuid,
    recall_rating      smallint    check (recall_rating between 1 and 4),
    response_ms        integer     not null check (response_ms >= 0),
    answered_at        timestamptz not null default now(),
    primary key (attempt_id, item_id),
    foreign key (item_id) references content_items (id) on delete cascade,
    foreign key (item_id, selected_option_id) references item_options (item_id, id) on delete cascade,
    check (num_nonnulls(selected_option_id, recall_rating) <= 1)
);

-- Estado del repaso espaciado por usuario e ítem.
-- Desnormalización deliberada: se podría recalcular desde attempt_answers,
-- pero el algoritmo es iterativo y se consulta en cada carga del dashboard.
create table review_states (
    user_id          uuid        not null references users (id) on delete cascade,
    item_id          uuid        not null references content_items (id) on delete cascade,
    due_on           date        not null,
    interval_days    integer     not null check (interval_days >= 0),
    ease_factor      numeric(4,2) not null default 2.50 check (ease_factor >= 1.30),
    repetitions      integer     not null default 0 check (repetitions >= 0),
    last_reviewed_at timestamptz not null,
    primary key (user_id, item_id)
);


-- =============================================================================
--  6. DUELOS
-- =============================================================================

-- Puntaje, tiempo y ganador NO se guardan: salen de los intentos (v_duel_results).
create table duels (
    id                uuid primary key default gen_random_uuid(),
    content_id        uuid        not null references generated_content (id) on delete cascade,
    seconds_per_item  integer     not null default 30 check (seconds_per_item between 5 and 300),
    status            text        not null default 'pending'
                      check (status in ('pending', 'in_progress', 'finished', 'declined', 'expired')),
    created_at        timestamptz not null default now(),
    finished_at       timestamptz,
    check ((status = 'finished') = (finished_at is not null))
);

create table duel_participants (
    duel_id       uuid    not null references duels (id) on delete cascade,
    user_id       uuid    not null references users (id) on delete cascade,
    role          text    not null check (role in ('challenger', 'opponent')),
    attempt_id    uuid    unique references attempts (id) on delete set null,
    xp_awarded    integer not null default 0,
    coins_awarded integer not null default 0 check (coins_awarded >= 0),
    primary key (duel_id, user_id),
    unique (duel_id, role)                            -- máximo dos jugadores
);


-- =============================================================================
--  7. SOCIAL: AMISTADES Y GRUPOS
-- =============================================================================

-- La pareja se guarda ordenada (user_low_id < user_high_id): así A→B y B→A
-- son la misma fila y no pueden duplicarse.
create table friendships (
    user_low_id  uuid        not null references users (id) on delete cascade,
    user_high_id uuid        not null references users (id) on delete cascade,
    requested_by uuid        not null references users (id) on delete cascade,
    status       text        not null default 'pending' check (status in ('pending', 'accepted')),
    created_at   timestamptz not null default now(),
    accepted_at  timestamptz,
    primary key (user_low_id, user_high_id),
    check (user_low_id < user_high_id),
    check (requested_by in (user_low_id, user_high_id)),
    check ((status = 'accepted') = (accepted_at is not null))
);

create table study_groups (
    id         uuid primary key default gen_random_uuid(),
    name       text        not null check (length(name) between 1 and 100),
    created_by uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null default now()
);

create table group_members (
    group_id  uuid        not null references study_groups (id) on delete cascade,
    user_id   uuid        not null references users (id) on delete cascade,
    role      text        not null default 'member' check (role in ('owner', 'member')),
    joined_at timestamptz not null default now(),
    primary key (group_id, user_id)
);

create table group_subjects (
    group_id   uuid not null references study_groups (id) on delete cascade,
    subject_id uuid not null references subjects (id) on delete cascade,
    primary key (group_id, subject_id)
);


-- =============================================================================
--  8. AULAS (profesor real)
-- =============================================================================

create table classrooms (
    id          uuid primary key default gen_random_uuid(),
    teacher_id  uuid        not null references users (id) on delete cascade,
    name        text        not null check (length(name) between 1 and 100),
    join_code   text        not null unique check (join_code ~ '^[A-Z0-9]{6,10}$'),
    created_at  timestamptz not null default now(),
    archived_at timestamptz
);

create table classroom_members (
    classroom_id uuid        not null references classrooms (id) on delete cascade,
    user_id      uuid        not null references users (id) on delete cascade,
    joined_at    timestamptz not null default now(),
    primary key (classroom_id, user_id)
);

-- Materias que el profesor comparte con el aula.
create table classroom_subjects (
    classroom_id uuid not null references classrooms (id) on delete cascade,
    subject_id   uuid not null references subjects (id) on delete cascade,
    primary key (classroom_id, subject_id)
);

create table assignments (
    id           uuid primary key default gen_random_uuid(),
    classroom_id uuid        not null references classrooms (id) on delete cascade,
    content_id   uuid        not null references generated_content (id) on delete cascade,
    title        text        not null check (length(title) between 1 and 200),
    instructions text,
    due_at       timestamptz,
    created_at   timestamptz not null default now()
);

-- Qué intentos cuentan como entrega de una asignación.
create table assignment_submissions (
    assignment_id uuid not null references assignments (id) on delete cascade,
    attempt_id    uuid not null unique references attempts (id) on delete cascade,
    primary key (assignment_id, attempt_id)
);

create table announcements (
    id           uuid primary key default gen_random_uuid(),
    classroom_id uuid        not null references classrooms (id) on delete cascade,
    author_id    uuid        not null references users (id) on delete cascade,
    body         text        not null,
    created_at   timestamptz not null default now()
);


-- =============================================================================
--  9. PROFESOR IA (chat)
-- =============================================================================

-- Alcance: un tema, una materia, o todo el material (las dos en null).
-- Nunca las dos a la vez: la materia de un tema ya se conoce por topics.
create table chat_sessions (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    subject_id uuid        references subjects (id) on delete cascade,
    topic_id   uuid        references topics (id) on delete cascade,
    title      text,
    created_at timestamptz not null default now(),
    check (num_nonnulls(subject_id, topic_id) <= 1)
);

create table chat_messages (
    id         uuid primary key default gen_random_uuid(),
    session_id uuid        not null references chat_sessions (id) on delete cascade,
    role       text        not null check (role in ('user', 'assistant')),
    body       text        not null,
    created_at timestamptz not null default now()
);

create table chat_message_sources (
    message_id uuid not null references chat_messages (id) on delete cascade,
    chunk_id   uuid not null references document_chunks (id) on delete cascade,
    primary key (message_id, chunk_id)
);


-- =============================================================================
--  10. GAMIFICACIÓN: MISIONES
-- =============================================================================

-- Antes: missions (diarias) + objectives (semanales), con la misma estructura.
-- Ahora una sola tabla con "period". El tipo apunta a activity_types, así que
-- un tipo que el código no conoce ya no puede existir.
create table missions (
    id                 integer generated always as identity primary key,
    code               text    not null unique,
    description        text    not null,
    period             text    not null check (period in ('daily', 'weekly')),
    activity_type_code text    not null references activity_types (code),
    required_count     integer not null check (required_count > 0),
    xp_reward          integer not null check (xp_reward >= 0),
    coin_reward        integer not null check (coin_reward >= 0),
    is_active          boolean not null default true
);

-- Misión asignada a un usuario para un período (día o semana).
-- El progreso NO se guarda: sale de v_user_mission_progress.
create table user_missions (
    user_id       uuid        not null references users (id) on delete cascade,
    mission_id    integer     not null references missions (id),
    period_start  date        not null,
    completed_at  timestamptz,
    xp_awarded    integer,
    coins_awarded integer     check (coins_awarded >= 0),
    primary key (user_id, mission_id, period_start),
    check ((completed_at is null) = (xp_awarded is null)),
    check ((completed_at is null) = (coins_awarded is null))
);


-- Correcciones manuales de XP o monedas (soporte, saldo inicial al migrar).
-- Siempre con motivo, para que todo movimiento quede explicado.
create table balance_adjustments (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    xp_delta   integer     not null default 0,
    coin_delta integer     not null default 0,
    reason     text        not null check (length(reason) > 0),
    created_at timestamptz not null default now(),
    check (xp_delta <> 0 or coin_delta <> 0)
);


-- =============================================================================
--  11. TIENDA E INVENTARIO
-- =============================================================================

-- price null = no se vende (se desbloquea por nivel, p. ej. los marcos).
create table store_items (
    id             integer generated always as identity primary key,
    item_type_code text    not null references item_types (code),
    name           text    not null unique,
    asset_url      text,
    price          integer check (price > 0),
    unlock_level   integer references levels (level),
    is_active      boolean not null default true,
    check (num_nonnulls(price, unlock_level) >= 1),
    unique (id, item_type_code)
);

-- Cada gasto de monedas.
create table purchases (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid        not null references users (id) on delete cascade,
    item_id      integer     not null references store_items (id),
    price_paid   integer     not null check (price_paid > 0),
    purchased_at timestamptz not null default now()
);

-- Ítems permanentes que el usuario tiene (comprados o desbloqueados).
create table user_items (
    user_id     uuid        not null references users (id) on delete cascade,
    item_id     integer     not null references store_items (id),
    purchase_id uuid        unique references purchases (id) on delete cascade,
    acquired_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

-- Cada unidad de un consumible (protector de racha). used_for_date = el día
-- que cubrió; null = todavía disponible.
create table user_consumables (
    purchase_id   uuid primary key references purchases (id) on delete cascade,
    used_for_date date
);

-- Un ítem equipado por tipo. Las dos llaves compuestas garantizan en la BD
-- que el ítem es de ese tipo y que el usuario lo tiene.
create table user_equipped (
    user_id        uuid    not null,
    item_type_code text    not null references item_types (code),
    item_id        integer not null,
    primary key (user_id, item_type_code),
    foreign key (user_id, item_id)        references user_items (user_id, item_id) on delete cascade,
    foreign key (item_id, item_type_code) references store_items (id, item_type_code)
);


-- =============================================================================
--  12. REGLAS QUE UNA LLAVE NO PUEDE EXPRESAR (triggers)
-- =============================================================================

-- Una compra no puede dejar el saldo de monedas en negativo. Bloquea la fila
-- del usuario para que dos compras simultáneas no gasten el mismo saldo.
create function check_coin_balance() returns trigger
language plpgsql as $$
declare
    balance integer;
begin
    perform 1 from users where id = new.user_id for update;

    select coalesce((select sum(coins_awarded) from attempts          where user_id = new.user_id), 0)
         + coalesce((select sum(coins_awarded) from duel_participants where user_id = new.user_id), 0)
         + coalesce((select sum(coins_awarded) from user_missions     where user_id = new.user_id
                                                                      and completed_at is not null), 0)
         + coalesce((select sum(coin_delta)    from balance_adjustments where user_id = new.user_id), 0)
         - coalesce((select sum(price_paid)    from purchases         where user_id = new.user_id), 0)
      into balance;

    if balance < new.price_paid then
        raise exception 'Monedas insuficientes: saldo %, precio %', balance, new.price_paid
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger purchases_check_balance
    before insert on purchases
    for each row execute function check_coin_balance();

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

-- El intento de un jugador de duelo tiene que ser sobre el contenido del duelo
-- y del mismo jugador.
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


-- =============================================================================
--  13. ÍNDICES
--  (las PK y UNIQUE ya crean los suyos; acá van los de llaves foráneas y
--   consultas frecuentes)
-- =============================================================================

create index on user_roles (role_code);
create index on subjects (owner_id) where archived_at is null;
create index on topics (subject_id);
create index on documents (topic_id);
create index on documents (uploaded_by);
create index on document_chunks using hnsw (embedding vector_cosine_ops);
create index on generated_content (topic_id, created_at desc) where deleted_at is null;
create index on generated_content (created_by, created_at desc);
create index on generated_content (content_type_code);
create index on content_sources (chunk_id);
create index on favorites (content_id);
create index on attempts (user_id, finished_at desc);
create index on attempts (content_id);
create index on attempt_answers (item_id);
create index on attempt_answers (selected_option_id);
create index on review_states (user_id, due_on);
create index on review_states (item_id);
create index on duels (content_id);
create index on duel_participants (user_id);
create index on friendships (user_high_id);
create index on friendships (requested_by);
create index on study_groups (created_by);
create index on group_members (user_id);
create index on group_subjects (subject_id);
create index on classrooms (teacher_id);
create index on classroom_members (user_id);
create index on classroom_subjects (subject_id);
create index on assignments (classroom_id, due_at);
create index on assignments (content_id);
create index on announcements (classroom_id, created_at desc);
create index on announcements (author_id);
create index on chat_sessions (user_id, created_at desc);
create index on chat_sessions (subject_id);
create index on chat_sessions (topic_id);
create index on chat_messages (session_id, created_at);
create index on chat_message_sources (chunk_id);
create index on missions (activity_type_code);
create index on user_missions (mission_id);
create index on balance_adjustments (user_id);
create index on store_items (item_type_code);
create index on store_items (unlock_level);
create index on purchases (user_id, purchased_at desc);
create index on purchases (item_id);
create index on user_items (item_id);
create index on user_equipped (item_type_code);
create index on user_equipped (item_id, item_type_code);
create index on user_equipped (user_id, item_id);


-- =============================================================================
--  14. VISTAS (todo lo derivado)
-- =============================================================================

-- Puntaje de cada intento. Una flashcard cuenta como acierto con recall_rating >= 3.
create view v_attempt_scores as
select a.id                                                        as attempt_id,
       count(i.id)                                                 as total_items,
       count(aa.item_id)                                           as answered_items,
       count(*) filter (where o.is_correct or aa.recall_rating >= 3) as correct_items,
       coalesce(sum(aa.response_ms), 0)                            as total_response_ms,
       round(100.0 * count(*) filter (where o.is_correct or aa.recall_rating >= 3)
             / nullif(count(i.id), 0), 2)                          as score_pct
from attempts a
join content_items i         on i.content_id = a.content_id
left join attempt_answers aa on aa.attempt_id = a.id and aa.item_id = i.id
left join item_options o     on o.id = aa.selected_option_id
group by a.id;

-- Resultado de cada jugador en un duelo terminado.
create view v_duel_results as
with s as (
    select dp.duel_id, dp.user_id,
           coalesce(a.status, 'expired')       as attempt_status,
           coalesce(sc.correct_items, 0)       as correct_items,
           coalesce(sc.total_response_ms, 0)   as total_response_ms
    from duel_participants dp
    left join attempts a          on a.id = dp.attempt_id
    left join v_attempt_scores sc on sc.attempt_id = a.id
)
select me.duel_id, me.user_id, me.correct_items, me.total_response_ms,
       case
           when d.status <> 'finished' then null
           when me.attempt_status <> 'completed' and op.attempt_status =  'completed' then 'lost'
           when me.attempt_status =  'completed' and op.attempt_status <> 'completed' then 'won'
           when me.correct_items > op.correct_items then 'won'
           when me.correct_items < op.correct_items then 'lost'
           when me.total_response_ms < op.total_response_ms then 'won'
           when me.total_response_ms > op.total_response_ms then 'lost'
           else 'draw'
       end as result
from s me
join s op    on op.duel_id = me.duel_id and op.user_id <> me.user_id
join duels d on d.id = me.duel_id;

-- XP y monedas: suma de todo lo ganado menos lo gastado.
create view v_user_balances as
with earned as (
    select user_id, sum(xp) as xp, sum(coins) as coins
    from (
        select user_id, xp_awarded as xp, coins_awarded as coins from attempts
        union all
        select user_id, xp_awarded, coins_awarded from duel_participants
        union all
        select user_id, xp_awarded, coins_awarded from user_missions where completed_at is not null
        union all
        select user_id, xp_delta, coin_delta from balance_adjustments
    ) e
    group by user_id
),
spent as (
    select user_id, sum(price_paid) as coins from purchases group by user_id
)
select u.id                                              as user_id,
       greatest(coalesce(e.xp, 0), 0)                    as xp,
       coalesce(e.coins, 0) - coalesce(s.coins, 0)       as coins,
       (select max(l.level) from levels l
        where l.min_xp <= greatest(coalesce(e.xp, 0), 0)) as level
from users u
left join earned e on e.user_id = u.id
left join spent  s on s.user_id = u.id;

-- Días con actividad (en la zona horaria del usuario) más días cubiertos por protector.
create view v_user_active_days as
select a.user_id,
       (a.finished_at at time zone coalesce(p.time_zone, 'America/Costa_Rica'))::date as day
from attempts a
left join user_profiles p on p.user_id = a.user_id
where a.status = 'completed'
union
select pu.user_id, c.used_for_date
from user_consumables c
join purchases pu on pu.id = c.purchase_id
where c.used_for_date is not null;

-- Racha actual y récord (islas de días consecutivos).
create view v_user_streaks as
with d as (
    select user_id, day,
           day - (row_number() over (partition by user_id order by day))::integer as grp
    from v_user_active_days
),
islands as (
    select user_id, max(day) as last_day, count(*) as length
    from d
    group by user_id, grp
)
select i.user_id,
       max(i.length) as streak_record,
       coalesce(max(i.length) filter (
           where i.last_day >= (now() at time zone coalesce(p.time_zone, 'America/Costa_Rica'))::date - 1
       ), 0)         as streak_current
from islands i
left join user_profiles p on p.user_id = i.user_id
group by i.user_id, p.time_zone;

-- Todo lo que cuenta para misiones, con su día local.
create view v_activity_events as
select a.user_id, ct.completion_activity as activity_type_code,
       (a.finished_at at time zone coalesce(p.time_zone, 'America/Costa_Rica'))::date as day
from attempts a
join generated_content gc on gc.id = a.content_id
join content_types ct     on ct.code = gc.content_type_code
left join user_profiles p on p.user_id = a.user_id
where a.status = 'completed'
  and not exists (select 1 from duel_participants dp where dp.attempt_id = a.id)
union all
select r.user_id, 'win_duel',
       (d.finished_at at time zone coalesce(p.time_zone, 'America/Costa_Rica'))::date
from v_duel_results r
join duels d              on d.id = r.duel_id
left join user_profiles p on p.user_id = r.user_id
where r.result = 'won'
union all
select user_id, 'active_day', day
from v_user_active_days;

create view v_user_mission_progress as
select um.user_id, um.mission_id, um.period_start,
       m.description, m.required_count, m.xp_reward, m.coin_reward,
       least(count(e.day), m.required_count) as progress,
       um.completed_at
from user_missions um
join missions m on m.id = um.mission_id
left join v_activity_events e
       on e.user_id = um.user_id
      and e.activity_type_code = m.activity_type_code
      and e.day between um.period_start
                    and um.period_start + case m.period when 'daily' then 0 else 6 end
group by um.user_id, um.mission_id, um.period_start,
         m.description, m.required_count, m.xp_reward, m.coin_reward, um.completed_at;

-- Ranking: victorias en duelos y XP.
create view v_leaderboard as
select u.id as user_id, u.username, b.xp, b.level,
       count(*) filter (where r.result = 'won') as duel_wins
from users u
join v_user_balances b       on b.user_id = u.id
left join v_duel_results r   on r.user_id = u.id
group by u.id, u.username, b.xp, b.level;


-- =============================================================================
--  15. DATOS DE CATÁLOGO
-- =============================================================================

insert into roles (code, description) values
    ('student', 'Estudiante'),
    ('teacher', 'Profesor'),
    ('admin',   'Administrador');

insert into languages (code, name) values
    ('es', 'Español'),
    ('en', 'English');

insert into activity_types (code, description, base_xp, base_coins) values
    ('study_flashcards',     'Completar una sesión de flashcards', 20, 15),
    ('complete_quiz',        'Completar un quiz',                  30, 20),
    ('complete_expert_exam', 'Completar un examen experto',        50, 35),
    ('read_summary',         'Leer un resumen',                    10,  5),
    ('read_schema',          'Revisar un esquema',                 10,  5),
    ('win_duel',             'Ganar un duelo',                     40, 40),
    ('lose_duel',            'Perder un duelo',                   -10,  8),
    ('draw_duel',            'Empatar un duelo',                   20, 15),
    ('abandon_exam',         'Abandonar un examen',               -25,  0),
    ('active_day',           'Estudiar al menos una vez en el día', 0,  0);

insert into content_types (code, description, is_answerable, completion_activity) values
    ('flashcard',   'Flashcards',     true,  'study_flashcards'),
    ('quiz',        'Quiz',           true,  'complete_quiz'),
    ('expert_exam', 'Examen experto', true,  'complete_expert_exam'),
    ('summary',     'Resumen',        false, 'read_summary'),
    ('schema',      'Esquema',        false, 'read_schema');

insert into item_types (code, description, is_consumable, is_equippable) values
    ('background',    'Fondo',              false, true),
    ('frame',         'Marco de perfil',    false, true),
    ('avatar',        'Avatar',             false, true),
    ('streak_shield', 'Protector de racha', true,  false);

insert into levels (level, min_xp) values
    (1, 0), (2, 80), (3, 200), (4, 380), (5, 640),
    (6, 1000), (7, 1480), (8, 2100), (9, 2900), (10, 3900);
