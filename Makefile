# Makefile - Orquestacion local del ecosistema ShopLab Helpdesk (LAB-110)
.DEFAULT_GOAL := help

GIT_BASE_URL ?= https://github.com/leandroAmariles
INFRA_DIR ?= ../shoplab-infra
REPOS = shoplab-gateway shoplab-chat-service shoplab-retrieval-service shoplab-catalog-service shoplab-frontend shoplab-ingest shoplab-eval shoplab-contracts shoplab-ci shoplab-infra

# El clúster de dev (shoplab-infra) llega a Ollama por 172.17.0.1:11434: por eso el puerto se publica siempre desde aquí.
# La GPU NVIDIA se detecta preguntando a Docker (un make de 32 bits en Windows no ve nvidia-smi); `GPU=0` la desactiva.
GPU ?= $(if $(findstring nvidia,$(shell docker info --format "{{json .Runtimes}}")),1,0)
COMPOSE = docker compose -f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml
ifeq ($(GPU),1)
COMPOSE += -f infra/docker-compose.gpu.yml
endif

.PHONY: help
help: ## Muestra la ayuda de comandos disponibles
	@echo "Uso: make <target> [VARIABLES]"
	@echo ""
	@echo "Targets disponibles:"
	@echo "  clone-all    Clona todos los repositorios hermanos en carpetas adyacentes (../)"
	@echo "  up           Levanta todo: PostgreSQL, Ollama, el cluster de dev y sus tuneles"
	@echo "  deps         Solo las dependencias del cluster en Docker: PostgreSQL y Ollama"
	@echo "  up-all       Levanta todo el sistema con Compose, sin cluster"
	@echo "  down         Cierra los tuneles y detiene el cluster y Compose, sin borrar volumenes"
	@echo "  status       Muestra el estado de los servicios de Compose"
	@echo "  test-e2e     Ejecuta las pruebas integradas de extremo a extremo (requiere up-all)"
	@echo ""
	@echo "Variables configurables:"
	@echo "  GIT_BASE_URL (por defecto: https://github.com/leandroAmariles)"
	@echo "  GPU          (1 o 0; por defecto 1 si Docker tiene el runtime nvidia)"
	@echo "  INFRA_DIR    (por defecto: ../shoplab-infra)"

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

# Las aplicaciones corren en el clúster; en Docker solo quedan sus dos dependencias. Los *-db-init crean, si faltan, la
# base y el rol de cada servicio, y terminan.
.PHONY: deps
deps: ## Levanta PostgreSQL y Ollama y prepara las bases de los servicios del clúster
	$(COMPOSE) up -d --wait postgres ollama
	$(COMPOSE) run --rm retrieval-db-init
	$(COMPOSE) run --rm chat-db-init
	$(COMPOSE) run --rm eval-db-init
	$(COMPOSE) run --rm catalog-db-init

# Primero las dependencias: el clúster las necesita al arrancar. El de shoplab-infra termina abriendo los túneles.
.PHONY: up
up: deps ## Levanta las dependencias, el clúster de dev y sus túneles
	$(MAKE) -C $(INFRA_DIR) up

# Publica los mismos puertos que los túneles del clúster (8080, 4000): no usar con `make forward` activo.
.PHONY: up-all
up-all: ## Levanta todo el sistema con Docker Compose, sin clúster
	$(COMPOSE) up -d --wait

# Sin `-v`: los volúmenes (bases, modelos y clúster) se conservan.
.PHONY: down
down: ## Cierra los túneles, detiene el clúster y retira los contenedores de Compose
	$(MAKE) -C $(INFRA_DIR) stop
	$(COMPOSE) down

.PHONY: status
status: ## Muestra el estado de los contenedores
	$(COMPOSE) ps

.PHONY: test-e2e
test-e2e: ## Ejecuta la verificacion de salud e integracion E2E
	@bash scripts/test-e2e.sh
