using System.Text.RegularExpressions;

namespace ThermoHammer.Tests;

/// <summary>
/// Every function the app imports from its own DLLs (benchcore, benchllm) is in that DLL's export list (native/*.def).
/// One that isn't compiles and packages fine and then throws EntryPointNotFoundException on a PC: 2.0.4 shipped with
/// bc_llm_tune_threads_progress missing, which ended every benchmark at the LLM test with an error.
/// </summary>
public class NativeExportsTests
{
    private static string AppFolder()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "native", "benchllm.def")) && File.Exists(Path.Combine(d.FullName, "ThermoHammer.csproj")))
                return d.FullName;
        throw new InvalidOperationException("ThermoHammerWindows not found above " + AppContext.BaseDirectory);
    }

    private static HashSet<string> Exports(string app, string dll) =>
        File.ReadAllLines(Path.Combine(app, "native", dll + ".def"))
            .SkipWhile(l => l.Trim() != "EXPORTS").Skip(1)
            .Select(l => l.Split(';')[0].Trim()).Where(l => l.Length > 0).ToHashSet();

    /// <summary>The functions the app's sources import from `dll`, with the file each is declared in.</summary>
    private static Dictionary<string, string> Imports(string app, string dll)
    {
        var found = new Dictionary<string, string>();
        var skip = new[] { "obj", "bin", "Tests", "native", "msix", "runtimes", "installer" };
        foreach (var file in Directory.EnumerateFiles(app, "*.cs", SearchOption.AllDirectories))
        {
            string rel = Path.GetRelativePath(app, file);
            if (skip.Contains(rel.Split(Path.DirectorySeparatorChar)[0])) continue;
            string src = File.ReadAllText(file);
            var consts = new Dictionary<string, string>();     // a name declared twice in a file (two classes): the last one, which no DLL name is
            foreach (Match c in Regex.Matches(src, @"const\s+string\s+(\w+)\s*=\s*""([^""]+)""")) consts[c.Groups[1].Value] = c.Groups[2].Value;
            // [LibraryImport(Dll, …)] or [DllImport("x", EntryPoint = "y")], any further attributes, then the method's name before its "("
            foreach (Match m in Regex.Matches(src, @"\[(?:LibraryImport|DllImport)\(\s*([^,\)\]]+)([^\]]*)\]\s*(?:\[[^\]]*\]\s*)*[^;{(]*?\s(\w+)\s*\("))
            {
                string lib = m.Groups[1].Value.Trim().Trim('"');
                if (consts.TryGetValue(lib, out var named)) lib = named;
                if (!lib.Replace(".dll", "").Equals(dll, StringComparison.OrdinalIgnoreCase)) continue;
                var entry = Regex.Match(m.Groups[2].Value, @"EntryPoint\s*=\s*""([^""]+)""");
                found[entry.Success ? entry.Groups[1].Value : m.Groups[3].Value] = rel;
            }
        }
        return found;
    }

    [Theory]
    [InlineData("benchcore")]
    [InlineData("benchllm")]
    public void Every_function_the_app_imports_is_exported_by_the_DLL(string dll)
    {
        string app = AppFolder();
        var imports = Imports(app, dll);
        Assert.True(imports.Count >= 10, $"only {imports.Count} imports of {dll} found: the scan is broken");
        var exports = Exports(app, dll);
        var missing = imports.Where(i => !exports.Contains(i.Key)).Select(i => $"{i.Key} ({i.Value})").OrderBy(x => x).ToArray();
        Assert.True(missing.Length == 0, $"not in native/{dll}.def: {string.Join(", ", missing)}");
    }

    [Fact]
    public void The_thread_tuning_with_progress_is_found_and_exported()
    {
        string app = AppFolder();
        Assert.Contains("bc_llm_tune_threads_progress", Imports(app, "benchllm").Keys);
        Assert.Contains("bc_llm_tune_threads_progress", Exports(app, "benchllm"));
    }
}
