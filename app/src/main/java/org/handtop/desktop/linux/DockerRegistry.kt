package org.handtop.desktop.linux

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal anonymous client for the Docker Hub registry (OCI distribution API): enough to
 * fetch the arm64 root filesystem of an official image such as `library/debian`.
 */
class DockerRegistry(private val repository: String) {

    /** One compressed filesystem layer of an image. */
    data class Layer(val digest: String, val size: Long, val mediaType: String)

    private val token: String by lazy {
        val scope = URLEncoder.encode("repository:$repository:pull", "UTF-8")
        val json = JSONObject(get("$AUTH_URL?service=registry.docker.io&scope=$scope", emptyMap()).readText())
        json.optString("token").ifEmpty { json.getString("access_token") }
    }

    /** Layers of [tag] for linux/arm64, resolving a multi-arch index if needed. */
    fun layers(tag: String): List<Layer> {
        var manifest = manifest(tag)
        if (manifest.has("manifests")) {
            val list = manifest.getJSONArray("manifests")
            val arm64 = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull {
                val p = it.optJSONObject("platform") ?: return@firstOrNull false
                p.optString("os") == "linux" && p.optString("architecture") == "arm64"
            } ?: throw IOException("$repository:$tag has no linux/arm64 image")
            manifest = manifest(arm64.getString("digest"))
        }
        val layers = manifest.getJSONArray("layers")
        return (0 until layers.length()).map {
            val l = layers.getJSONObject(it)
            Layer(l.getString("digest"), l.getLong("size"), l.optString("mediaType"))
        }
    }

    /** Stream of a blob; the caller verifies its digest. */
    fun openBlob(digest: String): HttpURLConnection =
        open("$REGISTRY_URL/v2/$repository/blobs/$digest", mapOf("Authorization" to "Bearer $token"))

    private fun manifest(reference: String): JSONObject = JSONObject(
        get(
            "$REGISTRY_URL/v2/$repository/manifests/$reference",
            mapOf("Authorization" to "Bearer $token", "Accept" to MANIFEST_TYPES),
        ).readText()
    )

    private fun get(url: String, headers: Map<String, String>): InputStream = open(url, headers).inputStream

    /**
     * GET with manual redirects: blobs redirect to a CDN, which must not receive the
     * registry's Authorization header.
     */
    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        var current = URL(url)
        var requestHeaders = headers
        repeat(MAX_REDIRECTS) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "HandTop")
            requestHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                c.disconnect()
                current = URL(current, location)
                requestHeaders = requestHeaders - "Authorization"
            } else if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code: ${current.host}${current.path}")
            } else {
                return c
            }
        }
        throw IOException("Too many redirects: $url")
    }

    private fun InputStream.readText() = use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val AUTH_URL = "https://auth.docker.io/token"
        const val REGISTRY_URL = "https://registry-1.docker.io"
        const val MAX_REDIRECTS = 5
        const val MANIFEST_TYPES = "application/vnd.oci.image.index.v1+json, " +
            "application/vnd.docker.distribution.manifest.list.v2+json, " +
            "application/vnd.oci.image.manifest.v1+json, " +
            "application/vnd.docker.distribution.manifest.v2+json"
    }
}
