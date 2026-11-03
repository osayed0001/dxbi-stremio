import AVFoundation
import AVKit
import MediaPlayer
import SwiftUI
import UIKit

/**
 * The player: mpv's picture, glass controls over it, and the gestures (tap the middle to pause,
 * double-tap a side to skip 10 seconds, drag across to seek, up and down for brightness and volume,
 * pinch to fill, hold for 2× and slide for faster or slower).
 */
struct PlayerScreen: View {
    @Environment(Stremio.self) private var stremio
    let playback: Playback

    private enum Panel { case tracks, more }
    @State private var panel: Panel?

    @State private var visible = true
    @State private var locked = false
    @State private var unlockShown = false
    @State private var hideTask: Task<Void, Never>?
    @State private var seekPreview: Double?
    @State private var heldSpeed: Double?
    @State private var level: (icon: String, value: Double)?
    @State private var scrubbing: Double?
    @State private var now = Date()

    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(mpv: playback.mpv).ignoresSafeArea()
            Gestures(handler: gestures).ignoresSafeArea()
            if playback.buffering && seekPreview == nil { ProgressView().controlSize(.large).tint(.white).allowsHitTesting(false) }
            if visible && !locked { controls.transition(.opacity) }
            overlays
            if let panel { side(panel).transition(.move(edge: .trailing).combined(with: .opacity)) }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .animation(.easeOut(duration: 0.2), value: visible)
        .animation(.snappy(duration: 0.25), value: panel)
        .onReceive(clock) { now = $0 }
        .onAppear {
            AppDelegate.orientations = .landscape
            turn(.landscape)
            poke()
        }
        .onDisappear {
            AppDelegate.orientations = .allButUpsideDown
            turn(.portrait)
        }
    }

    private func turn(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    /** Shows the controls, and hides them again after a few seconds of playing */
    private func poke() {
        visible = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled && !playback.paused && scrubbing == nil && panel == nil { visible = false }
        }
    }

    // MARK: - Gestures

    private var gestures: Gestures.Handler {
        var h = Gestures.Handler()
        h.tap = { x, width in
            if locked {
                unlockShown = true
                Task { try? await Task.sleep(for: .seconds(3)); unlockShown = false }
                return
            }
            if panel != nil {
                panel = nil
                return
            }
            // the middle pauses; either side shows or hides the controls
            if abs(x - width / 2) < width / 6 {
                playback.mpv.toggle()
                poke()
            } else if visible {
                visible = false
            } else {
                poke()
            }
        }
        h.doubleTap = { x, width in
            guard !locked else { return }
            let forward = x > width / 2
            playback.mpv.seek(by: forward ? 10 : -10)
            playback.show(forward ? "+10 seconds" : "−10 seconds")
        }
        h.seek = { phase, dx, width in
            guard !locked, playback.duration > 0 else { return }
            switch phase {
            case .changed:
                let base = seekPreview ?? playback.position
                if seekPreview == nil { seekPreview = base }
                let target = min(max(playback.position + Double(dx / width) * min(playback.duration, 600), 0), playback.duration)
                seekPreview = target
                visible = true
            default:
                if let target = seekPreview { playback.mpv.seek(to: target) }
                seekPreview = nil
                poke()
            }
        }
        h.slide = { phase, dy, left in
            guard !locked else { return }
            if phase != .changed {
                Task { try? await Task.sleep(for: .seconds(0.8)); level = nil }
                return
            }
            if left {
                let b = min(max(UIScreen.main.brightness - dy / 300, 0), 1)
                UIScreen.main.brightness = b
                level = ("sun.max.fill", Double(b))
            } else {
                let v = min(max(Double(SystemVolume.value) - Double(dy / 300), 0), 1)
                SystemVolume.set(Float(v))
                level = ("speaker.wave.2.fill", v)
            }
        }
        h.pinch = { scale in
            guard !locked else { return }
            let fit: Playback.Fit = scale > 1 ? .fill : .fit
            guard fit != playback.fit else { return }
            playback.fit = fit
            playback.show(fit == .fill ? "Fill screen" : "Fit to screen")
        }
        h.hold = { phase, dx in
            guard !locked else { return }
            switch phase {
            case .began:
                heldSpeed = playback.speed
                playback.mpv.setSpeed(2)
            case .changed:
                // slide right for faster (up to 4×), left for slower (down to 1.25×), a quarter at a time
                let steps = (dx / 36).rounded()
                playback.mpv.setSpeed(min(max(2 + Double(steps) * 0.25, 1.25), 4))
            default:
                if let held = heldSpeed { playback.mpv.setSpeed(held) }
                heldSpeed = nil
            }
        }
        return h
    }

    // MARK: - Controls

    private var controls: some View {
        ZStack {
            LinearGradient(stops: [.init(color: .black.opacity(0.6), location: 0), .init(color: .clear, location: 0.3), .init(color: .clear, location: 0.6), .init(color: .black.opacity(0.75), location: 1)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar
                Spacer()
                centre
                Spacer()
                bottomBar
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 14)
        }
    }

    private var topBar: some View {
        HStack(alignment: .top, spacing: 14) {
            GlassCircle(icon: "xmark", label: "Close", size: 46) { playback.finish(ended: false) }
            VStack(alignment: .leading, spacing: 2) {
                Text(playback.title).font(.headline).lineLimit(1)
                if !playback.subtitle.isEmpty { Text(playback.subtitle).font(.subheadline).foregroundStyle(Dx.muted).lineLimit(1) }
            }
            .padding(.top, 4)
            Spacer()
            if let party = playback.party {
                Label("\(party.people.count)", systemImage: "person.2.fill")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .glassEffect(.regular, in: .capsule)
            }
            if playback.duration > 0 {
                Text("Ends at \(now.addingTimeInterval((playback.duration - playback.position) / max(playback.speed, 0.1)), format: .dateTime.hour().minute())")
                    .font(.footnote).foregroundStyle(Dx.muted).padding(.top, 12)
            }
            if AVPictureInPictureController.isPictureInPictureSupported() {
                GlassCircle(icon: "pip.enter", label: "Picture in Picture", size: 46) { playback.pip.start() }
            }
            GlassCircle(icon: "lock.open.fill", label: "Lock", size: 46) {
                locked = true
                visible = false
                playback.show("Locked: tap the screen to unlock")
            }
        }
    }

    private var centre: some View {
        HStack(spacing: 56) {
            GlassCircle(icon: "gobackward.10", label: "Back 10 seconds", size: 62) { playback.mpv.seek(by: -10); poke() }
            Button {
                playback.mpv.toggle()
                poke()
            } label: {
                Image(systemName: playback.paused ? "play.fill" : "pause.fill")
                    .font(.system(size: 38, weight: .semibold))
                    .frame(width: 66, height: 66)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(playback.paused ? "Play" : "Pause")
            GlassCircle(icon: "goforward.10", label: "Forward 10 seconds", size: 62) { playback.mpv.seek(by: 10); poke() }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            Scrubber(position: scrubbing ?? seekPreview ?? playback.position, duration: playback.duration, accent: Color(playback.accent), segments: playback.segments) { value, done in
                if done {
                    playback.mpv.seek(to: value)
                    scrubbing = nil
                    poke()
                } else {
                    scrubbing = value
                    hideTask?.cancel()
                }
            }
            HStack(spacing: 10) {
                Text(time(scrubbing ?? seekPreview ?? playback.position)).monospacedDigit()
                Text("/ " + time(playback.duration)).monospacedDigit().foregroundStyle(Dx.muted)
                Spacer()
                speedMenu
                Button { panel = .tracks } label: { Label("Subtitles", systemImage: "captions.bubble.fill").frame(height: 24) }
                    .buttonStyle(.glass)
                Button { panel = .more } label: { Image(systemName: "ellipsis").frame(width: 24, height: 24) }
                    .buttonStyle(.glass)
                    .accessibilityLabel("More")
                if playback.nextTitle != nil {
                    Button { playback.playNext() } label: { Label("Next", systemImage: "forward.end.fill").frame(height: 24) }
                        .buttonStyle(.glass)
                }
            }
            .font(.subheadline.weight(.semibold))
        }
    }

    private var speedMenu: some View {
        Menu {
            Picker("Speed", selection: Binding(get: { playback.speed }, set: { playback.mpv.setSpeed($0) })) {
                ForEach([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2], id: \.self) { r in Text(r == 1 ? "Normal" : "\(r.formatted())×").tag(r) }
            }
        } label: {
            Label(playback.speed == 1 ? "Speed" : "\(playback.speed.formatted())×", systemImage: "gauge.with.dots.needle.67percent").frame(height: 24)
        }
        .buttonStyle(.glass)
        .menuActionDismissBehavior(.disabled)
    }

    // MARK: - Side panels (they scroll: a long list of subtitles fits)

    private func side(_ which: Panel) -> some View {
        HStack {
            Spacer()
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    switch which {
                    case .tracks: tracks
                    case .more: more
                    }
                }
                .padding(18)
            }
            .scrollIndicators(.hidden)
            .frame(width: 380)
            .frame(maxHeight: .infinity)
            .glassEffect(.regular, in: .rect(cornerRadius: 28))
            .padding(.vertical, 12)
            .padding(.trailing, 12)
        }
    }

    private func heading(_ text: String) -> some View {
        Text(text).font(.footnote.weight(.semibold)).foregroundStyle(Dx.muted).padding(.top, 14).padding(.bottom, 2)
    }

    private func choice(_ text: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(text).multilineTextAlignment(.leading)
                Spacer()
                if on { Image(systemName: "checkmark").fontWeight(.bold) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(on ? .white.opacity(0.16) : .clear, in: .rect(cornerRadius: 14))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /** Sound and subtitles; subtitles by where they come from */
    @ViewBuilder
    private var tracks: some View {
        let subs = playback.subtitleTracks
        Text("Subtitles").font(.title3.bold())
        choice("Off", on: !subs.contains(where: \.selected)) { playback.mpv.select("sub", nil) }
        ForEach([("video", "In the video"), ("stream", "With this stream"), ("addon", "From subtitle addons · may not match this video")], id: \.0) { source, title in
            let group = subs.filter { playback.source(of: $0) == source }
            if !group.isEmpty {
                heading(title)
                ForEach(group) { t in choice(trackName(t), on: t.selected) { playback.mpv.select("sub", t.id) } }
            }
        }
        if playback.audioTracks.count > 1 {
            Text("Audio").font(.title3.bold()).padding(.top, 18)
            ForEach(playback.audioTracks) { t in
                choice(trackName(t) + (t.codec.isEmpty ? "" : " · \(t.codec.uppercased())"), on: t.selected) { playback.mpv.select("audio", t.id) }
            }
        }
    }

    /** The picture, the stream's details, the watch party and playing it elsewhere */
    @ViewBuilder
    private var more: some View {
        if let party = playback.party {
            Text("Watch party · \(party.code)").font(.title3.bold())
            Text(party.people.isEmpty ? "Waiting for friends" : "With " + party.people.joined(separator: ", ")).font(.subheadline).foregroundStyle(Dx.muted)
            Button("Leave the Party", role: .destructive) { stremio.leaveParty(); panel = nil }.padding(.vertical, 8)
        }
        Text("Picture").font(.title3.bold())
        HStack(spacing: 8) {
            ForEach(Playback.Fit.allCases, id: \.self) { f in
                Button(f.rawValue) { playback.fit = f }
                    .buttonStyle(.glass)
                    .tint(playback.fit == f ? .white : nil)
                    .foregroundStyle(playback.fit == f ? .black : .white)
            }
        }
        .padding(.vertical, 6)
        Text("Stream").font(.title3.bold()).padding(.top, 14)
        TimelineView(.periodic(from: .now, by: 2)) { _ in
            VStack(alignment: .leading, spacing: 6) {
                ForEach(playback.mpv.info(), id: \.0) { label, value in
                    HStack(alignment: .top) {
                        Text(label).foregroundStyle(Dx.muted).frame(width: 74, alignment: .leading)
                        Text(value).textSelection(.enabled)
                    }
                    .font(.footnote)
                }
            }
        }
        .padding(.vertical, 6)
        Button {
            panel = nil
            stremio.playExternally(playback)
        } label: {
            Label("Play with External Player", systemImage: "arrow.up.forward.app").frame(maxWidth: .infinity, minHeight: 30)
        }
        .buttonStyle(.glass)
        .padding(.top, 10)
    }

    @ViewBuilder
    private func check(_ text: String, on: Bool) -> some View {
        if on { Label(text, systemImage: "checkmark") } else { Text(text) }
    }

    private func trackName(_ t: MPV.Track) -> String {
        let lang = Locale.current.localizedString(forLanguageCode: t.lang) ?? t.lang.uppercased()
        if t.external { return t.title.ifEmpty(lang.ifEmpty("Subtitles")) }
        return [lang, t.title].filter { !$0.isEmpty }.joined(separator: " · ").ifEmpty("Track \(t.id)")
    }

    // MARK: - Over everything: messages, skip, up next, lock

    private var overlays: some View {
        ZStack {
            VStack {
                if let text = heldSpeed != nil ? "\(playback.speed.formatted())× ▶▶" : playback.flash {
                    Text(text)
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .glassEffect(.regular, in: .capsule)
                        .padding(.top, 10)
                        .transition(.opacity)
                }
                if let preview = seekPreview {
                    Text("\(time(preview))  (\(preview >= playback.position ? "+" : "−")\(time(abs(preview - playback.position))))")
                        .font(.title3.weight(.semibold)).monospacedDigit()
                        .padding(.horizontal, 18).padding(.vertical, 10)
                        .glassEffect(.regular, in: .capsule)
                        .padding(.top, 10)
                }
                Spacer()
            }
            .allowsHitTesting(false)
            if let level {
                HStack(spacing: 10) {
                    Image(systemName: level.icon)
                    ProgressLine(value: level.value).frame(width: 120)
                }
                .padding(.horizontal, 18).padding(.vertical, 12)
                .glassEffect(.regular, in: .capsule)
                .allowsHitTesting(false)
            }
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    if !locked, playback.upNextDue, let next = playback.nextTitle {
                        upNext(next)
                    } else if !locked, let segment = playback.segment {
                        Button { playback.skip() } label: { Text(segment.label).font(.headline).padding(.horizontal, 8).frame(height: 30) }
                            .buttonStyle(.glassProminent)
                            .tint(.white)
                            .foregroundStyle(.black)
                    }
                }
                .padding(.bottom, visible ? 110 : 30)
                .padding(.horizontal, 30)
            }
            if locked && unlockShown {
                VStack {
                    HStack {
                        Spacer()
                        GlassCircle(icon: "lock.fill", label: "Unlock", size: 46) {
                            locked = false
                            unlockShown = false
                            poke()
                        }
                    }
                    Spacer()
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
            }
        }
        .animation(.easeOut(duration: 0.2), value: playback.flash)
    }

    private func upNext(_ next: String) -> some View {
        Button { playback.playNext() } label: {
            HStack(spacing: 12) {
                Art(url: playback.nextArt).frame(width: 120, height: 68).clipShape(.rect(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 2) {
                    Text("UP NEXT").font(.caption2.weight(.bold)).foregroundStyle(Dx.muted)
                    Text(next).font(.subheadline.weight(.semibold)).lineLimit(2)
                    Label("Play", systemImage: "play.fill").font(.footnote.weight(.bold))
                }
                .frame(width: 170, alignment: .leading)
            }
            .padding(10)
            .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 18))
        }
        .buttonStyle(.plain)
    }

    private func time(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let s = Int(max(seconds, 0))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/** The seek bar: drag anywhere on it; skip segments are marked */
private struct Scrubber: View {
    var position: Double
    var duration: Double
    var accent: Color
    var segments: [Playback.Segment]
    var onScrub: (Double, Bool) -> Void
    @State private var dragging = false

    var body: some View {
        GeometryReader { g in
            let fraction = duration > 0 ? min(max(position / duration, 0), 1) : 0
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25)).frame(height: dragging ? 10 : 6)
                ForEach(Array(segments.enumerated()), id: \.offset) { _, s in
                    if duration > 0 {
                        let end = s.end > 0 ? s.end : duration
                        Capsule().fill(.white.opacity(0.35))
                            .frame(width: max(g.size.width * (end - s.start) / duration, 2), height: dragging ? 10 : 6)
                            .offset(x: g.size.width * s.start / duration)
                    }
                }
                Capsule().fill(accent).frame(width: g.size.width * fraction, height: dragging ? 10 : 6)
                Circle().fill(.white).frame(width: dragging ? 22 : 14).offset(x: g.size.width * fraction - (dragging ? 11 : 7))
            }
            .frame(height: 30)
            .contentShape(.rect)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        dragging = true
                        onScrub(min(max(v.location.x / g.size.width, 0), 1) * duration, false)
                    }
                    .onEnded { v in
                        dragging = false
                        onScrub(min(max(v.location.x / g.size.width, 0), 1) * duration, true)
                    }
            )
            .animation(.snappy(duration: 0.15), value: dragging)
        }
        .frame(height: 30)
    }
}

