package com.nbks.famichibi.util

private val HONORIFIC_SUFFIXES = listOf("ちゃん", "くん", "さん", "様", "殿", "先生", "先輩", "後輩", "君")

fun formatName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "匿名"
    if (HONORIFIC_SUFFIXES.any { trimmed.endsWith(it) }) return trimmed
    return "${trimmed}さん"
}
