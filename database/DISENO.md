# Base de datos de Polaris: diseño objetivo

Este documento explica el esquema de [`esquema_objetivo.sql`](esquema_objetivo.sql): qué cambia respecto a la base actual de Supabase, por qué, y cómo se llega ahí.

`esquema_objetivo.sql` es el destino, **no una migración**. No se corre contra producción. Las migraciones se escriben a partir de él (ver [Plan de migración](#plan-de-migración)).

[`pruebas_esquema.sql`](pruebas_esquema.sql) carga datos de ejemplo, consulta todas las vistas e intenta 10 operaciones inválidas que la base tiene que rechazar.

## Principios

1. **Tercera forma normal (3FN).** Cada columna depende de la llave, de toda la llave y de nada más que la llave.
2. **Nada derivable se guarda.** Puntajes, XP, monedas, nivel, racha, progreso de misiones y ganador de un duelo se calculan con vistas (`v_*`) a partir de los hechos. Así no pueden quedar desincronizados.
3. **La base hace cumplir las reglas, no solo el código.** Si una regla se puede expresar como llave, `CHECK` o trigger, va en la base.
4. **Los tipos que usa el código son catálogos con llave foránea.** Un tipo de misión, de contenido o de ítem que no existe en su catálogo no se puede guardar. Esto elimina el bug actual de los objetivos semanales.
5. **Convenciones:** tablas en inglés, `snake_case`, plural; `timestamptz` para fechas y horas; `uuid` para entidades; `code` de texto para catálogos.

## Qué es 3FN y qué no

3FN significa que dentro de una tabla no hay columnas que dependan de otra columna que no es llave. Por ejemplo, en la base actual `user_weekly_objectives` copia `objective_description`, `required_count` y las recompensas desde `objectives`: esos datos dependen del tipo de objetivo, no de la asignación al usuario. Eso es una violación.

3FN **no** significa cero repetición entre tablas. Un sistema profesional normalizado puede tener, documentadas, algunas copias deliberadas por rendimiento. En este diseño hay **una sola**: `review_states` (ver [Desnormalizaciones deliberadas](#desnormalizaciones-deliberadas)).

## Mapa del esquema

| Área | Tablas |
|---|---|
| Catálogos | `roles`, `languages`, `countries`, `activity_types`, `content_types`, `item_types`, `levels` |
| Usuarios | `users`, `user_roles`, `user_profiles`, `assistant_settings` |
| Carpetas y RAG | `subjects`, `topics`, `documents`, `document_chunks` |
| Contenido generado | `generated_content`, `content_sources`, `content_items`, `item_options`, `favorites` |
| Aprendizaje | `attempts`, `attempt_answers`, `review_states` |
| Duelos | `duels`, `duel_participants` |
| Social | `friendships`, `study_groups`, `group_members`, `group_subjects` |
| Aulas | `classrooms`, `classroom_members`, `classroom_subjects`, `assignments`, `assignment_submissions`, `announcements` |
| Profesor IA | `chat_sessions`, `chat_messages`, `chat_message_sources` |
| Misiones y saldo | `missions`, `user_missions`, `balance_adjustments` |
| Tienda | `store_items`, `purchases`, `user_items`, `user_consumables`, `user_equipped` |
| Vistas | `v_attempt_scores`, `v_duel_results`, `v_user_balances`, `v_user_active_days`, `v_user_streaks`, `v_activity_events`, `v_user_mission_progress`, `v_leaderboard` |

## De la base actual a la nueva

| Hoy | Problema | En el diseño |
|---|---|---|
| `clientes` | Sin `UNIQUE(user_id)`: un usuario puede tener varios perfiles. Nombre en español. | `user_profiles`, con `user_id` como PK (1:1). Suma `time_zone`. `country` pasa a ser código ISO con catálogo. |
| `users` | Sin rol real (el JWT siempre dice `student`). | `user_roles` (un usuario puede ser estudiante y profesor). `username` y `email` como `citext`: no se distinguen mayúsculas. |
| `user_stats` | Guarda XP, nivel, monedas y racha, todos derivables. Columnas nulas. Sin control de concurrencia. | Se elimina. Todo sale de `v_user_balances` y `v_user_streaks`. |
| `study_content` | Un registro mezcla tipo, JSON con preguntas y respuestas, texto fuente repetido, embedding, favorito y `session_id` suelto. | `generated_content` (en un tema), `content_items` + `item_options` (cada pregunta o tarjeta es una fila), `content_sources` (de qué fragmentos salió) y `favorites` (por usuario). |
| `source_text` repetido en cada contenido | El mismo documento se guarda tantas veces como contenidos se generen. | `documents` + `document_chunks`: el texto se guarda una vez, dividido y con embedding. |
| `embedding` en `study_content` | Se calcula sobre el resultado generado, no sobre el material. | `document_chunks.embedding`. |
| `activity_results` | Solo guarda el primer intento y un puntaje total. Sin detalle por pregunta. | `attempts` (todos los intentos) + `attempt_answers` (cada respuesta). El puntaje sale de `v_attempt_scores`. |
| `duels` | Puntajes, tiempos y ganador guardados como columnas. Dos jugadores fijos en columnas. `content_id` sin `ON DELETE`, por eso borrar el historial borra duelos. Default `pending` fuera del `CHECK`. | `duels` + `duel_participants` (cada jugador tiene su intento). Ganador y puntaje salen de `v_duel_results`. El contenido usa borrado lógico (`deleted_at`), así que los duelos no se pierden. |
| `duel_answers` | Vacía. | Desaparece: las respuestas de duelo son `attempt_answers` del intento de cada jugador. |
| `friendships` | Permite A→B y B→A al mismo tiempo. `status` sin `CHECK`. | La pareja se guarda ordenada (`user_low_id < user_high_id`) y `requested_by` dice quién la pidió. |
| `missions` + `objectives` | Dos tablas con la misma estructura. `type` es texto libre que no coincide con el código. | Una sola `missions` con `period` (`daily` / `weekly`) y llave foránea a `activity_types`. |
| `user_daily_missions` + `user_weekly_objectives` | Progreso guardado. El semanal copia descripción y recompensas del catálogo. | `user_missions`: solo la asignación y lo que se pagó al completarla. El progreso sale de `v_user_mission_progress`. |
| `store_items` | Los marcos por nivel están en JavaScript, no en la base. | `store_items` con `price` o `unlock_level`, y catálogo `item_types`. `levels` reemplaza los umbrales que hoy están en Java. |
| `user_inventory` | El protector de racha se modela como ítem único + booleano en `user_stats`. `is_equipped` no impide equipar algo que no se tiene. | `purchases` (cada gasto), `user_items` (ítems permanentes), `user_consumables` (cada protector, con el día que cubrió) y `user_equipped` (un ítem por tipo). |
| `professor_config` | Mezcla ajustes del chat con ítems de tienda. | `assistant_settings` (nombre y personalidad). Lo visual va en `user_equipped`. |
| `chat_history` | `session_id` no apunta a nada. | `chat_sessions` (con alcance: tema, materia o todo) + `chat_messages` + `chat_message_sources` (citas). |
| `leaderboard_history` | Solo la usa el Wrapped para sacar fotos del XP. `rank_position` nunca se llena. | Se elimina. El XP de cualquier período se calcula desde `attempts`, `duel_participants` y `user_missions`. |
| `timestamp` sin zona | Java escribe horas en la zona de cada máquina. | `timestamptz` en todo. |
| `CURRENT_DATE` en el SQL (UTC) | Las misiones se desfasan de 6 p.m. a medianoche en Costa Rica. | Los días se calculan en las vistas con `user_profiles.time_zone`. |

## Decisiones importantes

### Por qué los puntajes, XP y monedas no se guardan
Hoy `user_stats` guarda el XP y las monedas. Si una actualización falla a medias, o dos llegan al mismo tiempo, el número queda mal y no hay forma de saber cuál era el correcto.

En el diseño nuevo, cada evento guarda lo que pagó: `attempts.xp_awarded`, `duel_participants.xp_awarded`, `user_missions.xp_awarded`. Las compras guardan `purchases.price_paid`. Las correcciones manuales van en `balance_adjustments`, siempre con un motivo. El saldo es la suma de todo eso. Esto también resuelve el Wrapped (el XP de la semana es una suma con filtro de fecha) y permite auditar qué ganó cada usuario y por qué.

Lo pagado **sí** se guarda en cada evento, aunque parezca calculable con `activity_types.base_xp`, porque las reglas van a cambiar: el XP que se dio en marzo con las reglas de marzo es un hecho, no un cálculo.

### Por qué cada pregunta es una fila
Para el repaso espaciado, "explicame lo que fallé" y la vista de profesor, la base tiene que saber qué pregunta se falló. Con las preguntas dentro de un JSON eso no se puede consultar ni relacionar. Los resúmenes y esquemas sí siguen en `generated_content.body` (JSONB), porque son documentos de lectura que no se responden.

La corrección de una respuesta no se guarda en `attempt_answers`: sale de `item_options.is_correct`. Una llave compuesta `(item_id, selected_option_id)` garantiza que la opción elegida es de esa pregunta.

### Por qué el servidor califica
El front manda qué opción eligió, no si acertó ni el porcentaje. Hoy el porcentaje lo manda el navegador.

### Borrado lógico del contenido
`generated_content.deleted_at`: cuando alguien "borra" un contenido, desaparece de su biblioteca, pero sus intentos y los duelos que lo usaron se conservan. Las cascadas físicas quedan solo para borrar una cuenta completa.

### Reglas que garantiza la base
Probadas en `pruebas_esquema.sql`:
- No se puede equipar un ítem que no se tiene, ni ponerlo en el lugar de otro tipo (por ejemplo, un fondo como marco).
- No se puede responder con una opción de otra pregunta, ni responder una pregunta de otro contenido.
- No se puede duplicar una amistad.
- Un duelo tiene como máximo dos jugadores, y el intento de cada uno tiene que ser suyo y sobre el contenido del duelo.
- No se puede comprar sin monedas suficientes, ni con dos compras simultáneas que gasten el mismo saldo.
- No se puede crear una misión con un tipo de actividad que no existe.
- Un chat no puede tener tema y materia a la vez.

Las tres últimas reglas que no caben en una llave usan triggers: `check_coin_balance`, `check_answer_item` y `check_duel_attempt`.

## Desnormalizaciones deliberadas

| Dato | Por qué se guarda |
|---|---|
| `review_states` | El estado del repaso espaciado (próxima fecha, intervalo, facilidad) se podría recalcular desde `attempt_answers`, pero el algoritmo es iterativo y se consulta cada vez que se abre el dashboard. Se actualiza en la misma transacción en que se guarda la respuesta. |

Si en el futuro alguna vista se vuelve lenta (por ejemplo `v_user_balances` con muchos usuarios), la solución profesional es una vista materializada o una tabla de caché mantenida por trigger, agregada a esta lista. No hace falta con el volumen actual (6 usuarios, 44 contenidos).

## Lo que no se valida en la base (y por qué)
- **Que el tipo de contenido de un duelo o una asignación tenga preguntas** (`content_types.is_answerable`). Se podría con un trigger, pero es una regla de interfaz que conviene mostrar con un mensaje claro desde el backend.
- **Que `user_missions.period_start` sea lunes para misiones semanales.** Lo calcula el backend al asignar.
- **Que solo un usuario con rol `teacher` cree aulas.** Es una regla de permisos del backend.

## Migraciones (Flyway)

Las migraciones viven en [`src/main/resources/db/migration/`](../src/main/resources/db/migration/). Flyway las aplica en orden al arrancar la app y anota cada una en la tabla `flyway_schema_history`, así nunca corre dos veces la misma.

- **Solo corren si la variable de entorno `DB_MIGRATE=true`.** Se define en Railway. Localmente queda apagado, para que nadie aplique a la base compartida una migración de su rama a medio hacer.
- **Nunca se edita una migración ya aplicada.** Si hay que corregir algo, se escribe una migración nueva (`V4__...`).
- **Nombres:** `V<número>__<descripcion_con_guiones_bajos>.sql` (dos guiones bajos).
- **Mientras dure la reestructuración, las migraciones se aplican a mano** en el SQL Editor de Supabase. `BASELINE_VERSION` en `DatabaseMigrationListener` es la última aplicada así (hoy, la V7). La primera vez que la app arranque con `DB_MIGRATE=true`, Flyway marca producción como "al día" hasta esa versión y solo ejecuta las siguientes. En una base vacía corre todas desde la V1. Cada migración aplicada a mano sube ese número en el mismo commit.

## Plan de migración

Hoy hay 6 usuarios y 44 contenidos: es el momento más barato para hacerlo.

1. **`V1__baseline.sql`** (hecho): el esquema de Supabase como estaba antes de la V2, para poder levantar una base idéntica desde cero.
2. **`V2__arreglos_urgentes.sql`** (aplicada en Supabase el 2026-11-02):
   - alinear `objectives.type` con los tipos que usa `GamificationService`;
   - `duels.content_id ... ON DELETE SET NULL`;
   - `UNIQUE (user_id)` en `clientes`;
   - `NOT NULL` y rangos válidos en `user_stats`, amistades sin duplicados e índices.
   - En Java: misiones y objetivos usan la fecha de Costa Rica en vez de `CURRENT_DATE`.
La reestructuración va **por fases**: cada fase es una migración más el código que la usa, y la app funciona al terminar cada una. Las tablas viejas quedan sin uso y se borran todas juntas al final.

3. **Fase 1 · `V3__usuarios_perfiles_roles.sql`** (aplicada): `roles`, `languages`, `countries`, `user_roles`, `user_profiles` (reemplaza a `clientes`) y `assistant_settings` (reemplaza a `professor_config`). `username` y `email` pasan a `citext`; los usernames repetidos en mayúsculas reciben sufijo.
4. **Fase 2 · `V4__materias_temas_contenido.sql`:** `content_types`, `subjects`, `topics`, `documents`, `document_chunks`, `generated_content` (reemplaza a `study_content`), `content_sources` y `favorites`.
   - Cada usuario con contenido recibe una materia "Sin clasificar" con un tema "General".
   - `source_text` pasa a un documento por sesión, dividido en fragmentos de 2000 caracteres. El backend calcula sus embeddings al arrancar.
   - El chat (RAG) busca en los fragmentos del material original, no en lo generado.
   - Transitorio: las preguntas siguen en `generated_content.body` hasta la fase 3, y el quiz de un duelo es tipo `duel_quiz` sin tema hasta la fase 4.
   - Contrato de la API para el front: [`docs/api-materias.md`](../docs/api-materias.md).
5. **Fase 3 · `V5__intentos_respuestas.sql`:** `content_items` e `item_options` (extraídas del JSON; el JSON sigue para dibujar), `attempts`, `attempt_answers` y la vista `v_attempt_scores`.
   - El servidor califica quiz, examen experto y flashcards: el front manda `answers` y se ignora el porcentaje.
   - Se guardan todos los intentos con sus respuestas; se sigue pagando solo el primero (eso cambia en la fase 5).
   - Cada `activity_results` pasa a un intento completado sin respuestas, con su porcentaje en `legacy_score_pct`.
6. **Fase 4 · `V6__duelos_participantes.sql`:** `duel_participants` (cada jugador juega un intento sobre el quiz del duelo, así que sus respuestas se guardan) y la vista `v_duels` (puntaje = respuestas correctas, tiempo = duración del intento). `duels.winner_id` se mantiene como resultado oficial. Los duelos anteriores conservan su puntaje en `legacy_score` / `legacy_time_secs`.
7. **Fase 5 · `V7__gamificacion_tienda.sql`:** XP, monedas, nivel, racha y progreso de misiones dejan de guardarse; se calculan (`v_xp_events`, `v_user_balances`, `v_user_streaks`, `v_user_mission_progress` y `v_user_stats`, que tiene las mismas columnas que la vieja `user_stats`).
   - Cada evento guarda lo que pagó: `attempts`, `duel_participants` y `user_missions`. El saldo anterior quedó en `balance_adjustments` ("Saldo migrado").
   - `levels` reemplaza los umbrales fijos en Java. `missions` tiene diarias y semanales (`period`) y apunta a `activity_types`.
   - Tienda: `item_types`, `purchases`, `user_items`, `user_consumables` (protectores) y `user_equipped`. La base impide equipar lo que no se tiene.
   - **La racha se reinició** (decisión del equipo). Generar contenido ya no da XP.
8. **Fase 6:** chat con `chat_sessions` y alcance.
9. **Limpieza:** se borran `study_content`, `clientes`, `professor_config` y el resto de tablas viejas.

## Supabase
- **RLS:** mantener RLS activo y sin políticas en todas las tablas nuevas. El backend se conecta con un usuario que salta RLS; con la clave pública de Supabase no se puede leer nada. Esa es la configuración correcta mientras todo pase por el backend Java.
- **pgvector:** el índice HNSW de `document_chunks` es global y las búsquedas filtran por tema. Para que el filtro no devuelva menos resultados de los pedidos, activar `SET hnsw.iterative_scan = relaxed_order` en la sesión que busca (pgvector 0.8 lo soporta).
- **`citext`:** hay que habilitar la extensión (viene incluida en Supabase).
