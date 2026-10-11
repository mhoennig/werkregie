package de.hoennig.werkregie.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class DeploymentTest : FunSpec({
    val deployment = Deployment(Path.of("/home/u/opt/werkregie-dashboard/bin/werkregie-dashboard"), Path.of("/home/u/.config/werkregie/dashboard.yml"))

    test("the units start the installed launcher in their mode with the configuration") {
        deployment.serverUnit() shouldContain
            "ExecStart=/home/u/opt/werkregie-dashboard/bin/werkregie-dashboard server --config /home/u/.config/werkregie/dashboard.yml"
        deployment.relayUnit() shouldContain "werkregie-dashboard relay --config"
        deployment.serverUnit() shouldContain "EnvironmentFile=-/home/u/.config/werkregie/dashboard.env"
    }

    test("the .htaccess proxies everything but the maintenance page to the service's port") {
        val htaccess = deployment.htaccess(31234)

        htaccess shouldContain "RewriteCond %{REQUEST_URI} !^/werkregie-maintenance.html$"
        htaccess shouldContain "RewriteRule .* http://127.0.0.1:31234%{REQUEST_URI} [proxy]"
        htaccess shouldContain "ErrorDocument 503 /werkregie-maintenance.html"
    }

    test("the maintenance page loads the requested address again every 15 seconds") {
        val page = deployment.maintenancePage()

        page shouldContain """<meta http-equiv="refresh" content="15">"""
        page shouldContain "Neuer Versuch alle 15 Sekunden"
    }
})
