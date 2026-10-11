// Integer transcription of W3 FUN0098f1a0 and the shared picture shake getter.
// W3 Game.exe SHA256 4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e.
// Original artifact is read-only. This program never invokes the Kotlin port.
#include <algorithm>
#include <climits>
#include <cstdio>

int main() {
    std::puts("# W3 native picture shake states; count>99999 alternates indefinitely");
    for (int count : {0,1,2,99999,100000,100001,999999,INT_MAX-1,INT_MAX}) {
        for (int duration : {0,1,3,5}) {
            int current=count,half=std::max(duration,1),left=half*2;
            for (int frame=0;frame<=half*6+2;++frame) {
                auto offset=[&](int amplitude) {
                    if (current<1) return 0;
                    int value=left<half?amplitude*left/half:amplitude-(left-half)*amplitude/half;
                    return (current%2==0?-1:1)*value;
                };
                std::printf("%d\t%d\t%d\t%d\t%d\t%d\t%d\n",count,duration,frame,current,left,offset(11),offset(-7));
                if (current>0 && --left<1) {
                    if (current>99999) current += (~current&1)*2;
                    --current; left=half*2;
                }
            }
        }
    }
}
