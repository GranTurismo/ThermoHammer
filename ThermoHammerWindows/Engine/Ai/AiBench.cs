using System.Text.Json;
using Microsoft.ML.OnnxRuntime;

namespace ThermoHammer.Engine.Ai;

// ─────────────────────────────────────────────────────────────────────────────
// AI BENCHMARK — the phones' six 8-bit models (Qualcomm AI Hub w8a8, the same .tflite files, converted op for op to ONNX by
// BenchmarkScene/build_onnx_models.py — the same int8 weights and activation quantization) on the CPU, the GPU and the NPU.
//
//   CPU: ONNX Runtime's CPU provider, 4 threads, the INT8 (QDQ) model — int8 kernels, as TFLite's XNNPACK on the phones.
//   GPU: DirectML on the PC's GPU, the model in float16 with its weights dequantized (the phones' GPU delegates do the same).
//   NPU: not in this build yet (see NpuProblem).
//   A run loads/compiles the model (not timed), warms up, then counts inferences for a fixed time. Outputs are turned back
//   into TFLite's 8-bit tensors afterwards, so the stage decodes every accelerator's result the same way.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class AiBench
{
    public const int WarmupRuns = 3, CpuThreads = 4;

    /// <summary>Inferences per second, and the outputs of the last inference (TFLite's 8-bit tensors).</summary>
    public sealed record Measured(double Ips, byte[][] Outputs);

    private sealed class Meta
    {
        public sealed class InputMeta { public string name { get; set; } = "x"; public long[] shape { get; set; } = []; public long[] tflite_shape { get; set; } = []; }
        public sealed class OutputMeta { public string name { get; set; } = ""; public double scale { get; set; } public int zero { get; set; } }
        public InputMeta input { get; set; } = new();
        public OutputMeta[] outputs { get; set; } = [];
    }

    private static string Dir => Path.Combine(AppContext.BaseDirectory, "assets", "ai");
    private static readonly Dictionary<string, Meta> metas = [];

    private static Meta MetaOf(AiTest test)
    {
        lock (metas)
        {
            if (metas.TryGetValue(test.Asset(), out var m)) return m;
            string path = Path.Combine(Dir, test.Asset() + ".json");
            if (!File.Exists(path)) throw new BenchException($"{test.Asset()}.json missing");
            return metas[test.Asset()] = JsonSerializer.Deserialize<Meta>(File.ReadAllText(path)) ?? throw new BenchException($"{test.Asset()}.json unreadable");
        }
    }

    /// <summary>
    /// Why the NPU can't run here, or null when it can. DirectML's NPU support is gone from recent ONNX Runtime builds: NPUs
    /// are reached through their vendors' providers (Qualcomm QNN, Intel OpenVINO, AMD Vitis AI), which this build doesn't carry yet.
    /// </summary>
    public static string? NpuProblem => "the NPU runtimes (Qualcomm QNN, Intel OpenVINO, AMD Vitis AI) aren't part of this build";

    public static string NpuRuntime => "";

    /// <summary>A failure as a person should read it: ONNX Runtime's messages start with a build-server path and a source line.</summary>
    public static string Explain(Exception e, AiAccel accel)
    {
        string m = e.Message.Trim();
        if (m.Contains("No devices detected", StringComparison.Ordinal))
            return accel == AiAccel.Gpu ? "this PC has no Direct3D 12 graphics card that DirectML can use" : "this PC has no processor of that kind DirectML can use";
        int at = m.IndexOf(".cc:", StringComparison.Ordinal);
        if (at > 0) { int sp = m.IndexOf(' ', at); if (sp > 0) m = m[(sp + 1)..]; }
        m = m.Replace("[ErrorCode:Fail] ", "").Replace("[ErrorCode:NotImplemented] ", "");
        int nl = m.IndexOf('\n');
        return nl > 0 ? m[..nl] : m;
    }

    private static InferenceSession Session(AiTest test, AiAccel accel)
    {
        string path = Path.Combine(Dir, test.Asset() + (accel == AiAccel.Cpu ? ".q.onnx" : ".f16.onnx"));
        if (!File.Exists(path)) throw new BenchException($"{Path.GetFileName(path)} missing");
        var o = new SessionOptions { GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL, LogSeverityLevel = OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR };
        if (accel == AiAccel.Cpu)
        {
            o.IntraOpNumThreads = CpuThreads;
            o.InterOpNumThreads = 1;
            o.ExecutionMode = ExecutionMode.ORT_SEQUENTIAL;
        }
        else
        {
            o.ExecutionMode = ExecutionMode.ORT_SEQUENTIAL;     // DirectML wants sequential execution and no memory pattern
            o.EnableMemoryPattern = false;
            // the fastest GPU of the PC (a laptop's discrete one over its integrated one)
            o.AppendExecutionProvider("DML", new Dictionary<string, string> { ["device_filter"] = "gpu", ["performance_preference"] = "high_performance" });
        }
        return new InferenceSession(path, o);
    }

    /// <summary>NHWC 8-bit → the models' planar NCHW input.</summary>
    private static byte[] Planar(byte[] nhwc, long[] nhwcShape)
    {
        if (nhwcShape.Length != 4) return nhwc;      // YAMNet's input is not an image
        int h = (int)nhwcShape[1], w = (int)nhwcShape[2], c = (int)nhwcShape[3];
        var o = new byte[nhwc.Length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                for (int k = 0; k < c; k++) o[k * h * w + y * w + x] = nhwc[(y * w + x) * c + k];
        return o;
    }

    public Measured Run(AiTest test, AiAccel accel, byte[] input, double seconds, Func<bool> isActive, Action<double, double> onProgress)
    {
        var meta = MetaOf(test);
        using var session = Session(test, accel);
        var data = Planar(input, meta.input.tflite_shape);
        long expected = meta.input.shape.Aggregate(1L, (a, b) => a * b);
        if (data.Length != expected) throw new BenchException($"input is {data.Length} bytes, the model wants {expected}");
        using var tensor = OrtValue.CreateTensorValueFromMemory(data, meta.input.shape);
        string[] inNames = [meta.input.name];
        string[] outNames = meta.outputs.Select(o => o.name).ToArray();
        using var runOptions = new RunOptions();
        IDisposableReadOnlyCollection<OrtValue>? last = null;
        try
        {
            for (int i = 0; i < WarmupRuns; i++) { last?.Dispose(); last = session.Run(runOptions, inNames, [tensor], outNames); }
            int n = 0;
            long t0 = Clock.Ns(), limit = (long)(seconds * 1e9), lastReport = t0;
            while (Clock.Ns() - t0 < limit && isActive())
            {
                last?.Dispose();
                last = session.Run(runOptions, inNames, [tensor], outNames);
                n++;
                long now = Clock.Ns();
                if (now - lastReport > 150_000_000)
                {
                    lastReport = now;
                    onProgress(Math.Min(1.0, (double)(now - t0) / limit), n * 1e9 / (now - t0));
                }
            }
            double ips = n * 1e9 / (Clock.Ns() - t0);
            if (n == 0) throw new BenchException("no inference finished");
            return new Measured(ips, Quantize(last!, meta));
        }
        finally { last?.Dispose(); }
    }

    /// <summary>The outputs back onto TFLite's 8-bit grid: q = round(value / scale) + zero (scale 0: the float holds the integer).</summary>
    private static byte[][] Quantize(IDisposableReadOnlyCollection<OrtValue> outputs, Meta meta)
    {
        var result = new byte[meta.outputs.Length][];
        for (int k = 0; k < result.Length; k++)
        {
            var values = outputs[k].GetTensorDataAsSpan<float>();
            var o = meta.outputs[k];
            var q = new byte[values.Length];
            for (int i = 0; i < q.Length; i++)
            {
                double v = o.scale > 0 ? Math.Round(values[i] / o.scale) + o.zero : Math.Round(values[i]);
                q[i] = (byte)Math.Clamp(v, 0, 255);
            }
            result[k] = q;
        }
        return result;
    }
}
