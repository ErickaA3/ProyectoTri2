# Base de datos

Estructura de la base de Polaris (PostgreSQL en Supabase), sacada del backup del 12-10-2026.

| Archivo | Qué tiene |
|---|---|
| `000_base.sql` | Las 17 tablas del esquema `public`: columnas, claves, índices y RLS. Sin datos. |
| `001_catalogos.sql` | Datos de los catálogos: `missions`, `objectives` y `store_items`. |
| `002_protector_vence_24h.sql` | Agrega `user_stats.streak_shield_until`: el protector de racha vence 24 h después de comprarlo. |

Para armar una base nueva, se corren en orden:

```bash
psql "$DATABASE_URL" -f database/000_base.sql -f database/001_catalogos.sql -f database/002_protector_vence_24h.sql
```

En la base que ya existe (Supabase) solo hace falta correr los archivos que todavía no se aplicaron, empezando por `002`.

Cada cambio nuevo al esquema va en un archivo numerado (`002_...sql`, `003_...sql`) y se aplica en ese orden. No se cambian tablas a mano en Supabase sin agregar el archivo.

El backup completo tiene datos reales de usuarios (correos, contraseñas cifradas, chats). No se sube al repo.
