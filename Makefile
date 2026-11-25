# Makefile - Orquestacion local del ecosistema ShopLab Helpdesk (LAB-110)
.DEFAULT_GOAL := help

GIT_BASE_URL ?= https://github.com/leandroAmariles
REPOS = shoplab-gateway shoplab-retrieval-service shoplab-frontend shoplab-ingest shoplab-eval shoplab-contracts shoplab-ci shoplab-infra

.PHONY: help
help: ## Muestra la ayuda de comandos disponibles
	@echo "Uso: make <target> [VARIABLES]"
	@echo ""
	@echo "Targets disponibles:"
	@echo "  clone-all    Clona todos los repositorios hermanos en carpetas adyacentes (../)"
	@echo "  up           Levanta todo el sistema con Compose en segundo plano"
	@echo "  down         Detiene todos los servicios de Compose"
	@echo "  status       Muestra el estado de los servicios de Compose"
	@echo "  test-e2e     Ejecuta las pruebas integradas de extremo a extremo"
	@echo ""
	@echo "Variables configurables:"
	@echo "  GIT_BASE_URL (por defecto: https://github.com/leandroAmariles)"

.PHONY: clone-all
clone-all: ## Clona todos los repositorios hermanos de forma idempotente
	@echo "==> Verificando y clonando repositorios hermanos en ../"
	@for repo in $(REPOS); do \
		if [ -d "../$$repo" ]; then \
			echo "  [SKIP] $$repo ya existe en ../$$repo"; \
		else \
			echo "  [CLONING] $$repo desde $(GIT_BASE_URL)/$$repo.git..."; \
			git clone $(GIT_BASE_URL)/$$repo.git ../$$repo || exit 1; \
			echo "  [CLONED] $$repo clonado exitosamente"; \
		fi \
	done
	@echo "==> Clonacion completada."

.PHONY: up
up: ## Levanta todo el sistema con Docker Compose
	docker compose -f infra/docker-compose.yml up -d --wait

.PHONY: down
down: ## Detiene los contenedores de Docker Compose
	docker compose -f infra/docker-compose.yml down

.PHONY: status
status: ## Muestra el estado de los contenedores
	docker compose -f infra/docker-compose.yml ps

.PHONY: test-e2e
test-e2e: ## Ejecuta la verificacion de salud e integracion E2E
	@bash scripts/test-e2e.sh