/** mpv's picture */
private struct VideoSurface: UIViewRepresentable {
    let mpv: MPV
    func makeUIView(context: Context) -> Surface { Surface(layer: mpv.layer) }
    func updateUIView(_ view: Surface, context: Context) {}

    final class Surface: UIView {
        let metal: CALayer
        init(layer: CALayer) {
            metal = layer
            super.init(frame: .zero)
            backgroundColor = .black
            self.layer.addSublayer(layer)
        }
        required init?(coder: NSCoder) { fatalError() }
        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            metal.frame = bounds
            CATransaction.commit()
        }
    }
}

/** The phone's volume (only Apple's volume slider can change it) */
@MainActor
enum SystemVolume {
    private static let view = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
    private static var slider: UISlider? { view.subviews.compactMap { $0 as? UISlider }.first }

    static var value: Float { AVAudioSessionVolume.current }

    static func set(_ value: Float) {
        if view.superview == nil { UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.addSubview(view) }
        slider?.value = value
    }
}

private enum AVAudioSessionVolume {
    static var current: Float { AVAudioSession.sharedInstance().outputVolume }
}

/**
 * The player's touch gestures, in UIKit: a single tap has to wait to know it isn't a double tap, and
 * drags have to be told apart by direction, which SwiftUI's gestures don't do well together.
 */
