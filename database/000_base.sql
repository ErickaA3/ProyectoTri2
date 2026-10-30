-- Polaris: estructura base del esquema public.
-- Extraida del backup de Supabase del 12-10-2026 (pg_dumpall).
-- Solo estructura: sin datos, sin permisos (ACL) ni duenos.
-- gen_random_uuid() viene incluido en PostgreSQL 13+, no hace falta pgcrypto.

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- TABLE: activity_results
CREATE TABLE public.activity_results (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    content_id uuid,
    score numeric(5,2),
    max_score numeric(5,2),
    time_taken_seconds integer,
    completed_at timestamp without time zone DEFAULT now()
);

-- TABLE: chat_history
CREATE TABLE public.chat_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    role character varying(10) NOT NULL,
    message text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    session_id uuid,
    CONSTRAINT chat_history_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);

-- TABLE: clientes
CREATE TABLE public.clientes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    full_name character varying,
    country character varying,
    language character varying DEFAULT 'es'::character varying,
    birthdate date,
    created_at timestamp without time zone DEFAULT now()
);

-- TABLE: duel_answers
CREATE TABLE public.duel_answers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    duel_id uuid NOT NULL,
    user_id uuid NOT NULL,
    question_index integer,
    answer_given character varying(10),
    is_correct boolean,
    time_ms integer
);

-- TABLE: duels
CREATE TABLE public.duels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    challenger_id uuid NOT NULL,
    opponent_id uuid NOT NULL,
    winner_id uuid,
    content_id uuid,
    status character varying(20) DEFAULT 'pending'::character varying,
    created_at timestamp without time zone DEFAULT now(),
    finished_at timestamp without time zone,
    topic text,
    question_count integer DEFAULT 10,
    challenger_score numeric,
    challenger_time integer,
    opponent_score numeric,
    opponent_time integer,
    time_per_question integer DEFAULT 30,
    CONSTRAINT duels_status_check CHECK (((status)::text = ANY ((ARRAY['waiting_opponent'::character varying, 'in_progress'::character varying, 'finished'::character varying, 'expired'::character varying, 'declined'::character varying])::text[])))
);

-- TABLE: friendships
CREATE TABLE public.friendships (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sender_id uuid NOT NULL,
    receiver_id uuid NOT NULL,
    status character varying(20) DEFAULT 'pending'::character varying,
    created_at timestamp without time zone DEFAULT now()
);

-- TABLE: leaderboard_history
CREATE TABLE public.leaderboard_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    period_type character varying NOT NULL,
    period_start date NOT NULL,
    xp_snapshot integer NOT NULL,
    rank_position integer,
    created_at timestamp without time zone DEFAULT now()
);

-- TABLE: missions
CREATE TABLE public.missions (
    id integer NOT NULL,
    description character varying(200) NOT NULL,
    type character varying(50) NOT NULL,
    required_count integer DEFAULT 1,
    xp_reward integer DEFAULT 50,
    coin_reward integer DEFAULT 10
);

-- SEQUENCE: missions_id_seq
CREATE SEQUENCE public.missions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: missions_id_seq
ALTER SEQUENCE public.missions_id_seq OWNED BY public.missions.id;

-- TABLE: objectives
CREATE TABLE public.objectives (
    id integer NOT NULL,
    type character varying(50) NOT NULL,
    description character varying(200) NOT NULL,
    required_count integer DEFAULT 1 NOT NULL,
    xp_reward integer DEFAULT 50 NOT NULL,
    coin_reward integer DEFAULT 10 NOT NULL
);

-- SEQUENCE: objectives_id_seq
CREATE SEQUENCE public.objectives_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: objectives_id_seq
ALTER SEQUENCE public.objectives_id_seq OWNED BY public.objectives.id;

-- TABLE: professor_config
CREATE TABLE public.professor_config (
    user_id uuid NOT NULL,
    avatar_item_id integer,
    background_item_id integer,
    professor_name character varying(50) DEFAULT 'Poli'::character varying,
    personality character varying(50) DEFAULT 'friendly'::character varying,
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT professor_config_personality_check CHECK (((personality)::text = ANY ((ARRAY['strict'::character varying, 'friendly'::character varying, 'funny'::character varying])::text[])))
);

-- TABLE: store_items
CREATE TABLE public.store_items (
    id integer NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(30) NOT NULL,
    cost integer NOT NULL,
    image_url character varying(255),
    CONSTRAINT store_items_type_check CHECK (((type)::text = ANY ((ARRAY['avatar'::character varying, 'background'::character varying, 'streak_shield'::character varying])::text[])))
);

-- SEQUENCE: store_items_id_seq
CREATE SEQUENCE public.store_items_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: store_items_id_seq
ALTER SEQUENCE public.store_items_id_seq OWNED BY public.store_items.id;

