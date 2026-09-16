{ pkgs, lib, config, inputs, ... }:

{
  # ---------------------------------------------------------------------------
  # Toolchain.
  # The competition mandates Java 11 + Spring Boot 2.6.3 + Maven (see
  # docs/01-project/constraints.md). Do not bump these without an ADR.
  # ---------------------------------------------------------------------------
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk11;
    maven.enable = true;
  };

  packages = with pkgs; [
    git
    jq
    curl
    unzip
  ];

  # ---------------------------------------------------------------------------
  # Local PostgreSQL with PostGIS. Started on demand via `devenv up`.
  # ---------------------------------------------------------------------------
  services.postgres = {
    enable = true;
    extensions = extensions: [ extensions.postgis ];
    initialDatabases = [ { name = "heating"; } ];
  };

  # ---------------------------------------------------------------------------
  # Convenience scripts. Run `devenv shell` and use them directly.
  # ---------------------------------------------------------------------------
  scripts = {
    build.exec = "mvn -q -DskipTests package";
    test.exec = "mvn -q test";
    run.exec = "mvn -q spring-boot:run";
    verify.exec = "mvn -q verify";
  };

  enterShell = ''
    echo "Java:   $(java -version 2>&1 | head -n 1)"
    echo "Maven:  $(mvn -v 2>/dev/null | head -n 1)"
    echo "PG:     host=$PGHOST port=$PGPORT user=$PGUSER db=heating"
    echo "Scripts: build | test | run | verify"
  '';

  # Fast smoke check used by `devenv test` (CI / entering the shell).
  enterTest = ''
    java -version 2>&1 | grep -q 'version "11' && echo "OK: Java 11"
    mvn -v 2>/dev/null | grep -q 'Apache Maven' && echo "OK: Maven"
    test -n "$PGHOST" && echo "OK: PostgreSQL env"
  '';

}
