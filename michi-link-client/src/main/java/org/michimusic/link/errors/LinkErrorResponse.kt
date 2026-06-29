package org.michimusic.link.errors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LinkErrorResponse(
    val error: LinkErrorDetail? = null,
)

@Serializable
data class LinkErrorDetail(
    val code: String = "",
    val message: String = "",
    val details: String = "",
)
