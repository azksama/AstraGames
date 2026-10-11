// WOLFX reference generator. Copyright (c) 2025 Sinflower; portions adapted
// from UberWolf f4191375d289171b1f29dbc5ca320133b2b4b96a WolfX/detail/*.hpp.
// MIT License: Permission is hereby granted, free of charge, to any person
// obtaining a copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including without
// limitation the rights to use, copy, modify, merge, publish, distribute,
// sublicense, and/or sell copies of the Software, and to permit persons to whom
// the Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
// Standalone: cl /std:c++20 /EHsc /O2 native-reference.cpp
// Optional pinned-upstream verification: /D WOLFX_UPSTREAM /I <WolfX/detail>.
#include <array>
#include <bit>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>
#ifdef WOLFX_UPSTREAM
#include "CrackDetail.hpp"
#endif
using Bytes = std::vector<std::uint8_t>;
using Static = std::array<std::uint8_t,64>;
using Blob = std::array<std::uint8_t,256>;
Static staticBlob(const Bytes& key) {
    Static a; a.fill(0xaa); std::uint8_t d=0xbe;
    for(std::size_t i=0;i<key.size();++i) {
        auto j=i%64; a[j]^=key[i]; a[j]+=d; a[j]=std::rotl<std::uint8_t>(a[j],3);
        d=key[i]^(0xb3*d);
    }
    for(int r=0;r<5;++r) for(int j=0;j<64;++j) {
        std::uint8_t prev=a[(j+13)%64], mix=a[j]^a[(j+7)%64];
        a[j]=std::rotr<std::uint8_t>(prev+mix,7);
    }
    for(auto& b:a) if(!b) b=1; return a;
}
template<class T> std::uint32_t fnv(const T& a) {
    std::uint32_t h=0x811c9dc5; for(auto b:a) h=0x01000193*(h^b); return h;
}
void putBE(Bytes& a,std::size_t pos,std::uint32_t v,unsigned n) {
    for(unsigned i=0;i<n;++i) a[pos+i]=v>>((n-1-i)*8);
}
std::string hex(const Bytes& a) {
    if(a.empty())return "-";
    std::ostringstream o; o<<std::hex<<std::setfill('0');
    for(auto b:a)o<<std::setw(2)<<(unsigned)b; return o.str();
}
Bytes make(const Bytes& key,std::uint16_t si,std::uint32_t ni,const Bytes& text,std::uint32_t number,const Bytes& payload,std::uint32_t head) {
    const auto stat=staticBlob(key); const auto offset=512+stat[0]+stat[1];
    Bytes plain(offset+payload.size(),0x69); plain[0]='W';plain[1]='O';plain[2]='L';plain[3]='F';plain[4]='X';
    putBE(plain,5,head,4);plain[9]=0xa5;
    putBE(plain,10,si,2);
    const auto sh=fnv(text);const auto word=((sh&0xffff0000)>>8)^(sh&0xffff)^ni;
    putBE(plain,12,word,3);
    for(unsigned i=0;i<5;++i)plain[15+i]=payload[static_cast<std::size_t>((payload.size()-1)*0.25*i)];
    std::copy(payload.begin(),payload.end(),plain.begin()+offset);
    Blob initial; auto seed=fnv(stat)^head;for(auto b:stat)seed^=b;
    for(auto& b:initial){seed=seed*1664525+1013904223;b=(std::uint8_t)seed;}
    Blob blob=initial;const auto ih=ni<1000000?((number<<13)^(73244475*number)):0;
    for(unsigned i=0;i<256;++i){
        const auto sb=text.empty()?0:text[i%text.size()];
        blob[i]^=plain[10+i%2]^sb^((ih>>((3-i%4)*8))&255)^((ni>>((2-i%3)*8))&255);
    }
    Bytes enc=plain;
    for(unsigned i=0;i<5;++i)enc[10+i]^=initial[i];
    for(std::size_t i=15;i<enc.size();++i)enc[i]^=blob[(i-10)%256];
#ifdef WOLFX_UPSTREAM
    Bytes dec(enc.size());unsigned actualOffset=0;
    const std::string s(text.begin(),text.end());
    if(!wolfx::detail::crack::decryptFull(enc,key,s,number,dec,actualOffset)||actualOffset!=offset||
       Bytes(dec.begin()+actualOffset,dec.end())!=payload)throw std::runtime_error("Pinned upstream mismatch");
#endif
    return enc;
}
int main(){
    const std::array<unsigned,10> sizes={1,2,3,5,4,255,256,257,1024,1537};
    const std::array<std::uint16_t,4> strings={65535,10000,0,9999};
    const std::array<std::uint32_t,4> numbers={16777215,1000000,0,999999};
    const std::array<std::uint32_t,6> values={0,1,0xffffffff,0x80000000,0x7fffffff,0xf8a432eb};
    std::cout<<"# id\tkeyHex\tstringIndex\tnumberIndex\tstringHex\tnumberBits\tpayloadHex\tencryptedHex\n";
    for(unsigned i=0;i<192;++i){
        Bytes key;if(i%6!=0)for(unsigned j=0;j<(i%6==5?137:i%6*9);++j)key.push_back((j*73+i*17)&255);
        const auto si=strings[(i/6)%4]; const auto ni=numbers[(i/24)%4];
        Bytes text;if(si<10000)for(unsigned j=0;j<((i%13)+1);++j)text.push_back((i*19+j*101)&255);
        const auto number=ni<1000000?values[i%6]:0;
        Bytes payload(sizes[i%10]);for(unsigned j=0;j<payload.size();++j)payload[j]=(j*23+i*11)&255;
        const auto enc=make(key,si,ni,text,number,payload,0x12345678^(i*0x9e3779b9));
        std::cout<<i<<'\t'<<hex(key)<<'\t'<<si<<'\t'<<ni<<'\t'<<hex(text)<<'\t'<<number<<'\t'<<hex(payload)<<'\t'<<hex(enc)<<'\n';
    }
}
