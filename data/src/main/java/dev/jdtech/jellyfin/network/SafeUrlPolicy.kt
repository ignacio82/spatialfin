package dev.jdtech.jellyfin.network

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.HttpMethod

class SafeUrlBlockedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class UrlTargetPolicy {
    PRIVATE_LAN_ONLY,
    LAN_ONLY,
    PUBLIC_INTERNET_ONLY,
    PUBLIC_OR_LAN,
}

internal class ProxyContext(
    val call: Call,
    val isWebSocket: Boolean,
    var hopPolicy: UrlTargetPolicy,
) {
    @Volatile
    var httpProxyOnly: Boolean = false

    @Volatile
    var activeRouteIsHttpProxy: Boolean = false

    val unresolvedProxyHosts: MutableMap<String, MutableSet<Int>> = ConcurrentHashMap()
    val allowedProxyEndpoints: MutableSet<Pair<String, Int>> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun resetForHop(newHopPolicy: UrlTargetPolicy) {
        hopPolicy = newHopPolicy
        httpProxyOnly = false
        activeRouteIsHttpProxy = false
        unresolvedProxyHosts.clear()
        allowedProxyEndpoints.clear()
    }
}

object SafeUrlPolicy {
    internal val activeProxyContext = ThreadLocal<ProxyContext?>()

    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "x-emby-authorization",
        "x-emby-token",
        "x-mediabrowser-token",
        "x-setup-token",
        "x-pairing-token",
        "x-api-key",
        "proxy-authorization",
        "cookie",
    )

    private val RAW_URL_SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$")
    private val CANONICAL_IPV4_LABEL = Regex("^(?:0|[1-9]\\d{0,2})$")
    private val OCTAL_OR_HEX_OR_DEC_LABEL = Regex("^(?:0[xX][0-9a-fA-F]+|0[0-7]+|\\d+)$")
    private val CONTROL_OR_INVALID_HOST_CHARS = Regex("[\\s/\\\\;\"'`$|\\r\\n\\u0000%]")

    fun validateInitialUrl(
        rawUrl: String,
        policy: UrlTargetPolicy,
        allowLoopbackForTests: Boolean = false,
        loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)? = null,
    ): HttpUrl {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw SafeUrlBlockedException("URL is empty")
        }

        val schemeMatch = RAW_URL_SCHEME_REGEX.matchEntire(trimmed)
            ?: throw SafeUrlBlockedException("URL must specify http or https scheme")
        val scheme = schemeMatch.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SafeUrlBlockedException("Unsupported URL scheme: $scheme")
        }

        val remainder = schemeMatch.groupValues[2]
        val authorityEnd = remainder.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) remainder.length else it
        }
        val authority = remainder.substring(0, authorityEnd)
        if (authority.isEmpty()) {
            throw SafeUrlBlockedException("URL authority is empty")
        }
        if ('@' in authority) {
            throw SafeUrlBlockedException("Credentials in URL authority are not allowed")
        }

        val rawHost = extractRawHostFromAuthority(authority)
        validateRawHostSyntax(rawHost)

        val parsed = trimmed.toHttpUrlOrNull()
            ?: throw SafeUrlBlockedException("Invalid HTTP(S) URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw SafeUrlBlockedException("Credentials in URL are not allowed")
        }
        validateParsedUrl(
            url = parsed,
            rawHost = rawHost,
            policy = policy,
            allowLoopbackForTests = allowLoopbackForTests,
            loopbackAllowlistProvider = loopbackAllowlistProvider,
        )
        return parsed
    }

    fun validateInitialUrl(
        url: HttpUrl,
        policy: UrlTargetPolicy,
        allowLoopbackForTests: Boolean = false,
        loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)? = null,
    ): HttpUrl {
        if (url.scheme != "http" && url.scheme != "https") {
            throw SafeUrlBlockedException("Unsupported URL scheme: ${url.scheme}")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw SafeUrlBlockedException("Credentials in URL are not allowed")
        }
        val rawHost = url.host
        validateRawHostSyntax(rawHost)
        validateParsedUrl(
            url = url,
            rawHost = rawHost,
            policy = policy,
            allowLoopbackForTests = allowLoopbackForTests,
            loopbackAllowlistProvider = loopbackAllowlistProvider,
        )
        return url
    }

    fun resolveLanHost(
        host: String,
        dns: Dns = Dns.SYSTEM,
    ): InetAddress {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("-")) {
            throw SafeUrlBlockedException("Invalid LAN host")
        }
        val unbracketed = stripBrackets(trimmed)
        if (unbracketed.isEmpty() || CONTROL_OR_INVALID_HOST_CHARS.containsMatchIn(unbracketed)) {
            throw SafeUrlBlockedException("Invalid characters in LAN host")
        }
        validateRawHostSyntax(unbracketed)
        if (isSyntacticLoopbackHost(unbracketed)) {
            throw SafeUrlBlockedException("Loopback host is not allowed for LAN targets")
        }

        val literalAddress = parseIpLiteralOrNull(unbracketed)
        if (literalAddress != null) {
            if (isLinkLocalV6(literalAddress)) {
                throw SafeUrlBlockedException("Link-local IPv6 literal is not allowed")
            }
            validateAddressForPolicy(
                address = literalAddress,
                policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                allowLoopbackForTests = false,
                loopbackAllowlist = emptySet(),
                port = null,
            )
            return literalAddress
        }

        val resolved = try {
            dns.lookup(unbracketed)
        } catch (e: UnknownHostException) {
            throw SafeUrlBlockedException("Failed to resolve LAN host: $unbracketed", e)
        }
        val filtered = filterResolvedAddresses(
            hostname = unbracketed,
            addresses = resolved,
            policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
            allowLoopbackForTests = false,
            loopbackAllowlist = emptySet(),
        )
        return filtered.first()
    }

    /**
     * Checks whether [candidateUrl] shares the exact `(scheme, host, port)` origin and
     * path-segment-boundary `basePath` prefix of [allowedBaseUrl].
     */
    fun matchesNormalizedBaseUrlPrefix(
        candidateUrl: String,
        allowedBaseUrl: String?,
    ): Boolean {
        val base = allowedBaseUrl?.trim()?.toHttpUrlOrNull() ?: return false
        val candidate = candidateUrl.trim().toHttpUrlOrNull() ?: return false
        if (!isSameOrigin(base, candidate)) return false

        val basePath = base.encodedPath.trimEnd('/')
        if (basePath.isEmpty()) return true
        val candidatePath = candidate.encodedPath
        return candidatePath == basePath || candidatePath.startsWith("$basePath/")
    }

    internal fun effectiveHopPolicy(policy: UrlTargetPolicy, isHttps: Boolean): UrlTargetPolicy =
        when (policy) {
            UrlTargetPolicy.LAN_ONLY ->
                if (isHttps) UrlTargetPolicy.PUBLIC_OR_LAN else UrlTargetPolicy.PRIVATE_LAN_ONLY
            else -> policy
        }

    internal fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            stripBrackets(a.host).equals(stripBrackets(b.host), ignoreCase = true) &&
            a.port == b.port

    internal fun isSameHostDefaultPortHttpsUpgrade(current: HttpUrl, next: HttpUrl): Boolean =
        !current.isHttps &&
            next.isHttps &&
            stripBrackets(current.host).equals(stripBrackets(next.host), ignoreCase = true) &&
            current.port == 80 &&
            next.port == 443

    internal fun isSensitiveHeader(name: String): Boolean =
        name.lowercase() in SENSITIVE_HEADERS

    internal fun stripBrackets(host: String): String =
        if (host.startsWith("[") && host.endsWith("]") && host.length >= 2) {
            host.substring(1, host.length - 1)
        } else {
            host
        }

    internal fun normalizeHostname(host: String): String =
        stripBrackets(host.trim()).trimEnd('.').lowercase()

    internal fun isSyntacticLoopbackHost(host: String): Boolean {
        val norm = normalizeHostname(host)
        return norm == "localhost" ||
            norm.endsWith(".localhost") ||
            norm == "ip6-localhost" ||
            norm == "ip6-loopback"
    }

    internal fun isLiteralLoopbackProxyHost(hostString: String?): Boolean {
        if (hostString.isNullOrBlank()) return false
        val norm = normalizeHostname(hostString)
        return norm == "127.0.0.1" || norm == "::1" || norm == "localhost"
    }

    internal fun extractRawHostFromAuthority(authority: String): String {
        if (authority.startsWith("[")) {
            val closeIdx = authority.indexOf(']')
            if (closeIdx <= 1) {
                throw SafeUrlBlockedException("Malformed IPv6 authority")
            }
            val trailing = authority.substring(closeIdx + 1)
            if (trailing.isNotEmpty() && !Regex("^:\\d{1,5}$").matches(trailing)) {
                throw SafeUrlBlockedException("Malformed port in IPv6 authority")
            }
            return authority.substring(1, closeIdx)
        }
        val colonCount = authority.count { it == ':' }
        if (colonCount > 1) {
            throw SafeUrlBlockedException("Unbracketed IPv6 literal in URL authority")
        }
        val colonIdx = authority.indexOf(':')
        return if (colonIdx >= 0) authority.substring(0, colonIdx) else authority
    }

    internal fun validateRawHostSyntax(rawHost: String) {
        val unbracketed = stripBrackets(rawHost.trim())
        if (unbracketed.isEmpty()) {
            throw SafeUrlBlockedException("Host is empty")
        }
        if ('%' in unbracketed) {
            throw SafeUrlBlockedException("Zone identifiers are not allowed in host: $unbracketed")
        }
        if (unbracketed.contains(':')) {
            // IPv6 literal — must parse cleanly via parseIpLiteralOrNull
            if (parseIpv6BytesOrNull(unbracketed) == null) {
                throw SafeUrlBlockedException("Malformed IPv6 literal: $unbracketed")
            }
            return
        }
        // Reject non-canonical / legacy IPv4 host syntax (e.g., 127.1, 0177.0.0.1, 2130706433, 0xa9fea9fe)
        val trimmedDots = unbracketed.trimEnd('.')
        if (trimmedDots.isEmpty()) {
            throw SafeUrlBlockedException("Host is empty")
        }
        val labels = trimmedDots.split('.')
        val lastLabel = labels.last()
        val isCanonicalV4 =
            labels.size == 4 && labels.all { label ->
                CANONICAL_IPV4_LABEL.matches(label) && (label.toIntOrNull() ?: 256) in 0..255
            }
        if (isCanonicalV4) return

        val lastLooksNumeric = OCTAL_OR_HEX_OR_DEC_LABEL.matches(lastLabel)
        val allLabelsNumeric = labels.all { OCTAL_OR_HEX_OR_DEC_LABEL.matches(it) }
        if (lastLooksNumeric || allLabelsNumeric) {
            throw SafeUrlBlockedException("Non-canonical IPv4 host syntax is not allowed: $unbracketed")
        }
    }

    private fun validateParsedUrl(
        url: HttpUrl,
        rawHost: String,
        policy: UrlTargetPolicy,
        allowLoopbackForTests: Boolean,
        loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)?,
    ) {
        val unbracketed = stripBrackets(rawHost)
        val allowLoopback = allowLoopbackForTests && policy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY
        val allowlist = if (policy == UrlTargetPolicy.PUBLIC_INTERNET_ONLY) {
            emptySet()
        } else {
            loopbackAllowlistProvider?.invoke().orEmpty()
        }

        if (isSyntacticLoopbackHost(unbracketed)) {
            if (!allowLoopback) {
                throw SafeUrlBlockedException("Syntactic loopback hostname blocked: $unbracketed")
            }
            return
        }

        val literal = parseIpLiteralOrNull(unbracketed)
        if (literal != null) {
            if (isLinkLocalV6(literal)) {
                throw SafeUrlBlockedException("Link-local IPv6 literal blocked: $unbracketed")
            }
            val hopPolicy = effectiveHopPolicy(policy, url.isHttps)
            validateAddressForPolicy(
                address = literal,
                policy = hopPolicy,
                allowLoopbackForTests = allowLoopback,
                loopbackAllowlist = allowlist,
                port = url.port,
            )
        }
    }

    internal fun parseIpLiteralOrNull(host: String): InetAddress? {
        val unbracketed = stripBrackets(host.trim())
        if (unbracketed.isEmpty() || '%' in unbracketed) return null
        if (unbracketed.contains(':')) {
            val bytes = parseIpv6BytesOrNull(unbracketed) ?: return null
            return InetAddress.getByAddress(unbracketed, bytes)
        }
        val labels = unbracketed.split('.')
        if (labels.size == 4 && labels.all { CANONICAL_IPV4_LABEL.matches(it) }) {
            val ints = labels.map { it.toIntOrNull() ?: return null }
            if (ints.all { it in 0..255 }) {
                val bytes = byteArrayOf(
                    ints[0].toByte(),
                    ints[1].toByte(),
                    ints[2].toByte(),
                    ints[3].toByte(),
                )
                return InetAddress.getByAddress(unbracketed, bytes)
            }
        }
        return null
    }

    private fun parseIpv6BytesOrNull(raw: String): ByteArray? {
        var input = raw
        val doubleColonIndex = input.indexOf("::")
        if (doubleColonIndex != -1 && input.indexOf("::", doubleColonIndex + 2) != -1) {
            return null // Multiple "::"
        }

        // Check for embedded IPv4 tail (e.g. ::ffff:127.0.0.1)
        var ipv4Tail: ByteArray? = null
        val lastColon = input.lastIndexOf(':')
        if (lastColon != -1 && '.' in input.substring(lastColon + 1)) {
            val v4Part = input.substring(lastColon + 1)
            val v4Labels = v4Part.split('.')
            if (v4Labels.size != 4 || !v4Labels.all { CANONICAL_IPV4_LABEL.matches(it) }) {
                return null
            }
            val ints = v4Labels.map { it.toIntOrNull() ?: return null }
            if (ints.any { it !in 0..255 }) return null
            ipv4Tail = byteArrayOf(
                ints[0].toByte(),
                ints[1].toByte(),
                ints[2].toByte(),
                ints[3].toByte(),
            )
            input = input.substring(0, lastColon)
            if (doubleColonIndex == lastColon - 1) {
                input += "::"
            }
        }

        val targetGroups = if (ipv4Tail != null) 6 else 8
        val leftStr: String
        val rightStr: String?
        val splitIdx = input.indexOf("::")
        if (splitIdx != -1) {
            leftStr = input.substring(0, splitIdx)
            rightStr = input.substring(splitIdx + 2)
        } else {
            leftStr = input
            rightStr = null
        }

        val leftGroups = if (leftStr.isEmpty()) emptyList() else leftStr.split(':')
        val rightGroups = if (rightStr.isNullOrEmpty()) emptyList() else rightStr.split(':')

        val parsedLeft = leftGroups.map { parseHexGroup(it) ?: return null }
        val parsedRight = rightGroups.map { parseHexGroup(it) ?: return null }

        val totalExplicit = parsedLeft.size + parsedRight.size
        if (rightStr == null) {
            if (totalExplicit != targetGroups) return null
        } else {
            if (totalExplicit > targetGroups) return null
        }

        val zeroFill = targetGroups - totalExplicit
        val out = ByteArray(16)
        var pos = 0
        for (g in parsedLeft) {
            out[pos++] = (g ushr 8).toByte()
            out[pos++] = (g and 0xFF).toByte()
        }
        repeat(zeroFill) {
            out[pos++] = 0
            out[pos++] = 0
        }
        for (g in parsedRight) {
            out[pos++] = (g ushr 8).toByte()
            out[pos++] = (g and 0xFF).toByte()
        }
        if (ipv4Tail != null) {
            System.arraycopy(ipv4Tail, 0, out, 12, 4)
        }
        return out
    }

    private fun parseHexGroup(group: String): Int? {
        if (group.isEmpty() || group.length > 4) return null
        return group.toIntOrNull(16)
    }

    internal fun isLoopbackAddress(address: InetAddress): Boolean {
        val unwrapped = unwrapEmbeddedIpv4(address)
        if (unwrapped != null) {
            return (unwrapped[0].toInt() and 0xFF) == 127
        }
        val b = address.address
        if (b.size == 4) return (b[0].toInt() and 0xFF) == 127
        if (b.size == 16) {
            for (i in 0 until 15) if (b[i].toInt() != 0) return false
            return b[15].toInt() == 1
        }
        return false
    }

    internal fun isLinkLocalV6(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 16) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 0xFE && (b1 and 0xC0) == 0x80
    }

    internal fun isDeprecatedOrTunnelV6(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 16) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        val b4 = b[4].toInt() and 0xFF
        val b5 = b[5].toInt() and 0xFF
        val b8 = b[8].toInt() and 0xFF
        val b9 = b[9].toInt() and 0xFF
        val b10 = b[10].toInt() and 0xFF
        val b11 = b[11].toInt() and 0xFF

        // ::/96 IPv4-compatible deprecated (excluding :: and ::1)
        val first12Zero = (0 until 12).all { b[it].toInt() == 0 }
        if (first12Zero) {
            val isUnspec = b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 && b[15].toInt() == 0
            val isLoopback = b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 && b[15].toInt() == 1
            if (!isUnspec && !isLoopback) return true
        }

        // ::ffff:0:0:0/96 (IPv4-translated: 0:0:0:0:ffff:0:x:y)
        val first8Zero = (0 until 8).all { b[it].toInt() == 0 }
        if (first8Zero && b8 == 0xFF && b9 == 0xFF && b10 == 0x00 && b11 == 0x00) {
            return true
        }

        // 64:ff9b:1::/48 (RFC 8215 local-use NAT64)
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B && b4 == 0x00 && b5 == 0x01) {
            return true
        }

        // 2002::/16 (6to4)
        if (b0 == 0x20 && b1 == 0x02) return true

        // 2001::/32 (Teredo)
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true

        // Raw-byte ISATAP: bytes[8] in {0x00, 0x02} && bytes[9] == 0x00 && bytes[10] == 0x5e && bytes[11] == 0xfe
        if ((b8 == 0x00 || b8 == 0x02) && b9 == 0x00 && b10 == 0x5E && b11 == 0xFE) {
            return true
        }

        return false
    }

    /**
     * Unwraps `::ffff:0:0/96` (IPv4-mapped) and `64:ff9b::/96` (RFC 6052 well-known NAT64)
     * into their 4-byte IPv4 payloads.
     */
    private fun unwrapEmbeddedIpv4(address: InetAddress): ByteArray? {
        val b = address.address
        if (b.size != 16) return null
        val isV4Mapped = (0 until 10).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF &&
            (b[11].toInt() and 0xFF) == 0xFF
        if (isV4Mapped) {
            return byteArrayOf(b[12], b[13], b[14], b[15])
        }
        val isWellKnownNat64 = (b[0].toInt() and 0xFF) == 0x00 &&
            (b[1].toInt() and 0xFF) == 0x64 &&
            (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B &&
            (4 until 12).all { b[it].toInt() == 0 }
        if (isWellKnownNat64) {
            return byteArrayOf(b[12], b[13], b[14], b[15])
        }
        return null
    }

    internal fun isUniversallyProhibitedNonLoopback(address: InetAddress): Boolean {
        if (address.address.size == 16 && isDeprecatedOrTunnelV6(address)) return true

        val v4 = unwrapEmbeddedIpv4(address) ?: if (address.address.size == 4) address.address else null
        if (v4 != null) {
            val a = v4[0].toInt() and 0xFF
            val b = v4[1].toInt() and 0xFF
            val c = v4[2].toInt() and 0xFF
            val d = v4[3].toInt() and 0xFF
            // 0.0.0.0/8 unspecified
            if (a == 0) return true
            // 169.254.0.0/16 link-local / IMDS
            if (a == 169 && b == 254) return true
            // 100.100.100.200/32 Alibaba Cloud IMDS
            if (a == 100 && b == 100 && c == 100 && d == 200) return true
            // 224.0.0.0/4 multicast, 240.0.0.0/4 Class E, 255.255.255.255 broadcast
            if (a >= 224) return true
            return false
        }

        val b = address.address
        if (b.size == 16) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // ::/128 unspecified
            if (b.all { it.toInt() == 0 }) return true
            // fe80::/10 link-local
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            // fec0::/10 deprecated site-local
            if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true
            // ff00::/8 multicast
            if (b0 == 0xFF) return true
            // fd00:ec2::254/128 AWS IPv6 IMDS
            if (
                b0 == 0xFD && b1 == 0x00 &&
                (b[2].toInt() and 0xFF) == 0x0E && (b[3].toInt() and 0xFF) == 0xC2 &&
                (4 until 14).all { b[it].toInt() == 0 } &&
                (b[14].toInt() and 0xFF) == 0x02 && (b[15].toInt() and 0xFF) == 0x54
            ) {
                return true
            }
        }
        return false
    }

    internal fun isPrivateLanAddress(address: InetAddress): Boolean {
        if (isLoopbackAddress(address) || isUniversallyProhibitedNonLoopback(address)) return false
        val v4 = unwrapEmbeddedIpv4(address) ?: if (address.address.size == 4) address.address else null
        if (v4 != null) {
            val a = v4[0].toInt() and 0xFF
            val b = v4[1].toInt() and 0xFF
            // RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            if (a == 10) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            // CGNAT: 100.64.0.0/10 (100.100.100.200 already excluded above)
            if (a == 100 && b in 64..127) return true
            return false
        }
        val b = address.address
        if (b.size == 16) {
            val b0 = b[0].toInt() and 0xFF
            // ULA: fc00::/7 (fd00:ec2::254 and ISATAP already excluded above)
            return (b0 and 0xFE) == 0xFC
        }
        return false
    }

    internal fun normalizedAddressString(address: InetAddress): String {
        val unwrapped = unwrapEmbeddedIpv4(address)
        if (unwrapped != null) {
            return unwrapped.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        val hostAddr = address.hostAddress?.substringBefore('%') ?: ""
        return hostAddr.lowercase()
    }

    internal fun isLoopbackAllowlisted(
        address: InetAddress,
        port: Int?,
        allowlist: Set<Pair<String, Int>>,
    ): Boolean {
        if (allowlist.isEmpty()) return false
        val ipStr = normalizedAddressString(address)
        val rawAddr = address.hostAddress?.substringBefore('%')?.lowercase().orEmpty()
        return if (port != null) {
            allowlist.any { (allowedHost, allowedPort) ->
                val norm = normalizeHostname(allowedHost)
                allowedPort == port && (norm == ipStr || norm == rawAddr)
            }
        } else {
            allowlist.any { (allowedHost, _) ->
                val norm = normalizeHostname(allowedHost)
                norm == ipStr || norm == rawAddr
            }
        }
    }

    internal fun validateAddressForPolicy(
        address: InetAddress,
        policy: UrlTargetPolicy,
        allowLoopbackForTests: Boolean,
        loopbackAllowlist: Set<Pair<String, Int>>,
        port: Int?,
    ) {
        if (isUniversallyProhibitedNonLoopback(address)) {
            throw SafeUrlBlockedException("Blocked prohibited IP address: ${address.hostAddress}")
        }

        if (isLoopbackAddress(address)) {
            val allowed = policy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY &&
                (allowLoopbackForTests || isLoopbackAllowlisted(address, port, loopbackAllowlist))
            if (!allowed) {
                throw SafeUrlBlockedException("Blocked loopback IP address: ${address.hostAddress}")
            }
            return
        }

        when (policy) {
            UrlTargetPolicy.PRIVATE_LAN_ONLY -> {
                if (!isPrivateLanAddress(address)) {
                    throw SafeUrlBlockedException("Blocked non-LAN IP address under PRIVATE_LAN_ONLY: ${address.hostAddress}")
                }
            }
            UrlTargetPolicy.PUBLIC_INTERNET_ONLY -> {
                if (isPrivateLanAddress(address)) {
                    throw SafeUrlBlockedException("Blocked private LAN IP under PUBLIC_INTERNET_ONLY: ${address.hostAddress}")
                }
            }
            UrlTargetPolicy.LAN_ONLY,
            UrlTargetPolicy.PUBLIC_OR_LAN -> {
                // Non-prohibited, non-loopback IPs are allowed here (for LAN_ONLY, HTTP hops
                // already downgraded policy to PRIVATE_LAN_ONLY before calling this).
            }
        }
    }

    /**
     * Applies the 3-step DNS filtering algorithm:
     * 1. Strip `fe80::/10` from multi-record answers (failing closed if empty).
     * 2. Fail closed if ANY remaining record is universally prohibited (or unallowlisted loopback).
     * 3. For `.local` under `PRIVATE_LAN_ONLY`, filter remaining records to private LAN
     *    (failing closed if empty); otherwise fail closed if ANY remaining record violates [policy].
     */
    internal fun filterResolvedAddresses(
        hostname: String,
        addresses: List<InetAddress>,
        policy: UrlTargetPolicy,
        allowLoopbackForTests: Boolean,
        loopbackAllowlist: Set<Pair<String, Int>>,
    ): List<InetAddress> {
        if (addresses.isEmpty()) {
            throw SafeUrlBlockedException("DNS resolution returned no addresses for $hostname")
        }

        // Step 1: Strip fe80::/10
        val withoutLinkLocalV6 = addresses.filterNot { isLinkLocalV6(it) }
        if (withoutLinkLocalV6.isEmpty()) {
            throw SafeUrlBlockedException("Only link-local IPv6 addresses resolved for $hostname")
        }

        val allowLoopback = allowLoopbackForTests && policy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY

        // Step 2: Fail closed immediately if ANY remaining record is universally prohibited or disallowed loopback
        for (addr in withoutLinkLocalV6) {
            if (isUniversallyProhibitedNonLoopback(addr)) {
                throw SafeUrlBlockedException("DNS returned prohibited IP ${addr.hostAddress} for $hostname")
            }
            if (isLoopbackAddress(addr)) {
                val allowed = allowLoopback || isLoopbackAllowlisted(addr, null, loopbackAllowlist)
                if (!allowed) {
                    throw SafeUrlBlockedException("DNS returned loopback IP ${addr.hostAddress} for $hostname")
                }
            }
        }

        // Step 3: Policy enforcement (.local filtering vs strict all-record validation)
        val normHost = normalizeHostname(hostname)
        if (normHost.endsWith(".local") && policy == UrlTargetPolicy.PRIVATE_LAN_ONLY) {
            val lanOnly = withoutLinkLocalV6.filter { isPrivateLanAddress(it) }
            if (lanOnly.isEmpty()) {
                throw SafeUrlBlockedException("mDNS .local host $hostname resolved to no private LAN addresses")
            }
            return lanOnly
        }

        for (addr in withoutLinkLocalV6) {
            validateAddressForPolicy(
                address = addr,
                policy = policy,
                allowLoopbackForTests = allowLoopback,
                loopbackAllowlist = loopbackAllowlist,
                port = null,
            )
        }
        return withoutLinkLocalV6
    }

    internal fun effectivePort(uri: URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> -1
        }
    }
}

