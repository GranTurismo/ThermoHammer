namespace ThermoHammer.Api.Tools;

/// <summary>
/// An Android phone reports its hardware code (Build.MODEL, e.g. "VKP-NX9"), not the name on its box ("HONOR 600 Pro").
/// This turns one into the other with Google's public list of Play devices (Data/play_devices.tsv, rebuilt by
/// tools/refresh_device_names.py) and hand-made corrections (Data/device_name_overrides.tsv, which win).
///
/// The apps send the name with the manufacturer in front ("HONOR VKP-NX9"), and the stored name keeps that shape:
/// only the code part is replaced, so "Xiaomi 12" and "OnePlus 12" stay two different phones.
/// </summary>
public sealed class DeviceNames
{
    private sealed record Row(string Brand, string Name);

    private readonly Dictionary<string, List<Row>> _byCode = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _brands = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _names = new(StringComparer.OrdinalIgnoreCase);

    private static readonly Lazy<DeviceNames> ShippedTable = new(LoadShipped);

    /// <summary>The table that ships inside the API.</summary>
    public static DeviceNames Shipped => ShippedTable.Value;

    /// <summary>Lines are <c>code TAB brand TAB name</c>; <c>#</c> starts a comment. A code in [overrides] replaces what [list] says about it.</summary>
    public DeviceNames(TextReader list, TextReader? overrides = null)
    {
        Read(list, replace: false);
        if (overrides is not null) Read(overrides, replace: true);
    }

    /// <summary>How many different phone codes the table knows.</summary>
    public int Count => _byCode.Count;

    /// <summary>
    /// The full name for what a phone sent: "HONOR VKP-NX9" (manufacturer in front, the way the apps send it) or the bare code.
    /// [manufacturer] is the one the phone reported, when the caller has it. False when the code is unknown or ambiguous:
    /// the name is then left exactly as it came.
    /// </summary>
    public bool TryResolve(string? deviceModel, string? manufacturer, out string name)
    {
        name = "";
        string input = deviceModel?.Trim() ?? "";
        if (input.Length == 0) return false;

        // already the name on the box (what an earlier pass stored): resolving it again must never turn it into another phone,
        // and some names end in something that is also a code ("POCO C31", "Redmi K60")
        if (IsKnownName(input, manufacturer?.Trim())) return false;

        foreach (var (code, maker) in Candidates(input, manufacturer?.Trim()))
        {
            if (!_byCode.TryGetValue(code, out var rows) || Pick(rows, code, maker) is not { } row) continue;
            string by = string.IsNullOrEmpty(maker) ? row.Brand : maker;
            name = StartsWithWord(row.Name, by) ? row.Name : $"{by} {row.Name}";
            return true;
        }
        return false;
    }

    /// <summary>The code alone ("VKP-NX9" out of "HONOR VKP-NX9"), which is what a model-number lookup wants; the text itself when no manufacturer leads it.</summary>
    public string CodeOf(string deviceModel, string? manufacturer)
    {
        string input = deviceModel.Trim();
        foreach (var (code, _) in Candidates(input, manufacturer?.Trim()).Skip(1)) return code;
        return input;
    }

    /// <summary>A name the table gives some phone, with or without a manufacturer in front.</summary>
    private bool IsKnownName(string input, string? maker)
    {
        if (_names.Contains(input)) return true;
        if (!string.IsNullOrEmpty(maker) && StartsWithWord(input, maker))
            return _names.Contains(input[maker.Length..].TrimStart(' ', '-', '_'));
        int space = input.IndexOf(' ');
        return space > 0 && _brands.Contains(input[..space]) && _names.Contains(input[(space + 1)..].Trim());
    }

