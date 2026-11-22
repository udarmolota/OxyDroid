#ifndef OXYDROID_MACHO_DARWIN_H
#define OXYDROID_MACHO_DARWIN_H
/* Darwin libc symbols that bionic lacks or lays out differently. See macho_darwin.c. */

/* name is the ELF spelling (one Mach-O underscore removed). NULL when there is no shim. */
void* macho_darwin_import(const char* name);

/* The replacement for dyld's tlv_get_addr (macho_loader.c): what every thread-local variable
 * descriptor's thunk slot is bound to, through the import "_tlv_bootstrap". */
void macho_tlv_thunk(void);

#endif
