import FlareAppleCore
import Foundation
import KotlinSharedUI
import SwiftUI

struct AiConfigHeadersSetting: View {
    let headers: [AiRequestHeader]
    let onSave: ([AiRequestHeader]) -> Void
    @State private var editing = false

    var body: some View {
        AiConfigEditButton(title: "settings_ai_config_custom_headers", value: headers.map(\.name).joined(separator: ", ")) {
            editing = true
        }
        .sheet(isPresented: $editing) {
            AiConfigHeadersEditor(headers: headers, onSave: onSave)
            #if os(macOS)
                .frame(minWidth: 420, minHeight: 400)
            #endif
        }
    }
}

private struct AiConfigHeadersEditor: View {
    private struct Draft: Identifiable {
        let id = UUID()
        var name = ""
        var value = ""
    }

    let onSave: ([AiRequestHeader]) -> Void
    @State private var drafts: [Draft]
    @Environment(\.dismiss) private var dismiss

    init(headers: [AiRequestHeader], onSave: @escaping ([AiRequestHeader]) -> Void) {
        self.onSave = onSave
        _drafts = State(initialValue: headers.map { Draft(name: $0.name, value: $0.value) })
    }

    var body: some View {
        let headers = drafts.map { AiRequestHeader(name: $0.name, value: $0.value) }
        let valid = AiRequestHeaderKt.areValidAiRequestHeaders(headers: headers)
        NavigationStack {
            Form {
                ForEach($drafts) { $draft in
                    Section {
                        TextField(FlareAppleUILocalization.string("settings_ai_config_header_name"), text: $draft.name)
                        TextField(FlareAppleUILocalization.string("settings_ai_config_header_value"), text: $draft.value)
                        Button(role: .destructive) {
                            let id = draft.id
                            drafts.removeAll { $0.id == id }
                        } label: {
                            Text("delete", bundle: FlareAppleUILocalization.bundle)
                        }
                    }
                }
                Section {
                    Button {
                        drafts.append(Draft())
                    } label: {
                        Text("settings_ai_config_header_add", bundle: FlareAppleUILocalization.bundle)
                    }
                } footer: {
                    if !valid {
                        Text("settings_ai_config_headers_invalid", bundle: FlareAppleUILocalization.bundle)
                    }
                }
            }
            .formStyle(.grouped)
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
            .autocorrectionDisabled()
            .navigationTitle(Text("settings_ai_config_custom_headers", bundle: FlareAppleUILocalization.bundle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        #if os(iOS)
                        Image(fontAwesome: .xmark)
                        #else
                        Text("Cancel", bundle: FlareAppleUILocalization.bundle)
                        #endif
                    }
                    .accessibilityLabel(Text("Cancel", bundle: FlareAppleUILocalization.bundle))
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        onSave(headers)
                        dismiss()
                    } label: {
                        #if os(iOS)
                        Image(fontAwesome: .check)
                        #else
                        Text("Save", bundle: FlareAppleUILocalization.bundle)
                        #endif
                    }
                    #if os(iOS)
                    .accessibilityLabel(Text("done", bundle: FlareAppleUILocalization.bundle))
                    #endif
                    .disabled(!valid)
                }
            }
        }
    }
}
