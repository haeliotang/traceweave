/* Isolated pressure: admission/running cap 768 MiB, cached LMK cap 1024 MiB.
 * Admission uses 32/256, running uses 16/208 and LMK uses 16/160 MiB steps/kernel floor.
 * Floors check kernel MemAvailable before each allocation, not Android MemoryInfo.
 * The app samples Android MemoryInfo between steps; EOF or timeout releases all.
 */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>

static long available_kb(void) {
    FILE *file = fopen("/proc/meminfo", "r");
    if (!file) return -1;
    char line[256]; long value = -1;
    while (fgets(line, sizeof line, file))
        if (sscanf(line, "MemAvailable: %ld kB", &value) == 1) break;
    fclose(file); return value;
}
int main(int argc, char **argv) {
    int running = argc == 2 && !strcmp(argv[1], "running");
    int lmk = argc == 2 && !strcmp(argv[1], "lmk");
    if (argc != 1 && !running && !lmk) return 2;
    int step_mib = (running || lmk) ? 16 : 32;
    int floor_mib = lmk ? 160 : running ? 208 : 256;
    int limit = (lmk ? 1024 : 768) / step_mib;
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() == 1) return 2;
    alarm(120);
    FILE *priority = fopen("/proc/self/oom_score_adj", "w");
    if (!priority) return 2;
    int written = fprintf(priority, "%d\n", lmk ? 800 : 1000);
    int closed = fclose(priority);
    if (written < 0 || closed) return 2;
    void *blocks[64]; int count = 0;
    const size_t chunk = (size_t)step_mib * 1024 * 1024;
    uint32_t random = 0x735abc19;
    char command[16];
    for (;;) {
        long available = available_kb();
        printf("{\"allocated_mib\":%d,\"available_kb\":%ld}\n", count * step_mib, available);
        fflush(stdout);
        if (!fgets(command, sizeof command, stdin)) break;
        if (!strcmp(command, "TOUCH\n")) {
            struct timespec started, current;
            clock_gettime(CLOCK_MONOTONIC, &started);
            int expired = 0;
            for (int block = 0; block < count && !expired; ++block) {
                volatile uint32_t *words = blocks[block];
                for (size_t offset = 0; offset < chunk / sizeof(uint32_t); offset += 1024) {
                    words[offset] ^= 1;
                    clock_gettime(CLOCK_MONOTONIC, &current);
                    if (current.tv_sec - started.tv_sec >= 2) { expired = 1; break; }
                }
            }
            continue;
        }
        if (!strcmp(command, "HOLD\n")) { usleep(250000); continue; }
        if (strcmp(command, "GROW\n")) break;
        if (count == limit || available < (floor_mib + step_mib) * 1024) break;
        void *block = mmap(NULL, chunk, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (block == MAP_FAILED) break;
        blocks[count++] = block;
        uint32_t *words = block;
        for (size_t index = 0; index < chunk / sizeof(uint32_t); ++index) {
            random ^= random << 13; random ^= random >> 17; random ^= random << 5;
            words[index] = random;
        }
    }
    for (int index = 0; index < count; ++index) munmap(blocks[index], chunk);
    return 0;
}
