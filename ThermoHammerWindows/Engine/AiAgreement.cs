namespace ThermoHammer.Engine;

/// <summary>
/// How well an accelerator's answer agrees with the CPU's for the same model — Android's AiAgreement.
/// A GPU or an NPU that is fast and wrong is worth nothing: a run whose answer isn't the CPU's scores 0, and says why
/// (a Mali GPU and a MediaTek NPU were found returning results that had nothing in common with the CPU's and being
/// paid points for them). All three accelerators hand back the model's 8-bit tensors, so their bytes compare directly.
/// </summary>
public static class AiAgreement
{
    /// <summary>Outputs at least this correlated with the CPU's are the same answer.</summary>
    public const double Minimum = 0.9;

    /// <summary>
    /// A scored run's answer is wrong below this: it has nothing in common with the CPU's (the broken ones seen sat at
    /// 0.00–0.06). Between this and Minimum an answer is off but recognisably the same — a GPU runs these 8-bit models
    /// as a floating-point approximation, and object detection drifts to about 0.7 that way — and keeps its points,
    /// with the agreement shown in the report: the line can be drawn tighter once PCs have reported theirs.
    /// </summary>
    public const double WrongBelow = 0.5;

    public static bool IsWrong(double agreement) => agreement < WrongBelow;

    /// <summary>Pearson correlation of two outputs' bytes; two identical constant outputs agree fully.</summary>
    public static double Correlation(byte[] a, byte[] b)
    {
        if (a.Length != b.Length || a.Length == 0) return 0;
        double ma = 0, mb = 0;
        for (int i = 0; i < a.Length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.Length; mb /= a.Length;
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.Length; i++)
        {
            double x = a[i] - ma, y = b[i] - mb;
            ab += x * y; aa += x * x; bb += y * y;
        }
        if (aa == 0 && bb == 0) return a.AsSpan().SequenceEqual(b) ? 1 : 0;
        return aa > 0 && bb > 0 ? ab / Math.Sqrt(aa * bb) : 0;
    }

    /// <summary>The worst correlation among the outputs that mean something; 0 when they don't line up.</summary>
    public static double Of(AiTest test, byte[][] a, byte[][] b)
    {
        if (a.Length == 0 || a.Length != b.Length) return 0;
        // YOLOX's third output is a class index per candidate box: where nothing was found the argmax is noise
        int compared = test == AiTest.Detect ? Math.Min(2, a.Length) : a.Length;
        double worst = 1;
        for (int k = 0; k < compared; k++) worst = Math.Min(worst, Correlation(a[k], b[k]));
        return worst;
    }

    /// <summary>Why a GPU's or an NPU's run scores 0 although it ran, as the result and the report say it.</summary>
    public static string WrongAnswer(double ips, double agreement) => string.Create(System.Globalization.CultureInfo.InvariantCulture,
        $"wrong answer: its result doesn't match the CPU's (agreement {agreement:0.00}, a match is 1.00), so it scores 0 · it ran at {ips:0.00}/s");
}
