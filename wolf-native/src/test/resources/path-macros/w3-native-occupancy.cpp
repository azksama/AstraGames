// Independent transcription of W3 0095a9b0 / 0095acb0 and a337da goal clearing.
// MIT-style fixture source; no production Kotlin or original EXE is called.
// cl /std:c++17 /EHsc /O2 w3-native-occupancy.cpp
#include <algorithm>
#include <climits>
#include <cstdint>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

struct Actor { int id, x, y, through, foot, word20, graphic44; };
using Cells = std::map<std::pair<int,int>,int>;

static Cells occupancy(const std::vector<Actor>& actors, int movingId, int gx, int gy) {
    Cells cells;
    const bool heroMode = movingId == -2;
    for (const Actor& actor : actors) {
        if (actor.through || (heroMode && actor.id >= 0 && actor.word20 == 0 && actor.graphic44 == -1)) continue;
        const int x0 = actor.x / 2;
        const int nx = actor.x % 2 == 1 ? 2 : 1;
        int y = actor.y, ny = 1;
        if (y % 2 == 1) { if (actor.foot) ny = 2; else ++y; }
        const int y0 = y / 2;
        for (int x = x0; x < x0 + nx; ++x) for (int cy = y0; cy < y0 + ny; ++cy) {
            int& marker = cells[{x,cy}];
            marker = marker == 0 ? actor.id + 10 : INT_MAX;
        }
    }
    const auto moving = std::find_if(actors.begin(), actors.end(), [=](const Actor& a) { return a.id == movingId; });
    if (moving != actors.end()) {
        if (moving->through) cells.clear();
        else for (int x = moving->x / 2; x < moving->x / 2 + 2; ++x)
            for (int y = moving->y / 2; y < moving->y / 2 + 2; ++y)
                if (cells[{x,y}] == movingId + 10) cells[{x,y}] = 0;
    }
    // The caller permits approaching an occupied destination by clearing that
    // destination's marker from the next 2x2 cells. If that marker is INT_MAX,
    // matching overlap sentinels are cleared too; different markers remain.
    const int goalMarker = cells[{gx,gy}];
    if (goalMarker != 0) for (int x = gx; x < gx + 2; ++x) for (int y = gy; y < gy + 2; ++y)
        if (cells[{x,y}] == goalMarker) cells[{x,y}] = 0;
    return cells;
}

static void emit(const std::string& name, const std::vector<Actor>& actors, int moving, int gx, int gy) {
    constexpr int width = 5, height = 4;
    std::cout << name << '\t' << width << '\t' << height << '\t' << moving << '\t' << gx << '\t' << gy << '\t';
    for (size_t i=0; i<actors.size(); ++i) {
        const auto& a=actors[i];
        if(i) std::cout << ';';
        std::cout << a.id << ',' << a.x << ',' << a.y << ',' << a.through << ',' << a.foot << ',' << a.word20 << ',' << a.graphic44;
    }
    const Cells result = occupancy(actors, moving, gx, gy);
    std::cout << '\t';
    for (int x=0; x<width; ++x) for (int y=0; y<height; ++y) {
        if(x||y) std::cout << ',';
        const auto it=result.find({x,y});
        std::cout << (it==result.end()?0:it->second);
    }
    std::cout << '\n';
}

int main() {
    std::cout << "name\twidth\theight\tmovingIndex\tgoalX\tgoalY\tactors_id_x_y_through_foot_word20_graphic44\texpectedMarkersXmajor\n";
    for (int x=2;x<=3;++x) for(int y=2;y<=3;++y) for(int foot=0;foot<=1;++foot)
        emit("parity-"+std::to_string(x)+"-"+std::to_string(y)+"-"+std::to_string(foot),
            {{-2,0,0,0,0,0,-1},{0,x,y,0,foot,1,-1}},-2,4,3);
    emit("hero-omits-blank-event",{{-2,0,0,0,0,0,-1},{0,2,2,0,0,0,-1}},-2,4,3);
    emit("event-keeps-blank-event",{{-2,0,0,0,0,0,-1},{0,2,2,0,0,0,-1},{1,6,4,0,0,1,-1}},1,4,3);
    emit("through-moving-clears-all",{{-2,0,0,1,0,0,-1},{0,2,2,0,0,1,-1}},-2,4,3);
    emit("own-clear-retains-overlap",{{-2,3,3,0,1,0,-1},{0,2,2,0,0,1,-1}},-2,4,3);
    emit("goal-clears-marker-2x2",{{-2,0,0,0,0,0,-1},{0,3,3,0,1,1,-1},{1,6,4,0,0,1,-1}},-2,1,1);
    emit("goal-overlap-clears-overlap-only",{{-2,0,0,0,0,0,-1},{0,3,3,0,1,1,-1},{1,2,2,0,0,1,-1}},-2,1,1);
    uint32_t seed=0x25acb0;
    auto next=[&](){seed=seed*1664525u+1013904223u;return seed;};
    for(int n=0;n<192;++n) {
        std::vector<Actor> actors;
        for(int id=-2;id<4;++id) {
            if(id==-1) continue;
            Actor a{id,int(next()%8),int(next()%6),int(next()%7==0),int(next()%2),int(next()%3),int(next()%3)-1};
            actors.push_back(a);
        }
        const int moving=n%3==0?-2:(n%3==1?1:99);
        const int gx=next()%5, gy=next()%4;
        emit("random-"+std::to_string(n),actors,moving,gx,gy);
    }
}
