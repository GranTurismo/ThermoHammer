import simd

// Matrices for Metal (clip-space depth 0..1), built the way the Android renderers build theirs with
// android.opengl.Matrix (right-handed views looking down −Z).

typealias Mat4 = simd_float4x4

extension simd_float4x4 {
    static let identity = matrix_identity_float4x4

    /** gluLookAt / Matrix.setLookAtM. */
    static func lookAt(eye: SIMD3<Float>, center: SIMD3<Float>, up: SIMD3<Float>) -> Mat4 {
        let f = simd_normalize(center - eye)
        let s = simd_normalize(simd_cross(f, up))
        let u = simd_cross(s, f)
        return Mat4(columns: (SIMD4(s.x, u.x, -f.x, 0), SIMD4(s.y, u.y, -f.y, 0), SIMD4(s.z, u.z, -f.z, 0),
                              SIMD4(-simd_dot(s, eye), -simd_dot(u, eye), simd_dot(f, eye), 1)))
    }

    /** Matrix.perspectiveM with Metal's 0..1 depth. */
    static func perspective(fovyDeg: Float, aspect: Float, near: Float, far: Float) -> Mat4 {
        let f = 1 / tan(fovyDeg * .pi / 360)
        return Mat4(columns: (SIMD4(f / aspect, 0, 0, 0), SIMD4(0, f, 0, 0), SIMD4(0, 0, far / (near - far), -1),
                              SIMD4(0, 0, near * far / (near - far), 0)))
    }

    /** Matrix.orthoM with Metal's 0..1 depth. */
    static func ortho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float) -> Mat4 {
        Mat4(columns: (SIMD4(2 / (right - left), 0, 0, 0), SIMD4(0, 2 / (top - bottom), 0, 0), SIMD4(0, 0, -1 / (far - near), 0),
                       SIMD4(-(right + left) / (right - left), -(top + bottom) / (top - bottom), -near / (far - near), 1)))
    }

    /** Clip space → shadow-map texture space (x, y to 0..1 with y down, depth unchanged). */
    static let shadowBias = Mat4(columns: (SIMD4(0.5, 0, 0, 0), SIMD4(0, -0.5, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(0.5, 0.5, 0, 1)))

    /** The world mirrored in a horizontal plane at height z. */
    static func mirrorZ(_ z: Float) -> Mat4 {
        var m = Mat4.identity
        m.columns.2.z = -1
        m.columns.3.z = 2 * z
        return m
    }

    /** Row 0 of the view matrix (the camera's right vector). */
    var row0: SIMD3<Float> { SIMD3(columns.0.x, columns.1.x, columns.2.x) }
    /** Row 1 (the camera's up vector). */
    var row1: SIMD3<Float> { SIMD3(columns.0.y, columns.1.y, columns.2.y) }
}
