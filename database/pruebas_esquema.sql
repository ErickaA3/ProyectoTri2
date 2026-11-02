\set ON_ERROR_STOP on
-- Datos mínimos para ejercitar restricciones y vistas.
insert into countries values ('CR', 'Costa Rica');

insert into users (id, username, email, password_hash) values
  ('00000000-0000-0000-0000-000000000001', 'ericka', 'e@x.com', 'h'),
  ('00000000-0000-0000-0000-000000000002', 'hans',   'h@x.com', 'h');
insert into user_profiles (user_id, full_name, country_code) values
  ('00000000-0000-0000-0000-000000000001', 'Ericka', 'CR'),
  ('00000000-0000-0000-0000-000000000002', 'Hans',   'CR');

insert into subjects (id, owner_id, name) values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Cálculo');
insert into topics (id, subject_id, name, position) values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Derivadas', 0);

insert into generated_content (id, topic_id, created_by, content_type_code, title) values
  ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'quiz', 'Quiz derivadas');
insert into content_items (id, content_id, position, prompt) values
  ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 0, 'd/dx x^2'),
  ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 1, 'd/dx sin x');
insert into item_options (id, item_id, position, text, is_correct) values
  ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 0, '2x', true),
  ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', 1, 'x',  false),
  ('50000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002', 0, 'cos x', true),
  ('50000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000002', 1, '-cos x', false);

-- Intento normal: 1 de 2 correctas
insert into attempts (id, user_id, content_id, status, started_at, finished_at, xp_awarded, coins_awarded) values
  ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'completed', now() - interval '1 day 1 hour', now() - interval '1 day', 30, 20);
insert into attempt_answers (attempt_id, item_id, selected_option_id, response_ms) values
  ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 3000),
  ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000004', 4000);

-- Duelo: ericka 2/2, hans 1/2
insert into duels (id, content_id, status, finished_at) values ('70000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'finished', now());
insert into attempts (id, user_id, content_id, status, finished_at) values
  ('60000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'completed', now()),
  ('60000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 'completed', now());
insert into attempt_answers (attempt_id, item_id, selected_option_id, response_ms) values
  ('60000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 1000),
  ('60000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000003', 1000),
  ('60000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 1000),
  ('60000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002', null, 30000);
insert into duel_participants (duel_id, user_id, role, attempt_id, xp_awarded, coins_awarded) values
  ('70000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'challenger', '60000000-0000-0000-0000-000000000002', 40, 40),
  ('70000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'opponent',   '60000000-0000-0000-0000-000000000003', -10, 8);

-- Misión y tienda
insert into missions (code, description, period, activity_type_code, required_count, xp_reward, coin_reward) values
  ('daily_quiz', 'Completá 1 quiz', 'daily', 'complete_quiz', 1, 50, 10),
  ('weekly_duel', 'Ganá 2 duelos', 'weekly', 'win_duel', 2, 100, 30);
insert into user_missions (user_id, mission_id, period_start)
select '00000000-0000-0000-0000-000000000001', id,
       case period when 'daily' then (now() at time zone 'America/Costa_Rica')::date - 1
                   else date_trunc('week', now() at time zone 'America/Costa_Rica')::date end
from missions;

insert into store_items (item_type_code, name, price) values ('background', 'Nebulosa', 50), ('streak_shield', 'Protector', 10);
insert into store_items (item_type_code, name, unlock_level) values ('frame', 'Marco Océano', 2);
insert into purchases (id, user_id, item_id, price_paid) values
  ('80000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 1, 50),
  ('80000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 2, 10);
insert into user_items (user_id, item_id, purchase_id) values ('00000000-0000-0000-0000-000000000001', 1, '80000000-0000-0000-0000-000000000001');
insert into user_consumables (purchase_id) values ('80000000-0000-0000-0000-000000000002');
insert into user_equipped (user_id, item_type_code, item_id) values ('00000000-0000-0000-0000-000000000001', 'background', 1);

\echo '--- vistas'
select attempt_id, total_items, correct_items, score_pct from v_attempt_scores order by attempt_id;
select user_id, correct_items, result from v_duel_results order by user_id;
select * from v_user_balances order by user_id;
select * from v_user_streaks order by user_id;
select mission_id, period_start, progress, required_count from v_user_mission_progress;
select username, xp, level, duel_wins from v_leaderboard order by username;

\echo '--- restricciones que deben fallar'
\set ON_ERROR_STOP off
-- equipar algo que no tiene
insert into user_equipped (user_id, item_type_code, item_id) values ('00000000-0000-0000-0000-000000000002', 'background', 1);
-- equipar un fondo en el slot de marco
insert into user_equipped (user_id, item_type_code, item_id) values ('00000000-0000-0000-0000-000000000001', 'frame', 1);
-- opción que no pertenece a la pregunta
insert into attempts (id, user_id, content_id) values ('60000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001');
insert into attempt_answers (attempt_id, item_id, selected_option_id, response_ms) values
  ('60000000-0000-0000-0000-000000000009', '40000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000003', 1);
-- amistad duplicada al revés (pareja desordenada)
insert into friendships (user_low_id, user_high_id, requested_by) values ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002');
-- tercer jugador en un duelo
insert into users (id, username, email, password_hash) values ('00000000-0000-0000-0000-000000000003', 'isaac', 'i@x.com', 'h');
insert into duel_participants (duel_id, user_id, role) values ('70000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', 'challenger');
-- gastar más monedas de las que tiene
insert into purchases (user_id, item_id, price_paid) values ('00000000-0000-0000-0000-000000000001', 1, 500);
-- responder un ítem de otro contenido
insert into generated_content (id, created_by, content_type_code, title) values ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'quiz', 'Otro');
insert into content_items (id, content_id, position, prompt) values ('40000000-0000-0000-0000-000000000009', '30000000-0000-0000-0000-000000000002', 0, 'otra');
insert into attempt_answers (attempt_id, item_id, response_ms) values ('60000000-0000-0000-0000-000000000009', '40000000-0000-0000-0000-000000000009', 1);
-- usar como intento de duelo el intento de otra persona
update duel_participants set attempt_id = '60000000-0000-0000-0000-000000000003' where user_id = '00000000-0000-0000-0000-000000000001';
-- misión con tipo inexistente (el bug actual de objectives)
insert into missions (code, description, period, activity_type_code, required_count, xp_reward, coin_reward) values ('x', 'x', 'weekly', 'flashcards', 1, 1, 1);
-- chat con tema y materia a la vez
insert into chat_sessions (user_id, subject_id, topic_id) values ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001');
