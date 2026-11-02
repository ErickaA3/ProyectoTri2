-- =============================================================================
--  V2 · Arreglos urgentes (no cambia el modelo)
--  Aplicada a mano en Supabase (SQL Editor) el 2026-11-02, después de copiar
--  las tablas al esquema "respaldo". Ver database/DISENO.md.
-- =============================================================================
begin;

-- 1. Objetivos semanales: alinear los tipos con los que avanza GamificationService.
--    Desde que objectives se lee de la tabla, ningún tipo coincidía y los
--    objetivos se quedaban en 0.
update objectives set type = case type
        when 'flashcards'     then 'complete_flashcard'
        when 'quizzes'        then 'complete_quiz'
        when 'streak'         then 'streak_days'
        when 'study_sessions' then 'activities'
    end
where type in ('flashcards', 'quizzes', 'streak', 'study_sessions');

update user_weekly_objectives u set type = case u.type
        when 'flashcards'     then 'complete_flashcard'
        when 'quizzes'        then 'complete_quiz'
        when 'streak'         then 'streak_days'
        when 'study_sessions' then 'activities'
    end
where u.type in ('flashcards', 'quizzes', 'streak', 'study_sessions')
  and not exists (
      select 1 from user_weekly_objectives x
      where x.user_id = u.user_id and x.week_start = u.week_start
        and x.type = case u.type
                         when 'flashcards'     then 'complete_flashcard'
                         when 'quizzes'        then 'complete_quiz'
                         when 'streak'         then 'streak_days'
                         when 'study_sessions' then 'activities'
                     end);

-- 2. Borrar contenido ya no obliga a borrar duelos.
alter table duels drop constraint duels_content_id_fkey;
alter table duels add constraint duels_content_id_fkey
    foreign key (content_id) references study_content (id) on delete set null;
alter table duels alter column status set default 'waiting_opponent';
alter table duels alter column status set not null;
alter table duels add constraint duels_not_self check (challenger_id <> opponent_id);

-- 3. Un solo perfil por usuario (1:1).
alter table clientes add constraint clientes_user_id_key unique (user_id);

-- 4. user_stats sin nulos y con rangos válidos.
update user_stats set
    xp                = coalesce(xp, 0),
    level             = coalesce(level, 1),
    coins             = coalesce(coins, 0),
    streak_current    = coalesce(streak_current, 0),
    streak_record     = coalesce(streak_record, 0),
    has_streak_shield = coalesce(has_streak_shield, false);
alter table user_stats
    alter column xp                set not null,
    alter column level             set not null,
    alter column coins             set not null,
    alter column streak_current    set not null,
    alter column streak_record     set not null,
    alter column has_streak_shield set not null,
    add constraint user_stats_xp_check     check (xp >= 0),
    add constraint user_stats_coins_check  check (coins >= 0),
    add constraint user_stats_level_check  check (level between 1 and 10),
    add constraint user_stats_streak_check check (streak_current >= 0 and streak_record >= streak_current);

-- 5. Amistades: estados válidos, sin auto-amistad y sin duplicado A→B / B→A.
alter table friendships
    alter column status set not null,
    add constraint friendships_status_check check (status in ('pending', 'accepted')),
    add constraint friendships_not_self     check (sender_id <> receiver_id);
create unique index friendships_pair_key
    on friendships (least(sender_id, receiver_id), greatest(sender_id, receiver_id));

-- 6. Otras columnas que no deberían ser nulas.
update study_content          set is_favorite = false where is_favorite is null;
update user_daily_missions    set progress = 0 where progress is null;
update user_daily_missions    set completed = false where completed is null;
update user_weekly_objectives set progress = 0 where progress is null;
update user_weekly_objectives set completed = false where completed is null;
update user_inventory         set is_equipped = false where is_equipped is null;
alter table study_content          alter column is_favorite set not null;
alter table user_daily_missions    alter column progress set not null, alter column completed set not null;
alter table user_weekly_objectives alter column progress set not null, alter column completed set not null;
alter table user_inventory         alter column is_equipped set not null;

-- 7. Índices: quitar duplicados e inútiles, agregar los que usan las consultas actuales.
alter table user_inventory drop constraint user_inventory_user_item_unique;   -- igual a la PK
drop index if exists idx_study_content_type;
drop index if exists idx_duels_status;
drop index if exists idx_study_content_user;                                  -- lo cubre el nuevo
create index idx_study_content_user_created    on study_content (user_id, created_at desc);
create index idx_chat_history_session          on chat_history (user_id, session_id, created_at);
create index idx_activity_results_user_content on activity_results (user_id, content_id);

-- 8. La fecha de las misiones la pone el backend (hora de Costa Rica), no la BD (UTC).
alter table user_daily_missions alter column date drop default;

commit;
