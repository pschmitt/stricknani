# NixOS VM test of the pre-upgrade backup (`services.stricknani.backup.beforeUpgrade`).
#
# The server package is replaced by a stub that only creates a small SQLite
# database and sleeps, so the test needs no Rust build: it exercises the
# module's ExecStartPre script, sandboxing and permissions.
{ pkgs, module }:
let
  stub = pkgs.writeShellApplication {
    name = "stricknani-server";
    runtimeInputs = [
      pkgs.sqlite
      pkgs.coreutils
    ];
    text = ''
      if [[ ! -f /var/lib/stricknani/stricknani.sqlite3 ]]
      then
        sqlite3 /var/lib/stricknani/stricknani.sqlite3 \
          "PRAGMA journal_mode=WAL; CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('hello');"
      fi
      exec sleep infinity
    '';
  };
  # A different store path per version (the script text embeds it).
  withVersion =
    version:
    pkgs.symlinkJoin {
      name = "stricknani-stub-${version}";
      paths = [ stub ];
      passthru.version = version;
      meta.mainProgram = "stricknani-server";
    };
in
pkgs.testers.runNixOSTest {
  name = "stricknani-predeploy-backup";
  nodes.machine =
    { lib, ... }:
    {
      imports = [ module ];
      services.stricknani = {
        enable = true;
        package = withVersion "1.0.0";
        port = 7674;
      };
      specialisation.upgraded.configuration.services.stricknani.package = lib.mkForce (
        withVersion "2.0.0"
      );
    };
  testScript = ''
    def backups():
        return machine.succeed("ls -1 /var/lib/stricknani/backups | grep '^predeploy-' || true").split()

    machine.wait_for_unit("stricknani.service")
    # First installation: no database yet, so no backup, but the package is recorded.
    assert backups() == [], backups()
    machine.succeed("test -s /var/lib/stricknani/.predeploy-backup-package")
    # The stub creates its database right after systemd considers it started.
    machine.wait_until_succeeds(
        "${pkgs.sqlite}/bin/sqlite3 /var/lib/stricknani/stricknani.sqlite3 'SELECT v FROM t;'"
    )

    # A restart with the same package does nothing.
    machine.succeed("systemctl restart stricknani.service")
    machine.wait_for_unit("stricknani.service")
    assert backups() == [], backups()

    # Switching to a different package backs up before the new service starts.
    machine.succeed("/run/current-system/specialisation/upgraded/bin/switch-to-configuration test")
    machine.wait_for_unit("stricknani.service")
    found = backups()
    assert len(found) == 1 and found[0].startswith("predeploy-2.0.0-"), found
    path = "/var/lib/stricknani/backups/" + found[0]
    assert machine.succeed(f"stat -c '%a %U' {path}").strip() == "600 stricknani"
    assert machine.succeed(f"${pkgs.sqlite}/bin/sqlite3 {path} 'PRAGMA integrity_check;'").strip() == "ok"
    assert machine.succeed(f"${pkgs.sqlite}/bin/sqlite3 {path} 'SELECT v FROM t;'").strip() == "hello"

    # Restarting again with that package creates no further backup.
    machine.succeed("systemctl restart stricknani.service")
    machine.wait_for_unit("stricknani.service")
    assert backups() == found, backups()
  '';
}