internal class GuardedProxySelector(
    internal val delegate: ProxySelector?,
    private val loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)?,
    private val allowLoopbackForTests: Boolean,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)

        val host = uri.host?.let { SafeUrlPolicy.stripBrackets(it) }
        val port = SafeUrlPolicy.effectivePort(uri)
        val allowlist = loopbackAllowlistProvider?.invoke().orEmpty()

        // Allowlisted literal loopback targets bypass any system HTTP proxy directly
        if (host != null && port > 0) {
            val literal = SafeUrlPolicy.parseIpLiteralOrNull(host)
            if (literal != null && SafeUrlPolicy.isLoopbackAddress(literal)) {
                if (allowLoopbackForTests || SafeUrlPolicy.isLoopbackAllowlisted(literal, port, allowlist)) {
                    return listOf(Proxy.NO_PROXY)
                }
            }
        }

        val candidates = delegate?.select(uri).orEmpty()
        if (candidates.isEmpty()) {
            return listOf(Proxy.NO_PROXY)
        }

        val validHttpProxies = mutableListOf<Proxy>()
        val ctx = SafeUrlPolicy.activeProxyContext.get()

        for (proxy in candidates) {
            when (proxy.type()) {
                Proxy.Type.DIRECT -> {
                    // Handled below: stripped if any valid HTTP proxy is present
                }
                Proxy.Type.SOCKS -> {
                    throw SafeUrlBlockedException("SOCKS proxies are blocked by SafeUrlPolicy")
                }
                Proxy.Type.HTTP -> {
                    val addr = proxy.address()
                    if (addr !is InetSocketAddress) {
                        throw SafeUrlBlockedException("Invalid HTTP proxy address: $addr")
                    }
                    val proxyPort = addr.port
                    if (proxyPort !in 1..65535) {
                        throw SafeUrlBlockedException("Invalid HTTP proxy port: $proxyPort")
                    }
                    if (!addr.isUnresolved && addr.address != null) {
                        val resolvedIp = addr.address
                        if (SafeUrlPolicy.isUniversallyProhibitedNonLoopback(resolvedIp)) {
                            throw SafeUrlBlockedException("HTTP proxy resolves to prohibited IP: ${resolvedIp.hostAddress}")
                        }
                        if (SafeUrlPolicy.isLoopbackAddress(resolvedIp)) {
                            if (!SafeUrlPolicy.isLiteralLoopbackProxyHost(addr.hostString)) {
                                throw SafeUrlBlockedException(
                                    "Resolved loopback HTTP proxy blocked for non-literal host: ${addr.hostString}",
                                )
                            }
                        }
                        ctx?.allowedProxyEndpoints?.add(
                            SafeUrlPolicy.normalizedAddressString(resolvedIp) to proxyPort,
                        )
                    } else {
                        val rawProxyHost = SafeUrlPolicy.stripBrackets(addr.hostString.orEmpty().trim())
                        if (rawProxyHost.isEmpty()) {
                            throw SafeUrlBlockedException("HTTP proxy host is empty")
                        }
                        SafeUrlPolicy.validateRawHostSyntax(rawProxyHost)
                        val literalProxyIp = SafeUrlPolicy.parseIpLiteralOrNull(rawProxyHost)
                        if (literalProxyIp != null) {
                            if (SafeUrlPolicy.isUniversallyProhibitedNonLoopback(literalProxyIp)) {
                                throw SafeUrlBlockedException("HTTP proxy uses prohibited IP: ${literalProxyIp.hostAddress}")
                            }
                            if (SafeUrlPolicy.isLoopbackAddress(literalProxyIp) &&
                                !SafeUrlPolicy.isLiteralLoopbackProxyHost(rawProxyHost)
                            ) {
                                throw SafeUrlBlockedException("HTTP proxy uses disallowed loopback IP: $rawProxyHost")
                            }
                            ctx?.allowedProxyEndpoints?.add(
                                SafeUrlPolicy.normalizedAddressString(literalProxyIp) to proxyPort,
                            )
                        } else {
                            val normProxyHost = SafeUrlPolicy.normalizeHostname(rawProxyHost)
                            ctx?.unresolvedProxyHosts
                                ?.computeIfAbsent(normProxyHost) { Collections.newSetFromMap(ConcurrentHashMap()) }
                                ?.add(proxyPort)
                        }
                    }
                    validHttpProxies.add(proxy)
                }
            }
        }

        if (validHttpProxies.isNotEmpty()) {
            ctx?.httpProxyOnly = true
            return validHttpProxies
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegate?.connectFailed(uri, sa, ioe)
    }
}

