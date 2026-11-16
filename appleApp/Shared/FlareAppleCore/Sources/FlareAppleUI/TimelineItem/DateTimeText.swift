import SwiftUI
import KotlinSharedUI

public struct DateTimeText: View {
    @Environment(\.timelineAppearance.absoluteTimestamp) private var absoluteTimestamp
    private let data: UiDateTime
    private let fullTime: Bool

    public init(data: UiDateTime, fullTime: Bool) {
        self.data = data
        self.fullTime = fullTime
    }

    public var body: some View {
        if let date = data.displayDate {
            if fullTime {
                Text(data.full)
            } else if data.shouldShowFull {
                Text(date, style: .date)
            } else if absoluteTimestamp {
                Text(data.absolute)
            } else {
                Text(date, style: .relative)
            }
        }
    }
}

public extension DateTimeText {
    init(data: UiDateTime) {
        self.init(data: data, fullTime: false)
    }
}

public extension UiDateTime {
    var displayDate: Date? {
        let instant = value
        let date = Date(
            timeIntervalSince1970: Double(instant.epochSeconds) + Double(instant.nanosecondsOfSecond) / 1_000_000_000
        )
        return (Date.distantPast...Date.distantFuture).contains(date) ? date : nil
    }
}
