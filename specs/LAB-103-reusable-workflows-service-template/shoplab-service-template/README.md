# template-service

Plantilla de los microservicios Java de ShopLab Helpdesk (LAB-103, corregida en LAB-129): Spring Boot 4.x reactivo,
persistencia JDBC con un esquema propio (ADR-0011, ADR-0015), pipeline de `shoplab-ci` e imagen firmada.

## Crear un servicio

1. Genera el repositorio desde esta plantilla.
2. Cambia `template-service` por el nombre del servicio en `pom.xml`, `release-please-config.json`,
   `src/main/resources/application.yml` y `.github/workflows/`.
3. Añade `DEPLOY_APP_ID` (variable) y `DEPLOY_APP_PRIVATE_KEY` y `RELEASE_PLEASE_TOKEN` (secretos).
4. Tras la primera release, haz público el paquete de la imagen (no es reversible).

## Construir

```bash
./mvnw verify   # necesita Docker y acceso a GitHub Packages (servidor `github-shoplab-contracts` en settings.xml)
GITHUB_TOKEN=... docker build --secret id=github_token,env=GITHUB_TOKEN -t template-service .
```

No lleva autenticación hasta que exista el login (LAB-61).