internal class GuardedDns(
    private val policy: UrlTargetPolicy,
    internal val allowLoopbackForTests: Boolean,
    private val loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)?,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val unbracketed = SafeUrlPolicy.stripBrackets(hostname.trim())
        SafeUrlPolicy.validateRawHostSyntax(unbracketed)

        val ctx = SafeUrlPolicy.activeProxyContext.get()
        val normHost = SafeUrlPolicy.normalizeHostname(unbracketed)

        // Check if resolving an unresolved HTTP proxy host recorded by GuardedProxySelector
        if (ctx != null && ctx.httpProxyOnly) {
            val proxyPorts = ctx.unresolvedProxyHosts[normHost]
            if (proxyPorts != null && proxyPorts.isNotEmpty()) {
                val resolved = delegate.lookup(unbracketed)
                val withoutLinkLocalV6 = resolved.filterNot { SafeUrlPolicy.isLinkLocalV6(it) }
                if (withoutLinkLocalV6.isEmpty()) {
                    throw SafeUrlBlockedException("HTTP proxy host $hostname resolved to no usable addresses")
                }
                val isLiteralLoopbackProxy = SafeUrlPolicy.isLiteralLoopbackProxyHost(unbracketed)
                for (addr in withoutLinkLocalV6) {
                    if (SafeUrlPolicy.isUniversallyProhibitedNonLoopback(addr)) {
                        throw SafeUrlBlockedException("HTTP proxy host $hostname resolved to prohibited IP ${addr.hostAddress}")
                    }
                    if (SafeUrlPolicy.isLoopbackAddress(addr) && !isLiteralLoopbackProxy) {
                        throw SafeUrlBlockedException("HTTP proxy host $hostname resolved to loopback IP ${addr.hostAddress}")
                    }
                    val normIp = SafeUrlPolicy.normalizedAddressString(addr)
                    for (p in proxyPorts) {
                        ctx.allowedProxyEndpoints.add(normIp to p)
                    }
                }
                return withoutLinkLocalV6
            }
        }

        val activePolicy = when (policy) {
            UrlTargetPolicy.LAN_ONLY -> ctx?.hopPolicy ?: UrlTargetPolicy.PRIVATE_LAN_ONLY
            else -> policy
        }
        val allowLoopback = allowLoopbackForTests && activePolicy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY
        val allowlist = if (activePolicy == UrlTargetPolicy.PUBLIC_INTERNET_ONLY) {
            emptySet()
        } else {
            loopbackAllowlistProvider?.invoke().orEmpty()
        }

        if (SafeUrlPolicy.isSyntacticLoopbackHost(unbracketed) && !allowLoopback) {
            throw SafeUrlBlockedException("Syntactic loopback hostname blocked in DNS lookup: $unbracketed")
        }

        val resolved = delegate.lookup(unbracketed)
        return SafeUrlPolicy.filterResolvedAddresses(
            hostname = unbracketed,
            addresses = resolved,
            policy = activePolicy,
            allowLoopbackForTests = allowLoopback,
            loopbackAllowlist = allowlist,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuardedDns) return false
        return policy == other.policy &&
            allowLoopbackForTests == other.allowLoopbackForTests &&
            loopbackAllowlistProvider === other.loopbackAllowlistProvider &&
            delegate == other.delegate
    }

    override fun hashCode(): Int {
        var result = policy.hashCode()
        result = 31 * result + allowLoopbackForTests.hashCode()
        result = 31 * result + System.identityHashCode(loopbackAllowlistProvider)
        result = 31 * result + delegate.hashCode()
        return result
    }
}

