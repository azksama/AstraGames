/* Authored integration fixture; these values belong to no commercial game. */
typedef unsigned long D;typedef unsigned long long U;typedef void *H;
#ifdef __i386__
#define API __declspec(dllimport) __attribute__((stdcall))
#else
#define API __declspec(dllimport)
#endif
API H CreateFileA(const char*,D,D,void*,D,D,H);
API int WriteFile(H,const void*,D,D*,void*);
API int ReadFile(H,void*,D,D*,void*);
API int CloseHandle(H);
API D GetFileAttributesA(const char*);
API void Sleep(D);
API void ExitProcess(D);
volatile long gold=123456789;
volatile short health=-1731;
volatile signed char level=73;
volatile long long experience=1234567891234567LL;
volatile float speed=123.5f;
volatile double energy=456.25;
void mainCRTStartup(void) {
    H file=CreateFileA("fixture.bin",0x40000000,3,0,2,0x80,0);D written;
    U addresses[]={(U)&gold,(U)&health,(U)&level,(U)&experience,(U)&speed,(U)&energy};
    WriteFile(file,addresses,sizeof(addresses),&written,0);CloseHandle(file);
    for(int i=0;i<2400;i++) {
        if(GetFileAttributesA("finish")!=(D)-1)break;
        if(GetFileAttributesA("change")!=(D)-1)gold=77777777;
        Sleep(50);
    }
    file=CreateFileA("final.bin",0x40000000,3,0,2,0x80,0);
    WriteFile(file,(const void*)&gold,4,&written,0);CloseHandle(file);ExitProcess(0);
}
