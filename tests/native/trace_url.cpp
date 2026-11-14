#include "../../native/trace_url.h"
#include <cassert>

int main() {
    assert(tracker_host(nullptr) == "unknown");
    assert(tracker_host("invalid") == "unknown");
    assert(tracker_host("https://user:secret@tracker.example/passkey/announce?key=secret#secret") == "https://tracker.example");
    assert(tracker_host("https://tracker.example?passkey=secret") == "https://tracker.example");
    assert(tracker_host("https://tracker.example#secret") == "https://tracker.example");
    assert(tracker_host("udp://[::1]:8080/announce") == "udp://[::1]:8080");
    assert(tracker_host("udp://tracker.example:80") == "udp://tracker.example:80");
}
