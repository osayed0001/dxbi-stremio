import SwiftUI

/**
 * A title in a row, as Apple TV draws them: tall art, the title's logo low down over a soft fade, its
 * genre under that, and (Netflix's Top 10) a large number in the corner.
 */
struct PosterCard: View {
    var title: Title
    var width: CGFloat = 150
    var rank: Int?
    var progress: Double?

    var body: some View {
        ZStack(alignment: .bottom) {
            Art(url: title.art)
                .frame(width: width, height: width * 1.5)
            LinearGradient(stops: [.init(color: .clear, location: 0.5), .init(color: .black.opacity(0.85), location: 1)], startPoint: .top, endPoint: .bottom)
            VStack(spacing: 4) {
                if title.logo != nil {
                    TitleLogo(title: title, maxHeight: width * 0.3, textSize: 15)
                        .frame(maxWidth: width * 0.85)
                }
                if let genre = title.genres.first {
                    Text(genre).font(.footnote).foregroundStyle(Dx.muted).lineLimit(1)
                }
                if let progress, progress > 0 {
                    ProgressLine(value: progress, colour: Dx.accent).padding(.horizontal, 10).padding(.top, 2)
                }
            }
            .padding(.bottom, 10)
            .padding(.horizontal, 6)
        }
        .frame(width: width, height: width * 1.5)
        .overlay(alignment: .topLeading) {
            if let rank {
                Text("\(rank)")
                    .font(.system(size: width * 0.3, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.6), radius: 6)
                    .padding(.leading, 10)
                    .padding(.top, 2)
            }
        }
        .overlay(alignment: .topTrailing) {
            if title.watched {
                Image(systemName: "checkmark.circle.fill").font(.title3).symbolRenderingMode(.palette).foregroundStyle(.black, .white).padding(8)
            }
        }
        .clipShape(.rect(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(.white.opacity(0.1)))
        .contentShape(.rect(cornerRadius: 18))
    }
}

/** Continue Watching: wide art with the logo, and a glass strip with how far they got */
struct ContinueCard: View {
    var item: Continue
    var width: CGFloat

    var body: some View {
        let title = item.title
        ZStack(alignment: .bottom) {
            Art(url: title.backdrop).frame(width: width, height: width * 0.62)
            TitleLogo(title: title, maxHeight: width * 0.2, textSize: 22)
                .frame(maxWidth: width * 0.7)
                .frame(maxHeight: .infinity)
                .padding(.bottom, 40)
            HStack(spacing: 10) {
                if item.progress > 0 { ProgressLine(value: item.progress).frame(width: 44) }
                Text([episodeLabel(item.videoId), item.newEpisodes > 0 ? "\(item.newEpisodes) new" : nil].compactMap { $0 }.joined(separator: " · ").ifEmpty(typeName(title.type)))
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(.ultraThinMaterial)
        }
        .frame(width: width, height: width * 0.62)
        .clipShape(.rect(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(.white.opacity(0.12)))
        .contentShape(.rect(cornerRadius: 22))
    }
}

/** New episodes this week: the episode's picture, the show and when */
struct AiringCard: View {
    var item: Airing
    var width: CGFloat

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Art(url: URL(string: item.still ?? item.background ?? "")).frame(width: width, height: width * 0.56)
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.out ? "NEW EPISODE" : item.countdown.uppercased())
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(item.out ? Dx.accent : Dx.muted)
                Text(item.name).font(.headline).lineLimit(1)
                Text("S\(item.season), E\(item.episode)\(item.more > 0 ? " +\(item.more)" : "") · \(item.whenText)")
                    .font(.footnote).foregroundStyle(Dx.muted).lineLimit(1)
            }
            .padding(14)
        }
        .frame(width: width, height: width * 0.56)
        .clipShape(.rect(cornerRadius: 20))
        .contentShape(.rect(cornerRadius: 20))
    }
}

/** An episode, as Apple TV shows them: a tall card, the picture above and the words on glass below */
struct EpisodeCard: View {
    var video: Video
    var width: CGFloat
    var fallbackArt: URL?
    var playing = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Art(url: URL(string: video.thumbnail ?? "") ?? fallbackArt)
                .frame(width: width, height: width * 0.92)
            VStack(alignment: .leading, spacing: 4) {
                Text(video.episode.map { "EPISODE \($0)" } ?? "SPECIAL")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Dx.muted)
                Text(video.title.ifEmpty("Episode \(video.episode ?? 0)"))
                    .font(.headline)
                    .lineLimit(1)
                if let overview = video.overview {
                    Text(overview).font(.subheadline).foregroundStyle(Dx.muted).lineLimit(3)
                }
                HStack(spacing: 8) {
                    if video.upcoming, let date = video.releaseDate {
                        Text(date, format: .dateTime.day().month(.abbreviated)).font(.footnote.weight(.semibold))
                    } else if video.watched {
                        Label("Watched", systemImage: "checkmark").font(.footnote.weight(.semibold))
                    } else if let p = video.progress, p > 0 {
                        ProgressLine(value: p, colour: Dx.accent).frame(width: 60)
                    } else if let date = video.releaseDate {
                        Text(date, format: .dateTime.year().month(.abbreviated).day()).font(.footnote).foregroundStyle(Dx.muted)
                    }
                    Spacer(minLength: 0)
                    if playing { Image(systemName: "play.circle.fill").font(.title3).foregroundStyle(.white) }
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(.ultraThinMaterial)
        }
        .frame(width: width, height: width * 0.92)
        .clipShape(.rect(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(.white.opacity(playing ? 0.6 : 0.1), lineWidth: playing ? 2 : 1))
        .opacity(video.upcoming ? 0.6 : 1)
        .contentShape(.rect(cornerRadius: 22))
    }
}

extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}
