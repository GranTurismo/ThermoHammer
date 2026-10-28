using System.IO.Compression;

namespace ThermoHammer.Engine.Gpu;

/// <summary>A minimal PNG encoder for the GPU checks (no window system or imaging library needed).</summary>
public static class PngWriter
{
    private static readonly uint[] Table = Enumerable.Range(0, 256).Select(n =>
    {
        uint c = (uint)n;
        for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        return c;
    }).ToArray();

    private static uint Crc(ReadOnlySpan<byte> type, ReadOnlySpan<byte> data)
    {
        uint c = 0xFFFFFFFF;
        foreach (byte b in type) c = Table[(c ^ b) & 0xFF] ^ (c >> 8);
        foreach (byte b in data) c = Table[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFF;
    }

    private static void Chunk(Stream s, string type, ReadOnlySpan<byte> data)
    {
        Span<byte> n = stackalloc byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(n, (uint)data.Length);
        s.Write(n);
        var t = System.Text.Encoding.ASCII.GetBytes(type);
        s.Write(t);
        s.Write(data);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(n, Crc(t, data));
        s.Write(n);
    }

    /// <summary>`rgba`: width × height × 4 bytes, rows top to bottom.</summary>
    public static void Save(string path, int width, int height, byte[] rgba)
    {
        using var raw = new MemoryStream();
        for (int y = 0; y < height; y++)
        {
            raw.WriteByte(0);
            raw.Write(rgba, y * width * 4, width * 4);
        }
        using var packed = new MemoryStream();
        using (var z = new ZLibStream(packed, CompressionLevel.Fastest, true)) z.Write(raw.GetBuffer(), 0, (int)raw.Length);
        using var f = File.Create(path);
        f.Write([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
        Span<byte> head = stackalloc byte[13];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(head, (uint)width);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(head[4..], (uint)height);
        head[8] = 8; head[9] = 6; head[10] = 0; head[11] = 0; head[12] = 0;
        Chunk(f, "IHDR", head);
        Chunk(f, "IDAT", packed.GetBuffer().AsSpan(0, (int)packed.Length));
        Chunk(f, "IEND", []);
    }
}