    /// <summary>What to look up, most literal first: the text as it is, then without the manufacturer in front of it.</summary>
    private IEnumerable<(string Code, string? Maker)> Candidates(string input, string? maker)
    {
        yield return (input, maker);
        if (!string.IsNullOrEmpty(maker))
        {
            if (StartsWithWord(input, maker) && input[maker.Length..].TrimStart(' ', '-', '_') is { Length: > 0 } rest)
                yield return (rest, maker);
            yield break;
        }
        // the caller doesn't know the manufacturer: its name may still be the first word ("HONOR VKP-NX9")
        int space = input.IndexOf(' ');
        if (space > 0 && input[(space + 1)..].Trim() is { Length: > 0 } tail && _brands.Contains(input[..space]))
            yield return (tail, input[..space]);
    }

    /// <summary>The one name the table gives for [code] (null when it gives none or several).</summary>
    private static Row? Pick(List<Row> rows, string code, string? maker)
    {
        string by = maker ?? "";
        IEnumerable<Row> pool = rows;
        var ofMaker = by.Length == 0 ? new List<Row>() : rows.Where(r => Related(by, r.Brand)).ToList();
        if (ofMaker.Count > 0) pool = ofMaker;
        // nothing vouches for the brand: only believe something that looks like a hardware code, so a name that is already
        // right ("90") never turns into another phone's just because that one's code happens to be the same text
        else if (!LooksLikeCode(code)) return null;

        var names = pool.GroupBy(r => r.Name, StringComparer.OrdinalIgnoreCase).ToList();
        return names.Count == 1 ? names[0].First() : null;
    }

    /// <summary>The manufacturer a phone reports and the brand Google lists can differ in case or form: "HONOR"/"Honor", "LGE"/"LG".</summary>
    private static bool Related(string maker, string brand) =>
        maker.Equals(brand, StringComparison.OrdinalIgnoreCase)
        || (Math.Min(maker.Length, brand.Length) >= 2
            && (maker.StartsWith(brand, StringComparison.OrdinalIgnoreCase) || brand.StartsWith(maker, StringComparison.OrdinalIgnoreCase)));

    /// <summary>"VKP-NX9", "SM-S918B", "2412DPC0AG": no spaces, at least one letter and one digit.</summary>
    private static bool LooksLikeCode(string code) =>
        !code.Contains(' ') && code.Any(char.IsLetter) && code.Any(char.IsDigit);

    private static bool StartsWithWord(string text, string word) =>
        word.Length > 0 && text.StartsWith(word, StringComparison.OrdinalIgnoreCase)
        && (text.Length == word.Length || !char.IsLetterOrDigit(text[word.Length]));

    private void Read(TextReader reader, bool replace)
    {
        var replaced = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (string? line; (line = reader.ReadLine()) is not null;)
        {
            if (line.Length == 0 || line[0] == '#') continue;
            var f = line.Split('\t');
            if (f.Length < 3 || f[0].Length == 0 || f[1].Length == 0 || f[2].Length == 0) continue;
            string code = f[0].Trim();
            if (replace && replaced.Add(code)) _byCode.Remove(code);   // the first correction for a code drops what the list said
            if (!_byCode.TryGetValue(code, out var rows)) _byCode[code] = rows = [];
            rows.Add(new Row(f[1].Trim(), f[2].Trim()));
            _brands.Add(f[1].Trim());
            _names.Add(f[2].Trim());
        }
    }

    private static DeviceNames LoadShipped()
    {
        try
        {
            var assembly = typeof(DeviceNames).Assembly;
            using var list = Open(assembly, "play_devices.tsv");
            using var overrides = Open(assembly, "device_name_overrides.tsv");
            return new DeviceNames(list, overrides);
        }
        catch (Exception)
        {
            // a damaged table must never stop a submission: names then stay as the phone reports them
            return new DeviceNames(TextReader.Null);
        }
    }

    private static TextReader Open(System.Reflection.Assembly assembly, string resource) =>
        assembly.GetManifestResourceStream(resource) is { } stream ? new StreamReader(stream) : TextReader.Null;
}
