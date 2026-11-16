import CoreGraphics

/// Render changes require a new measurement, but do not discard known geometry.
struct TimelineItemHeightCache {
    struct Geometry: Equatable {
        let widthInPixels: Int
        let multipleColumns: Bool
        var singleMediaMaxHeightInPixels: Int = 0
    }

    private struct Measurement {
        let geometry: Geometry
        let renderHash: Int32
        let height: CGFloat
        let isReusable: Bool
    }

    private var items: [String: [Measurement]] = [:]
    private var pending: [String: [Measurement]] = [:]

    var hasPendingMeasurements: Bool { !pending.isEmpty }
    var pendingItemIDs: [String] { Array(pending.keys) }

    func height(for id: String, geometry: Geometry, renderHash: Int32? = nil) -> CGFloat? {
        items[id]?.first {
            $0.geometry == geometry && (renderHash == nil || $0.renderHash == renderHash)
        }?.height
    }

    /// Cells may reuse their latest measurement before the layout adopts it.
    /// Layout sizing must keep reading `height` until the preserving update.
    func measuredHeight(for id: String, geometry: Geometry, renderHash: Int32, reusableOnly: Bool = false) -> CGFloat? {
        let measurement = pending[id]?.first { $0.geometry == geometry && $0.renderHash == renderHash }
            ?? items[id]?.first { $0.geometry == geometry && $0.renderHash == renderHash }
        guard let measurement, !reusableOnly || measurement.isReusable else { return nil }
        return measurement.height
    }

    mutating func stage(_ height: CGFloat, for id: String, geometry: Geometry, renderHash: Int32, isReusable: Bool = true) {
        let previous = self.height(for: id, geometry: geometry)
        let measurement = Measurement(geometry: geometry, renderHash: renderHash, height: height, isReusable: isReusable)
        var measurements = pending[id] ?? []
        measurements.removeAll { $0.geometry == geometry }
        if let previous, abs(previous - height) <= 1 {
            // Updating render validity cannot move the layout. Publish it without
            // queuing another layout, including when a pending change is reverted.
            _ = store(measurement, for: id)
        } else {
            measurements.insert(measurement, at: 0)
        }
        pending[id] = measurements.isEmpty ? nil : Array(measurements.prefix(2))
    }

    /// Invoke only after capturing the old layout's reading anchor. Measurements
    /// produced by the ensuing layout remain pending for the next coalesced batch.
    mutating func commitPendingMeasurements(validating isValid: (String, Geometry, Int32) -> Bool) -> Bool {
        let batch = pending
        pending = [:]
        var changed = false
        for (id, measurements) in batch {
            // Store oldest first so the newest geometry remains the first entry.
            for measurement in measurements.reversed() where isValid(id, measurement.geometry, measurement.renderHash) {
                let updated = store(measurement, for: id)
                changed = updated || changed
            }
        }
        return changed
    }

    /// Returns whether the layout needs an update, independently of render state.
    private mutating func store(_ measurement: Measurement, for id: String) -> Bool {
        let geometry = measurement.geometry
        let previous = self.height(for: id, geometry: geometry)
        // Keep the geometry actually supplied to the layout when ignoring rounding
        // noise. Otherwise repeated sub-threshold updates could accumulate drift.
        let height = measurement.height
        let layoutHeight = previous.map { abs($0 - height) <= 1 ? $0 : height } ?? height
        var measurements = items[id] ?? []
        measurements.removeAll { $0.geometry == geometry }
        measurements.insert(Measurement(geometry: geometry, renderHash: measurement.renderHash,
                                        height: layoutHeight, isReusable: measurement.isReusable), at: 0)
        // Interactive window resizing and repeated likes must not grow the cache.
        items[id] = Array(measurements.prefix(2))
        return previous != layoutHeight
    }

    mutating func keep(_ ids: Set<String>, retainingReusableHeights: Bool) {
        if retainingReusableHeights {
            items = items.reduce(into: [:]) { kept, entry in
                let measurements = ids.contains(entry.key) ? entry.value : entry.value.filter(\.isReusable)
                if !measurements.isEmpty {
                    kept[entry.key] = measurements
                }
            }
        } else {
            items = items.filter { ids.contains($0.key) }
        }
        pending = pending.filter { ids.contains($0.key) }
    }

    mutating func discardPendingMeasurements() {
        pending.removeAll(keepingCapacity: true)
    }

    mutating func removeAll() {
        items.removeAll(keepingCapacity: true)
        discardPendingMeasurements()
    }
}
