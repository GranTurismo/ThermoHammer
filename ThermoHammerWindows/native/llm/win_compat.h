// Force-included into BenchCore/llm/llm.cpp (which is written for POSIX): what MinGW's C library lacks.
#pragma once
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif
static inline int setenv(const char* name, const char* value, int overwrite) {
    if (!overwrite && getenv(name)) return 0;
    return _putenv_s(name, value);
}
#ifdef __cplusplus
}
#endif
