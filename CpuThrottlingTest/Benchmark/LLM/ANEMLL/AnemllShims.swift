import Foundation

// The two types ANEMLL's core takes from its other files, cut down to what the core uses. Its own YAMLConfig parses
// meta.yaml with Yams and its Tokenizer wraps Hugging Face's swift-transformers; the benchmark knows its one model's
// settings and reads its tokens itself (LLM/AneLlm.swift), so neither package comes into the app.

/** The tokenizer the core asks for: only for its padding id and its debug printing (never on at debugLevel 0). */
public final class Tokenizer: @unchecked Sendable {
    public let padTokenId: Int
    private let pieces: [String]
    public init(padTokenId: Int, pieces: [String] = []) { self.padTokenId = padTokenId; self.pieces = pieces }
    public func decode(tokens: [Int], skipSpecialTokens: Bool = true) -> String {
        tokens.map { $0 >= 0 && $0 < pieces.count ? pieces[$0] : "" }.joined().replacingOccurrences(of: "\u{2581}", with: " ")
    }
}

/** The model's settings ModelLoader reads (ANEMLL's meta.yaml keys of the same names). */
public struct YAMLConfig: Sendable {
    public let contextLength: Int
    public let embedPath: String
    public let ffnPath: String
    public let lmheadPath: String
    public let numChunks: Int
    public let isMonolithic: Bool
    public let monolithicModelPath: String?
    public let slidingWindow: Int?
}
