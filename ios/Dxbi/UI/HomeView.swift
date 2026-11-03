import SwiftUI

/**
 * Home, the Apple TV way: a tall billboard to swipe through (logo, what it is, Play and +), then
 * Continue Watching, dx.bi's own rows and the addons' catalogs in the viewer's order.
 */
struct HomeView: View {
    @Environment(Stremio.self) private var stremio
    @State private var account = false
    @State private var party = false
    @State private var kind: String?

    var body: some View {
        GeometryReader { g in
            let width = g.size.width
            let wide = width > g.size.height
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 30) {
                    if !stremio.billboard.isEmpty {
                        Billboard(titles: stremio.billboard, height: wide ? g.size.height * 0.92 : min(g.size.height * 0.74, width * 1.55))
                    } else {
                        Color.clear.frame(height: 90)
                    }
                    kinds
                    if !stremio.continueWatching.isEmpty && kind == nil {
                        Shelf(title: "Continue Watching") {
                            ForEach(stremio.continueWatching) { item in
                                ContinueTile(item: item, width: min(max(width * 0.68, 260), 360))
                            }
                        }
                    }
                    ForEach(Array(stremio.myList.enumerated()), id: \.offset) { _, row in
                        if kind == nil, !row.items.isEmpty { titleRow(row.name, row.items, see: .library) }
                    }
                    if kind == nil, let forYou = stremio.forYou { titleRow(forYou.name, forYou.items) }
                    if kind == nil, !stremio.airing.isEmpty {
                        Shelf(title: "New Episodes This Week") {
                            ForEach(stremio.airing) { item in
                                NavigationLink(value: Route.title(TitleRef(type: "series", id: item.id, name: item.name))) {
                                    AiringCard(item: item, width: min(max(width * 0.7, 260), 380))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    let rows = stremio.rows.filter { kind == nil || $0.type == kind }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        if row.state == .ready && !row.items.isEmpty {
                            // Netflix's Top 10: the first row is numbered
                            titleRow(row.heading, row.items, rank: i == 0, see: row.discover.map { .browse(link: $0, name: row.heading) })
                        } else if row.state == .loading {
                            loadingRow(row.heading)
                        }
                    }
                    if stremio.rows.isEmpty && stremio.ready {
                        ContentUnavailableView("Nothing to show yet", systemImage: "puzzlepiece.extension", description: Text("Install addons to fill Home: tap your picture, then Addons."))
                    }
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
            // the billboard shows through the top bar, as on Apple TV
            .scrollEdgeEffectHidden(true, for: .top)
            .ignoresSafeArea(edges: .top)
        }
        .screenBackground()
        .navigationTitle("Home")
        .toolbarTitleDisplayMode(.inlineLarge)
        .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { party = true } label: { Image(systemName: "person.2.fill") }
                    .accessibilityLabel("Watch party")
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            ToolbarItem(placement: .topBarTrailing) {
                Button { account = true } label: { Avatar(email: stremio.account?.email) }
                    .accessibilityLabel("Profile and settings")
            }
        }
        .sheet(isPresented: $account) { AccountView() }
        .sheet(isPresented: $party) { PartyView() }
        .refreshable { stremio.refreshExtras() }
    }

    /** Netflix's Movies · Series chips */
    private var kinds: some View {
        let types = ["movie", "series", "anime"].filter { t in stremio.rows.contains { $0.type == t } }
        return ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(types.prefix(4), id: \.self) { t in
                    let on = kind == t
                    Button {
                        withAnimation(.snappy) { kind = on ? nil : t }
                    } label: {
                        HStack(spacing: 6) {
                            Text(t == "movie" ? "Movies" : t == "series" ? "TV Shows" : typeName(t))
                            if on { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 6)
                    }
                    .buttonStyle(.glass)
                    .tint(on ? .white : nil)
                }
            }
            .padding(.horizontal, Dx.gutter)
        }
        .scrollIndicators(.hidden)
        .opacity(types.count > 1 ? 1 : 0)
        .frame(height: types.count > 1 ? nil : 0)
    }

    private func titleRow(_ name: String, _ items: [Title], rank: Bool = false, see: Route? = nil) -> some View {
        Shelf(title: name, see: see) {
            ForEach(Array(items.prefix(rank ? 10 : 30).enumerated()), id: \.element.id) { i, title in
                NavigationLink(value: Route.title(title.ref)) {
                    PosterCard(title: title, width: 150, rank: rank ? i + 1 : nil)
                        .zoomSource(title.id)
                }
                .buttonStyle(.plain)
                .contextMenu { TitleMenu(title: title) }
            }
        }
    }

    private func loadingRow(_ name: String) -> some View {
        Shelf(title: name) {
            ForEach(0..<4, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 18).fill(Dx.raised).frame(width: 150, height: 225)
            }
        }
        .redacted(reason: .placeholder)
    }
}

