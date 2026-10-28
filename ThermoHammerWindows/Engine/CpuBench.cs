using System.Diagnostics;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// CPU TESTS — BenchCore's kernels (the same C++ as on the phones), each warmed
// up untimed, then timed for its share of the test. Every call's checksum must
// equal the first one's.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>Live state for the stage.</summary>
public sealed record CpuProgress(string Kernel, double Fraction, double Rate, int Threads);

public sealed class BenchException(string message) : Exception(message);

/// <summary>One BenchCore kernel instance (one per thread).</summary>
internal sealed class CoreKernel : IDisposable
{
    public string Name { get; }
    private IntPtr handle;

    public CoreKernel(int id)
    {
        Name = BenchCore.KernelName(id);
        handle = BenchCore.bc_kernel_create(id);
    }

    public long Run()
    {
        long v = BenchCore.bc_kernel_run(handle, out int ok);
        if (ok == 0) throw new BenchException($"{Name}: {BenchCore.KernelError(handle)}");
        return v;
    }

    public void Dispose()
    {
        if (handle != IntPtr.Zero) BenchCore.bc_kernel_destroy(handle);
        handle = IntPtr.Zero;
    }
}

internal static class Clock
{
    public static long Ns() => (long)(Stopwatch.GetTimestamp() * (1e9 / Stopwatch.Frequency));
}

public sealed class CpuBench
{
    private const double WarmupS = 0.3;
    public int Threads { get; } = Environment.ProcessorCount;

    public CpuTestRun Run(CpuTest test, Func<bool> isActive, Action<CpuProgress> onProgress)
    {
        var rates = new List<(string, double)>();
        var ids = test.KernelIds();
        var labels = test.Labels();
        for (int k = 0; k < ids.Length; k++)
        {
            if (!isActive()) break;
            string name = labels[k];
            int kk = k;
            double rate;
            if (test.MultiCore())
            {
                rate = Multi(ids[k], test.SecondsPerKernel(), isActive,
                    (done, r) => onProgress(new CpuProgress(name, (kk + done) / ids.Length, r, Threads)));
            }
            else
            {
                using var kernel = new CoreKernel(ids[k]);
                rate = Single(kernel, test.SecondsPerKernel(), isActive,
                    (done, r) => onProgress(new CpuProgress(name, (kk + done) / ids.Length, r, 1)));
            }
            rates.Add((name, rate));
        }
        return new CpuTestRun(test, rates);
    }

    private static double Single(CoreKernel kernel, double seconds, Func<bool> isActive, Action<double, double> report)
    {
        long expected = kernel.Run();
        long t = Clock.Ns();
        while ((Clock.Ns() - t) / 1e9 < WarmupS)
            if (kernel.Run() != expected) throw new BenchException($"{kernel.Name} result changed");
        long limit = (long)(seconds * 1e9);
        int n = 0;
        t = Clock.Ns();
        long lastReport = t;
        while (Clock.Ns() - t < limit && isActive())
        {
            if (kernel.Run() != expected) throw new BenchException($"{kernel.Name} result changed");
            n++;
            long now = Clock.Ns();
            if (now - lastReport > 150_000_000)
            {
                lastReport = now;
                report((double)(now - t) / limit, n * 1e9 / (now - t));
            }
        }
        return n * 1e9 / (Clock.Ns() - t);
    }

    /// <summary>Every core runs its own instance at once; the rate is all of them together.</summary>
    private double Multi(int id, double seconds, Func<bool> isActive, Action<double, double> report)
    {
        long ops = 0;
        Exception? failure = null;
        long stopAt = long.MaxValue;
        using var ready = new CountdownEvent(Threads);
        using var go = new ManualResetEventSlim(false);
        var workers = new List<Thread>();
        for (int i = 0; i < Threads; i++)
        {
            var w = new Thread(() =>
            {
                bool signalled = false;
                try
                {
                    using var kernel = new CoreKernel(id);
                    long expected = kernel.Run();
                    long w0 = Clock.Ns();
                    while ((Clock.Ns() - w0) / 1e9 < WarmupS) kernel.Run();
                    ready.Signal(); signalled = true;
                    go.Wait();
                    while (Clock.Ns() < Volatile.Read(ref stopAt))
                    {
                        if (kernel.Run() != expected) throw new BenchException($"{kernel.Name} result changed");
                        Interlocked.Increment(ref ops);
                    }
                }
                catch (Exception e)
                {
                    Interlocked.CompareExchange(ref failure, e, null);
                    if (!signalled) ready.Signal();
                }
            }) { IsBackground = true, Priority = ThreadPriority.AboveNormal };
            w.Start();
            workers.Add(w);
        }
        ready.Wait();
        long limit = (long)(seconds * 1e9);
        long t0 = Clock.Ns();
        Volatile.Write(ref stopAt, t0 + limit);
        go.Set();
        while (Clock.Ns() - t0 < limit && isActive())
        {
            Thread.Sleep(150);
            long el = Clock.Ns() - t0;
            report(Math.Min(1, (double)el / limit), Interlocked.Read(ref ops) * 1e9 / el);
        }
        Volatile.Write(ref stopAt, 0);
        foreach (var w in workers) w.Join();
        if (failure != null) throw failure;
        return Interlocked.Read(ref ops) * 1e9 / (Clock.Ns() - t0);
    }
}

/// <summary>Is the machine quiet?</summary>
public static class QuietProbe
{
    /// <summary>
    /// Share of the CPU this app gets right now, 0..1: one spinning thread per logical processor, each timing its own
    /// CPU time against the wall clock (the first 150 ms skipped while parked cores wake). ≈1 on a quiet PC.
    /// </summary>
    public static float CpuShare()
    {
        int n = Environment.ProcessorCount;
        var shares = Enumerable.Repeat(1.0, n).ToArray();
        using var go = new ManualResetEventSlim(false);
        var threads = new List<Thread>();
        for (int i = 0; i < n; i++)
        {
            int index = i;
            var t = new Thread(() =>
            {
                IntPtr me = Win32.OpenThread(Win32.ThreadQueryLimitedInformation, false, Win32.GetCurrentThreadId());
                go.Wait();
                long t0 = Clock.Ns();
                ulong x = 0;
                while (Clock.Ns() - t0 < 150_000_000) x = x * 31 + 7;
                long w1 = Clock.Ns(), c1 = Win32.ThreadCpuTime(me);
                while (Clock.Ns() - t0 < 550_000_000) x = x * 31 + 7;
                long w2 = Clock.Ns(), c2 = Win32.ThreadCpuTime(me);
                if (c2 > c1) shares[index] = Math.Min(1, (c2 - c1) * 100.0 / (w2 - w1));
                if (x == 42) Debug.WriteLine("");   // keep the loop
                Win32.CloseHandle(me);
            }) { IsBackground = true };
            t.Start();
            threads.Add(t);
        }
        go.Set();
        foreach (var t in threads) t.Join();
        return (float)Math.Clamp(shares.Average(), 0, 1);
    }
}
