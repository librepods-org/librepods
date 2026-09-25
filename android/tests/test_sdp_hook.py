from pathlib import Path
import subprocess
import os
import tempfile
temporary = tempfile.TemporaryDirectory()
root = Path(temporary.name)
cpp = Path(__file__).resolve().parents[1] / 'app/src/main/cpp'
src = (cpp / 'l2c_fcr_hook.cpp').read_text()
fn=src.split('tBTA_STATUS fake_BTA_DmSetLocalDiRecord',1)[1].split('static bool decompressXZ',1)[0]
harness=r'''
#include <atomic>
#include <cstdio>
#include <initializer_list>
#include "l2c_fcr_hook.h"
void log_sink(const char*, ...) {}
#define LOGI(...) log_sink(__VA_ARGS__)
#define LOGE(...) log_sink(__VA_ARGS__)
static std::atomic<bool> enableSdpHook(false);
static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD*,uint32_t*)=nullptr;
'''+ 'tBTA_STATUS fake_BTA_DmSetLocalDiRecord'+fn+r'''
int calls=0,failures=0; uint16_t seenVendor=0,seenSource=0;
tBTA_STATUS fake_original(tSDP_DI_RECORD* p,uint32_t* h) {
    ++calls; if(p){seenVendor=p->vendor;seenSource=p->vendor_id_source;}
    if(h)*h=1234; return BTA_BUSY;
}
void check(bool ok,const char* msg){if(!ok){std::printf("FAIL: %s\n",msg);++failures;}}
int main(){
 for(bool enabled:{false,true}){
    enableSdpHook=enabled;original_BTA_DmSetLocalDiRecord=fake_original;
    calls=0;tSDP_DI_RECORD p{};p.vendor=0x75;p.vendor_id_source=2;p.product=7;uint32_t h=0;
    check(fake_BTA_DmSetLocalDiRecord(&p,&h)==BTA_BUSY,"return original status");
    check(calls==1,"one original call");check(h==1234,"output handle preserved");
    check(seenVendor==(enabled?0x4c:0x75),"vendor toggle respected");
    check(seenSource==(enabled?1:2),"vendor source toggle respected");
    check(p.vendor==0x75&&p.vendor_id_source==2&&p.product==7,"caller record restored");
    calls=0;check(fake_BTA_DmSetLocalDiRecord(nullptr,nullptr)==BTA_BUSY,"null inputs forwarded");
    check(calls==1,"one original call for null inputs");
 }
 original_BTA_DmSetLocalDiRecord=nullptr; tSDP_DI_RECORD p{};p.vendor=0x75;
 check(fake_BTA_DmSetLocalDiRecord(&p,nullptr)==BTA_FAILURE,"missing original fails");
 check(p.vendor==0x75,"missing original does not mutate record");
 std::printf("%d failures\n",failures);return failures?1:0;
}
'''
(root/'sdp-regression.cpp').write_text(harness)
subprocess.run([os.environ.get('CXX', 'c++'),'-std=c++17','-I',str(cpp),str(root/'sdp-regression.cpp'),'-o',str(root/'sdp-regression.exe')],check=True)
result=subprocess.run([str(root/'sdp-regression.exe')],capture_output=True,text=True)
print(result.stdout);raise SystemExit(result.returncode)

