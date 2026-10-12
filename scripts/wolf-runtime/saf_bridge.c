/* Astra-authored SAF descriptor bridge, for the pinned Bionic Wine runtime. */
#define _GNU_SOURCE
#include "saf_bridge.h"
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <sys/mman.h>
#include <stddef.h>

#define MAGIC 0x41534631
#define OPEN 1
#define STAT 2
#define LIST 3
#define COMMIT 4
#define MKDIR 5
#define DELETE 6
#define RENAME 7
#define RESERVE 8
#define FILE_LIMIT (256LL * 1024 * 1024)
#define DEVICE 0x415346
#define REAL(name, type) ((type)dlsym(RTLD_NEXT, name))
static __thread int inside;
static pthread_mutex_t table_lock = PTHREAD_MUTEX_INITIALIZER;
struct tracked {
    int fd, writing, directory; uint64_t inode; long offset;
    char path[PATH_MAX]; unsigned char *page; size_t length, position;
    struct dirent entry; struct tracked *next;
};
static struct tracked *files;
static size_t file_count;
static void saf_fork_prepare(void) { pthread_mutex_lock(&table_lock); }
static void saf_fork_done(void) { pthread_mutex_unlock(&table_lock); }
__attribute__((constructor)) static void saf_initialize(void) { pthread_atfork(saf_fork_prepare,saf_fork_done,saf_fork_done); }
struct response { int32_t error, mode; int64_t size, modified, inode; uint32_t length, reserved; } __attribute__((packed));
struct request { uint32_t magic; int32_t op, flags, offset; uint32_t length, second_length; };
static int real_close(int fd) { return REAL("close", int (*)(int))(fd); }
static int real_open(const char *p, int f, mode_t m) { return REAL("open", int (*)(const char *, int, ...))(p, f, m); }
static struct tracked *find_fd(int fd) { for (struct tracked *x = files; x; x = x->next) if (x->fd == fd) return x; return NULL; }
static void forget_fd(int fd) {
    pthread_mutex_lock(&table_lock);
    struct tracked **x = &files;
    while (*x) { if ((*x)->fd == fd) { struct tracked *old = *x; *x = old->next; free(old->page); free(old); --file_count; break; } x = &(*x)->next; }
    pthread_mutex_unlock(&table_lock);
}
static int remember_fd(int fd, const char *path, int writing, int directory, uint64_t inode) {
    forget_fd(fd);
    struct tracked *x = calloc(1, sizeof(*x));
    if (!x) { errno = ENOMEM; return -1; }
    x->fd = fd; x->writing = writing; x->directory = directory; x->inode = inode;
    snprintf(x->path, sizeof(x->path), "%s", path);
    pthread_mutex_lock(&table_lock);
    if (file_count >= 4096) { pthread_mutex_unlock(&table_lock); free(x); errno = EMFILE; return -1; }
    x->next = files; files = x; ++file_count; pthread_mutex_unlock(&table_lock); return 0;
}
static int fd_info(int fd, char *path, int *writing, int *directory, uint64_t *inode) {
    if(inside || !getenv("ASTRA_SAF_ROOT"))return 0;
    pthread_mutex_lock(&table_lock); struct tracked *x = find_fd(fd);
    if (x) { if(path) strcpy(path, x->path); if(writing) *writing=x->writing; if(directory) *directory=x->directory; if(inode) *inode=x->inode; }
    pthread_mutex_unlock(&table_lock); return x != NULL;
}
static int copy_fd(int old, int next) {
    char path[PATH_MAX]; int w,d; uint64_t ino;
    if (next >= 0 && fd_info(old,path,&w,&d,&ino)) return remember_fd(next,path,w,d,ino);
    return 0;
}

