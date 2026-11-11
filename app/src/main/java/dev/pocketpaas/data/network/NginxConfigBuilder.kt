package dev.pocketpaas.data.network

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import javax.inject.Inject

/** Renders POSIX paths from the Termux runtime, without accessing another Android UID's files. */
class NginxConfigBuilder @Inject constructor(private val serverConfig: ServerConfig) {
    fun renderMaster(
        layout: RuntimeLayout,
        includePaths: List<String>? = null,
        revision: String = "pocketpaas",
        customHostIncludePaths: List<String>? = null,
    ): String {
        validateLayout(layout)
        require(REVISION.matches(revision)) { "Invalid Nginx configuration revision" }
        val includes = if (includePaths == null) {
            "include ${quoted(layout.nginxHostsPath + "/*.conf")};"
        } else {
            require(includePaths.distinct().size == includePaths.size) { "Duplicate Nginx include" }
            includePaths.joinToString("\n        ") { path ->
                require(isChildOf(path, layout.nginxHostsPath)) { "Nginx include must be in conf.d" }
                "include ${quoted(path)};"
            }
        }
        val customDirectory = customHostsDirectory(layout)
        val customIncludes = if (customHostIncludePaths == null) {
            "include ${quoted("$customDirectory/*.conf")};"
        } else {
            require(customHostIncludePaths.distinct().size == customHostIncludePaths.size) { "Duplicate custom-host Nginx include" }
            customHostIncludePaths.joinToString("\n    ") { path ->
                require(isChildOf(path, customDirectory)) { "Custom-host include must be in the custom-host directory" }
                "include ${quoted(path)};"
            }
        }
        return """
            worker_processes 1;
            pid ${quoted(layout.runtimePath + "/nginx.pid")};
            error_log ${quoted(layout.logsPath + "/nginx-error.log")} warn;
            events {
                worker_connections 512;
            }
            http {
                server_tokens off;
                sendfile on;
                tcp_nopush on;
                keepalive_timeout 30s;
                client_header_timeout 15s;
                client_body_timeout 60s;
                send_timeout 60s;
                client_max_body_size 64m;
                client_body_temp_path ${quoted(layout.runtimePath + "/client-body")};
                proxy_temp_path ${quoted(layout.runtimePath + "/proxy-temp")};
                fastcgi_temp_path ${quoted(layout.runtimePath + "/fastcgi-temp")};
                uwsgi_temp_path ${quoted(layout.runtimePath + "/uwsgi-temp")};
                scgi_temp_path ${quoted(layout.runtimePath + "/scgi-temp")};
                default_type application/octet-stream;
                types {
                    text/html html htm;
                    text/plain txt;
                    text/css css;
                    application/javascript js mjs;
                    application/json json map;
                    application/wasm wasm;
                    application/pdf pdf;
                    image/svg+xml svg;
                    image/png png;
                    image/jpeg jpg jpeg;
                    image/gif gif;
                    image/webp webp;
                    image/x-icon ico;
                    font/woff woff;
                    font/woff2 woff2;
                }
                log_format pocketpaas '${'$'}remote_addr [${'$'}time_local] "${'$'}request_method ${'$'}uri ${'$'}server_protocol" ${'$'}status ${'$'}body_bytes_sent';
                access_log ${quoted(layout.logsPath + "/nginx-access.log")} pocketpaas;
                map ${'$'}http_upgrade ${'$'}pocketpaas_connection {
                    default upgrade;
                    '' close;
                }
                map ${'$'}http_x_forwarded_proto ${'$'}pocketpaas_forwarded_proto {
                    default ${'$'}scheme;
                    https https;
                    http http;
                }
                server {
                    listen ${serverConfig.nginxHost}:${serverConfig.nginxPort} default_server;
                    server_name _;
                    absolute_redirect off;
                    add_header X-Content-Type-Options nosniff always;
                    location = /_pocketpaas/health {
                        default_type text/plain;
                        return 200 "$revision";
                    }
                    location = /manage {
                        return 308 /manage/;
                    }
                    location = /manage/ {
                        proxy_pass http://${serverConfig.apiHost}:${serverConfig.apiPort}/_pocketpaas/manager/;
                        proxy_set_header Host ${'$'}host;
                        proxy_set_header X-Forwarded-Proto ${'$'}pocketpaas_forwarded_proto;
                        proxy_set_header X-Forwarded-Host ${'$'}host;
                    }
                    location = /_pocketpaas/manager.css {
                        proxy_pass http://${serverConfig.apiHost}:${serverConfig.apiPort}/_pocketpaas/manager.css;
                    }
                    location = /_pocketpaas/manager.js {
                        proxy_pass http://${serverConfig.apiHost}:${serverConfig.apiPort}/_pocketpaas/manager.js;
                    }
                    location ^~ /_pocketpaas/files/ {
                        client_max_body_size 256k;
                        proxy_pass http://${serverConfig.apiHost}:${serverConfig.apiPort};
                        proxy_set_header Host ${'$'}host;
                        proxy_set_header X-Forwarded-Proto ${'$'}pocketpaas_forwarded_proto;
                        proxy_set_header X-Forwarded-Host ${'$'}host;
                    }
                    location = / {
                        default_type text/plain;
                        return 200 "Pocket PaaS is running. Sites are served under /sites/<domain>/\n";
                    }
                    $includes
                    location / {
                        return 404;
                    }
                }
                $customIncludes
            }
        """.trimIndent() + "\n"
    }

    fun renderVirtualHost(site: Site, rootDir: String, layout: RuntimeLayout): String {
        validateLayout(layout)
        validateSite(site)
        validatePath(rootDir)
        val projectRoot = layout.projectsPath + "/" + site.domainName
        require(rootDir == projectRoot || isChildOf(rootDir, projectRoot)) {
            "Site root must be inside its own project directory"
        }
        val prefix = sitePrefix(site)
        val redirect = """
            location = ${prefix.removeSuffix("/")} {
                return 308 $prefix${'$'}is_args${'$'}args;
            }
        """.trimIndent()
        val body = when (site.projectType) {
            ProjectType.STATIC -> renderStatic(prefix, rootDir, spa = false)
            ProjectType.REACT -> renderStatic(prefix, rootDir, spa = true)
            ProjectType.DJANGO, ProjectType.NODEJS -> """
                location ^~ $prefix {
                    proxy_pass http://127.0.0.1:${site.internalPort}/;
                    proxy_http_version 1.1;
                    proxy_set_header Host ${'$'}host;
                    proxy_set_header X-Real-IP ${'$'}remote_addr;
                    proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
                    proxy_set_header X-Forwarded-Host ${'$'}host;
                    proxy_set_header X-Forwarded-Proto ${'$'}pocketpaas_forwarded_proto;
                    proxy_set_header X-Forwarded-Prefix ${prefix.removeSuffix("/")};
                    proxy_set_header Upgrade ${'$'}http_upgrade;
                    proxy_set_header Connection ${'$'}pocketpaas_connection;
                    proxy_cookie_path / $prefix;
                    proxy_connect_timeout 5s;
                    proxy_send_timeout 60s;
                    proxy_read_timeout 60s;
                }
            """.trimIndent()
        }
        return "$redirect\n$body\n"
    }

    /** Exact-host server exposes a project's root without changing its internal /sites/<domain>/ route. */
    fun renderCustomVirtualHost(site: Site, rootDir: String, layout: RuntimeLayout): String {
        validateLayout(layout)
        validateSite(site)
        validatePath(rootDir)
        val hostname = checkNotNull(site.customHostname) { "A custom DNS hostname is required." }
        val projectRoot = layout.projectsPath + "/" + site.domainName
        require(rootDir == projectRoot || isChildOf(rootDir, projectRoot)) {
            "Site root must be inside its own project directory"
        }
        val body = when (site.projectType) {
            ProjectType.STATIC -> renderCustomStatic(rootDir, spa = false)
            ProjectType.REACT -> renderCustomStatic(rootDir, spa = true)
            ProjectType.DJANGO, ProjectType.NODEJS -> """
                location / {
                    proxy_pass http://127.0.0.1:${site.internalPort};
                    proxy_http_version 1.1;
                    proxy_set_header Host ${'$'}host;
                    proxy_set_header X-Real-IP ${'$'}remote_addr;
                    proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
                    proxy_set_header X-Forwarded-Host ${'$'}host;
                    proxy_set_header X-Forwarded-Proto ${'$'}pocketpaas_forwarded_proto;
                    proxy_set_header Upgrade ${'$'}http_upgrade;
                    proxy_set_header Connection ${'$'}pocketpaas_connection;
                    proxy_connect_timeout 5s;
                    proxy_send_timeout 60s;
                    proxy_read_timeout 60s;
                }
            """.trimIndent()
        }
        return """
            server {
                listen ${serverConfig.nginxHost}:${serverConfig.nginxPort};
                server_name $hostname;
                absolute_redirect off;
                add_header X-Content-Type-Options nosniff always;
                $body
            }
        """.trimIndent() + "\n"
    }

    fun hostPath(layout: RuntimeLayout, site: Site): String {
        validateLayout(layout)
        validateSite(site)
        return layout.nginxHostsPath + "/" + site.domainName + ".conf"
    }

    fun customHostPath(layout: RuntimeLayout, site: Site): String {
        validateLayout(layout)
        validateSite(site)
        return "${customHostsDirectory(layout)}/${site.domainName}.conf"
    }

    fun customHostsDirectory(layout: RuntimeLayout): String {
        validateLayout(layout)
        return layout.nginxHostsPath + "/custom"
    }

    fun sitePrefix(site: Site): String {
        validateSite(site)
        return "/sites/${site.domainName}/"
    }

    private fun renderStatic(prefix: String, rootDir: String, spa: Boolean): String {
        val fallback = if (spa) "error_page 404 = ${prefix}index.html;" else ""
        val staticLocation = """
            location ^~ $prefix {
                alias ${quoted("$rootDir/")};
                index index.html;
                autoindex off;
                disable_symlinks on;
                $fallback
                location ~ (?:^|/)\. {
                    return 403;
                }
            }
        """.trimIndent()
        if (!spa) return staticLocation
        // Exact index location keeps a missing SPA entry point from redirecting into itself.
        return """
            location = ${prefix}index.html {
                alias ${quoted("$rootDir/index.html")};
                disable_symlinks on;
            }
            $staticLocation
        """.trimIndent()
    }

    private fun renderCustomStatic(rootDir: String, spa: Boolean): String {
        val exactIndex = if (spa) {
            """
                location = /index.html {
                    alias ${quoted("$rootDir/index.html")};
                    disable_symlinks on;
                }
            """.trimIndent()
        } else ""
        val fallback = if (spa) "error_page 404 = /index.html;" else ""
        return """
            $exactIndex
            location / {
                alias ${quoted("$rootDir/")};
                index index.html;
                autoindex off;
                disable_symlinks on;
                $fallback
                location ~ (?:^|/)\. {
                    return 403;
                }
            }
        """.trimIndent()
    }

    private fun validateSite(site: Site) {
        require(site.domainName == SiteInput.normalizeDomainName(site.domainName)) {
            "Site domain must use its canonical ASCII identity"
        }
        site.customHostname?.let { hostname ->
            require(hostname == SiteInput.normalizeCustomHostname(hostname)) {
                "Custom hostname must use its canonical ASCII identity"
            }
        }
        require(site.internalPort in 1024..65535) { "Site port must be unprivileged" }
        require(site.internalPort != serverConfig.nginxPort && site.internalPort != serverConfig.apiPort) {
            "Site port conflicts with a platform listener"
        }
    }

    private fun validateLayout(layout: RuntimeLayout) {
        validatePath(layout.rootPath)
        require(layout.rootPath != "/") { "Runtime root cannot be the filesystem root" }
        listOf(
            layout.projectsPath, layout.nginxConfigPath, layout.nginxHostsPath,
            layout.runtimePath, layout.logsPath,
        ).forEach { path ->
            require(isChildOf(path, layout.rootPath)) { "Runtime paths must be inside the runtime root" }
        }
    }

    private fun isChildOf(path: String, parent: String): Boolean {
        validatePath(path)
        validatePath(parent)
        return path.startsWith("$parent/")
    }

    private fun quoted(value: String): String {
        require(value.none { it.isISOControl() || it in "\\\"\u0024;{}'" }) {
            "Unsafe Nginx path"
        }
        return "\"$value\""
    }

    private fun validatePath(path: String) {
        require(path.startsWith('/') && path.length <= 4096) { "A bounded absolute POSIX path is required" }
        require(path.none { it.isISOControl() || it in "\\\"\u0024;{}'*?[]" }) { "Unsafe runtime path" }
        require(path == "/" || (!path.endsWith('/') && path.split('/').drop(1).all {
            it.isNotEmpty() && it != "." && it != ".."
        })) { "A normalized POSIX path is required" }
    }

    private companion object {
        val REVISION = Regex("[a-zA-Z0-9_-]{1,128}")
    }
}
