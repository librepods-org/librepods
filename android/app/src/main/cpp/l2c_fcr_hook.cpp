/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <android/log.h>
#include <fstream>
#include <string>
#include <sys/system_properties.h>
#include "l2c_fcr_hook.h"
#include <cerrno>
#include <cstdlib>

#define LOG_TAG "AirPodsHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static HookFunType hook_func = nullptr;
#define L2CEVT_L2CAP_CONFIG_REQ     4
#define L2CEVT_L2CAP_CONFIG_RSP 15

struct t_l2c_lcb;
typedef struct _BT_HDR {
    uint16_t event;
    uint16_t len;
    uint16_t offset;
    uint16_t layer_specific;
    uint8_t data[];
} BT_HDR;

typedef struct {
    uint8_t mode;
    uint8_t tx_win_sz;
    uint8_t max_transmit;
    uint16_t rtrans_tout;
    uint16_t mon_tout;
    uint16_t mps;
} tL2CAP_FCR;

// Flow spec structure
typedef struct {
    uint8_t  qos_present;
    uint8_t  flow_direction;
    uint8_t  service_type;
    uint32_t token_rate;
    uint32_t token_bucket_size;
    uint32_t peak_bandwidth;
    uint32_t latency;
    uint32_t delay_variation;
} FLOW_SPEC;

// Configuration info structure
typedef struct {
    uint16_t result;
    uint16_t mtu_present;
    uint16_t mtu;
    uint16_t flush_to_present;
    uint16_t flush_to;
    uint16_t qos_present;
    FLOW_SPEC qos;
    uint16_t fcr_present;
    tL2CAP_FCR fcr;
    uint16_t fcs_present;
    uint16_t fcs;
    uint16_t ext_flow_spec_present;
    FLOW_SPEC ext_flow_spec;
} tL2CAP_CFG_INFO;

// Basic L2CAP link control block
typedef struct {
    bool wait_ack;
    // Other FCR fields - not needed for our specific hook
} tL2C_FCRB;

// Forward declarations for needed types
struct t_l2c_rcb;
struct t_l2c_lcb;

typedef struct t_l2c_ccb {
    struct t_l2c_ccb* p_next_ccb;  // Next CCB in the chain
    struct t_l2c_ccb* p_prev_ccb;  // Previous CCB in the chain
    struct t_l2c_lcb* p_lcb;       // Link this CCB belongs to
    struct t_l2c_rcb* p_rcb;       // Registration CB for this Channel
    uint16_t local_cid;            // Local CID
    uint16_t remote_cid;           // Remote CID
    uint16_t p_lcb_next;           // For linking CCBs to an LCB
    uint8_t ccb_priority;          // Channel priority
    uint16_t tx_mps;               // MPS for outgoing messages
    uint16_t max_rx_mtu;           // Max MTU we will receive
    // State variables
    bool in_use;                   // True when channel active
    uint8_t chnl_state;            // Channel state
    uint8_t local_id;              // Transaction ID for local trans
    uint8_t remote_id;             // Transaction ID for remote
    uint8_t timer_entry;           // Timer entry
    uint8_t is_flushable;          // True if flushable
    // Configuration variables
    uint16_t our_cfg_bits;         // Bitmap of local config bits
    uint16_t peer_cfg_bits;        // Bitmap of peer config bits
    uint16_t config_done;          // Configuration bitmask
    uint16_t remote_config_rsp_result; // Remote config response result
    tL2CAP_CFG_INFO our_cfg;       // Our saved configuration options
    tL2CAP_CFG_INFO peer_cfg;      // Peer's saved configuration options
    // Additional control fields
    uint8_t remote_credit_count;   // Credits sent to peer
    tL2C_FCRB fcrb;                // FCR info
    bool ecoc;                     // Enhanced Credit-based mode
} tL2C_CCB;

static uint8_t (*original_l2c_fcr_chk_chan_modes)(void* p_ccb) = nullptr;
static void (*original_l2cu_process_our_cfg_req)(tL2C_CCB* p_ccb, tL2CAP_CFG_INFO* p_cfg) = nullptr;
static void (*original_l2c_csm_config)(tL2C_CCB* p_ccb, uint8_t event, void* p_data) = nullptr;
static void (*original_l2cu_send_peer_info_req)(tL2C_LCB* p_lcb, uint16_t info_type) = nullptr;

// Add original pointer for BTA_DmSetLocalDiRecord
static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD* p_device_info, uint32_t* p_handle) = nullptr;

