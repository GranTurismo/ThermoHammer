namespace ThermoHammer.Engine.Gpu;

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY LIGHT GRID — world-space light binning for GPU Test 2, a port of the
// phones' NeonLightGrid.
//
//   ~870 static lights (signs, lamps, shop windows) plus a few hundred moving
//   ones (car lamps, trains, the searchlight), binned into 4 m × 4 m ground
//   cells. A cell lists its moving lights first, then its static ones, each run
//   ranked by what the light adds in that cell. Static lights are binned once;
//   moving ones are re-binned and merged in each frame.
//
//   GPU layout (uploaded by the renderer into buffers):
//     lights  4 float4 per light: (x y z radius) (r g b type) (dir xyz, cos outer) (group, 0, 0, 0)
//             type 0 point · 1 spot · 2 shadowed spot (the searchlight)
//     cells   offset << 12 | moving << 6 | count, cellsX × cellsY (counts ≤ 48)
//     indices u16
// ─────────────────────────────────────────────────────────────────────────────

public sealed class NeonLightGrid
{
    public const float X0 = -256, Y0 = -320, Cell = 4;
    public const int CellsX = 128, CellsY = 336;
    public const int MaxPerCell = 48;
    public const int LightFloats = 16;

    public readonly int StaticCount;
    public readonly int Capacity;
    public readonly float[] Lights;
    public readonly uint[] Cells;
    private readonly int[] staticCells;          // offset << 8 | count into staticIdx
    private readonly ushort[] staticIdx;
    private readonly float[] staticImp;
    private readonly int[] dynCount, dynStart, dynFill;
    private ushort[] dynIdx;
    private float[] dynImp;
    private readonly bool[] dynPinned;
    private int dynamicCount;
    private readonly int maxDynamic;
    public ushort[] Indices { get; private set; }
    public int IndexCount { get; private set; }

    public int LightCount => StaticCount + dynamicCount;

    public NeonLightGrid(NeonCityAsset.Table staticLights, int maxDynamic)
    {
        this.maxDynamic = maxDynamic;
        StaticCount = staticLights.Rows;
        Capacity = StaticCount + maxDynamic;
        Lights = new float[Capacity * LightFloats];
        int nCells = CellsX * CellsY;
        Cells = new uint[nCells];
        staticCells = new int[nCells];
        dynCount = new int[nCells];
        dynStart = new int[nCells + 1];
        dynFill = new int[nCells];
        dynIdx = new ushort[maxDynamic * 64];
        dynImp = new float[maxDynamic * 64];
        dynPinned = new bool[maxDynamic];
        Indices = new ushort[2048 * 64];
        for (int i = 0; i < StaticCount; i++)
            for (int c = 0; c < 13; c++) Lights[i * LightFloats + c] = staticLights[i, c];   // same column order as the table
        // static binning: two passes (count, fill), then each cell ranked; Build() caps the merged lists
        var count = new int[nCells];
        for (int i = 0; i < StaticCount; i++) ForCells(i, c => count[c]++);
        int total = 0;
        for (int c = 0; c < nCells; c++)
        {
            staticCells[c] = (total << 8) | Math.Min(count[c], 255);
            total += Math.Min(count[c], 255);
        }
        staticIdx = new ushort[total];
        staticImp = new float[total];
        var fill = new int[nCells];
        for (int i = 0; i < StaticCount; i++)
        {
            int light = i;
            ForCells(light, cell =>
            {
                if (fill[cell] < (staticCells[cell] & 255))
                {
                    int at = (staticCells[cell] >> 8) + fill[cell];
                    fill[cell]++;
                    staticIdx[at] = (ushort)light;
                    staticImp[at] = Importance(light, cell);
                }
            });
        }
        for (int c = 0; c < nCells; c++)
        {
            int from = staticCells[c] >> 8;
            SortByImportance(staticIdx, staticImp, from, from + (staticCells[c] & 255));
        }
    }

    // ── per frame — moving lights ──

    public bool Add(float x, float y, float z, float radius, float r, float g, float b, int type = 0,
        float dx = 0, float dy = 0, float dz = -1, float cosOuter = 0, bool pinned = false)
    {
        if (dynamicCount >= maxDynamic) return false;
        dynPinned[dynamicCount] = pinned;
        int o = (StaticCount + dynamicCount) * LightFloats;
        Lights[o] = x; Lights[o + 1] = y; Lights[o + 2] = z; Lights[o + 3] = radius;
        Lights[o + 4] = r; Lights[o + 5] = g; Lights[o + 6] = b; Lights[o + 7] = type;
        Lights[o + 8] = dx; Lights[o + 9] = dy; Lights[o + 10] = dz; Lights[o + 11] = cosOuter;
        Lights[o + 12] = 0;
        dynamicCount++;
        return true;
    }

