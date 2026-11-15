import Accessibility
import CoreTransferable
import FlareAppleCore
import FlareAppleUI
import ImageIO
@preconcurrency import KotlinSharedUI
import PhotosUI
import SwiftUI
import SwiftUIBackports
import UniformTypeIdentifiers

struct DMListScreen: View {
    let accountType: AccountType
    @State private var presenter: KotlinPresenter<DMListState>
    var body: some View {
        List {
            PagingView(data: presenter.state.items) { item in
                NavigationLink(value: Route.dmConversation(accountType, item.key, item.users.count == 1 ? item.users.first?.name.innerText ?? String(localized: "direct_messages_title") : String(localized: "direct_messages_title"))) {
                    HStack {
                        if item.hasUser, let image = item.users.first?.avatar {
                            AvatarView(data: image)
                                .frame(width: 48, height: 48)
                        } else {
                            Image(fontAwesome: .list)
                        }
                        VStack(
                            alignment: .leading,
                            spacing: 8
                        ) {
                            HStack {
                                if item.hasUser {
                                    ForEach(item.users, id: \.key) { user in
                                        RichText(text: user.name)
                                            .lineLimit(1)
                                        if (item.users.count == 1) {
                                            Text(user.handle.display)
                                                .lineLimit(1)
                                                .font(.caption)
                                                .flareSecondaryText()
                                        }
                                    }
                                }
                                Spacer()
                                if let lasMessage = item.lastMessage {
                                    DateTimeText(data: lasMessage.timestamp)
                                        .lineLimit(1)
                                        .font(.caption)
                                        .flareSecondaryText()
                                }
                            }
                            Text(item.lastMessageText)
                                .lineLimit(2)
                                .font(.caption)
                                .flareSecondaryText()
                        }
                        if item.unreadCount > 0 {
                            Spacer()
                            Text("\(item.unreadCount)")
                                .lineLimit(1)
                                .font(.caption)
                                .padding(6)
                                .background(
                                    Circle()
                                        .fill(Color.accentColor)
                                )
                                .foregroundStyle(.white)
                        }
                    }
                }
            } loadingContent: {
                UiListPlaceholder()
            }
        }
        .refreshable {
            try? await presenter.state.refreshSuspend()
        }
        .navigationTitle("direct_messages_title")
    }
}

extension DMListScreen {
    init(accountType: AccountType) {
        self.accountType = accountType
        self._presenter = .init(wrappedValue: .init(presenter: DMListPresenter(accountType: accountType)))
    }
}

struct UserDMConversationScreen: View {
    @State private var presenter: KotlinPresenter<UserDMConversationPresenterState>
    private let accountType: AccountType
    
    init(accountType: AccountType, userKey: MicroBlogKey) {
        self.accountType = accountType
        self._presenter = .init(wrappedValue: .init(presenter: UserDMConversationPresenter(accountType: accountType, userKey: userKey)))
    }
    
    var body: some View {
        StateView(state: presenter.state.roomKey) { roomKey in
            DMConversationScreen(accountType: accountType, roomKey: roomKey)
                .id(roomKey)
        } errorContent: { error in
            ListErrorView(error: error) {
                
            }
        } loadingContent: {
            ProgressView()
        }
    }
}

