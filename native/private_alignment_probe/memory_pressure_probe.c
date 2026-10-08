/* Isolated synthetic pressure: 32 MiB steps, 768 MiB cap, 256 MiB reserve.
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

static long available_kb(void) {
    FILE *file = fopen("/proc/meminfo", "r");
    if (!file) return -1;
    char line[256]; long value = -1;
    while (fgets(line, sizeof line, file))
        if (sscanf(line, "MemAvailable: %ld kB", &value) == 1) break;
    fclose(file); return value;
}
int main(void) {
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() == 1) return 2;
    alarm(120);
    FILE *priority = fopen("/proc/self/oom_score_adj", "w");
    if (!priority) return 2;
    int written = fprintf(priority, "1000\n");
    int closed = fclose(priority);
    if (written < 0 || closed) return 2;
    void *blocks[24]; int count = 0;
    const size_t chunk = 32UL * 1024 * 1024;
    uint32_t random = 0x735abc19;
    char command[16];
    for (;;) {
        long available = available_kb();
        printf("{\"allocated_mib\":%d,\"available_kb\":%ld}\n", count * 32, available);
        fflush(stdout);
        if (!fgets(command, sizeof command, stdin) || strcmp(command, "GROW\n")) break;
        if (count == 24 || available < (256 + 32) * 1024) break;
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
