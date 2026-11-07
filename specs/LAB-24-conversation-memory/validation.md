# Validación: memoria de la conversación (LAB-24)

Fecha: 2026-11-07. Entorno: backend local (`./mvnw spring-boot:run`) contra el PostgreSQL y el Ollama del compose
(`llama3.2:3b` para el chat, `bge-m3` para los embeddings) con `data/kb` indexada (24 documentos `INDEXED`).

## Pruebas automáticas

| Comprobación | Resultado |
|---|---|
| `./mvnw verify -Dskip.srcclr=true` (Checkstyle, PMD, Modulith/ArchUnit, BlockHound, JaCoCo) | verde; «All coverage checks have been met» |
| BlockHound | activo en toda la suite; las pruebas de memoria (`JdbcConversationMemoryAdapterTests`, `ChatMemoryIntegrationTests`, `ChatMemoryDownIntegrationTests`) recorren lectura y escritura sin detecciones |
| `cd frontend && npm test -- --watch=false` | 18 archivos, 290 pruebas en verde |
| `npm run lint`, `npm run build` | sin errores ni avisos nuevos |
| Pruebas de contrato de LAB-12, LAB-22 y LAB-23 | pasan sin cambiar sus expectativas (`ChatStreamContractTests`, `ChatCitationsIntegrationTests`, `RagAnsweringIntegrationTests`) |

## SC-001 / SC-002: 10 pares «pregunta + seguimiento» con el modelo real

Cada par se hizo con un `conversationId` nuevo (pregunta, luego seguimiento) y, como control, el mismo seguimiento con
otro identificador (sin historial). Primer token con historial: 1,3–2,2 s; respuesta completa: 2,8–5,1 s (SC-005 < 5 s
para el primer token).

| # | Tema | Seguimiento | Fuentes con historial | Sin historial |
|---|---|---|---|---|
| 1 | envíos | ¿y si es internacional? | Preguntas frecuentes sobre envíos, Costos de envío | sin fuentes |
| 2 | envíos | ¿y si compro más de una cosa? | Preguntas frecuentes sobre envíos, Costos de envío | sin fuentes |
| 3 | devoluciones | ¿y si ya lo usé? | Preguntas frecuentes sobre devoluciones, Política de devoluciones, Cambios y cancelaciones | sin fuentes |
| 4 | devoluciones | ¿y si pagué con tarjeta? | Devoluciones (FAQ), Problemas con el pago, Pagos (FAQ), Cambios y cancelaciones | fuentes de pagos (otro tema) |
| 5 | pagos | ¿y con cuántas cuotas máximo? | Pago en cuotas, Pagos (FAQ) | Pago en cuotas (la pregunta ya se entiende sola) |
| 6 | pagos | ¿y si quiero pagar con boleto? | Boleto y vales, Métodos de pago | Boleto y vales (se entiende sola) |
| 7 | garantía | ¿y la extendida? | Garantía extendida, Garantías (FAQ) | Garantía extendida |
| 8 | garantía | ¿y qué documentos necesito? | Garantías (FAQ), Garantía legal, Cómo reclamar la garantía | sin fuentes |
| 9 | privacidad | ¿y para qué los usan? | Privacidad (FAQ), Política de privacidad | sin fuentes |
| 10 | privacidad | ¿y cuánto tardan? | Privacidad (FAQ), Tus derechos sobre los datos | fuentes de garantías y envíos (otro tema) |

- **SC-002**: los 10 seguimientos recuperaron fragmentos del documento del tema de la conversación (10/10 ≥ 80 %). Sin
  historial, 6 de 10 seguimientos no encontraron nada y 2 encontraron documentos de otro tema: es la prueba de que la
  consulta compuesta (`pregunta anterior + actual`) es lo que sostiene el seguimiento.
- **SC-001**: 10/10 respuestas tratan el tema sin pedir aclaración (≥ 8/10). El par 1 responde que el contexto no
  menciona envíos internacionales, y es correcto: `data/kb` no contiene la palabra «internacional». Calidad del modelo
  pequeño a vigilar: en el par 2 la respuesta inventa un matiz («siempre responsable») sobre el envío exprés, y en el par
  10 dice que no hay plazo cuando el contexto trae datos de conservación; son límites de `llama3.2:3b`, no de la
  memoria, y los medirá la evaluación de F2.

## Otras comprobaciones con el servidor real

- **Persistencia (SC-004)**: las 30 filas de `chat.conversation_turn` (10 pares × 3 peticiones) se guardaron antes de
  `done`; el `conversation_id` de cada una existe en la tabla. El reinicio del servicio está cubierto por
  `ChatMemoryIntegrationTests.theTurnsSurviveANewMemoryInstanceOverTheSameDatabase`.
- **Métricas (SC-008)**: `shoplab_chat_history_messages_count` = 30 (suma 20 mensajes), `shoplab_chat_history_trimmed_total`
  = 0, sin etiquetas con identificadores.
- **Registros (SC-008)**: ninguno de los 20 `conversationId` usados aparece en el log del backend, y no hay texto de
  preguntas ni respuestas (se buscaron «internacional», «cuotas» y «garant»: 0 coincidencias). Hay un registro INFO por
  petición con `requestId`, `turns`, `characters` y `trimmed`.
- Los datos de la validación se borraron de la base local al terminar.

## Pendiente

- Revisión manual de la pantalla de chat (teclado, lector de pantalla, móvil) del botón «Nueva conversación» y de la
  conversación de varios turnos en el navegador: no se hizo en esta sesión. Está cubierto por `chat.spec.ts`
  (clic, foco, deshabilitado en vacío, texto en español).
- Primer token con el historial al límite configurado (5 turnos): los pares de arriba tienen un turno de historial; con el
  límite lleno el prompt crece hasta unos 4 000 caracteres más, dentro de `num-ctx` 8192. Conviene medirlo en el entorno
  `dev`.
