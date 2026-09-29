# Heating routing service — единый интерфейс сборки, тестов, запуска и деплоя.
#
# Использование:
#   make help          список команд
#   make verify        полная проверка (Maven)
#   make dev           локальный dev: БД в Docker + приложение + визуализатор
#   make fe-up         весь стек (app + db + визуализатор) в Docker
#
# Требуется только GNU make; JDK 11 и Maven подтягиваются через Maven Wrapper
# (`./mvnw`), Node/pnpm — для визуализатора (опционально). Окружение nix/devenv
# не обязательно: devenv-скрипты — тонкие обёртки над этими же целями.

# Портативно: bash есть и на NixOS, и на Ubuntu; /bin/sh может быть dash.
SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

MVN       ?= ./mvnw
PNPM      ?= pnpm
FRONTEND  := frontend
HEALTH    := http://localhost:8080/actuator/health

# Автоопределение docker compose (плагин v2 / docker-compose v1 / podman-compose).
COMPOSE := $(shell \
  if docker compose version >/dev/null 2>&1; then echo "docker compose"; \
  elif command -v docker-compose >/dev/null 2>&1; then echo "docker-compose"; \
  elif command -v podman-compose >/dev/null 2>&1; then echo "podman-compose"; \
  else echo "docker compose"; fi)

# podman-compose неполно поддерживает `--profile`: включаем профиль через env.
FRONTEND_PROFILE := COMPOSE_PROFILES=frontend

##@ Общее

.PHONY: help
help: ## Показать этот список команд
	@awk 'BEGIN {FS = ":.*##"; printf "Использование: make <команда>\n\n"} \
		/^[a-zA-Z0-9_.-]+:.*##/ {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2} \
		/^##@/ {printf "\n\033[1m%s\033[0m\n", substr($$0, 5)}' $(MAKEFILE_LIST)

.PHONY: clean
clean: ## Очистить артефакты backend и frontend
	$(MVN) -B -q clean
	rm -rf $(FRONTEND)/dist $(FRONTEND)/tsconfig.tsbuildinfo

##@ Backend (Java 11 / Spring Boot)

.PHONY: compile
compile: ## Компиляция
	$(MVN) -B -DskipTests compile

.PHONY: build
build: ## Сборка jar (без тестов)
	$(MVN) -B -DskipTests package

.PHONY: test
test: ## Быстрые тесты (slow исключены)
	$(MVN) -B test

.PHONY: test-slow
test-slow: ## Полный набор тестов, включая slow
	$(MVN) -B test -Dsurefire.excludedGroups= -Dgroups=slow

.PHONY: verify
verify: ## Полная проверка Maven (compile + tests + package)
	$(MVN) -B verify

.PHONY: run
run: ## Запустить сервис локально (нужна БД: make db-up)
	$(MVN) -q spring-boot:run

##@ Frontend (опциональный визуализатор, ADR-0013)

.PHONY: fe-install
fe-install: ## Установить зависимости фронтенда
	$(PNPM) --dir $(FRONTEND) install

.PHONY: fe-dev
fe-dev: ## Dev-сервер визуализатора (Vite)
	$(PNPM) --dir $(FRONTEND) dev

.PHONY: fe-build
fe-build: ## Production-сборка визуализатора
	$(PNPM) --dir $(FRONTEND) build

.PHONY: fe-test
fe-test: ## Тесты визуализатора (vitest)
	$(PNPM) --dir $(FRONTEND) test

.PHONY: fe-preview
fe-preview: ## Сборка и предпросмотр визуализатора без Docker
	$(PNPM) --dir $(FRONTEND) build && $(PNPM) --dir $(FRONTEND) preview

##@ База данных (PostgreSQL + PostGIS, docker-compose)

.PHONY: db-up
db-up: ## Поднять контейнер БД
	$(COMPOSE) up -d db

.PHONY: db-down
db-down: ## Остановить контейнер БД
	$(COMPOSE) stop db

.PHONY: db-logs
db-logs: ## Логи контейнера БД
	$(COMPOSE) logs -f db

##@ Docker-стек (app + db, опционально frontend)

.PHONY: up
up: ## Поднять app + db (пересборка)
	$(COMPOSE) up --build -d

.PHONY: fe-up
fe-up: ## Поднять весь стек app + db + визуализатор
	$(FRONTEND_PROFILE) $(COMPOSE) up --build -d

.PHONY: stop
stop: ## Остановить контейнеры (без удаления)
	$(FRONTEND_PROFILE) $(COMPOSE) stop

.PHONY: down
down: ## Остановить и удалить стек (podman-safe)
	$(FRONTEND_PROFILE) $(COMPOSE) stop
	$(FRONTEND_PROFILE) $(COMPOSE) down --remove-orphans

.PHONY: ps
ps: ## Статус контейнеров
	$(FRONTEND_PROFILE) $(COMPOSE) ps

.PHONY: fe-logs
fe-logs: ## Логи контейнера визуализатора
	$(FRONTEND_PROFILE) $(COMPOSE) logs -f frontend

.PHONY: compose-config
compose-config: ## Проверить валидность docker-compose
	$(COMPOSE) config

.PHONY: deploy
deploy: fe-up ## Деплой стека на сервере (app + db + визуализатор)

##@ Локальная разработка

.PHONY: dev
dev: db-up fe-install ## БД + приложение + визуализатор (Ctrl+C — стоп)
	@echo "Ожидание готовности API ($(HEALTH))…"
	@set -m; \
	$(MVN) -q spring-boot:run & APP_PID=$$!; \
	for _ in $$(seq 1 600); do \
		if curl -fsS $(HEALTH) >/dev/null 2>&1; then break; fi; \
		if ! kill -0 $$APP_PID 2>/dev/null; then \
			echo "Приложение не запустилось (spring-boot:run)." >&2; exit 1; \
		fi; \
		sleep 1; \
	done; \
	curl -fsS $(HEALTH) >/dev/null 2>&1 || { echo "API не поднялся за 600 с." >&2; exit 1; }; \
	echo "API готов."; \
	$(PNPM) --dir $(FRONTEND) dev & FE_PID=$$!; \
	trap 'kill $$APP_PID $$FE_PID 2>/dev/null' EXIT INT TERM; \
	wait -n
