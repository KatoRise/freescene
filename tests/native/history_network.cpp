#include "../../native/history_network.h"
#include <cassert>
#include <iostream>
#include <unistd.h>
int main() {
    namespace fs=std::filesystem;using json=nlohmann::json;
    auto root=fs::temp_directory_path()/("freescene-history-cache-"+std::to_string(getpid()));
    const std::string a(64,'a'),b(64,'b');
    HistoryNetwork cache(root);
    const auto peers=json::array({json::array({"127.0.0.1",1234})});
    assert(!cache.save(a,"torrent",0,peers,"dht",100));assert(!fs::exists(root));
    cache.save_torrent(a,"metadata",json{{"local","magnet"}});assert(!fs::exists(root));
    cache.sync(json::array({a,b}));assert(!fs::exists(root));
    assert(cache.save(a,"torrent",0,peers,"dht-a",100));
    assert(cache.save(b,"torrent",1,peers,"dht-b",101));
    cache.save_torrent(a,"metadata",json{{"local","magnet"}});assert(!cache.torrent(a).empty());
    assert(cache.replay(a).at("file")==0);
    assert(cache.dht()=="dht-a");assert(cache.peers("torrent",102).size()==2);
    cache.sync(json::array({b}));assert(!fs::exists(root/a));assert(fs::exists(root/b));
    cache.save_torrent(a,"resurrect",json{{"local","magnet"}});assert(cache.torrent(a).empty());
    assert(!cache.save(a,"torrent",0,peers,"resurrect",103));assert(!fs::exists(root/a));
    assert(cache.dht()=="dht-b");assert(cache.peers("other",102).empty());assert(cache.peers("torrent",90000).empty());
    HistoryNetwork restored(root);restored.sync(json::array({b}));assert(restored.peers("torrent",104).size()==1);
    restored.sync(json::array());assert(!fs::exists(root));assert(!restored.save(b,"torrent",1,peers,"stale",105));
    bool rejected=false;try { restored.sync(json::array({"../escape"})); } catch(const std::invalid_argument&) { rejected=true; }assert(rejected);
    std::cout<<"history network cache: eligibility, per-file ownership, restore, expiry, eviction and no resurrection passed\n";
}
