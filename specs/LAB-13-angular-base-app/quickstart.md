# Quickstart: validar la app Angular base

**Requisitos**: Node `22.22.3` o superior de la línea 22 (`node -v`; `nvm use` lee `frontend/.nvmrc`), y el backend
local (`cd backend && ./mvnw spring-boot:run`) para los escenarios 3 y 4.

## 1. Arranque, pruebas y build (US1)

```bash
cd frontend
npm ci
npm run lint
npm test            # el comando exacto lo fija T001 según el runner
npm run build       # genera dist/browser/index.html
npm start           # http://localhost:4200
```

**Esperado**: lint, pruebas y build terminan sin errores; la app abre sin errores en la consola del navegador.

## 2. Layout y rutas (US2, US3)

1. Abrir `http://localhost:4200/`: se ven cabecera, navegación y contenido con componentes Material tematizados.
2. Navegar a otra ruta: cabecera y navegación permanecen.
3. Abrir `http://localhost:4200/no-existe`: aparece la pantalla «no encontrado» dentro del layout.
4. Reducir el ancho a 360 px: la navegación pasa a modo superpuesto y sigue siendo utilizable.

## 3. Proxy (US4)

```bash
curl -i -N -X POST http://localhost:4200/api/chat \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"message":"hola"}'
```

**Esperado**: la petición a `/api/chat` llega al backend (la forma del cuerpo está en el spec de LAB-12) y los eventos
aparecen uno a uno con `-N`, sin esperar al final. Sin backend: error de conexión del proxy y la app sigue viva.

## 4. Contenedor (US5)

```bash
docker build -t shoplab-frontend frontend
docker run --rm -p 8081:8080 shoplab-frontend
```

**Esperado**: `http://localhost:8081/` sirve la app; recargar `http://localhost:8081/algo` devuelve la app (pantalla
404 de Angular) y no un 404 de nginx.
