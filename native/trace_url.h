#pragma once
#include <string>

// Keep tracker identity useful without recording credentials or URL parameters.
inline std::string tracker_host(const char* url) {
    const std::string value = url ? url : "";
    const auto start = value.find("://");
    if (start == std::string::npos) return "unknown";
    const auto end = value.find_first_of("/?#", start + 3);
    auto authority = value.substr(start + 3, end - (start + 3));
    const auto user = authority.rfind('@');
    if (user != std::string::npos) authority.erase(0, user + 1);
    return value.substr(0, start) + "://" + authority;
}