/** A titled row of cards that scrolls sideways, snapping to each */
struct Shelf<Content: View>: View {
    var title: String
    var see: Route?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let see {
                NavigationLink(value: see) {
                    HStack(spacing: 4) {
                        Text(title).font(.title2.bold()).lineLimit(1)
                        Image(systemName: "chevron.right").font(.title3.weight(.bold)).foregroundStyle(Dx.muted)
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, Dx.gutter)
            } else {
                SectionHeader(title: title)
            }
            ScrollView(.horizontal) {
                LazyHStack(spacing: 14) { content }
                    .scrollTargetLayout()
                    .padding(.horizontal, Dx.gutter)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
        }
    }
}

/** Long-press on a title */
struct TitleMenu: View {
    @Environment(Stremio.self) private var stremio
    var title: Title
    var body: some View {
        NavigationLink(value: Route.title(title.ref)) { Label("Details", systemImage: "info.circle") }
        NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, play: true))) { Label("Play", systemImage: "play.fill") }
    }
}

/** Continue Watching: plays where they left off; ⋯ for the rest */
private struct ContinueTile: View {
    @Environment(Stremio.self) private var stremio
    var item: Continue
    var width: CGFloat

    var body: some View {
        NavigationLink(value: Route.title(TitleRef(type: item.title.type, id: item.title.id, name: item.title.name, play: true))) {
            ContinueCard(item: item, width: width)
                .zoomSource(item.title.id)
        }
        .buttonStyle(.plain)
        .contextMenu {
            NavigationLink(value: Route.title(item.title.ref)) { Label("Details", systemImage: "info.circle") }
            Button(role: .destructive) { stremio.removeFromContinue(item.title.id) } label: { Label("Remove from Continue Watching", systemImage: "eye.slash") }
        }
    }
}

/** The tall billboard at the top of Home */
private struct Billboard: View {
    @Environment(Stremio.self) private var stremio
    var titles: [Title]
    var height: CGFloat
    @State private var page: Title.ID?

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(titles) { title in
                    slide(title).containerRelativeFrame(.horizontal).id(title.id)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollIndicators(.hidden)
        .scrollPosition(id: $page)
        .frame(height: height)
        .overlay(alignment: .bottom) { dots.padding(.bottom, 4) }
        .task(id: titles.map(\.id)) {
            // turns by itself, like Apple TV's
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(9))
                guard titles.count > 1 else { continue }
                let at = titles.firstIndex { $0.id == page } ?? 0
                withAnimation(.smooth(duration: 0.6)) { page = titles[(at + 1) % titles.count].id }
            }
        }
    }

    private func slide(_ title: Title) -> some View {
        ZStack(alignment: .bottom) {
            // (the picture fills its own slide and no more: it spilled onto the next while swiping)
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .overlay { Art(url: title.backdrop) }
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .clear, location: 0.25), .init(color: .clear, location: 0.45), .init(color: .black, location: 0.92)], startPoint: .top, endPoint: .bottom))
            VStack(spacing: 12) {
                if let year = title.year { Badge(text: year) }
                TitleLogo(title: title, maxHeight: 110, textSize: 40)
                    .frame(maxWidth: 320)
                Text(([typeName(title.type)] + title.genres.prefix(2)).joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(Dx.muted)
                if let d = title.description {
                    Text(d).font(.footnote).foregroundStyle(Dx.faint).lineLimit(2).multilineTextAlignment(.center).frame(maxWidth: 360)
                }
                HStack(spacing: 14) {
                    NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, play: true))) {
                        Label("Play", systemImage: "play.fill").font(.headline).frame(width: 150, height: 50)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.white)
                    .foregroundStyle(.black)
                    NavigationLink(value: Route.title(title.ref)) {
                        Image(systemName: title.inLibrary ? "checkmark" : "plus").font(.title3.weight(.semibold)).frame(width: 36, height: 50)
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel("Details")
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 34)
        }
        .contentShape(.rect)
    }

    private var dots: some View {
        let at = titles.firstIndex { $0.id == page } ?? 0
        return HStack(spacing: 8) {
            ForEach(titles.indices, id: \.self) { i in
                Capsule().fill(i == at ? .white : .white.opacity(0.4)).frame(width: i == at ? 22 : 7, height: 7)
            }
        }
        .animation(.snappy, value: at)
    }
}

/** The signed-in person: their Stremio picture, or their initial */
struct Avatar: View {
    var email: String?
    var body: some View {
        Text(String((email ?? "?").prefix(1)).uppercased())
            .font(.headline)
            .frame(width: 30, height: 30)
            .background(LinearGradient(colors: [Color(red: 0.35, green: 0.6, blue: 0.75), Color(red: 0.2, green: 0.4, blue: 0.55)], startPoint: .top, endPoint: .bottom), in: .circle)
    }
}
