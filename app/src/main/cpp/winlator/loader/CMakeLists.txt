# winloader: freestanding userspace ELF loader, packaged as libwinloader.so so it can be
# executed from nativeLibraryDir (the only place execve() is allowed for targetSdk >= 29).
# It has no libc, no PT_INTERP and no relocations.

# the NDK toolchain appends -latomic -lm, which would add DT_NEEDED entries
set(CMAKE_C_STANDARD_LIBRARIES "")

add_library(winloader SHARED winloader.c)

target_compile_options(winloader PRIVATE
        -O2 -Wall -Wextra
        -ffreestanding -fno-builtin -fno-stack-protector -fno-asynchronous-unwind-tables
        -fvisibility=hidden)

target_link_options(winloader PRIVATE
        -nostdlib -nostartfiles
        -Wl,-e,_start -Wl,-Bsymbolic -Wl,-z,norelro
        -Wl,--no-undefined)

# bionic side: LD_PRELOADed into bionic guests so exec()/mprotect() interception survives
# libc's own SIGSYS handler. Regular shared library, but keep libc calls to a minimum.
add_library(winloader_shim SHARED winloader_shim.c)

target_compile_options(winloader_shim PRIVATE
        -O2 -Wall -Wextra -Wno-unused-function
        -fno-builtin -fno-stack-protector -fvisibility=hidden)