-- TABLE: study_content
CREATE TABLE public.study_content (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    title character varying(200),
    content jsonb NOT NULL,
    is_favorite boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT now(),
    session_id uuid,
    source_text text,
    embedding public.vector(1536),
    CONSTRAINT study_content_type_check CHECK (((type)::text = ANY ((ARRAY['flashcard'::character varying, 'quiz'::character varying, 'summary'::character varying, 'schema'::character varying, 'exam'::character varying, 'expert_exam'::character varying, 'duel_quiz'::character varying])::text[])))
);

-- TABLE: user_daily_missions
CREATE TABLE public.user_daily_missions (
    user_id uuid NOT NULL,
    mission_id integer NOT NULL,
    date date DEFAULT CURRENT_DATE NOT NULL,
    progress integer DEFAULT 0,
    completed boolean DEFAULT false
);

-- TABLE: user_inventory
CREATE TABLE public.user_inventory (
    user_id uuid NOT NULL,
    item_id integer NOT NULL,
    purchased_at timestamp without time zone DEFAULT now(),
    is_equipped boolean DEFAULT false
);

-- TABLE: user_stats
CREATE TABLE public.user_stats (
    user_id uuid NOT NULL,
    xp integer DEFAULT 0,
    level integer DEFAULT 1,
    coins integer DEFAULT 0,
    streak_current integer DEFAULT 0,
    streak_record integer DEFAULT 0,
    streak_last_activity date,
    has_streak_shield boolean DEFAULT false
);

-- TABLE: user_weekly_objectives
CREATE TABLE public.user_weekly_objectives (
    user_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    week_start date NOT NULL,
    objective_description character varying(200),
    required_count integer,
    progress integer DEFAULT 0,
    completed boolean DEFAULT false,
    xp_reward integer,
    coin_reward integer
);

-- TABLE: users
CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    username character varying(50) NOT NULL,
    email character varying(100) NOT NULL,
    password_hash character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);

-- DEFAULT: missions id
ALTER TABLE ONLY public.missions ALTER COLUMN id SET DEFAULT nextval('public.missions_id_seq'::regclass);

-- DEFAULT: objectives id
ALTER TABLE ONLY public.objectives ALTER COLUMN id SET DEFAULT nextval('public.objectives_id_seq'::regclass);

-- DEFAULT: store_items id
ALTER TABLE ONLY public.store_items ALTER COLUMN id SET DEFAULT nextval('public.store_items_id_seq'::regclass);

-- CONSTRAINT: activity_results activity_results_pkey
ALTER TABLE ONLY public.activity_results
    ADD CONSTRAINT activity_results_pkey PRIMARY KEY (id);

-- CONSTRAINT: chat_history chat_history_pkey
ALTER TABLE ONLY public.chat_history
    ADD CONSTRAINT chat_history_pkey PRIMARY KEY (id);

-- CONSTRAINT: clientes clientes_pkey
ALTER TABLE ONLY public.clientes
    ADD CONSTRAINT clientes_pkey PRIMARY KEY (id);

-- CONSTRAINT: duel_answers duel_answers_pkey
ALTER TABLE ONLY public.duel_answers
    ADD CONSTRAINT duel_answers_pkey PRIMARY KEY (id);

-- CONSTRAINT: duels duels_pkey
ALTER TABLE ONLY public.duels
    ADD CONSTRAINT duels_pkey PRIMARY KEY (id);

-- CONSTRAINT: friendships friendships_pkey
ALTER TABLE ONLY public.friendships
    ADD CONSTRAINT friendships_pkey PRIMARY KEY (id);

-- CONSTRAINT: friendships friendships_sender_id_receiver_id_key
ALTER TABLE ONLY public.friendships
    ADD CONSTRAINT friendships_sender_id_receiver_id_key UNIQUE (sender_id, receiver_id);

-- CONSTRAINT: leaderboard_history leaderboard_history_pkey
ALTER TABLE ONLY public.leaderboard_history
    ADD CONSTRAINT leaderboard_history_pkey PRIMARY KEY (id);

-- CONSTRAINT: leaderboard_history leaderboard_history_user_id_period_type_period_start_key
ALTER TABLE ONLY public.leaderboard_history
    ADD CONSTRAINT leaderboard_history_user_id_period_type_period_start_key UNIQUE (user_id, period_type, period_start);

-- CONSTRAINT: missions missions_pkey
ALTER TABLE ONLY public.missions
    ADD CONSTRAINT missions_pkey PRIMARY KEY (id);

-- CONSTRAINT: objectives objectives_pkey
ALTER TABLE ONLY public.objectives
    ADD CONSTRAINT objectives_pkey PRIMARY KEY (id);

-- CONSTRAINT: objectives objectives_type_key
ALTER TABLE ONLY public.objectives
    ADD CONSTRAINT objectives_type_key UNIQUE (type);

