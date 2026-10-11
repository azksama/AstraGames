// Independent CPU oracle for the public DxLib 3.25a D3D11 PMA shaders.
// The subject Game.exe is never executed. Values are generated from shader
// equations using normalized vector RGBA and texture sampling, independently
// of the Kotlin packed-pixel implementation.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <vector>
using RGBA = std::array<float, 4>;
struct Filter { int op, a, b, c, d; };
float sat(float x) { return !(x > 0.f) ? 0.f : std::min(x, 1.f); }
int q(float v) { return std::max(0, std::min(255, int(std::floor(v * 255.f + .5f)))); }
RGBA write(RGBA c) {
  for (float& v : c) v = float(q(v)) / 255.f;
  return c;
}
RGBA load(uint32_t c) {
  unsigned a = c >> 24;
  RGBA r{};
  for (int channel = 0; channel < 3; ++channel) {
    unsigned rgb = (c >> (16 - 8 * channel)) & 255;
    r[channel] = float((rgb * a + 127) / 255) / 255.f;
  }
  r[3] = float(a) / 255.f;
  return r;
}
uint32_t store(RGBA c) {
  int a = q(c[3]);
  uint32_t result = unsigned(a) << 24;
  if (a) for (int i = 0; i < 3; ++i)
    result |= unsigned((q(c[i]) * 255 + a / 2) / a) << (16 - 8 * i);
  return result;
}
RGBA shader(RGBA color, Filter f) {
  float r = 0, g = 0, b = 0;
  if (color[3] >= .001f) {
    r = color[0] / color[3]; g = color[1] / color[3]; b = color[2] / color[3];
  }
  if (f.op == 21 || f.op == 22) {
    float bright = float(f.c) * 100.f / 255.f / 100.f;
    auto brightness = [bright](float v) { return sat(bright > 0.f ? v + (1.f - v) * bright : v * (1.f + bright)); };
    r = brightness(r); g = brightness(g); b = brightness(b);
    float hi = std::max(r, std::max(g, b)), lo = std::min(r, std::min(g, b));
    float l = (hi + lo) / 2.f, delta = hi - lo, s = 0.f, h = 0.f;
    if (delta != 0.f) {
      s = delta / (l > .5f ? 2.f - hi - lo : hi + lo);
      float tr = (hi - r) / delta, tg = (hi - g) / delta, tb = (hi - b) / delta;
      h = hi == r ? tb - tg : hi == g ? 2.f + tr - tb : 4.f + tg - tr;
      if (h < 0.f) h += 6.f;
    }
    if (f.op == 22) h = std::max(0.f, std::min(1.f, float(f.a) / 360.f)) * 6.f;
    else {
      h += float((f.a % 360 + 180) % 360 - 180) / 360.f * 6.f;
      if (h < 0.f) h += 6.f; else if (h >= 6.f) h -= 6.f;
    }
    float boost = std::max(-1.f, std::min(1.f, float(f.b) * 100.f / 255.f / 100.f));
    s = sat(s * (1.f + (boost > 0.f ? boost * 5.f : boost)));
    std::array<float, 3> rgb;
    if (h < 1) rgb = {1,h,0}; else if (h < 2) rgb = {2-h,1,0};
    else if (h < 3) rgb = {0,1,h-2}; else if (h < 4) rgb = {0,4-h,1};
    else if (h < 5) rgb = {h-4,0,1}; else rgb = {1,0,6-h};
    for (float& v : rgb) {
      v += (.5f - v) * (1.f - s);
      if (l <= .5f) v *= l * 2.f; else v += (1.f - v) * ((l - .5f) * 2.f);
    }
    r = rgb[0]; g = rgb[1]; b = rgb[2];
  } else if (f.op == 24) { r = 1-r; g = 1-g; b = 1-b; }
  else if (f.op == 26) {
    auto normalized = [](int x) { return float(std::max(0,std::min(255,x))) / 255.f; };
    float oldMax = normalized(f.b), oldMin = std::min(normalized(f.a),oldMax);
    float newMax = normalized(f.d), newMin = std::min(normalized(f.c),newMax);
    auto level = [=](float value) {
      float uv = sat((value - oldMin) * (1.f / (oldMax - oldMin)));
      float textureX = std::max(0.f,std::min(255.f,uv*256.f-.5f));
      float sample = textureX / 255.f;
      return newMin + (newMax-newMin) * sample;
    };
    r = level(r); g = level(g); b = level(b);
  }
  color[0] = sat(r) * color[3]; color[1] = sat(g) * color[3]; color[2] = sat(b) * color[3];
  return write(color);
}
RGBA sample(const std::vector<RGBA>& tex, int w, int h, float x, float y) {
  x = std::max(0.f,std::min(float(w-1),x)); y = std::max(0.f,std::min(float(h-1),y));
  int x0 = int(std::floor(x)), y0 = int(std::floor(y));
  int x1 = std::min(w-1,x0+1), y1 = std::min(h-1,y0+1);
  float fx = x-x0, fy = y-y0;
  RGBA value{};
  for (int c=0;c<4;++c) {
    float a=tex[y0*w+x0][c]+(tex[y0*w+x1][c]-tex[y0*w+x0][c])*fx;
    float b=tex[y1*w+x0][c]+(tex[y1*w+x1][c]-tex[y1*w+x0][c])*fx;
    value[c] = a+(b-a)*fy;
  }
  return value;
}
std::vector<RGBA> gaussian(std::vector<RGBA> src, int w, int h, int strength) {
  int n = strength < 8 ? 4 : strength < 12 ? 8 : 16;
  uint32_t product = uint32_t(std::max(0,std::min(16,strength))) * (uint32_t(strength)+1) * 10;
  float variance = std::max(1e-10f,float(int32_t(product))), total = 0;
  std::array<float,16> weight{};
  for (int i=0;i<n;++i) { weight[i]=std::exp(-.5f*float(i*i)/variance); total+=weight[i]*(i?2.f:1.f); }
  for (int i=0;i<n;++i) weight[i]/=total;
  for (int pass=0;pass<2;++pass) {
    std::vector<RGBA> dst(src.size());
    for (int y=0;y<h;++y) for (int x=0;x<w;++x) {
      RGBA color{};
      for (int c=0;c<4;++c) color[c]=src[y*w+x][c]*weight[0];
      for (int i=1;i<n;++i) {
        float d=2.f*i-.5f;
        RGBA positive=sample(src,w,h,float(x)+(pass==0?d:0),float(y)+(pass==1?d:0));
        RGBA negative=sample(src,w,h,float(x)-(pass==0?d:0),float(y)-(pass==1?d:0));
        for (int c=0;c<4;++c) color[c]+=weight[i]*(positive[c]+negative[c]);
      }
      dst[y*w+x]=write(color);
    }
    src=std::move(dst);
  }
  return src;
}
void emit(std::ostream& out, int w, int h, std::vector<uint32_t> pixels, const std::vector<Filter>& filters) {
  out << w << '|' << h << '|';
  for (size_t i=0;i<filters.size();++i) {
    auto f=filters[i]; if(i)out<<';';out<<f.op<<','<<f.a<<','<<f.b<<','<<f.c<<','<<f.d;
  }
  out << '|';for(size_t i=0;i<pixels.size();++i){if(i)out<<',';out<<std::hex<<std::setw(8)<<std::setfill('0')<<pixels[i];}out<<std::dec;
  std::vector<RGBA> texture;for(auto p:pixels)texture.push_back(load(p));
  for(auto f:filters) { if(f.op==23)texture=gaussian(std::move(texture),w,h,f.a);else for(auto& p:texture)p=shader(p,f); }
  out << '|';for(size_t i=0;i<texture.size();++i){if(i)out<<',';out<<std::hex<<std::setw(8)<<std::setfill('0')<<store(texture[i]);}out<<std::dec<<'\n';
}
int main(int argc,char**argv) {
  if(argc!=2)return 2;std::ofstream out(argv[1]);if(!out)return 3;
  out << "# DxLib 3.25a shader equations; independent C++ reference; not execution of Wolf or Android\n";
  std::vector<uint32_t> palette={0xff000000,0xffffffff,0xffff0000,0xff00ff00,0xff0000ff,0xffaa7733,0xff336699,0xff818181,0x801122ee,0x4010ff90,0x01123456,0x00abcdef};
  int count=0;
  for(int op:{21,22}) for(int hue:{-1080,-360,-181,-180,-1,0,1,60,120,179,180,240,359,360,1080})
    for(int s:{-510,-255,-64,0,32,127,255,510}) for(int b:{-300,-128,0,64,255,300}) {
      emit(out,12,1,palette,{{op,hue,s,b,0}});++count;
    }
  emit(out,12,1,palette,{{24,0,0,0,0}});++count;
  for(auto bounds:std::vector<std::array<int,4>>{{0,255,0,255},{30,230,16,240},{128,128,0,255},{230,20,255,0},{-100,400,-100,400},{0,255,0,128},{50,100,20,100}}) {
    emit(out,12,1,palette,{{26,bounds[0],bounds[1],bounds[2],bounds[3]}});++count;
  }
  std::vector<uint32_t> image;uint32_t rng=0x94178223;
  for(int i=0;i<17*11;++i) {rng=rng*1664525+1013904223;image.push_back(rng);}
  for(int strength:{-1,0,1,2,7,8,11,12,16,17,2147483647}) {emit(out,17,11,image,{{23,strength,0,0,0}});++count;}
  for(auto chain:std::vector<std::vector<Filter>>{
    {{24,0,0,0,0},{21,120,64,0,0}},{{21,120,64,0,0},{24,0,0,0,0}},
    {{23,7,0,0,0},{26,20,180,10,230},{22,210,-80,32,0}},
    {{21,-180,255,-64,0},{23,1,0,0,0},{24,0,0,0,0},{26,0,255,0,100}}
  }) {emit(out,17,11,image,chain);++count;}
  std::cout << count << " reference conversions written\n";
  return 0;
}
