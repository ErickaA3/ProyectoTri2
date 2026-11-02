-- =============================================================================
--  V8 · Fase 6 de la reestructuración: chat del profesor IA
--
--  · chat_sessions: cada conversación es una fila, con título y alcance
--    (un tema, una materia, o todo el material si las dos van en null).
--    Antes session_id era un UUID suelto en cada mensaje.
--  · chat_messages: los mensajes cuelgan de su sesión (el usuario sale de ahí).
--  · chat_message_sources: de qué fragmentos del material salió cada respuesta.
--
--  Las sesiones migradas conservan su id (los links del front siguen
--  funcionando). Los mensajes viejos sin session_id quedan en una sesión
--  "Conversación anterior" por usuario. chat_history queda sin uso hasta la
--  limpieza final.
--  Sin begin/commit ni tablas temporales (SQL Editor de Supabase).
-- =============================================================================

create table chat_sessions (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    subject_id uuid        references subjects (id) on delete cascade,
    topic_id   uuid        references topics (id) on delete cascade,
    title      text        not null check (length(title) between 1 and 100),
    created_at timestamptz not null default now(),
    -- un tema ya implica su materia: nunca las dos a la vez
    check (num_nonnulls(subject_id, topic_id) <= 1)
);
create index on chat_sessions (user_id, created_at desc);
create index on chat_sessions (subject_id);
create index on chat_sessions (topic_id);

create table chat_messages (
    id         uuid primary key default gen_random_uuid(),
    session_id uuid        not null references chat_sessions (id) on delete cascade,
    role       text        not null check (role in ('user', 'assistant')),
    body       text        not null,
    created_at timestamptz not null default now()
);
create index on chat_messages (session_id, created_at);

create table chat_message_sources (
    message_id uuid not null references chat_messages (id) on delete cascade,
    chunk_id   uuid not null references document_chunks (id) on delete cascade,
    primary key (message_id, chunk_id)
);
create index on chat_message_sources (chunk_id);

-- ── Migración ──────────────────────────────────────────────────────────────
-- Id de sesión: el que tenía, o uno fijo por usuario para los mensajes sin sesión.

insert into chat_sessions (id, user_id, title, created_at)
select h.session_key,
       h.user_id,
       left(coalesce(nullif(trim(
           (array_agg(h.message order by h.created_at) filter (where h.role = 'user'))[1]
       ), ''), 'Conversación anterior'), 100),
       coalesce(min(h.created_at) at time zone 'UTC', now())
from (
    select user_id,
           coalesce(session_id, md5(user_id::text || ':sin-sesion')::uuid) as session_key,
           role, message, created_at
    from chat_history
) h
group by h.user_id, h.session_key;

insert into chat_messages (id, session_id, role, body, created_at)
select ch.id,
       coalesce(ch.session_id, md5(ch.user_id::text || ':sin-sesion')::uuid),
       ch.role,
       ch.message,
       coalesce(ch.created_at at time zone 'UTC', now())
from chat_history ch;

alter table chat_sessions        enable row level security;
alter table chat_messages        enable row level security;
alter table chat_message_sources enable row level security;