-- CONSTRAINT: professor_config professor_config_pkey
ALTER TABLE ONLY public.professor_config
    ADD CONSTRAINT professor_config_pkey PRIMARY KEY (user_id);

-- CONSTRAINT: store_items store_items_pkey
ALTER TABLE ONLY public.store_items
    ADD CONSTRAINT store_items_pkey PRIMARY KEY (id);

-- CONSTRAINT: study_content study_content_pkey
ALTER TABLE ONLY public.study_content
    ADD CONSTRAINT study_content_pkey PRIMARY KEY (id);

-- CONSTRAINT: user_daily_missions user_daily_missions_pkey
ALTER TABLE ONLY public.user_daily_missions
    ADD CONSTRAINT user_daily_missions_pkey PRIMARY KEY (user_id, mission_id, date);

-- CONSTRAINT: user_inventory user_inventory_pkey
ALTER TABLE ONLY public.user_inventory
    ADD CONSTRAINT user_inventory_pkey PRIMARY KEY (user_id, item_id);

-- CONSTRAINT: user_inventory user_inventory_user_item_unique
ALTER TABLE ONLY public.user_inventory
    ADD CONSTRAINT user_inventory_user_item_unique UNIQUE (user_id, item_id);

-- CONSTRAINT: user_stats user_stats_pkey
ALTER TABLE ONLY public.user_stats
    ADD CONSTRAINT user_stats_pkey PRIMARY KEY (user_id);

-- CONSTRAINT: user_weekly_objectives user_weekly_objectives_pkey
ALTER TABLE ONLY public.user_weekly_objectives
    ADD CONSTRAINT user_weekly_objectives_pkey PRIMARY KEY (user_id, type, week_start);

-- CONSTRAINT: users users_email_key
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

-- CONSTRAINT: users users_pkey
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

-- CONSTRAINT: users users_username_key
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);

-- INDEX: idx_activity_results_user
CREATE INDEX idx_activity_results_user ON public.activity_results USING btree (user_id);

-- INDEX: idx_chat_history_user
CREATE INDEX idx_chat_history_user ON public.chat_history USING btree (user_id, created_at DESC);

-- INDEX: idx_daily_missions_date
CREATE INDEX idx_daily_missions_date ON public.user_daily_missions USING btree (user_id, date);

-- INDEX: idx_duel_answers_duel
CREATE INDEX idx_duel_answers_duel ON public.duel_answers USING btree (duel_id);

-- INDEX: idx_duels_challenger
CREATE INDEX idx_duels_challenger ON public.duels USING btree (challenger_id);

-- INDEX: idx_duels_opponent
CREATE INDEX idx_duels_opponent ON public.duels USING btree (opponent_id);

-- INDEX: idx_duels_status
CREATE INDEX idx_duels_status ON public.duels USING btree (status);

-- INDEX: idx_friendships_receiver
CREATE INDEX idx_friendships_receiver ON public.friendships USING btree (receiver_id, status);

-- INDEX: idx_friendships_sender
CREATE INDEX idx_friendships_sender ON public.friendships USING btree (sender_id, status);

-- INDEX: idx_leaderboard_history_period
CREATE INDEX idx_leaderboard_history_period ON public.leaderboard_history USING btree (period_type, period_start);

-- INDEX: idx_study_content_fav
CREATE INDEX idx_study_content_fav ON public.study_content USING btree (user_id, is_favorite);

-- INDEX: idx_study_content_type
CREATE INDEX idx_study_content_type ON public.study_content USING btree (type);

-- INDEX: idx_study_content_user
CREATE INDEX idx_study_content_user ON public.study_content USING btree (user_id);

-- INDEX: idx_user_stats_xp
CREATE INDEX idx_user_stats_xp ON public.user_stats USING btree (xp DESC);

-- INDEX: idx_weekly_obj_week
CREATE INDEX idx_weekly_obj_week ON public.user_weekly_objectives USING btree (user_id, week_start);

-- INDEX: study_content_embedding_idx
CREATE INDEX study_content_embedding_idx ON public.study_content USING hnsw (embedding public.vector_cosine_ops);

-- FK CONSTRAINT: activity_results activity_results_content_id_fkey
ALTER TABLE ONLY public.activity_results
    ADD CONSTRAINT activity_results_content_id_fkey FOREIGN KEY (content_id) REFERENCES public.study_content(id) ON DELETE SET NULL;

-- FK CONSTRAINT: activity_results activity_results_user_id_fkey
ALTER TABLE ONLY public.activity_results
    ADD CONSTRAINT activity_results_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: chat_history chat_history_user_id_fkey
ALTER TABLE ONLY public.chat_history
    ADD CONSTRAINT chat_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: clientes clientes_user_id_fkey
ALTER TABLE ONLY public.clientes
    ADD CONSTRAINT clientes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: duel_answers duel_answers_duel_id_fkey
