package dev.pocketpaas.domain.model

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object SiteInput {
    fun normalizeDomainName(input: String): String {
        val value = input.trim().removeSuffix(".")
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() }) {
            "Domain name must not be blank or contain whitespace"
        }
        val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        val labelPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        require(ascii.length <= 253 && ascii.split('.').all { labelPattern.matches(it) }) {
            "Domain name must contain valid DNS labels"
        }
        return ascii
    }

    /** A custom public hostname is separate from the site's path identity and must be a FQDN. */
    fun normalizeCustomHostname(input: String): String {
        val hostname = normalizeDomainName(input)
        require(hostname.contains('.')) { "Custom hostname must be a fully qualified DNS name" }
        return hostname
    }

    fun isHostnameInZone(hostname: String, zoneName: String): Boolean =
        hostname == zoneName || hostname.endsWith(".$zoneName")

    fun validateSiteId(id: Long) {
        require(id > 0) { "Site ID must be positive" }
    }

    fun validateRuntimeState(status: SiteStatus, processId: Int?) {
        require(processId == null || processId > 0) { "Process ID must be positive" }
        require(status != SiteStatus.STOPPED || processId == null) {
            "A stopped site must not retain a process ID"
        }
    }

    fun normalizePublicUrl(input: String?): String? {
        if (input == null) return null
        val value = input.trim()
        require(value.length in 1..2048 && value.none { it.isISOControl() }) { "Invalid public URL" }
        val uri = try {
            URI(value)
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException("Invalid public URL", exception)
        }
        require(
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535),
        ) { "Public URL must be an HTTP(S) URL without credentials" }
        return uri.toASCIIString()
    }

    fun logTail(text: String, maximumCharacters: Int): String {
        require(maximumCharacters > 0)
        val sanitized = text.replace('\u0000', '\uFFFD')
        val codePoints = sanitized.codePointCount(0, sanitized.length)
        return if (codePoints <= maximumCharacters) sanitized else {
            sanitized.substring(sanitized.offsetByCodePoints(0, codePoints - maximumCharacters))
        }
    }
}
