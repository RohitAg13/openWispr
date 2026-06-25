import AppKit
import SwiftUI

/// The main window's root: a brand-styled sidebar (Home / Settings) hosting the Home
/// screen and the existing Settings form. An LSUIElement app has no Dock or app menu, so
/// this window — opened at launch and from the menu bar — is the app's primary surface.
struct RootView: View {
    /// Sidebar sections.
    enum Section: Hashable, CaseIterable {
        case home, settings

        var title: String {
            switch self {
            case .home: return "Home"
            case .settings: return "Settings"
            }
        }
        var icon: String {
            switch self {
            case .home: return "house.fill"
            case .settings: return "gearshape.fill"
            }
        }
    }

    /// One Home dictation controller for the lifetime of the window. Separate from the
    /// menu-bar popover's controller and the hands-free coordinator (each owns its own).
    @StateObject private var controller = DictationController()
    @State private var section: Section = .home

    var body: some View {
        NavigationSplitView {
            sidebar
                .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 260)
        } detail: {
            detail
                .navigationSplitViewColumnWidth(min: 560, ideal: 680)
        }
        .navigationSplitViewStyle(.balanced)
        .frame(minWidth: 820, minHeight: 560)
        .background(OW.bg)
    }

    // MARK: - Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                OWOrb(size: 30, breathing: false)
                Text("OpenWispr")
                    .font(OW.ui(17, weight: .semibold))
                    .foregroundStyle(OW.text)
            }
            .padding(.horizontal, 14)
            .padding(.top, 18)
            .padding(.bottom, 18)

            VStack(spacing: 4) {
                ForEach(Section.allCases, id: \.self) { item in
                    sidebarRow(item)
                }
            }
            .padding(.horizontal, 10)

            Spacer()

            // Live status footer + Quit.
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 7) {
                    Circle().fill(statusDotColor).frame(width: 7, height: 7)
                    MonoLabel(text: statusText, color: OW.textDim, size: 10, tracking: 1.0)
                }
                Button("Quit OpenWispr") { NSApp.terminate(nil) }
                    .buttonStyle(OWGhostButtonStyle())
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(OW.bgSunk)
    }

    private func sidebarRow(_ item: Section) -> some View {
        let isSelected = section == item
        return Button {
            section = item
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.icon)
                    .font(.system(size: 13))
                    .frame(width: 18)
                    .foregroundStyle(isSelected ? OW.coralDeep : OW.textDim)
                Text(item.title)
                    .font(OW.ui(14, weight: isSelected ? .semibold : .medium))
                    .foregroundStyle(isSelected ? OW.text : OW.textDim)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(
                isSelected ? AnyShapeStyle(OW.coralPill.opacity(0.6)) : AnyShapeStyle(Color.clear),
                in: RoundedRectangle(cornerRadius: OW.rChip)
            )
            .contentShape(RoundedRectangle(cornerRadius: OW.rChip))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Detail

    @ViewBuilder
    private var detail: some View {
        switch section {
        case .home:
            HomeView(controller: controller)
        case .settings:
            SettingsView(settings: .shared)
                .background(OW.bg)
        }
    }

    // MARK: - Status

    private var statusText: String {
        switch controller.phase {
        case .idle:         return controller.canInsert ? "Ready" : "Setup"
        case .listening:    return "Listening"
        case .transcribing: return "Working"
        case .done:         return "Done"
        case .error:        return "Error"
        }
    }

    private var statusDotColor: Color {
        switch controller.phase {
        case .listening:    return OW.coral
        case .error:        return OW.danger
        case .done:         return OW.success
        default:            return controller.canInsert ? OW.coral : OW.textMuted
        }
    }
}
