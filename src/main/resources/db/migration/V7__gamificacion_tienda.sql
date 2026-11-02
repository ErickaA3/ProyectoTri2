-- =============================================================================
--  V7 · Fase 5 de la reestructuración: gamificación y tienda
--
--  Nada derivable se guarda:
--  · XP y monedas = suma de lo pagado por cada evento (intentos, duelos,
--    misiones, ajustes) menos lo gastado en compras (v_user_balances).
--  · Nivel = tabla levels según el XP.
--  · Racha = días seguidos con algún intento completado, más los días que cubrió
--    un protector (v_user_streaks).
--  · Progreso de misiones = eventos del período (v_user_mission_progress).
--  · v_user_stats reproduce las columnas de la vieja user_stats para la app.
--
--  Migración: el XP y las monedas actuales de user_stats pasan a un
--  balance_adjustments "Saldo migrado" por usuario (nadie pierde nada). Lo ya
--  pagado antes de la V7 queda en 0 en cada evento para no contarlo dos veces.
--  La racha se reinicia (decisión del equipo): no hay días guardados de antes.
--
--  missions pasa a tener misiones diarias y semanales (objectives se copia
--  como semanales). user_missions reemplaza a user_daily_missions y
--  user_weekly_objectives. La tienda pasa a purchases, user_items,
--  user_consumables y user_equipped. user_stats, user_inventory, objectives,
--  user_daily_missions, user_weekly_objectives y leaderboard_history quedan
--  sin uso hasta la limpieza final.
--
--  Sin begin/commit ni tablas temporales (SQL Editor de Supabase).
-- =============================================================================

-- ── Catálogos ──────────────────────────────────────────────────────────────

-- Lo que hace avanzar una misión.
create table activity_types (
    code        text primary key,
    description text not null
);

insert into activity_types (code, description) values
    ('complete_quiz',        'Completar un quiz'),
    ('complete_expert_exam', 'Completar un examen experto'),
    ('complete_flashcard',   'Completar una sesión de flashcards'),
    ('complete_summary',     'Leer un resumen'),
    ('perfect_exam',         'Sacar 100 en un examen experto'),
    ('win_duel',             'Ganar un duelo'),
    ('any_activity',         'Completar cualquier actividad'),
    ('active_day',           'Estudiar al menos una vez en el día');

alter table content_types add column completion_activity text references activity_types (code);
update content_types set completion_activity = case code
    when 'quiz'        then 'complete_quiz'
    when 'expert_exam' then 'complete_expert_exam'
    when 'flashcard'   then 'complete_flashcard'
    when 'summary'     then 'complete_summary'
end;

create table levels (
    level   integer primary key check (level >= 1),
    min_xp  integer not null unique check (min_xp >= 0)
);

insert into levels (level, min_xp) values
    (1, 0), (2, 80), (3, 200), (4, 380), (5, 640),
    (6, 1000), (7, 1480), (8, 2100), (9, 2900), (10, 3900);

create table item_types (
    code          text primary key,
    description   text    not null,
    is_consumable boolean not null,
    is_equippable boolean not null,
    check (not (is_consumable and is_equippable))
);

insert into item_types (code, description, is_consumable, is_equippable) values
    ('background',    'Fondo',              false, true),
    ('avatar',        'Avatar',             false, true),
    ('frame',         'Marco de perfil',    false, true),
    ('streak_shield', 'Protector de racha', true,  false);

-- ── Misiones: diarias y semanales en una sola tabla ───────────────────────

alter table missions
    add column code               text,
    add column period             text,
    add column activity_type_code text references activity_types (code),
    add column is_active          boolean not null default true;

update missions set
    code               = 'daily_' || id,
    period             = 'daily',
    activity_type_code = case type
        when 'maintain_streak' then 'active_day'
        else type
    end;

insert into missions (description, type, required_count, xp_reward, coin_reward,
                      code, period, activity_type_code)
select description, type, required_count, xp_reward, coin_reward,
       'weekly_' || id, 'weekly',
       case type
           when 'activities'  then 'any_activity'
           when 'streak_days' then 'active_day'
           when 'duels_won'   then 'win_duel'
           else type
       end
from objectives;

update missions set required_count = 1  where required_count is null;
update missions set xp_reward      = 0  where xp_reward is null;
update missions set coin_reward    = 0  where coin_reward is null;

alter table missions
    alter column code               set not null,
    alter column period             set not null,
    alter column activity_type_code set not null,
    alter column required_count     set not null,
    alter column xp_reward          set not null,
    alter column coin_reward        set not null,
    add constraint missions_code_key      unique (code),
    add constraint missions_period_check  check (period in ('daily', 'weekly')),
    add constraint missions_required_check check (required_count > 0),
    add constraint missions_rewards_check check (xp_reward >= 0 and coin_reward >= 0),
    drop column type;
create index on missions (activity_type_code);

-- Misión asignada a un usuario para un día (diaria) o una semana (semanal,
-- period_start = lunes). El progreso no se guarda: sale de la vista.
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
create index on user_missions (mission_id);