    /// <summary>Ranks this frame's moving lights per cell and puts them ahead of the ranked static lists.</summary>
    public void Build()
    {
        int nCells = Cells.Length;
        for (int c = 0; c < nCells; c++) { dynCount[c] = 0; dynFill[c] = 0; }
        for (int k = 0; k < dynamicCount; k++) ForCells(StaticCount + k, c => dynCount[c]++);
        int acc = 0;
        for (int c = 0; c < nCells; c++) { dynStart[c] = acc; acc += dynCount[c]; }
        dynStart[nCells] = acc;
        if (acc > dynIdx.Length) { dynIdx = new ushort[acc * 2]; dynImp = new float[acc * 2]; }
        for (int k = 0; k < dynamicCount; k++)
        {
            int light = StaticCount + k;
            ForCells(light, cell =>
            {
                int at = dynStart[cell] + dynFill[cell];
                dynFill[cell]++;
                dynIdx[at] = (ushort)light;
                dynImp[at] = Importance(light, cell);
            });
        }
        int needed = staticIdx.Length + acc;
        if (needed > Indices.Length) Indices = new ushort[(needed / 2048 + 8) * 2048];
        int w = 0;
        for (int c = 0; c < nCells; c++)
        {
            int start = w, d = dynStart[c];
            SortByImportance(dynIdx, dynImp, d, d + dynFill[c]);
            int nd = Math.Min(dynFill[c], MaxPerCell);
            for (int k = 0; k < nd; k++) Indices[w + k] = dynIdx[d + k];
            w += nd;
            int ns = Math.Min(staticCells[c] & 255, MaxPerCell - nd);
            int s0 = staticCells[c] >> 8;
            for (int k = 0; k < ns; k++) Indices[w + k] = staticIdx[s0 + k];
            w += ns;
            Cells[c] = (uint)((start << 12) | (nd << 6) | (w - start));
        }
        IndexCount = w;
    }

    /// <summary>
    /// This frame's moving lights from the simulation — the police searchlight (shadowed spot) and its strobes, every
    /// car's headlights and tail lights, lit train cars — then the merged grid.
    /// </summary>
    public void SetMovingLights(NeonCitySim sim, float time, float searchCos)
    {
        dynamicCount = 0;
        var p = sim.SearchPos; var a = sim.SearchAim;
        float dx = a[0] - p[0], dy = a[1] - p[1], dz = a[2] - p[2];
        float dl = MathF.Sqrt(dx * dx + dy * dy + dz * dz);
        dx /= dl; dy /= dl; dz /= dl;
        Add(p[0], p[1], p[2], 90, 0.85f * 38, 0.92f * 38, 1.0f * 38, type: 2, dx: dx, dy: dy, dz: dz, cosOuter: searchCos, pinned: true);
        float strobe = sim.Groups[31];
        bool red = (time * 2.2f) % 1 < 0.3f;
        Add(p[0], p[1], p[2] + 0.4f, 18, red ? 6 * strobe : 0, 0, red ? 0 : 7 * strobe, pinned: true);
        var lamps = sim.CarLamps;
        for (int i = 0; i < sim.CarCount; i++)
        {
            int o = i * 7;
            float x = lamps[o], y = lamps[o + 1], fx = lamps[o + 3], fy = lamps[o + 4];
            float half = lamps[o + 5] / 2;
            bool brake = lamps[o + 6] > 0.5f;
            Add(x + fx * (half + 0.3f), y + fy * (half + 0.3f), 0.75f, 30, 5, 4.6f, 4.1f, type: 1, dx: fx * 0.985f, dy: fy * 0.985f, dz: -0.17f, cosOuter: 0.85f);
            float tail = brake ? 2.2f : 0.7f;
            Add(x - fx * (half + 0.2f), y - fy * (half + 0.2f), 0.85f, brake ? 8 : 5, tail, tail * 0.04f, tail * 0.02f);
        }
        var tl = sim.TrainLamps;
        for (int i = 0; i < sim.TrainLampCount; i++) Add(tl[i * 3], tl[i * 3 + 1], tl[i * 3 + 2], 14, 2.5f, 2.25f, 1.9f);
        Build();
    }

