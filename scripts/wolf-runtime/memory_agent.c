/* Astra's original Wine memory editor. No injection, remote port, or game code.
 * Runs inside one Wine prefix; only the exact executable in target.txt is eligible.
 * IPC files live in Astra's private, random per-launch directory. Win64 ABI. */
typedef unsigned long D;
typedef unsigned long long U;
typedef long long I;
typedef unsigned short W;
typedef void *H;
void *memset(void *p,int value,U length) { volatile unsigned char *b=p;for(U i=0;i<length;i++)b[i]=(unsigned char)value;return p; }
#define API __declspec(dllimport)
API H CreateFileA(const char*,D,D,void*,D,D,H);
API int ReadFile(H,void*,D,D*,void*);
API int WriteFile(H,const void*,D,D*,void*);
API int CloseHandle(H);
API int MoveFileExA(const char*,const char*,D);
API int DeleteFileA(const char*);
API D GetFileAttributesA(const char*);
API D GetLastError(void);
API void Sleep(D);
API void ExitProcess(D);
API H CreateToolhelp32Snapshot(D,D);
typedef struct { D size,usage,pid; U heap; D module,threads,parent; long priority; D flags; W name[260]; } PE;
API int Process32FirstW(H,PE*);
API int Process32NextW(H,PE*);
API H OpenProcess(D,int,D);
API int QueryFullProcessImageNameW(H,D,W*,D*);
API int CompareStringOrdinal(const W*,int,const W*,int,int);
API int GetExitCodeProcess(H,D*);
API U GetTickCount64(void);
API int ReadProcessMemory(H,const void*,void*,U,U*);
API int WriteProcessMemory(H,void*,const void*,U,U*);
typedef struct { U base,allocation; D allocationProtect,pad; U size; D state,protect,type,pad2; } MB;
API U VirtualQueryEx(H,const void*,MB*,U);
API void *VirtualAlloc(void*,U,D,D);
API int VirtualFree(void*,U,D);

