# Contrato: rutas de la aplicación

| Ruta | Pantalla | Dentro del layout | Notas |
|------|----------|-------------------|-------|
| `/` | Inicio (`features/home`) | Sí | Pantalla de bienvenida con el estado de la app |
| `/chat` | *(reservada)* | Sí | La añade LAB-14; no se crea en esta historia |
| `**` | No encontrado (`features/not-found`) | Sí | Siempre la última ruta; ofrece enlace a inicio |

**Reglas**

- Toda pantalla se carga con `loadComponent` (carga diferida) y declara un `title`.
- El layout (`layout/shell`) es la ruta padre; las pantallas son sus hijas.
- Servidor de contenedor: cualquier ruta que no sea un archivo estático se resuelve a `index.html` (ver
  `nginx.conf`), de modo que recargar `/chat` funciona.