    /// <summary>
    /// How much `light` can add anywhere in `cell`, street level to 6 m up: its brightness × the shaders' falloff (and cone)
    /// at the cell's point nearest the light, or the cell's centre if that is brighter. Pinned lights outrank everything.
    /// </summary>
    private float Importance(int light, int cell)
    {
        if (light >= StaticCount && dynPinned[light - StaticCount]) return float.MaxValue;
        int o = light * LightFloats;
        float x0 = X0 + (cell % CellsX) * Cell;
        float y0 = Y0 + (cell / CellsX) * Cell;
        float z = Math.Min(Math.Max(Lights[o + 2], 0), 6);
        float near = Falloff(o, Math.Min(Math.Max(Lights[o], x0), x0 + Cell), Math.Min(Math.Max(Lights[o + 1], y0), y0 + Cell), z);
        float mid = Lights[o + 7] >= 0.5f ? Falloff(o, x0 + Cell / 2, y0 + Cell / 2, z) : 0;
        return (0.2126f * Lights[o + 4] + 0.7152f * Lights[o + 5] + 0.0722f * Lights[o + 6]) * Math.Max(near, mid);
    }

    /// <summary>lightAtten() in the shaders, without groups or the searchlight's shadow.</summary>
    private float Falloff(int o, float px, float py, float pz)
    {
        float dx = px - Lights[o], dy = py - Lights[o + 1], dz = pz - Lights[o + 2];
        float d2 = dx * dx + dy * dy + dz * dz;
        float r2 = Lights[o + 3] * Lights[o + 3];
        if (d2 >= r2) return 0;
        float f = d2 / r2;
        float att = (1 - f) * (1 - f) / (d2 + 1);
        if (Lights[o + 7] >= 0.5f && d2 > 1e-6f)
        {
            float outer = Lights[o + 11];
            float c = (dx * Lights[o + 8] + dy * Lights[o + 9] + dz * Lights[o + 10]) / MathF.Sqrt(d2);
            float u = Math.Min(Math.Max((c - outer) / ((1 - outer) * 0.45f), 0), 1);
            att *= u * u * (3 - 2 * u);
        }
        return att;
    }

    /// <summary>Insertion sort of one cell's entries, most important first — the lists are short.</summary>
    private static void SortByImportance(ushort[] idx, float[] imp, int from, int to)
    {
        for (int i = from + 1; i < to; i++)
        {
            float v = imp[i]; ushort id = idx[i];
            int j = i - 1;
            while (j >= from && imp[j] < v) { imp[j + 1] = imp[j]; idx[j + 1] = idx[j]; j--; }
            imp[j + 1] = v; idx[j + 1] = id;
        }
    }

    /// <summary>Calls `f` for every cell a light can reach (spots: the cone's footprint box).</summary>
    private void ForCells(int light, Action<int> f)
    {
        int o = light * LightFloats;
        float x = Lights[o], y = Lights[o + 1], z = Lights[o + 2], r = Lights[o + 3];
        float x0 = x - r, x1 = x + r, y0 = y - r, y1 = y + r;
        if (Lights[o + 7] >= 0.5f)
        {
            // spot: bound the cone to where it can light — reach r along dir, spread by the cone's tangent
            float dx = Lights[o + 8], dy = Lights[o + 9], dz = Lights[o + 10];
            float c = Math.Min(Math.Max(Lights[o + 11], 0.05f), 0.9999f);
            float spread = r * MathF.Sqrt(1 - c * c) / c;
            float reach = dz < -0.2f ? Math.Min(r, z / -dz) : r;
            float ex = x + dx * reach, ey = y + dy * reach;
            x0 = Math.Max(Math.Min(x, ex) - spread, x - r); x1 = Math.Min(Math.Max(x, ex) + spread, x + r);
            y0 = Math.Max(Math.Min(y, ey) - spread, y - r); y1 = Math.Min(Math.Max(y, ey) + spread, y + r);
        }
        int cx0 = Math.Max(0, (int)MathF.Floor((x0 - X0) / Cell)), cx1 = Math.Min(CellsX - 1, (int)MathF.Floor((x1 - X0) / Cell));
        int cy0 = Math.Max(0, (int)MathF.Floor((y0 - Y0) / Cell)), cy1 = Math.Min(CellsY - 1, (int)MathF.Floor((y1 - Y0) / Cell));
        if (cx0 > cx1 || cy0 > cy1) return;
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++)
            {
                // skip cells whose nearest point is outside the radius
                float nx = Math.Min(Math.Max(x, X0 + cx * Cell), X0 + (cx + 1) * Cell) - x;
                float ny = Math.Min(Math.Max(y, Y0 + cy * Cell), Y0 + (cy + 1) * Cell) - y;
                if (nx * nx + ny * ny <= r * r) f(cy * CellsX + cx);
            }
    }
}
