#pragma once

#include <cstdlib>

extern "C" void kmp_set_defaults(char const *defaults);

inline void configure_android_openmp() {
  // Disabling binding alone still lets libomp parse /proc/cpuinfo. Android
  // can take cores offline between libomp's CPU count and topology discovery,
  // tripping __kmp_create_affinity_none_places' CPU-count assertion. The flat
  // topology uses the runtime's existing count instead of that second snapshot.
  setenv("KMP_AFFINITY", "disabled", 1);
  setenv("KMP_TOPOLOGY_METHOD", "flat", 1);
  setenv("OMP_PROC_BIND", "false", 1);
  unsetenv("OMP_PLACES");
  // kmp_set_defaults expects SPACE-separated name=value pairs (the same format
  // as KMP_SETTINGS); the previous pipe-separated string was parsed as a single
  // unknown setting and silently ignored.
  kmp_set_defaults("KMP_AFFINITY=disabled KMP_TOPOLOGY_METHOD=flat");
}

inline void warmup_openmp_parallel_runtime() {
  // Force the one-time __kmp_parallel_initialize() (including affinity/topology
  // discovery) to happen HERE, at library-load time: the KMP_* settings above
  // are guaranteed applied and the CPU topology snapshot is still fresh. The
  // first real upscale used to be the first parallel region of the process
  // (minutes after start, after Android had hot-unplugged cores) and that is
  // where the affinity assertion fired. After this warmup the parallel runtime
  // is initialised once and never re-runs discovery mid-reading.
  volatile int touched = 0;
#pragma omp parallel
  {
    touched = 1;
  }
  (void)touched;
}
