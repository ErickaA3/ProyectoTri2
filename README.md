# ProyectoTri2
Proyecto de Quinto Trimestre
Isaac cambio

## Variables de entorno

| Variable | Requerida | Descripción |
|----------|-----------|-------------|
| `JWT_SECRET` | **Sí en producción** | Secreto HMAC para firmar/validar los JWT. Mínimo 32 caracteres (256 bits, HS256). Si no se define, se usa un secreto de desarrollo (solo local) y se imprime una advertencia. |

- **Railway / producción:** definir `JWT_SECRET` en las variables de entorno del servicio. Cambiar su valor **rota el secreto e invalida todos los tokens vigentes** (los usuarios deben volver a iniciar sesión).
- **Local:** exportar `JWT_SECRET`, o pasar `-Djwt.secret=...` a la JVM. Si no se define, se usa el fallback de desarrollo. 