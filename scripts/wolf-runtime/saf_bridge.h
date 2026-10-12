#ifndef ASTRA_SAF_BRIDGE_H
#define ASTRA_SAF_BRIDGE_H
/* NULL means outside the mount; a returned value is owned by the caller. */
char *astra_saf_realpath(const char *path, char *resolved);
#endif
