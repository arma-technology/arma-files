cmake_minimum_required(VERSION 3.22)
project(archivejni C)

# libarchive zbudowana wcześniej dla każdej architektury (patrz build-libarchive.sh).
add_library(libarchive STATIC IMPORTED)
set_target_properties(libarchive PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libarchive.a)

add_library(archivejni SHARED archivejni.c)
target_include_directories(archivejni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_definitions(archivejni PRIVATE LIBARCHIVE_STATIC)
target_link_libraries(archivejni libarchive z log)
target_link_options(archivejni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
