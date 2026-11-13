import SwiftUI
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI
import AVFAudio
import Combine
import UIKit

@main
struct FlareApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        MediaCacheMaintenance.configure()
        configureAudioSessionForMixing()
        let firebaseEnabled = FirebaseBootstrap.configureIfAvailable()
        if firebaseEnabled {
            AppleSharedHelper.shared.setupCrashlytics()
        }
        ProxySessionConfigurator.install()
        AppleSharedHelper.shared.initialize(
            inAppNotification: SwiftInAppNotification.shared,
            swiftFormatter: Formatter.shared,
            swiftPlatformTextRenderer: PlatformTextRenderer.shared,
            swiftOnDeviceAI: FoundationModelOnDeviceAI.shared
        )
    }
    var body: some Scene {
        WindowGroup {
            FlareTheme {
                root
            }
            .modifier(IOSTimelineListEnvironment())
        .ugoiraExportStatus()
            .onChange(of: scenePhase, initial: true) { _, phase in
                VideoPlaybackDiagnostics.shared.record(message: "scene phase=\(phase) protectedData=\(UIApplication.shared.isProtectedDataAvailable)")
                MediaCacheMaintenance.handleScenePhase(phase)
                PluginRuntimeLifecycleFacadeV1().setForeground(active: phase == .active)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                MediaProxy.shared.setRelayForeground(active: false)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                MediaProxy.shared.setRelayForeground(active: true)
            }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                let type = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.intValue ?? -1
                VideoPlaybackDiagnostics.shared.record(message: "audio interruption type=\(type)")
            }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.mediaServicesWereResetNotification)) { _ in
                VideoPlaybackDiagnostics.shared.record(message: "audio media services reset")
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                Task.detached {
                    try? await PluginRuntimeLifecycleFacadeV1().onMemoryPressure()
                }
            }
        }
    }

    @ViewBuilder
    private var root: some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--media-viewer-test") {
            MediaViewerTestFixture()
        } else if ProcessInfo.processInfo.arguments.contains("--timeline-media-scroll-test") {
            TimelineMediaScrollTestFixture()
        } else {
            appRoot
        }
        #else
        appRoot
        #endif
    }

    @ViewBuilder
    private var appRoot: some View {
        if #available(iOS 18.0, *) {
            FlareRoot()
        } else {
            BackportFlareRoot()
        }
    }
    
    func configureAudioSessionForMixing() {
        AudioSessionManager.shared.activateAmbient()
    }
}
