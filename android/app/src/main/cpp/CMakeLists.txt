cmake_minimum_required(VERSION 3.22.1)
project(mfilm C)

# the film OS, unchanged, from the camera's sources
set(FOS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ml-dev/filmos)
if(NOT DEFINED MFILM_VERSION)
  set(MFILM_VERSION "android")
endif()

add_library(mfilm SHARED
  jni_bridge.c
  mfilm_port.c
  mkcr2_lib.c
  ${FOS}/sim/port_files.c
  ${FOS}/sim/fonts.c
  ${FOS}/sim/films.c
  ${FOS}/fos_gfx.c ${FOS}/fos_film.c
  ${FOS}/fos_dev.c ${FOS}/fos_dng.c ${FOS}/fos_lab.c
  ${FOS}/fos_looks.c ${FOS}/fos_jpeg.c)

target_include_directories(mfilm PRIVATE ${FOS})
# FOS_DEV_RAWIN: the darkroom also develops the phone's own RAW (IMG_nnnn.DNG, fos_dng.c)
target_compile_definitions(mfilm PRIVATE MFILM_VERSION="${MFILM_VERSION}" FOS_DEV_RAWIN)
target_compile_options(mfilm PRIVATE -std=gnu99 -O2 -Wno-unused-parameter -Wno-unused-function
  -Wno-sign-compare -Wno-unused-variable -Wno-unused-but-set-variable)
# sim/port_files.c is the card (stdio); its camera name is ours (mfilm_port.c), as web/build.sh does
set_source_files_properties(${FOS}/sim/port_files.c PROPERTIES COMPILE_DEFINITIONS fos_port_model=sim_port_model)

target_link_libraries(mfilm android jnigraphics log m)
