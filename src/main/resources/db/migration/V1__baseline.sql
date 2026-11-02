-- =============================================================================
--  V1 · Esquema base de Polaris
--
--  Es el esquema de Supabase tal como estaba ANTES de la V2, reconstruido a
--  partir del catálogo de la base (2026-11-02). En producción NO se ejecuta:
--  Flyway marca esa base como ya migrada hasta la V2 (baseline). Sirve para
--  levantar una base nueva (desarrollo, pruebas) idéntica a la real.
-- =============================================================================

create extension if not exists vector;

-- ── Usuarios ────────────────────────────────────────────────────────────────

create table users (
    id            uuid primary key default gen_random_uuid(),
    username      varchar(50)  not null,
    email         varchar(100) not null,
    password_hash varchar(255) not null,
    created_at    timestamp default now(),
    constraint users_email_key    unique (email),
    constraint users_username_key unique (username)
);

create table clientes (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null,
    full_name  varchar,
    country    varchar,
    language   varchar default 'es',
    birthdate  date,
    created_at timestamp default now(),
    constraint clientes_user_id_fkey foreign key (user_id) references users (id) on delete cascade
);

create table user_stats (
    user_id              uuid primary key,
    xp                   integer default 0,
    level                integer default 1,
    coins                integer default 0,
    streak_current       integer default 0,
    streak_record        integer default 0,
    streak_last_activity date,
    has_streak_shield    boolean default false,
    constraint user_stats_user_id_fkey foreign key (user_id) references users (id) on delete cascade
);

create table friendships (
    id          uuid primary key default gen_random_uuid(),
    sender_id   uuid not null,
    receiver_id uuid not null,
    status      varchar(20) default 'pending',
    created_at  timestamp default now(),
    constraint friendships_sender_id_fkey   foreign key (sender_id)   references users (id) on delete cascade,
    constraint friendships_receiver_id_fkey foreign key (receiver_id) references users (id) on delete cascade,
    constraint friendships_sender_id_receiver_id_key unique (sender_id, receiver_id)
);

-- ── Tienda ──────────────────────────────────────────────────────────────────

create table store_items (
    id        serial primary key,
    name      varchar(100) not null,
    type      varchar(30)  not null,
    cost      integer      not null,
    image_url varchar(255),
    constraint store_items_type_check check (type in ('avatar', 'background', 'streak_shield'))
);

create table user_inventory (
    user_id      uuid    not null,
    item_id      integer not null,
    purchased_at timestamp default now(),
    is_equipped  boolean default false,
    constraint user_inventory_pkey primary key (user_id, item_id),
    constraint user_inventory_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint user_inventory_item_id_fkey foreign key (item_id) references store_items (id)
);
-- Duplica la PK (la V2 la quita). Va aparte porque dentro de CREATE TABLE
-- Postgres descarta en silencio un UNIQUE igual a la PK.
alter table user_inventory add constraint user_inventory_user_item_unique unique (user_id, item_id);

create table professor_config (
    user_id            uuid primary key,
    avatar_item_id     integer,
    background_item_id integer,
    professor_name     varchar(50) default 'Poli',
    personality        varchar(50) default 'friendly',
    updated_at         timestamp default now(),
    constraint professor_config_user_id_fkey            foreign key (user_id)            references users (id) on delete cascade,
    constraint professor_config_avatar_item_id_fkey     foreign key (avatar_item_id)     references store_items (id),
    constraint professor_config_background_item_id_fkey foreign key (background_item_id) references store_items (id),
    constraint professor_config_personality_check check (personality in ('strict', 'friendly', 'funny'))
);

-- ── Contenido y chat ────────────────────────────────────────────────────────

create table study_content (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid        not null,
    type        varchar(20) not null,
    title       varchar(200),
    content     jsonb       not null,
    is_favorite boolean default false,
    created_at  timestamp default now(),
    session_id  uuid,
    source_text text,
    embedding   vector(1536),
    constraint study_content_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint study_content_type_check check (type in
        ('flashcard', 'quiz', 'summary', 'schema', 'exam', 'expert_exam', 'duel_quiz'))
);

create table chat_history (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null,
    role       varchar(10) not null,
    message    text        not null,
    created_at timestamp default now(),
    session_id uuid,
    constraint chat_history_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint chat_history_role_check check (role in ('user', 'assistant'))
);

create table activity_results (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null,
    content_id         uuid,
    score              numeric,
    max_score          numeric,
    time_taken_seconds integer,
    completed_at       timestamp default now(),
    constraint activity_results_user_id_fkey    foreign key (user_id)    references users (id) on delete cascade,
    constraint activity_results_content_id_fkey foreign key (content_id) references study_content (id) on delete set null
);

-- ── Duelos ──────────────────────────────────────────────────────────────────

