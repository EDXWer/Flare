@testable import FlareAppleCore
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest

final class LivePhotoExportTests: XCTestCase {
    func testCoverKeepsResolutionAndReceivesMovieIdentifier() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let original = directory.appendingPathComponent("original.jpg")
        let paired = directory.appendingPathComponent("paired.jpg")
        let context = try XCTUnwrap(CGContext(data: nil, width: 113, height: 257, bitsPerComponent: 8,
                                              bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(context.makeImage())
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(original as CFURL, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))

        try LivePhotoExport.writeCover(from: original, identifier: "7C8F98AC-B51B-4227-BF6B-5533481CD5BC", to: paired)

        let source = try XCTUnwrap(CGImageSourceCreateWithURL(paired as CFURL, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any])
        let maker = properties[kCGImagePropertyMakerAppleDictionary as String] as? [String: Any]
        XCTAssertEqual(maker?["17"] as? String, "7C8F98AC-B51B-4227-BF6B-5533481CD5BC")
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth as String] as? Int, 113)
        XCTAssertEqual(properties[kCGImagePropertyPixelHeight as String] as? Int, 257)
    }
}
