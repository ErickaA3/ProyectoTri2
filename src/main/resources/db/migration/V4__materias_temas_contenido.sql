-- =============================================================================
--  V4 · Fase 2 de la reestructuración: materias, temas, documentos y contenido
--
--  · subjects → topics: las "carpetas" del estudiante.
--  · documents + document_chunks: el material original, guardado una sola vez
--    y dividido en fragmentos con embedding (la base del RAG).
--  · generated_content reemplaza a study_content: cada contenido vive en un
--    tema y sabe de qué fragmentos salió (content_sources).
--  · favorites: el favorito pasa a ser por usuario, no una columna.
--  · Borrado lógico (deleted_at): borrar contenido ya no borra sus resultados
--    ni sus duelos.
--
--  Transitorio hasta la fase 3: las preguntas y tarjetas siguen dentro de
--  generated_content.body (JSONB). En la fase 3 pasan a content_items.
--  Transitorio hasta la fase 4: el quiz de un duelo es tipo 'duel_quiz' sin tema.
--
--  Lo existente se migra a una materia "Sin clasificar" con un tema "General"
--  por usuario. Los embeddings de los fragmentos migrados quedan en null y los
--  genera el backend en segundo plano al arrancar.
--  study_content NO se borra acá: queda sin uso hasta la limpieza final.
-- =============================================================================

-- ── Catálogo de tipos de contenido ─────────────────────────────────────────

create table content_types (
    code          text primary key,
    description   text    not null,
    is_answerable boolean not null          -- tiene preguntas o tarjetas que se responden
);

insert into content_types (code, description, is_answerable) values
    ('flashcard',   'Flashcards',     true),
    ('quiz',        'Quiz',           true),
    ('expert_exam', 'Examen experto', true),
    ('summary',     'Resumen',        false),
    ('schema',      'Esquema',        false),
    ('duel_quiz',   'Quiz de duelo',  true);   -- transitorio hasta la fase 4

-- ── Materias y temas ───────────────────────────────────────────────────────

create table subjects (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid        not null references users (id) on delete cascade,
    name        text        not null check (length(name) between 1 and 100),
    color       text        check (color ~ '^#[0-9A-Fa-f]{6}$'),
    created_at  timestamptz not null default now(),
    archived_at timestamptz,
    unique (owner_id, name)
);
create index on subjects (owner_id) where archived_at is null;

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
create index on topics (subject_id);

-- ── Documentos y fragmentos (RAG) ──────────────────────────────────────────

create table documents (
    id           uuid primary key default gen_random_uuid(),
    topic_id     uuid        not null references topics (id) on delete cascade,
    uploaded_by  uuid        not null references users (id) on delete cascade,
    file_name    text        not null,
    mime_type    text        not null,
    storage_path text        unique,                 -- Supabase Storage; null si fue texto pegado
    created_at   timestamptz not null default now()
);
create index on documents (topic_id);
create index on documents (uploaded_by);

create table document_chunks (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid    not null references documents (id) on delete cascade,
    chunk_index integer not null check (chunk_index >= 0),
    content     text    not null,
    token_count integer not null check (token_count > 0),
    embedding   vector(1536),                         -- null mientras se genera
    unique (document_id, chunk_index)
);
create index on document_chunks using hnsw (embedding vector_cosine_ops);

-- ── Contenido generado ─────────────────────────────────────────────────────

create table generated_content (
    id                uuid primary key default gen_random_uuid(),
    topic_id          uuid        references topics (id) on delete cascade,  -- null: quiz de duelo
    created_by        uuid        not null references users (id) on delete cascade,
    content_type_code text        not null references content_types (code),
    title             text        not null check (length(title) between 1 and 200),
    body              jsonb       not null,
    created_at        timestamptz not null default now(),
    deleted_at        timestamptz
);
create index on generated_content (topic_id, created_at desc) where deleted_at is null;
create index on generated_content (created_by, created_at desc);
create index on generated_content (content_type_code);

create table content_sources (
    content_id uuid not null references generated_content (id) on delete cascade,
    chunk_id   uuid not null references document_chunks (id) on delete cascade,
    primary key (content_id, chunk_id)
);
create index on content_sources (chunk_id);

