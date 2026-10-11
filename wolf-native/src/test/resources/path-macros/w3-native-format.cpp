// Independent reconstruction of W3 serializer 0042c6a0 and wrap helper0042ca30.
// This fixture supplies an already found path; it does not assert search behavior.
// REA ev_8974c373a473cb8bff83317ac728feee82f9063028f893bee4570191ff0b04f5,
// ev_e5ebb7da54e038e9111583a950218301fc8f52eda9da07b0a754d287a87e9502,
// ev_0a0da664fd95dccd1ec8cd102c09d28e93e292016269f031bada09d4b8b6bb14.
#include <algorithm>
#include <cstdio>
#include <climits>
#include <string>
#include <utility>
#include <vector>

using Point=std::pair<int,int>;
static int delta(int from,int to,int dimension,bool loop) {
    int d=to-from;
    if(loop){if(d>dimension/2)return d-dimension;if(d<-(dimension/2))return d+dimension;}
    return d;
}
static std::string nativeFormat(const std::vector<Point>& path,int mode,int limit,int width,int height,bool loopX,bool loopY) {
    static const unsigned char table[]={7,4,1,8,5,2,9,6,3};
    std::string out;
    for(size_t step=0;step+1<path.size();step++) {
        // Native checks the limit inside the loop; no END marker is appended on an early return.
        if(limit!=0&&limit<=static_cast<int>(step))return out;
        if(mode==0) {
            int x=std::clamp(delta(path[step].first,path[step+1].first,width,loopX),-1,1);
            int y=std::clamp(delta(path[step].second,path[step+1].second,height,loopY),-1,1);
            out+=std::to_string(table[(x+1)*3+y+1]);
        } else {
            out+=std::to_string(path[step+1].first)+","+std::to_string(path[step+1].second)+"/";
        }
    }
    return out+(mode==0?"/END":"END");
}
static std::string points(const std::vector<Point>& path){std::string out;for(auto p:path){if(!out.empty())out+=';';out+=std::to_string(p.first)+","+std::to_string(p.second);}return out;}
static std::string hex(const std::string& value){const char*digits="0123456789abcdef";std::string out;for(unsigned char c:value){out+=digits[c>>4];out+=digits[c&15];}return out;}
static void fixture(const char* name,const std::vector<Point>& path,int width,int height,bool loopX,bool loopY){
    int n=static_cast<int>(path.size())-1;
    const int limits[]={0,1,2,n-1,n,n+1,-1,INT_MIN};
    for(int mode=0;mode<=1;mode++)for(int limit:limits){
        std::string out=nativeFormat(path,mode,limit,width,height,loopX,loopY);
        std::printf("%s\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%d\t%s\n",name,mode,limit,width,height,loopX,loopY,points(path).c_str(),n,hex(out).c_str());
    }
}
int main(){
    std::puts("# W3 native serializer: name POS LEN width height wrapX wrapY origin+points fullSteps outputHex");
    fixture("all-eight-directions",{{4,4},{3,3},{3,4},{4,5},{3,5},{4,5},{5,4},{5,3},{4,4}},10,10,false,false);
    fixture("start-is-goal",{{2,2}},10,10,false,false);
    fixture("one-step",{{2,2},{3,2}},10,10,false,false);
    fixture("wrapped-axes",{{0,0},{9,9},{0,9},{0,0}},10,10,true,true);
    fixture("wrap-half-tie",{{0,0},{5,5},{0,0}},10,10,true,true);
    fixture("clamped-long-delta",{{0,0},{7,-3},{7,-3}},20,20,false,false);
}