/* Resolve existing aliases in the Wine prefix, then append the not-yet-physical tail. */
static int virtual_path_at(int dirfd, const char *path, char *relative) {
    const char *root = getenv("ASTRA_SAF_ROOT");
    if (inside || !root || !*root || !getenv("ASTRA_SAF_SOCKET") || !path) return 0;
    char full[PATH_MAX], base[PATH_MAX], normalized[PATH_MAX];
    if (*path == '/') { if (strlen(path) >= sizeof(full)) return 0; strcpy(full,path); }
    else {
        if (dirfd == AT_FDCWD) { if (!getcwd(base,sizeof(base))) return 0; }
        else {
            char known[PATH_MAX];
            if (fd_info(dirfd,known,NULL,NULL,NULL)) snprintf(base,sizeof(base),"%s/%s",root,known);
            else { char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",dirfd);
                ssize_t n=REAL("readlink",ssize_t (*)(const char *,char *,size_t))(proc,base,sizeof(base)-1); if(n<0)return 0; base[n]=0; }
        }
        if (snprintf(full,sizeof(full),"%s/%s",base,path)>=(int)sizeof(full)) return 0;
    }
    // Known system/runtime paths cannot point into the source mount. Avoid extra filesystem
    // resolution on Wine's DLL/font probes, which otherwise turns on-demand storage into overhead.
    if(!strncmp(full,"/system/",8)||!strncmp(full,"/vendor/",8)||!strncmp(full,"/apex/",6))return 0;
    const char *runtime=getenv("ASTRA_RUNTIME_ROOT");size_t runtime_size=runtime?strlen(runtime):0;
    if(runtime_size&&!strncmp(full,runtime,runtime_size)&&full[runtime_size]=='/')return 0;
    const char *prefix=getenv("WINEPREFIX");size_t prefix_size=prefix?strlen(prefix):0;
    if(prefix_size&&!strncmp(full,prefix,prefix_size)&&full[prefix_size]=='/'&&!strstr(full,"/dosdevices/"))return 0;
    // realpath of the longest existing ancestor handles dosdevices/g: and its aliases.
    char ancestor[PATH_MAX], suffix[PATH_MAX]=""; strcpy(ancestor,full);
    char *canonical=NULL;
    ++inside;
    while (!(canonical=REAL("realpath",char *(*)(const char *,char *))(ancestor,NULL))) {
        char *slash=strrchr(ancestor,'/'); if(!slash || slash==ancestor) break;
        char rest[PATH_MAX]; if(snprintf(rest,sizeof(rest),"/%s%s",slash+1,suffix)>=(int)sizeof(rest)) break;
        strcpy(suffix,rest); *slash=0;
    }
    --inside;
    if (!canonical) return 0;
    if (snprintf(full,sizeof(full),"%s%s",canonical,suffix)>=(int)sizeof(full)) { free(canonical); return 0; }
    free(canonical);
    size_t used=0; char *save=NULL; char *segment=strtok_r(full,"/",&save); normalized[0]=0;
    while(segment) {
        if(!strcmp(segment,"..")) { if(used) { char *slash=strrchr(normalized,'/'); if(slash) { used=(size_t)(slash-normalized); normalized[used]=0; } } }
        else if(strcmp(segment,".")) { size_t n=strlen(segment); if(used+n+2>=sizeof(normalized))return 0; normalized[used++]='/'; memcpy(normalized+used,segment,n+1); used+=n; }
        segment=strtok_r(NULL,"/",&save);
    }
    size_t n=strlen(root);
    if(strncmp(normalized,root,n) || (normalized[n] && normalized[n]!='/')) return 0;
    strcpy(relative,normalized+n+(normalized[n]=='/')); return 1;
}
static int virtual_path(const char *p,char *r) { return virtual_path_at(AT_FDCWD,p,r); }

static int send_all(int fd,const void *buffer,size_t size) {
    const char *p=buffer; while(size) { ssize_t n=send(fd,p,size,MSG_NOSIGNAL); if(n<=0){if(errno==EINTR)continue;return -1;}p+=n;size-=(size_t)n; }return 0;
}
static int read_all(int fd,void *buffer,size_t size) {
    char *p=buffer; while(size){ssize_t n=recv(fd,p,size,0);if(n<=0){if(errno==EINTR)continue;errno=EIO;return -1;}p+=n;size-=(size_t)n;}return 0;
}
static int rpc(int op,const char *path,int flags,int offset,const char *second,struct response *result,int *passed,unsigned char **payload) {
    int error=EIO, fd=-1, received=-1; unsigned char *data=NULL;
    const char *name=getenv("ASTRA_SAF_SOCKET"); if(!name){errno=ENODEV;return -1;}
    ++inside;
    fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); if(fd<0)goto done;
    struct timeval timeout={.tv_sec=15}; setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)); setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_un address={.sun_family=AF_UNIX}; size_t name_size=strlen(name);
    if(name_size>=sizeof(address.sun_path)-1){error=ENAMETOOLONG;goto done;}
    memcpy(address.sun_path+1,name,name_size);
    if(REAL("connect",int (*)(int,const struct sockaddr *,socklen_t))(fd,(struct sockaddr *)&address,offsetof(struct sockaddr_un,sun_path)+name_size+1)){error=errno;goto done;}
    struct request req={MAGIC,op,flags,offset,(uint32_t)strlen(path),(uint32_t)(second?strlen(second):0)};
    if(send_all(fd,&req,sizeof(req)) || send_all(fd,path,req.length) || (req.second_length && send_all(fd,second,req.second_length)))goto done;
    char control[CMSG_SPACE(sizeof(int))]; struct iovec iov={result,sizeof(*result)};
    struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    ssize_t n=recvmsg(fd,&msg,MSG_CMSG_CLOEXEC); if(n<=0)goto done;
    for(struct cmsghdr *c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c)) if(c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS) memcpy(&received,CMSG_DATA(c),sizeof(int));
    if((size_t)n<sizeof(*result) && read_all(fd,((char *)result)+n,sizeof(*result)-(size_t)n))goto done;
    if(result->error){error=result->error;goto done;}
    if(result->length>65536){error=EOVERFLOW;goto done;}
    if(result->length){data=malloc(result->length);if(!data){error=ENOMEM;goto done;}if(read_all(fd,data,result->length))goto done;}
    error=0;
