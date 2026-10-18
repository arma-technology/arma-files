cmake_minimum_required(VERSION 3.22)
project(dwgjni C)

# LibreDWG zbudowana wcześniej dla każdej architektury (patrz build-libredwg.sh).
add_library(redwg STATIC IMPORTED)
set_target_properties(redwg PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libredwg.a)

add_library(dwgjni SHARED dwgjni.c)
target_include_directories(dwgjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(dwgjni redwg log m)
# Z biblioteki zostaje tylko to, czego używa odczyt DWG i zapis DXF.
target_link_options(dwgjni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
