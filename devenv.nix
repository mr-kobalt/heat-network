{ pkgs, lib, config, inputs, ... }:

{
  # ---------------------------------------------------------------------------
  # Toolchain only. The competition mandates Java 11 + Spring Boot 2.6.3 + Maven
  # (docs/01-project/constraints.md). Do not bump without an ADR.
  # PostgreSQL/PostGIS is not run by devenv: the single database is provided by
  # docker-compose (ADR-0017). Use `db-up`.
  # ---------------------------------------------------------------------------
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk11;
    maven.enable = true;
  };

  # Frontend toolchain (опциональный визуализатор, ADR-0013).
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_22;
    pnpm.enable = true;
  };

  packages = with pkgs; [
    git
    jq
    curl
    unzip
  ];

  # ---------------------------------------------------------------------------
  # Convenience scripts. Run `devenv shell` and use them directly.
  # Database comes from docker-compose (ADR-0017).
  # ---------------------------------------------------------------------------
  scripts = {
    build.exec = "mvn -q -DskipTests package";
    test.exec = "mvn -q test";
    verify.exec = "mvn -q verify";
    run.exec = "mvn -q spring-boot:run";
    # Полный dev-режим: БД (docker-compose) + приложение + фронтенд-визуализатор.
    # Ctrl+C останавливает приложение и фронтенд; контейнер БД остаётся (db-down).
    dev.exec = ''
      set -m
      docker compose up -d db
      until docker compose exec -T db pg_isready -U heating >/dev/null 2>&1; do
        sleep 1
      done
      [ -d frontend/node_modules ] || pnpm --dir frontend install
      mvn -q spring-boot:run &
      APP_PID=$!
      # Ждём готовности API, иначе Vite-прокси сразу получит ECONNREFUSED.
      echo "Ожидание готовности API (http://localhost:8080/actuator/health)…"
      for _ in $(seq 1 600); do
        if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
          break
        fi
        if ! kill -0 $APP_PID 2>/dev/null; then
          echo "Приложение не запустилось (mvn spring-boot:run)." >&2
          exit 1
        fi
        sleep 1
      done
      curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1 || {
        echo "API не поднялся за 600 с." >&2
        exit 1
      }
      echo "API готов."
      pnpm --dir frontend dev &
      FE_PID=$!
      trap 'kill $APP_PID $FE_PID 2>/dev/null' EXIT INT TERM
      wait -n
    '';
    fe-dev.exec = "pnpm --dir frontend dev";
    fe-build.exec = "pnpm --dir frontend build";
    # Прод-раздача без Docker (sirv + production-сборка) для проверки подложки/тайлов.
    fe-preview.exec = "pnpm --dir frontend build && pnpm --dir frontend preview";
    db-up.exec = "docker compose up -d db";
    db-down.exec = "docker compose stop db";
    db-logs.exec = "docker compose logs -f db";
    # podman-compose неполно поддерживает `--profile`: включаем профиль через
    # COMPOSE_PROFILES, иначе сервис frontend не создаётся.
    stop.exec = "COMPOSE_PROFILES=frontend docker compose stop";
    up.exec = "docker compose up --build -d";
    down.exec = "COMPOSE_PROFILES=frontend docker compose stop; COMPOSE_PROFILES=frontend docker compose down --remove-orphans";
    # Полный стек (app + db + визуализатор, профиль frontend).
    fe-up.exec = "COMPOSE_PROFILES=frontend docker compose up --build -d";
    fe-logs.exec = "COMPOSE_PROFILES=frontend docker compose logs -f frontend";
    ps.exec = "COMPOSE_PROFILES=frontend docker compose ps";
  };

  enterShell = ''
    echo "Java:  $(java -version 2>&1 | head -n 1)"
    echo "Maven: $(mvn -v 2>/dev/null | head -n 1)"
    echo "Node:  $(node -v 2>/dev/null)  pnpm: $(pnpm -v 2>/dev/null)"
    echo "Scripts: dev | build | test | verify | run | fe-dev | fe-build | fe-preview | db-up | db-down | db-logs | up | down | stop | fe-up | fe-logs | ps"
  '';

  # Fast smoke check used by `devenv test` (CI / entering the shell).
  enterTest = ''
    java -version 2>&1 | grep -q 'version "11' && echo "OK: Java 11"
    mvn -v 2>/dev/null | grep -q 'Apache Maven' && echo "OK: Maven"
    node -v >/dev/null 2>&1 && echo "OK: Node"
  '';

}