done:
    if(fd>=0)real_close(fd);
    if(!error){if(passed)*passed=received;else if(received>=0)real_close(received);if(payload)*payload=data;else free(data);}
    else {if(received>=0)real_close(received);free(data);}
    --inside;errno=error;return error?-1:0;
}
static void fill_stat(struct stat *st,const struct response *r) {
    memset(st,0,sizeof(*st)); st->st_mode=r->mode;st->st_nlink=S_ISDIR(r->mode)?2:1;st->st_uid=getuid();st->st_gid=getgid();
    st->st_dev=DEVICE;st->st_ino=(ino_t)r->inode;st->st_size=r->size;st->st_blksize=4096;st->st_blocks=(r->size+511)/512;
    st->st_mtim.tv_sec=r->modified/1000;st->st_mtim.tv_nsec=(r->modified%1000)*1000000;st->st_atim=st->st_ctim=st->st_mtim;
}
static int vstat(const char *path,struct stat *st) { struct response r;if(rpc(STAT,path,0,0,NULL,&r,NULL,NULL))return -1;fill_stat(st,&r);return 0; }
static int vopen(const char *path,int flags) {
    struct response r;int fd=-1;if(rpc(OPEN,path,flags,0,NULL,&r,&fd,NULL))return -1;
    if(fd<0){errno=EIO;return -1;}
    int writing=(flags&O_ACCMODE)!=O_RDONLY || (flags&(O_CREAT|O_TRUNC));
    if(remember_fd(fd,path,writing,S_ISDIR(r.mode),(uint64_t)r.inode)){real_close(fd);return -1;}
    if(!(flags&O_CLOEXEC)) REAL("fcntl",int (*)(int,int,...))(fd,F_SETFD,0);
    if(flags&O_APPEND) REAL("fcntl",int (*)(int,int,...))(fd,F_SETFL,O_APPEND);
    return fd;
}
int open(const char *path,int flags,...) { mode_t mode=0;if(flags&O_CREAT){va_list ap;va_start(ap,flags);mode=va_arg(ap,int);va_end(ap);}char p[PATH_MAX];if(virtual_path(path,p))return vopen(p,flags);return real_open(path,flags,mode); }
int open64(const char *path,int flags,...) { mode_t mode=0;if(flags&O_CREAT){va_list ap;va_start(ap,flags);mode=va_arg(ap,int);va_end(ap);}return open(path,flags,mode); }
int openat(int fd,const char *path,int flags,...) { mode_t mode=0;if(flags&O_CREAT){va_list ap;va_start(ap,flags);mode=va_arg(ap,int);va_end(ap);}char p[PATH_MAX];if(virtual_path_at(fd,path,p))return vopen(p,flags);return REAL("openat",int (*)(int,const char *,int,...))(fd,path,flags,mode); }
int openat64(int fd,const char *path,int flags,...) { mode_t mode=0;if(flags&O_CREAT){va_list ap;va_start(ap,flags);mode=va_arg(ap,int);va_end(ap);}return openat(fd,path,flags,mode); }
int __open_2(const char *p,int f){return open(p,f,0);}
int __open64_2(const char *p,int f){return open(p,f,0);}
int stat(const char *path,struct stat *st){char p[PATH_MAX];if(virtual_path(path,p))return vstat(p,st);return REAL("stat",int (*)(const char *,struct stat *))(path,st);}
int lstat(const char *path,struct stat *st){char p[PATH_MAX];if(virtual_path(path,p))return vstat(p,st);return REAL("lstat",int (*)(const char *,struct stat *))(path,st);}
int fstatat(int fd,const char *path,struct stat *st,int flags){char p[PATH_MAX];if(virtual_path_at(fd,path,p))return vstat(p,st);return REAL("fstatat",int (*)(int,const char *,struct stat *,int))(fd,path,st,flags);}
int fstat(int fd,struct stat *st){int result=REAL("fstat",int (*)(int,struct stat *))(fd,st);uint64_t ino;if(!result && fd_info(fd,NULL,NULL,NULL,&ino)){st->st_dev=DEVICE;st->st_ino=(ino_t)ino;}return result;}
int stat64(const char *p,struct stat64 *s){return stat(p,(struct stat *)s);}
int lstat64(const char *p,struct stat64 *s){return lstat(p,(struct stat *)s);}
int fstat64(int f,struct stat64 *s){return fstat(f,(struct stat *)s);}
int fstatat64(int f,const char *p,struct stat64 *s,int flags){return fstatat(f,p,(struct stat *)s,flags);}
int access(const char *path,int mode){char p[PATH_MAX];if(virtual_path(path,p)){struct stat st;if(vstat(p,&st))return -1;if(mode&X_OK && !S_ISDIR(st.st_mode)){errno=EACCES;return -1;}return 0;}return REAL("access",int (*)(const char *,int))(path,mode);}
int faccessat(int fd,const char *path,int mode,int flags){char p[PATH_MAX];if(virtual_path_at(fd,path,p)){struct stat st;return vstat(p,&st);}return REAL("faccessat",int (*)(int,const char *,int,int))(fd,path,mode,flags);}

