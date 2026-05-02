package com.nbks.famichibi.overlay

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class VrmHttpServer(port: Int, private val context: Context) : NanoHTTPD(port) {

    override fun serve(
        uri: String,
        method: Method?,
        headers: Map<String, String>?,
        parms: Map<String, String>?,
        files: Map<String, String>?
    ): Response {
        return when {
            uri == "/" || uri == "/index.html" -> serveHtml()
            uri.startsWith("/vrm/") -> serveVrm(uri.removePrefix("/vrm/"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveHtml(): Response {
        return try {
            val stream = context.assets.open("vrm-viewer/index.html")
            newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveVrm(name: String): Response {
        // 1. ユーザーがインポートしたファイルを優先
        val userFile = File(context.filesDir, name)
        if (userFile.exists()) {
            return try {
                newChunkedResponse(Response.Status.OK, "application/octet-stream", FileInputStream(userFile))
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        // 2. assetsから検索
        return try {
            val stream = context.assets.open("vrm-viewer/$name")
            newChunkedResponse(Response.Status.OK, "application/octet-stream", stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "VRM not found: $name")
        }
    }
}