internal class GuardedSocketFactory(
    private val policy: UrlTargetPolicy,
    private val allowLoopbackForTests: Boolean,
    private val loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)?,
    private val dns: GuardedDns,
    private val delegate: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {

    private fun validateEndpoint(address: InetAddress, port: Int) {
        val ctx = SafeUrlPolicy.activeProxyContext.get()
        val isHttpProxyRoute = ctx != null &&
            (ctx.activeRouteIsHttpProxy || (ctx.isWebSocket && ctx.httpProxyOnly))
        if (isHttpProxyRoute && ctx != null) {
            val normIp = SafeUrlPolicy.normalizedAddressString(address)
            if ((normIp to port) in ctx.allowedProxyEndpoints) {
                return
            }
            throw SafeUrlBlockedException("Socket connect to unapproved HTTP proxy endpoint $normIp:$port")
        }

        val activePolicy = when (policy) {
            UrlTargetPolicy.LAN_ONLY -> ctx?.hopPolicy ?: UrlTargetPolicy.PRIVATE_LAN_ONLY
            else -> policy
        }
        val allowLoopback = allowLoopbackForTests && activePolicy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY
        val allowlist = if (activePolicy == UrlTargetPolicy.PUBLIC_INTERNET_ONLY) {
            emptySet()
        } else {
            loopbackAllowlistProvider?.invoke().orEmpty()
        }
        SafeUrlPolicy.validateAddressForPolicy(
            address = address,
            policy = activePolicy,
            allowLoopbackForTests = allowLoopback,
            loopbackAllowlist = allowlist,
            port = port,
        )
    }

    private inner class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?) {
            connect(endpoint, 0)
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (endpoint !is InetSocketAddress || endpoint.isUnresolved || endpoint.address == null) {
                throw SafeUrlBlockedException("Unresolved or non-InetSocketAddress blocked: $endpoint")
            }
            validateEndpoint(endpoint.address, endpoint.port)
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(): Socket = GuardedSocket()

    override fun createSocket(host: String, port: Int): Socket {
        val addresses = dns.lookup(host)
        val target = addresses.first()
        validateEndpoint(target, port)
        return GuardedSocket().apply {
            connect(InetSocketAddress(target, port))
        }
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val addresses = dns.lookup(host)
        val target = addresses.first()
        validateEndpoint(target, port)
        return GuardedSocket().apply {
            if (localHost != null) bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(target, port))
        }
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        validateEndpoint(host, port)
        return GuardedSocket().apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        validateEndpoint(address, port)
        return GuardedSocket().apply {
            if (localAddress != null) bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuardedSocketFactory) return false
        return policy == other.policy &&
            allowLoopbackForTests == other.allowLoopbackForTests &&
            loopbackAllowlistProvider === other.loopbackAllowlistProvider &&
            dns == other.dns
    }

    override fun hashCode(): Int {
        var result = policy.hashCode()
        result = 31 * result + allowLoopbackForTests.hashCode()
        result = 31 * result + System.identityHashCode(loopbackAllowlistProvider)
        result = 31 * result + dns.hashCode()
        return result
    }
}