static int operation(int op,const char *path,const char *second){struct response r;return rpc(op,path,0,0,second,&r,NULL,NULL);}
int mkdir(const char *path,mode_t mode){char p[PATH_MAX];if(virtual_path(path,p))return operation(MKDIR,p,NULL);return REAL("mkdir",int (*)(const char *,mode_t))(path,mode);}
int mkdirat(int fd,const char *path,mode_t mode){char p[PATH_MAX];if(virtual_path_at(fd,path,p))return operation(MKDIR,p,NULL);return REAL("mkdirat",int (*)(int,const char *,mode_t))(fd,path,mode);}
int unlink(const char *path){char p[PATH_MAX];if(virtual_path(path,p))return operation(DELETE,p,NULL);return REAL("unlink",int (*)(const char *))(path);}
int rmdir(const char *path){char p[PATH_MAX];if(virtual_path(path,p)){struct response r;return rpc(DELETE,p,AT_REMOVEDIR,0,NULL,&r,NULL,NULL);}return REAL("rmdir",int (*)(const char *))(path);}
int unlinkat(int fd,const char *path,int flags){char p[PATH_MAX];if(virtual_path_at(fd,path,p)){struct response r;return rpc(DELETE,p,flags,0,NULL,&r,NULL,NULL);}return REAL("unlinkat",int (*)(int,const char *,int))(fd,path,flags);}
static int vrename(const char *from,const char *to){
    int result=operation(RENAME,from,to);if(result)return result;
    size_t n=strlen(from);pthread_mutex_lock(&table_lock);
    for(struct tracked *x=files;x;x=x->next) if(!strncmp(x->path,from,n) && (!x->path[n]||x->path[n]=='/')) {
        char next[PATH_MAX];if(snprintf(next,sizeof(next),"%s%s",to,x->path+n)<(int)sizeof(next))strcpy(x->path,next);
    }
    pthread_mutex_unlock(&table_lock);return 0;
}
int rename(const char *from,const char *to){char a[PATH_MAX],b[PATH_MAX];int av=virtual_path(from,a),bv=virtual_path(to,b);if(av&&bv)return vrename(a,b);if(av||bv){errno=EXDEV;return -1;}return REAL("rename",int (*)(const char *,const char *))(from,to);}
int renameat(int af,const char *from,int bf,const char *to){char a[PATH_MAX],b[PATH_MAX];int av=virtual_path_at(af,from,a),bv=virtual_path_at(bf,to,b);if(av&&bv)return vrename(a,b);if(av||bv){errno=EXDEV;return -1;}return REAL("renameat",int (*)(int,const char *,int,const char *))(af,from,bf,to);}

int close(int fd){char path[PATH_MAX];int writing=0;int tracked=fd_info(fd,path,&writing,NULL,NULL);int result=0,error=0;if(tracked&&writing&&!inside){if(REAL("fsync",int (*)(int))(fd)){result=-1;error=errno;}else if(operation(COMMIT,path,NULL)){result=-1;error=errno;}}forget_fd(fd);int rc=real_close(fd);if(rc<0&&result==0)return rc;if(result<0)errno=error;return result;}
int fsync(int fd){int result=REAL("fsync",int (*)(int))(fd);char p[PATH_MAX];int w;if(!result&&!inside&&fd_info(fd,p,&w,NULL,NULL)&&w)return operation(COMMIT,p,NULL);return result;}
int fdatasync(int fd){return fsync(fd);}
int dup(int fd){int result=REAL("dup",int (*)(int))(fd);if(copy_fd(fd,result)){real_close(result);return -1;}return result;}
int dup2(int fd,int to){int result=REAL("dup2",int (*)(int,int))(fd,to);if(fd!=to){forget_fd(to);if(copy_fd(fd,result)){real_close(result);return -1;}}return result;}
int dup3(int fd,int to,int flags){int result=REAL("dup3",int (*)(int,int,int))(fd,to,flags);if(result>=0){forget_fd(to);if(copy_fd(fd,result)){real_close(result);return -1;}}return result;}
int fcntl(int fd,int cmd,...){
    long arg=0;switch(cmd){case F_GETFD:case F_GETFL:case F_GETOWN:break;default:{va_list ap;va_start(ap,cmd);arg=va_arg(ap,long);va_end(ap);}}
    int result=REAL("fcntl",int (*)(int,int,...))(fd,cmd,arg);
    if((cmd==F_DUPFD || cmd==F_DUPFD_CLOEXEC) && result>=0 && copy_fd(fd,result)){real_close(result);return -1;}return result;
}
static int reserve_write(int fd,size_t length,off_t at){char path[PATH_MAX];int w;if(inside || !fd_info(fd,path,&w,NULL,NULL)||!w)return 0;
    struct stat st;if(REAL("fstat",int (*)(int,struct stat *))(fd,&st))return -1;
    if(at<0){at=REAL("lseek",off_t (*)(int,off_t,int))(fd,0,SEEK_CUR);if(at<0)return -1;
        if(REAL("fcntl",int (*)(int,int,...))(fd,F_GETFL,0)&O_APPEND)at=st.st_size;}
    if(at>FILE_LIMIT || length>(uint64_t)(FILE_LIMIT-at)){errno=EFBIG;return -1;}
    if(at+(off_t)length>st.st_size){struct response r;return rpc(RESERVE,path,0,(int)(at+length),NULL,&r,NULL,NULL);}return 0;
}
ssize_t write(int fd,const void *data,size_t length){if(reserve_write(fd,length,-1))return -1;return REAL("write",ssize_t (*)(int,const void *,size_t))(fd,data,length);}
ssize_t pwrite(int fd,const void *data,size_t length,off_t offset){if(offset>=0 && reserve_write(fd,length,offset))return -1;return REAL("pwrite",ssize_t (*)(int,const void *,size_t,off_t))(fd,data,length,offset);}
ssize_t pwrite64(int f,const void *p,size_t n,off64_t o){return pwrite(f,p,n,o);}
ssize_t writev(int fd,const struct iovec *iov,int count){size_t total=0;for(int i=0;i<count;i++){if(iov[i].iov_len>SIZE_MAX-total){errno=EOVERFLOW;return -1;}total+=iov[i].iov_len;}if(reserve_write(fd,total,-1))return -1;return REAL("writev",ssize_t (*)(int,const struct iovec *,int))(fd,iov,count);}
ssize_t pwritev(int fd,const struct iovec *iov,int count,off_t offset){size_t total=0;for(int i=0;i<count;i++){if(iov[i].iov_len>SIZE_MAX-total){errno=EOVERFLOW;return -1;}total+=iov[i].iov_len;}if(offset>=0&&reserve_write(fd,total,offset))return -1;return REAL("pwritev",ssize_t (*)(int,const struct iovec *,int,off_t))(fd,iov,count,offset);}
int ftruncate(int fd,off_t length){if(length>=0 && reserve_write(fd,0,length))return -1;return REAL("ftruncate",int (*)(int,off_t))(fd,length);}
int ftruncate64(int f,off64_t n){return ftruncate(f,n);}

