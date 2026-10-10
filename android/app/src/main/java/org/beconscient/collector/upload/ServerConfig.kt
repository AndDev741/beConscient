package org.beconscient.collector.upload

import org.beconscient.collector.BuildConfig
import org.beconscient.collector.util.Base64Url

/** Server address and HPKE public key, fixed at build time (see app/build.gradle.kts). */
data class ServerConfig(
    val baseUrl: String,
    val kid: String,
    val publicKey: ByteArray,
) {
    val pairUrl: String get() = "$baseUrl/ingest/v1/pair"
    val batchUrl: String get() = "$baseUrl/ingest/v1/batches"

    companion object {
        fun fromBuild(): ServerConfig = ServerConfig(
            baseUrl = BuildConfig.SERVER_URL.trimEnd('/'),
            kid = BuildConfig.SERVER_KID,
            publicKey = Base64Url.decode(BuildConfig.SERVER_PUBLIC_KEY),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is ServerConfig && baseUrl == other.baseUrl && kid == other.kid && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = (baseUrl.hashCode() * 31 + kid.hashCode()) * 31 + publicKey.contentHashCode()
}
