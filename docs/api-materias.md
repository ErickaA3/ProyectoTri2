# API de materias y temas ("Mis materias")

Contrato entre back y front para las carpetas del estudiante. Todas las rutas usan el JWT (`Authorization: Bearer <token>`) y responden JSON con `success`.

Una **materia** es una carpeta (Cálculo, Biología). Adentro tiene **temas** (Derivadas, Integrales). Todo lo que se genera vive dentro de un tema. Archivar no borra nada: el contenido sigue existiendo, solo deja de mostrarse.

## Listar materias con sus temas

`GET /api/subjects`

```json
{
  "success": true,
  "subjects": [
    {
      "id": "75315736-30fd-4d0a-8e8f-7c05e9b23e04",
      "name": "Cálculo",
      "color": "#3550C9",
      "topics": [
        { "id": "68eab3be-…", "name": "Derivadas",  "position": 0, "contentCount": 3, "documentCount": 1 },
        { "id": "0f04b160-…", "name": "Integrales", "position": 1, "contentCount": 0, "documentCount": 0 }
      ]
    },
    {
      "id": "9dd9df09-…",
      "name": "Sin clasificar",
      "color": null,
      "topics": [ { "id": "ed288be3-…", "name": "General", "position": 0, "contentCount": 12, "documentCount": 4 } ]
    }
  ]
}
```

- Ordenadas por nombre; los temas, por `position`.
- `color` puede ser `null`.
- **"Sin clasificar / General"** es donde cae todo lo que se genera sin elegir tema (y todo el contenido que existía antes de esta fase). Se crea sola.

## Crear materia

`POST /api/subjects`

```json
{ "name": "Cálculo", "color": "#3550C9" }
```

- `name`: obligatorio, de 1 a 100 caracteres.
- `color`: opcional, formato `#RRGGBB`.
- Responde `201` con `{ "success": true, "id": "…" }`.
- `409` si el usuario ya tiene una materia con ese nombre, incluso archivada.

## Editar materia

`PUT /api/subjects/{id}`

```json
{ "name": "Cálculo I", "color": "#9A6A00" }
```

Los dos campos son opcionales: se cambia solo lo que venga. Responde `200`; `404` si no existe o no es del usuario; `409` si el nombre ya existe.

## Archivar materia

`DELETE /api/subjects/{id}` → `200`, o `404`.

## Crear tema dentro de una materia

`POST /api/subjects/{id}/topics`

```json
{ "name": "Derivadas" }
```

Responde `201` con `{ "success": true, "id": "…" }`. El tema queda al final (`position` siguiente). `404` si la materia no existe o no es del usuario; `409` si ya hay un tema con ese nombre en esa materia.

## Renombrar tema

`PUT /api/topics/{id}` con `{ "name": "Regla de la cadena" }` → `200`, `404` o `409`.

## Archivar tema

`DELETE /api/topics/{id}` → `200`, o `404`.

## Generar contenido dentro de un tema

`POST /modo-estudio/generar` (la ruta que ya existe) acepta un campo nuevo, **`topicId`**:

- **JSON:** `{ "topicId": "…", "options": [...], "text": "...", "configs": {...} }`
- **Archivo (multipart):** un parámetro más, `topicId`.

Si no se manda `topicId`, el contenido va a "Sin clasificar / General", igual que hasta ahora. Si se manda uno que no existe o no es del usuario: `404`.

La respuesta agrega `topicId` y `documentId`. El material subido (archivo o texto) se guarda como documento del tema: es lo que usa el profesor IA para responder.

## Cambios en respuestas que ya existían

- **`GET /api/historial`:** cada elemento trae además `topicId`, `topicName`, `subjectId` y `subjectName`. Ya no incluye el quiz de los duelos (antes se filtraba en el front).
- **`GET /api/historial/{id}`:** trae `topicId` en lugar de `sessionId`.
- **`GET /api/summaries?id=…`:** trae `topicId`, `topicName`, `subjectId` y `subjectName` en lugar de `sessionId`.
- **Borrar contenido** (uno o todo el historial) ahora es borrado lógico: deja de verse, pero sus resultados y duelos se conservan.

## Chat del profesor IA con alcance

`POST /api/chat` acepta, al **empezar** una conversación (sin `sessionId`), un alcance opcional:

- `topicId`: el profesor IA busca solo en el material de ese tema.
- `subjectId`: busca en toda esa materia.
- Ninguno: busca en todo el material del estudiante (como hasta ahora).

Si vienen los dos, manda el tema. El alcance queda guardado en la sesión: los mensajes siguientes con el mismo `sessionId` no necesitan repetirlo. Tema o materia que no existe o no es del usuario: `404`.

La respuesta agrega `topicId` / `subjectId` (si la sesión tiene alcance) y **`sources`**: de qué material salió la respuesta.

```json
{
  "reply": "La regla de la cadena dice...",
  "sessionId": "c0000000-…",
  "topicId": "68eab3be-…",
  "sources": [
    { "subject": "Cálculo", "topic": "Derivadas", "document": "clase3.pdf" }
  ]
}
```

En la lista de conversaciones (`GET /api/chat`), `firstMessage` ahora es el título de la sesión (el primer mensaje, recortado a 100 caracteres).