-- Lo pagado antes de la V7 ya está en el saldo migrado: queda en 0.
insert into user_missions (user_id, mission_id, period_start, completed_at, xp_awarded, coins_awarded)
select udm.user_id, udm.mission_id, udm.date,
       case when udm.completed then (udm.date + time '12:00') at time zone 'America/Costa_Rica' end,
       case when udm.completed then 0 end,
       case when udm.completed then 0 end
from user_daily_missions udm;

insert into user_missions (user_id, mission_id, period_start, completed_at, xp_awarded, coins_awarded)
select uwo.user_id, m.id, uwo.week_start,
       case when uwo.completed then (uwo.week_start + time '12:00') at time zone 'America/Costa_Rica' end,
       case when uwo.completed then 0 end,
       case when uwo.completed then 0 end
from user_weekly_objectives uwo
join objectives o on o.type = uwo.type
join missions m   on m.code = 'weekly_' || o.id
on conflict do nothing;

-- ── Saldo ──────────────────────────────────────────────────────────────────

-- Correcciones manuales de XP o monedas, siempre con motivo.
create table balance_adjustments (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    xp_delta   integer     not null default 0,
    coin_delta integer     not null default 0,
    reason     text        not null check (length(reason) > 0),
    created_at timestamptz not null default now(),
    check (xp_delta <> 0 or coin_delta <> 0)
);
create index on balance_adjustments (user_id);

insert into balance_adjustments (user_id, xp_delta, coin_delta, reason)
select user_id, xp, coins, 'Saldo migrado'
from user_stats
where xp <> 0 or coins <> 0;

-- ── Tienda ─────────────────────────────────────────────────────────────────

alter table store_items drop constraint store_items_type_check;
alter table store_items
    add constraint store_items_type_fkey foreign key (type) references item_types (code),
    add column unlock_level integer references levels (level),
    add column is_active    boolean not null default true,
    add constraint store_items_id_type_key unique (id, type);
alter table store_items alter column cost drop not null;
alter table store_items
    add constraint store_items_cost_check check (cost is null or cost >= 0),
    add constraint store_items_price_or_level check (cost is not null or unlock_level is not null);

-- Cada gasto de monedas (price_paid 0 = protector migrado).
create table purchases (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid        not null references users (id) on delete cascade,
    item_id      integer     not null references store_items (id),
    price_paid   integer     not null check (price_paid >= 0),
    purchased_at timestamptz not null default now()
);
create index on purchases (user_id, purchased_at desc);
create index on purchases (item_id);

-- Ítems permanentes del usuario (comprados, gratis o desbloqueados).
create table user_items (
    user_id     uuid        not null references users (id) on delete cascade,
    item_id     integer     not null references store_items (id),
    purchase_id uuid        unique references purchases (id) on delete cascade,
    acquired_at timestamptz not null default now(),
    primary key (user_id, item_id)
);
create index on user_items (item_id);

-- Cada unidad de un consumible; used_for_date = el día que cubrió.
create table user_consumables (
    purchase_id   uuid primary key references purchases (id) on delete cascade,
    used_for_date date
);

-- Un ítem equipado por tipo: tiene que ser de ese tipo y del usuario.
create table user_equipped (
    user_id        uuid    not null,
    item_type_code text    not null references item_types (code),
    item_id        integer not null,
    primary key (user_id, item_type_code),
    foreign key (user_id, item_id)        references user_items (user_id, item_id) on delete cascade,
    foreign key (item_id, item_type_code) references store_items (id, type)
);
create index on user_equipped (item_id, item_type_code);
create index on user_equipped (user_id, item_id);

-- Migración del inventario (lo ya comprado no se vuelve a cobrar: está en el saldo migrado).
insert into user_items (user_id, item_id, acquired_at)
select ui.user_id, ui.item_id, coalesce(ui.purchased_at at time zone 'UTC', now())
from user_inventory ui
join store_items si on si.id = ui.item_id
join item_types it  on it.code = si.type
where not it.is_consumable;

insert into user_equipped (user_id, item_type_code, item_id)
select distinct on (ui.user_id, si.type) ui.user_id, si.type, ui.item_id
from user_inventory ui
join store_items si on si.id = ui.item_id
join item_types it  on it.code = si.type
where ui.is_equipped and it.is_equippable
order by ui.user_id, si.type, ui.purchased_at desc;

-- Protector activo → una unidad disponible.
insert into purchases (user_id, item_id, price_paid)
select us.user_id, (select min(id) from store_items where type = 'streak_shield'), 0
from user_stats us
where us.has_streak_shield
  and exists (select 1 from store_items where type = 'streak_shield');

insert into user_consumables (purchase_id)
select p.id from purchases p
join store_items si on si.id = p.item_id
where si.type = 'streak_shield';

-- ── Vistas derivadas ───────────────────────────────────────────────────────

-- Todo lo que suma o resta XP y monedas.
create view v_xp_events as
select user_id, xp_awarded as xp, coins_awarded as coins, finished_at as occurred_at, 'attempt' as source
from attempts where finished_at is not null
union all
select dp.user_id, dp.xp_awarded, dp.coins_awarded, coalesce(d.finished_at, d.created_at), 'duel'
from duel_participants dp join duels d on d.id = dp.duel_id
union all
select user_id, xp_awarded, coins_awarded, completed_at, 'mission'
from user_missions where completed_at is not null
union all
select user_id, xp_delta, coin_delta, created_at, 'adjustment'
from balance_adjustments;

