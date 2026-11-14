#include "engine.h"
#include "range.h"
#include "stream_window.h"
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/peer_info.hpp>
#include <libtorrent/ip_filter.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/write_resume_data.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/load_torrent.hpp>
#include <nlohmann/json.hpp>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <poll.h>
#include <unistd.h>
#include <fcntl.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <queue>
#include <regex>
#include <set>
#include <sstream>
#include <thread>
#include <cstring>
#include <cstdlib>

namespace lt = libtorrent;
namespace fs = std::filesystem;
using json = nlohmann::json;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
namespace {
struct OwnedFD {
    int value=-1;
    ~OwnedFD() { if(value>=0) close(value); }
    operator int() const { return value; }
    OwnedFD& operator=(int fd) { value=fd; return *this; }
};
std::string read_file(const fs::path& p) {
    std::ifstream file(p, std::ios::binary);
    if (!file) throw std::runtime_error("Cannot read " + p.filename().string());
    return {std::istreambuf_iterator<char>(file), {}};
}
void atomic_file(const fs::path& path, const std::string& contents) {
    auto temp = path.string() + ".tmp";
    std::ofstream output(temp, std::ios::binary | std::ios::trunc);
    output.write(contents.data(), contents.size()); output.close();
    if (!output) throw std::runtime_error("Cannot save torrent session");
    fs::rename(temp, path);
}
std::string tracker_host(const char* url) {
    // Never record passkeys, URL paths, or userinfo in diagnostics.
    std::string value=url ? url : "";
    auto start=value.find("://"); if(start==std::string::npos) return "unknown";
    auto end=value.find('/',start+3); auto authority=value.substr(start+3,end-(start+3));
    auto user=authority.rfind('@'); if(user!=std::string::npos) authority.erase(0,user+1);
    return value.substr(0,start)+"://"+authority;
}
std::string hash_id(const lt::info_hash_t& hashes) {
    std::ostringstream stream;
    if (hashes.has_v2()) stream << hashes.v2; else stream << hashes.v1;
    return stream.str();
}
bool video(const std::string& name) {
    auto extension = fs::path(name).extension().string();
    for (auto& c : extension) c = char(std::tolower(static_cast<unsigned char>(c)));
    static const std::set<std::string> formats = {".mkv", ".mp4", ".avi", ".mov", ".webm", ".m4v", ".ts", ".m2ts", ".mpg", ".mpeg", ".wmv"};
    return formats.count(extension);
}
bool send_bytes(int fd, const char* bytes, size_t length, const std::atomic<bool>* stopping=nullptr) {
    while (length) {
        if(stopping && *stopping) return false;
        auto sent = send(fd, bytes, length, MSG_DONTWAIT
#ifdef MSG_NOSIGNAL
            | MSG_NOSIGNAL
#endif
        );
        if(sent<0 && errno==EINTR) continue;
        if(sent<0 && (errno==EAGAIN || errno==EWOULDBLOCK)) {
            pollfd writable{fd,POLLOUT,0};
            if(poll(&writable,1,200)>0 && (writable.revents & (POLLERR|POLLHUP|POLLNVAL))) return false;
            continue;
        }
        if (sent <= 0) return false;
        bytes += sent; length -= size_t(sent);
    }
    return true;
}
void response(int fd, int code, const std::string& extra = "") {
    std::string message = "HTTP/1.1 " + std::to_string(code) + " Error\r\nConnection: close\r\nContent-Length: 0\r\n" + extra + "\r\n";
    send_bytes(fd, message.data(), message.size());
}
struct Task {
    std::string id, source, problem, event;
    json origin;
    lt::torrent_handle handle;
    bool paused = false, removed = false, playback_paused=false;
    Clock::time_point started = Clock::now(), advanced = Clock::now();
    int64_t previous_done = 0;
    std::map<int,std::pair<int,int>> windows;
    std::set<int> deadlines;
    std::vector<lt::tcp::endpoint> playback_peers;
    struct Warmup { int file, first, last; Clock::time_point expires; };
    std::optional<Warmup> warmup;
};

class Engine {
    fs::path profile_, downloads_, network_state_;
    OwnedFD profile_lock_;
    OwnedFD listener_;
    int port_ = 0;
    std::string token_;
    std::unique_ptr<lt::session> session_;
    std::map<std::string, std::shared_ptr<Task>> tasks_;
    std::mutex mutex_, clients_mutex_;
    std::condition_variable clients_ready_;
    std::queue<int> pending_;
    std::set<int> clients_;
    std::atomic<bool> stopping_{false};
    std::thread listener_thread_, alerts_thread_;
    std::vector<std::thread> workers_;
    struct PieceRead { bool pending=false; std::shared_ptr<std::vector<char>> data; std::string error; };
    std::map<std::pair<std::string,int>,std::shared_ptr<PieceRead>> reads_;
    std::string persistence_error_, dht_error_;
    bool isolated_ = false, dht_bootstrapped_ = false, stream_only_ = false;
    size_t piece_cache_bytes_ = 32 * 1024 * 1024;
    bool diagnostics_ = false;
    std::mutex trace_mutex_;
    std::ofstream trace_file_;
    Clock::time_point trace_started_ = Clock::now();
    std::atomic<int> request_sequence_{0};
    void trace(json event) noexcept {
        if (!diagnostics_) return;
        try {
            event["ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-trace_started_).count();
            std::lock_guard lock(trace_mutex_);
            if (trace_file_.tellp() < 4*1024*1024) { trace_file_ << event.dump() << '\n'; trace_file_.flush(); }
        } catch (...) {} // Diagnostics must never affect streaming.
    }
    json limits_ = {{"download",0},{"upload",0},{"active",3}};

    void save_network_state() noexcept {
        if (network_state_.empty() || isolated_) return;
        try {
            const auto flags=lt::session_handle::save_dht_state;
            auto state=session_->session_state(flags);
            auto bytes=lt::write_session_params_buf(state,flags);
            fs::create_directories(network_state_.parent_path());
            atomic_file(network_state_,{bytes.data(),bytes.size()});
        } catch (...) {} // An optional routing cache must not fail playback.
    }
    void save_manifest() {
        json list = json::array();
        for (auto& [id, t] : tasks_) list.push_back({{"id", id}, {"source", t->source}, {"origin", t->origin}, {"paused", t->paused}});
        atomic_file(profile_ / "tasks.json", json{{"version", 1}, {"tasks", list}}.dump(2));
    }
    void resume_request() {
        for (auto& [id, t] : tasks_) t->handle.save_resume_data(lt::torrent_handle::save_info_dict | lt::torrent_handle::flush_disk_cache);
    }
    void alerts() {
        std::vector<lt::alert*> alerts;
        session_->pop_alerts(&alerts);
        for (auto* alert : alerts) {
            if (diagnostics_) {
                if (auto* a=lt::alert_cast<lt::tracker_reply_alert>(alert)) trace({{"event","tracker_reply"},{"host",tracker_host(a->tracker_url())},{"peers",a->num_peers}});
                if (auto* a=lt::alert_cast<lt::tracker_error_alert>(alert)) trace({{"event","tracker_error"},{"host",tracker_host(a->tracker_url())},{"error",a->error.message()}});
                if (auto* a=lt::alert_cast<lt::tracker_announce_alert>(alert)) trace({{"event","tracker_announce"},{"host",tracker_host(a->tracker_url())}});
                if (auto* a=lt::alert_cast<lt::dht_get_peers_reply_alert>(alert)) trace({{"event","dht_peers"},{"peers",a->num_peers()}});
                if (lt::alert_cast<lt::dht_bootstrap_alert>(alert)) trace({{"event","dht_bootstrapped"}});
                if (auto* a=lt::alert_cast<lt::peer_disconnected_alert>(alert)) trace({{"event","peer_disconnected"},{"socket",int(a->socket_type)},{"error",a->error.message()}});
                if (auto* a=lt::alert_cast<lt::peer_connect_alert>(alert)) trace({{"event","peer_connect"},{"socket",int(a->socket_type)}});
                if (auto* a=lt::alert_cast<lt::piece_finished_alert>(alert)) trace({{"event","piece_finished"},{"piece",int(a->piece_index)}});
            }
            if (auto* read = lt::alert_cast<lt::read_piece_alert>(alert)) {
                auto id=task_id(read->handle);
                auto it=reads_.find({id,int(read->piece)});
                if(it==reads_.end()) continue;
                if(read->error) it->second->error=read->error.message();
                else it->second->data=std::make_shared<std::vector<char>>(read->buffer.get(),read->buffer.get()+read->size);
                // Shared buffers in active requests remain alive on eviction.
                size_t bytes=0;
                for(auto& [key,value]:reads_) if(value->data) bytes+=value->data->size();
                for(auto victim=reads_.begin(); bytes>piece_cache_bytes_ && victim!=reads_.end();) {
                    if(victim==it || !victim->second->data) { ++victim; continue; }
                    bytes-=victim->second->data->size(); victim=reads_.erase(victim);
                }
            } else if (auto* saved = lt::alert_cast<lt::save_resume_data_alert>(alert)) {
                auto id = task_id(saved->handle);
                if (!tasks_.count(id)) continue;
                auto bytes = lt::write_resume_data_buf(saved->params);
                atomic_file(profile_ / (id + ".resume"), {bytes.data(), bytes.size()});
            } else if (auto* error = lt::alert_cast<lt::torrent_error_alert>(alert)) {
                for (auto& [id, t] : tasks_) if (t->handle == error->handle) {
                    t->problem = error->error.message(); t->event = "downloadFailed";
                }
            } else if (auto* dht = lt::alert_cast<lt::dht_error_alert>(alert)) {
                dht_error_ = dht->error.message();
            } else if (lt::alert_cast<lt::dht_bootstrap_alert>(alert)) {
                dht_error_.clear();
                dht_bootstrapped_ = true;
            } else if (auto* failed = lt::alert_cast<lt::metadata_failed_alert>(alert)) {
                for (auto& [id, t] : tasks_) if (t->handle == failed->handle) {
                    t->problem = "Metadata failed hash validation: " + failed->error.message();
                    t->event = "metadataFailed";
                }
            } else if (auto* metadata = lt::alert_cast<lt::metadata_received_alert>(alert)) {
                metadata->handle.save_resume_data(lt::torrent_handle::save_info_dict);
            }
        }
    }
    std::string task_id(const lt::torrent_handle& handle) {
        for(auto& [id,t]:tasks_) if(t->handle==handle) return id;
        return "";
    }
    std::shared_ptr<Task> task(const std::string& id) {
        auto it = tasks_.find(id);
        if (it == tasks_.end()) throw std::runtime_error("Torrent no longer exists");
        return it->second;
    }
    json add(const json& args, bool restoring = false) {
        std::string source = args.at("source");
        if (source.size() > 65536) throw std::runtime_error("Source too long");
        lt::add_torrent_params params;
        bool magnet = source.rfind("magnet:?", 0) == 0;
        if (magnet) params = lt::parse_magnet_uri(source);
        else {
            if (!restoring && fs::file_size(source) > 16 * 1024 * 1024) throw std::runtime_error("Torrent exceeds 16 MiB");
            params = lt::load_torrent_file(source);
        }
        if (params.ti) {
            for (auto index : params.ti->layout().file_range()) {
                if (params.ti->layout().file_flags(index) & lt::file_storage::flag_symlink)
                    throw std::runtime_error("Symlink entries are not supported");
            }
        }
        auto id = hash_id(params.info_hashes);
        if (args.contains("expectedMagnet")) {
            const auto expected=lt::parse_magnet_uri(args.at("expectedMagnet").get<std::string>());
            if (!((expected.info_hashes.has_v1() && params.info_hashes.has_v1() && expected.info_hashes.v1==params.info_hashes.v1) ||
                  (expected.info_hashes.has_v2() && params.info_hashes.has_v2() && expected.info_hashes.v2==params.info_hashes.v2)))
                throw std::runtime_error("Downloaded torrent does not match the selected source");
        }
        for(auto& [existing,t]:tasks_) {
            auto known=t->handle.info_hashes();
            if ((known.has_v1() && params.info_hashes.has_v1() && known.v1==params.info_hashes.v1) ||
                (known.has_v2() && params.info_hashes.has_v2() && known.v2==params.info_hashes.v2)) {
                if (params.ti && !t->handle.torrent_file()) {
                    if (!t->handle.set_metadata(params.ti->info_section())) throw std::runtime_error("Torrent metadata rejected");
                    for(size_t i=0;i<params.trackers.size();++i) { lt::announce_entry tracker(params.trackers[i]); if(i<params.tracker_tiers.size()) tracker.tier=params.tracker_tiers[i]; t->handle.add_tracker(tracker); }
                    auto local=profile_/(existing+".torrent"); atomic_file(local,read_file(source)); t->source=local.string();
                    t->problem.clear(); t->event.clear(); t->handle.save_resume_data(lt::torrent_handle::save_info_dict); save_manifest();
                }
                return {{"id",existing},{"duplicate",true}};
            }
        }
        json origin = args.value("origin", json{{"local", magnet ? "magnet" : "torrentFile"}});
        auto provider_id_valid = [](const std::string &value) {
            if (value.empty() || value.size() > 64 || value.front() < 'a' || value.front() > 'z') return false;
            return std::all_of(value.begin(), value.end(), [](unsigned char c) {
                return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            });
        };
        if (!(origin.is_object() && origin.size() == 1 &&
            ((origin.contains("provider") && origin["provider"].is_string() && provider_id_valid(origin["provider"].get<std::string>())) ||
             (origin.contains("local") && (origin["local"] == "magnet" || origin["local"] == "torrentFile")))))
            throw std::runtime_error("Invalid provider origin");
        auto saved = profile_ / (id + ".resume");
        if (restoring && fs::exists(saved)) {
            auto resumed = lt::read_resume_data(read_file(saved));
            // A crash can leave a pre-metadata resume file beside a verified
            // .torrent. Preserve the newer metadata and its own trackers.
            if (params.ti && !resumed.ti) {
                resumed.ti=params.ti;
                resumed.info_hashes=params.info_hashes;
                resumed.trackers=params.trackers;
                resumed.tracker_tiers=params.tracker_tiers;
            }
            params=std::move(resumed);
        }
        params.save_path = (downloads_ / id).string();
        fs::create_directories(params.save_path);
        params.flags |= lt::torrent_flags::auto_managed;
        params.flags &= ~lt::torrent_flags::paused;
        params.flags &= ~lt::torrent_flags::duplicate_is_error;
        if (stream_only_) {
            params.flags |= lt::torrent_flags::default_dont_download;
            params.file_priorities.clear();
            params.piece_priorities.clear();
        }
        // Private torrents keep their own trackers. No global tracker injection.

        auto t = std::make_shared<Task>(); t->id = id; t->origin = origin;
        t->source = source; t->paused = args.value("paused", false) || (stream_only_ && params.ti);
        if (!magnet && !restoring) {
            auto local = profile_ / (id + ".torrent");
            atomic_file(local, read_file(source)); t->source = local.string();
        }
        if (t->paused) { params.flags &= ~lt::torrent_flags::auto_managed; params.flags |= lt::torrent_flags::paused; }
        t->handle = session_->add_torrent(params);
        tasks_[id] = t;
        if (!restoring) save_manifest();
        return {{"id", id}, {"duplicate", false}};
    }
    json files(const lt::torrent_handle& h) {
        json result = json::array(); auto info = h.torrent_file();
        if (!info) return result;
        auto progress = h.file_progress();
        for (auto index : info->layout().file_range()) {
            auto& f = info->layout();
            if (f.pad_file_at(index)) continue;
            result.push_back({{"index", int(index)}, {"name", f.file_path(index)}, {"size", f.file_size(index)},
                              {"done", progress[int(index)]}, {"video", video(f.file_path(index))}});
        }
        return result;
    }
    json snapshot() {
        json list = json::array();
        for (auto& [id, t] : tasks_) {
            auto s = t->handle.status();
            const bool metadata = s.has_metadata;
            const bool queued = bool(s.flags & lt::torrent_flags::paused) && !t->paused;
            auto phase = t->paused ? "paused" : queued ? "queued" : !metadata ? "metadata" :
                (s.state == lt::torrent_status::checking_files || s.state == lt::torrent_status::checking_resume_data) ? "checking" :
                s.is_seeding ? "seeding" : s.is_finished ? "completed" : "downloading";
            if (s.errc) { t->problem = s.errc.message(); t->event = "downloadFailed"; phase = "failed"; }
            auto now = Clock::now();
            if (s.total_wanted_done > t->previous_done) {
                t->advanced = now; t->problem.clear(); t->event.clear();
            }
            t->previous_done = s.total_wanted_done;
            int seeds = 0, peers = 0;
            std::vector<lt::peer_info> connections; t->handle.get_peer_info(connections);
            for (auto& p : connections) {
                if (p.flags & (lt::peer_info::connecting | lt::peer_info::handshake)) continue;
                if (p.connection_type & (lt::peer_info::web_seed | lt::peer_info::http_seed)) continue;
                (p.flags & lt::peer_info::seed) ? ++seeds : ++peers;
            }
            bool running = !t->paused && !queued && !s.errc && s.state!=lt::torrent_status::checking_files && s.state!=lt::torrent_status::checking_resume_data;
            bool usable=std::any_of(connections.begin(),connections.end(),[](const lt::peer_info& p){ return !(p.flags & (lt::peer_info::connecting|lt::peer_info::handshake)); });
            if (!running) t->started = t->advanced = now;
            if (running && !metadata && now - t->started > 60s) {
                const bool dht_required=!dht_error_.empty() && t->handle.trackers().empty() && !isolated_;
                t->event = dht_required ? "dhtFailed" : "metadataFailed";
                t->problem = !dht_required ? "Torrent metadata timeout (60 s). Retry or check trackers and DHT." : "DHT unavailable: " + dht_error_;
            } else if (running && metadata && !s.is_finished && !usable && now - t->advanced > 90s) {
                t->event = "noPeers"; t->problem = "No usable peers or web seeds after 90 s. Retry or check the source.";
            } else if (running && !s.errc && (s.is_finished || seeds + peers > 0) && t->event != "downloadFailed") {
                t->problem.clear(); t->event.clear();
            }
            bool buffering=false;
            for (const auto& window : t->windows) {
                if (!t->handle.have_piece(lt::piece_index_t(window.second.first))) { buffering=true; break; }
            }
            auto info=t->handle.torrent_file();
            std::string dht=info && info->priv() ? "private" : isolated_ ? "disabled" :
                !dht_error_.empty() ? "error" : dht_bootstrapped_ ? "ready" : session_->is_dht_running() ? "searching" : "starting";
            list.push_back({{"id", id}, {"title", s.name.empty() ? id : s.name}, {"origin", t->origin},
                {"phase", phase}, {"metadata", metadata}, {"done", s.total_wanted_done}, {"total", s.total_wanted},
                {"dht", dht}, {"buffering", buffering && !t->playback_paused && running},
                {"rate", s.download_payload_rate}, {"uploadRate", s.upload_payload_rate}, {"seeds", running ? json(seeds) : json(nullptr)},
                {"peers", running ? json(peers) : json(nullptr)}, {"error", t->problem}, {"event", t->event},
                {"files", files(t->handle)}});
        }
        return {{"tasks", list}, {"persistenceError", persistence_error_}, {"port", port_}, {"listenPort", session_->listen_port()}, {"limits", limits_}};
    }
    void remember_playback_peers(const std::shared_ptr<Task>& t) {
        if (!stream_only_) return;
        std::vector<lt::peer_info> peers;
        t->handle.get_peer_info(peers);
        std::vector<lt::tcp::endpoint> endpoints;
        for (const auto& peer : peers) {
            if (!(peer.flags & lt::peer_info::outgoing_connection) ||
                (peer.flags & (lt::peer_info::connecting | lt::peer_info::handshake)) ||
                (peer.connection_type & (lt::peer_info::web_seed | lt::peer_info::http_seed))) continue;
            endpoints.push_back(peer.remote_endpoint());
            if (endpoints.size() == 40) break;
        }
        if (!endpoints.empty()) t->playback_peers = std::move(endpoints);
    }
    void update_window(const std::shared_ptr<Task>& t, int fd, int first, int last) {
        std::lock_guard lock(mutex_);
        const bool restarting = first >= 0 && (t->windows.empty() ||
            (stream_only_ && t->handle.status().is_finished));
        // Preserve reachable endpoints before zero priorities mark this task
        // finished and either side disconnects the now unneeded seed.
        if (first < 0 && !t->removed) remember_playback_peers(t);
        if (first<0) t->windows.erase(fd); else t->windows[fd]={first,last};
        std::map<int,int> desired;
        for (auto& [client,range]:t->windows) for(int p=range.first;p<=range.second;++p) {
            int delay=(p-range.first)*150;
            auto it=desired.find(p); if(it==desired.end() || delay<it->second) desired[p]=delay;
        }
        if (t->warmup && Clock::now() >= t->warmup->expires) t->warmup.reset();
        if (t->warmup) for (int p=t->warmup->first;p<=t->warmup->last;++p) {
            const int delay=(p-t->warmup->first)*150;
            auto it=desired.find(p); if(it==desired.end() || delay<it->second) desired[p]=delay;
        }
        for(int p:t->deadlines) if(!desired.count(p)) {
            t->handle.reset_piece_deadline(lt::piece_index_t(p));
            if (stream_only_) t->handle.piece_priority(lt::piece_index_t(p), lt::dont_download);
        }
        t->deadlines.clear();
        for(auto [p,delay]:desired) { t->handle.set_piece_deadline(lt::piece_index_t(p),delay); t->deadlines.insert(p); }
        // Reintroduce peers only after the new deadlines restore download
        // interest. Otherwise a completed selective stream can retain zero
        // connection candidates even though known seeds have the next range.
        if (stream_only_ && restarting && !t->paused)
            for (const auto& peer : t->playback_peers) t->handle.connect_peer(peer);
    }
    void serve(int fd) {
        timeval timeout{2, 0};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
#ifdef SO_NOSIGPIPE
        int yes = 1; setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes));
#endif
        std::string header; char input[1024];
        while (header.find("\r\n\r\n") == std::string::npos) {
            auto n = recv(fd, input, sizeof(input), 0); if (n <= 0) return;
            header.append(input, size_t(n)); if (header.size() > 16384) { response(fd, 431); return; }
        }
        std::istringstream request(header); std::string method, path, protocol;
        request >> method >> path >> protocol;
        if (method != "GET" && method != "HEAD") { response(fd, 405, "Allow: GET, HEAD\r\n"); return; }
        std::smatch match;
        if (!std::regex_match(path, match, std::regex("/" + token_ + "/([0-9a-f]{40,64})/([0-9]+)"))) { response(fd, 404); return; }
        std::shared_ptr<Task> t;
        { std::lock_guard lock(mutex_); auto found = tasks_.find(match[1].str()); if (found == tasks_.end()) { response(fd,404); return; } t = found->second; }
        auto info = t->handle.torrent_file(); if (!info) { response(fd, 503); return; }
        auto idx64 = decimal(match[2].str()); auto& files = info->layout();
        if (idx64 >= files.num_files()) { response(fd,404); return; }
        auto index = lt::file_index_t(int(idx64)); auto size = files.file_size(index);
        std::optional<std::string> range;
        std::string line; std::getline(request, line);
        while (std::getline(request, line) && line != "\r") {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            auto colon = line.find(':'); if (colon == std::string::npos) continue;
            auto key = line.substr(0, colon); for (auto& c : key) c = char(std::tolower(static_cast<unsigned char>(c)));
            if (key == "range") {
                if (range) { response(fd,416); return; }
                auto value = line.substr(colon+1); value.erase(0, value.find_first_not_of(" \t")); range = value;
            }
        }
        ByteRange r;
        try { r = parse_range(range, size); } catch (...) { response(fd,416,"Content-Range: bytes */" + std::to_string(size) + "\r\n"); return; }
        const int request_id=++request_sequence_;
        trace({{"event","http_request"},{"request",request_id},{"first",r.first},{"last",r.last}});
        auto base = downloads_ / t->id;
        auto file = fs::weakly_canonical(base / files.file_path(index));
        auto relative = file.lexically_relative(fs::weakly_canonical(base));
        if (relative.empty() || relative.is_absolute() || *relative.begin() == ".." || files.file_flags(index) & lt::file_storage::flag_symlink) { response(fd,403); return; }
        std::string mime = fs::path(files.file_path(index)).extension() == ".mp4" ? "video/mp4" : "application/octet-stream";
        auto head = "HTTP/1.1 " + std::string(r.partial ? "206 Partial Content" : "200 OK") + "\r\nConnection: close\r\nAccept-Ranges: bytes\r\nContent-Type: " + mime + "\r\nContent-Length: " + std::to_string(r.last-r.first+1) + "\r\n";
        if (r.partial) head += "Content-Range: bytes " + std::to_string(r.first) + "-" + std::to_string(r.last) + "/" + std::to_string(size) + "\r\n";
        head += "\r\n";
        if (method == "HEAD") { send_bytes(fd,head.data(),head.size()); return; }
        auto cleanup = [this,t,fd,request_id](int*) { trace({{"event","http_close"},{"request",request_id}}); try { update_window(t,fd,-1,-1); } catch (...) {} };
        std::unique_ptr<int,decltype(cleanup)> window_guard(reinterpret_cast<int*>(1),cleanup);
        // Matroska reads Cues at EOF before returning to the opening cluster.
        // Retain startup read-ahead across that short index request. Short source
        // probes never create this reservation; real seeks discard it.
        const bool index_tail = size-r.first <= 1024*1024 && r.last==size-1;
        const bool opening_mkv = stream_only_ && r.first==0 && r.last==size-1 && fs::path(files.file_path(index)).extension()==".mkv";
        const int seek_end = stream_only_ ? stream_seek_window_end(files.file_offset(index), r.first, r.last, info->piece_length()) : -1;
        bool warmup_started=false;
        int previous_piece=-1, previous_end=-1;
        bool headers_sent = false;
        bool startup_prefetch = false;
        auto next_progress_check = Clock::now();
        auto next_trace = Clock::now();
        auto last_data = Clock::now();
        int64_t cursor = r.first;
        while (cursor <= r.last && !stopping_) {
            { std::lock_guard lock(mutex_); if (t->removed) return; if (t->paused || t->playback_paused) last_data = Clock::now(); }
            pollfd disconnected{fd, POLLIN, 0};
            if (poll(&disconnected,1,0) > 0) {
                char peek; if (recv(fd,&peek,1,MSG_PEEK | MSG_DONTWAIT) <= 0) return;
            }
            auto offset = files.file_offset(index) + cursor;
            int piece = int(offset / info->piece_length());
            const int range_end = int((files.file_offset(index) + r.last) / info->piece_length());
            const bool verified = t->handle.have_piece(lt::piece_index_t(piece));
            bool warmup_changed=false;
            {
                std::lock_guard lock(mutex_);
                if(t->warmup && (t->warmup->file!=int(index) || (!index_tail && piece>t->warmup->first) || Clock::now()>=t->warmup->expires)) {
                    trace({{"event","warmup_end"},{"request",request_id}});
                    t->warmup.reset(); warmup_changed=true;
                }
                if(opening_mkv && !warmup_started && !headers_sent && verified) {
                    t->warmup=Task::Warmup{int(index),piece,stream_window_end(piece,range_end,info->piece_length(),true),Clock::now()+90s};
                    warmup_started=true; warmup_changed=true;
                    trace({{"event","warmup_begin"},{"first",piece},{"last",t->warmup->last}});
                }
            }
            if (diagnostics_ && Clock::now() >= next_trace) {
                auto status=t->handle.status();
                std::vector<lt::peer_info> peers; t->handle.get_peer_info(peers);
                json connections=json::array();
                for (const auto& peer:peers) if (!(peer.flags & (lt::peer_info::connecting | lt::peer_info::handshake)))
                    connections.push_back({{"interesting",bool(peer.flags & lt::peer_info::interesting)},{"choked",bool(peer.flags & lt::peer_info::remote_choked)},{"source",int(static_cast<unsigned char>(peer.source))},{"rate",peer.down_speed}});
                std::vector<lt::partial_piece_info> queue; t->handle.get_download_queue(queue);
                json progress=json::array();
                for (auto& q:queue) progress.push_back({{"piece",int(q.piece_index)},{"blocks",q.blocks_in_piece},{"finished",q.finished},{"writing",q.writing},{"requested",q.requested}});
                trace({{"event","http_progress"},{"request",request_id},{"cursor",cursor},{"piece",piece},{"verified",verified},{"peers",status.num_peers},{"knownPeers",status.list_peers},{"candidates",status.connect_candidates},{"connections",status.num_connections},{"paused",bool(status.flags & lt::torrent_flags::paused)},{"priority",int(static_cast<unsigned char>(t->handle.piece_priority(lt::piece_index_t(piece))))},{"wanted",status.total_wanted},{"peerStates",connections},{"seeds",status.num_seeds},{"rate",status.download_payload_rate},{"downloaded",status.total_payload_download},{"queue",progress}});
                next_trace=Clock::now()+1s;
            }
            if (!startup_prefetch && !verified && range_end > piece && Clock::now() >= next_progress_check) {
                std::vector<lt::partial_piece_info> queue;
                t->handle.get_download_queue(queue);
                for (const auto& progress : queue) if (int(progress.piece_index) == piece) {
                    startup_prefetch = stream_prefetch_ready(progress.blocks_in_piece, progress.finished, progress.writing);
                    break;
                }
                next_progress_check = Clock::now() + 250ms;
            }
            const int end = std::max(seek_end, stream_window_end(piece, range_end, info->piece_length(), headers_sent || verified || startup_prefetch));
            if (piece != previous_piece || end != previous_end || warmup_changed) {
                trace({{"event","window"},{"request",request_id},{"first",piece},{"last",end}});
                update_window(t,fd,piece,end); previous_piece=piece; previous_end=end;
            }
            if (!verified) {
                if (Clock::now() - last_data > (headers_sent ? 45s : 90s)) {
                    {
                        std::lock_guard lock(mutex_);
                        t->event="downloadFailed";
                        t->problem=headers_sent ? "No new verified video data for 45 seconds. Retry playback." : "No verified video data within 90 seconds. Retry or check peers.";
                    }
                    if (!headers_sent) response(fd,504);
                    return;
                }
                std::this_thread::sleep_for(50ms); continue;
            }
            std::shared_ptr<std::vector<char>> buffer;
            {
                std::lock_guard lock(mutex_);
                auto& slot=reads_[{t->id,piece}];
                if(!slot) slot=std::make_shared<PieceRead>();
                if(!slot->pending) { slot->pending=true; t->handle.read_piece(lt::piece_index_t(piece)); }
                if(!slot->error.empty()) { if(!headers_sent) response(fd,500); return; }
                buffer=slot->data;
            }
            if(!buffer) { std::this_thread::sleep_for(20ms); continue; }
            auto within=size_t(offset % info->piece_length());
            if(within>=buffer->size()) { if(!headers_sent) response(fd,500); return; }
            auto length = size_t(std::min({r.last-cursor+1, int64_t(buffer->size()-within), int64_t(262144)}));
            if (!headers_sent) { trace({{"event","http_first_byte"},{"request",request_id},{"piece",piece}}); if (!send_bytes(fd,head.data(),head.size())) return; headers_sent = true; }
            if (!send_bytes(fd,buffer->data()+within,length,&stopping_)) return;
            cursor += length; last_data = Clock::now();
        }
    }
public:
    explicit Engine(const json& config) : profile_(config.at("profile").get<std::string>()), downloads_(config.at("downloads").get<std::string>()), isolated_(config.value("isolated", false)) {
        fs::create_directories(profile_); fs::create_directories(downloads_);
        profile_lock_ = ::open((profile_ / "engine.lock").c_str(), O_CREAT | O_RDWR, 0600);
        if (profile_lock_ < 0 || flock(profile_lock_,LOCK_EX | LOCK_NB)) throw std::runtime_error("FreeScene profile is already open");
        diagnostics_=config.value("diagnostics",false);
        if(diagnostics_) trace_file_.open(profile_/"stream-trace.jsonl", std::ios::trunc);
        trace({{"event","initialize"}});
        const bool lite = config.value("lite", false);
        stream_only_ = config.value("streamOnly", false);
        piece_cache_bytes_ = (lite ? 16 : 32) * 1024 * 1024;
        const auto ca = config.value("caBundle", std::string("/etc/ssl/cert.pem"));
        if (!getenv("SSL_CERT_FILE")) setenv("SSL_CERT_FILE",ca.c_str(),0);
        lt::settings_pack settings;
        settings.set_str(lt::settings_pack::user_agent, "FreeScene/0.5 libtorrent");
        settings.set_str(lt::settings_pack::dht_bootstrap_nodes, "dht.libtorrent.org:25401,dht.transmissionbt.com:6881,router.bittorrent.com:6881");
        // Keep metadata peers while the TV user is choosing an episode. Zero
        // file priorities otherwise make libtorrent disconnect seed peers.
        if (stream_only_) {
            settings.set_bool(lt::settings_pack::close_redundant_connections, false);
            // The default 60-second reconnect cooldown also applies after an
            // intentional pause. A TV returning to a retained stream must be
            // able to reuse known peers before the player's HTTP read times out.
            settings.set_int(lt::settings_pack::min_reconnect_time, 5);
            // A slow/dead tier must not block discovery on the other trackers
            // already supplied by the torrent. No external trackers are added.
            settings.set_bool(lt::settings_pack::announce_to_all_tiers, true);
        }
        settings.set_int(lt::settings_pack::active_downloads, 3);
        settings.set_int(lt::settings_pack::active_limit, 6);
        if (lite) {
            limits_["active"] = 1;
            settings.set_int(lt::settings_pack::active_downloads, 1);
            settings.set_int(lt::settings_pack::active_limit, 2);
            settings.set_int(lt::settings_pack::connections_limit, 40);
            settings.set_int(lt::settings_pack::aio_threads, 2);
            settings.set_int(lt::settings_pack::max_queued_disk_bytes, 4 * 1024 * 1024);
            settings.set_int(lt::settings_pack::send_buffer_watermark, 256 * 1024);
        }
        settings.set_bool(lt::settings_pack::enable_upnp, false);
        settings.set_bool(lt::settings_pack::enable_natpmp, false);
        settings.set_bool(lt::settings_pack::enable_lsd, !isolated_);
        settings.set_bool(lt::settings_pack::enable_dht, !isolated_);
        settings.set_int(lt::settings_pack::alert_mask, int(static_cast<uint32_t>(lt::alert_category::error | lt::alert_category::storage | lt::alert_category::status | lt::alert_category::dht | (diagnostics_ ? lt::alert_category::tracker | lt::alert_category::piece_progress | lt::alert_category::connect | lt::alert_category::dht_operation : lt::alert_category_t{}))));
        if (isolated_) {
            settings.set_str(lt::settings_pack::listen_interfaces,"127.0.0.1:0");
            settings.set_str(lt::settings_pack::outgoing_interfaces,"127.0.0.1");
            settings.set_bool(lt::settings_pack::ssrf_mitigation,false);
            settings.set_bool(lt::settings_pack::apply_ip_filter_to_trackers,false);
        }
        if (fs::exists(profile_ / "limits.json")) {
            auto limits = json::parse(read_file(profile_ / "limits.json")); limits_=limits;
            settings.set_int(lt::settings_pack::download_rate_limit, limits.at("download"));
            settings.set_int(lt::settings_pack::upload_rate_limit, limits.at("upload"));
            int active=limits.at("active");
            settings.set_int(lt::settings_pack::active_downloads, active==0 ? -1 : active);
            settings.set_int(lt::settings_pack::active_limit, active==0 ? -1 : active+3);
        }
        // Keep routing contacts outside the disposable movie buffer. Without
        // them each TV playback rebuilds DHT discovery from bootstrap nodes.
        network_state_=config.value("networkState",std::string());
        lt::session_params session_params;
        if (!isolated_ && !network_state_.empty()) {
            try {
                if (fs::exists(network_state_) && fs::file_size(network_state_) <= 1024*1024) {
                    auto bytes=read_file(network_state_);
                    session_params=lt::read_session_params(bytes,lt::session_handle::save_dht_state);
                    trace({{"event","dht_restored"},{"nodes",session_params.dht_state.nodes.size()},{"nodes6",session_params.dht_state.nodes6.size()}});
                }
            } catch (...) {} // Discard a missing/corrupt cache and bootstrap normally.
        }
        session_params.settings=settings;
        session_ = std::make_unique<lt::session>(std::move(session_params));
        // libtorrent normally exempts LAN peers from global rate limits.
        // The user's limits apply consistently to local and remote peers.
        lt::ip_filter classes;
        classes.add_rule(lt::make_address("0.0.0.0"),lt::make_address("255.255.255.255"),1);
        classes.add_rule(lt::make_address("::"),lt::make_address("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),1);
        session_->set_peer_class_filter(classes);
        if (fs::exists(profile_ / "tasks.json")) {
            auto saved = json::parse(read_file(profile_ / "tasks.json"));
            if (saved.at("version") != 1) throw std::runtime_error("Unsupported torrent session version");
            for (auto& entry : saved.at("tasks")) {
                if (config.value("pauseOnRestore", false)) entry["paused"] = true;
                add(entry, true);
            }
        }
        unsigned char random[24]; arc4random_buf(random,sizeof(random));
        const char hex[]="0123456789abcdef";
        for (auto byte : random) { token_ += hex[byte>>4]; token_ += hex[byte&15]; }
        listener_ = socket(AF_INET,SOCK_STREAM,0);
        sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK); address.sin_port=0;
        if (listener_ < 0 || bind(listener_,reinterpret_cast<sockaddr*>(&address),sizeof(address)) || listen(listener_,8)) throw std::runtime_error("Cannot start loopback streaming server");
        socklen_t size=sizeof(address); getsockname(listener_,reinterpret_cast<sockaddr*>(&address),&size); port_=ntohs(address.sin_port);
        for (int i=0;i<(lite ? 4 : 8);++i) workers_.emplace_back([this] {
            while (!stopping_) {
                int fd;
                { std::unique_lock lock(clients_mutex_); clients_ready_.wait(lock,[this]{return stopping_ || !pending_.empty();});
                  if (stopping_) return; fd=pending_.front(); pending_.pop(); clients_.insert(fd); }
                try { serve(fd); } catch (...) { /* Close without appending headers to a partial body. */ }
                { std::lock_guard lock(clients_mutex_); clients_.erase(fd); close(fd); }
            }
        });
        listener_thread_ = std::thread([this] {
            while (!stopping_) {
                pollfd p{listener_,POLLIN,0}; if (poll(&p,1,200)<=0) continue;
                int fd=accept(listener_,nullptr,nullptr); if (fd<0) continue;
                std::lock_guard lock(clients_mutex_);
                if (pending_.size()>=8) close(fd); else { pending_.push(fd); clients_ready_.notify_one(); }
            }
        });
        alerts_thread_ = std::thread([this] {
            auto saved = Clock::now();
            while (!stopping_) {
                { std::lock_guard lock(mutex_);
                  try { alerts(); if (Clock::now()-saved>15s) { resume_request(); save_network_state(); saved=Clock::now(); } }
                  catch (const std::exception& e) { persistence_error_=e.what(); } }
                std::this_thread::sleep_for(100ms);
            }
        });
    }
    ~Engine() {
        stopping_=true; clients_ready_.notify_all();
        if (listener_thread_.joinable()) listener_thread_.join();
        if (listener_ >= 0) { close(listener_); listener_ = -1; }

        { std::lock_guard lock(clients_mutex_); for (auto fd:clients_) shutdown(fd,SHUT_RDWR); while (!pending_.empty()) { close(pending_.front()); pending_.pop(); } }
        for (auto& worker:workers_) worker.join();
        if (alerts_thread_.joinable()) alerts_thread_.join();
        save_network_state();
        try {
            resume_request(); auto until=Clock::now()+3s;
            while (Clock::now()<until) { alerts(); std::this_thread::sleep_for(50ms); }
            save_manifest();
        } catch (...) {}
        session_.reset();
        if (profile_lock_>=0) { flock(profile_lock_,LOCK_UN); close(profile_lock_); profile_lock_ = -1; }
    }
    json command(const json& args) {
        std::lock_guard lock(mutex_);
        auto method=args.at("method").get<std::string>();
        if (method=="add") return add(args);
        if (method=="status") return snapshot();
        if (method=="limits") {
            int down=args.at("download"), up=args.at("upload"), active=args.at("active");
            if (down<0 || up<0 || active<0 || active>20) throw std::runtime_error("Invalid limits");
            lt::settings_pack s; s.set_int(lt::settings_pack::download_rate_limit,down); s.set_int(lt::settings_pack::upload_rate_limit,up); s.set_int(lt::settings_pack::active_downloads,active==0 ? -1 : active); s.set_int(lt::settings_pack::active_limit,active==0 ? -1 : active+3); session_->apply_settings(s);
            limits_={{"download",down},{"upload",up},{"active",active}}; atomic_file(profile_/"limits.json",limits_.dump()); return {{"ok",true}};
        }
        auto t=task(args.at("id"));
        if (method=="pause" || method=="resume") {
            t->paused=method=="pause"; t->started=t->advanced=Clock::now(); t->event.clear(); t->problem.clear();
            if (t->paused) { remember_playback_peers(t); t->handle.unset_flags(lt::torrent_flags::auto_managed); t->handle.pause(); }
            else { t->handle.set_flags(lt::torrent_flags::auto_managed); t->handle.clear_error(); t->handle.resume(); }
            save_manifest(); t->handle.save_resume_data(lt::torrent_handle::save_info_dict); return {{"ok",true}};
        }
        if (method=="remove") {
            t->removed=true; session_->remove_torrent(t->handle, args.value("deleteFiles",false) ? lt::session::delete_files : lt::remove_flags_t{});
            tasks_.erase(t->id); save_manifest(); fs::remove(profile_/(t->id+".resume")); fs::remove(profile_/(t->id+".torrent")); return {{"ok",true}};
        }
        if (method=="prepareStream") {
            trace({{"event","prepareStream"}});
            if (stream_only_ && t->handle.torrent_file()) {
                // Known file lists need no peer connections until a video is chosen.
                t->paused=true; t->handle.unset_flags(lt::torrent_flags::auto_managed);
                t->handle.pause(); return {{"ok",true}};
            }
            // Playback must get metadata even when background queue slots are
            // occupied. Keep bandwidth limits, bypass only automatic queuing.
            t->paused=false; t->playback_paused=false; t->started=t->advanced=Clock::now(); t->problem.clear(); t->event.clear();
            t->handle.unset_flags(lt::torrent_flags::auto_managed);
            t->handle.clear_error(); t->handle.resume(); t->handle.force_reannounce(); t->handle.force_dht_announce();
            save_manifest(); return {{"ok",true}};
        }
        if (method=="streamPaused") { t->playback_paused=args.at("paused"); return {{"ok",true}}; }
        if (method=="stream") {
            trace({{"event","stream"}});
            auto info=t->handle.torrent_file(); if (!info) throw std::runtime_error("Metadata is not ready");
            int index=args.at("file");
            if (index<0 || index>=info->num_files() || !video(info->layout().file_path(lt::file_index_t(index)))) throw std::runtime_error("Choose a video file");
            t->paused=false; t->playback_paused=false; t->started=t->advanced=Clock::now(); t->problem.clear(); t->event.clear();
            t->handle.unset_flags(lt::torrent_flags::auto_managed);
            if (stream_only_) {
                // Advertise real interest before connecting peers. With all file
                // priorities at zero a seed otherwise sees an upload-only peer.
                const int first=int(info->layout().file_offset(lt::file_index_t(index))/info->piece_length());
                t->handle.set_piece_deadline(lt::piece_index_t(first),0);
                t->deadlines.insert(first);
            }
            t->handle.resume(); t->handle.force_reannounce(); t->handle.force_dht_announce();
            return {{"url","http://127.0.0.1:"+std::to_string(port_)+"/"+token_+"/"+t->id+"/"+std::to_string(index)}};
        }
        if (method=="connectPeer" && isolated_) {
            auto address=lt::make_address("127.0.0.1"); t->handle.connect_peer({address,args.at("port").get<unsigned short>()}); return {{"ok",true}};
        }
        throw std::runtime_error("Unknown engine command");
    }
};
}
static thread_local std::string startup_error;
extern "C" void* fs_engine_create(const char* config) {
    try { startup_error.clear(); return new Engine(json::parse(config)); } catch (const std::exception& e) { startup_error=e.what(); return nullptr; } catch (...) { startup_error="Engine initialization failed"; return nullptr; }
}
extern "C" char* fs_engine_command(void* engine, const char* request) {
    json result;
    try { if (!engine) throw std::runtime_error(startup_error.empty() ? "Torrent engine could not open the profile" : startup_error); result={{"result",static_cast<Engine*>(engine)->command(json::parse(request))}}; }
    catch (const std::exception& e) { result={{"error",e.what()}}; }
    catch (...) { result={{"error","Native engine failure"}}; }
    return strdup(result.dump().c_str());
}
extern "C" void fs_engine_free(char* value) { free(value); }
extern "C" void fs_engine_destroy(void* engine) { delete static_cast<Engine*>(engine); }
