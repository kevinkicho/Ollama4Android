# Host toolchain for building vulkan-shaders-gen on Windows (x64)
# This tool runs on the build machine to generate Vulkan shader code
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR AMD64)

# MSVC compiler + linker
set(CMAKE_C_COMPILER "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/cl.exe")
set(CMAKE_CXX_COMPILER "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/cl.exe")
set(CMAKE_LINKER "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/link.exe")

# Resource compiler and manifest tool from Windows SDK
set(CMAKE_RC_COMPILER "C:/Program Files (x86)/Windows Kits/10/bin/10.0.28000.0/x64/rc.exe")
set(CMAKE_MT "C:/Program Files (x86)/Windows Kits/10/bin/10.0.28000.0/x64/mt.exe")

# MSVC runtime library
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# Include paths: MSVC headers + Windows SDK headers
include_directories(
    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/include"
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.28000.0/ucrt"
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.28000.0/um"
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.28000.0/shared"
)

# Library paths: MSVC libs + Windows SDK libs + UCRT libs
link_directories(
    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/lib/x64"
    "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.28000.0/um/x64"
    "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.28000.0/ucrt/x64"
)
