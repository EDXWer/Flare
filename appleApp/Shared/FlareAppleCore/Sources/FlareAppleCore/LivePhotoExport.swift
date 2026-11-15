import AVFoundation
import Foundation
import ImageIO
import Photos
import UniformTypeIdentifiers

public enum LivePhotoExport {
    // False means the source cannot be paired. Errors from Photos itself are propagated.
    @concurrent public static func saveIfSupported(cover: URL, movie: URL) async throws -> Bool {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let pairedMovie = directory.appendingPathComponent("LivePhoto.mov")
        let pairedCover = directory.appendingPathComponent("LivePhoto.jpg")
        try FileManager.default.copyItem(at: movie, to: pairedMovie)
        do {
            try LivePhotoPlaybackFile.normalize(pairedMovie)
            let asset = AVURLAsset(url: pairedMovie)
            let metadata = try await asset.loadMetadata(for: .quickTimeMetadata)
            // shortcut: reuse the original pair and still-frame time. Author new metadata if unpaired clips need Live Photo export.
            guard let item = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .quickTimeMetadataContentIdentifier).first,
                  let identifier = try await item.load(.stringValue), !identifier.isEmpty,
                  try await !asset.loadTracks(withMediaType: .metadata).isEmpty else {
                return false
            }
            try writeCover(from: cover, identifier: identifier, to: pairedCover)
        } catch let error as CocoaError where error.code == .fileReadCorruptFile {
            return false
        } catch let error as URLError where error.code == .cannotDecodeContentData || error.code == .dataLengthExceedsMaximum {
            return false
        }
        try Task.checkCancellation()

        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: pairedCover, options: nil)
            request.addResource(with: .pairedVideo, fileURL: pairedMovie, options: nil)
        }
        return true
    }

    nonisolated static func writeCover(from cover: URL, identifier: String, to output: URL) throws {
        guard let source = CGImageSourceCreateWithURL(cover as CFURL, nil),
              let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let key = kCGImagePropertyMakerAppleDictionary as String
        let sourceProperties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
        var maker = sourceProperties?[key] as? [String: Any] ?? [:]
        maker["17"] = identifier
        CGImageDestinationAddImageFromSource(destination, source, 0, [key: maker] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    }
}