uint8_t fake_l2c_fcr_chk_chan_modes(void* p_ccb) {
    LOGI("l2c_fcr_chk_chan_modes hooked, returning true.");
    return 1;
}

void fake_l2cu_process_our_cfg_req(tL2C_CCB* p_ccb, tL2CAP_CFG_INFO* p_cfg) {
    original_l2cu_process_our_cfg_req(p_ccb, p_cfg);
    p_ccb->our_cfg.fcr.mode = 0x00;
    LOGI("Set FCR mode to Basic Mode in outgoing config request");
}

void fake_l2c_csm_config(tL2C_CCB* p_ccb, uint8_t event, void* p_data) {
    // Call the original function first to handle the specific code path where the FCR mode is checked
    original_l2c_csm_config(p_ccb, event, p_data);

    // Check if this happens during CONFIG_RSP event handling
    if (event == L2CEVT_L2CAP_CONFIG_RSP) {
        p_ccb->our_cfg.fcr.mode = p_ccb->peer_cfg.fcr.mode;
        LOGI("Forced compatibility in l2c_csm_config: set our_mode=%d to match peer_mode=%d",
             p_ccb->our_cfg.fcr.mode, p_ccb->peer_cfg.fcr.mode);
    }
}

// Replacement function that does nothing
void fake_l2cu_send_peer_info_req(tL2C_LCB* p_lcb, uint16_t info_type) {
    LOGI("Intercepted l2cu_send_peer_info_req for info_type 0x%04x - doing nothing", info_type);
    // Just return without doing anything
    return;
}

// New loader for SDP hook offset (persist.librepods.sdp_offset)
uintptr_t loadSdpOffset() {
    const char* property_name = "persist.librepods.sdp_offset";
    char value[PROP_VALUE_MAX] = {0};

    int len = __system_property_get(property_name, value);
    if (len > 0) {
        LOGI("Read sdp offset from property: %s", value);
        uintptr_t offset;
        char* endptr = nullptr;

        const char* parse_start = value;
        if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X')) {
            parse_start = value + 2;
        }

        errno = 0;
        offset = strtoul(parse_start, &endptr, 16);

        if (errno == 0 && endptr != parse_start && *endptr == '\0' && offset > 0) {
            LOGI("Parsed sdp offset: 0x%x", offset);
            return offset;
        }

        LOGE("Failed to parse sdp offset from property value: %s", value);
    }

    LOGI("No sdp offset property present - skipping SDP hook");
    return 0;
}

// Fake BTA_DmSetLocalDiRecord: set vendor/vendor_id_source then call original
tBTA_STATUS fake_BTA_DmSetLocalDiRecord(tSDP_DI_RECORD* p_device_info, uint32_t* p_handle) {
    LOGI("BTA_DmSetLocalDiRecord hooked - forcing vendor fields");
    if (p_device_info) {
        p_device_info->vendor = 0x004C;
        p_device_info->vendor_id_source = 0x0001;
    }
    LOGI("Set vendor=0x%04x, vendor_id_source=0x%04x", p_device_info->vendor, p_device_info->vendor_id_source);
    if (original_BTA_DmSetLocalDiRecord) {
        return original_BTA_DmSetLocalDiRecord(p_device_info, p_handle);
    }

    LOGE("Original BTA_DmSetLocalDiRecord not available");
    return BTA_FAILURE;
}

uintptr_t loadHookOffset([[maybe_unused]] const char* package_name) {
    const char* property_name = "persist.librepods.hook_offset";
    char value[PROP_VALUE_MAX] = {0};

    int len = __system_property_get(property_name, value);
    if (len > 0) {
        LOGI("Read hook offset from property: %s", value);
        uintptr_t offset;
        char* endptr = nullptr;

        const char* parse_start = value;
        if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X')) {
            parse_start = value + 2;
        }

        errno = 0;
        offset = strtoul(parse_start, &endptr, 16);

        if (errno == 0 && endptr != parse_start && *endptr == '\0' && offset > 0) {
            LOGI("Parsed offset: 0x%x", offset);
            return offset;
        }

        LOGE("Failed to parse offset from property value: %s", value);
    }

    LOGI("Using hardcoded fallback offset");
    return 0x00a55e30;
}