/* Bionic stdio uses hidden open/close symbols, so route the stream's FD explicitly. */
FILE *fopen(const char *path,const char *mode){char p[PATH_MAX];if(!virtual_path(path,p))return REAL("fopen",FILE *(*)(const char *,const char *))(path,mode);
    int flags;switch(*mode){case 'r':flags=O_RDONLY;break;case 'w':flags=O_WRONLY|O_CREAT|O_TRUNC;break;case 'a':flags=O_WRONLY|O_CREAT|O_APPEND;break;default:errno=EINVAL;return NULL;}
    if(strchr(mode,'+'))flags=(flags&~O_ACCMODE)|O_RDWR;if(strchr(mode,'x'))flags|=O_EXCL;if(strchr(mode,'e'))flags|=O_CLOEXEC;
    int fd=vopen(p,flags);if(fd<0)return NULL;FILE *stream=REAL("fdopen",FILE *(*)(int,const char *))(fd,mode);if(!stream){forget_fd(fd);real_close(fd);}return stream;
}
FILE *fopen64(const char *path,const char *mode){return fopen(path,mode);}
size_t fwrite(const void *data,size_t size,size_t count,FILE *stream){if(size&&count>SIZE_MAX/size){errno=EOVERFLOW;return 0;}int fd=fileno(stream);off_t at=REAL("ftello",off_t (*)(FILE *))(stream);if(reserve_write(fd,size*count,at))return 0;return REAL("fwrite",size_t (*)(const void *,size_t,size_t,FILE *))(data,size,count,stream);}
int fputs(const char *text,FILE *stream){int fd=fileno(stream);off_t at=REAL("ftello",off_t (*)(FILE *))(stream);if(reserve_write(fd,strlen(text),at))return EOF;return REAL("fputs",int (*)(const char *,FILE *))(text,stream);}
int fclose(FILE *stream){int fd=fileno(stream),writing=0;char path[PATH_MAX];int tracked=fd_info(fd,path,&writing,NULL,NULL),error=0;
    if(tracked&&writing&&!inside){if(fflush(stream)||REAL("fsync",int (*)(int))(fd)||operation(COMMIT,path,NULL))error=errno?errno:EIO;}
    forget_fd(fd);int result=REAL("fclose",int (*)(FILE *))(stream);if(error){errno=error;return EOF;}return result;
}