private struct Gestures: UIViewRepresentable {
    struct Handler {
        var tap: (CGFloat, CGFloat) -> Void = { _, _ in }
        var doubleTap: (CGFloat, CGFloat) -> Void = { _, _ in }
        /** A drag across: how far, and the screen's width */
        var seek: (UIGestureRecognizer.State, CGFloat, CGFloat) -> Void = { _, _, _ in }
        /** A drag up or down: how far since last time, and whether on the left */
        var slide: (UIGestureRecognizer.State, CGFloat, Bool) -> Void = { _, _, _ in }
        var pinch: (CGFloat) -> Void = { _ in }
        /** Holding: how far the finger has slid sideways since it started */
        var hold: (UIGestureRecognizer.State, CGFloat) -> Void = { _, _ in }
    }

    var handler: Handler

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let c = context.coordinator
        let double = UITapGestureRecognizer(target: c, action: #selector(Coordinator.double(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: c, action: #selector(Coordinator.single(_:)))
        single.require(toFail: double)
        let pan = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.pan(_:)))
        let pinch = UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinch(_:)))
        let hold = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.hold(_:)))
        hold.minimumPressDuration = 0.45
        pan.require(toFail: hold)
        for g in [double, single, pan, pinch, hold] as [UIGestureRecognizer] { view.addGestureRecognizer(g) }
        return view
    }

    func updateUIView(_ view: UIView, context: Context) { context.coordinator.handler = handler }

    @MainActor
    final class Coordinator: NSObject {
        var handler = Handler()
        private var axis: Int = 0 // 1 across, 2 up and down
        private var last: CGPoint = .zero
        private var holdStart: CGFloat = 0

        @objc func single(_ g: UITapGestureRecognizer) {
            guard let v = g.view else { return }
            handler.tap(g.location(in: v).x, v.bounds.width)
        }

        @objc func double(_ g: UITapGestureRecognizer) {
            guard let v = g.view else { return }
            handler.doubleTap(g.location(in: v).x, v.bounds.width)
        }

        @objc func pan(_ g: UIPanGestureRecognizer) {
            guard let v = g.view else { return }
            let t = g.translation(in: v)
            switch g.state {
            case .began:
                axis = 0
                last = .zero
            case .changed:
                if axis == 0 { axis = abs(t.x) > abs(t.y) ? 1 : 2 }
                if axis == 1 { handler.seek(.changed, t.x, v.bounds.width) }
                else { handler.slide(.changed, t.y - last.y, g.location(in: v).x < v.bounds.width / 2) }
                last = t
            default:
                if axis == 1 { handler.seek(.ended, t.x, v.bounds.width) } else if axis == 2 { handler.slide(.ended, 0, true) }
                axis = 0
            }
        }

        @objc func pinch(_ g: UIPinchGestureRecognizer) {
            if g.state == .ended { handler.pinch(g.scale) }
        }

        @objc func hold(_ g: UILongPressGestureRecognizer) {
            guard let v = g.view else { return }
            let x = g.location(in: v).x
            switch g.state {
            case .began:
                holdStart = x
                handler.hold(.began, 0)
            case .changed: handler.hold(.changed, x - holdStart)
            default: handler.hold(.ended, 0)
            }
        }
    }
}
