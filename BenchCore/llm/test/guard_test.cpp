// Host test of bench_guard.h:   clang++ -std=c++17 -I BenchCore/llm BenchCore/llm/test/guard_test.cpp -o /tmp/guard_test && /tmp/guard_test
// It stands in for the Vulkan backend with calls that throw from inside C-linkage frames, the way
// vk::DeviceLostError left llama_decode in Play's crash report for 2.1.1.
#include <cstdio>
#include <stdexcept>
#include <string>
#include <system_error>

#include "bench_guard.h"

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); failures++; } } while (0)

struct DeviceLost : std::system_error {                  // shaped like vk::DeviceLostError (a std::system_error)
    DeviceLost() : std::system_error(std::make_error_code(std::errc::io_error), "vk::Queue::submit: ErrorDeviceLost") {}
};

extern "C" void backend_submit_that_loses_the_device() { throw DeviceLost(); }
extern "C" int decode_through_c_frames(int* out) { backend_submit_that_loses_the_device(); *out = 1; return 0; }
struct NotStd { int code; };

int main() {
    std::string what;

    int result = 0;
    CHECK(bc_guard::call([&] { result = 42; }, what));
    CHECK(result == 42 && what.empty());                 // a call that returns is untouched

    int out = 0;
    CHECK(!bc_guard::call([&] { decode_through_c_frames(&out); }, what));
    CHECK(out == 0);                                     // nothing after the throw ran
    CHECK(what.find("ErrorDeviceLost") != std::string::npos);

    what.clear();
    CHECK(!bc_guard::call([&] { throw std::runtime_error("allocation failed"); }, what));
    CHECK(what == "allocation failed");

    what.clear();
    CHECK(!bc_guard::call([&] { throw NotStd{7}; }, what));
    CHECK(!what.empty());                                // something that isn't a std::exception is still caught and described

    what.clear();
    CHECK(!bc_guard::call([&] { throw std::runtime_error(""); }, what));
    CHECK(what == "an exception without a message");

    // the way llm.cpp uses it: a failed call leaves the process running and the caller able to carry on
    int calls = 0;
    for (int i = 0; i < 3; i++) if (!bc_guard::call([&] { calls++; throw DeviceLost(); }, what)) continue;
    CHECK(calls == 3);

    std::printf(failures ? "%d FAILED\n" : "all guard tests passed\n", failures);
    return failures ? 1 : 0;
}