#define MAGIC 0x41535443
#define CAP 2097152
typedef struct { D magic,id,op,type,mode,limit; U address,value,other; D offset,reserved; } Request;
typedef struct { U address,value; } Row;
typedef struct { D magic,id,status,type; U count,scanned; D flags,error,rows,reserved; } Response;
typedef struct { U address,value,allocation; D type,enabled; } Pin;
static H target;
static W expected[2048], image[2048];
static Row *found, *previous;
static U count, oldCount, scanned, oldScanned, lastFreeze;
static D scanType, oldType, scanFlags, oldFlags;
static Pin pins[64];
static unsigned char chunk[65536];
static Request request;
static Response response;
static U lastCommand;
static D width(D type) { return type==0?1:type==1?2:(type==2||type==4)?4:8; }
static U mask(U bits,D type) { D n=width(type); return n==8?bits:bits&((1ULL<<(8*n))-1); }
static int active(void) { D code=0; return target && GetExitCodeProcess(target,&code) && code==259; }
static void refreshPins(void);
static int cancelled(void) { refreshPins();return GetFileAttributesA("cancel") != (D)-1 || !active(); }
static void release(Row **p) { if (*p) VirtualFree(*p,0,0x8000); *p=0; }
static int writable(U address,D n,MB *m) {
    if (!VirtualQueryEx(target,(void*)address,m,sizeof(*m))) return 0;
    return m->state==0x1000 && !(m->protect&0x100) && (m->protect==4||m->protect==8)
        && address>=m->base && address+n>=address && address+n<=m->base+m->size;
}
static int readValue(U address,D type,U *value) {
    U read=0; MB m; *value=0;
    return writable(address,width(type),&m) && ReadProcessMemory(target,(void*)address,value,width(type),&read) && read==width(type);
}
static int writeValue(U address,D type,U value) {
    U written=0; MB m;
    return writable(address,width(type),&m) && WriteProcessMemory(target,(void*)address,&value,width(type),&written) && written==width(type);
}
static void refreshPins(void) {
    U now=GetTickCount64();
    if(now-lastFreeze<250 || !active())return;
    for(int i=0;i<64;i++)if(pins[i].enabled) {
        MB m;
        if(!writable(pins[i].address,width(pins[i].type),&m)||m.allocation!=pins[i].allocation ||
           !writeValue(pins[i].address,pins[i].type,pins[i].value))pins[i].enabled=0;
    }
    lastFreeze=now;
}
static int compare(U a,U b,D type) {
    if(type==4) { union{D u;float f;} x,y; x.u=(D)a;y.u=(D)b;return x.f>y.f?1:x.f<y.f?-1:x.f==y.f?0:2; }
    if(type==5) { union{U u;double f;} x,y; x.u=a;y.u=b;return x.f>y.f?1:x.f<y.f?-1:x.f==y.f?0:2; }
    I x=type==0?(signed char)a:type==1?(short)a:type==2?(long)a:(I)a;
    I y=type==0?(signed char)b:type==1?(short)b:type==2?(long)b:(I)b;
    return x>y?1:x<y?-1:0;
}
/* modes: exact, unknown, changed, unchanged, increased, decreased, range. */
static int match(U value,U old) {
    int c=compare(value,old,request.type);
    switch(request.mode) {
        case 0:return compare(value,request.value,request.type)==0;
        case 1:return 1;
        case 2:return c!=0;
        case 3:return c==0;
        case 4:return c==1;
        case 5:return c==-1;
        case 6:return compare(value,request.value,request.type)>=0 && compare(value,request.value,request.type)<=1
            && compare(value,request.other,request.type)<=0;
    } return 0;
}
static void findTarget(void) {
    H file=CreateFileA("target.txt",0x80000000,3,0,3,0,0); D bytes=0;
    if(file==(H)-1)return;
    ReadFile(file,expected,sizeof(expected)-2,&bytes,0);CloseHandle(file);expected[bytes/2]=0;
    H snapshot=CreateToolhelp32Snapshot(2,0);if(snapshot==(H)-1)return;
    PE entry={0};entry.size=sizeof(entry);
    if(Process32FirstW(snapshot,&entry))do {
        H p=OpenProcess(0x438,0,entry.pid); if(!p)continue;
        D length=2048;
        if(QueryFullProcessImageNameW(p,0,image,&length) && CompareStringOrdinal(image,-1,expected,-1,1)==2) {
            if(target) { CloseHandle(target);CloseHandle(p);target=0;break; } /* ambiguous: refuse */
            target=p;
        } else CloseHandle(p);
    }while(Process32NextW(snapshot,&entry));
    CloseHandle(snapshot);
}
static void scan(void) {
    if(request.op==2 && (!found || request.type!=scanType)) {response.status=2;return;}
    Row *next=VirtualAlloc(0,(U)CAP*sizeof(Row),0x3000,4);
    if(!next){response.status=8;return;}
    U total=0,bytes=0;D flags=0,step=width(request.type);
    if(request.op==1) {
        U at=0;MB m;
        while(VirtualQueryEx(target,(void*)at,&m,sizeof(m))) {
            U end=m.base+m.size;if(end<=at)break;
            if(m.state==0x1000 && (m.protect==4||m.protect==8)) {
                for(U pos=m.base;pos<end;) {
                    if(cancelled())goto cancel;
                    U size=end-pos;if(size>sizeof(chunk))size=sizeof(chunk);
                    U got=0;
                    if(ReadProcessMemory(target,(void*)pos,chunk,size,&got)) {
                        bytes+=got;
                        for(U offset=0;offset+step<=got;offset+=step) {
                            U value=0;for(D b=0;b<step;b++)value|=(U)chunk[offset+b]<<(b*8);
                            if(match(value,0)) {
                                if(total==CAP){flags|=1;goto complete;}
                                next[total].address=pos+offset;next[total++].value=value;
                            }
                        }
                    }
                    pos+=size;
                }
            }
            at=end;
        }
    }else {
        flags=scanFlags;
        for(U i=0;i<count;i++) {
            if(!(i%1024)&&cancelled())goto cancel;
            U value;
            if(readValue(found[i].address,scanType,&value)) {
                bytes+=step;
                if(match(value,found[i].value)) {next[total].address=found[i].address;next[total++].value=value;}
            }
        }
    }
complete:
    release(&previous);previous=found;oldCount=count;oldType=scanType;oldFlags=scanFlags;oldScanned=scanned;
    found=next;count=total;scanType=request.type;scanned=bytes;scanFlags=flags;return;
cancel:
    VirtualFree(next,0,0x8000);response.status=6;
}
static Row rows[128];
static void respond(void) {
    response.magic=MAGIC;response.id=request.id;
    response.type=scanType;response.count=count;response.scanned=scanned;response.flags=scanFlags;
    H f=CreateFileA("response.tmp",0x40000000,0,0,2,0,0);if(f==(H)-1)return;
    D written;
    WriteFile(f,&response,sizeof(response),&written,0);
    if(response.rows)WriteFile(f,rows,response.rows*sizeof(Row),&written,0);
    CloseHandle(f);MoveFileExA("response.tmp","response.bin",1|8);
}
static void handle(void) {
    response=(Response){0};
    if(request.magic!=MAGIC||request.type>5||request.mode>6){response.status=2;respond();return;}
    if(request.op==9){respond();ExitProcess(0);}
    if(!target)findTarget();
    if(!target){response.status=1;respond();return;}
    if(!active()){response.status=4;respond();return;}
    switch(request.op) {
      case 0:break;
      case 1:case 2:scan();break;
      case 3:
        for(U i=request.offset;i<count && response.rows<128;i++)rows[response.rows++]=found[i];
        break;
      case 4: { U current;
        if(!readValue(request.address,request.type,&current)||current!=mask(request.other,request.type)){response.status=10;break;}
        if(!writeValue(request.address,request.type,request.value)){response.status=5;response.error=GetLastError();}
        break;
      }
      case 5:
        if(!readValue(request.address,request.type,&rows[0].value)){response.status=5;response.error=GetLastError();break;}
        rows[0].address=request.address;response.rows=1;break;
      case 6: { MB m; int slot=-1;U current;
        if(!readValue(request.address,request.type,&current)||current!=mask(request.other,request.type)){response.status=10;break;}
        if(!writable(request.address,width(request.type),&m)){response.status=5;break;}
        for(int i=0;i<64;i++)if(pins[i].enabled && pins[i].address==request.address){slot=i;break;}
        if(slot<0)for(int i=0;i<64;i++)if(!pins[i].enabled){slot=i;break;}
        if(slot<0){response.status=8;break;}
        pins[slot]=(Pin){request.address,request.value,m.allocation,request.type,1};break;
      }
      case 7:for(int i=0;i<64;i++)if(pins[i].address==request.address)pins[i].enabled=0;break;
      case 8:release(&found);release(&previous);count=oldCount=scanned=scanFlags=0;break;
      case 10:
        if(!previous){response.status=2;break;}
        {Row *swap=found;found=previous;previous=swap;U c=count;count=oldCount;oldCount=c;D t=scanType;scanType=oldType;oldType=t;
         D f=scanFlags;scanFlags=oldFlags;oldFlags=f;U s=scanned;scanned=oldScanned;oldScanned=s;}break;
      default:response.status=2;
    }
    respond();
}
void mainCRTStartup(void) {
    lastCommand=GetTickCount64();D lastId=0;
    for(;;) {
        H f=CreateFileA("request.bin",0x80000000,3,0,3,0,0);
        if(f!=(H)-1) {
            D n=0;int ok=ReadFile(f,&request,sizeof(request),&n,0);CloseHandle(f);
            if(ok && n==sizeof(request) && request.id!=lastId) {
                lastId=request.id;lastCommand=GetTickCount64();handle();
            }
        }
        U now=GetTickCount64();
        refreshPins();
        if(target && !active())ExitProcess(0);
        /* Attached sessions (including frozen values) live as long as their game. */
        if(!target && now-lastCommand>1800000)ExitProcess(0);
        Sleep(50);
    }
}
