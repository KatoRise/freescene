#include "../../native/stream_window.h"
#include <cassert>
#include <climits>

int main() {
    constexpr int MiB = 1024 * 1024;
    // Start overlapping before the first piece is verified, but never from
    // merely requested blocks; odd final pieces must round the threshold up.
    assert(!stream_prefetch_ready(512, 0, 0));
    assert(!stream_prefetch_ready(512, 255, 0));
    assert(stream_prefetch_ready(512, 255, 1));
    assert(stream_prefetch_ready(512, 512, 0));
    assert(!stream_prefetch_ready(3, 1, 0));
    assert(stream_prefetch_ready(3, 1, 1));
    assert(!stream_prefetch_ready(0, 0, 0));
    // Base cold-read scheduling gives the required 8 MiB piece a head start.
    assert(stream_window_end(0, 962, 8 * MiB, false) == 0);
    assert(stream_window_end(250, 962, 8 * MiB, false) == 250);
    // Continuous playback retains the original two following pieces.
    assert(stream_window_end(0, 962, 8 * MiB, true) == 2);
    assert(stream_window_end(250, 962, 8 * MiB, true) == 252);
    // Short probes and EOF must not schedule outside the requested range.
    assert(stream_window_end(0, 0, 8 * MiB, true) == 0);
    assert(stream_window_end(962, 962, 8 * MiB, true) == 962);
    assert(stream_window_end(3, 5, MiB / 4, true) == 5);
    // Preserve enough read-ahead for small pieces and avoid integer overflow.
    assert(stream_window_end(0, 100, MiB / 4, true) == 32);
    assert(stream_window_end(0, 100, 16 * MiB, true) == 2);
    assert(stream_window_end(INT_MAX - 1, INT_MAX, MiB / 4, true) == INT_MAX);

    // Observed EniaHD seek: only 18,030 bytes remain in piece 20. Piece 23
    // must be scheduled immediately, not 13 seconds later on entering 21.
    assert(stream_seek_window_end(0, 176142738, 8074228044, 8 * MiB) == 23);
    // Aligned requests need three pieces; a partial first piece needs four.
    assert(stream_seek_window_end(0, 20LL * 8 * MiB, 8074228044, 8 * MiB) == 22);
    assert(stream_seek_window_end(0, 20LL * 8 * MiB + 1, 8074228044, 8 * MiB) == 23);
    assert(stream_seek_window_end(1, 20LL * 8 * MiB, 8074228044, 8 * MiB) == 23);
    // Startup/Cues reopening and 4 MiB source probes must be unaffected.
    assert(stream_seek_window_end(0, 0, 8074228044, 8 * MiB) == -1);
    assert(stream_seek_window_end(0, 8097, 8074228044, 8 * MiB) == -1);
    assert(stream_seek_window_end(0, 176142738, 176142738 + 4 * MiB - 1, 8 * MiB) == -1);
    assert(stream_seek_window_end(0, 8074003155, 8074228044, 8 * MiB) == -1);
    // Clip a shorter tail and keep arithmetic valid beyond 32-bit file sizes.
    assert(stream_seek_window_end(0, 100LL * MiB, 110LL * MiB - 1, MiB) == 109);
    assert(stream_seek_window_end(0, 5000LL * MiB, 8000LL * MiB, MiB) == 5023);
    // Once playback passes the fixed reservation, normal sliding read-ahead wins.
    assert(std::max(23, stream_window_end(24, 962, 8 * MiB, true)) == 26);
}
