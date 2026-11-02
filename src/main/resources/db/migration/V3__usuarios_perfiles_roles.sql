-- =============================================================================
--  V3 · Fase 1 de la reestructuración: usuarios
--
--  · Catálogos: roles, languages, countries.
--  · user_roles: cada usuario existente queda como "student".
--  · user_profiles reemplaza a clientes (1:1, país e idioma con llave foránea,
--    zona horaria por usuario).
--  · assistant_settings reemplaza a professor_config (solo nombre y personalidad).
--  · users: username y email sin distinguir mayúsculas (citext), created_at con zona.
--
--  clientes y professor_config NO se borran acá: quedan sin uso y se eliminan
--  en la migración de limpieza del final de la reestructuración.
--  Ver database/DISENO.md.
-- =============================================================================

create extension if not exists citext;

-- ── Catálogos ───────────────────────────────────────────────────────────────

create table roles (
    code        text primary key,
    description text not null
);

insert into roles (code, description) values
    ('student', 'Estudiante'),
    ('teacher', 'Profesor'),
    ('admin',   'Administrador');

create table languages (
    code text primary key check (code ~ '^[a-z]{2}$'),          -- ISO 639-1
    name text not null
);

insert into languages (code, name) values
    ('es', 'Español'),
    ('en', 'English'),
    ('pt', 'Português');

create table countries (
    code char(2) primary key check (code ~ '^[A-Z]{2}$'),       -- ISO 3166-1 alfa-2
    name text not null unique
);

insert into countries (code, name) values
    ('AR', 'Argentina'),       ('BO', 'Bolivia'),          ('BR', 'Brasil'),
    ('CA', 'Canadá'),          ('CL', 'Chile'),            ('CO', 'Colombia'),
    ('CR', 'Costa Rica'),      ('CU', 'Cuba'),             ('DO', 'República Dominicana'),
    ('EC', 'Ecuador'),         ('SV', 'El Salvador'),      ('ES', 'España'),
    ('US', 'Estados Unidos'),  ('GT', 'Guatemala'),        ('HN', 'Honduras'),
    ('MX', 'México'),          ('NI', 'Nicaragua'),        ('PA', 'Panamá'),
    ('PY', 'Paraguay'),        ('PE', 'Perú'),             ('PR', 'Puerto Rico'),
    ('PT', 'Portugal'),        ('UY', 'Uruguay'),          ('VE', 'Venezuela'),
    ('DE', 'Alemania'),        ('FR', 'Francia'),          ('IT', 'Italia'),
    ('GB', 'Reino Unido');

-- ── Roles por usuario ───────────────────────────────────────────────────────

create table user_roles (
    user_id   uuid not null references users (id) on delete cascade,
    role_code text not null references roles (code),
    primary key (user_id, role_code)
);
create index on user_roles (role_code);

insert into user_roles (user_id, role_code)
select id, 'student' from users;

-- ── Perfil (antes: clientes) ────────────────────────────────────────────────

create table user_profiles (
    user_id       uuid primary key references users (id) on delete cascade,
    full_name     text,
    country_code  char(2) references countries (code),
    language_code text    not null default 'es' references languages (code),
    birthdate     date    check (birthdate > date '1900-01-01'),
    time_zone     text    not null default 'America/Costa_Rica'
);
create index on user_profiles (country_code);
create index on user_profiles (language_code);

-- El país en clientes era texto libre: se compara sin mayúsculas ni tildes,
-- contra el nombre, el código o un alias común. Lo que no coincide queda null.
create function pg_temp.normalize_name(t text) returns text
language sql immutable as $$
    select lower(translate(trim(t), 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunaeiouun'))
$$;

with aliases (alias, code) as (
    select pg_temp.normalize_name(name), code from countries
    union all
    select lower(code), code from countries
    union all
    values ('usa', 'US'), ('eeuu', 'US'), ('ee.uu.', 'US'), ('mexico', 'MX'),
           ('republica dominicana', 'DO'), ('spain', 'ES'), ('united states', 'US')
)
insert into user_profiles (user_id, full_name, country_code, language_code, birthdate)
select u.id,
       nullif(trim(c.full_name), ''),
       a.code,
       coalesce(l.code, 'es'),
       case when c.birthdate > date '1900-01-01' then c.birthdate end
from users u
left join clientes c  on c.user_id = u.id
left join (select distinct alias, code from aliases) a
                      on a.alias = pg_temp.normalize_name(c.country)
left join languages l on l.code = lower(trim(c.language));

-- ── Ajustes del profesor IA (antes: professor_config) ───────────────────────

create table assistant_settings (
    user_id     uuid primary key references users (id) on delete cascade,
    name        text not null default 'Poli' check (length(name) between 1 and 50),
    personality text not null default 'friendly'
                check (personality in ('strict', 'friendly', 'funny'))
);

insert into assistant_settings (user_id, name, personality)
select user_id,
       coalesce(nullif(trim(professor_name), ''), 'Poli'),
       coalesce(personality, 'friendly')
from professor_config;

-- ── users ───────────────────────────────────────────────────────────────────

alter table users
    alter column username type citext,
    alter column email    type citext,
    alter column created_at type timestamptz using created_at at time zone 'UTC',
    alter column created_at set not null,
    add constraint users_username_length check (length(username) between 3 and 30),
    add constraint users_email_format    check (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$');

-- ── RLS: igual que el resto de tablas (solo el backend accede) ──────────────

alter table roles              enable row level security;
alter table languages          enable row level security;
alter table countries          enable row level security;
alter table user_roles         enable row level security;
alter table user_profiles      enable row level security;
alter table assistant_settings enable row level security;