class SafeRedirectInterceptor(
    private val policy: UrlTargetPolicy,
    private val allowLoopbackForTests: Boolean = false,
    private val loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)? = null,
    private val allowCrossOriginRedirects: Boolean = (policy == UrlTargetPolicy.PUBLIC_OR_LAN),
    private val maxRedirects: Int = 5,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var currentRequest = chain.request()
        val isWebSocket = currentRequest.header("Upgrade").equals("websocket", ignoreCase = true)

        SafeUrlPolicy.validateInitialUrl(
            url = currentRequest.url,
            policy = policy,
            allowLoopbackForTests = allowLoopbackForTests,
            loopbackAllowlistProvider = loopbackAllowlistProvider,
        )

        val initialHopPolicy = SafeUrlPolicy.effectiveHopPolicy(policy, currentRequest.url.isHttps)
        val previousCtx = SafeUrlPolicy.activeProxyContext.get()
        val ctx = ProxyContext(
            call = chain.call(),
            isWebSocket = isWebSocket,
            hopPolicy = initialHopPolicy,
        )
        SafeUrlPolicy.activeProxyContext.set(ctx)

        try {
            var redirectCount = 0
            while (true) {
                val hopPolicy = SafeUrlPolicy.effectiveHopPolicy(policy, currentRequest.url.isHttps)
                ctx.resetForHop(hopPolicy)
                val response = chain.proceed(currentRequest)

                if (isWebSocket || maxRedirects <= 0 || response.code !in REDIRECT_CODES) {
                    return response
                }

                val location = response.header("Location")?.trim()
                if (location.isNullOrEmpty()) {
                    return response
                }

                if (redirectCount >= maxRedirects) {
                    response.close()
                    throw SafeUrlBlockedException("Too many redirects (max $maxRedirects)")
                }

                val currentUrl = currentRequest.url
                // Validate raw absolute Location target BEFORE OkHttp canonicalization if absolute
                val nextUrl = if (location.contains("://")) {
                    SafeUrlPolicy.validateInitialUrl(
                        rawUrl = location,
                        policy = policy,
                        allowLoopbackForTests = allowLoopbackForTests,
                        loopbackAllowlistProvider = loopbackAllowlistProvider,
                    )
                } else {
                    val resolved = currentUrl.resolve(location)
                        ?: run {
                            response.close()
                            throw SafeUrlBlockedException("Invalid redirect Location: $location")
                        }
                    SafeUrlPolicy.validateInitialUrl(
                        url = resolved,
                        policy = policy,
                        allowLoopbackForTests = allowLoopbackForTests,
                        loopbackAllowlistProvider = loopbackAllowlistProvider,
                    )
                }

                // Block HTTPS -> HTTP downgrade
                if (currentUrl.isHttps && !nextUrl.isHttps) {
                    response.close()
                    throw SafeUrlBlockedException("HTTPS to HTTP redirect downgrade blocked")
                }

                // Unconditionally block redirects from any non-loopback origin to a loopback target
                val currentIsLoopback = isOriginLoopback(currentUrl)
                val nextIsLoopback = isOriginLoopback(nextUrl)
                if (!currentIsLoopback && nextIsLoopback) {
                    response.close()
                    throw SafeUrlBlockedException("Redirect from non-loopback origin to loopback target blocked")
                }

                val sameOrigin = SafeUrlPolicy.isSameOrigin(currentUrl, nextUrl)
                val isHttpsDefaultUpgrade =
                    policy == UrlTargetPolicy.PUBLIC_OR_LAN &&
                        SafeUrlPolicy.isSameHostDefaultPortHttpsUpgrade(currentUrl, nextUrl)

                if (!sameOrigin && !allowCrossOriginRedirects) {
                    response.close()
                    throw SafeUrlBlockedException("Cross-origin redirect blocked under $policy")
                }

                // Method and body rewriting
                val method = currentRequest.method
                val rewriteToGet = when (response.code) {
                    303 -> method != "GET" && method != "HEAD"
                    301, 302 -> method == "POST"
                    else -> false
                }
                val newMethod = if (rewriteToGet) "GET" else method
                val retainedBody = if (rewriteToGet || !HttpMethod.permitsRequestBody(newMethod)) {
                    null
                } else {
                    currentRequest.body
                }

                if (retainedBody != null) {
                    if (retainedBody.isOneShot()) {
                        response.close()
                        throw SafeUrlBlockedException("Cannot redirect one-shot request body")
                    }
                    if (!sameOrigin && !isHttpsDefaultUpgrade && response.code in setOf(301, 302, 307, 308)) {
                        response.close()
                        throw SafeUrlBlockedException("Cross-origin redirect retaining request body blocked")
                    }
                }

                val nextBuilder = currentRequest.newBuilder().url(nextUrl)
                if (rewriteToGet) {
                    nextBuilder.method("GET", null)
                    nextBuilder.removeHeader("Content-Length")
                    nextBuilder.removeHeader("Content-Type")
                    nextBuilder.removeHeader("Content-Encoding")
                    nextBuilder.removeHeader("Transfer-Encoding")
                } else {
                    nextBuilder.method(newMethod, retainedBody)
                }

                // Strip sensitive headers on any origin change except same-host default-port HTTPS upgrade under PUBLIC_OR_LAN
                if (!sameOrigin && !isHttpsDefaultUpgrade) {
                    for (headerName in currentRequest.headers.names()) {
                        if (SafeUrlPolicy.isSensitiveHeader(headerName)) {
                            nextBuilder.removeHeader(headerName)
                        }
                    }
                }

                response.close()
                currentRequest = nextBuilder.build()
                redirectCount++
            }
        } finally {
            if (previousCtx == null) {
                SafeUrlPolicy.activeProxyContext.remove()
            } else {
                SafeUrlPolicy.activeProxyContext.set(previousCtx)
            }
        }
    }

    private fun isOriginLoopback(url: HttpUrl): Boolean {
        val host = SafeUrlPolicy.stripBrackets(url.host)
        if (SafeUrlPolicy.isSyntacticLoopbackHost(host)) return true
        val ip = SafeUrlPolicy.parseIpLiteralOrNull(host) ?: return false
        return SafeUrlPolicy.isLoopbackAddress(ip)
    }

    private companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal class GuardedNetworkInterceptor(
    private val policy: UrlTargetPolicy,
    private val allowLoopbackForTests: Boolean,
    private val loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = chain.connection()
        if (connection != null) {
            val route = connection.route()
            if (route.proxy.type() != Proxy.Type.HTTP) {
                val socket = connection.socket()
                val inetAddress = socket.inetAddress
                if (inetAddress != null) {
                    val activePolicy = SafeUrlPolicy.effectiveHopPolicy(policy, chain.request().url.isHttps)
                    val allowLoopback = allowLoopbackForTests && activePolicy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY
                    val allowlist = if (activePolicy == UrlTargetPolicy.PUBLIC_INTERNET_ONLY) {
                        emptySet()
                    } else {
                        loopbackAllowlistProvider?.invoke().orEmpty()
                    }
                    SafeUrlPolicy.validateAddressForPolicy(
                        address = inetAddress,
                        policy = activePolicy,
                        allowLoopbackForTests = allowLoopback,
                        loopbackAllowlist = allowlist,
                        port = socket.port,
                    )
                }
            }
        }
        return chain.proceed(chain.request())
    }
}

