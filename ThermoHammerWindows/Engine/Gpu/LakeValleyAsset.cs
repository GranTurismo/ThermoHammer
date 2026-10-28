using System.Numerics;

namespace ThermoHammer.Engine.Gpu;

/// <summary>
/// Lake Valley scene asset, parsed from the bundled lake_valley.bin (written by BenchmarkScene/build_lake_valley.py,
/// which documents the byte layout) — the same file the Android and iPhone apps render.
/// </summary>
public sealed class LakeValleyAsset
{
    public sealed record Mesh(string Name, int VertexCount, int IndexCount, int PolarSegments, int PolarRings, float Height, byte[] Vertices, byte[]? Indices)
    {
        /// <summary>Polar meshes have no index data — their indices are built on the device.</summary>
        public bool IsPolar => PolarSegments > 0;
    }

    /// <summary>Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}.</summary>
    public sealed record Group(string Name, int Mesh, int Count, float[] Transforms);

    public const int VertexBytes = 20;     // f32 xyz · i8 normal ×4 · u8 sRGB rgb + class

    public float LakeCenterX, LakeCenterY, LakeRadiusX, LakeRadiusY, WaterZ;
    public Dictionary<string, Vector3> Anchors = [];
    public List<Mesh> Meshes = [];
    public List<Group> Groups = [];

    public LakeValleyAsset(byte[] data)
    {
        var r = new ByteReader(data);
        if (r.Ascii(4) != "LVSC") throw new SceneError("not a Lake Valley scene");
        if (r.I32() != 1) throw new SceneError("Lake Valley asset version");
        LakeCenterX = r.F32(); LakeCenterY = r.F32(); LakeRadiusX = r.F32(); LakeRadiusY = r.F32(); WaterZ = r.F32();
        for (int i = r.I32(); i > 0; i--) { var n = r.Name16(); Anchors[n] = new Vector3(r.F32(), r.F32(), r.F32()); }
        for (int i = r.I32(); i > 0; i--)
        {
            var name = r.Name16();
            int vc = r.I32(), ic = r.I32(), seg = r.I32(), rings = r.I32();
            float h = r.F32();
            var v = r.Bytes(vc * VertexBytes);
            var idx = ic > 0 ? r.Bytes(ic * 2) : null;
            if (ic % 2 == 1) r.Skip(2);   // u16 run padded to 4 bytes
            Meshes.Add(new Mesh(name, vc, ic, seg, rings, h, v, idx));
        }
        for (int i = r.I32(); i > 0; i--)
        {
            var name = r.Name16();
            int mesh = r.I32(), count = r.I32();
            var t = new float[count * 9];
            for (int k = 0; k < t.Length; k++) t[k] = r.F32();
            Groups.Add(new Group(name, mesh, count, t));
        }
        if (!r.AtEnd) throw new SceneError("Lake Valley asset has trailing bytes");
    }

    public int MeshIndex(string name) => Math.Max(0, Meshes.FindIndex(m => m.Name == name));
}