uintptr_t loadL2cuProcessCfgReqOffset() {
    const char* property_name = "persist.librepods.cfg_req_offset";
    char value[PROP_VALUE_MAX] = {0};

    int len = __system_property_get(property_name, value);
    if (len > 0) {
        LOGI("Read l2cu_process_our_cfg_req offset from property: %s", value);
        uintptr_t offset;
        char* endptr = nullptr;

        const char* parse_start = value;
        if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X')) {
            parse_start = value + 2;
        }

        errno = 0;
        offset = strtoul(parse_start, &endptr, 16);

        if (errno == 0 && endptr != parse_start && *endptr == '\0' && offset > 0) {
            LOGI("Parsed l2cu_process_our_cfg_req offset: 0x%x", offset);
            return offset;
        }

        LOGE("Failed to parse l2cu_process_our_cfg_req offset from property value: %s", value);
    }

    // Return 0 if not found - we'll skip this hook
    return 0;
}

uintptr_t loadL2cCsmConfigOffset() {
    const char* property_name = "persist.librepods.csm_config_offset";
    char value[PROP_VALUE_MAX] = {0};

    int len = __system_property_get(property_name, value);
    if (len > 0) {
        LOGI("Read l2c_csm_config offset from property: %s", value);
        uintptr_t offset;
        char* endptr = nullptr;

        const char* parse_start = value;
        if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X')) {
            parse_start = value + 2;
        }

        errno = 0;
        offset = strtoul(parse_start, &endptr, 16);

        if (errno == 0 && endptr != parse_start && *endptr == '\0' && offset > 0) {
            LOGI("Parsed l2c_csm_config offset: 0x%x", offset);
            return offset;
        }

        LOGE("Failed to parse l2c_csm_config offset from property value: %s", value);
    }

    // Return 0 if not found - we'll skip this hook
    return 0;
}

uintptr_t loadL2cuSendPeerInfoReqOffset() {
    const char* property_name = "persist.librepods.peer_info_req_offset";
    char value[PROP_VALUE_MAX] = {0};

    int len = __system_property_get(property_name, value);
    if (len > 0) {
        LOGI("Read l2cu_send_peer_info_req offset from property: %s", value);
        uintptr_t offset;
        char* endptr = nullptr;

        const char* parse_start = value;
        if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X')) {
            parse_start = value + 2;
        }

        errno = 0;
        offset = strtoul(parse_start, &endptr, 16);

        if (errno == 0 && endptr != parse_start && *endptr == '\0' && offset > 0) {
            LOGI("Parsed l2cu_send_peer_info_req offset: 0x%x", offset);
            return offset;
        }

        LOGE("Failed to parse l2cu_send_peer_info_req offset from property value: %s", value);
    }

    // Return 0 if not found - we'll skip this hook
    return 0;
}

uintptr_t getModuleBase(const char *module_name) {
    FILE *fp;
    char line[1024];
    uintptr_t base_addr = 0;

    fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        LOGE("Failed to open /proc/self/maps");
        return 0;
    }

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, module_name)) {
            char *start_addr_str = line;
            char *end_addr_str = strchr(line, '-');
            if (end_addr_str) {
                *end_addr_str = '\0';
                base_addr = strtoull(start_addr_str, nullptr, 16);
                break;
            }
        }
    }

    fclose(fp);
    return base_addr;
}

