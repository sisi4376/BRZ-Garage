"""Run the production provisioning/load/save functions against an in-memory NVS."""
from pathlib import Path
import unittest
from test_obd_poll_health import function
import test_obd_poll_health as health

ROOT = Path(__file__).resolve().parents[1]

class ObdFactoryDefaultTest(unittest.TestCase):
    def test_factory_default_and_existing_config_preservation(self):
        source = (ROOT / 'main/bsp_obd_dsp/nvs_storage.c').read_text(encoding='utf-8')
        harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
typedef int esp_err_t;
typedef int nvs_handle_t;
#define ESP_OK 0
#define ESP_ERR_NVS_NOT_FOUND 1
#define NVS_READONLY 0
#define NVS_READWRITE 1
#define NS_CFG "cfg"
#define KEY_CFG "settings"
typedef struct { char ble_device_name[32]; uint8_t ble_obd_mac[6]; uint8_t guard[32]; } cfg_t;
static cfg_t s_cfg, disk;
static bool ns_exists, cfg_exists;
static size_t disk_len;
static unsigned writes;
static int open_error, read_error, write_error;
static int nvs_open(const char *ns, int mode, nvs_handle_t *h) {
    assert(!strcmp(ns,NS_CFG));
    if (open_error) return open_error;
    if (!ns_exists && mode==NVS_READONLY) return ESP_ERR_NVS_NOT_FOUND;
    ns_exists=true; *h=1; return ESP_OK;
}
static void nvs_close(int h) { assert(h==1); }
static int nvs_get_blob(int h, const char *key, void *out, size_t *len) {
    assert(h==1 && !strcmp(key,KEY_CFG));
    if (read_error) return read_error;
    if (!cfg_exists) return ESP_ERR_NVS_NOT_FOUND;
    if (!out) { *len=disk_len; return ESP_OK; }
    assert(*len>=disk_len); memcpy(out,&disk,disk_len); *len=disk_len; return ESP_OK;
}
static int nvs_set_blob(int h, const char *key, const void *data, size_t len) {
    assert(h==1 && !strcmp(key,KEY_CFG) && len==sizeof(s_cfg));
    if (write_error) return write_error;
    memcpy(&disk,data,len); disk_len=len; cfg_exists=true; writes++; return ESP_OK;
}
static int nvs_commit(int h) { assert(h==1); return ESP_OK; }
static int load_blob(const char*,const char*,void*,size_t);
static int save_blob(const char*,const char*,const void*,size_t);
'''
        harness += '\n'.join(function(source,name) for name in ('load_blob','save_blob','load_user_cfg'))
        harness += r'''
int main(void) {
    // Blank namespace: persist the new default, then reboot without rewriting it.
    assert(load_user_cfg()==ESP_OK && !strcmp(s_cfg.ble_device_name,"OBDBLE"));
    assert(!strcmp(disk.ble_device_name,"OBDBLE"));
    unsigned saved_writes=writes; memset(&s_cfg,0,sizeof(s_cfg));
    assert(load_user_cfg()==ESP_OK && !strcmp(s_cfg.ble_device_name,"OBDBLE"));
    assert(writes==saved_writes);
    const char *names[]={"", "OBDII", "OBDBLE", "My Adapter"};
    for (unsigned i=0;i<4;i++) {
        memset(&disk,0x5a,sizeof(disk)); memset(disk.ble_device_name,0,32);
        strcpy(disk.ble_device_name,names[i]); cfg_t before=disk;
        memset(&s_cfg,0,sizeof(s_cfg)); writes=0;
        assert(load_user_cfg()==ESP_OK && !memcmp(&s_cfg,&before,sizeof(before)));
        assert(!memcmp(&disk,&before,sizeof(before)) && writes==0);
        // No cfgver lookup: legacy settings without a version marker stay unchanged.
    }
    // Old shorter struct: preserve name/MAC, let generic load grow only trailing bytes.
    disk_len=38; strcpy(disk.ble_device_name,"Old Adapter");
    memset(&s_cfg,0,sizeof(s_cfg)); uint8_t mac[6]; memcpy(mac,disk.ble_obd_mac,6);
    assert(load_user_cfg()==ESP_OK && !strcmp(s_cfg.ble_device_name,"Old Adapter"));
    assert(!memcmp(mac,s_cfg.ble_obd_mac,6) && disk_len==sizeof(s_cfg));
    // Existing namespace with no settings is still a fresh configuration.
    cfg_exists=false; assert(load_user_cfg()==ESP_OK && !strcmp(disk.ble_device_name,"OBDBLE"));
    cfg_t before=disk; writes=0; open_error=99;
    assert(load_user_cfg()==99 && writes==0 && !memcmp(&disk,&before,sizeof(disk)));
    open_error=0; read_error=98;
    assert(load_user_cfg()==98 && writes==0 && !memcmp(&disk,&before,sizeof(disk)));
    read_error=0; cfg_exists=false; write_error=97;
    assert(load_user_cfg()==97 && writes==0);
    return 0;
}
'''
        health.PollHealthTest.run_c(self,harness)
        init = function(source,'nvs_storage_init')
        self.assertIn('err = load_user_cfg();',init)
        client=(ROOT/'main/bsp_obd_dsp/elm327_ble_client.c').read_text(encoding='utf-8')
        self.assertIn('static char s_target_name[32] = "OBDII";',client)

if __name__=='__main__': unittest.main()
