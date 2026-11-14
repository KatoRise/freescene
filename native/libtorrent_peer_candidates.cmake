# The pinned libtorrent caches whether a peer list belongs to a finished
# selective download. add_peer() must refresh that cache when a new streaming
# window makes the torrent incomplete again, before testing existing seeds.
# Compile a patched copy; leave the checksum/revision-pinned checkout untouched.
function(freescene_patch_peer_candidates)
    set(original "${libtorrent_SOURCE_DIR}/src/peer_list.cpp")
    file(SHA256 "${original}" digest)
    if(NOT digest STREQUAL "3c1b1fef19bb9ed38343fba7f18aa990fba179b0ea4fc2c72b5b93182f9ee8dd")
        message(FATAL_ERROR "Review the peer-candidate fix for this libtorrent source")
    endif()
    file(READ "${original}" content)
    set(anchor "\t\tauto const remote_address = remote.address();")
    string(FIND "${content}" "${anchor}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "Review the peer-candidate fix for this libtorrent revision")
    endif()
    string(REPLACE "${anchor}"
        "\t\t// FreeScene: completed selective streams may become incomplete again.\n\t\tif (bool(m_finished) != state->is_finished)\n\t\t\trecalculate_connect_candidates(state);\n\n${anchor}"
        patched "${content}")
    set(output "${CMAKE_CURRENT_BINARY_DIR}/freescene_peer_list.cpp")
    file(CONFIGURE OUTPUT "${output}" CONTENT "${patched}" @ONLY)
    get_target_property(sources torrent-rasterbar SOURCES)
    list(LENGTH sources before)
    list(FILTER sources EXCLUDE REGEX "(^|/)peer_list\\.cpp$")
    list(LENGTH sources after)
    math(EXPR removed "${before} - ${after}")
    if(NOT removed EQUAL 1)
        message(FATAL_ERROR "Expected exactly one libtorrent peer_list.cpp source")
    endif()
    set_property(TARGET torrent-rasterbar PROPERTY SOURCES "${sources}")
    target_sources(torrent-rasterbar PRIVATE "${output}")
endfunction()
freescene_patch_peer_candidates()
