# Quickstart: validar las citas a la fuente

Guía de validación; el contrato está en [contracts/sources-event.md](contracts/sources-event.md) y los tipos en
[data-model.md](data-model.md).

## Requisitos

- Java 21 y Maven; Node ^22.22.3 o ^24.15.0 para el frontend; Docker (Testcontainers).
- Para la prueba manual: `docker compose -f infra/docker-compose.yml up` (PostgreSQL, Ollama con `llama3.2:3b` y `bge-m3`,
  `ingest`) y `data/kb` indexada (documentos `INDEXED`, como en LAB-21/LAB-22).

## 1. Pruebas automáticas

```bash
# Backend (módulos chat y retrieval; sin Ollama real)
cd backend && ./mvnw test -Dskip.srcclr=true --no-transfer-progress
./mvnw verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # cobertura JaCoCo ≥ 80 %

# Frontend
cd frontend && npm test -- --watch=false
npm run lint
```

Deben pasar sin cambios las pruebas de contrato de LAB-12 y LAB-22 (SC-006).

## 2. Evento `sources` desde la terminal

```bash
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"message":"Cuántos días tengo para devolver un producto"}'
```

(En Windows, `curl.exe` y el JSON en un archivo UTF-8 con `--data-binary @mensaje.json` si el mensaje lleva acentos.)

Esperado: primero `event: sources` con 1–4 fuentes (`number` desde 1, `title` legible como «Política de devoluciones»,
`section`, `text`), luego `event: token`\* con marcas `[n]` en el texto y por último `event: done`.

| Caso | Mensaje | Esperado |
|---|---|---|
| Con contexto | «Cuántos días tengo para devolver un producto» | `sources` + tokens con ≥ 1 `[n]` + `done` |
| Sin contexto | «Cuál es la capital de Francia» | Mensaje fijo de «sin información»; **sin** evento `sources` |
| Saludo | «Hola» | Igual que el caso anterior (modo `fixed-message`) |

## 3. Interfaz (manual, `npm start` con el backend en marcha)

1. Preguntar «¿Cuántos días tengo para devolver un producto?». Mientras se escribe, las marcas `[1]` ya aparecen como botones
   y se puede abrir una (Historia 3).
2. Al terminar, bajo la respuesta aparece «Fuentes» con «1. Política de devoluciones › …». El número coincide con las citas.
3. Clic en `[1]` del texto y en la fuente 1 de la lista: se abre el mismo fragmento con título, sección y texto completo.
4. Cerrar con Escape: el foco vuelve al botón; el borrador del campo de entrada sigue igual.
5. Abrir `[1]` y, sin cerrar, `[2]`: solo hay un diálogo abierto.
6. Solo teclado: Tab llega a las citas y a la lista; Enter y Espacio las abren; un lector anuncia «Fuente 1: Política de
   devoluciones».
7. Móvil (ancho ≤ 480 px, herramientas del navegador): el diálogo cabe en pantalla y el texto largo se desplaza.
8. Pregunta fuera de tema: respuesta de «sin información», sin lista de fuentes ni elementos vacíos.
9. Detener una respuesta a mitad: se conservan las citas ya escritas y su lista.

## 4. Citas inválidas y ausentes (modelo simulado en pruebas)

Cubierto por pruebas automáticas (`CitationTrackerTests`, `ChatCitationsIntegrationTests`, `markdown-renderer.spec.ts`,
`message-bubble.spec.ts`): `[7]` con 4 fuentes queda como texto; una respuesta con contexto sin ninguna marca muestra
«Fuentes consultadas»; `[1, 2]` y `[1-2]` se expanden; una marca partida entre tokens no parpadea.

## 5. Métricas

```bash
curl -s http://localhost:8080/actuator/prometheus | grep shoplab_chat_citations
```

Esperado tras varias preguntas: `shoplab_chat_citations_total{outcome="valid"}` crece con las respuestas citadas;
`invalid` y `none` reflejan los fallos del modelo. Los registros del backend no contienen texto de preguntas ni de fragmentos.

## 6. Verificación con el modelo real (SC-001 / SC-002)

Hacer ≥ 10 preguntas cubiertas por `data/kb` (devoluciones, pagos, envíos, privacidad, garantía) y anotar en
`validation.md` (de esta historia): si hay cita válida (objetivo ≥ 90 %) y si el fragmento citado contiene el dato
(objetivo ≥ 80 %). Si `llama3.2:3b` cita mal, ajustar el texto de la regla de citas por configuración y repetir; no es un
fallo del sistema porque la validación es independiente del modelo.
