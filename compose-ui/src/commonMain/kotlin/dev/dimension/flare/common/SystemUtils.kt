package dev.dimension.flare.common

internal expect object SystemUtils {
    val isAndroid: Boolean
    val isBlurSupported: Boolean
}
