package dev.dimension.flare.common

internal actual object SystemUtils {
    actual val isAndroid: Boolean = false

    actual val isBlurSupported: Boolean
        get() = true
}
