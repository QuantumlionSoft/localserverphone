package dev.pocketpaas.data.network

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NginxConfigBuilderTest {
    private val builder = NginxConfigBuilder(ServerConfig())
    private val root = "/data/data/com.termux/files/home/.pocket-paas"
    private val layout = RuntimeLayout(
        rootPath = root,
        projectsPath = "$root/htdocs",
        nginxConfigPath = "$root/nginx/nginx.conf",
        nginxHostsPath = "$root/nginx/conf.d",
        runtimePath = "$root/runtime",
        logsPath = "$root/logs",
    )

    @Test
    fun masterKeepsAllListenersOnLoopbackAndUsesExplicitPrivatePaths() {
        val config = builder.renderMaster(layout)
        assertTrue(config.contains("listen 127.0.0.1:8081 default_server;"))
        assertFalse(config.contains("listen 80"))
        assertFalse(config.contains("listen 0.0.0.0"))
        assertTrue(config.contains("pid \"$root/runtime/nginx.pid\";"))
        assertTrue(config.contains("include \"$root/nginx/conf.d/*.conf\";"))
        assertTrue(config.contains("include \"$root/nginx/conf.d/custom/*.conf\";"))
        assertTrue(config.contains("location = /_pocketpaas/health"))
        assertTrue(config.contains("return 200 \"pocketpaas\";"))
        assertTrue(config.contains("absolute_redirect off;"))
    }

    @Test
    fun masterProvidesForwardingMapsAndBrowserAssetContentTypes() {
        val config = builder.renderMaster(layout)
        assertTrue(config.contains("map \$http_upgrade \$pocketpaas_connection"))
        assertTrue(config.contains("map \$http_x_forwarded_proto \$pocketpaas_forwarded_proto"))
        assertTrue(config.contains("application/javascript js mjs;"))
        assertTrue(config.contains("text/css css;"))
        assertTrue(config.contains("font/woff2 woff2;"))
        assertTrue(config.contains("application/wasm wasm;"))
    }

    @Test
    fun masterProxiesOnlyTheAuthenticatedSiteFileManagerToLoopbackApi() {
        val config = builder.renderMaster(layout)
        assertTrue(config.contains("location = /manage/"))
        assertTrue(config.contains("proxy_pass http://127.0.0.1:8080/_pocketpaas/manager/;"))
        assertTrue(config.contains("location ^~ /_pocketpaas/files/"))
        assertTrue(config.contains("proxy_pass http://127.0.0.1:8080;"))
        assertTrue(config.contains("client_max_body_size 256k;"))
        assertFalse(config.contains("location /api"))
        assertFalse(config.contains("location ^~ /api/"))
    }

    @Test
    fun candidateMasterIncludesOnlySuppliedPathsAndUsesItsRevision() {
        val paths = listOf("$root/nginx/conf.d/first.conf", "$root/nginx/conf.d/.candidate-second")
        val config = builder.renderMaster(layout, paths, "revision-2", emptyList())
        assertTrue(config.contains("include \"${paths[0]}\";"))
        assertTrue(config.contains("include \"${paths[1]}\";"))
        assertFalse(config.contains("*.conf"))
        assertTrue(config.contains("return 200 \"revision-2\";"))
        val empty = builder.renderMaster(layout, emptyList(), customHostIncludePaths = emptyList())
        assertFalse(empty.contains("include "))
    }

    @Test
    fun candidateMasterIncludesOnlyTheSuppliedCustomHostServerPaths() {
        val candidate = "$root/nginx/conf.d/custom/.candidate-site"
        val config = builder.renderMaster(layout, emptyList(), "custom-revision", listOf(candidate))
        assertTrue(config.contains("include \"$candidate\";"))
        assertFalse(config.contains("custom/*.conf"))
        assertThrows(IllegalArgumentException::class.java) {
            builder.renderMaster(layout, emptyList(), "custom-revision", listOf("$root/nginx/conf.d/escape.conf"))
        }
    }

    @Test
    fun includesCannotEscapeConfDirectoryOrDuplicateAnotherFile() {
        listOf(
            listOf("$root/htdocs/app.conf"),
            listOf("$root/nginx/conf.d-extra/app.conf"),
            listOf("$root/nginx/conf.d/../app.conf"),
            listOf("$root/nginx/conf.d/app.conf", "$root/nginx/conf.d/app.conf"),
        ).forEach { paths ->
            assertThrows(IllegalArgumentException::class.java) { builder.renderMaster(layout, paths) }
        }
    }

    @Test
    fun staticSitesUseMatchingAliasAndLocationTrailingSlashes() {
        val site = site(ProjectType.STATIC)
        val config = builder.renderVirtualHost(site, "$root/htdocs/example.com", layout)
        assertTrue(config.contains("location = /sites/example.com {"))
        assertTrue(config.contains("return 308 /sites/example.com/\$is_args\$args;"))
        assertTrue(config.contains("location ^~ /sites/example.com/ {"))
        assertTrue(config.contains("alias \"$root/htdocs/example.com/\";"))
        assertTrue(config.contains("autoindex off;"))
        assertTrue(config.contains("disable_symlinks on;"))
        assertTrue(config.contains("return 403;"))
        assertFalse(config.contains("proxy_pass"))
        assertFalse(config.contains("error_page"))
    }

    @Test
    fun reactFallbackUsesExactEntryPointWithoutRecursiveTryFiles() {
        val config = builder.renderVirtualHost(site(ProjectType.REACT), "$root/htdocs/example.com/dist", layout)
        assertTrue(config.contains("error_page 404 = /sites/example.com/index.html;"))
        assertTrue(config.contains("location = /sites/example.com/index.html {"))
        assertTrue(config.contains("alias \"$root/htdocs/example.com/dist/\";"))
        assertFalse(config.contains("try_files"))
        assertEquals(1, Regex("error_page").findAll(config).count())
    }

    @Test
    fun dynamicSitesStripPrefixAndCarryForwardingAndWebsocketHeaders() {
        listOf(ProjectType.DJANGO, ProjectType.NODEJS).forEach { type ->
            val config = builder.renderVirtualHost(site(type), "$root/htdocs/example.com", layout)
            assertTrue(config.contains("proxy_pass http://127.0.0.1:18000/;"))
            assertTrue(config.contains("proxy_set_header X-Forwarded-Prefix /sites/example.com;"))
            assertTrue(config.contains("proxy_set_header X-Forwarded-Proto \$pocketpaas_forwarded_proto;"))
            assertTrue(config.contains("proxy_set_header X-Forwarded-Host \$host;"))
            assertTrue(config.contains("proxy_set_header Upgrade \$http_upgrade;"))
            assertTrue(config.contains("proxy_set_header Connection \$pocketpaas_connection;"))
            assertTrue(config.contains("proxy_cookie_path / /sites/example.com/;"))
            assertFalse(config.contains("alias "))
        }
    }

    @Test
    fun customHostnameServesStaticProjectFromRootWithoutChangingPathIdentityOrManagerRoutes() {
        val site = site(ProjectType.STATIC).copy(customHostname = "www.public.example")
        val config = builder.renderCustomVirtualHost(site, "$root/htdocs/example.com", layout)
        assertTrue(config.contains("server_name www.public.example;"))
        assertTrue(config.contains("listen 127.0.0.1:8081;"))
        assertTrue(config.contains("location / {"))
        assertTrue(config.contains("alias \"$root/htdocs/example.com/\";"))
        assertTrue(config.contains("disable_symlinks on;"))
        assertTrue(config.contains("return 403;"))
        assertFalse(config.contains("/sites/example.com/"))
        assertFalse(config.contains("/_pocketpaas/files/"))
        assertEquals("/sites/example.com/", builder.sitePrefix(site))
        assertEquals("$root/nginx/conf.d/custom/example.com.conf", builder.customHostPath(layout, site))
    }

    @Test
    fun customHostnameSupportsReactFallbackAndDynamicProxyAtTheRoot() {
        val react = builder.renderCustomVirtualHost(
            site(ProjectType.REACT).copy(customHostname = "react.example.com"),
            "$root/htdocs/example.com/dist",
            layout,
        )
        assertTrue(react.contains("error_page 404 = /index.html;"))
        assertTrue(react.contains("location = /index.html"))
        assertTrue(react.contains("alias \"$root/htdocs/example.com/dist/index.html\";"))

        val node = builder.renderCustomVirtualHost(
            site(ProjectType.NODEJS).copy(customHostname = "api.example.com"),
            "$root/htdocs/example.com",
            layout,
        )
        assertTrue(node.contains("proxy_pass http://127.0.0.1:18000;"))
        assertTrue(node.contains("proxy_set_header X-Forwarded-Host \$host;"))
        assertFalse(node.contains("proxy_set_header X-Forwarded-Prefix"))
    }

    @Test
    fun customHostnameMustBeCanonicalAndProjectRootRemainsSiteScoped() {
        val site = site(ProjectType.STATIC)
        listOf("", "my-app", "https://www.example.com", "../evil.example").forEach { hostname ->
            assertThrows(IllegalArgumentException::class.java) {
                builder.renderCustomVirtualHost(site.copy(customHostname = hostname), "$root/htdocs/example.com", layout)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.renderCustomVirtualHost(
                site.copy(customHostname = "www.example.com"),
                "$root/htdocs/another.example",
                layout,
            )
        }
    }

    @Test
    fun sitesCannotServeSiblingProjectsOrPrefixMatchingOutsideDirectories() {
        val site = site(ProjectType.STATIC)
        listOf(
            "$root/htdocs/another.example",
            "$root/htdocs/example.com-evil",
            "$root/htdocs",
            "$root/htdocs/example.com/../../logs",
            "/etc",
        ).forEach { path ->
            assertThrows("Accepted site root $path", IllegalArgumentException::class.java) {
                builder.renderVirtualHost(site, path, layout)
            }
        }
    }

    @Test
    fun configInjectionAndNonNormalizedPathsAreRejected() {
        val site = site(ProjectType.STATIC)
        listOf(
            "$root/htdocs/example.com/quote\"name",
            "$root/htdocs/example.com/\$variable",
            "$root/htdocs/example.com/a;include evil",
            "$root/htdocs/example.com/a{b}",
            "$root/htdocs/example.com/a\\b",
            "$root/htdocs/example.com/a\nb",
            "$root/htdocs/example.com/a\u0000b",
            "$root/htdocs/example.com/./dist",
            "$root/htdocs/example.com//dist",
            "$root/htdocs/example.com/dist/",
        ).forEach { path ->
            assertThrows("Accepted unsafe runtime path", IllegalArgumentException::class.java) {
                builder.renderVirtualHost(site, path, layout)
            }
        }
        val spaced = "$root/htdocs/example.com/built assets"
        assertTrue(builder.renderVirtualHost(site, spaced, layout).contains("alias \"$spaced/\";"))
    }

    @Test
    fun layoutAndRevisionCannotInjectOrEscapeRuntimeRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.renderMaster(layout.copy(logsPath = "/tmp/logs"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.renderMaster(layout.copy(rootPath = "/"))
        }
        listOf("", "injected\";}", "line\nfeed", "x".repeat(129)).forEach { revision ->
            assertThrows(IllegalArgumentException::class.java) {
                builder.renderMaster(layout, revision = revision)
            }
        }
    }

    @Test
    fun unsafeDomainsAndPlatformPortConflictsAreRejectedBeforeFilenameCreation() {
        listOf("Example.COM", "example.com.", "../escape", "safe;evil", "name\nline").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                builder.hostPath(layout, site(ProjectType.STATIC).copy(domainName = name))
            }
        }
        listOf(0, 80, 8080, 8081, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) {
                builder.sitePrefix(site(ProjectType.STATIC).copy(internalPort = port))
            }
        }
    }

    @Test
    fun canonicalDomainsProduceStableFilenameAndPublicPath() {
        val site = site(ProjectType.STATIC).copy(domainName = "xn--bcher-kva.example")
        assertEquals("$root/nginx/conf.d/xn--bcher-kva.example.conf", builder.hostPath(layout, site))
        assertEquals("/sites/xn--bcher-kva.example/", builder.sitePrefix(site))
    }

    private fun site(type: ProjectType) = Site(
        id = 1,
        domainName = "example.com",
        internalPort = 18000,
        projectType = type,
        processId = null,
        publicUrl = null,
        status = SiteStatus.STOPPED,
        logs = "",
        createdAt = 1,
    )
}
