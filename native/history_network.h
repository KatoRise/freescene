#pragma once
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <algorithm>
#include <set>
#include <string>
#include <vector>

// Persistent discovery hints belong to eligible watch-history entries, never to
// the disposable movie buffer. The caller serializes access with its engine lock.
class HistoryNetwork {
    using json = nlohmann::json;
    std::filesystem::path root_;
    std::vector<std::string> ids_;
    static std::string read(const std::filesystem::path& path, uintmax_t limit) {
        if (!std::filesystem::is_regular_file(path) || std::filesystem::file_size(path)>limit) return {};
        std::ifstream file(path,std::ios::binary);return {std::istreambuf_iterator<char>(file),{}};
    }
    static void write(const std::filesystem::path& path,const std::string& bytes) {
        auto temp=path;temp += ".tmp";
        std::ofstream file(temp,std::ios::binary|std::ios::trunc);file.write(bytes.data(),bytes.size());file.close();
        if(!file)throw std::runtime_error("Cannot write history network cache");
        std::filesystem::rename(temp,path);
    }
public:
    static bool valid_id(const std::string& id) { return id.size()==64 && id.find_first_not_of("0123456789abcdef")==std::string::npos; }
    explicit HistoryNetwork(std::filesystem::path root={}) : root_(std::move(root)) {}
    bool allows(const std::string& id) const { return !root_.empty() && std::find(ids_.begin(),ids_.end(),id)!=ids_.end(); }
    void sync(const json& ids) {
        if(!ids.is_array() || ids.size()>500)throw std::invalid_argument("Invalid history cache owners");
        std::vector<std::string> next;
        for(const auto& id:ids) {
            if(!id.is_string() || !valid_id(id.get<std::string>()))throw std::invalid_argument("Invalid history cache owner");
            next.push_back(id.get<std::string>());
        }
        ids_=std::move(next);
        if(root_.empty() || !std::filesystem::exists(root_))return;
        for(const auto& entry:std::filesystem::directory_iterator(root_))if(!allows(entry.path().filename().string()))std::filesystem::remove_all(entry.path());
        if(ids_.empty())std::filesystem::remove_all(root_);
    }
    bool save(const std::string& id,const std::string& task,int file,const json& peers,const std::string& dht,int64_t now) {
        if(!allows(id) || file<0 || !peers.is_array() || peers.size()>40 || dht.size()>1024*1024)return false;
        const auto directory=root_/id;std::filesystem::create_directories(directory);
        write(directory/"peers.json",json{{"version",1},{"task",task},{"file",file},{"at",now},{"peers",peers}}.dump());
        if(!dht.empty())write(directory/"dht.state",dht);
        return true;
    }
    std::string dht() const {
        for(const auto& id:ids_)try { auto bytes=read(root_/id/"dht.state",1024*1024);if(!bytes.empty())return bytes; } catch(...) {}
        return {};
    }
    void save_torrent(const std::string& id,const std::string& bytes,const json& origin) {
        if(!allows(id) || bytes.empty() || bytes.size()>16*1024*1024)return;
        const auto directory=root_/id;std::filesystem::create_directories(directory);
        write(directory/"source.torrent",bytes);write(directory/"origin.json",origin.dump());
    }
    json replay(const std::string& id) const {
        if(!allows(id))throw std::invalid_argument("History entry no longer exists");
        try { return json::parse(read(root_/id/"peers.json",16*1024)); } catch(...) { return json::object(); }
    }
    std::string torrent(const std::string& id) const {
        if(!allows(id))return {};
        const auto path=root_/id/"source.torrent";
        return read(path,16*1024*1024).empty() ? std::string() : path.string();
    }
    json origin(const std::string& id) const {
        if(allows(id))try { return json::parse(read(root_/id/"origin.json",1024)); } catch(...) {}
        return {{"local","magnet"}};
    }
    json peers(const std::string& task,int64_t now) const {
        json result=json::array();
        for(const auto& id:ids_)try {
            const auto entry=json::parse(read(root_/id/"peers.json",16*1024));
            if(entry.value("version",0)!=1 || entry.value("task",std::string())!=task || !entry.at("peers").is_array())continue;
            const auto age=now-entry.at("at").get<int64_t>();if(age<0 || age>86400)continue;
            for(const auto& peer:entry.at("peers"))if(result.size()<40)result.push_back(peer);
        } catch(...) {}
        return result;
    }
};