ALTER TABLE ONLY public.duel_answers
    ADD CONSTRAINT duel_answers_duel_id_fkey FOREIGN KEY (duel_id) REFERENCES public.duels(id) ON DELETE CASCADE;

-- FK CONSTRAINT: duel_answers duel_answers_user_id_fkey
ALTER TABLE ONLY public.duel_answers
    ADD CONSTRAINT duel_answers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);

-- FK CONSTRAINT: duels duels_challenger_id_fkey
ALTER TABLE ONLY public.duels
    ADD CONSTRAINT duels_challenger_id_fkey FOREIGN KEY (challenger_id) REFERENCES public.users(id);

-- FK CONSTRAINT: duels duels_content_id_fkey
ALTER TABLE ONLY public.duels
    ADD CONSTRAINT duels_content_id_fkey FOREIGN KEY (content_id) REFERENCES public.study_content(id);

-- FK CONSTRAINT: duels duels_opponent_id_fkey
ALTER TABLE ONLY public.duels
    ADD CONSTRAINT duels_opponent_id_fkey FOREIGN KEY (opponent_id) REFERENCES public.users(id);

-- FK CONSTRAINT: duels duels_winner_id_fkey
ALTER TABLE ONLY public.duels
    ADD CONSTRAINT duels_winner_id_fkey FOREIGN KEY (winner_id) REFERENCES public.users(id);

-- FK CONSTRAINT: friendships friendships_receiver_id_fkey
ALTER TABLE ONLY public.friendships
    ADD CONSTRAINT friendships_receiver_id_fkey FOREIGN KEY (receiver_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: friendships friendships_sender_id_fkey
ALTER TABLE ONLY public.friendships
    ADD CONSTRAINT friendships_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: leaderboard_history leaderboard_history_user_id_fkey
ALTER TABLE ONLY public.leaderboard_history
    ADD CONSTRAINT leaderboard_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: professor_config professor_config_avatar_item_id_fkey
ALTER TABLE ONLY public.professor_config
    ADD CONSTRAINT professor_config_avatar_item_id_fkey FOREIGN KEY (avatar_item_id) REFERENCES public.store_items(id);

-- FK CONSTRAINT: professor_config professor_config_background_item_id_fkey
ALTER TABLE ONLY public.professor_config
    ADD CONSTRAINT professor_config_background_item_id_fkey FOREIGN KEY (background_item_id) REFERENCES public.store_items(id);

-- FK CONSTRAINT: professor_config professor_config_user_id_fkey
ALTER TABLE ONLY public.professor_config
    ADD CONSTRAINT professor_config_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: study_content study_content_user_id_fkey
ALTER TABLE ONLY public.study_content
    ADD CONSTRAINT study_content_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_daily_missions user_daily_missions_mission_id_fkey
ALTER TABLE ONLY public.user_daily_missions
    ADD CONSTRAINT user_daily_missions_mission_id_fkey FOREIGN KEY (mission_id) REFERENCES public.missions(id);

-- FK CONSTRAINT: user_daily_missions user_daily_missions_user_id_fkey
ALTER TABLE ONLY public.user_daily_missions
    ADD CONSTRAINT user_daily_missions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_inventory user_inventory_item_id_fkey
ALTER TABLE ONLY public.user_inventory
    ADD CONSTRAINT user_inventory_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.store_items(id);

-- FK CONSTRAINT: user_inventory user_inventory_user_id_fkey
ALTER TABLE ONLY public.user_inventory
    ADD CONSTRAINT user_inventory_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_stats user_stats_user_id_fkey
ALTER TABLE ONLY public.user_stats
    ADD CONSTRAINT user_stats_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_weekly_objectives user_weekly_objectives_user_id_fkey
ALTER TABLE ONLY public.user_weekly_objectives
    ADD CONSTRAINT user_weekly_objectives_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- ROW SECURITY: activity_results
ALTER TABLE public.activity_results ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: chat_history
ALTER TABLE public.chat_history ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: clientes
ALTER TABLE public.clientes ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: duel_answers
ALTER TABLE public.duel_answers ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: duels
ALTER TABLE public.duels ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: friendships
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: leaderboard_history
ALTER TABLE public.leaderboard_history ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: missions
ALTER TABLE public.missions ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: objectives
ALTER TABLE public.objectives ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: professor_config
ALTER TABLE public.professor_config ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: store_items
ALTER TABLE public.store_items ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: study_content
ALTER TABLE public.study_content ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: user_daily_missions
ALTER TABLE public.user_daily_missions ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: user_inventory
ALTER TABLE public.user_inventory ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: user_stats
ALTER TABLE public.user_stats ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: user_weekly_objectives
ALTER TABLE public.user_weekly_objectives ENABLE ROW LEVEL SECURITY;

-- ROW SECURITY: users
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
