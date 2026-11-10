# Quickstart: validar la pantalla de administración de documentos (LAB-25)

Guía de validación de extremo a extremo. Contratos en [`contracts/documents-api.md`](contracts/documents-api.md); modelo en
[`data-model.md`](data-model.md).

## Prerrequisitos

- Stack local: `docker compose -f infra/docker-compose.yml up` (Postgres+pgvector, Ollama con `bge-m3` y el modelo de chat,
  `ingest` en `:8000`).
- Backend: `cd backend && mvn spring-boot:run` (puerto 8080). Frontend: `cd frontend && npm start` (proxy `/api` → 8080).
- Documentos de prueba del corpus del proyecto (LAB-18), p. ej. `data/knowledge-base/…/*.md` y un PDF.

## 1. Subida y progreso (US1, US2)

1. Abrir `http://localhost:4200/documentos`.
2. Elegir un Markdown válido. **Esperado**: barra de progreso que sube sin retroceder, luego «Procesando…», luego el
   documento en la lista con su número de chunks y estado «Pendiente».
3. Esperar. **Esperado**: sin recargar, la fila pasa a «Indexado» (< 10 s tras terminar los embeddings).
4. Ir a `/chat` y preguntar por el contenido. **Esperado**: respuesta con cita al documento subido.
5. Subir el mismo archivo otra vez. **Esperado**: aviso «actualizado», mismo `id`, estado vuelve a «Pendiente» y luego
   «Indexado».
6. Subir un archivo de ~10 MiB y cancelar a mitad. **Esperado**: la subida figura como cancelada y no aparece fila nueva.

## 2. Rechazos (US1)

| Prueba | Esperado |
|---|---|
| Archivo `.txt` | rechazo local con mensaje en español, sin petición |
| Archivo > 10 MiB | rechazo local «supera el tamaño máximo» |
| `.md` vacío (forzando la petición con `requests.http`) | 422 `SLHD_RETR_00003` |
| PDF escaneado sin texto | 422 `SLHD_RETR_00003`; nada en la base |
| Parar `ingest` y subir | 503 `SLHD_RETR_00004`; la pantalla ofrece «Reintentar» |

## 3. Lista y estados (US3)

1. Con documentos en los tres estados (provocar `ERROR` parando Ollama durante la indexación), abrir la pantalla.
   **Esperado**: cada fila con nombre, estado (texto + icono, no solo color), chunks; en `ERROR`, causa en español.
2. Base vacía (`DELETE FROM retrieval.document` en entorno local). **Esperado**: estado vacío con invitación a subir.
3. Parar el backend con la pantalla abierta. **Esperado**: se conserva la última lista y aparece aviso con «Reintentar».

## 4. Navegación y accesibilidad (US4)

- La navegación lateral muestra «Documentos» y lleva a `/documentos`.
- Ancho 360 px: sin scroll horizontal de la página.
- Solo teclado: elegir archivo, subir, cancelar y reintentar son operables; la barra tiene `role="progressbar"` con
  `aria-valuenow`; la tabla tiene cabeceras y nombre accesible.

## 5. Automatizado

```bash
# Backend (pruebas de unidad, contrato REST con WebTestClient y BlockHound)
mvn test -pl backend -am -Dskip.srcclr=true --no-transfer-progress
mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress
mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # cobertura >= 80 %

# Frontend
cd frontend && npm run lint && npm test -- --watch=false && npm run build
```

Además: `specs/LAB-25-document-admin-screen/requests.http` (generado al implementar) recorre los escenarios de la spec.

## 6. Métricas

`GET /actuator/prometheus` → `retrieval_upload_seconds_count{outcome="ok|rejected|failed"}` y
`retrieval_documents{status="PENDING|INDEXED|ERROR"}`; sin nombres de documento como etiqueta.
