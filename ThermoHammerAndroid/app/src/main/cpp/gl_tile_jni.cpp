// GL_EXT_multisampled_render_to_texture for the GPU scenes (engine/gpu/GlTile.kt): anti-aliasing that a tile-based
// GPU (Mali, Adreno, PowerVR, Xclipse) keeps on the chip and resolves as it writes each tile out, so the 4-sample
// image never goes to memory. Android's Java GL bindings don't carry the extension's two functions, so they are
// looked up here; nothing else is done here. Every call must come from a thread with a current GL context.
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define TILE_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_gpu_GlTileNative_##name

static PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC framebufferTexture2DMultisample = nullptr;
static PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC renderbufferStorageMultisample = nullptr;

/** Looks the two functions up; false when the driver has neither. */
TILE_JNI(jboolean, load)(JNIEnv*, jclass) {
    framebufferTexture2DMultisample = reinterpret_cast<PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC>(
        eglGetProcAddress("glFramebufferTexture2DMultisampleEXT"));
    renderbufferStorageMultisample = reinterpret_cast<PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC>(
        eglGetProcAddress("glRenderbufferStorageMultisampleEXT"));
    return framebufferTexture2DMultisample != nullptr && renderbufferStorageMultisample != nullptr;
}

TILE_JNI(void, framebufferTexture2DMultisample)(JNIEnv*, jclass, jint target, jint attachment, jint texTarget,
                                                jint texture, jint level, jint samples) {
    if (framebufferTexture2DMultisample)
        framebufferTexture2DMultisample(static_cast<GLenum>(target), static_cast<GLenum>(attachment),
                                        static_cast<GLenum>(texTarget), static_cast<GLuint>(texture), level, samples);
}

TILE_JNI(void, renderbufferStorageMultisample)(JNIEnv*, jclass, jint target, jint samples, jint format,
                                               jint width, jint height) {
    if (renderbufferStorageMultisample)
        renderbufferStorageMultisample(static_cast<GLenum>(target), samples, static_cast<GLenum>(format), width, height);
}
