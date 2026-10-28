using System.Numerics;
using System.Runtime.InteropServices;

namespace ThermoHammer.Engine.Gpu;

/// <summary>
/// A 4×4 matrix stored column by column, as the shaders' constant buffers and the phones' simd matrices hold it
/// (HLSL's default column_major packing: <c>mul(M, v)</c> is M·v). Direct3D's clip-space depth is 0..1 like Metal's, so
/// these are the iPhone app's GpuMath.swift, built the way the Android renderers build theirs with android.opengl.Matrix
/// (right-handed views looking down −Z).
/// </summary>
[StructLayout(LayoutKind.Sequential)]
public struct Mat4
{
    public Vector4 C0, C1, C2, C3;

    public Mat4(Vector4 c0, Vector4 c1, Vector4 c2, Vector4 c3) { C0 = c0; C1 = c1; C2 = c2; C3 = c3; }

    public static readonly Mat4 Identity = new(new(1, 0, 0, 0), new(0, 1, 0, 0), new(0, 0, 1, 0), new(0, 0, 0, 1));

    public static Vector4 operator *(Mat4 m, Vector4 v) => m.C0 * v.X + m.C1 * v.Y + m.C2 * v.Z + m.C3 * v.W;
    public static Mat4 operator *(Mat4 a, Mat4 b) => new(a * b.C0, a * b.C1, a * b.C2, a * b.C3);

    /// <summary>gluLookAt / Matrix.setLookAtM.</summary>
    public static Mat4 LookAt(Vector3 eye, Vector3 center, Vector3 up)
    {
        var f = Vector3.Normalize(center - eye);
        var s = Vector3.Normalize(Vector3.Cross(f, up));
        var u = Vector3.Cross(s, f);
        return new(new(s.X, u.X, -f.X, 0), new(s.Y, u.Y, -f.Y, 0), new(s.Z, u.Z, -f.Z, 0),
                   new(-Vector3.Dot(s, eye), -Vector3.Dot(u, eye), Vector3.Dot(f, eye), 1));
    }

    /// <summary>Matrix.perspectiveM with 0..1 depth.</summary>
    public static Mat4 Perspective(float fovyDeg, float aspect, float near, float far)
    {
        float f = 1 / MathF.Tan(fovyDeg * MathF.PI / 360);
        return new(new(f / aspect, 0, 0, 0), new(0, f, 0, 0), new(0, 0, far / (near - far), -1), new(0, 0, near * far / (near - far), 0));
    }

    /// <summary>Matrix.orthoM with 0..1 depth.</summary>
    public static Mat4 Ortho(float left, float right, float bottom, float top, float near, float far) =>
        new(new(2 / (right - left), 0, 0, 0), new(0, 2 / (top - bottom), 0, 0), new(0, 0, -1 / (far - near), 0),
            new(-(right + left) / (right - left), -(top + bottom) / (top - bottom), -near / (far - near), 1));

    /// <summary>Clip space → shadow-map texture space (x, y to 0..1 with y down, depth unchanged).</summary>
    public static readonly Mat4 ShadowBias = new(new(0.5f, 0, 0, 0), new(0, -0.5f, 0, 0), new(0, 0, 1, 0), new(0.5f, 0.5f, 0, 1));

    /// <summary>The world mirrored in a horizontal plane at height z.</summary>
    public static Mat4 MirrorZ(float z)
    {
        var m = Identity;
        m.C2.Z = -1;
        m.C3.Z = 2 * z;
        return m;
    }

    /// <summary>Row 0 of the matrix (the camera's right vector, for a view matrix).</summary>
    public readonly Vector3 Row0 => new(C0.X, C1.X, C2.X);
    /// <summary>Row 1 (the camera's up vector).</summary>
    public readonly Vector3 Row1 => new(C0.Y, C1.Y, C2.Y);

    /// <summary>The inverse. (The columns read as rows are the transpose; (Mᵀ)⁻¹ = (M⁻¹)ᵀ, whose rows are M⁻¹'s columns.)</summary>
    public readonly Mat4 Inverse()
    {
        var t = new Matrix4x4(C0.X, C0.Y, C0.Z, C0.W, C1.X, C1.Y, C1.Z, C1.W, C2.X, C2.Y, C2.Z, C2.W, C3.X, C3.Y, C3.Z, C3.W);
        Matrix4x4.Invert(t, out var r);
        return new(new(r.M11, r.M12, r.M13, r.M14), new(r.M21, r.M22, r.M23, r.M24), new(r.M31, r.M32, r.M33, r.M34), new(r.M41, r.M42, r.M43, r.M44));
    }
}

/// <summary>Little-endian reader over a scene file (the phones' ByteReader).</summary>
public sealed class ByteReader(byte[] data)
{
    private int at;
    public bool AtEnd => at == data.Length;
    public void Skip(int n) => at += n;
    public int I32() { int v = BitConverter.ToInt32(data, at); at += 4; return v; }
    public float F32() { float v = BitConverter.ToSingle(data, at); at += 4; return v; }
    public byte[] Bytes(int n) { var b = data.AsSpan(at, n).ToArray(); at += n; return b; }
    public string Ascii(int n) => System.Text.Encoding.UTF8.GetString(Bytes(n));
    public string Name16() { var b = Bytes(16); int len = Array.IndexOf(b, (byte)0); return System.Text.Encoding.UTF8.GetString(b, 0, len < 0 ? 16 : len); }
}

public sealed class SceneError(string message) : Exception(message);