/* A real empty local directory supplies cwd/dirfd; names are paged from provider metadata. */
DIR *opendir(const char *path){char p[PATH_MAX];if(!virtual_path(path,p))return REAL("opendir",DIR *(*)(const char *))(path);
    struct response r;if(rpc(OPEN,p,O_RDONLY|O_DIRECTORY|O_CLOEXEC,0,NULL,&r,NULL,NULL))return NULL;
    const char *root=getenv("ASTRA_SAF_ROOT");char local[PATH_MAX];snprintf(local,sizeof(local),"%s/%s",root,p);
    ++inside;DIR *d=REAL("opendir",DIR *(*)(const char *))(local);--inside;
    if(d && remember_fd(REAL("dirfd",int (*)(DIR *))(d),p,0,1,(uint64_t)r.inode)){REAL("closedir",int (*)(DIR *))(d);return NULL;}return d;
}
DIR *fdopendir(int fd){return REAL("fdopendir",DIR *(*)(int))(fd);}
struct dirent *readdir(DIR *directory){int fd=REAL("dirfd",int (*)(DIR *))(directory);char path[PATH_MAX];int d;if(!fd_info(fd,path,NULL,&d,NULL)||!d)return REAL("readdir",struct dirent *(*)(DIR *))(directory);
    pthread_mutex_lock(&table_lock);struct tracked *x=find_fd(fd);if(!x){pthread_mutex_unlock(&table_lock);errno=EBADF;return NULL;}
    if(x->position>=x->length){free(x->page);x->page=NULL;x->length=x->position=0;struct response r;unsigned char *page=NULL;
        if(rpc(LIST,path,0,(int)x->offset,NULL,&r,NULL,&page)){pthread_mutex_unlock(&table_lock);return NULL;}x->page=page;x->length=r.length;if(!r.length){pthread_mutex_unlock(&table_lock);errno=0;return NULL;}}
    if(x->length-x->position<16){pthread_mutex_unlock(&table_lock);errno=EIO;return NULL;}
    uint32_t n,mode;uint64_t ino;memcpy(&n,x->page+x->position,4);memcpy(&mode,x->page+x->position+4,4);memcpy(&ino,x->page+x->position+8,8);
    if(n>=sizeof(x->entry.d_name)||n+16>x->length-x->position){pthread_mutex_unlock(&table_lock);errno=EOVERFLOW;return NULL;}
    memset(&x->entry,0,sizeof(x->entry));x->entry.d_ino=ino;x->entry.d_off=++x->offset;x->entry.d_type=S_ISDIR(mode)?DT_DIR:DT_REG;
    memcpy(x->entry.d_name,x->page+x->position+16,n);x->entry.d_reclen=(unsigned short)((offsetof(struct dirent,d_name)+n+1+7)&~7);x->position+=n+16;
    struct dirent *entry=&x->entry;pthread_mutex_unlock(&table_lock);return entry;
}
struct dirent64 *readdir64(DIR *d){return (struct dirent64 *)readdir(d);}
int closedir(DIR *d){forget_fd(REAL("dirfd",int (*)(DIR *))(d));return REAL("closedir",int (*)(DIR *))(d);}
void rewinddir(DIR *d){int fd=REAL("dirfd",int (*)(DIR *))(d);pthread_mutex_lock(&table_lock);struct tracked *x=find_fd(fd);if(x){x->offset=0;x->position=x->length=0;}pthread_mutex_unlock(&table_lock);REAL("rewinddir",void (*)(DIR *))(d);}
long telldir(DIR *d){int fd=REAL("dirfd",int (*)(DIR *))(d);pthread_mutex_lock(&table_lock);struct tracked *x=find_fd(fd);long o=x?x->offset:-1;pthread_mutex_unlock(&table_lock);return o>=0?o:REAL("telldir",long (*)(DIR *))(d);}
void seekdir(DIR *d,long o){int fd=REAL("dirfd",int (*)(DIR *))(d);pthread_mutex_lock(&table_lock);struct tracked *x=find_fd(fd);if(x){x->offset=o;x->position=x->length=0;}pthread_mutex_unlock(&table_lock);if(!x)REAL("seekdir",void (*)(DIR *,long))(d,o);}
off_t lseek(int fd,off_t off,int how){int d;if(fd_info(fd,NULL,NULL,&d,NULL)&&d){pthread_mutex_lock(&table_lock);struct tracked *x=find_fd(fd);if(x){if(how==SEEK_SET)x->offset=off;else if(how==SEEK_CUR)x->offset+=off;else{pthread_mutex_unlock(&table_lock);errno=EINVAL;return -1;}x->position=x->length=0;off=x->offset;}pthread_mutex_unlock(&table_lock);return off;}return REAL("lseek",off_t (*)(int,off_t,int))(fd,off,how);}
off64_t lseek64(int fd,off64_t o,int h){return lseek(fd,o,h);}
int chdir(const char *path){char p[PATH_MAX];if(virtual_path(path,p)){struct response r;int fd=-1;if(rpc(OPEN,p,O_RDONLY|O_DIRECTORY|O_CLOEXEC,0,NULL,&r,&fd,NULL))return -1;int result=REAL("fchdir",int (*)(int))(fd);real_close(fd);return result;}return REAL("chdir",int (*)(const char *))(path);}
ssize_t readlink(const char *path,char *out,size_t size){int fd=-1;char relative[PATH_MAX];if(sscanf(path,"/proc/self/fd/%d",&fd)==1&&fd_info(fd,relative,NULL,NULL,NULL)){char full[PATH_MAX];snprintf(full,sizeof(full),"%s/%s",getenv("ASTRA_SAF_ROOT"),relative);size_t n=strlen(full);if(n>size)n=size;memcpy(out,full,n);return (ssize_t)n;}return REAL("readlink",ssize_t (*)(const char *,char *,size_t))(path,out,size);}
char *astra_saf_realpath(const char *path,char *resolved){char p[PATH_MAX];if(!virtual_path(path,p))return NULL;struct stat st;if(vstat(p,&st))return (char *)-1;char full[PATH_MAX];snprintf(full,sizeof(full),"%s%s%s",getenv("ASTRA_SAF_ROOT"),*p?"/":"",p);if(resolved){strcpy(resolved,full);return resolved;}return strdup(full);}

