import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WebKit

// ─────────────────────────────────────────────────────────────────────────────
// WEB TESTS (UX 1–3) — the Android app's own pages (web/bench.html,
// scroll.html, gallery.html) in WebKit, at a fixed 1920×1080 physical pixels
// (as on Android), scaled down only for display. The pages fetch `media/…`
// (served from the bundle through the thbench: scheme) and report through
// `window.ThermoBench`, shimmed here onto a WebKit message handler.
// ─────────────────────────────────────────────────────────────────────────────

struct WebBenchView: UIViewRepresentable {
    let page: String
    let engine: BenchmarkEngine

    static let pixelW: CGFloat = 1920, pixelH: CGFloat = 1080

    /** The page's channel to the app — the same calls the Android pages make on their JavaScript interface. */
    private static let shim = """
    window.ThermoBench = {
      progress: function (scene, fraction, complexity, value, index) {
        window.webkit.messageHandlers.bench.postMessage({ k: 'p', s: String(scene), f: +fraction, c: +complexity, v: +value, i: +index });
      },
      done: function (json) { window.webkit.messageHandlers.bench.postMessage({ k: 'd', j: String(json) }); },
      fail: function (message) { window.webkit.messageHandlers.bench.postMessage({ k: 'f', m: String(message) }); }
    };
    """

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var engine: BenchmarkEngine?
        init(_ engine: BenchmarkEngine) { self.engine = engine }

        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let m = message.body as? [String: Any], let k = m["k"] as? String else { return }
            switch k {
            case "p":
                engine?.webProgress(scene: m["s"] as? String ?? "", fraction: m["f"] as? Double ?? 0,
                                    complexity: Int(m["c"] as? Double ?? 0), value: m["v"] as? Double ?? 0, index: Int(m["i"] as? Double ?? 0))
            case "d": engine?.webFinished(.success(m["j"] as? String ?? ""))
            default: engine?.webFinished(.failure(GpuError(message: m["m"] as? String ?? "page failed")))
            }
        }

        // a web process that runs out of memory fails the test instead of taking the app down
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            engine?.webFinished(.failure(GpuError(message: "WebKit's web process was killed")))
        }
    }

    /** Weak hop: WKUserContentController keeps its handlers alive. */
    private final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: WKScriptMessageHandler?
        init(_ t: WKScriptMessageHandler) { target = t }
        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) { target?.userContentController(c, didReceive: m) }
    }

    func makeCoordinator() -> Coordinator { Coordinator(engine) }

    func makeUIView(context: Context) -> UIView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(BundleSchemeHandler(), forURLScheme: "thbench")
        config.websiteDataStore = .nonPersistent()
        config.userContentController.addUserScript(WKUserScript(source: Self.shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        config.userContentController.add(WeakHandler(context.coordinator), name: "bench")
        let scale = UIScreen.main.scale
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: Self.pixelW / scale, height: Self.pixelH / scale), configuration: config)
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0.043, green: 0.055, blue: 0.071, alpha: 1)
        web.scrollView.isScrollEnabled = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        let host = UIView()
        host.clipsToBounds = true
        host.addSubview(web)
        if let url = Bundle.main.url(forResource: page, withExtension: nil), let html = try? String(contentsOf: url) {
            web.loadHTMLString(html, baseURL: URL(string: "thbench://local/"))
        } else {
            engine.webFinished(.failure(GpuError(message: "\(page) missing")))
        }
        return host
    }

    func updateUIView(_ host: UIView, context: Context) {
        guard let web = host.subviews.first else { return }
        // the page keeps its 1920×1080 pixels; only the view is scaled to fit the stage
        let s = min(host.bounds.width / web.bounds.width, host.bounds.height / web.bounds.height, 1)
        web.transform = CGAffineTransform(scaleX: s, y: s)
        web.center = CGPoint(x: host.bounds.midX, y: host.bounds.midY)
    }
}

/** Serves thbench://local/<path> from the app bundle (the pages and media/ copied from the Android assets). */
final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let path = String(url.path.drop { $0 == "/" })
        guard let file = Bundle.main.url(forResource: path, withExtension: nil), let data = try? Data(contentsOf: file) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                        headerFields: ["Content-Type": mime, "Content-Length": "\(data.count)"])!)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}
