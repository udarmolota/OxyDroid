#ifndef OXYDROID_PERF_H
#define OXYDROID_PERF_H

/** Called after every vkQueuePresentKHR of the game. Cheap: safe on the render thread. Starts the
 *  10-second telemetry thread (OXYPERF log lines) and the ADPF hint session on the first frame. */
void oxy_perf_frame(void);

#endif // OXYDROID_PERF_H