/* Instrumentation exercises the exact bridge without installing or running a third-party APK. */
/* JNI loads the library locally; resolve its own exports to avoid libc symbol preemption. */
#define TEST(name,type) ((type)dlsym(bridge,name))
JNIEXPORT jstring JNICALL Java_fr_astragames_app_windows_WolfSafBridge_probe(JNIEnv *env,jobject self,jstring root_value,jstring socket_value) {
    (void)self;
    Dl_info info;dladdr((void *)&Java_fr_astragames_app_windows_WolfSafBridge_probe,&info);void *bridge=dlopen(info.dli_fname,RTLD_NOW);
    const char *root=(*env)->GetStringUTFChars(env,root_value,NULL),*sock=(*env)->GetStringUTFChars(env,socket_value,NULL);
    char *old_root=getenv("ASTRA_SAF_ROOT")?strdup(getenv("ASTRA_SAF_ROOT")):NULL,*old_sock=getenv("ASTRA_SAF_SOCKET")?strdup(getenv("ASTRA_SAF_SOCKET")):NULL;
    setenv("ASTRA_SAF_ROOT",root,1);setenv("ASTRA_SAF_SOCKET",sock,1);
    char path[PATH_MAX],result[512]="OK";int fd=-1;DIR *dir=NULL;snprintf(path,sizeof(path),"%s/Data 日本語/asset.bin",root);
    fd=TEST("open",int (*)(const char *,int,...))(path,O_RDONLY);if(fd<0){snprintf(result,sizeof(result),"open read errno=%d",errno);goto done;}
    struct stat st;if(TEST("fstat",int (*)(int,struct stat *))(fd,&st)||st.st_size!=20){snprintf(result,sizeof(result),"stat size=%lld errno=%d",(long long)st.st_size,errno);goto done;}
    void *mapped=mmap(NULL,20,PROT_READ,MAP_PRIVATE,fd,0);if(mapped==MAP_FAILED||memcmp(mapped,"streamed fixture 123",20)){snprintf(result,sizeof(result),"mmap errno=%d",errno);if(mapped!=MAP_FAILED)munmap(mapped,20);goto done;}munmap(mapped,20);TEST("close",int (*)(int))(fd);fd=-1;
    snprintf(path,sizeof(path),"%s/Data 日本語",root);dir=TEST("opendir",DIR *(*)(const char *))(path);if(!dir){snprintf(result,sizeof(result),"opendir errno=%d",errno);goto done;}
    int found=0;struct dirent *entry;while((entry=TEST("readdir",struct dirent *(*)(DIR *))(dir)))if(!strcmp(entry->d_name,"asset.bin"))found=1;TEST("closedir",int (*)(DIR *))(dir);dir=NULL;if(!found){strcpy(result,"directory asset missing");goto done;}
    snprintf(path,sizeof(path),"%s/Save",root);if(TEST("mkdir",int (*)(const char *,mode_t))(path,0700)&&errno!=EEXIST){snprintf(result,sizeof(result),"mkdir errno=%d",errno);goto done;}
    snprintf(path,sizeof(path),"%s/Save/slot.sav",root);fd=TEST("open",int (*)(const char *,int,...))(path,O_CREAT|O_TRUNC|O_RDWR,0600);if(fd<0){snprintf(result,sizeof(result),"open save errno=%d",errno);goto done;}
    if(TEST("write",ssize_t (*)(int,const void *,size_t))(fd,"native durable save",19)!=19 || TEST("fsync",int (*)(int))(fd)){snprintf(result,sizeof(result),"write/commit errno=%d",errno);goto done;}if(TEST("close",int (*)(int))(fd)){fd=-1;snprintf(result,sizeof(result),"close errno=%d",errno);goto done;}fd=-1;
    char next[PATH_MAX];snprintf(next,sizeof(next),"%s/Save/renamed.sav",root);if(TEST("rename",int (*)(const char *,const char *))(path,next)){snprintf(result,sizeof(result),"rename errno=%d",errno);goto done;}
    fd=TEST("open",int (*)(const char *,int,...))(next,O_RDONLY);char buffer[20]={0};if(fd<0||read(fd,buffer,19)!=19||strcmp(buffer,"native durable save")){snprintf(result,sizeof(result),"read saved errno=%d",errno);goto done;}
done:
    if(fd>=0)TEST("close",int (*)(int))(fd);if(dir)TEST("closedir",int (*)(DIR *))(dir);dlclose(bridge);
    if(old_root){setenv("ASTRA_SAF_ROOT",old_root,1);free(old_root);}else unsetenv("ASTRA_SAF_ROOT");if(old_sock){setenv("ASTRA_SAF_SOCKET",old_sock,1);free(old_sock);}else unsetenv("ASTRA_SAF_SOCKET");
    (*env)->ReleaseStringUTFChars(env,root_value,root);(*env)->ReleaseStringUTFChars(env,socket_value,sock);return (*env)->NewStringUTF(env,result);
}

