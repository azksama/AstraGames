// Independently transcribed x86 W2 447ad0 / W3 9ead50, with explicit 32-bit overflow.
// Synthetic vectors only; this does not execute or call a Windows game binary.
#include <algorithm>
#include <cstdint>
#include <cstdio>

int main() {
    const int16_t settings[15] = {0, 1,2, 3,4, 5,6, 7,8, 9,10, 11,12, 13,14};
    std::puts("name\tspeed\talt\tdelta\tmodern\tx\ty\ttargetX\ttargetY\tdiagX\tdiagY\tfrequency\tidle\tincrement\toutX\toutY\tarrived\tmoved\tready");
    int caseId = 0;
    const int frequencies[] = {0,1,2,3,6,-1,-6,32767,-32768};
    for (int speed = 0; speed <= 6; ++speed)
    for (int alt = 0; alt < 2; ++alt)
    for (int delta = 1; delta <= 2; ++delta)
    for (int modern = 0; modern < 2; ++modern)
    for (int direction = 0; direction < 8; ++direction) {
        const int dx[] = {0,-1,1,0,-1,1,-1,1};
        const int dy[] = {1,0,0,-1,1,1,-1,-1};
        int x = -11, y = 17;
        const int tx = x + dx[direction] * 9;
        const int ty = y + dy[direction] * 11;
        const int initialX = x, initialY = y;
        const int inc = int(settings[2*speed + (alt ? 1 : 2)]) * delta;
        const int diagonalX = caseId % 3 == 0 ? 0 : caseId % 3 == 1 ? 50 : 250;
        const int diagonalY = caseId % 3 == 0 ? 25 : caseId % 3 == 1 ? 100 : 10000;
        // Preserve native sequential order and signed integer truncation.
        if (y < ty) y = std::min(ty, y + (!modern || x == tx ? inc : std::max(1, inc*diagonalY/100)));
        if (x > tx) x = std::max(tx, x - (!modern || y == ty ? inc : std::max(1, inc*diagonalX/100)));
        if (x < tx) x = std::min(tx, x + (!modern || y == ty ? inc : std::max(1, inc*diagonalX/100)));
        if (y > ty) y = std::max(ty, y - (!modern || x == tx ? inc : std::max(1, inc*diagonalY/100)));
        const int frequency = frequencies[caseId % 9];
        const int idle = caseId * 3;
        const int threshold = static_cast<int32_t>(uint32_t(frequency) * uint32_t(frequency) * 20u);
        std::printf("native-%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\n",
            caseId++, speed,alt,delta,modern,initialX,initialY,tx,ty,diagonalX,diagonalY,frequency,idle,inc,x,y,
            int(x==tx && y==ty),int(x!=initialX || y!=initialY),int(idle>threshold));
    }
}
