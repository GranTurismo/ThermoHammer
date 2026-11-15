// Turns a C++ exception into a result. llama.cpp's Vulkan backend reports a driver that loses its device (and some
// allocation failures) by THROWING (vk::DeviceLostError: "vk::Queue::submit: ErrorDeviceLost") from deep inside
// llama_decode / llama_synchronize / llama_get_logits_ith. Nothing above those catches it, and an exception that
// leaves the JNI call ends the process: Play's crash report for 2.1.1 (Vivo T3 5G) was exactly this. Every call that
// can reach a GPU backend runs through here, so the test fails the way it fails for any other GPU problem and the
// CPU result stands.
#pragma once

#include <exception>
#include <string>

namespace bc_guard {

/** Runs body(). True when it returned; false when it threw, with a description of what was thrown in `what`. */
template <class F>
bool call(F&& body, std::string& what) {
    try {
        body();
        return true;
    } catch (const std::exception& e) {
        what = e.what();
    } catch (...) {
        what = "an exception that was not a std::exception";
    }
    if (what.empty()) what = "an exception without a message";
    return false;
}

}  // namespace bc_guard
