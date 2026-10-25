// CPU Test 2 (JSON & XML) kernels: a social feed moved the way apps move data — encoded, parsed into a document
// tree and mapped back to objects (JSON); written and SAX-parsed (XML). Plus the text corpus of Test 3.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "core.hpp"

namespace bc {
namespace {

// ── the feed ───────────────────────────────────────────────────────────────

struct Author { int64_t id; std::string name, handle; bool verified; int followers; std::string avatar; };
struct Media { std::string url; int width, height; std::string type; bool hasAlt; std::string alt; };
struct Comment { int64_t id, authorId; std::string text; int likes; };
struct Post {
    int64_t id; Author author; std::string text; std::vector<std::string> tags; int likes, shares; int64_t createdAt;
    std::vector<Media> media; std::vector<Comment> comments; bool pinned; double score;
};
struct Feed { std::vector<Post> posts; std::string cursor; int64_t generatedAt; };

const char* const kWords[] = {
    "rain", "neon", "city", "ramen", "night", "market", "metro", "signal", "tower", "river", "street", "harbor",
    "bridge", "cafe", "hotel", "avenue", "lantern", "traffic", "window", "phone", "battery", "thermal", "render",
    "frame", "pixel", "shadow", "<tag>", "&", "\"quote\"", "\xc3\xbcn\xc3\xaf\x63\xc3\xb6\x64\xc3\xa9",
    "\xe6\x9d\xb1\xe4\xba\xac", "caf\xc3\xa9", "na\xc3\xafve", "\xe2\x98\x94"};   // ünïcödé 東京 café naïve ☔
constexpr int kWordCount = int(sizeof kWords / sizeof kWords[0]);

std::string words(Rng& r, int n) {
    std::string s;
    for (int i = 0; i < n; i++) { if (i) s += ' '; s += kWords[r.below(kWordCount)]; }
    return s;
}

/** A deterministic feed: posts with authors, media and comments (~0.8 KB of JSON each). */
Feed makeFeed(int count) {
    Rng r(2026);
    Feed f;
    f.posts.reserve(size_t(count));
    for (int i = 0; i < count; i++) {
        Post p;
        p.id = 1'000'000 + i;
        p.author = {500 + i % 37, words(r, 2), "@user" + std::to_string(i % 37), i % 5 == 0, r.below(2'000'000),
                    "https://cdn.example/avatars/" + std::to_string(i % 37) + ".webp"};
        p.text = words(r, 18 + r.below(30));
        const int tags = 1 + r.below(4);
        for (int t = 0; t < tags; t++) p.tags.push_back(std::string("#") + kWords[r.below(kWordCount)]);
        p.likes = r.below(100'000);
        p.shares = r.below(5'000);
        p.createdAt = 1'790'000'000'000LL + i * 61'000LL;
        const int media = r.below(3);
        for (int m = 0; m < media; m++) {
            p.media.push_back({"https://cdn.example/m/" + std::to_string(i) + "-" + std::to_string(m) + ".jpg", 1080,
                               720 + m * 40, m == 2 ? "video" : "image", m == 0, m == 0 ? words(r, 4) : ""});
        }
        const int comments = r.below(5);
        for (int c = 0; c < comments; c++) p.comments.push_back({i * 10LL + c, 900LL + c, words(r, 6 + r.below(10)), r.below(500)});
        p.pinned = i == 0;
        p.score = r.unit() * 100;
        f.posts.push_back(std::move(p));
    }
    f.cursor = "c-" + std::to_string(count);
    f.generatedAt = 1'790'000'000'000LL;
    return f;
}

// Numbers are formatted and parsed by hand, like real JSON libraries do: the C library's double conversions
// share big-number scratch space behind a global lock on Android, which would stop the multi-core test scaling.

void appendInt(std::string& s, int64_t v) {
    char buf[24];
    int n = 0;
    uint64_t u = v < 0 ? 0 - uint64_t(v) : uint64_t(v);
    do { buf[n++] = char('0' + u % 10); u /= 10; } while (u);
    if (v < 0) s += '-';
    while (n) s += buf[--n];
}

/** A non-negative value with six decimals, e.g. 37.125000. */
void appendFixed6(std::string& s, double v) {
    const int64_t micro = int64_t(v * 1e6 + 0.5);
    appendInt(s, micro / 1'000'000);
    char frac[6];
    int64_t r = micro % 1'000'000;
    for (int i = 5; i >= 0; i--) { frac[i] = char('0' + r % 10); r /= 10; }
    s += '.';
    s.append(frac, 6);
}

// ── JSON ──────────────────────────────────────────────────────────────────

void jsonString(std::string& s, const std::string& v) {
    s += '"';
    for (const char ch : v) {
        switch (ch) {
            case '"': s += "\\\""; break;
            case '\\': s += "\\\\"; break;
            case '\n': s += "\\n"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", ch);
                    s += buf;
                } else {
                    s += ch;
                }
        }
    }
    s += '"';
}

void key(std::string& s, const char* k) { s += '"'; s += k; s += "\":"; }

void encode(const Feed& f, std::string& s) {
    s.clear();
    s += "{\"posts\":[";
    for (size_t i = 0; i < f.posts.size(); i++) {
        const Post& p = f.posts[i];
        if (i) s += ',';
        s += '{'; key(s, "id"); appendInt(s, p.id);
        s += ','; key(s, "author"); s += '{';
        key(s, "id"); appendInt(s, p.author.id);
        s += ','; key(s, "name"); jsonString(s, p.author.name);
        s += ','; key(s, "handle"); jsonString(s, p.author.handle);
        s += ','; key(s, "verified"); s += p.author.verified ? "true" : "false";
        s += ','; key(s, "followers"); appendInt(s, p.author.followers);
        s += ','; key(s, "avatar"); jsonString(s, p.author.avatar);
        s += "},"; key(s, "text"); jsonString(s, p.text);
        s += ','; key(s, "tags"); s += '[';
        for (size_t t = 0; t < p.tags.size(); t++) { if (t) s += ','; jsonString(s, p.tags[t]); }
        s += "],"; key(s, "likes"); appendInt(s, p.likes);
        s += ','; key(s, "shares"); appendInt(s, p.shares);
        s += ','; key(s, "createdAt"); appendInt(s, p.createdAt);
        s += ','; key(s, "media"); s += '[';
        for (size_t m = 0; m < p.media.size(); m++) {
            const Media& md = p.media[m];
            if (m) s += ',';
            s += '{'; key(s, "url"); jsonString(s, md.url);
            s += ','; key(s, "width"); appendInt(s, md.width);
            s += ','; key(s, "height"); appendInt(s, md.height);
            s += ','; key(s, "type"); jsonString(s, md.type);
            if (md.hasAlt) { s += ','; key(s, "alt"); jsonString(s, md.alt); }
            s += '}';
        }
        s += "],"; key(s, "comments"); s += '[';
        for (size_t c = 0; c < p.comments.size(); c++) {
            const Comment& cm = p.comments[c];
            if (c) s += ',';
            s += '{'; key(s, "id"); appendInt(s, cm.id);
            s += ','; key(s, "authorId"); appendInt(s, cm.authorId);
            s += ','; key(s, "text"); jsonString(s, cm.text);
            s += ','; key(s, "likes"); appendInt(s, cm.likes);
            s += '}';
        }
        s += "],"; key(s, "pinned"); s += p.pinned ? "true" : "false";
        s += ','; key(s, "score"); appendFixed6(s, p.score);
        s += '}';
    }
    s += "],"; key(s, "cursor"); jsonString(s, f.cursor);
    s += ','; key(s, "generatedAt"); appendInt(s, f.generatedAt);
    s += '}';
}

/**
 * A parsed JSON document: every value a node in one pool, containers pointing at a contiguous run of child
 * slots, strings unescaped into one arena — the layout fast parsers use. Everything is reused from document to
 * document, so after the first one parsing allocates nothing (on Android the system allocator serialises
 * threads, which would turn the multi-core test into an allocator test).
 */
struct Doc {
    enum Type : uint8_t { Null, Bool, Num, Str, Arr, Obj };
    struct Node {
        Type type;
        bool b;
        double num;
        uint32_t str, len;     // Str: arena range
        uint32_t kids, count;  // Arr/Obj: range in kidNodes (and kidKeys for objects)
    };
    struct Key { uint32_t str, len; };
    std::vector<Node> nodes;
    std::vector<uint32_t> kidNodes;
    std::vector<Key> kidKeys;
    std::string arena;
    std::vector<uint32_t> stackNodes;   // children of the containers still open
    std::vector<Key> stackKeys;

    void clear() { nodes.clear(); kidNodes.clear(); kidKeys.clear(); arena.clear(); }

    /** Child of object `obj` named `k`, or -1. */
    int32_t get(uint32_t obj, const char* k) const {
        const Node& n = nodes[obj];
        if (n.type != Obj) return -1;
        const size_t klen = std::strlen(k);
        for (uint32_t i = 0; i < n.count; i++) {
            const Key& key = kidKeys[n.kids + i];
            if (key.len == klen && !std::memcmp(arena.data() + key.str, k, klen)) return int32_t(kidNodes[n.kids + i]);
        }
        return -1;
    }
    int64_t asInt(int32_t i) const { return i < 0 ? 0 : int64_t(nodes[size_t(i)].num); }
    bool asBool(int32_t i) const { return i >= 0 && nodes[size_t(i)].b; }
    void asStr(int32_t i, std::string& out) const {
        if (i < 0) { out.clear(); return; }
        out.assign(arena, nodes[size_t(i)].str, nodes[size_t(i)].len);
    }
};

struct Parser {
    const char* p;
    const char* end;
    Doc& doc;
    bool ok = true;

    void ws() { while (p < end && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) p++; }

    static void utf8(std::string& out, uint32_t cp) {
        if (cp < 0x80) out += char(cp);
        else if (cp < 0x800) { out += char(0xC0 | (cp >> 6)); out += char(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += char(0xE0 | (cp >> 12)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
        else {
            out += char(0xF0 | (cp >> 18)); out += char(0x80 | ((cp >> 12) & 0x3F));
            out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F));
        }
    }

    uint32_t hex4() {
        uint32_t v = 0;
        for (int i = 0; i < 4 && p < end; i++, p++) {
            const char c = *p;
            v = v * 16 + uint32_t(c <= '9' ? c - '0' : (c | 0x20) - 'a' + 10);
        }
        return v;
    }

    /** Unescapes the string at p into the arena; returns its range. */
    Doc::Key string() {
        std::string& a = doc.arena;
        const uint32_t start = uint32_t(a.size());
        p++;   // opening quote
        while (p < end && *p != '"') {
            const char* run = p;
            while (p < end && *p != '"' && *p != '\\') p++;
            a.append(run, p);
            if (p >= end || *p == '"') break;
            p++;
            const char e = *p++;
            switch (e) {
                case 'n': a += '\n'; break;
                case 't': a += '\t'; break;
                case 'r': a += '\r'; break;
                case 'b': a += '\b'; break;
                case 'f': a += '\f'; break;
                case 'u': {
                    uint32_t cp = hex4();
                    if (cp >= 0xD800 && cp < 0xDC00 && p + 1 < end && p[0] == '\\' && p[1] == 'u') {
                        p += 2;
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (hex4() - 0xDC00);
                    }
                    utf8(a, cp);
                    break;
                }
                default: a += e;   // \" \\ \/
            }
        }
        p++;   // closing quote
        return {start, uint32_t(a.size()) - start};
    }

    uint32_t value() {
        ws();
        const uint32_t id = uint32_t(doc.nodes.size());
        doc.nodes.push_back({Doc::Null, false, 0, 0, 0, 0, 0});
        if (p >= end) { ok = false; return id; }
        const char c = *p;
        if (c == '{' || c == '[') {
            const bool obj = c == '{';
            const size_t base = doc.stackNodes.size();
            p++; ws();
            if (*p != (obj ? '}' : ']')) {
                for (;;) {
                    ws();
                    if (obj) { doc.stackKeys.push_back(string()); ws(); p++; }   // key, ':'
                    const uint32_t child = value();
                    doc.stackNodes.push_back(child);
                    ws();
                    if (*p == ',') { p++; continue; }
                    break;
                }
            }
            p++;   // '}' or ']'
            Doc::Node& n = doc.nodes[id];
            n.type = obj ? Doc::Obj : Doc::Arr;
            n.kids = uint32_t(doc.kidNodes.size());
            n.count = uint32_t(doc.stackNodes.size() - base);
            doc.kidNodes.insert(doc.kidNodes.end(), doc.stackNodes.begin() + std::ptrdiff_t(base), doc.stackNodes.end());
            doc.stackNodes.resize(base);
            if (obj) {
                const size_t keyBase = doc.stackKeys.size() - n.count;
                doc.kidKeys.insert(doc.kidKeys.end(), doc.stackKeys.begin() + std::ptrdiff_t(keyBase), doc.stackKeys.end());
                doc.stackKeys.resize(keyBase);
            } else {
                doc.kidKeys.resize(doc.kidNodes.size());   // keep the slots aligned
            }
            return id;
        }
        Doc::Node& n = doc.nodes[id];
        switch (c) {
            case '"': {
                const Doc::Key s = string();
                Doc::Node& sn = doc.nodes[id];
                sn.type = Doc::Str; sn.str = s.str; sn.len = s.len;
                return id;
            }
            case 't': n.type = Doc::Bool; n.b = true; p += 4; return id;
            case 'f': n.type = Doc::Bool; p += 5; return id;
            case 'n': p += 4; return id;
            default: {   // [-]digits[.digits]
                const bool neg = *p == '-';
                if (neg) p++;
                const char* digits = p;
                int64_t whole = 0;
                while (p < end && *p >= '0' && *p <= '9') whole = whole * 10 + (*p++ - '0');
                double v = double(whole);
                if (p < end && *p == '.') {
                    p++;
                    int64_t frac = 0;
                    double scale = 1;
                    while (p < end && *p >= '0' && *p <= '9') { frac = frac * 10 + (*p++ - '0'); scale *= 10; }
                    v += double(frac) / scale;
                }
                if (p == digits) ok = false;
                n.type = Doc::Num; n.num = neg ? -v : v;
                return id;
            }
        }
    }
};

/** Document → objects, the way an app maps a response into its model classes (reusing them here). */
void decode(const Doc& d, uint32_t root, Feed& f) {
    const int32_t posts = d.get(root, "posts");
    const uint32_t count = posts < 0 ? 0 : d.nodes[size_t(posts)].count;
    f.posts.resize(count);
    for (uint32_t i = 0; i < count; i++) {
        const uint32_t jp = d.kidNodes[d.nodes[size_t(posts)].kids + i];
        Post& p = f.posts[i];
        p.id = d.asInt(d.get(jp, "id"));
        const int32_t a = d.get(jp, "author");
        if (a >= 0) {
            const uint32_t ua = uint32_t(a);
            p.author.id = d.asInt(d.get(ua, "id"));
            d.asStr(d.get(ua, "name"), p.author.name);
            d.asStr(d.get(ua, "handle"), p.author.handle);
            p.author.verified = d.asBool(d.get(ua, "verified"));
            p.author.followers = int(d.asInt(d.get(ua, "followers")));
            d.asStr(d.get(ua, "avatar"), p.author.avatar);
        }
        d.asStr(d.get(jp, "text"), p.text);
        const int32_t tags = d.get(jp, "tags");
        const uint32_t tagCount = tags < 0 ? 0 : d.nodes[size_t(tags)].count;
        p.tags.resize(tagCount);
        for (uint32_t t = 0; t < tagCount; t++) d.asStr(int32_t(d.kidNodes[d.nodes[size_t(tags)].kids + t]), p.tags[t]);
        p.likes = int(d.asInt(d.get(jp, "likes")));
        p.shares = int(d.asInt(d.get(jp, "shares")));
        p.createdAt = d.asInt(d.get(jp, "createdAt"));
        const int32_t media = d.get(jp, "media");
        const uint32_t mediaCount = media < 0 ? 0 : d.nodes[size_t(media)].count;
        p.media.resize(mediaCount);
        for (uint32_t m = 0; m < mediaCount; m++) {
            const uint32_t jm = d.kidNodes[d.nodes[size_t(media)].kids + m];
            Media& md = p.media[m];
            d.asStr(d.get(jm, "url"), md.url);
            md.width = int(d.asInt(d.get(jm, "width")));
            md.height = int(d.asInt(d.get(jm, "height")));
            d.asStr(d.get(jm, "type"), md.type);
            const int32_t alt = d.get(jm, "alt");
            md.hasAlt = alt >= 0;
            d.asStr(alt, md.alt);
        }
        const int32_t comments = d.get(jp, "comments");
        const uint32_t commentCount = comments < 0 ? 0 : d.nodes[size_t(comments)].count;
        p.comments.resize(commentCount);
        for (uint32_t c = 0; c < commentCount; c++) {
            const uint32_t jc = d.kidNodes[d.nodes[size_t(comments)].kids + c];
            Comment& cm = p.comments[c];
            cm.id = d.asInt(d.get(jc, "id"));
            cm.authorId = d.asInt(d.get(jc, "authorId"));
            d.asStr(d.get(jc, "text"), cm.text);
            cm.likes = int(d.asInt(d.get(jc, "likes")));
        }
        p.pinned = d.asBool(d.get(jp, "pinned"));
        const int32_t score = d.get(jp, "score");
        p.score = score < 0 ? 0 : d.nodes[size_t(score)].num;
    }
    d.asStr(d.get(root, "cursor"), f.cursor);
    f.generatedAt = d.asInt(d.get(root, "generatedAt"));
}

struct Json final : bc_kernel {
    Feed feed = makeFeed(200);
    std::string text;   // these three are reused call after call
    Doc doc;
    Feed back;
    int64_t run() override {
        encode(feed, text);
        doc.clear();
        Parser parser{text.data(), text.data() + text.size(), doc};
        const uint32_t root = parser.value();
        decode(doc, root, back);
        if (!parser.ok || back.posts.size() != feed.posts.size() || back.posts[123].text != feed.posts[123].text ||
            back.posts[7].comments.size() != feed.posts[7].comments.size() || back.cursor != feed.cursor) {
            return fail("JSON round trip");
        }
        int64_t likes = 0;
        for (const Post& p : back.posts) likes += p.likes;
        return int64_t(text.size()) + likes;
    }
};

// ── XML ───────────────────────────────────────────────────────────────────

void esc(std::string& s, const std::string& v) {
    for (const char ch : v) {
        switch (ch) {
            case '<': s += "&lt;"; break;
            case '>': s += "&gt;"; break;
            case '&': s += "&amp;"; break;
            case '"': s += "&quot;"; break;
            default: s += ch;
        }
    }
}

void writeXml(const Feed& f, std::string& s) {
    s.clear();
    s += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<feed cursor=\""; esc(s, f.cursor);
    s += "\" generatedAt=\""; appendInt(s, f.generatedAt); s += "\">\n";
    for (const Post& p : f.posts) {
        s += "<post id=\""; appendInt(s, p.id); s += "\" likes=\""; appendInt(s, p.likes);
        s += "\" shares=\""; appendInt(s, p.shares); s += "\" createdAt=\""; appendInt(s, p.createdAt);
        s += "\" pinned=\""; s += p.pinned ? "true" : "false"; s += "\" score=\""; appendFixed6(s, p.score); s += "\">\n";
        s += "  <author id=\""; appendInt(s, p.author.id); s += "\" handle=\""; esc(s, p.author.handle);
        s += "\" verified=\""; s += p.author.verified ? "true" : "false"; s += "\" followers=\"";
        appendInt(s, p.author.followers); s += "\">"; esc(s, p.author.name); s += "</author>\n";
        s += "  <text>"; esc(s, p.text); s += "</text>\n  <tags>";
        for (const std::string& t : p.tags) { s += "<tag>"; esc(s, t); s += "</tag>"; }
        s += "</tags>\n";
        for (const Media& m : p.media) {
            s += "  <media url=\""; esc(s, m.url); s += "\" w=\""; appendInt(s, m.width); s += "\" h=\"";
            appendInt(s, m.height); s += "\" type=\""; s += m.type; s += '"';
            if (m.hasAlt) { s += " alt=\""; esc(s, m.alt); s += '"'; }
            s += "/>\n";
        }
        for (const Comment& c : p.comments) {
            s += "  <comment id=\""; appendInt(s, c.id); s += "\" author=\""; appendInt(s, c.authorId);
            s += "\" likes=\""; appendInt(s, c.likes); s += "\">"; esc(s, c.text); s += "</comment>\n";
        }
        s += "</post>\n";
    }
    s += "</feed>\n";
}

/** Decodes the five predefined entities of [from, to) into out. */
void unescape(const char* from, const char* to, std::string& out) {
    out.clear();
    while (from < to) {
        if (*from != '&') { out += *from++; continue; }
        if (!std::strncmp(from, "&lt;", 4)) { out += '<'; from += 4; }
        else if (!std::strncmp(from, "&gt;", 4)) { out += '>'; from += 4; }
        else if (!std::strncmp(from, "&amp;", 5)) { out += '&'; from += 5; }
        else if (!std::strncmp(from, "&quot;", 6)) { out += '"'; from += 6; }
        else if (!std::strncmp(from, "&apos;", 6)) { out += '\''; from += 6; }
        else out += *from++;
    }
}

struct Xml final : bc_kernel {
    Feed feed = makeFeed(200);
    std::string xml;
    std::string name, attr, value, text;   // reused, like a SAX parser's buffers
    int64_t expectedLikes = 0;
    Xml() { for (const Post& p : feed.posts) expectedLikes += p.likes; }

    int64_t run() override {
        writeXml(feed, xml);
        const char* p = xml.data();
        const char* end = p + xml.size();
        int64_t posts = 0, likes = 0, chars = 0;
        while (p < end) {
            if (*p != '<') {   // character data up to the next tag
                const char* start = p;
                while (p < end && *p != '<') p++;
                unescape(start, p, text);
                chars += int64_t(text.size());
                continue;
            }
            p++;
            if (*p == '?' || *p == '/') { while (p < end && *p != '>') p++; p++; continue; }   // declaration, end tag
            const char* n0 = p;
            while (p < end && *p != ' ' && *p != '>' && *p != '/') p++;
            name.assign(n0, p);
            const bool isPost = name == "post";
            if (isPost) posts++;
            for (;;) {   // attributes
                while (p < end && *p == ' ') p++;
                if (p >= end || *p == '>' || *p == '/') break;
                const char* a0 = p;
                while (p < end && *p != '=') p++;
                attr.assign(a0, p);
                p += 2;   // ="
                const char* v0 = p;
                while (p < end && *p != '"') p++;
                unescape(v0, p, value);
                p++;
                if (isPost && attr == "likes") likes += std::atoll(value.c_str());
            }
            while (p < end && *p != '>') p++;
            p++;
        }
        if (posts != int64_t(feed.posts.size()) || likes != expectedLikes) return fail("XML round trip");
        return int64_t(xml.size()) + likes + chars;
    }
};

}  // namespace

std::vector<uint8_t> corpus(size_t bytes) {
    const Feed f = makeFeed(60);
    std::string text;
    text.reserve(bytes + 1024);
    while (text.size() < bytes) {
        for (const Post& p : f.posts) {
            text += p.text;
            text += '\n';
            if (text.size() >= bytes) break;
        }
    }
    return std::vector<uint8_t>(text.begin(), text.begin() + std::ptrdiff_t(bytes));
}

bc_kernel* makeJson() { return new Json(); }
bc_kernel* makeXml() { return new Xml(); }

}  // namespace bc
