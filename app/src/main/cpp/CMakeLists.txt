# Pont JNI vers Gutenprint. La bibliothèque statique et ses en-têtes (gp/) sont produits par la CI
# (.github/workflows/build.yml) avant la compilation Gradle.
cmake_minimum_required(VERSION 3.22.1)
project(canonjni C)

set(GP ${CMAKE_CURRENT_SOURCE_DIR}/gp)
add_library(gutenprint STATIC IMPORTED)
set_target_properties(gutenprint PROPERTIES IMPORTED_LOCATION ${GP}/lib/libgutenprint.a)

add_library(canonjni SHARED gp_jni.c)
target_include_directories(canonjni PRIVATE ${GP}/include)
target_link_libraries(canonjni gutenprint log m)