bool findAndHookFunction(const char *library_name) {
    if (!hook_func) {
        LOGE("Hook function not initialized");
        return false;
    }

    uintptr_t base_addr = getModuleBase(library_name);
    if (!base_addr) {
        LOGE("Failed to get base address of %s", library_name);
        return false;
    }

    // Load all offsets from system properties - no hardcoding
    uintptr_t l2c_fcr_offset = loadHookOffset(nullptr);
    uintptr_t l2cu_process_our_cfg_req_offset = loadL2cuProcessCfgReqOffset();
    uintptr_t l2c_csm_config_offset = loadL2cCsmConfigOffset();
    uintptr_t l2cu_send_peer_info_req_offset = loadL2cuSendPeerInfoReqOffset();
    uintptr_t sdp_offset = loadSdpOffset();

    bool success = false;

    // Hook l2c_fcr_chk_chan_modes - this is our primary hook
    if (l2c_fcr_offset > 0) {
        void* target = reinterpret_cast<void*>(base_addr + l2c_fcr_offset);
        LOGI("Hooking l2c_fcr_chk_chan_modes at offset: 0x%x, base: %p, target: %p",
             l2c_fcr_offset, (void*)base_addr, target);

        int result = hook_func(target, (void*)fake_l2c_fcr_chk_chan_modes, (void**)&original_l2c_fcr_chk_chan_modes);
        if (result != 0) {
            LOGE("Failed to hook l2c_fcr_chk_chan_modes, error: %d", result);
            return false;
        }
        LOGI("Successfully hooked l2c_fcr_chk_chan_modes");
        success = true;
    } else {
        LOGE("No valid offset for l2c_fcr_chk_chan_modes found, cannot proceed");
        return false;
    }

    // Hook l2cu_process_our_cfg_req if offset is available
    if (l2cu_process_our_cfg_req_offset > 0) {
        void* target = reinterpret_cast<void*>(base_addr + l2cu_process_our_cfg_req_offset);
        LOGI("Hooking l2cu_process_our_cfg_req at offset: 0x%x, base: %p, target: %p",
             l2cu_process_our_cfg_req_offset, (void*)base_addr, target);

        int result = hook_func(target, (void*)fake_l2cu_process_our_cfg_req, (void**)&original_l2cu_process_our_cfg_req);
        if (result != 0) {
            LOGE("Failed to hook l2cu_process_our_cfg_req, error: %d", result);
            // Continue even if this hook fails
        } else {
            LOGI("Successfully hooked l2cu_process_our_cfg_req");
        }
    } else {
        LOGI("Skipping l2cu_process_our_cfg_req hook as offset is not available");
    }

    // Hook l2c_csm_config if offset is available
    if (l2c_csm_config_offset > 0) {
        void* target = reinterpret_cast<void*>(base_addr + l2c_csm_config_offset);
        LOGI("Hooking l2c_csm_config at offset: 0x%x, base: %p, target: %p",
             l2c_csm_config_offset, (void*)base_addr, target);

        int result = hook_func(target, (void*)fake_l2c_csm_config, (void**)&original_l2c_csm_config);
        if (result != 0) {
            LOGE("Failed to hook l2c_csm_config, error: %d", result);
            // Continue even if this hook fails
        } else {
            LOGI("Successfully hooked l2c_csm_config");
        }
    } else {
        LOGI("Skipping l2c_csm_config hook as offset is not available");
    }

    // Hook l2cu_send_peer_info_req if offset is available
    if (l2cu_send_peer_info_req_offset > 0) {
        void* target = reinterpret_cast<void*>(base_addr + l2cu_send_peer_info_req_offset);
        LOGI("Hooking l2cu_send_peer_info_req at offset: 0x%x, base: %p, target: %p",
             l2cu_send_peer_info_req_offset, (void*)base_addr, target);

        int result = hook_func(target, (void*)fake_l2cu_send_peer_info_req, (void**)&original_l2cu_send_peer_info_req);
        if (result != 0) {
            LOGE("Failed to hook l2cu_send_peer_info_req, error: %d", result);
            // Continue even if this hook fails
        } else {
            LOGI("Successfully hooked l2cu_send_peer_info_req");
        }
    } else {
        LOGI("Skipping l2cu_send_peer_info_req hook as offset is not available");
    }

    if (sdp_offset > 0) {
        void* target = reinterpret_cast<void*>(base_addr + sdp_offset);
        LOGI("Hooking BTA_DmSetLocalDiRecord at offset: 0x%x, base: %p, target: %p",
             sdp_offset, (void*)base_addr, target);

        int result = hook_func(target, (void*)fake_BTA_DmSetLocalDiRecord, (void**)&original_BTA_DmSetLocalDiRecord);
        if (result != 0) {
            LOGE("Failed to hook BTA_DmSetLocalDiRecord, error: %d", result);
        } else {
            LOGI("Successfully hooked BTA_DmSetLocalDiRecord (SDP)");
        }
    } else {
        LOGI("Skipping BTA_DmSetLocalDiRecord hook as sdp offset is not available");
    }

    return success;
}

void on_library_loaded(const char *name, [[maybe_unused]] void *handle) {
    if (strstr(name, "libbluetooth_jni.so")) {
        LOGI("Detected Bluetooth JNI library: %s", name);

        bool hooked = findAndHookFunction("libbluetooth_jni.so");
        if (!hooked) {
            LOGE("Failed to hook Bluetooth JNI library function");
        }
    } else if (strstr(name, "libbluetooth_qti.so")) {
        LOGI("Detected Bluetooth QTI library: %s", name);

        bool hooked = findAndHookFunction("libbluetooth_qti.so");
        if (!hooked) {
            LOGE("Failed to hook Bluetooth QTI library function");
        }
    }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    LOGI("L2C FCR Hook module initialized");

    hook_func = entries->hook_func;

    return on_library_loaded;
}