internal class GuardedEventListenerFactory(
    internal val delegate: EventListener.Factory,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val inner = delegate.create(call)
        return object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                val ctx = SafeUrlPolicy.activeProxyContext.get()
                if (ctx?.call === call) {
                    ctx.activeRouteIsHttpProxy = proxy.type() == Proxy.Type.HTTP
                }
                inner.connectStart(call, inetSocketAddress, proxy)
            }

            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: okhttp3.Protocol?,
            ) {
                val ctx = SafeUrlPolicy.activeProxyContext.get()
                if (ctx?.call === call) {
                    ctx.activeRouteIsHttpProxy = false
                }
                inner.connectEnd(call, inetSocketAddress, proxy, protocol)
            }

            override fun connectFailed(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: okhttp3.Protocol?,
                ioe: IOException,
            ) {
                val ctx = SafeUrlPolicy.activeProxyContext.get()
                if (ctx?.call === call) {
                    ctx.activeRouteIsHttpProxy = false
                }
                inner.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
            }

            override fun callEnd(call: Call) {
                val ctx = SafeUrlPolicy.activeProxyContext.get()
                if (ctx?.call === call) {
                    ctx.activeRouteIsHttpProxy = false
                }
                inner.callEnd(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                val ctx = SafeUrlPolicy.activeProxyContext.get()
                if (ctx?.call === call) {
                    ctx.activeRouteIsHttpProxy = false
                }
                inner.callFailed(call, ioe)
            }
        }
    }
}

