/* Built and run by CTest in CI: the freshly built library loads, reports the
 * pinned release and round-trips a buffer. */
#include <stdio.h>
#include <string.h>
#include "zstd.h"

int main(int argc, char **argv) {
    const char *expected = argc > 1 ? argv[1] : ZSTD_VERSION_STRING;
    if (strcmp(ZSTD_versionString(), expected) != 0) {
        fprintf(stderr, "libzstd %s, expected %s\n", ZSTD_versionString(), expected);
        return 1;
    }
    char src[4096], packed[8192], out[4096];
    for (int i = 0; i < (int) sizeof src; i++) src[i] = (char) (i * 7 % 61);
    size_t n = ZSTD_compress(packed, sizeof packed, src, sizeof src, 3);
    if (ZSTD_isError(n)) return 2;
    size_t m = ZSTD_decompress(out, sizeof out, packed, n);
    if (ZSTD_isError(m) || m != sizeof src || memcmp(src, out, m) != 0) return 3;
    printf("libzstd %s ok (%zu -> %zu bytes)\n", ZSTD_versionString(), sizeof src, n);
    return 0;
}
