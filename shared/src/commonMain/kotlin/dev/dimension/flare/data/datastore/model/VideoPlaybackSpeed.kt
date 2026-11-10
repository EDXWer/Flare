package dev.dimension.flare.data.datastore.model

public enum class VideoPlaybackSpeed(
    public val rate: Float,
    public val label: String,
) {
    HALF(0.5f, "0.5×"),
    THREE_QUARTERS(0.75f, "0.75×"),
    NORMAL(1f, "1×"),
    ONE_AND_QUARTER(1.25f, "1.25×"),
    ONE_AND_HALF(1.5f, "1.5×"),
    ONE_AND_THREE_QUARTERS(1.75f, "1.75×"),
    DOUBLE(2f, "2×"),
    FOUR_TIMES(4f, "4×"),
    EIGHT_TIMES(8f, "8×"),
    SIXTEEN_TIMES(16f, "16×"),
    ;

    public companion object {
        public fun fromRate(rate: Float): VideoPlaybackSpeed = entries.firstOrNull { it.rate == rate } ?: NORMAL
    }
}
