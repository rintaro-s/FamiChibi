package com.nbks.famichibi.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*

object ApiClient {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(JsonConfig.json) }
        install(HttpTimeout) { requestTimeoutMillis = 30000 }
    }

    private fun authQuery(url: String, userId: String, userName: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "${sep}user_id=${urlEncode(userId)}&user_name=${urlEncode(userName)}"
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    suspend fun get(url: String, userId: String, userName: String): HttpResponse {
        return client.get(url + authQuery(url, userId, userName))
    }

    suspend fun postForm(url: String, userId: String, userName: String, params: Parameters): HttpResponse {
        return client.submitForm(url + authQuery(url, userId, userName), formParameters = params)
    }

    suspend fun post(url: String, userId: String, userName: String): HttpResponse {
        return client.post(url + authQuery(url, userId, userName))
    }

    suspend fun putForm(url: String, userId: String, userName: String, params: Parameters): HttpResponse {
        return client.submitForm(
            url = url + authQuery(url, userId, userName),
            formParameters = params
        ) { method = HttpMethod.Put }
    }

    suspend fun delete(url: String, userId: String, userName: String): HttpResponse {
        return client.delete(url + authQuery(url, userId, userName))
    }
}