create table favorites (
    user_id    uuid        not null references users (id) on delete cascade,
    content_id uuid        not null references generated_content (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, content_id)
);
create index on favorites (content_id);

-- ── Migración de datos ─────────────────────────────────────────────────────

-- 1. "Sin clasificar / General" para cada usuario que tiene contenido propio.
insert into subjects (owner_id, name)
select distinct user_id, 'Sin clasificar'
from study_content
where type <> 'duel_quiz';

insert into topics (subject_id, name, position)
select id, 'General', 0
from subjects
where name = 'Sin clasificar';

-- 2. Un documento por cada texto fuente. Lo generado en una misma sesión salió
--    del mismo texto, así que comparte documento.
--    doc_map es una tabla normal (no temporal) y se borra al final: el SQL Editor
--    de Supabase puede ejecutar cada sentencia en una conexión distinta, y ahí
--    una tabla temporal desaparece entre una sentencia y la siguiente.
create table doc_map as
select gen_random_uuid()                                         as document_id,
       grp.user_id, grp.group_key, grp.source_text, grp.created_at
from (
    select distinct on (sc.user_id, coalesce(sc.session_id, sc.id))
           sc.user_id,
           coalesce(sc.session_id, sc.id) as group_key,
           sc.source_text,
           sc.created_at
    from study_content sc
    where sc.type <> 'duel_quiz'
      and nullif(trim(sc.source_text), '') is not null
    order by sc.user_id, coalesce(sc.session_id, sc.id), sc.created_at
) grp;

insert into documents (id, topic_id, uploaded_by, file_name, mime_type, created_at)
select dm.document_id, t.id, dm.user_id, 'Material migrado', 'text/plain',
       coalesce(dm.created_at at time zone 'UTC', now())
from doc_map dm
join subjects s on s.owner_id = dm.user_id and s.name = 'Sin clasificar'
join topics t   on t.subject_id = s.id and t.name = 'General';

-- 3. Fragmentos de 2000 caracteres (el backend calcula los embeddings después).
insert into document_chunks (document_id, chunk_index, content, token_count)
select dm.document_id,
       n,
       substring(dm.source_text from n * 2000 + 1 for 2000),
       greatest(1, length(substring(dm.source_text from n * 2000 + 1 for 2000)) / 4)
from doc_map dm
cross join lateral generate_series(0, (length(dm.source_text) - 1) / 2000) as n;

-- 4. El contenido, con el mismo id (así activity_results y duels siguen apuntando bien).
insert into generated_content (id, topic_id, created_by, content_type_code, title, body, created_at)
select sc.id,
       case when sc.type = 'duel_quiz' then null else t.id end,
       sc.user_id,
       sc.type,
       left(coalesce(nullif(trim(sc.title), ''), 'Sin título'), 200),
       sc.content,
       coalesce(sc.created_at at time zone 'UTC', now())
from study_content sc
left join subjects s on s.owner_id = sc.user_id and s.name = 'Sin clasificar'
left join topics t   on t.subject_id = s.id and t.name = 'General';

-- 5. De qué fragmentos salió cada contenido.
insert into content_sources (content_id, chunk_id)
select sc.id, ch.id
from study_content sc
join doc_map dm         on dm.user_id = sc.user_id and dm.group_key = coalesce(sc.session_id, sc.id)
join document_chunks ch on ch.document_id = dm.document_id
where sc.type <> 'duel_quiz';

-- 6. Favoritos.
insert into favorites (user_id, content_id)
select user_id, id from study_content where is_favorite;

-- 7. Resultados y duelos pasan a apuntar a generated_content.
alter table activity_results drop constraint activity_results_content_id_fkey;
alter table activity_results add constraint activity_results_content_id_fkey
    foreign key (content_id) references generated_content (id) on delete set null;

alter table duels drop constraint duels_content_id_fkey;
alter table duels add constraint duels_content_id_fkey
    foreign key (content_id) references generated_content (id) on delete set null;

drop table doc_map;

-- ── RLS ─────────────────────────────────────────────────────────────────────

alter table content_types     enable row level security;
alter table subjects          enable row level security;
alter table topics            enable row level security;
alter table documents         enable row level security;
alter table document_chunks   enable row level security;
alter table generated_content enable row level security;
alter table content_sources   enable row level security;
alter table favorites         enable row level security;