create view v_user_balances as
select u.id as user_id,
       greatest(coalesce(e.xp, 0), 0)                              as xp,
       coalesce(e.coins, 0) - coalesce(p.spent, 0)                 as coins
from users u
left join (select user_id, sum(xp) as xp, sum(coins) as coins
           from v_xp_events group by user_id) e on e.user_id = u.id
left join (select user_id, sum(price_paid) as spent
           from purchases group by user_id) p on p.user_id = u.id;

-- Días con actividad (en la zona del usuario) más días cubiertos por un protector.
create view v_user_active_days as
select a.user_id,
       (a.finished_at at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date as day
from attempts a
left join user_profiles pr on pr.user_id = a.user_id
where a.status = 'completed' and a.legacy_score_pct is null
union
select p.user_id, c.used_for_date
from user_consumables c
join purchases p on p.id = c.purchase_id
where c.used_for_date is not null;

create view v_user_streaks as
with d as (
    select user_id, day,
           day - (row_number() over (partition by user_id order by day))::integer as grp
    from v_user_active_days
),
islands as (
    select user_id, max(day) as last_day, count(*)::int as length
    from d group by user_id, grp
)
select i.user_id,
       max(i.length)   as streak_record,
       max(i.last_day) as last_day,
       coalesce(max(i.length) filter (
           where i.last_day >= (now() at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date - 1
              -- con un protector disponible, un día de ausencia todavía no rompe la racha
              or (i.last_day = (now() at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date - 2
                  and exists (select 1 from user_consumables c join purchases p on p.id = c.purchase_id
                              where p.user_id = i.user_id and c.used_for_date is null))
       ), 0)           as streak_current
from islands i
left join user_profiles pr on pr.user_id = i.user_id
group by i.user_id, pr.time_zone;

-- Mismas columnas que la vieja user_stats, calculadas.
create view v_user_stats as
select u.id as user_id,
       b.xp,
       (select max(l.level) from levels l where l.min_xp <= b.xp) as level,
       b.coins,
       coalesce(s.streak_current, 0) as streak_current,
       coalesce(s.streak_record, 0)  as streak_record,
       s.last_day                    as streak_last_activity,
       exists (select 1 from user_consumables c join purchases p on p.id = c.purchase_id
               where p.user_id = u.id and c.used_for_date is null) as has_streak_shield
from users u
join v_user_balances b    on b.user_id = u.id
left join v_user_streaks s on s.user_id = u.id;

-- Todo lo que cuenta para misiones, con su día local.
create view v_activity_events as
select a.user_id, ct.completion_activity as activity_type_code,
       (a.finished_at at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date as day
from attempts a
join generated_content gc  on gc.id = a.content_id
join content_types ct      on ct.code = gc.content_type_code
left join user_profiles pr on pr.user_id = a.user_id
where a.status = 'completed' and ct.completion_activity is not null
union all
select a.user_id, 'any_activity',
       (a.finished_at at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date
from attempts a
left join user_profiles pr on pr.user_id = a.user_id
where a.status = 'completed'
union all
select a.user_id, 'perfect_exam',
       (a.finished_at at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date
from attempts a
join generated_content gc  on gc.id = a.content_id and gc.content_type_code = 'expert_exam'
join v_attempt_scores s    on s.attempt_id = a.id and s.score_pct >= 100
left join user_profiles pr on pr.user_id = a.user_id
where a.status = 'completed'
union all
select d.winner_id, 'win_duel',
       (d.finished_at at time zone coalesce(pr.time_zone, 'America/Costa_Rica'))::date
from duels d
left join user_profiles pr on pr.user_id = d.winner_id
where d.winner_id is not null and d.finished_at is not null
union all
select user_id, 'active_day', day
from v_user_active_days;

create view v_user_mission_progress as
select um.user_id, um.mission_id, um.period_start, m.period,
       m.description, m.activity_type_code, m.required_count, m.xp_reward, m.coin_reward,
       least(count(e.day), m.required_count)::int as progress,
       um.completed_at
from user_missions um
join missions m on m.id = um.mission_id
left join v_activity_events e
       on e.user_id = um.user_id
      and e.activity_type_code = m.activity_type_code
      and e.day between um.period_start
                    and um.period_start + case m.period when 'daily' then 0 else 6 end
group by um.user_id, um.mission_id, um.period_start, m.period, m.description,
         m.activity_type_code, m.required_count, m.xp_reward, m.coin_reward, um.completed_at;

-- ── RLS ─────────────────────────────────────────────────────────────────────

alter table activity_types      enable row level security;
alter table levels              enable row level security;
alter table item_types          enable row level security;
alter table user_missions       enable row level security;
alter table balance_adjustments enable row level security;
alter table purchases           enable row level security;
alter table user_items          enable row level security;
alter table user_consumables    enable row level security;
alter table user_equipped       enable row level security;