fun OkHttpClient.newBuilderWithSsrfGuard(
    policy: UrlTargetPolicy,
    allowLoopbackForTests: Boolean = false,
    loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)? = null,
    allowCrossOriginRedirects: Boolean = (policy == UrlTargetPolicy.PUBLIC_OR_LAN),
    maxRedirects: Int = 5,
): OkHttpClient.Builder {
    val inheritedTestLoopback =
        policy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY &&
            (this.dns as? GuardedDns)?.allowLoopbackForTests == true
    val unwrappedProxySelector = unwrapProxySelector(this.proxySelector)
    val unwrappedEventListenerFactory = unwrapEventListenerFactory(this.eventListenerFactory)
    val builder = this.newBuilder()
    builder.interceptors().removeAll { it is SafeRedirectInterceptor }
    builder.networkInterceptors().removeAll { it is GuardedNetworkInterceptor }
    return builder.withSsrfGuard(
        policy = policy,
        allowLoopbackForTests = allowLoopbackForTests || inheritedTestLoopback,
        loopbackAllowlistProvider = loopbackAllowlistProvider,
        allowCrossOriginRedirects = allowCrossOriginRedirects,
        maxRedirects = maxRedirects,
        baseProxySelector = unwrappedProxySelector,
        baseEventListenerFactory = unwrappedEventListenerFactory,
    )
}

