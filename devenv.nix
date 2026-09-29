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
    gnumake
    # Чтение регламентной/исходной документации конкурса.
    poppler-utils   # pdftotext/pdfinfo (PDF)
    pandoc          # docx → markdown/текст
    python3         # локальные скрипты (в т.ч. обработка наборов)
  ];

  # ---------------------------------------------------------------------------
  # Convenience scripts. Run `devenv shell` and use them directly.
  # Единый источник команд — корневой Makefile (`make help`); здесь только
  # тонкие обёртки, чтобы не дублировать логику. БД — docker-compose (ADR-0017).
  # ---------------------------------------------------------------------------
  scripts = {
    build.exec = "make build";
    compile.exec = "make compile";
    test.exec = "make test";
    verify.exec = "make verify";
    run.exec = "make run";
    dev.exec = "make dev";
    fe-install.exec = "make fe-install";
    fe-dev.exec = "make fe-dev";
    fe-build.exec = "make fe-build";
    fe-test.exec = "make fe-test";
    fe-preview.exec = "make fe-preview";
    db-up.exec = "make db-up";
    db-down.exec = "make db-down";
    db-logs.exec = "make db-logs";
    stop.exec = "make stop";
    up.exec = "make up";
    down.exec = "make down";
    fe-up.exec = "make fe-up";
    fe-logs.exec = "make fe-logs";
    ps.exec = "make ps";
  };

  enterShell = ''
    echo "Java:  $(java -version 2>&1 | head -n 1)"
    echo "Maven: $(mvn -v 2>/dev/null | head -n 1)"
    echo "Node:  $(node -v 2>/dev/null)  pnpm: $(pnpm -v 2>/dev/null)"
    echo "Make:  $(make --version 2>/dev/null | head -n 1)"
    echo "Команды: make help   (или devenv-обёртки: dev | build | test | verify | run | db-up | up | down | fe-up | …)"
  '';

  # Fast smoke check used by `devenv test` (CI / entering the shell).
  enterTest = ''
    java -version 2>&1 | grep -q 'version "11' && echo "OK: Java 11"
    mvn -v 2>/dev/null | grep -q 'Apache Maven' && echo "OK: Maven"
    node -v >/dev/null 2>&1 && echo "OK: Node"
    make --version >/dev/null 2>&1 && echo "OK: Make"
    pdftotext -v >/dev/null 2>&1 && echo "OK: pdftotext"
  '';

}
