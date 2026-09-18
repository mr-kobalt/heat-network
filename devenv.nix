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
    fe-dev.exec = "pnpm --dir frontend dev";
    fe-build.exec = "pnpm --dir frontend build";
    db-up.exec = "docker compose up -d db";
    db-down.exec = "docker compose down";
    db-logs.exec = "docker compose logs -f db";
    up.exec = "docker compose up --build -d";
    down.exec = "docker compose down";
  };

  enterShell = ''
    echo "Java:  $(java -version 2>&1 | head -n 1)"
    echo "Maven: $(mvn -v 2>/dev/null | head -n 1)"
    echo "Node:  $(node -v 2>/dev/null)  pnpm: $(pnpm -v 2>/dev/null)"
    echo "Scripts: build | test | verify | run | fe-dev | fe-build | db-up | db-down | db-logs | up | down"
  '';

  # Fast smoke check used by `devenv test` (CI / entering the shell).
  enterTest = ''
    java -version 2>&1 | grep -q 'version "11' && echo "OK: Java 11"
    mvn -v 2>/dev/null | grep -q 'Apache Maven' && echo "OK: Maven"
    node -v >/dev/null 2>&1 && echo "OK: Node"
  '';

}