struct DMConversationScreen: View {
    @State private var inputText: String = ""
    @State private var pickedImage: PhotosPickerItem?
    @State private var imageImportFailed = false
    @Environment(\.openURL) private var openURL
    @State private var presenter: KotlinPresenter<DMConversationState>
    var body: some View {
        DMConversationMessagesView(
            data: presenter.state.items,
            onRetry: { key in
                presenter.state.retry(key: key)
            },
            onOpenURL: { url in
                openURL(url)
            }
        )
        .background(Color(.systemGroupedBackground))
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: 8) {
                if presenter.state.canSendImage {
                    imageAttachment
                }
                HStack {
                    if presenter.state.canSendImage {
                        PhotosPicker(selection: $pickedImage, matching: .images, preferredItemEncoding: .current) {
                            Image(systemName: "photo")
                        }
                        .backport
                        .glassButtonStyle()
                        .disabled(attachmentBusy)
                        .accessibilityLabel(Text("dm_add_image"))
                    }
                    TextField("dm_conversation_input_placeholder", text: $inputText)
                        .padding()
                        .backport
                        .glassEffect(.regularInteractive, in: .capsule, fallbackBackground: .regularMaterial)
                    Button(action: send) {
                        Image(systemName: "paperplane.fill")
                            .font(.title2)
                    }
                    .backport
                    .glassProminentButtonStyle()
                    .disabled(attachmentBusy || (presenter.state.selectedImage == nil && inputText.isEmpty))
                    .accessibilityLabel(Text("agent_chat_send"))
                }
                .backport
                .glassEffectContainer()
            }
            .padding([.horizontal, .bottom])
        }
        .task(id: pickedImage) {
            await loadPickedImage()
        }
        .onDisappear {
            pickedImage = nil
        }
        .onChange(of: attachmentStatus) { _, status in
            if let status {
                AccessibilityNotification.Announcement(status).post()
            }
        }
    }

    @ViewBuilder
    private var imageAttachment: some View {
        if let file = presenter.state.selectedImage {
            HStack {
                Label(file.name ?? String(localized: "dm_image_attached"), systemImage: "photo")
                    .lineLimit(1)
                Button {
                    presenter.state.selectImage(file: nil)
                    imageImportFailed = false
                } label: {
                    Image(systemName: "xmark.circle.fill")
                }
                .disabled(attachmentBusy)
                .accessibilityLabel(Text("dm_remove_image"))
            }
            .font(.subheadline)
        }
        if let attachmentStatus {
            if attachmentBusy {
                ProgressView(attachmentStatus)
                    .controlSize(.small)
            } else {
                Text(attachmentStatus)
                    .font(.caption)
                    .foregroundStyle(Color.red)
            }
        }
    }

    private var attachmentBusy: Bool {
        pickedImage != nil || presenter.state.isSendingImage
    }

    private var attachmentStatus: String? {
        guard presenter.state.canSendImage else { return nil }
        if presenter.state.isSendingImage { return String(localized: "dm_sending") }
        if pickedImage != nil { return String(localized: "#loading") }
        if imageImportFailed { return String(localized: "import_error") }
        if presenter.state.imageSendFailed { return String(localized: "dm_image_send_failed") }
        return nil
    }

    private func loadPickedImage() async {
        guard let item = pickedImage else { return }
        let image = try? await item.loadTransferable(type: PickedDMImage.self)
        guard !Task.isCancelled, pickedImage == item else { return }
        pickedImage = nil
        imageImportFailed = image == nil
        if let image, presenter.state.canSendImage {
            presenter.state.selectImage(file: FileItem(
                name: image.file.url.lastPathComponent,
                pathProvider: { @Sendable [file = image.file] in file.url.path },
                type: .image,
                mimeType: image.mimeType
            ))
        }
    }

    private func send() {
        guard !attachmentBusy else { return }
        if presenter.state.selectedImage != nil {
            imageImportFailed = false
            presenter.state.sendImage()
        } else if !inputText.isEmpty {
            presenter.state.send(message: inputText)
            inputText = ""
        }
    }
}

extension DMConversationScreen {
    init(accountType: AccountType, roomKey: MicroBlogKey) {
        self._presenter = .init(wrappedValue: .init(presenter: DMConversationPresenter(accountType: accountType, roomKey: roomKey)))
    }
}

private nonisolated struct PickedDMImage: Transferable {
    let file: ComposeMediaFile
    let mimeType: String?

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            let file = try ComposeMediaFile(copying: received.file)
            guard let source = CGImageSourceCreateWithURL(file.url as CFURL, nil),
                  let identifier = CGImageSourceGetType(source) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            return PickedDMImage(
                file: file,
                mimeType: UTType(identifier as String)?.preferredMIMEType
            )
        }
    }
}
