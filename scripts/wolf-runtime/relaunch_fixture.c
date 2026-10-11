/* Authored launcher handoff fixture: parent exits 0, Windows child stays alive. */
typedef unsigned long D; typedef unsigned long long U; typedef unsigned short W; typedef void *H;
#define API __declspec(dllimport)
typedef struct { D size; W *reserved,*desktop,*title; D x,y,w,h,cx,cy,fill,flags; unsigned short show,reservedSize; void *reservedBytes; H in,out,err; } SI;
typedef struct { H process,thread; D pid,tid; } PI;
API W *GetCommandLineW(void);
API D GetModuleFileNameW(H,W*,D);
API int CreateProcessW(const W*,W*,void*,void*,int,D,void*,const W*,SI*,PI*);
API H CreateFileA(const char*,D,D,void*,D,D,H);
API D GetFileAttributesA(const char*);
API int WriteFile(H,const void*,D,D*,void*);
API int CloseHandle(H);
API void Sleep(D);
API void ExitProcess(D);
static W path[2048],command[2100];
void mainCRTStartup(void) {
    W *line=GetCommandLineW(); D n=0;while(line[n])n++;
    if(n>=5 && line[n-5]=='c' && line[n-4]=='h' && line[n-3]=='i' && line[n-2]=='l' && line[n-1]=='d') {
        H f=CreateFileA("child.ready",0x40000000,3,0,2,0x80,0);D written;
        if(f!=(H)-1){WriteFile(f,"ready",5,&written,0);CloseHandle(f);}
        for(int i=0;i<2400 && GetFileAttributesA("finish")== (D)-1;i++)Sleep(50);
        ExitProcess(0);
    }
    D length=GetModuleFileNameW(0,path,2048);if(!length||length>=2048)ExitProcess(10);
    command[0]='"';for(D i=0;i<length;i++)command[i+1]=path[i];
    const W suffix[]={'"',' ','c','h','i','l','d',0};for(D i=0;i<8;i++)command[length+1+i]=suffix[i];
    SI startup={0};PI child={0};startup.size=sizeof(startup);
    if(!CreateProcessW(path,command,0,0,0,0,0,0,&startup,&child))ExitProcess(11);
    CloseHandle(child.thread);CloseHandle(child.process);ExitProcess(0);
}
