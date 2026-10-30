-- Polaris: datos de los catalogos (misiones, objetivos, tienda).
-- Extraidos del backup de Supabase del 12-10-2026. No contiene datos de usuarios.

-- Datos de missions
COPY public.missions (id, description, type, required_count, xp_reward, coin_reward) FROM stdin;
1	Genera un set de flashcards	complete_flashcard	1	50	10
2	Completa un quiz	complete_quiz	1	60	15
3	Genera un resumen	complete_summary	1	40	10
4	Completa un examen experto	complete_expert_exam	1	100	25
5	Gana un duelo	win_duel	1	80	20
6	Usa la plataforma 3 días seguidos	maintain_streak	3	120	30
\.

-- Datos de objectives
COPY public.objectives (id, type, description, required_count, xp_reward, coin_reward) FROM stdin;
1	study_sessions	Completar 3 sesiones de estudio esta semana	3	100	25
2	flashcards	Repasar 20 flashcards esta semana	20	80	20
3	quizzes	Completar 2 quizzes esta semana	2	90	22
4	streak	Mantener racha de 5 días esta semana	5	120	30
\.

-- Datos de store_items
COPY public.store_items (id, name, type, cost, image_url) FROM stdin;
8	Noche Oscura	background	0	/images/backgrounds/bg-default.png
9	Galaxia	background	500	/images/backgrounds/bg-galaxy.png
10	Océano	background	550	/images/backgrounds/ocean-bg.png
11	Volcán	background	600	/images/backgrounds/volcano-bg.jpg
12	Amazonas	background	650	/images/backgrounds/forest-bg.jpg
13	Cielo Nocturno	background	700	/images/backgrounds/sky-bg.png
14	Lluvia Digital	background	750	/images/backgrounds/rain-bg.png
15	Aurora Boreal	background	800	/images/backgrounds/aurora-bg.jpg
16	Streak Protector	streak_shield	300	/images/backgrounds/shield.png
\.

SELECT pg_catalog.setval('public.missions_id_seq', 6, true);

SELECT pg_catalog.setval('public.objectives_id_seq', 4, true);

SELECT pg_catalog.setval('public.store_items_id_seq', 16, true);
