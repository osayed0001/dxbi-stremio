import SwiftUI

/**
 * A film or show, the Apple TV way: its art at the top in the art's own colour, the logo, Play (one
 * tap: dx.bi picks the stream), then the seasons and episodes. Sideways, the details sit on the left
 * and the episodes on the right.
 */
struct TitleView: View {
    @Environment(Stremio.self) private var stremio
    @Environment(\.dismiss) private var dismiss
    var ref: TitleRef

    @State private var choices: StreamChoices?
    /** Play was pressed for this video: dx.bi's pick plays as soon as it's sure */
    @State private var waitingFor: String?
    @State private var streamsOpen = false
    @State private var season: Int?
    @State private var tint = Color(white: 0.16)
    @State private var reviews: Reviews?
    @State private var reviewsOpen = false
    @State private var moreText = false
    @State private var autoPlayed = false

    private var details: Details? { stremio.details?.metaId == ref.id ? stremio.details : nil }
    private var title: Title { details?.title ?? Title(id: ref.id, type: ref.type, name: ref.name) }
    private var current: StreamChoices? {
        guard let c = choices, let v = details?.videoId, c.videoId == v else { return nil }
        return c
    }

    var body: some View {
        GeometryReader { g in
            let wide = g.size.width > g.size.height && g.size.width > 600
            Group {
                if wide {
                    HStack(spacing: 0) {
                        ScrollView { header(width: g.size.width * 0.45, wide: true) }
                            .frame(width: g.size.width * 0.45)
                        Divider().overlay(.white.opacity(0.08))
                        ScrollView {
                            VStack(alignment: .leading, spacing: 24) {
                                if ref.type == "series" { episodes(width: g.size.width * 0.55, vertical: true) }
                                extras
                            }
                            .padding(.vertical, 24)
                        }
                        .background(.black)
                    }
                    .ignoresSafeArea(edges: .top)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            header(width: g.size.width, wide: false)
                            VStack(alignment: .leading, spacing: 30) {
                                if ref.type == "series" { episodes(width: g.size.width, vertical: false) }
                                extras
                            }
                            .padding(.vertical, 26)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.black)
                        }
                    }
                    .ignoresSafeArea(edges: .top)
                }
            }
            .scrollIndicators(.hidden)
        }
        .background(tint.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let link = shareLink { ShareLink(item: link) { Image(systemName: "square.and.arrow.up") } }
            }
        }
        .sheet(isPresented: $streamsOpen) {
            StreamsSheet(choices: current, heading: title.name, subheading: episodeLine) { option, target in
                play(option, in: target)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $reviewsOpen) { ReviewsView(title: title, reviews: reviews) }
        .task(id: ref.id) {
            if stremio.details?.metaId != ref.id { stremio.open(ref.type, ref.id) }
            reviews = await stremio.reviews(ref.type, ref.id)
        }
        .task(id: title.background ?? title.poster) {
            if let c = await ArtColour.of(title.backdrop) { withAnimation(.easeOut(duration: 0.4)) { tint = c } }
        }
        // a series: the episode they're on, ready before Play is pressed
        .onChange(of: details?.state == .ready, initial: true) {
            guard let d = details, d.state == .ready, ref.type == "series" else { return }
            if season == nil { season = nextVideo(d)?.season ?? d.seasons.first }
            if d.videoId == nil, let v = nextVideo(d) { stremio.open(ref.type, ref.id, videoId: v.id) }
            if ref.play && !autoPlayed { autoPlayed = true; start(nextVideo(d)?.id) }
        }
        .onChange(of: details?.state == .ready && ref.type != "series", initial: true) { _, ready in
            if ready && ref.play && !autoPlayed { autoPlayed = true; start(nil) }
        }
        // dx.bi's reading of the streams, again as more addons answer
        .task(id: details?.streamsKey) {
            guard details?.videoId != nil else { return }
            choices = await stremio.streams()
            justPlayIfSure()
        }
        .task(id: waitingFor) {
            guard waitingFor != nil else { return }
            try? await Task.sleep(for: .seconds(6))
            if let c = current, c.justPlay, waitingFor == c.videoId, let pick = c.picked { play(pick) }
        }
    }

    // MARK: - Playing

    /** What Play starts: the episode they're in the middle of, else the first one that's out */
    private func nextVideo(_ d: Details) -> Video? {
        guard ref.type == "series" else { return nil }
        return d.videos.first { $0.id == d.resumeVideoId } ?? d.videos.first { ($0.season ?? 0) > 0 && !$0.upcoming } ?? d.videos.first
    }

    private func start(_ videoId: String?) {
        let target = videoId ?? details?.videoId ?? ref.id
        if ref.type == "series", let videoId, videoId != details?.videoId {
            choices = nil
            stremio.open(ref.type, ref.id, videoId: videoId)
        }
        waitingFor = target
        streamsOpen = true
        justPlayIfSure()
    }

    /** Just press Play: the stream chosen last time plays at once, anything else once every addon has answered */
    private func justPlayIfSure() {
        guard let c = current, c.justPlay, waitingFor == c.videoId, let pick = c.picked else { return }
        if c.sameAsLastTime || c.settled { play(pick) }
    }

    private func play(_ option: StreamOption, in target: Stremio.Target = .player) {
        waitingFor = nil
        streamsOpen = false
        stremio.play(option, in: target)
    }

    private var playLabel: String {
        guard let d = details else { return "Play" }
        if ref.type == "series", let v = nextVideo(d), let s = v.season, let e = v.episode {
            let resuming = v.id == d.resumeVideoId && d.resumeMs > 0
            return "\(resuming ? "Resume" : "Play") S\(s), E\(e)"
        }
        return d.resumeMs > 0 && d.resumeVideoId == ref.id ? "Resume" : "Play"
    }

    private var episodeLine: String? {
        guard let d = details, let v = d.videos.first(where: { $0.id == d.videoId }), let s = v.season, let e = v.episode else { return nil }
        return "S\(s), E\(e) · \(v.title)"
    }

    private var shareLink: URL? {
        if let imdb = title.imdb ?? (ref.id.hasPrefix("tt") ? ref.id : nil) { return URL(string: "https://www.imdb.com/title/\(imdb)/") }
        return nil
    }

    // MARK: - The top: art, logo, Play

    private func header(width: CGFloat, wide: Bool) -> some View {
        VStack(spacing: 14) {
            Art(url: title.backdrop)
                .frame(width: width, height: wide ? width * 0.62 : width * 0.9)
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.3), location: 0), .init(color: .clear, location: 0.3), .init(color: tint, location: 1)], startPoint: .top, endPoint: .bottom))
                .overlay(alignment: .bottom) {
                    TitleLogo(title: title, maxHeight: 110, textSize: 38)
                        .frame(maxWidth: min(width * 0.75, 380))
                        .padding(.bottom, -10)
                }
            VStack(spacing: 14) {
                Text(([typeName(title.type)] + title.genres.prefix(3)).joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(Dx.muted)
                    .padding(.top, 6)
                PrimaryPill(label: playLabel) { start(details.flatMap(nextVideo)?.id) }
                    .frame(maxWidth: 360)
                    .disabled(details?.state != .ready)
                buttons
                if let d = title.description {
                    Button { withAnimation(.snappy) { moreText.toggle() } } label: {
                        Text(d)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(moreText ? nil : 3)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
                facts
                if let scores = reviews?.scores, !scores.isEmpty { scoreRow(scores) }
            }
            .padding(.horizontal, Dx.gutter)
            .padding(.bottom, 24)
        }
    }

    private var buttons: some View {
        let inList = title.inLibrary
        return HStack(spacing: 12) {
            Button { streamsOpen = true } label: {
                Label("Streams", systemImage: "list.bullet").font(.headline).frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.capsule)
            .disabled(details?.videoId == nil && ref.type == "series")
            GlassCircle(icon: inList ? "checkmark" : "plus", label: inList ? "Remove from Library" : "Add to Library") {
                stremio.setInLibrary(!inList)
                stremio.show(inList ? "Removed from your Library" : "Added to your Library")
            }
            Menu {
                Button { stremio.setWatched(!title.watched) } label: {
                    Label(title.watched ? "Mark as Unwatched" : "Mark as Watched", systemImage: title.watched ? "eye.slash" : "eye")
                }
                if let trailer = title.trailer, let url = URL(string: "https://www.youtube.com/watch?v=\(trailer)") {
                    Link(destination: url) { Label("Trailer", systemImage: "film") }
                }
                Button { reviewsOpen = true } label: { Label("Reviews", systemImage: "star") }
                if let pick = current?.picked {
                    Button { play(pick, in: .otherApp) } label: { Label("Play in Another App", systemImage: "arrow.up.forward.app") }
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 20, weight: .semibold)).frame(width: 36, height: 36)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
        }
        .frame(maxWidth: 360)
    }

    private var facts: some View {
        HStack(spacing: 8) {
            Text([title.year, title.runtime].compactMap { $0 }.joined(separator: " · "))
            if title.watched { Badge(text: "WATCHED") }
            Spacer(minLength: 0)
        }
        .font(.subheadline)
        .foregroundStyle(Dx.muted)
    }

    private func scoreRow(_ scores: [Score]) -> some View {
        Button { reviewsOpen = true } label: {
            ScrollView(.horizontal) {
                HStack(spacing: 14) {
                    ForEach(scores.prefix(5)) { s in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(s.value).font(.headline)
                            Text(s.name).font(.caption).foregroundStyle(Dx.muted)
                        }
                    }
                    Image(systemName: "chevron.right").foregroundStyle(Dx.muted)
                }
            }
            .scrollIndicators(.hidden)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Episodes

    @ViewBuilder
    private func episodes(width: CGFloat, vertical: Bool) -> some View {
        if let d = details, !d.videos.isEmpty {
            let shown = d.videos.filter { $0.season == (season ?? d.seasons.first) }
            VStack(alignment: .leading, spacing: 14) {
                Menu {
                    Picker("Season", selection: Binding(get: { season ?? d.seasons.first ?? 1 }, set: { season = $0 })) {
                        ForEach(d.seasons, id: \.self) { s in Text(s == 0 ? "Specials" : "Season \(s)").tag(s) }
                    }
                } label: {
                    HStack(spacing: 6) {
                        let s = season ?? d.seasons.first ?? 1
                        Text(s == 0 ? "Specials" : "Season \(s)").font(.title2.bold())
                        Image(systemName: "chevron.up.chevron.down").font(.headline).foregroundStyle(Dx.muted)
                    }
                    .foregroundStyle(.white)
                }
                .padding(.horizontal, Dx.gutter)
                if vertical {
                    LazyVStack(spacing: 14) {
                        ForEach(shown) { v in episodeButton(v, width: width - Dx.gutter * 2, d: d) }
                    }
                    .padding(.horizontal, Dx.gutter)
                } else {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal) {
                            LazyHStack(spacing: 14) {
                                ForEach(shown) { v in episodeButton(v, width: min(width * 0.72, 340), d: d).id(v.id) }
                            }
                            .scrollTargetLayout()
                            .padding(.horizontal, Dx.gutter)
                        }
                        .scrollTargetBehavior(.viewAligned)
                        .scrollIndicators(.hidden)
                        .onAppear { if let v = nextVideo(d) { proxy.scrollTo(v.id, anchor: .leading) } }
                    }
                }
            }
        }
    }

    private func episodeButton(_ v: Video, width: CGFloat, d: Details) -> some View {
        Button { start(v.id) } label: {
            EpisodeCard(video: v, width: width, fallbackArt: title.backdrop, playing: v.id == d.resumeVideoId && d.resumeMs > 0)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { start(v.id) } label: { Label("Play", systemImage: "play.fill") }
            Button {
                choices = nil
                stremio.open(ref.type, ref.id, videoId: v.id)
                streamsOpen = true
            } label: { Label("Choose a Stream", systemImage: "list.bullet") }
        }
    }

    // MARK: - Cast, crew

    @ViewBuilder
    private var extras: some View {
        if let d = details, !(d.cast.isEmpty && d.directors.isEmpty) {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Cast & Crew")
                ScrollView(.horizontal) {
                    HStack(spacing: 10) {
                        ForEach(d.directors.prefix(3), id: \.self) { person(name: $0, role: "Director") }
                        ForEach(d.cast.prefix(15), id: \.self) { person(name: $0, role: nil) }
                    }
                    .padding(.horizontal, Dx.gutter)
                }
                .scrollIndicators(.hidden)
            }
        }
    }

    private func person(name: String, role: String?) -> some View {
        VStack(spacing: 6) {
            Text(name.split(separator: " ").compactMap(\.first).prefix(2).map(String.init).joined())
                .font(.title2.weight(.semibold))
                .frame(width: 76, height: 76)
                .glassEffect(.regular, in: .circle)
            Text(name).font(.footnote).lineLimit(2).multilineTextAlignment(.center)
            if let role { Text(role).font(.caption).foregroundStyle(Dx.muted) }
        }
        .frame(width: 92)
    }
}
