#pragma once

// Thread-safe ring queue for native log messages that Java drains via
// nDrainNativeLogs(). Without this, CUDA errors and other native diagnostics
// vanish into stderr/stdout buffers and never surface in Minecraft's
// latest.log.
//
// Message format: optional single-char severity + '|' + body
//   "E|cuda init failed: out of memory"
//   "W|chunk dispatch retry"
//   plain "..." routes to INFO on the Java side.

#ifdef __cplusplus
extern "C" {
#endif

void pluto_log_queue_push(const char* msg);

// Returns a newly allocated array of newly allocated UTF-8 strings; caller
// owns both. *count receives the number of entries. The caller is expected
// to free each string with pluto_log_queue_free_string and the array with
// pluto_log_queue_free_array. Returns nullptr / *count = 0 if empty.
char** pluto_log_queue_drain(int* count);
void pluto_log_queue_free_string(char* s);
void pluto_log_queue_free_array(char** arr);

#ifdef __cplusplus
}
#endif
