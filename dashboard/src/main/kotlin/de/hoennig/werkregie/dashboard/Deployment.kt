package de.hoennig.werkregie.dashboard

import java.nio.file.Files
import java.nio.file.Path

/**
 * The host integration `init` writes: a systemd user unit for the server or the relay and, for a
 * server behind Hostsharing's managed Apache, the `.htaccess` that proxies the domain to the
 * service's localhost port plus the static page Apache shows while the service restarts —
 * the same shape as Werkator's (SystemdServiceFiles).
 */
class Deployment(private val launcher: Path, private val configPath: Path) {

    fun serverUnit(): String = unit("Werkregie-Dashboard (server)", "server")

    fun relayUnit(): String = unit("Werkregie-Relay", "relay")

    private fun unit(description: String, mode: String): String =
        """
        [Unit]
        Description=$description

        [Service]
        # tokens referenced by tokenEnv/controlTokenEnv, and JAVA_OPTS; optional, mode 0600
        EnvironmentFile=-${quote(environmentFile())}
        ExecStart=${quote(launcher)} $mode --config ${quote(configPath)}
        Restart=on-failure
        RestartSec=5
        # SIGTERM ends the JVM with 143: a clean stop, not a failure
        SuccessExitStatus=143

        [Install]
        WantedBy=default.target
        """.trimIndent() + "\n"

    /**
     * `ErrorDocument` maps a refused connection while the service restarts to [maintenancePage];
     * the `RewriteCond` keeps that one file from being proxied itself.
     */
    fun htaccess(port: Int): String =
        """
        DirectoryIndex disabled
        ErrorDocument 502 /$MAINTENANCE_PAGE
        ErrorDocument 503 /$MAINTENANCE_PAGE
        ErrorDocument 504 /$MAINTENANCE_PAGE
        RewriteEngine On
        RewriteBase /
        RewriteCond %{REQUEST_URI} !^/${MAINTENANCE_PAGE}${'$'}
        RewriteRule .* http://127.0.0.1:$port%{REQUEST_URI} [proxy]
        """.trimIndent() + "\n"

    fun maintenancePage(): String =
        """
        <!doctype html>
        <html lang="de">
        <head>
        <meta charset="utf-8">
        <title>Werkregie — Neustart</title>
        <style>
            body { font-family: sans-serif; background: #242928; color: #f4f5f5; display: flex;
                   align-items: center; justify-content: center; height: 100vh; margin: 0; }
            div { text-align: center; }
            h1 { font-size: 1.4rem; margin-bottom: .5rem; }
            p { color: #9ea9a7; }
        </style>
        </head>
        <body>
        <div>
            <h1>Werkregie startet neu</h1>
            <p>Bitte in einer Minute erneut laden.</p>
        </div>
        </body>
        </html>
        """.trimIndent() + "\n"

    /** Writes the files for [mode] into [directory] and returns how to install them. */
    fun write(mode: String, directory: Path, port: Int): String {
        Files.createDirectories(directory)
        return when (mode) {
            "server" -> {
                Files.writeString(directory.resolve(SERVER_UNIT), serverUnit())
                Files.writeString(directory.resolve(".htaccess"), htaccess(port))
                Files.writeString(directory.resolve(MAINTENANCE_PAGE), maintenancePage())
                """
                Geschrieben nach $directory: $SERVER_UNIT, .htaccess, $MAINTENANCE_PAGE

                Installieren (als Benutzer des Webspace):
                  mkdir -p ~/.config/systemd/user
                  cp $directory/$SERVER_UNIT ~/.config/systemd/user/
                  cp $directory/.htaccess $directory/$MAINTENANCE_PAGE ~/doms/<domain>/htdocs-ssl/
                  loginctl enable-linger          # Dienst läuft auch ohne Anmeldung
                  systemctl --user daemon-reload && systemctl --user enable --now ${SERVER_UNIT.removeSuffix(".service")}
                  $launcher token --config $configPath   # Zugriffstoken für Browser und Relays
                """.trimIndent()
            }
            "relay" -> {
                Files.writeString(directory.resolve(RELAY_UNIT), relayUnit())
                """
                Geschrieben nach $directory: $RELAY_UNIT

                Installieren:
                  mkdir -p ~/.config/systemd/user
                  cp $directory/$RELAY_UNIT ~/.config/systemd/user/
                  systemctl --user daemon-reload && systemctl --user enable --now ${RELAY_UNIT.removeSuffix(".service")}
                """.trimIndent()
            }
            else -> error("init kennt nur 'server' und 'relay'")
        }
    }

    /** Next to the configuration: `dashboard.yml` → `dashboard.env`. */
    fun environmentFile(): Path = configPath.resolveSibling(configPath.fileName.toString().substringBeforeLast('.') + ".env")

    private fun quote(path: Path) = path.toString().let { if (' ' in it) "\"$it\"" else it }

    companion object {
        const val SERVER_UNIT = "werkregie-dashboard.service"
        const val RELAY_UNIT = "werkregie-relay.service"
        const val MAINTENANCE_PAGE = "werkregie-maintenance.html"
    }
}
