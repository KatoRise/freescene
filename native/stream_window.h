#pragma once
#include <algorithm>
#include <cstdint>

// Give a cold read a head start, then overlap read-ahead with the remainder
// of its first piece. Waiting until verification can starve playback at the
// next piece boundary. This only schedules data; HTTP still requires hashing.
inline bool stream_prefetch_ready(int blocks, int finished, int writing) {
    return blocks > 0 && int64_t(finished) + writing >= (int64_t(blocks) + 1) / 2;
}

// Retain the existing steady-state cushion: at least two
// following pieces (or approximately 8 MiB for small pieces).
// Never fetch beyond the last piece touched by this HTTP range.
inline int stream_window_end(int first, int last, int piece_bytes, bool prefetch) {
    if (!prefetch) return first;
    constexpr int64_t ahead_bytes = 8 * 1024 * 1024;
    const auto ahead = std::max<int64_t>(2, ahead_bytes / piece_bytes);
    return int(std::min<int64_t>(last, int64_t(first) + ahead));
}

// A seek near a piece boundary can leave almost no usable data in its first
// piece. Reserve a fixed 24 MiB from the requested byte, including that partial
// piece, so filling the player's buffer does not wait for the cursor to advance.
// Opening clusters, short source probes and small EOF index reads keep their
// existing scheduling. The reservation never extends beyond the HTTP range.
inline int stream_seek_window_end(int64_t file_offset, int64_t first, int64_t last, int piece_bytes) {
    constexpr int64_t MiB = 1024 * 1024;
    if (first < piece_bytes || last - first < 8 * MiB) return -1;
    const auto tail = std::min<int64_t>(last - first, 24 * MiB - 1);
    return int((file_offset + first + tail) / piece_bytes);
}
