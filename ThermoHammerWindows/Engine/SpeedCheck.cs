namespace ThermoHammer.Engine;

/// <summary>Live state of a check: the second it's in, the last second's rate and every rate so far.</summary>
public sealed record SpeedProgress(int Second, double Rate, IReadOnlyList<double> Rates);

/// <summary>
/// FIND MY LOST SPEED — 30 seconds of the benchmark's multi-core CPU work (BenchCore's MULTI-CORE kernels, the same C++ as the
/// phones, one instance per logical processor, each thread going round the six kernels), counted in 1-second windows. What it
/// concludes is SpeedMath's; the latest result is kept (speed_check.json) so "check again" works after a trip to Settings, even
/// after the app was closed.
/// </summary>
public sealed class SpeedCheck
{
    private const int Stride = 16;              // counters 16 longs apart: no false sharing
    private volatile bool running;

    /// <summary>Runs on a thread of its own; [progress] is called on that thread once a second. Throws when a kernel fails.</summary>
    public Task<SpeedResult> Run(Action<SpeedProgress> progress, CancellationToken cancel)
    {
        var done = new TaskCompletionSource<SpeedResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var t = new Thread(() =>
        {
            try { done.SetResult(Measure(progress, cancel)); }
            catch (OperationCanceledException) { done.SetCanceled(cancel); }
            catch (Exception e) { done.SetException(e); }
        }) { IsBackground = true, Name = "speed-check", Priority = ThreadPriority.Highest };
        t.Start();
        return done.Task;
    }

    private SpeedResult Measure(Action<SpeedProgress> progress, CancellationToken cancel)
    {
        // the power state the check runs under (what the diagnosis is about); the thermal level is read at the end
        var tel = Telemetry.Shared.Current;
        int threads = Environment.ProcessorCount;
        int[] ids = CpuTest.Multi.KernelIds();
        var counters = new long[threads * Stride];
        Exception? failure = null;
        running = true;
        using var ready = new CountdownEvent(threads);
        using var go = new ManualResetEventSlim(false);
        var workers = new List<Thread>();
        for (int i = 0; i < threads; i++)
        {
            int slot = i * Stride;
            var w = new Thread(() =>
            {
                bool signalled = false;
                var kernels = new List<CoreKernel>();
                try
                {
                    // each kernel once before the clock starts (its buffers and tables built), as the benchmark warms up
                    foreach (int id in ids) { var k = new CoreKernel(id); kernels.Add(k); k.Run(); }
                    ready.Signal(); signalled = true;
                    go.Wait();
                    while (running)
                        foreach (var k in kernels)
                        {
                            k.Run();
                            Interlocked.Increment(ref counters[slot]);
                            if (!running) break;
                        }
                }
                catch (Exception e)
                {
                    Interlocked.CompareExchange(ref failure, e, null);
                    running = false;
                    if (!signalled) ready.Signal();
                }
                finally { foreach (var k in kernels) k.Dispose(); }
            }) { IsBackground = true, Name = $"speed-check-{i}", Priority = ThreadPriority.AboveNormal };
            w.Start();
            workers.Add(w);
        }

        var rates = new List<double>(SpeedMath.Seconds);
        try
        {
            ready.Wait(cancel);
            if (failure != null) throw failure;
            long Total() { long sum = 0; for (int i = 0; i < threads; i++) sum += Interlocked.Read(ref counters[i * Stride]); return sum; }
            go.Set();
            long t0 = Clock.Ns(), prevNs = t0, prevOps = 0;
            for (int s = 1; s <= SpeedMath.Seconds; s++)
            {
                // to the end of second s, by the clock (a late wake-up only moves the window's end, its rate stays right)
                while (Clock.Ns() < t0 + s * 1_000_000_000L)
                {
                    cancel.ThrowIfCancellationRequested();
                    if (failure != null) throw failure;
                    long left = t0 + s * 1_000_000_000L - Clock.Ns();
                    if (left > 2_000_000) Thread.Sleep((int)Math.Min(50, left / 1_000_000 - 1));
                }
                long now = Clock.Ns(), ops = Total();
                rates.Add((ops - prevOps) * 1e9 / Math.Max(1, now - prevNs));
                prevNs = now; prevOps = ops;
                progress(new SpeedProgress(s, rates[^1], rates.ToList()));
            }
        }
        finally
        {
            running = false;
            go.Set();
            foreach (var w in workers) w.Join();
        }
        if (failure != null) throw failure;
        var end = Telemetry.Shared.Current;
        return new SpeedResult(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0, rates, tel.BatterySaver, tel.OnAc, tel.HasBattery,
            tel.PowerMode, end.Thermal);
    }
}

/// <summary>The latest check, kept so the next one can be compared with it (for an hour — SpeedMath.Expiry).</summary>
public static class SpeedCheckStore
{
    private const string File = "speed_check.json";

    /// <summary>The PC Health verdict of the last benchmark on this PC, when it was below its model (in memory: no new request is made for it).</summary>
    public static HealthNote? LastHealth { get; set; }

    /// <summary>The latest check, while it's fresh enough to compare with.</summary>
    public static SpeedResult? Fresh()
    {
        var r = AppData.Load<SpeedResult>(File);
        return r != null && r.Rates.Count > 0 && SpeedMath.IsFresh(r, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0) ? r : null;
    }

    public static void Save(SpeedResult r) => AppData.Save(File, r);
}
