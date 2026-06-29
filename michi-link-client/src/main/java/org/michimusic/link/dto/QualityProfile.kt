package org.michimusic.link.dto

enum class QualityProfile(val id: String, val label: String) {
    ORIGINAL("original", "Calidad original"),
    MOBILE_BALANCED("mobile_balanced", "Balanceado"),
    MOBILE_LOW_STORAGE("mobile_low_storage", "Poco almacenamiento"),
}
