package dev.pocketpaas.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteInputTest {
    @Test
    fun domainNamesHaveOneCanonicalAsciiIdentity() {
        assertEquals("example.com", SiteInput.normalizeDomainName("  EXAMPLE.COM.  "))
        assertEquals("xn--bcher-kva.example", SiteInput.normalizeDomainName("BÜCHER.Example"))
        assertEquals(
            SiteInput.normalizeDomainName("bücher.example"),
            SiteInput.normalizeDomainName("XN--BCHER-KVA.EXAMPLE."),
        )
    }

    @Test
    fun domainsCannotContainPathsShellSyntaxOrInvalidDnsLabels() {
        val invalidNames = listOf(
            "", "../outside", "/data/private", "example.com;nginx", "site\$(id)",
            "bad label.example", "first\nsecond.example", "-leading.example",
            "trailing-.example", "empty..example", "a".repeat(64) + ".example",
            List(5) { "a".repeat(63) }.joinToString("."),
        )

        invalidNames.forEach { name ->
            assertThrows("Accepted unsafe domain: $name", IllegalArgumentException::class.java) {
                SiteInput.normalizeDomainName(name)
            }
        }
    }

    @Test
    fun customHostnamesAreCanonicalFullyQualifiedNamesAndCanBeBoundedToAZone() {
        assertEquals("www.example.com", SiteInput.normalizeCustomHostname(" WWW.Example.COM. "))
        assertEquals("xn--bcher-kva.example", SiteInput.normalizeCustomHostname("bücher.example"))
        assertTrue(SiteInput.isHostnameInZone("www.example.com", "example.com"))
        assertTrue(SiteInput.isHostnameInZone("example.com", "example.com"))
        assertFalse(SiteInput.isHostnameInZone("notexample.com", "example.com"))
        assertFalse(SiteInput.isHostnameInZone("example.com.evil", "example.com"))
        listOf("", "my-app", "https://example.com", "a..example.com", "*.example.com").forEach { hostname ->
            assertThrows("Accepted unsafe custom hostname: $hostname", IllegalArgumentException::class.java) {
                SiteInput.normalizeCustomHostname(hostname)
            }
        }
    }

    @Test
    fun publicUrlsAllowHttpAndHttpsWithoutCredentialsOrInvalidPorts() {
        assertEquals(null, SiteInput.normalizePublicUrl(null))
        assertEquals(
            "https://public.example/caf%C3%A9",
            SiteInput.normalizePublicUrl("  https://public.example/café  "),
        )
        assertEquals("http://127.0.0.1:8081/", SiteInput.normalizePublicUrl("http://127.0.0.1:8081/"))

        listOf(
            "", "/relative/path", "javascript:alert(1)", "file:///etc/passwd",
            "https://user:password@public.example", "https://public.example:0",
            "https://public.example:65536", "https://public.example\u0000/path",
            "https://public.example/path\nheader", "https://", "https://public.example/" + "a".repeat(2048),
        ).forEach { value ->
            assertThrows("Accepted unsafe URL", IllegalArgumentException::class.java) {
                SiteInput.normalizePublicUrl(value)
            }
        }
    }

    @Test
    fun logTailSanitizesNulAndKeepsCompleteUnicodeCodePoints() {
        assertEquals("\uFFFD🚀é終", SiteInput.logTail("discarded\u0000🚀é終", 4))
        assertEquals("🚀", SiteInput.logTail("discarded🚀", 1))
        assertEquals("a\uFFFDb", SiteInput.logTail("a\u0000b", 3))
        assertEquals("", SiteInput.logTail("", 1))
        assertThrows(IllegalArgumentException::class.java) { SiteInput.logTail("log", 0) }
    }
}