fun OkHttpClient.Builder.withSsrfGuard(
    policy: UrlTargetPolicy,
    allowLoopbackForTests: Boolean = false,
    loopbackAllowlistProvider: (() -> Set<Pair<String, Int>>)? = null,
    allowCrossOriginRedirects: Boolean = (policy == UrlTargetPolicy.PUBLIC_OR_LAN),
    maxRedirects: Int = 5,
    baseProxySelector: ProxySelector? = ProxySelector.getDefault(),
    baseEventListenerFactory: EventListener.Factory = EventListener.Factory { EventListener.NONE },
): OkHttpClient.Builder {
    interceptors().removeAll { it is SafeRedirectInterceptor }
    networkInterceptors().removeAll { it is GuardedNetworkInterceptor }

    val effectiveAllowLoopback =
        allowLoopbackForTests && policy != UrlTargetPolicy.PUBLIC_INTERNET_ONLY
    val effectiveAllowlistProvider =
        if (policy == UrlTargetPolicy.PUBLIC_INTERNET_ONLY) null else loopbackAllowlistProvider

    val guardedDns = GuardedDns(
        policy = policy,
        allowLoopbackForTests = effectiveAllowLoopback,
        loopbackAllowlistProvider = effectiveAllowlistProvider,
    )
    val guardedSocketFactory = GuardedSocketFactory(
        policy = policy,
        allowLoopbackForTests = effectiveAllowLoopback,
        loopbackAllowlistProvider = effectiveAllowlistProvider,
        dns = guardedDns,
    )

    followRedirects(false)
    followSslRedirects(false)
    fastFallback(false)
    dns(guardedDns)
    socketFactory(guardedSocketFactory)
    eventListenerFactory(GuardedEventListenerFactory(unwrapEventListenerFactory(baseEventListenerFactory)))

    when (policy) {
        UrlTargetPolicy.PRIVATE_LAN_ONLY,
        UrlTargetPolicy.LAN_ONLY,
        UrlTargetPolicy.PUBLIC_INTERNET_ONLY -> {
            proxy(Proxy.NO_PROXY)
        }
        UrlTargetPolicy.PUBLIC_OR_LAN -> {
            proxy(null)
            proxySelector(
                GuardedProxySelector(
                    delegate = unwrapProxySelector(baseProxySelector),
                    loopbackAllowlistProvider = effectiveAllowlistProvider,
                    allowLoopbackForTests = effectiveAllowLoopback,
                ),
            )
        }
    }

    addInterceptor(
        SafeRedirectInterceptor(
            policy = policy,
            allowLoopbackForTests = effectiveAllowLoopback,
            loopbackAllowlistProvider = effectiveAllowlistProvider,
            allowCrossOriginRedirects = allowCrossOriginRedirects,
            maxRedirects = maxRedirects,
        ),
    )
    addNetworkInterceptor(
        GuardedNetworkInterceptor(
            policy = policy,
            allowLoopbackForTests = effectiveAllowLoopback,
            loopbackAllowlistProvider = effectiveAllowlistProvider,
        ),
    )
    return this
}

private fun unwrapProxySelector(selector: ProxySelector?): ProxySelector? {
    var current = selector
    while (current is GuardedProxySelector) {
        current = current.delegate
    }
    return current
}

private fun unwrapEventListenerFactory(factory: EventListener.Factory): EventListener.Factory {
    var current = factory
    while (current is GuardedEventListenerFactory) {
        current = current.delegate
    }
    return current
}
