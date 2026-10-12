/* Astra original, out-of-process Wolf data adapter. Layout facts and exact
 * official executable identities: docs/research/WOLF-DATA-ADAPTER.md.
 * No DLL injection, engine calls, remote allocation, or proprietary code.
 * Unknown binaries are deliberately refused; generic scanner is independent. */
#define DATA_MAGIC 0x41534445
#define DATA_VERSION 1
#define DATA_MAX 4096
#define DATA_OUTPUT (104+128*(40+DATA_MAX))
typedef struct { D magic,version; U token; D category,bank,queryBytes,beforeBytes,afterBytes,unused; } DataRequest;
typedef struct { D magic,id,status,version; U token; D layout,codepage,rows,payloadBytes; U count;
    D bankCount,textCount,commonCount,flags,banks[10]; } DataResponse;
typedef struct { D id,category,bank,index,type,capacity; U token; D bytes,flags; } DataRow;
typedef struct { D rva,numbers,text,commonNumbers,commonText,vectorProxy,vectorStride,stringStride,stringData,stringLength,stringCapacity,codepage; } Layout;
static const Layout layouts[2]={
    {0x6a8ee8,0x18,0x28,0x38,0x48,4,16,28,4,20,24,932},
    {0x110d370,0x14,0x20,0x2c,0x38,0,12,24,0,16,20,65001}
};
static const unsigned char imageHashes[2][32]={
    {0x79,0x52,0x03,0xa6,0xe8,0x75,0x61,0x8d,0x7f,0xbd,0xc7,0xdd,0x1f,0x40,0x9a,0x44,0x5c,0x60,0x26,0x2c,0x91,0x17,0x45,0x55,0x3d,0x04,0x00,0x81,0xbe,0xdb,0x6a,0xc0},
    {0x4e,0xe2,0xa9,0x05,0xa5,0xa7,0x4a,0x51,0x3c,0x88,0x5b,0x70,0x96,0x15,0x0f,0xfd,0xa1,0xa9,0x80,0x01,0x80,0xdb,0x5b,0x5b,0x03,0x37,0xad,0x8f,0xd8,0x6f,0xbf,0x8e}
};
static DataRequest dataRequest;
static DataResponse dataResponse;
static unsigned char dataInput[3*DATA_MAX],dataOutput[DATA_OUTPUT];
static char dataValue[DATA_MAX+1],dataBefore[DATA_MAX+1],dataAfter[DATA_MAX+1];
static W dataWide[DATA_MAX+1];
static D dataRequestValid,dataOutputSize,identityChecked,layoutId;
static U dataRoot;
static const Layout *dataLayout;
static int equalBytes(const void *a,const void *b,D n){const unsigned char *x=a,*y=b;for(D i=0;i<n;i++)if(x[i]!=y[i])return 0;return 1;}
static void copyBytes(void *a,const void *b,D n){unsigned char *x=a;const unsigned char *y=b;for(D i=0;i<n;i++)x[i]=y[i];}
/* SHA-256, from the public FIPS 180-4 algorithm, implemented here. */
static D rotate(D n,D k){return(n>>k)|(n<<(32-k));}
static const D shaK[64]={0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static void shaBlock(D *s,const unsigned char *b){
    D w[64];for(D i=0;i<16;i++)w[i]=((D)b[4*i]<<24)|((D)b[4*i+1]<<16)|((D)b[4*i+2]<<8)|b[4*i+3];
    for(D i=16;i<64;i++){D a=w[i-15],z=w[i-2];w[i]=w[i-16]+(rotate(a,7)^rotate(a,18)^(a>>3))+w[i-7]+(rotate(z,17)^rotate(z,19)^(z>>10));}
    D a=s[0],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7],v=s[1];
    for(D i=0;i<64;i++){D t=h+(rotate(e,6)^rotate(e,11)^rotate(e,25))+((e&f)^(~e&g))+shaK[i]+w[i];D u=(rotate(a,2)^rotate(a,13)^rotate(a,22))+((a&v)^(a&c)^(v&c));h=g;g=f;f=e;e=d+t;d=c;c=v;v=a;a=t+u;}
    s[0]+=a;s[1]+=v;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
}
static int hashImage(unsigned char *hash){
    H f=CreateFileW(expected,0x80000000,1,0,3,0,0);if(f==(H)-1)return 0;
    D s[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19},n=0,left=0;U total=0;
    unsigned char tail[128];int ok=1;
    for(;;){if(!ReadFile(f,chunk,sizeof(chunk),&n,0)){ok=0;break;}if(!n)break;total+=n;
        if(total>32*1024*1024ULL || cancelled()){ok=0;break;}
        D i=0;if(left){while(left<64&&i<n)tail[left++]=chunk[i++];if(left==64){shaBlock(s,tail);left=0;}}
        for(;i+64<=n;i+=64)shaBlock(s,chunk+i);for(;i<n;i++)tail[left++]=chunk[i];
    }
    CloseHandle(f);if(!ok)return 0;tail[left++]=0x80;D padded=left>56?128:64;for(D i=left;i<padded;i++)tail[i]=0;
    U bits=total*8;for(D i=0;i<8;i++)tail[padded-1-i]=(unsigned char)(bits>>(8*i));shaBlock(s,tail);if(padded==128)shaBlock(s,tail+64);
    for(D i=0;i<32;i++)hash[i]=(unsigned char)(s[i/4]>>(24-8*(i%4)));return 1;
}
static int readable(U address,D length){MB m;if(!length)return 1;return VirtualQueryEx(target,(void*)address,&m,sizeof(m)) && m.state==0x1000 && !(m.protect&0x101) && (m.protect&0xfe) && address>=m.base && address+length>=address && address+length<=m.base+m.size;}
static int remoteRead(U address,void *value,D n){U got=0;return readable(address,n)&&ReadProcessMemory(target,(void*)address,value,n,&got)&&got==n;}
static int remoteWrite(U address,const void *value,D n){MB m;U got=0;return writable(address,n,&m)&&WriteProcessMemory(target,(void*)address,value,n,&got)&&got==n;}
static U mixToken(U a,U b){a^=b+0x9e3779b97f4a7c15ULL+(a<<6)+(a>>2);return a?a:1;}
static D identifyLayout(void){
    if(identityChecked)return layoutId?0:20;
    unsigned char digest[32];if(!hashImage(digest))return 5;
    identityChecked=1;for(D i=0;i<2;i++)if(equalBytes(digest,imageHashes[i],32))layoutId=i+1;
    if(!layoutId)return 20;dataLayout=&layouts[layoutId-1];
    H snap=CreateToolhelp32Snapshot(8|16,targetPid);if(snap==(H)-1){identityChecked=0;layoutId=0;return 5;}
    ME entry={0};entry.size=sizeof(entry);
    if(Module32FirstW(snap,&entry))do{if(CompareStringOrdinal(entry.path,-1,expected,-1,1)==2 && entry.imageSize>dataLayout->rva+0x60){dataRoot=(U)entry.base+dataLayout->rva;break;}}while(Module32NextW(snap,&entry));
    CloseHandle(snap);if(!dataRoot){identityChecked=0;layoutId=0;return 5;}
    return 0;
}
/* Begin/end/capacity are checked before every access, never guessed from names. */
typedef struct {D begin,end,cap,count;} Vector;
static int vectorRead(U at,D stride,D maximum,Vector *v){
    D values[3];if(!remoteRead(at+dataLayout->vectorProxy,values,12))return 0;
    v->begin=values[0];v->end=values[1];v->cap=values[2];
    if(!v->begin){v->count=0;return !v->end&&!v->cap;}
    if(v->end<v->begin||v->cap<v->end||(v->end-v->begin)%stride || (v->cap-v->begin)%stride)return 0;
    v->count=(v->end-v->begin)/stride;
    return v->count<=maximum && (v->cap-v->begin)/stride<=maximum && readable(v->begin,v->end-v->begin);
}
static Vector numberOuter,textOuter,commonNumberOuter,commonTextOuter;
static D probeData(void){
    D status=identifyLayout();if(status)return status;
    if(!vectorRead(dataRoot+dataLayout->numbers,dataLayout->vectorStride,10,&numberOuter)||
       !vectorRead(dataRoot+dataLayout->text,dataLayout->stringStride,1000000,&textOuter)||
       !vectorRead(dataRoot+dataLayout->commonNumbers,dataLayout->vectorStride,10000,&commonNumberOuter)||
       !vectorRead(dataRoot+dataLayout->commonText,dataLayout->vectorStride,10000,&commonTextOuter))return 21;
    dataResponse.layout=layoutId;dataResponse.codepage=dataLayout->codepage;dataResponse.bankCount=numberOuter.count;
    dataResponse.textCount=textOuter.count;dataResponse.commonCount=commonNumberOuter.count>commonTextOuter.count?commonNumberOuter.count:commonTextOuter.count;
    U token=mixToken(dataRoot,numberOuter.begin);token=mixToken(token,textOuter.begin);token=mixToken(token,commonNumberOuter.begin);token=mixToken(token,commonTextOuter.begin);
    D total=0;
    for(D i=0;i<numberOuter.count;i++){Vector v;if(!vectorRead(numberOuter.begin+i*dataLayout->vectorStride,4,100000,&v))return 21;dataResponse.banks[i]=v.count;total+=v.count;token=mixToken(token,v.begin);token=mixToken(token,v.cap);}
    dataResponse.token=token;
    return !total&&!textOuter.count?22:0;
}
typedef struct{DataRow row;U object,data,allocation;D length,number;unsigned char stringObject[28];} Entry;
static D resolveEntry(D id,Entry *e){
    *e=(Entry){0};e->row.id=id;Vector v;D index;
    if(id>=2000000&&id<3000000){D bank=(id-2000000)/100000;index=id%100000;if(bank>=numberOuter.count||!vectorRead(numberOuter.begin+bank*dataLayout->vectorStride,4,100000,&v)||index>=v.count)return 23;e->data=v.begin+index*4;e->row.category=1;e->row.bank=bank;e->row.index=index;}
    else if(id>=3000000&&id<4000000){index=id%1000000;if(index>=textOuter.count)return 23;e->object=textOuter.begin+index*dataLayout->stringStride;e->row.category=2;e->row.index=index;}
    else if(id>=15000000&&id<16000000){D bank=(id%1000000)/100;index=id%100;e->row.bank=bank;e->row.index=index;
        if(index>=5&&index<=9){if(bank>=commonTextOuter.count||!vectorRead(commonTextOuter.begin+bank*dataLayout->vectorStride,dataLayout->stringStride,5,&v)||index-5>=v.count)return 23;e->object=v.begin+(index-5)*dataLayout->stringStride;e->row.category=4;}
        else{if(bank>=commonNumberOuter.count||!vectorRead(commonNumberOuter.begin+bank*dataLayout->vectorStride,4,100,&v)||index>=v.count)return 23;e->data=v.begin+index*4;e->row.category=3;}
    }else return 23;
    if(e->object){
        if(!remoteRead(e->object,e->stringObject,dataLayout->stringStride))return 21;
        D cap=*(D*)(e->stringObject+dataLayout->stringCapacity);e->length=*(D*)(e->stringObject+dataLayout->stringLength);
        if(cap<15||cap>16*1024*1024||e->length>cap)return 21;
        e->data=cap==15?e->object+dataLayout->stringData:*(D*)(e->stringObject+dataLayout->stringData);
        if(!e->data||!readable(e->data,e->length+1))return 21;
        e->row.type=1;e->row.capacity=cap;e->row.flags=e->length>DATA_MAX?1:0;
    }else{if(!remoteRead(e->data,&e->number,4))return 21;e->row.capacity=4;}
    MB m;if(!VirtualQueryEx(target,(void*)e->data,&m,sizeof(m)))return 21;e->allocation=m.allocation;
    e->row.token=mixToken(mixToken(mixToken(dataResponse.token,e->data),e->object),mixToken(e->allocation,e->row.capacity));
    return 0;
}
static D resolveFresh(D id,Entry *e){D s=probeData();return s?s:resolveEntry(id,e);}
static D decimal(D bits,char *value){I n=(long)bits;D p=0;char rev[12];U a=n<0?(U)-n:(U)n;do{rev[p++]=(char)('0'+a%10);a/=10;}while(a);D j=0;if(n<0)value[j++]='-';while(p)value[j++]=rev[--p];value[j]=0;return j;}
static int parseNumber(const unsigned char *s,D n,D *bits){if(!n||n>11)return 0;D i=0,negative=s[0]=='-';if(negative)i++;if(i==n)return 0;U value=0;for(;i<n;i++){if(s[i]<'0'||s[i]>'9')return 0;value=value*10+s[i]-'0';if(value>(negative?2147483648ULL:2147483647ULL))return 0;}*bits=negative?(D)(0-value):(D)value;return 1;}
static int encodeText(const unsigned char *src,D n,char *out,D *length){
    for(D i=0;i<n;i++)if(!src[i])return 0;
    if(!n){*length=0;out[0]=0;return 1;}
    int chars=MultiByteToWideChar(65001,8,(const char*)src,(int)n,dataWide,DATA_MAX);if(chars<=0)return 0;
    int used=0;int bytes=WideCharToMultiByte(dataLayout->codepage,dataLayout->codepage==65001?0:0x400,dataWide,chars,out,DATA_MAX,0,dataLayout->codepage==65001?0:&used);
    if(bytes<=0||used)return 0;out[bytes]=0;*length=(D)bytes;return 1;
}
static D valueEntry(Entry *e){
    if(!e->row.type){e->row.bytes=decimal(e->number,dataValue);return 0;}
    if(e->row.flags){const char *s="[Texte de plus de 4096 octets]";D n=0;while(s[n])n++;copyBytes(dataValue,s,n+1);e->row.bytes=n;return 0;}
    if(!remoteRead(e->data,dataBefore,e->length+1)||dataBefore[e->length])return 21;
    for(D i=0;i<e->length;i++)if(!dataBefore[i]){e->row.flags=1;return 24;}
    if(!e->length){dataValue[0]=0;e->row.bytes=0;return 0;}
    int chars=MultiByteToWideChar(dataLayout->codepage,dataLayout->codepage==65001?8:0,dataBefore,e->length,dataWide,DATA_MAX);
    if(chars<=0)return 24;int n=WideCharToMultiByte(65001,0,dataWide,chars,dataValue,DATA_MAX,0,0);if(n<=0)return 24;
    e->row.bytes=(D)n;dataValue[n]=0;return 0;
}
static int contains(const char *s,D n,const unsigned char *query,D q){if(!q)return 1;for(D i=0;i+q<=n;i++){D j=0;for(;j<q;j++){unsigned char a=(unsigned char)s[i+j],b=query[j];if(a>='A'&&a<='Z')a+=32;if(b>='A'&&b<='Z')b+=32;if(a!=b)break;}if(j==q)return 1;}return 0;}
static D appendEntry(Entry *e){if(dataOutputSize+sizeof(DataRow)+e->row.bytes>sizeof(dataOutput))return 2;copyBytes(dataOutput+dataOutputSize,&e->row,sizeof(e->row));dataOutputSize+=sizeof(e->row);copyBytes(dataOutput+dataOutputSize,dataValue,e->row.bytes);dataOutputSize+=e->row.bytes;dataResponse.rows++;return 0;}
static D visit(D id){
    Entry e;D status=resolveEntry(id,&e);if(status==23)return 0;if(status)return status;
    if(dataRequest.category&&dataRequest.category!=e.row.category)return 0;if(dataRequest.bank!=(D)-1&&dataRequest.bank!=e.row.bank)return 0;
    status=valueEntry(&e);if(status)return status;
    char label[12];D n=decimal(e.row.id,label);
    if(!contains(dataValue,e.row.bytes,dataInput,dataRequest.queryBytes)&&!contains(label,n,dataInput,dataRequest.queryBytes))return 0;
    U ordinal=dataResponse.count++;if(ordinal>=request.offset&&dataResponse.rows<128)return appendEntry(&e);return 0;
}
static D listData(void){
    D s;for(D bank=0;bank<numberOuter.count;bank++){if(dataRequest.category&&dataRequest.category!=1)break;if(dataRequest.bank!=(D)-1&&dataRequest.bank!=bank)continue;
        for(D i=0;i<dataResponse.banks[bank];i++){if(!(i%256)&&cancelled())return 6;if((s=visit(2000000+bank*100000+i)))return s;}}
    if(!dataRequest.category||dataRequest.category==2)for(D i=0;i<textOuter.count;i++){if(!(i%256)&&cancelled())return 6;if((s=visit(3000000+i)))return s;}
    D commons=commonNumberOuter.count>commonTextOuter.count?commonNumberOuter.count:commonTextOuter.count;
    if(!dataRequest.category||dataRequest.category>=3)for(D bank=0;bank<commons;bank++){if(dataRequest.bank!=(D)-1&&dataRequest.bank!=bank)continue;if(!(bank%16)&&cancelled())return 6;
        Vector n={0},t={0};if(bank<commonNumberOuter.count&&!vectorRead(commonNumberOuter.begin+bank*dataLayout->vectorStride,4,100,&n))return 21;
        if(bank<commonTextOuter.count&&!vectorRead(commonTextOuter.begin+bank*dataLayout->vectorStride,dataLayout->stringStride,5,&t))return 21;
        for(D i=0;i<100;i++){if(i>=5&&i<=9){if(dataRequest.category==3||i-5>=t.count)continue;}else if(dataRequest.category==4||i>=n.count)continue;if((s=visit(15000000+bank*100+i)))return s;}}
    return 0;
}
static D mutateData(void){
    Entry e,verify;D s=resolveEntry((D)request.address,&e);if(s)return s;if(e.row.token!=dataRequest.token)return 25;
    for(D i=0;i<64;i++)if(pins[i].enabled){U end=pins[i].address+width(pins[i].type);
        if((end>e.data&&pins[i].address<e.data+(e.row.type?e.row.capacity+1:4)) ||
           (e.object&&end>e.object&&pins[i].address<e.object+dataLayout->stringStride))return 26;
    }
    const unsigned char *before=dataInput+dataRequest.queryBytes,*after=before+dataRequest.beforeBytes;
    if(!e.row.type){D old,newValue,current;if(!parseNumber(before,dataRequest.beforeBytes,&old)||!parseNumber(after,dataRequest.afterBytes,&newValue))return 2;
        if(e.number!=old)return 10;
        if((s=resolveFresh(e.row.id,&verify))||verify.row.token!=e.row.token)return 25;
        if(verify.number!=old)return 10;if(!remoteWrite(e.data,&newValue,4))return 5;
        if((s=resolveFresh(e.row.id,&verify))||verify.row.token!=e.row.token||verify.number!=newValue)return 11;
        if(!remoteRead(e.data,&current,4)||current!=newValue)return 11;
    }else{
        if(e.row.flags)return 24;D oldLength,newLength;
        if(!encodeText(before,dataRequest.beforeBytes,dataBefore,&oldLength)||!encodeText(after,dataRequest.afterBytes,dataAfter,&newLength))return 24;
        if(newLength>e.row.capacity)return 27;if(oldLength!=e.length)return 10;
        char current[DATA_MAX+1];if(!remoteRead(e.data,current,oldLength+1)||!equalBytes(current,dataBefore,oldLength+1))return 10;
        if((s=resolveFresh(e.row.id,&verify))||verify.row.token!=e.row.token||!equalBytes(verify.stringObject,e.stringObject,dataLayout->stringStride))return 25;
        /* In-place only. Capacity, pointer, allocator and ownership are untouched. */
        if(!remoteWrite(e.data,dataAfter,newLength+1))return 5;
        if(!remoteWrite(e.object+dataLayout->stringLength,&newLength,4)){
            if(resolveFresh(e.row.id,&verify)==0&&verify.row.token==e.row.token&&remoteRead(e.data,current,newLength+1)&&equalBytes(current,dataAfter,newLength+1))remoteWrite(e.data,dataBefore,oldLength+1);
            return 5;
        }
        if((s=resolveFresh(e.row.id,&verify))||verify.row.token!=e.row.token||verify.length!=newLength||!remoteRead(e.data,current,newLength+1)||!equalBytes(current,dataAfter,newLength+1))return 11;
    }
    s=resolveFresh(e.row.id,&verify);if(s)return s;if((s=valueEntry(&verify)))return s;dataResponse.count=1;return appendEntry(&verify);
}
static void dataReadRequest(H f){
    dataRequestValid=0;if(request.op<11||request.op>14)return;D n=0;
    if(!ReadFile(f,&dataRequest,sizeof(dataRequest),&n,0)||n!=sizeof(dataRequest)||dataRequest.magic!=DATA_MAGIC||dataRequest.version!=DATA_VERSION||dataRequest.unused||dataRequest.category>4||dataRequest.queryBytes>DATA_MAX||dataRequest.beforeBytes>DATA_MAX||dataRequest.afterBytes>DATA_MAX)return;
    D bytes=dataRequest.queryBytes+dataRequest.beforeBytes+dataRequest.afterBytes;
    if(bytes&&(!ReadFile(f,dataInput,bytes,&n,0)||n!=bytes))return;
    unsigned char extra;if(!ReadFile(f,&extra,1,&n,0)||n)return;dataRequestValid=1;
}
static void dataRespond(void){
    dataResponse.magic=DATA_MAGIC;dataResponse.id=request.id;dataResponse.version=DATA_VERSION;dataResponse.payloadBytes=dataOutputSize;
    if(dataResponse.status){dataOutputSize=0;dataResponse.rows=0;dataResponse.payloadBytes=0;}
    H f=CreateFileA("response.tmp",0x40000000,0,0,2,0,0);if(f==(H)-1)return;D wrote;
    WriteFile(f,&dataResponse,sizeof(dataResponse),&wrote,0);if(dataOutputSize)WriteFile(f,dataOutput,dataOutputSize,&wrote,0);CloseHandle(f);MoveFileExA("response.tmp","response.bin",1|8);
}
static void dataHandle(void){
    dataResponse=(DataResponse){0};dataOutputSize=0;
    if(!dataRequestValid||request.magic!=MAGIC||request.address>0xffffffffULL||request.offset>3000000){dataResponse.status=2;dataRespond();return;}
    if(!target)findTarget();if(!target)dataResponse.status=1;else if(!active())dataResponse.status=4;
    else if(!(dataResponse.status=probeData())){
        if(request.op==12)dataResponse.status=listData();
        else if(request.op==13){Entry e;D s=resolveEntry((D)request.address,&e);if(!s)s=valueEntry(&e);if(!s){dataResponse.count=1;s=appendEntry(&e);}dataResponse.status=s;}
        else if(request.op==14)dataResponse.status=mutateData();
    }
    dataRespond();
}