JNIEXPORT jstring JNICALL Java_fr_astragames_app_windows_WolfSafBridge_stage(JNIEnv *env,jobject self,jstring root_value,jstring socket_value,jstring path_value,jstring data_value,jboolean commit,jobject before_commit) {
    (void)self;
    Dl_info info;dladdr((void *)&Java_fr_astragames_app_windows_WolfSafBridge_stage,&info);void *bridge=dlopen(info.dli_fname,RTLD_NOW);
    const char *root=(*env)->GetStringUTFChars(env,root_value,NULL),*sock=(*env)->GetStringUTFChars(env,socket_value,NULL);
    const char *relative=(*env)->GetStringUTFChars(env,path_value,NULL),*data=(*env)->GetStringUTFChars(env,data_value,NULL);
    char *old_root=getenv("ASTRA_SAF_ROOT")?strdup(getenv("ASTRA_SAF_ROOT")):NULL,*old_sock=getenv("ASTRA_SAF_SOCKET")?strdup(getenv("ASTRA_SAF_SOCKET")):NULL;
    setenv("ASTRA_SAF_ROOT",root,1);setenv("ASTRA_SAF_SOCKET",sock,1);
    char path[PATH_MAX],result[128]="OK";snprintf(path,sizeof(path),"%s/%s",root,relative);
    int fd=TEST("open",int (*)(const char *,int,...))(path,O_CREAT|O_TRUNC|O_RDWR,0600);
    if(fd<0)snprintf(result,sizeof(result),"stage open errno=%d",errno);
    else {
        if(TEST("write",ssize_t (*)(int,const void *,size_t))(fd,data,strlen(data))!=(ssize_t)strlen(data) || REAL("fsync",int (*)(int))(fd))snprintf(result,sizeof(result),"stage write errno=%d",errno);
        if(before_commit && !strcmp(result,"OK")) {
            jclass type=(*env)->GetObjectClass(env,before_commit);
            jmethodID run=(*env)->GetMethodID(env,type,"run","()V");
            if(run)(*env)->CallVoidMethod(env,before_commit,run);
            (*env)->DeleteLocalRef(env,type);
            if((*env)->ExceptionCheck(env))strcpy(result,"fixture callback failed");
        }
        if(commit && !strcmp(result,"OK") && operation(COMMIT,relative,NULL))snprintf(result,sizeof(result),"stage commit errno=%d",errno);
        /* Without COMMIT this models a process lost with an unfinished open descriptor. */
        forget_fd(fd);real_close(fd);
    }
    dlclose(bridge);
    if(old_root){setenv("ASTRA_SAF_ROOT",old_root,1);free(old_root);}else unsetenv("ASTRA_SAF_ROOT");if(old_sock){setenv("ASTRA_SAF_SOCKET",old_sock,1);free(old_sock);}else unsetenv("ASTRA_SAF_SOCKET");
    (*env)->ReleaseStringUTFChars(env,root_value,root);(*env)->ReleaseStringUTFChars(env,socket_value,sock);
    (*env)->ReleaseStringUTFChars(env,path_value,relative);(*env)->ReleaseStringUTFChars(env,data_value,data);
    if((*env)->ExceptionCheck(env))return NULL;
    return (*env)->NewStringUTF(env,result);
}

JNIEXPORT jstring JNICALL Java_fr_astragames_app_windows_WolfSafBridge_read(JNIEnv *env,jobject self,jstring root_value,jstring socket_value,jstring path_value) {
    (void)self;
    Dl_info info;dladdr((void *)&Java_fr_astragames_app_windows_WolfSafBridge_read,&info);void *bridge=dlopen(info.dli_fname,RTLD_NOW);
    const char *root=(*env)->GetStringUTFChars(env,root_value,NULL),*sock=(*env)->GetStringUTFChars(env,socket_value,NULL);
    const char *relative=(*env)->GetStringUTFChars(env,path_value,NULL);
    char *old_root=getenv("ASTRA_SAF_ROOT")?strdup(getenv("ASTRA_SAF_ROOT")):NULL,*old_sock=getenv("ASTRA_SAF_SOCKET")?strdup(getenv("ASTRA_SAF_SOCKET")):NULL;
    setenv("ASTRA_SAF_ROOT",root,1);setenv("ASTRA_SAF_SOCKET",sock,1);
    char path[PATH_MAX],result[4096]={0};snprintf(path,sizeof(path),"%s/%s",root,relative);
    int fd=TEST("open",int (*)(const char *,int,...))(path,O_RDONLY);
    if(fd<0)snprintf(result,sizeof(result),"read open errno=%d",errno);
    else { ssize_t n=read(fd,result,sizeof(result)-1);if(n<0)snprintf(result,sizeof(result),"read errno=%d",errno);TEST("close",int (*)(int))(fd); }
    dlclose(bridge);
    if(old_root){setenv("ASTRA_SAF_ROOT",old_root,1);free(old_root);}else unsetenv("ASTRA_SAF_ROOT");if(old_sock){setenv("ASTRA_SAF_SOCKET",old_sock,1);free(old_sock);}else unsetenv("ASTRA_SAF_SOCKET");
    (*env)->ReleaseStringUTFChars(env,root_value,root);(*env)->ReleaseStringUTFChars(env,socket_value,sock);(*env)->ReleaseStringUTFChars(env,path_value,relative);
    return (*env)->NewStringUTF(env,result);
}
