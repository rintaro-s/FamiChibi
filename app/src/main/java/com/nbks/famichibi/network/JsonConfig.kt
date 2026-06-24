package com.nbks.famichibi.network

import kotlinx.serialization.json.Json

object JsonConfig {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }
}