create table duels (
    id                uuid primary key default gen_random_uuid(),
    challenger_id     uuid not null,
    opponent_id       uuid not null,
    winner_id         uuid,
    content_id        uuid,
    status            varchar(20) default 'pending',
    created_at        timestamp default now(),
    finished_at       timestamp,
    topic             text,
    question_count    integer default 10,
    challenger_score  numeric,
    challenger_time   integer,
    opponent_score    numeric,
    opponent_time     integer,
    time_per_question integer default 30,
    constraint duels_challenger_id_fkey foreign key (challenger_id) references users (id),
    constraint duels_opponent_id_fkey   foreign key (opponent_id)   references users (id),
    constraint duels_winner_id_fkey     foreign key (winner_id)     references users (id),
    constraint duels_content_id_fkey    foreign key (content_id)    references study_content (id),
    constraint duels_status_check check (status in
        ('waiting_opponent', 'in_progress', 'finished', 'expired', 'declined'))
);

create table duel_answers (
    id             uuid primary key default gen_random_uuid(),
    duel_id        uuid not null,
    user_id        uuid not null,
    question_index integer,
    answer_given   varchar(10),
    is_correct     boolean,
    time_ms        integer,
    constraint duel_answers_duel_id_fkey foreign key (duel_id) references duels (id) on delete cascade,
    constraint duel_answers_user_id_fkey foreign key (user_id) references users (id)
);

-- ── Misiones, objetivos y ranking ───────────────────────────────────────────

create table missions (
    id             serial primary key,
    description    varchar(200) not null,
    type           varchar(50)  not null,
    required_count integer default 1,
    xp_reward      integer default 50,
    coin_reward    integer default 10
);

create table user_daily_missions (
    user_id    uuid    not null,
    mission_id integer not null,
    date       date    not null default current_date,
    progress   integer default 0,
    completed  boolean default false,
    constraint user_daily_missions_pkey primary key (user_id, mission_id, date),
    constraint user_daily_missions_user_id_fkey    foreign key (user_id)    references users (id) on delete cascade,
    constraint user_daily_missions_mission_id_fkey foreign key (mission_id) references missions (id)
);

create table objectives (
    id             serial primary key,
    type           varchar(50)  not null,
    description    varchar(200) not null,
    required_count integer not null default 1,
    xp_reward      integer not null default 50,
    coin_reward    integer not null default 10,
    constraint objectives_type_key unique (type)
);

create table user_weekly_objectives (
    user_id               uuid        not null,
    type                  varchar(50) not null,
    week_start            date        not null,
    objective_description varchar(200),
    required_count        integer,
    progress              integer default 0,
    completed             boolean default false,
    xp_reward             integer,
    coin_reward           integer,
    constraint user_weekly_objectives_pkey primary key (user_id, type, week_start),
    constraint user_weekly_objectives_user_id_fkey foreign key (user_id) references users (id) on delete cascade
);

create table leaderboard_history (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid    not null,
    period_type   varchar not null,
    period_start  date    not null,
    xp_snapshot   integer not null,
    rank_position integer,
    created_at    timestamp default now(),
    constraint leaderboard_history_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint leaderboard_history_user_id_period_type_period_start_key unique (user_id, period_type, period_start)
);

-- ── Índices ─────────────────────────────────────────────────────────────────

create index idx_activity_results_user      on activity_results (user_id);
create index idx_chat_history_user          on chat_history (user_id, created_at desc);
create index idx_duel_answers_duel          on duel_answers (duel_id);
create index idx_duels_challenger           on duels (challenger_id);
create index idx_duels_opponent             on duels (opponent_id);
create index idx_duels_status               on duels (status);
create index idx_friendships_receiver       on friendships (receiver_id, status);
create index idx_friendships_sender         on friendships (sender_id, status);
create index idx_leaderboard_history_period on leaderboard_history (period_type, period_start);
create index idx_study_content_fav          on study_content (user_id, is_favorite);
create index idx_study_content_type         on study_content (type);
create index idx_study_content_user         on study_content (user_id);
create index study_content_embedding_idx    on study_content using hnsw (embedding vector_cosine_ops);
create index idx_daily_missions_date        on user_daily_missions (user_id, date);
create index idx_user_stats_xp              on user_stats (xp desc);
create index idx_weekly_obj_week            on user_weekly_objectives (user_id, week_start);

-- ── RLS: activo y sin políticas (solo el backend accede) ────────────────────

alter table activity_results       enable row level security;
alter table chat_history           enable row level security;
alter table clientes               enable row level security;
alter table duel_answers           enable row level security;
alter table duels                  enable row level security;
alter table friendships            enable row level security;
alter table leaderboard_history    enable row level security;
alter table missions               enable row level security;
alter table objectives             enable row level security;
alter table professor_config       enable row level security;
alter table store_items            enable row level security;
alter table study_content          enable row level security;
alter table user_daily_missions    enable row level security;
alter table user_inventory         enable row level security;
alter table user_stats             enable row level security;
alter table user_weekly_objectives enable row level security;
alter table users                  enable row level security;
