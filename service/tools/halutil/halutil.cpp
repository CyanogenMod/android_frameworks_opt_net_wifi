#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "wifi_hal.h"

#define LOG_TAG  "WifiHAL"

#include <utils/Log.h>
#include <inttypes.h>
#include <sys/socket.h>
#include <linux/if.h>
#include <ctype.h>
#include <stdarg.h>
#include "wifi_hal_stub.h"
#include <semaphore.h>

pthread_mutex_t printMutex;

static wifi_hal_fn hal_fn;
int init_wifi_hal_func_table(wifi_hal_fn *hal_fn) {
    if (hal_fn == NULL) {
        return -1;
    }
    hal_fn->wifi_initialize = wifi_initialize_stub;
    hal_fn->wifi_cleanup = wifi_cleanup_stub;
    hal_fn->wifi_event_loop = wifi_event_loop_stub;
    hal_fn->wifi_get_error_info = wifi_get_error_info_stub;
    hal_fn->wifi_get_supported_feature_set = wifi_get_supported_feature_set_stub;
    hal_fn->wifi_get_concurrency_matrix = wifi_get_concurrency_matrix_stub;
    hal_fn->wifi_set_scanning_mac_oui =  wifi_set_scanning_mac_oui_stub;
    hal_fn->wifi_get_supported_channels = wifi_get_supported_channels_stub;
    hal_fn->wifi_is_epr_supported = wifi_is_epr_supported_stub;
    hal_fn->wifi_get_ifaces = wifi_get_ifaces_stub;
    hal_fn->wifi_get_iface_name = wifi_get_iface_name_stub;
    hal_fn->wifi_reset_iface_event_handler = wifi_reset_iface_event_handler_stub;
    hal_fn->wifi_start_gscan = wifi_start_gscan_stub;
    hal_fn->wifi_stop_gscan = wifi_stop_gscan_stub;
    hal_fn->wifi_get_cached_gscan_results = wifi_get_cached_gscan_results_stub;
    hal_fn->wifi_set_bssid_hotlist = wifi_set_bssid_hotlist_stub;
    hal_fn->wifi_reset_bssid_hotlist = wifi_reset_bssid_hotlist_stub;
    hal_fn->wifi_set_significant_change_handler = wifi_set_significant_change_handler_stub;
    hal_fn->wifi_reset_significant_change_handler = wifi_reset_significant_change_handler_stub;
    hal_fn->wifi_get_gscan_capabilities = wifi_get_gscan_capabilities_stub;
    hal_fn->wifi_set_link_stats = wifi_set_link_stats_stub;
    hal_fn->wifi_get_link_stats = wifi_get_link_stats_stub;
    hal_fn->wifi_clear_link_stats = wifi_clear_link_stats_stub;
    hal_fn->wifi_get_valid_channels = wifi_get_valid_channels_stub;
    hal_fn->wifi_rtt_range_request = wifi_rtt_range_request_stub;
    hal_fn->wifi_rtt_range_cancel = wifi_rtt_range_cancel_stub;
    hal_fn->wifi_get_rtt_capabilities = wifi_get_rtt_capabilities_stub;
    hal_fn->wifi_set_nodfs_flag = wifi_set_nodfs_flag_stub;
    hal_fn->wifi_start_logging = wifi_start_logging_stub;
    hal_fn->wifi_set_epno_list = wifi_set_epno_list_stub;
    hal_fn->wifi_set_country_code = wifi_set_country_code_stub;
    hal_fn->wifi_get_firmware_memory_dump = wifi_get_firmware_memory_dump_stub;
    hal_fn->wifi_set_log_handler = wifi_set_log_handler_stub;
    hal_fn->wifi_set_alert_handler = wifi_set_alert_handler_stub;
        hal_fn->wifi_get_firmware_version = wifi_get_firmware_version_stub;
    hal_fn->wifi_get_ring_buffers_status = wifi_get_ring_buffers_status_stub;
    hal_fn->wifi_get_logger_supported_feature_set = wifi_get_logger_supported_feature_set_stub;
    hal_fn->wifi_get_ring_data = wifi_get_ring_data_stub;
    hal_fn->wifi_get_driver_version = wifi_get_driver_version_stub;
    hal_fn->wifi_set_ssid_white_list = wifi_set_ssid_white_list_stub;
    hal_fn->wifi_set_gscan_roam_params = wifi_set_gscan_roam_params_stub;
    hal_fn->wifi_set_bssid_preference = wifi_set_bssid_preference_stub;
    hal_fn->wifi_set_bssid_blacklist = wifi_set_bssid_blacklist_stub;
    hal_fn->wifi_enable_lazy_roam = wifi_enable_lazy_roam_stub;
    return 0;
}

void printMsg(const char *fmt, ...)
{
    pthread_mutex_lock(&printMutex);
    va_list l;
    va_start(l, fmt);

    vprintf(fmt, l);
    va_end(l);
    pthread_mutex_unlock(&printMutex);
}

template<typename T, unsigned N>
unsigned countof(T (&rgt)[N]) {
    return N;
}

template<typename T>
T min(const T& t1, const T& t2) {
    return (t1 < t2) ? t1 : t2;
}

#define	NBBY    8  /* number of bits/byte */

/* Bit map related macros. */
#define setbit(a,i) ((a)[(i)/NBBY] |= 1<<((i)%NBBY))
#define clrbit(a,i) ((a)[(i)/NBBY] &= ~(1<<((i)%NBBY)))
#define isset(a,i)  ((a)[(i)/NBBY] & (1<<((i)%NBBY)))
#define isclr(a,i)  (((a)[(i)/NBBY] & (1<<((i)%NBBY))) == 0)
#define CEIL(x, y)  (((x) + ((y) - 1)) / (y))

/* TLV defines */
#define TLV_TAG_OFF     0   /* tag offset */
#define TLV_LEN_OFF     1   /* length offset */
#define TLV_HDR_LEN     2   /* header length */
#define TLV_BODY_OFF    2   /* body offset */
#define TLV_BODY_LEN_MAX 255  /* max body length */


/* Information Element IDs */
#define WIFI_EID_SSID 0
#define WIFI_EID_SUPP_RATES 1
#define WIFI_EID_FH_PARAMS 2
#define WIFI_EID_DS_PARAMS 3
#define WIFI_EID_CF_PARAMS 4
#define WIFI_EID_TIM 5
#define WIFI_EID_IBSS_PARAMS 6
#define WIFI_EID_COUNTRY 7
#define WIFI_EID_BSS_LOAD 11
#define WIFI_EID_CHALLENGE 16
/* EIDs defined by IEEE 802.11h - START */
#define WIFI_EID_PWR_CONSTRAINT 32
#define WIFI_EID_PWR_CAPABILITY 33
#define WIFI_EID_TPC_REQUEST 34
#define WIFI_EID_TPC_REPORT 35
#define WIFI_EID_SUPPORTED_CHANNELS 36
#define WIFI_EID_CHANNEL_SWITCH 37
#define WIFI_EID_MEASURE_REQUEST 38
#define WIFI_EID_MEASURE_REPORT 39
#define WIFI_EID_QUITE 40
#define WIFI_EID_IBSS_DFS 41
/* EIDs defined by IEEE 802.11h - END */
#define WIFI_EID_ERP_INFO 42
#define WIFI_EID_HT_CAP 45
#define WIFI_EID_QOS 46
#define WIFI_EID_RSN 48
#define WIFI_EID_EXT_SUPP_RATES 50
#define WIFI_EID_NEIGHBOR_REPORT 52
#define WIFI_EID_MOBILITY_DOMAIN 54
#define WIFI_EID_FAST_BSS_TRANSITION 55
#define WIFI_EID_TIMEOUT_INTERVAL 56
#define WIFI_EID_RIC_DATA 57
#define WIFI_EID_SUPPORTED_OPERATING_CLASSES 59
#define WIFI_EID_HT_OPERATION 61
#define WIFI_EID_SECONDARY_CHANNEL_OFFSET 62
#define WIFI_EID_WAPI 68
#define WIFI_EID_TIME_ADVERTISEMENT 69
#define WIFI_EID_20_40_BSS_COEXISTENCE 72
#define WIFI_EID_20_40_BSS_INTOLERANT 73
#define WIFI_EID_OVERLAPPING_BSS_SCAN_PARAMS 74
#define WIFI_EID_MMIE 76
#define WIFI_EID_SSID_LIST 84
#define WIFI_EID_BSS_MAX_IDLE_PERIOD 90
#define WIFI_EID_TFS_REQ 91
#define WIFI_EID_TFS_RESP 92
#define WIFI_EID_WNMSLEEP 93
#define WIFI_EID_TIME_ZONE 98
#define WIFI_EID_LINK_ID 101
#define WIFI_EID_INTERWORKING 107
#define WIFI_EID_ADV_PROTO 108
#define WIFI_EID_QOS_MAP_SET 110
#define WIFI_EID_ROAMING_CONSORTIUM 111
#define WIFI_EID_EXT_CAPAB 127
#define WIFI_EID_CCKM 156
#define WIFI_EID_VHT_CAP 191
#define WIFI_EID_VHT_OPERATION 192
#define WIFI_EID_VHT_EXTENDED_BSS_LOAD 193
#define WIFI_EID_VHT_WIDE_BW_CHSWITCH  194
#define WIFI_EID_VHT_TRANSMIT_POWER_ENVELOPE 195
#define WIFI_EID_VHT_CHANNEL_SWITCH_WRAPPER 196
#define WIFI_EID_VHT_AID 197
#define WIFI_EID_VHT_QUIET_CHANNEL 198
#define WIFI_EID_VHT_OPERATING_MODE_NOTIFICATION 199
#define WIFI_EID_VENDOR_SPECIFIC 221


/* Extended capabilities IE bitfields */
/* 20/40 BSS Coexistence Management support bit position */
#define DOT11_EXT_CAP_OBSS_COEX_MGMT        0
/* Extended Channel Switching support bit position */
#define DOT11_EXT_CAP_EXT_CHAN_SWITCHING    2
/* scheduled PSMP support bit position */
#define DOT11_EXT_CAP_SPSMP         6
/*  Flexible Multicast Service */
#define DOT11_EXT_CAP_FMS           11
/* proxy ARP service support bit position */
#define DOT11_EXT_CAP_PROXY_ARP     12
/* Civic Location */
#define DOT11_EXT_CAP_CIVIC_LOC     14
/* Geospatial Location */
#define DOT11_EXT_CAP_LCI           15
/* Traffic Filter Service */
#define DOT11_EXT_CAP_TFS           16
/* WNM-Sleep Mode */
#define DOT11_EXT_CAP_WNM_SLEEP	    17
/* TIM Broadcast service */
#define DOT11_EXT_CAP_TIMBC         18
/* BSS Transition Management support bit position */
#define DOT11_EXT_CAP_BSSTRANS_MGMT 19
/* Direct Multicast Service */
#define DOT11_EXT_CAP_DMS           26
/* Interworking support bit position */
#define DOT11_EXT_CAP_IW            31
/* QoS map support bit position */
#define DOT11_EXT_CAP_QOS_MAP       32
/* service Interval granularity bit position and mask */
#define DOT11_EXT_CAP_SI            41
#define DOT11_EXT_CAP_SI_MASK       0x0E
/* WNM notification */
#define DOT11_EXT_CAP_WNM_NOTIF     46
/* Operating mode notification - VHT (11ac D3.0 - 8.4.2.29) */
#define DOT11_EXT_CAP_OPER_MODE_NOTIF  62
/* Fine timing measurement - D3.0 */
#define DOT11_EXT_CAP_FTM_RESPONDER    70
#define DOT11_EXT_CAP_FTM_INITIATOR	   71 /* tentative 11mcd3.0 */

#define DOT11_EXT_CH_MASK   0x03 /* extension channel mask */
#define DOT11_EXT_CH_UPPER  0x01 /* ext. ch. on upper sb */
#define DOT11_EXT_CH_LOWER  0x03 /* ext. ch. on lower sb */
#define DOT11_EXT_CH_NONE   0x00 /* no extension ch.  */

enum vht_op_chan_width {
    VHT_OP_CHAN_WIDTH_20_40	= 0,
    VHT_OP_CHAN_WIDTH_80	= 1,
    VHT_OP_CHAN_WIDTH_160	= 2,
    VHT_OP_CHAN_WIDTH_80_80	= 3
};
/**
 * Channel Factor for the starting frequence of 2.4 GHz channels.
 * The value corresponds to 2407 MHz.
 */
#define CHAN_FACTOR_2_4_G 4814 /* 2.4 GHz band, 2407 MHz */

/**
 * Channel Factor for the starting frequence of 5 GHz channels.
 * The value corresponds to 5000 MHz.
 */
#define CHAN_FACTOR_5_G 10000 /* 5 GHz band, 5000 MHz */


/* ************* HT definitions. ************* */
#define MCSSET_LEN 16       /* 16-bits per 8-bit set to give 128-bits bitmap of MCS Index */
#define MAX_MCS_NUM (128)   /* max mcs number = 128 */

struct ht_op_ie {
    u8  ctl_ch;         /* control channel number */
    u8  chan_info;      /* ext ch,rec. ch. width, RIFS support */
    u16 opmode;         /* operation mode */
    u16 misc_bits;      /* misc bits */
    u8  basic_mcs[MCSSET_LEN];  /* required MCS set */
} __attribute__ ((packed));
struct vht_op_ie {
    u8  chan_width;
    u8  chan1;
    u8  chan2;
    u16 supp_mcs; /* same def as above in vht cap */
} __attribute__ ((packed));

#define EVENT_BUF_SIZE 2048
#define MAX_CH_BUF_SIZE  64
#define MAX_FEATURE_SET  8
#define HOTLIST_LOST_WINDOW  5

static wifi_handle halHandle;
static wifi_interface_handle *ifaceHandles;
static wifi_interface_handle wlan0Handle;
static wifi_interface_handle p2p0Handle;
static int numIfaceHandles;
static int cmdId = 0;
static int ioctl_sock = 0;
static int max_event_wait = 5;
static int stest_max_ap = 10;
static int stest_base_period = 5000;
static int stest_threshold_percent = 80;
static int stest_threshold_num_scans = 10;
static int swctest_rssi_sample_size =  3;
static int swctest_rssi_lost_ap =  3;
static int swctest_rssi_min_breaching =  2;
static int swctest_rssi_ch_threshold =  1;
static int htest_low_threshold =  90;
static int htest_high_threshold =  10;

#define FILE_NAME_LEN 128
#define FILE_MAX_SIZE (1 * 1024 * 1024)
#define MAX_RING_NAME_SIZE 32

#define NUM_ALERT_DUMPS 10

/////////////////////////////////////////////////////////////////
// Logger related.

#define DEFAULT_MEMDUMP_FILE "/data/memdump.bin"
#define ALERT_MEMDUMP_PREFIX "/data/alertdump"
#define RINGDATA_PREFIX "/data/ring-"

static char mem_dump_file[FILE_NAME_LEN] = DEFAULT_MEMDUMP_FILE;

struct LoggerParams {
    u32 verbose_level;
    u32 flags;
    u32 max_interval_sec;
    u32 min_data_size;
    wifi_ring_buffer_id ring_id;
    char ring_name[MAX_RING_NAME_SIZE];
};
struct LoggerParams default_logger_param = {0,0,0,0,0,0};

char default_ring_name[MAX_RING_NAME_SIZE] = "fw_event";

typedef enum {
    LOG_INVALID = -1,
    LOG_START,
    LOG_GET_MEMDUMP,
    LOG_GET_FW_VER,
    LOG_GET_DRV_VER,
    LOG_GET_RING_STATUS,
    LOG_GET_RINGDATA,
    LOG_GET_FEATURE,
    LOG_GET_RING_DATA,
    LOG_SET_LOG_HANDLER,
    LOG_SET_ALERT_HANDLER,
} LoggerCmd;

LoggerCmd log_cmd = LOG_INVALID;
wifi_ring_buffer_id ringId = -1;

#define C2S(x)  case x: return #x;

static const char *RBentryTypeToString(int cmd) {
    switch (cmd) {
        C2S(ENTRY_TYPE_CONNECT_EVENT)
        C2S(ENTRY_TYPE_PKT)
        C2S(ENTRY_TYPE_WAKE_LOCK)
        C2S(ENTRY_TYPE_POWER_EVENT)
        C2S(ENTRY_TYPE_DATA)
        default:
            return "ENTRY_TYPE_UNKNOWN";
    }
}

static const char *RBconnectEventToString(int cmd)
{
    switch (cmd) {
        C2S(WIFI_EVENT_ASSOCIATION_REQUESTED)
        C2S(WIFI_EVENT_AUTH_COMPLETE)
        C2S(WIFI_EVENT_ASSOC_COMPLETE)
        C2S(WIFI_EVENT_FW_AUTH_STARTED)
        C2S(WIFI_EVENT_FW_ASSOC_STARTED)
        C2S(WIFI_EVENT_FW_RE_ASSOC_STARTED)
        C2S(WIFI_EVENT_DRIVER_SCAN_REQUESTED)
        C2S(WIFI_EVENT_DRIVER_SCAN_RESULT_FOUND)
        C2S(WIFI_EVENT_DRIVER_SCAN_COMPLETE)
        C2S(WIFI_EVENT_G_SCAN_STARTED)
        C2S(WIFI_EVENT_G_SCAN_COMPLETE)
        C2S(WIFI_EVENT_DISASSOCIATION_REQUESTED)
        C2S(WIFI_EVENT_RE_ASSOCIATION_REQUESTED)
        C2S(WIFI_EVENT_ROAM_REQUESTED)
        C2S(WIFI_EVENT_BEACON_RECEIVED)
        C2S(WIFI_EVENT_ROAM_SCAN_STARTED)
        C2S(WIFI_EVENT_ROAM_SCAN_COMPLETE)
        C2S(WIFI_EVENT_ROAM_SEARCH_STARTED)
        C2S(WIFI_EVENT_ROAM_SEARCH_STOPPED)
        C2S(WIFI_EVENT_CHANNEL_SWITCH_ANOUNCEMENT)
        C2S(WIFI_EVENT_FW_EAPOL_FRAME_TRANSMIT_START)
        C2S(WIFI_EVENT_FW_EAPOL_FRAME_TRANSMIT_STOP)
        C2S(WIFI_EVENT_DRIVER_EAPOL_FRAME_TRANSMIT_REQUESTED)
        C2S(WIFI_EVENT_FW_EAPOL_FRAME_RECEIVED)
        C2S(WIFI_EVENT_DRIVER_EAPOL_FRAME_RECEIVED)
        C2S(WIFI_EVENT_BLOCK_ACK_NEGOTIATION_COMPLETE)
        C2S(WIFI_EVENT_BT_COEX_BT_SCO_START)
        C2S(WIFI_EVENT_BT_COEX_BT_SCO_STOP)
        C2S(WIFI_EVENT_BT_COEX_BT_SCAN_START)
        C2S(WIFI_EVENT_BT_COEX_BT_SCAN_STOP)
        C2S(WIFI_EVENT_BT_COEX_BT_HID_START)
        C2S(WIFI_EVENT_BT_COEX_BT_HID_STOP)
        C2S(WIFI_EVENT_ROAM_AUTH_STARTED)
        C2S(WIFI_EVENT_ROAM_AUTH_COMPLETE)
        C2S(WIFI_EVENT_ROAM_ASSOC_STARTED)
        C2S(WIFI_EVENT_ROAM_ASSOC_COMPLETE)
        default:
            return "WIFI_EVENT_UNKNOWN";
    }
}

static const char *RBTlvTagToString(int cmd) {
    switch (cmd) {
        C2S(WIFI_TAG_VENDOR_SPECIFIC)
        C2S(WIFI_TAG_BSSID)
        C2S(WIFI_TAG_ADDR)
        C2S(WIFI_TAG_SSID)
        C2S(WIFI_TAG_STATUS)
        C2S(WIFI_TAG_CHANNEL_SPEC)
        C2S(WIFI_TAG_WAKE_LOCK_EVENT)
        C2S(WIFI_TAG_ADDR1)
        C2S(WIFI_TAG_ADDR2)
        C2S(WIFI_TAG_ADDR3)
        C2S(WIFI_TAG_ADDR4)
        C2S(WIFI_TAG_IE)
        C2S(WIFI_TAG_INTERFACE)
        C2S(WIFI_TAG_REASON_CODE)
        C2S(WIFI_TAG_RATE_MBPS)
        default:
            return "WIFI_TAG_UNKNOWN";
    }
}

static const char *RBchanWidthToString(int cmd) {
    switch (cmd) {
        C2S(WIFI_CHAN_WIDTH_20)
        C2S(WIFI_CHAN_WIDTH_40)
        C2S(WIFI_CHAN_WIDTH_80)
        C2S(WIFI_CHAN_WIDTH_160)
        C2S(WIFI_CHAN_WIDTH_80P80)
        C2S(WIFI_CHAN_WIDTH_5)
        C2S(WIFI_CHAN_WIDTH_10)
        C2S(WIFI_CHAN_WIDTH_INVALID)
        default:
            return "WIFI_CHAN_WIDTH_INVALID";
    }
}

/////////////////////////////////////////////////////////////////
// RTT related to configuration
#define FILE_NAME_LEN 128
#define MAX_SSID_LEN (32 + 1)
/* 18-bytes of Ethernet address buffer length */
#define ETHER_ADDR_STR_LEN	18

#define DEFAULT_RTT_FILE "/data/rtt-ap.list"
static int rtt_from_file = 0;
static int rtt_to_file = 0;
static wifi_band band = WIFI_BAND_UNSPECIFIED;
static int max_ap = 256; // the maximum count of  ap for RTT test
static char rtt_aplist[FILE_NAME_LEN] = DEFAULT_RTT_FILE;
struct rtt_params {
    u32 burst_period;
    u32 num_burst;
    u32 num_frames_per_burst;
    u32 num_retries_per_ftm;
    u32 num_retries_per_ftmr;
    u32 burst_duration;
    u8 LCI_request;
    u8 LCR_request;
    u8 preamble;
    u8 bw;
};
struct rtt_params default_rtt_param = {0, 0, 0, 0, 0, 15, 0, 0, 0, 0};

static int A_band_boost_threshold = 65;
static int A_band_penalty_threshold = 75;
static int A_band_boost_factor = 4;
static int A_band_penalty_factor = 2;
static int A_band_max_boost = 50;
static int lazy_roam_hysteresis = 10;
static int alert_roam_rssi_trigger = 65;
static int lazy_roam = 1;

mac_addr hotlist_bssids[16];
mac_addr blacklist_bssids[16];
unsigned char mac_oui[3];
wifi_epno_network epno_ssid[32];
int channel_list[16];
int num_hotlist_bssids = 0;
int num_channels = 0;
int num_epno_ssids = -1;
char whitelist_ssids[16][32];
int num_whitelist_ssids = -1;
mac_addr pref_bssids[16];
int rssi_modifier[16];
int num_pref_bssids = -1;
int num_blacklist_bssids = -1;

#define EPNO_HIDDEN               (1 << 0)
#define EPNO_A_BAND_TRIG          (1 << 1)
#define EPNO_BG_BAND_TRIG         (1 << 2)
#define EPNO_ABG_BAND_TRIG        (EPNO_A_BAND_TRIG | EPNO_BG_BAND_TRIG)

void parseMacAddress(const char *str, mac_addr addr);

int linux_set_iface_flags(int sock, const char *ifname, int dev_up)
{
    struct ifreq ifr;
    int ret;

    printMsg("setting interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");

    if (sock < 0) {
        printMsg("Bad socket: %d\n", sock);
        return -1;
    }

    memset(&ifr, 0, sizeof(ifr));
    strlcpy(ifr.ifr_name, ifname, IFNAMSIZ);

    printMsg("reading old value\n");

    if (ioctl(sock, SIOCGIFFLAGS, &ifr) != 0) {
        ret = errno ? -errno : -999;
        printMsg("Could not read interface %s flags: %d\n", ifname, errno);
        return ret;
    } else {
        printMsg("writing new value\n");
    }

    if (dev_up) {
        if (ifr.ifr_flags & IFF_UP) {
            printMsg("interface %s is already up\n", ifname);
            return 0;
        }
        ifr.ifr_flags |= IFF_UP;
    } else {
        if (!(ifr.ifr_flags & IFF_UP)) {
            printMsg("interface %s is already down\n", ifname);
            return 0;
        }
        ifr.ifr_flags &= ~IFF_UP;
    }

    if (ioctl(sock, SIOCSIFFLAGS, &ifr) != 0) {
      ret = -errno;
      printMsg("Could not set interface %s flags \n", ifname);
      return ret;
    }else {
        printMsg("set interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");
        printMsg("Could not set interface %s flags \n", ifname);
        return ret;
    }
    printMsg("Done\n");
    return 0;
}


static int init() {
    if(init_wifi_hal_func_table(&hal_fn) != 0 ) {
        ALOGD("Can not initialize the basic function pointer table");
        return -1;
    }

    wifi_error res = init_wifi_vendor_hal_func_table(&hal_fn);
    if (res != WIFI_SUCCESS) {
        ALOGD("Can not initialize the vendor function pointer table");
        return -1;
    }

    ioctl_sock = socket(PF_INET, SOCK_DGRAM, 0);
    if (ioctl_sock < 0) {
        printMsg("Bad socket: %d\n", ioctl_sock);
        return errno;
    } else {
        printMsg("Good socket: %d\n", ioctl_sock);
    }

    int ret = linux_set_iface_flags(ioctl_sock, "wlan0", 1);
    if (ret < 0) {
        return ret;
    }

    res = hal_fn.wifi_initialize(&halHandle);
    if (res < 0) {
        return res;
    }

    res = hal_fn.wifi_get_ifaces(halHandle, &numIfaceHandles, &ifaceHandles);
    if (res < 0) {
        return res;
    }

    char buf[EVENT_BUF_SIZE];
    for (int i = 0; i < numIfaceHandles; i++) {
        if (hal_fn.wifi_get_iface_name(ifaceHandles[i], buf, sizeof(buf)) == WIFI_SUCCESS) {
            if (strcmp(buf, "wlan0") == 0) {
                printMsg("found interface %s\n", buf);
                wlan0Handle = ifaceHandles[i];
            } else if (strcmp(buf, "p2p0") == 0) {
                printMsg("found interface %s\n", buf);
                p2p0Handle = ifaceHandles[i];
            }
        }
    }

    return res;
}

static void cleaned_up_handler(wifi_handle handle) {
    printMsg("HAL cleaned up handler\n");
    halHandle = NULL;
    ifaceHandles = NULL;
}

static void cleanup() {
    printMsg("cleaning up HAL\n");
    hal_fn.wifi_cleanup(halHandle, cleaned_up_handler);
}

sem_t event_thread_mutex;

static void *eventThreadFunc(void *context) {

    printMsg("starting wifi event loop\n");
    sem_post( &event_thread_mutex );
    hal_fn.wifi_event_loop(halHandle);
    printMsg("out of wifi event loop\n");

    return NULL;
}


static int getNewCmdId() {
    return cmdId++;
}

/* -------------------------------------------  */
/* helpers                                      */
/* -------------------------------------------  */

void printScanHeader() {
    printMsg("SSID\t\t\t\t\tBSSID\t\t  RSSI\tchannel\ttimestamp\tRTT\tRTT SD\n");
    printMsg("----\t\t\t\t\t-----\t\t  ----\t-------\t---------\t---\t------\n");
}

void printScanResult(wifi_scan_result result) {

    printMsg("%-32s\t", result.ssid);

    printMsg("%02x:%02x:%02x:%02x:%02x:%02x ", result.bssid[0], result.bssid[1],
            result.bssid[2], result.bssid[3], result.bssid[4], result.bssid[5]);

    printMsg("%d\t", result.rssi);
    printMsg("%d\t", result.channel);
    printMsg("%lld\t", result.ts);
    printMsg("%lld\t", result.rtt);
    printMsg("%lld\n", result.rtt_sd);
}

void printSignificantChangeResult(wifi_significant_change_result *res) {

    wifi_significant_change_result &result = *res;
    printMsg("%02x:%02x:%02x:%02x:%02x:%02x ", result.bssid[0], result.bssid[1],
            result.bssid[2], result.bssid[3], result.bssid[4], result.bssid[5]);

    printMsg("%d\t", result.channel);

    for (int i = 0; i < result.num_rssi; i++) {
        printMsg("%d,", result.rssi[i]);
    }
    printMsg("\n");
}

void printScanCapabilities(wifi_gscan_capabilities capabilities)
{
    printMsg("Scan Capabililites\n");
    printMsg("  max_scan_cache_size = %d\n", capabilities.max_scan_cache_size);
    printMsg("  max_scan_buckets = %d\n", capabilities.max_scan_buckets);
    printMsg("  max_ap_cache_per_scan = %d\n", capabilities.max_ap_cache_per_scan);
    printMsg("  max_rssi_sample_size = %d\n", capabilities.max_rssi_sample_size);
    printMsg("  max_scan_reporting_threshold = %d\n", capabilities.max_scan_reporting_threshold);
    printMsg("  max_hotlist_bssids = %d\n", capabilities.max_hotlist_bssids);
    printMsg("  max_significant_wifi_change_aps = %d\n",
            capabilities.max_significant_wifi_change_aps);
    printMsg("  max_number_epno_networks = %d\n", capabilities.max_number_epno_networks);
}


/* -------------------------------------------  */
/* commands and events                          */
/* -------------------------------------------  */

typedef enum {
    EVENT_TYPE_SCAN_RESULTS_AVAILABLE = 1000,
    EVENT_TYPE_HOTLIST_AP_FOUND = 1001,
    EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE = 1002,
    EVENT_TYPE_RTT_RESULTS = 1003,
    EVENT_TYPE_SCAN_COMPLETE = 1004,
    EVENT_TYPE_HOTLIST_AP_LOST = 1005,
    EVENT_TYPE_EPNO_SSID = 1006,
    EVENT_TYPE_LOGGER_RINGBUFFER_DATA = 1007,
    EVENT_TYPE_LOGGER_MEMDUMP_DATA = 1008,
    EVENT_TYPE_LOGGER_ALERT_DATA = 1009,
} EventType;

typedef struct {
    int type;
    char buf[256];
} EventInfo;

const int MAX_EVENTS_IN_CACHE = 256;
EventInfo eventCache[256];
int eventsInCache = 0;
pthread_cond_t eventCacheCondition;
pthread_mutex_t eventCacheMutex;

void putEventInCache(int type, const char *msg) {
    pthread_mutex_lock(&eventCacheMutex);
    if (eventsInCache + 1 < MAX_EVENTS_IN_CACHE) {
        eventCache[eventsInCache].type = type;
        strcpy(eventCache[eventsInCache].buf, msg);
        eventsInCache++;
        pthread_cond_signal(&eventCacheCondition);
        //printf("put new event in cache; size = %d\n", eventsInCache);
    } else {
        printf("Too many events in the cache\n");
    }
    pthread_mutex_unlock(&eventCacheMutex);
}

void getEventFromCache(EventInfo& info) {
    pthread_mutex_lock(&eventCacheMutex);
    while (true) {
        if (eventsInCache > 0) {
            //printf("found an event in cache; size = %d\n", eventsInCache);
            info.type = eventCache[0].type;
            strcpy(info.buf, eventCache[0].buf);
            eventsInCache--;
            memmove(&eventCache[0], &eventCache[1], sizeof(EventInfo) * eventsInCache);
            pthread_mutex_unlock(&eventCacheMutex);
            return;
        } else {
            pthread_cond_wait(&eventCacheCondition, &eventCacheMutex);
            //printf("pthread_cond_wait unblocked ...\n");
        }
    }
}

int numScanResultsAvailable = 0;
static void onScanResultsAvailable(wifi_request_id id, unsigned num_results) {
    printMsg("Received scan results available event\n");
    numScanResultsAvailable = num_results;
    putEventInCache(EVENT_TYPE_SCAN_RESULTS_AVAILABLE, "New scan results are available");
}

static void on_scan_event(wifi_scan_event event, unsigned status) {
    if (event == WIFI_SCAN_BUFFER_FULL) {
        printMsg("Received scan complete event - WIFI_SCAN_BUFFER_FULL \n");
    } else if(event == WIFI_SCAN_COMPLETE) {
        printMsg("Received scan complete event  - WIFI_SCAN_COMPLETE\n");
    }
}

static int scanCmdId;
static int hotlistCmdId;
static int significantChangeCmdId;
static int rttCmdId;
static int epnoCmdId;
static int loggerCmdId;

static bool startScan( void (*pfnOnResultsAvailable)(wifi_request_id, unsigned),
        int max_ap_per_scan, int base_period, int threshold_percent, int threshold_num_scans) {

    /* Get capabilties */
    wifi_gscan_capabilities capabilities;
    int result = hal_fn.wifi_get_gscan_capabilities(wlan0Handle, &capabilities);
    if (result < 0) {
        printMsg("failed to get scan capabilities - %d\n", result);
        printMsg("trying scan anyway ..\n");
    } else {
        printScanCapabilities(capabilities);
    }

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    if(num_channels > 0){
        params.max_ap_per_scan = max_ap_per_scan;
        params.base_period = base_period;                      // 5 second by default
        params.report_threshold_percent = threshold_percent;
        params.report_threshold_num_scans = threshold_num_scans;
        params.num_buckets = 1;

        params.buckets[0].bucket = 0;
        params.buckets[0].band = WIFI_BAND_UNSPECIFIED;
        params.buckets[0].period = base_period;
        params.buckets[0].num_channels = num_channels;

        for(int i = 0; i < num_channels; i++){
            params.buckets[0].channels[i].channel = channel_list[i];
        }

    } else {

        /* create a schedule to scan channels 1, 6, 11 every 5 second and
         * scan 36, 40, 44, 149, 153, 157, 161 165 every 10 second */
        params.max_ap_per_scan = max_ap_per_scan;
        params.base_period = base_period;                      // 5 second
        params.report_threshold_percent = threshold_percent;
        params.report_threshold_num_scans = threshold_num_scans;
        params.num_buckets = 3;

        params.buckets[0].bucket = 0;
        params.buckets[0].band = WIFI_BAND_UNSPECIFIED;
        params.buckets[0].period = 5000;                // 5 second
        params.buckets[0].report_events = 0;
        params.buckets[0].num_channels = 2;

        params.buckets[0].channels[0].channel = 2412;
        params.buckets[0].channels[1].channel = 2437;

        params.buckets[1].bucket = 1;
        params.buckets[1].band = WIFI_BAND_A;
        params.buckets[1].period = 10000;               // 10 second
        params.buckets[1].report_events = 1;
        params.buckets[1].num_channels = 8;   // driver should ignore list since band is specified


        params.buckets[1].channels[0].channel = 5180;
        params.buckets[1].channels[1].channel = 5200;
        params.buckets[1].channels[2].channel = 5220;
        params.buckets[1].channels[3].channel = 5745;
        params.buckets[1].channels[4].channel = 5765;
        params.buckets[1].channels[5].channel = 5785;
        params.buckets[1].channels[6].channel = 5805;
        params.buckets[1].channels[7].channel = 5825;

        params.buckets[2].bucket = 2;
        params.buckets[2].band = WIFI_BAND_UNSPECIFIED;
        params.buckets[2].period = 15000;                // 15 second
        params.buckets[2].report_events = 2;
        params.buckets[2].num_channels = 1;

        params.buckets[2].channels[0].channel = 2462;
    }

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = pfnOnResultsAvailable;
    handler.on_scan_event = on_scan_event;

    scanCmdId = getNewCmdId();
    printMsg("Starting scan --->\n");
    return hal_fn.wifi_start_gscan(scanCmdId, wlan0Handle, params, handler) == WIFI_SUCCESS;
}

static void stopScan() {
    wifi_request_id id = scanCmdId;
    if (id == 0)
        id = -1;

    hal_fn.wifi_stop_gscan(id, wlan0Handle);
    scanCmdId = 0;
}

wifi_scan_result **saved_scan_results;
unsigned max_saved_scan_results;
unsigned num_saved_scan_results;

static void on_single_shot_scan_event(wifi_scan_event event, unsigned status) {
    if (event == WIFI_SCAN_BUFFER_FULL) {
        printMsg("Received scan complete event - WIFI_SCAN_BUFFER_FULL \n");
    } else if(event == WIFI_SCAN_COMPLETE) {
        printMsg("Received scan complete event  - WIFI_SCAN_COMPLETE\n");
        putEventInCache(EVENT_TYPE_SCAN_COMPLETE, "One scan completed");
    }
}

static void on_full_scan_result(wifi_request_id id, wifi_scan_result *r) {
    if (num_saved_scan_results < max_saved_scan_results) {
        int alloc_len = offsetof(wifi_scan_result, ie_data) + r->ie_length;
        wifi_scan_result **result = &(saved_scan_results[num_saved_scan_results]);
        *result = (wifi_scan_result *)malloc(alloc_len);
        memcpy(*result, r, alloc_len);
        num_saved_scan_results++;
    }
}

static int scanOnce(wifi_band band, wifi_scan_result **results, int num_results) {

    saved_scan_results = results;
    max_saved_scan_results = num_results;
    num_saved_scan_results = 0;

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    params.max_ap_per_scan = 10;
    params.base_period = 5000;                        // 5 second by default
    params.report_threshold_percent = 90;
    params.report_threshold_num_scans = 1;
    params.num_buckets = 1;

    params.buckets[0].bucket = 0;
    params.buckets[0].band = band;
    params.buckets[0].period = 5000;                  // 5 second
    params.buckets[0].report_events = 2;              // REPORT_EVENTS_AFTER_EACH_SCAN
    params.buckets[0].num_channels = 0;

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = NULL;
    handler.on_scan_event = on_single_shot_scan_event;
    handler.on_full_scan_result = on_full_scan_result;

    int scanCmdId = getNewCmdId();
    printMsg("Starting scan --->\n");
    if (hal_fn.wifi_start_gscan(scanCmdId, wlan0Handle, params, handler) == WIFI_SUCCESS) {
        int events = 0;
        while (true) {
            EventInfo info;
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);
            if (info.type == EVENT_TYPE_SCAN_RESULTS_AVAILABLE
                    || info.type == EVENT_TYPE_SCAN_COMPLETE) {
                int retrieved_num_results = num_saved_scan_results;
                if (retrieved_num_results == 0) {
                    printMsg("fetched 0 scan results, waiting for more..\n");
                    continue;
                } else {
                    printMsg("fetched %d scan results\n", retrieved_num_results);

                    printMsg("Scan once completed, stopping scan\n");
                    hal_fn.wifi_stop_gscan(scanCmdId, wlan0Handle);
                    saved_scan_results = NULL;
                    max_saved_scan_results = 0;
                    num_saved_scan_results = 0;
                    return retrieved_num_results;
                }
            }
        }
    } else {
        return 0;
    }
}

static void retrieveScanResults() {

    int num_results = 64;
    wifi_cached_scan_results results[64];
    memset(results, 0, sizeof(wifi_cached_scan_results) * num_results);
    printMsg("Retrieve Scan results available -->\n");
    int result = hal_fn.wifi_get_cached_gscan_results(wlan0Handle, 1, num_results, results, &num_results);
    if (result < 0) {
        printMsg("failed to fetch scan results : %d\n", result);
        return;
    } else {
        printMsg("fetched %d scan results\n", num_results);
    }

    printScanHeader();
    for (int i = 0; i < num_results; i++) {
        printMsg("ScanId = %d, Flags = %x, num results = %d\n",
            results[i].scan_id, results[i].flags, results[i].num_results);
        for (int j = 0; j < results[i].num_results; j++) {
            printScanResult(results[i].results[j]);
        }
        printMsg("\n");
    }
}


static int compareScanResultsByRssi(const void *p1, const void *p2) {
    const wifi_scan_result *result1 = *(const wifi_scan_result **)(p1);
    const wifi_scan_result *result2 = *(const wifi_scan_result **)(p2);

    /* RSSI is -ve, so lower one wins */
    if (result1->rssi < result2->rssi) {
        return 1;
    } else if (result1->rssi == result2->rssi) {
        return 0;
    } else {
        return -1;
    }
}

static void sortScanResultsByRssi(wifi_scan_result **results, int num_results) {
    qsort(results, num_results, sizeof(wifi_scan_result*), &compareScanResultsByRssi);
}

static int removeDuplicateScanResults(wifi_scan_result **results, int num) {
    /* remove duplicates by BSSID */
    int num_results = num;
    wifi_scan_result *tmp;
    for (int i = 0; i < num_results; i++) {
        for (int j = i + 1; j < num_results; ) {
            if (memcmp((*results[i]).bssid, (*results[j]).bssid, sizeof(mac_addr)) == 0) {
                int num_to_move = num_results - j - 1;
                tmp = results[j];
                memmove(&results[j], &results[j+1], num_to_move * sizeof(wifi_scan_result *));
                free(tmp);
                num_results--;
            } else {
                j++;
            }
        }
    }
    return num_results;
}

static void onRTTResults (wifi_request_id id, unsigned num_results, wifi_rtt_result *result[]) {

    printMsg("RTT results\n");
    wifi_rtt_result *rtt_result;
    mac_addr addr = {0};
    for (unsigned i = 0; i < num_results; i++) {
        rtt_result = result[i];
        if (memcmp(addr, rtt_result->addr, sizeof(mac_addr))) {
            printMsg("Target mac : %02x:%02x:%02x:%02x:%02x:%02x\n",
                    rtt_result->addr[0],
                    rtt_result->addr[1],
                    rtt_result->addr[2],
                    rtt_result->addr[3],
                    rtt_result->addr[4],
                    rtt_result->addr[5]);
            memcpy(addr, rtt_result->addr, sizeof(mac_addr));
        }
        printMsg("\tburst_num : %d, measurement_number : %d, success_number : %d\n"
                "\tnumber_per_burst_peer : %d, status : %d, retry_after_duration : %d s\n"
                "\trssi : %d dbm, rx_rate : %d Kbps, rtt : %llu ns, rtt_sd : %llu\n"
                "\tdistance : %d cm, burst_duration : %d ms, negotiated_burst_num : %d\n",
                rtt_result->burst_num, rtt_result->measurement_number,
                rtt_result->success_number, rtt_result->number_per_burst_peer,
                rtt_result->status, rtt_result->retry_after_duration,
                rtt_result->rssi, rtt_result->rx_rate.bitrate * 100,
                rtt_result->rtt/10, rtt_result->rtt_sd, rtt_result->distance,
                rtt_result->burst_duration, rtt_result->negotiated_burst_num);
    }

    putEventInCache(EVENT_TYPE_RTT_RESULTS, "RTT results");
}

static void onHotlistAPFound(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {

    printMsg("Found hotlist APs\n");
    for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
    putEventInCache(EVENT_TYPE_HOTLIST_AP_FOUND, "Found a hotlist AP");
}

static void onHotlistAPLost(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {

    printMsg("Lost hotlist APs\n");
    for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
    putEventInCache(EVENT_TYPE_HOTLIST_AP_LOST, "Lost event Hotlist APs");
}

static void onePnoSsidFound(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {

    printMsg("Found ePNO SSID\n");
    for (unsigned i = 0; i < num_results; i++) {
        printMsg("SSID %s, channel %d, rssi %d\n", results[i].ssid,
            results[i].channel, (signed char)results[i].rssi);
    }
    putEventInCache(EVENT_TYPE_EPNO_SSID, "Found ePNO SSID");
}

static const u8 *bss_get_ie(u8 id, const char* ie, const s32 ie_len)
{
    const u8 *end, *pos;

    pos = (const u8 *)ie;
    end = pos + ie_len;

    while (pos + 1 < end) {
        if (pos + 2 + pos[1] > end)
            break;
        if (pos[0] == id)
            return pos;
        pos += 2 + pos[1];
    }

    return NULL;
}
static bool is11mcAP(const char* ie, const s32 ie_len)
{
    const u8 *ext_cap_ie, *ptr_ie;
    u8 ext_cap_length = 0;
    ptr_ie = bss_get_ie(WIFI_EID_EXT_CAPAB, ie, ie_len);
    if (ptr_ie) {
        ext_cap_length = *(ptr_ie + TLV_LEN_OFF);
        ext_cap_ie = ptr_ie + TLV_BODY_OFF;
        if ((ext_cap_length >= CEIL(DOT11_EXT_CAP_FTM_RESPONDER, NBBY)) &&
                (isset(ext_cap_ie, DOT11_EXT_CAP_FTM_RESPONDER) ||
                 isset(ext_cap_ie, DOT11_EXT_CAP_FTM_INITIATOR))) {
            return true;
        }
    }
    return false;
}

int channel2mhz(uint ch)
{
    int freq;
    int start_factor = (ch > 14)? CHAN_FACTOR_5_G : CHAN_FACTOR_2_4_G;
    if ((start_factor == CHAN_FACTOR_2_4_G && (ch < 1 || ch > 14)) ||
            (ch > 200))
        freq = -1;
    else if ((start_factor == CHAN_FACTOR_2_4_G) && (ch == 14))
        freq = 2484;
    else
        freq = ch * 5 + start_factor / 2;

    return freq;
}

struct ht_op_ie *read_ht_oper_ie(const char* ie, const s32 ie_len)
{
    const u8 *ptr_ie;
    ptr_ie = bss_get_ie(WIFI_EID_HT_OPERATION, ie, ie_len);
    if (ptr_ie) {
        return (struct ht_op_ie *)(ptr_ie + TLV_BODY_OFF);
    }
    return NULL;
}

struct vht_op_ie *read_vht_oper_ie(const char* ie, const s32 ie_len)
{
    const u8 *ptr_ie;
    ptr_ie = bss_get_ie(WIFI_EID_VHT_OPERATION, ie, ie_len);
    if (ptr_ie) {
        return (struct vht_op_ie *)(ptr_ie + TLV_BODY_OFF);
    }
    return NULL;
}
wifi_channel_info get_channel_of_ie(const char* ie, const s32 ie_len)
{
    struct vht_op_ie *vht_op;
    struct ht_op_ie *ht_op;
    const u8 *ptr_ie;
    wifi_channel_info chan_info;
    vht_op = read_vht_oper_ie(ie, ie_len);
    if ((vht_op = read_vht_oper_ie(ie, ie_len)) &&
            (ht_op = read_ht_oper_ie(ie, ie_len))) {
        /* VHT mode */
        if (vht_op->chan_width == VHT_OP_CHAN_WIDTH_80) {
            chan_info.width = WIFI_CHAN_WIDTH_80;
            /* primary channel */
            chan_info.center_freq = channel2mhz(ht_op->ctl_ch);
            /* center frequency */
            chan_info.center_freq0 = channel2mhz(vht_op->chan1);
			return chan_info;
		}
	}
	if (ht_op = read_ht_oper_ie(ie, ie_len)){
		/* HT mode */
		/* control channel */
		chan_info.center_freq = channel2mhz(ht_op->ctl_ch);
		chan_info.width = WIFI_CHAN_WIDTH_20;
		switch (ht_op->chan_info & DOT11_EXT_CH_MASK) {
			chan_info.center_freq = channel2mhz(ht_op->ctl_ch);
			case DOT11_EXT_CH_UPPER:
				chan_info.width = WIFI_CHAN_WIDTH_40;
				break;
			case DOT11_EXT_CH_LOWER:
				chan_info.width = WIFI_CHAN_WIDTH_40;
				break;
			case DOT11_EXT_CH_NONE:
				break;
		}
	} else {
		chan_info.width = WIFI_CHAN_WIDTH_20;
		ptr_ie = bss_get_ie(WIFI_EID_DS_PARAMS, ie, ie_len);
		if (ptr_ie) {
			chan_info.center_freq = channel2mhz(ptr_ie[TLV_BODY_OFF]);
		}
	}
	return chan_info;
}

static void testRTT()
{
    wifi_scan_result *results[max_ap];
    wifi_scan_result *scan_param;
    u32 num_ap = 0;
    /* Run by a provided rtt-ap-list file */
    FILE* w_fp = NULL;
    wifi_rtt_config params[max_ap];
    if (!rtt_from_file) {
        /* band filter for a specific band */
        if (band == WIFI_BAND_UNSPECIFIED)
            band = WIFI_BAND_ABG;
        int num_results = scanOnce(band, results, max_ap);
        if (num_results == 0) {
            printMsg("RTT aborted because of no scan results\n");
            return;
        } else {
            printMsg("Retrieved %d scan results\n", num_results);
        }

        num_results = removeDuplicateScanResults(results, num_results);

        sortScanResultsByRssi(results, num_results);
        printMsg("Sorted scan results -\n");
        for (int i = 0; i < num_results; i++) {
            printScanResult(*results[i]);
        }
        if (rtt_to_file) {
            /* Write a RTT AP list to a file */
            w_fp = fopen(rtt_aplist, "w");
            if (w_fp == NULL) {
                printMsg("failed to open the file : %s\n", rtt_aplist);
                return;
            }
            fprintf(w_fp, "|SSID|BSSID|Primary Freq|Center Freq|Channel BW(0=20MHZ,1=40MZ,2=80MHZ)"
                    "|rtt_type(1=1WAY,2=2WAY,3=auto)|Peer Type(STA=0, AP=1)|burst period|"
                    "Num of Burst|FTM retry count|FTMR retry count|LCI|LCR|Burst Duration|Preamble|BW\n");
        }
        for (int i = 0; i < min(num_results, max_ap); i++) {
            scan_param = results[i];
            if(is11mcAP(&scan_param->ie_data[0], scan_param->ie_length)) {
                memcpy(params[num_ap].addr, scan_param->bssid, sizeof(mac_addr));
                mac_addr &addr = params[num_ap].addr;
                printMsg("Adding %s(%02x:%02x:%02x:%02x:%02x:%02x) on Freq (%d) for 11mc RTT\n",
                        scan_param->ssid, addr[0], addr[1],
                        addr[2], addr[3], addr[4], addr[5],
                        scan_param->channel);
                params[num_ap].type = RTT_TYPE_2_SIDED;
                params[num_ap].channel = get_channel_of_ie(&scan_param->ie_data[0],
                        scan_param->ie_length);
                params[num_ap].peer = RTT_PEER_AP;
                params[num_ap].num_burst = default_rtt_param.num_burst;
                params[num_ap].num_frames_per_burst = default_rtt_param.num_frames_per_burst;
                params[num_ap].num_retries_per_rtt_frame =
                    default_rtt_param.num_retries_per_ftm;
                params[num_ap].num_retries_per_ftmr = default_rtt_param.num_retries_per_ftmr;
                params[num_ap].burst_period = default_rtt_param.burst_period;
                params[num_ap].burst_duration = default_rtt_param.burst_duration;
                params[num_ap].LCI_request = default_rtt_param.LCI_request;
                params[num_ap].LCR_request = default_rtt_param.LCR_request;
                params[num_ap].preamble = (wifi_rtt_preamble)default_rtt_param.preamble;
                params[num_ap].bw = (wifi_rtt_bw)default_rtt_param.bw;
                if (rtt_to_file) {
                    fprintf(w_fp, "%s %02x:%02x:%02x:%02x:%02x:%02x %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d\n", scan_param->ssid,
                            params[num_ap].addr[0], params[num_ap].addr[1], params[num_ap].addr[2], params[num_ap].addr[3],
                            params[num_ap].addr[4], params[num_ap].addr[5],params[num_ap].channel.center_freq,
                            params[num_ap].channel.center_freq0, params[num_ap].channel.width, params[num_ap].type,params[num_ap].peer,
                            params[num_ap].burst_period, params[num_ap].num_burst, params[num_ap].num_frames_per_burst,
                            params[num_ap].num_retries_per_rtt_frame, params[num_ap].num_retries_per_ftmr,
                            params[num_ap].LCI_request, params[num_ap].LCR_request, params[num_ap].burst_duration,
                            params[num_ap].preamble, params[num_ap].bw);
                }
                num_ap++;
            } else {
                /* legacy AP */
            }
        }
        /* free the results data */
        for (int i = 0; i < num_results; i++) {
            free(results[i]);
            results[i] = NULL;
        }
        if (w_fp)
            fclose(w_fp);
    } else {
        /* Run by a provided rtt-ap-list file */
        FILE* fp;
        char bssid[ETHER_ADDR_STR_LEN];
        char ssid[MAX_SSID_LEN];
        char first_char;
        memset(bssid, 0, sizeof(bssid));
        memset(ssid, 0, sizeof(ssid));
        /* Read a RTT AP list from a file */
        fp = fopen(rtt_aplist, "r");
        if (fp == NULL) {
            printMsg("\nRTT AP list file does not exist on %s.\n"
                    "Please specify correct full path or use default one, %s, \n"
                    "  by following order in file, such as:\n"
                    "|SSID|BSSID|Center Freq|Freq0|Channel BW(0=20MHZ,1=40MZ,2=80MHZ)|"
                    "RTT_Type(1=1WAY,2=2WAY,3=auto)|Peer Type(STA=0, AP=1)|Burst Period|"
                    "No of Burst|No of FTM Burst|FTM Retry Count|FTMR Retry Count|LCI|LCR|"
                    "Burst Duration|Preamble|Bandwith\n",
                    rtt_aplist, DEFAULT_RTT_FILE);
            return;
        }
        printMsg("    %-16s%-20s%-8s%-14s%-12s%-10s%-10s%-16s%-10s%-14s%-11s%-12s%-5s%-5s%-15s%-10s\n",
                "SSID", "BSSID", "c_Freq", "c_Freq0", "Bandwidth", "RTT_Type", "RTT_Peer",
                "Burst_Period", "No_Burst", "No_FTM_Burst", "FTM_Retry",
                "FTMR_Retry", "LCI", "LCR", "Burst_duration", "Preamble", "Bandwidth");
        int i = 0;
        while (!feof(fp)) {
            if ((fscanf(fp, "%c", &first_char) == 1) && (first_char != '|')) {
                fseek(fp, -1, SEEK_CUR);
                fscanf(fp, "%s %s %u %u %u %u %u %u %u %u %u %u %hhu %hhu %u %hhu %hhu\n",
                        ssid, bssid, (unsigned int*)&params[i].channel.center_freq,
                        (unsigned int*)&params[i].channel.center_freq0,
                        (unsigned int*)&params[i].channel.width,
                        (unsigned int*)&params[i].type, (unsigned int*)&params[i].peer,
                        &params[i].burst_period, &params[i].num_burst,
                        &params[i].num_frames_per_burst,
                        &params[i].num_retries_per_rtt_frame,
                        &params[i].num_retries_per_ftmr,
                        (unsigned char*)&params[i].LCI_request,
                        (unsigned char*)&params[i].LCR_request,
                        (unsigned int*)&params[i].burst_duration,
                        (unsigned char*)&params[i].preamble,
                        (unsigned char*)&params[i].bw);

                parseMacAddress(bssid, params[i].addr);

                printMsg("[%d] %-16s%-20s%-8u%-14u%-12d%-10d%-10u%-16u%-10u%-14u%-11u%-12u%-5hhu%-5hhu%-15u%-10hhu-10hhu\n",
                        i+1, ssid, bssid, params[i].channel.center_freq,
                        params[i].channel.center_freq0, params[i].channel.width,
                        params[i].type, params[i].peer, params[i].burst_period,
                        params[i].num_burst, params[i].num_frames_per_burst,
                        params[i].num_retries_per_rtt_frame,
                        params[i].num_retries_per_ftmr, params[i].LCI_request,
                        params[i].LCR_request, params[i].burst_duration, params[i].preamble, params[i].bw);

                i++;
            } else {
                /* Ignore the rest of the line. */
                fscanf(fp, "%*[^\n]");
                fscanf(fp, "\n");
            }
        }
        num_ap = i;
        fclose(fp);
        fp = NULL;
    }

    wifi_rtt_event_handler handler;
    handler.on_rtt_results = &onRTTResults;
    if (!rtt_to_file) {
        if (num_ap) {
            printMsg("Configuring RTT for %d APs\n", num_ap);
            int result = hal_fn.wifi_rtt_range_request(rttCmdId, wlan0Handle, num_ap, params, handler);
            if (result == WIFI_SUCCESS) {
                printMsg("\nWaiting for RTT results\n");
                while (true) {
                    EventInfo info;
                    memset(&info, 0, sizeof(info));
                    getEventFromCache(info);
                    if (info.type == EVENT_TYPE_RTT_RESULTS) {
                        break;
                    }
                }
            } else {
                printMsg("Could not set setRTTAPs : %d\n", result);
            }
        } else {
            printMsg("no candidate for RTT\n");
        }
    } else {
        printMsg("written AP info into file %s successfully\n", rtt_aplist);
    }
}
static int cancelRTT()
{
    int ret;
    ret = hal_fn.wifi_rtt_range_cancel(rttCmdId, wlan0Handle, 0, NULL);
    if (ret == WIFI_SUCCESS) {
        printMsg("Successfully cancelled the RTT\n");
    }
    return ret;
}
static void getRTTCapability()
{
    int ret;
    wifi_rtt_capabilities rtt_capability;
    ret = hal_fn.wifi_get_rtt_capabilities(wlan0Handle, &rtt_capability);
    if (ret == WIFI_SUCCESS) {
        printMsg("Supported Capabilites of RTT :\n");
        if (rtt_capability.rtt_one_sided_supported)
            printMsg("One side RTT is supported\n");
        if (rtt_capability.rtt_ftm_supported)
            printMsg("FTM(11mc) RTT is supported\n");
        if (rtt_capability.lci_support)
            printMsg("LCI is supported\n");
        if (rtt_capability.lcr_support)
            printMsg("LCR is supported\n");
        if (rtt_capability.bw_support) {
            printMsg("BW(%s %s %s %s) are supported\n",
                    (rtt_capability.bw_support & BW_20_SUPPORT) ? "20MHZ" : "",
                    (rtt_capability.bw_support & BW_40_SUPPORT) ? "40MHZ" : "",
                    (rtt_capability.bw_support & BW_80_SUPPORT) ? "80MHZ" : "",
                    (rtt_capability.bw_support & BW_160_SUPPORT) ? "160MHZ" : "");
        }
        if (rtt_capability.preamble_support) {
            printMsg("Preamble(%s %s %s) are supported\n",
                    (rtt_capability.preamble_support & PREAMBLE_LEGACY) ? "Legacy" : "",
                    (rtt_capability.preamble_support & PREAMBLE_HT) ? "HT" : "",
                    (rtt_capability.preamble_support & PREAMBLE_VHT) ? "VHT" : "");

        }
    } else {
        printMsg("Could not get the rtt capabilities : %d\n", ret);
    }

}

static int GetCachedGScanResults(int max, wifi_scan_result *results, int *num)
{
    int num_results = 64;
    wifi_cached_scan_results results2[64];
    memset(results2, 0, sizeof(wifi_cached_scan_results) * num_results);
    int result =hal_fn.wifi_get_cached_gscan_results(wlan0Handle, 1, num_results, results2, &num_results);
    if (result < 0) {
        printMsg("failed to fetch scan results : %d\n", result);
        return result;
    } else {
        printMsg("fetched %d scan data\n", num_results);
    }

    *num = 0;
    for (int i = 0; i < num_results; i++) {
        for (int j = 0; j < results2[i].num_results; j++, (*num)++) {
            memcpy(&(results[*num]), &(results2[i].results[j]), sizeof(wifi_scan_result));
        }
    }
    return result;
}


static wifi_error setHotlistAPsUsingScanResult(wifi_bssid_hotlist_params *params)
{
    printMsg("testHotlistAPs Scan started, waiting for event ...\n");
    EventInfo info;
    memset(&info, 0, sizeof(info));
    getEventFromCache(info);

    wifi_scan_result results[256];
    memset(results, 0, sizeof(wifi_scan_result) * 256);

    printMsg("Retrieving scan results for Hotlist AP setting\n");
    int num_results = 256;
    int result = GetCachedGScanResults(num_results, results, &num_results);
    if (result < 0) {
        printMsg("failed to fetch scan results : %d\n", result);
        return WIFI_ERROR_UNKNOWN;
    } else {
        printMsg("fetched %d scan results\n", num_results);
    }

    for (int i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }

    for (int i = 0; i < stest_max_ap; i++) {
        memcpy(params->ap[i].bssid, results[i].bssid, sizeof(mac_addr));
        params->ap[i].low  = -htest_low_threshold;
        params->ap[i].high = -htest_high_threshold;
    }
    params->num_bssid = stest_max_ap;
    return WIFI_SUCCESS;
}

static wifi_error setHotlistAPs() {
    wifi_bssid_hotlist_params params;
    memset(&params, 0, sizeof(params));

    params.lost_ap_sample_size = HOTLIST_LOST_WINDOW;
    if (num_hotlist_bssids > 0) {
        for (int i = 0; i < num_hotlist_bssids; i++) {
            memcpy(params.ap[i].bssid, hotlist_bssids[i], sizeof(mac_addr));
            params.ap[i].low  = -htest_low_threshold;
            params.ap[i].high = -htest_high_threshold;
        }
        params.num_bssid = num_hotlist_bssids;
    } else {
        setHotlistAPsUsingScanResult(&params);
    }

    printMsg("BSSID\t\t\tHIGH\tLOW\n");
    for (int i = 0; i < params.num_bssid; i++) {
        mac_addr &addr = params.ap[i].bssid;
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\t%d\t%d\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5],
                params.ap[i].high, params.ap[i].low);
    }

    wifi_hotlist_ap_found_handler handler;
    handler.on_hotlist_ap_found = &onHotlistAPFound;
    handler.on_hotlist_ap_lost = &onHotlistAPLost;
    hotlistCmdId = getNewCmdId();
    printMsg("Setting hotlist APs threshold\n");
    return hal_fn.wifi_set_bssid_hotlist(hotlistCmdId, wlan0Handle, params, handler);
}

static void resetHotlistAPs() {
    printMsg(", stoping Hotlist AP scanning\n");
    hal_fn.wifi_reset_bssid_hotlist(hotlistCmdId, wlan0Handle);
}

static void setPnoMacOui() {
    hal_fn.wifi_set_scanning_mac_oui(wlan0Handle, mac_oui);
}

static void testHotlistAPs(){

    EventInfo info;
    memset(&info, 0, sizeof(info));

    printMsg("starting Hotlist AP scanning\n");
    bool startScanResult = startScan(&onScanResultsAvailable, stest_max_ap,
            stest_base_period, stest_threshold_percent, stest_threshold_num_scans);
    if (!startScanResult) {
        printMsg("testHotlistAPs failed to start scan!!\n");
        return;
    }

    int result = setHotlistAPs();
    if (result == WIFI_SUCCESS) {
        printMsg("Waiting for Hotlist AP event\n");
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);

            if (info.type == EVENT_TYPE_SCAN_RESULTS_AVAILABLE) {
                retrieveScanResults();
            } else if (info.type == EVENT_TYPE_HOTLIST_AP_FOUND ||
                    info.type == EVENT_TYPE_HOTLIST_AP_LOST) {
                printMsg("Hotlist APs");
                if (--max_event_wait > 0)
                    printMsg(", waiting for more event ::%d\n", max_event_wait);
                else
                    break;
            }
        }
        resetHotlistAPs();
    } else {
        printMsg("Could not set AP hotlist : %d\n", result);
    }
}

static void testPNO(){

    EventInfo info;
    wifi_epno_handler handler;
    handler.on_network_found = &onePnoSsidFound;
    printMsg("configuring ePNO SSIDs num %u\n", num_epno_ssids);
    memset(&info, 0, sizeof(info));
    epnoCmdId = getNewCmdId();
    int result = WIFI_SUCCESS+1;
     if (result == WIFI_SUCCESS) {
        bool startScanResult = startScan(&onScanResultsAvailable, stest_max_ap,
            stest_base_period, stest_threshold_percent, stest_threshold_num_scans);
        if (!startScanResult) {
            printMsg("testPNO failed to start scan!!\n");
            return;
        }
        printMsg("Waiting for ePNO events\n");
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);

            if (info.type == EVENT_TYPE_SCAN_RESULTS_AVAILABLE) {
                retrieveScanResults();
            } else if (info.type == EVENT_TYPE_EPNO_SSID) {
                printMsg("FOUND ePNO event");
                if (--max_event_wait > 0)
                  printMsg(", waiting for more event ::%d\n", max_event_wait);
                else
                  break;
            }
        }
        //wifi_reset_epno_list(epnoCmdId, wlan0Handle);
    } else {
        printMsg("Could not set ePNO : %d\n", result);
    }
}

static void onSignificantWifiChange(wifi_request_id id,
        unsigned num_results, wifi_significant_change_result **results)
{
    printMsg("Significant wifi change for %d\n", num_results);
    for (unsigned i = 0; i < num_results; i++) {
        printSignificantChangeResult(results[i]);
    }
    putEventInCache(EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE, "significant wifi change noticed");
}

static int SelectSignificantAPsFromScanResults() {
    wifi_scan_result results[256];
    memset(results, 0, sizeof(wifi_scan_result) * 256);
    printMsg("Retrieving scan results for significant wifi change setting\n");
    int num_results = 256;
    int result = GetCachedGScanResults(num_results, results, &num_results);
    if (result < 0) {
        printMsg("failed to fetch scan results : %d\n", result);
        return WIFI_ERROR_UNKNOWN;
    } else {
        printMsg("fetched %d scan results\n", num_results);
    }

    for (int i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }

    wifi_significant_change_params params;
    memset(&params, 0, sizeof(params));

    params.rssi_sample_size = swctest_rssi_sample_size;
    params.lost_ap_sample_size = swctest_rssi_lost_ap;
    params.min_breaching = swctest_rssi_min_breaching;

    for (int i = 0; i < stest_max_ap; i++) {
        memcpy(params.ap[i].bssid, results[i].bssid, sizeof(mac_addr));
        params.ap[i].low  = results[i].rssi - swctest_rssi_ch_threshold;
        params.ap[i].high = results[i].rssi + swctest_rssi_ch_threshold;
    }
    params.num_bssid = stest_max_ap;

    printMsg("Settting Significant change params rssi_sample_size#%d lost_ap_sample_size#%d"
            " and min_breaching#%d\n", params.rssi_sample_size,
            params.lost_ap_sample_size , params.min_breaching);
    printMsg("BSSID\t\t\tHIGH\tLOW\n");
    for (int i = 0; i < params.num_bssid; i++) {
        mac_addr &addr = params.ap[i].bssid;
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\t%d\t%d\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5],
                params.ap[i].high, params.ap[i].low);
    }
    wifi_significant_change_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_significant_change = &onSignificantWifiChange;

    int id = getNewCmdId();
    return hal_fn.wifi_set_significant_change_handler(id, wlan0Handle, params, handler);

}

static void untrackSignificantChange() {
    printMsg(", Stop tracking SignificantChange\n");
    hal_fn.wifi_reset_bssid_hotlist(hotlistCmdId, wlan0Handle);
}

static void trackSignificantChange() {
    printMsg("starting trackSignificantChange\n");

    if (!startScan(&onScanResultsAvailable, stest_max_ap,
                stest_base_period, stest_threshold_percent, stest_threshold_num_scans)) {
        printMsg("trackSignificantChange failed to start scan!!\n");
        return;
    } else {
        printMsg("trackSignificantChange Scan started, waiting for event ...\n");
    }

    EventInfo info;
    memset(&info, 0, sizeof(info));
    getEventFromCache(info);

    int result = SelectSignificantAPsFromScanResults();
    if (result == WIFI_SUCCESS) {
        printMsg("Waiting for significant wifi change event\n");
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);

            if (info.type == EVENT_TYPE_SCAN_RESULTS_AVAILABLE) {
                retrieveScanResults();
            } else if(info.type == EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE) {
                printMsg("Received significant wifi change");
                if (--max_event_wait > 0)
                    printMsg(", waiting for more event ::%d\n", max_event_wait);
                else
                    break;
            }
        }
        untrackSignificantChange();
    } else {
        printMsg("Failed to set significant change  ::%d\n", result);
    }
}


void testScan() {
    printf("starting scan with max_ap_per_scan#%d  base_period#%d  threshold#%d \n",
            stest_max_ap,stest_base_period, stest_threshold_percent);
    if (!startScan(&onScanResultsAvailable, stest_max_ap,
                stest_base_period, stest_threshold_percent, stest_threshold_num_scans)) {
        printMsg("failed to start scan!!\n");
        return;
    } else {
        EventInfo info;
        memset(&info, 0, sizeof(info));

        while (true) {
            getEventFromCache(info);
            printMsg("retrieved event %d : %s\n", info.type, info.buf);
            retrieveScanResults();
            if(--max_event_wait > 0)
                printMsg("Waiting for more :: %d event \n", max_event_wait);
            else
                break;
        }

        stopScan();
        printMsg("stopped scan\n");
    }
}

void testStopScan() {
    stopScan();
    printMsg("stopped scan\n");
}


///////////////////////////////////////////////////////////////////////////////
// Logger feature set

static void onRingBufferData(char *ring_name, char *buffer, int buffer_size,
                                wifi_ring_buffer_status *status)
{
    // helper for LogHandler

    static int cnt = 1;
    FILE* w_fp;
    static int f_count = 0;
    char ring_file[FILE_NAME_LEN];
    char *pBuff;

    if (!buffer || buffer_size <= 0) {
        printMsg("No data in dump buffer\n");
        return;
    }

    printMsg("\n%d) RingId=%d, Name=%s, Flags=%u, DebugLevel=%u, "
            "wBytes=%u, rBytes=%u, RingSize=%u, wRecords=%u\n",
            cnt++, status->ring_id, status->name, status->flags,
            status->verbose_level, status->written_bytes,
            status->read_bytes, status->ring_buffer_byte_size,
            status->written_records);

    wifi_ring_buffer_entry *buffer_entry = (wifi_ring_buffer_entry *) buffer;

    printMsg("Format: (%d) ", buffer_entry->flags);
    if (buffer_entry->flags & RING_BUFFER_ENTRY_FLAGS_HAS_BINARY)
        printMsg("\"BINARY\" ");
    if (buffer_entry->flags & RING_BUFFER_ENTRY_FLAGS_HAS_TIMESTAMP)
        printMsg("\"TIMESTAMP\"");

    printMsg(", Type: %s (%d)", RBentryTypeToString(buffer_entry->type), buffer_entry->type);
    printMsg(", Size: %d bytes\n", buffer_entry->entry_size);

    pBuff = (char *) (buffer_entry + 1);
    sprintf(ring_file, "%s%s-%d.bin", RINGDATA_PREFIX, ring_name, f_count);
    w_fp = fopen(ring_file, "a");
    if (w_fp == NULL) {
        printMsg("Failed to open a file: %s\n", ring_file);
        return;
    }

    fwrite(pBuff, 1, buffer_entry->entry_size, w_fp);
    if (ftell(w_fp) >= FILE_MAX_SIZE) {
        f_count++;
        if (f_count >= NUM_ALERT_DUMPS)
            f_count = 0;
    }
    fclose(w_fp);
    w_fp = NULL;

    printMsg("Data: ");
    if (buffer_entry->flags & RING_BUFFER_ENTRY_FLAGS_HAS_BINARY) {
        for (int i = 0; i < buffer_size; i++)
            printMsg("%02x ", buffer[i]);
        printMsg("\n");
    } else {
        printMsg("%s\n", pBuff);
    }

    /*
     * Parsing Wake Lock event
     */
    if (buffer_entry->type == ENTRY_TYPE_WAKE_LOCK) {
        const char *strStatus[] = {"Taken", "Released", "Timeout"};
        wake_lock_event *wlock_event = (wake_lock_event *) pBuff;

        printMsg("Wakelock Event: Status=%s (%02x), Name=%s, Reason=%s (%02x)\n",
            strStatus[wlock_event->status], wlock_event->status,
            wlock_event->name, "\"TO BE\"", wlock_event->reason);
        return;
    }

    /*
     * Parsing TLV data
     */
    if (buffer_entry->type == ENTRY_TYPE_CONNECT_EVENT) {
        wifi_ring_buffer_driver_connectivity_event *connect_event =
            (wifi_ring_buffer_driver_connectivity_event *) (pBuff);

        tlv_log *tlv_data = (tlv_log *) (connect_event + 1);
        printMsg("Event type: %s (%u)\n", RBconnectEventToString(connect_event->event),
                connect_event->event);

        char *pos = (char *)tlv_data;
        char *end = (char *)connect_event + buffer_entry->entry_size;
        while (pos < end) {
            printMsg("TLV.type: %s (%d), TLV.len=%d (%02x)\n",
                    RBTlvTagToString(tlv_data->tag),
                    tlv_data->tag, tlv_data->length, tlv_data->length);

            switch (tlv_data->tag) {
                case WIFI_TAG_VENDOR_SPECIFIC:
                    break;

                case WIFI_TAG_BSSID:
                case WIFI_TAG_ADDR:
                case WIFI_TAG_ADDR1:
                case WIFI_TAG_ADDR2:
                case WIFI_TAG_ADDR3:
                case WIFI_TAG_ADDR4:
                {
                    if (tlv_data->length == sizeof(mac_addr)) {
                        mac_addr addr;
                        memcpy(&addr, tlv_data->value, sizeof(mac_addr));
                        printMsg("Address: %02x:%02x:%02x:%02x:%02x:%02x\n",
                            addr[0], addr[1], addr[2], addr[3], addr[4], addr[5]);
                    } else
                        printMsg("wrong lenght of address\n");
                    break;
                }

                case WIFI_TAG_SSID:
                {
                    char ssid[MAX_SSID_LEN];
                    memset(ssid, 0, sizeof(ssid));
                    if (tlv_data->length > MAX_SSID_LEN)
                        tlv_data->length = MAX_SSID_LEN;
                    memcpy(ssid, tlv_data->value, tlv_data->length);
                    printMsg("SSID = %s\n", ssid);
                    break;
                }

                case WIFI_TAG_STATUS:
                {
                    unsigned int status = 0;
                    memcpy(&status, tlv_data->value, tlv_data->length);
                    printMsg("Status = %u\n", status);
                    break;
                }

                case WIFI_TAG_CHANNEL_SPEC:
                {
                    wifi_channel_info *ch_spec = (wifi_channel_info *) tlv_data->value;
                    printMsg("Channel Info: center_freq=%d, freq0=%d, freq1=%d, width=%s (%d)\n",
                        RBchanWidthToString(ch_spec->width), ch_spec->center_freq,
                        ch_spec->center_freq0, ch_spec->center_freq1);
                    break;
                }

                case WIFI_TAG_WAKE_LOCK_EVENT:
                {
                    printMsg("Wake lock event = \"TO BE DONE LATER\"\n", tlv_data->value);
                    break;
                }

                case WIFI_TAG_TSF:
                {
                    u64 tsf = 0;
                    memcpy(&tsf, tlv_data->value, tlv_data->length);
                    printMsg("TSF value = %d\n", tsf);
                    break;
                }

                case WIFI_TAG_IE:
                {
                    printMsg("Information Element = \"TO BE\"\n");
                    break;
                }

                case WIFI_TAG_INTERFACE:
                {
                    const int len = 32;
                    char inf_name[len];

                    if (tlv_data->length > len)
                        tlv_data->length = len;
                    memset(inf_name, 0, 32);
                    memcpy(inf_name, tlv_data->value, tlv_data->length);
                    printMsg("Interface = %s\n", inf_name);
                    break;
                }

                case WIFI_TAG_REASON_CODE:
                {
                    u16 reason = 0;
                    memcpy(&reason, tlv_data->value, 2);
                    printMsg("Reason code = %d\n", reason);
                    break;
                }

                case WIFI_TAG_RATE_MBPS:
                {
                    u32 rate = 0;
                    memcpy(&rate, tlv_data->value, tlv_data->length);
                    printMsg("Rate = %.1f Mbps\n", rate * 0.5);    // rate unit is 500 Kbps.
                    break;
                }
            }
            pos = (char *)(tlv_data + 1);
            pos += tlv_data->length;
            tlv_data = (tlv_log *) pos;
        }
    }
}

static void onAlert(wifi_request_id id, char *buffer, int buffer_size, int err_code)
{

    // helper for AlertHandler

    printMsg("Getting FW Memory dump: (%d bytes), err code: %d\n", buffer_size, err_code);

    FILE* w_fp = NULL;
    static int f_count = 0;
    char dump_file[FILE_NAME_LEN];

    if (!buffer || buffer_size <= 0) {
        printMsg("No data in alert buffer\n");
        return;
    }

    sprintf(dump_file, "%s-%d.bin", ALERT_MEMDUMP_PREFIX, f_count++);
    if (f_count >= NUM_ALERT_DUMPS)
        f_count = 0;

    w_fp = fopen(dump_file, "w");
    if (w_fp == NULL) {
        printMsg("Failed to create a file: %s\n", dump_file);
        return;
    }

    printMsg("Write to \"%s\"\n", dump_file);
    fwrite(buffer, 1, buffer_size, w_fp);
    fclose(w_fp);
    w_fp = NULL;

#if 0
    for (int i = 0; i < buffer_size; i++)
        printMsg("%02x ", buffer[i]);
    printMsg("\n");
#endif
}

static void onFirmwareMemoryDump(char *buffer, int buffer_size)
{
    // helper for LoggerGetMemdump()

    printMsg("Getting FW Memory dump: (%d bytes)\n", buffer_size);

    // Write a raw dump data into default local file or specified name
    FILE* w_fp = NULL;

    if (!buffer || buffer_size <= 0) {
        printMsg("No data in dump buffer\n");
        return;
    }

    w_fp = fopen(mem_dump_file, "w");
    if (w_fp == NULL) {
        printMsg("Failed to create a file: %s\n", mem_dump_file);
        return;
    }

    printMsg("Write to \"%s\"\n", mem_dump_file);
    fwrite(buffer, 1, buffer_size, w_fp);
    fclose(w_fp);
    w_fp = NULL;

    putEventInCache(EVENT_TYPE_LOGGER_MEMDUMP_DATA, "Memdump data");
}

static wifi_error LoggerStart()
{
    int ret;

    ret = hal_fn.wifi_start_logging(wlan0Handle,
        default_logger_param.verbose_level, default_logger_param.flags,
        default_logger_param.max_interval_sec, default_logger_param.min_data_size,
        default_logger_param.ring_name);

    if (ret != WIFI_SUCCESS) {
        printMsg("Failed to start Logger: %d\n", ret);
        return WIFI_ERROR_UNKNOWN;
    }

    /*
     * debug mode (0) which means no more debug events will be triggered.
     *
     * Hopefully, need to extend this functionality by additional interfaces such as
     * set verbose level to each ring buffer.
     */
    return WIFI_SUCCESS;
}

static wifi_error LoggerGetMemdump()
{
    wifi_firmware_memory_dump_handler handler;
    handler.on_firmware_memory_dump = &onFirmwareMemoryDump;

    printMsg("Create Memdump event\n");
    int result = hal_fn.wifi_get_firmware_memory_dump(wlan0Handle, handler);

    if (result == WIFI_SUCCESS) {
        EventInfo info;
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);
            if (info.type == EVENT_TYPE_LOGGER_MEMDUMP_DATA)
                break;
            else
                printMsg("Could not get memdump data: %d\n", result);
        }
    }
    return WIFI_SUCCESS;
}

static wifi_error LoggerGetRingData()
{
    int result = hal_fn.wifi_get_ring_data(wlan0Handle, default_ring_name);

    if (result == WIFI_SUCCESS)
        printMsg("Get Ring data command success\n");
    else
        printMsg("Failed to execute get ring data command\n");

    return WIFI_SUCCESS;
}

static wifi_error LoggerGetFW()
{
    int ret;
    const int BSIZE = 256;
    int buffer_size = BSIZE;

    char buffer[BSIZE];
    memset(buffer, 0, BSIZE);

    ret = hal_fn.wifi_get_firmware_version(wlan0Handle, buffer, buffer_size);

    if (ret == WIFI_SUCCESS)
        printMsg("FW version (len=%d):\n%s\n", strlen(buffer), buffer);
    else
        printMsg("Failed to get FW version\n");

    return WIFI_SUCCESS;
}

static wifi_error LoggerGetDriver()
{
    int ret;
    const int BSIZE = 256;
    int buffer_size = BSIZE;

    char buffer[BSIZE];
    memset(buffer, 0, BSIZE);

    ret = hal_fn.wifi_get_driver_version(wlan0Handle, buffer, buffer_size);

    if (ret == WIFI_SUCCESS)
        printMsg("Driver version (len=%d):\n%s\n", strlen(buffer), buffer);
    else
        printMsg("Failed to get driver version\n");

    return WIFI_SUCCESS;
}

static wifi_error LoggerGetRingbufferStatus()
{
    int ret;
    const int NRING = 10;
    u32 num_rings = NRING;

    wifi_ring_buffer_status *status =
        (wifi_ring_buffer_status *)malloc(sizeof(wifi_ring_buffer_status) * num_rings);

    if (status == NULL)
        return WIFI_ERROR_OUT_OF_MEMORY;
    memset(status, 0, sizeof(wifi_ring_buffer_status) * num_rings);

    ret = hal_fn.wifi_get_ring_buffers_status(wlan0Handle, &num_rings, status);

    if (ret == WIFI_SUCCESS) {
        printMsg("RingBuffer status: [%d ring(s)]\n", num_rings);

        for (unsigned int i=0; i < num_rings; i++) {
            printMsg("[%d] RingId=%d, Name=%s, Flags=%u, DebugLevel=%u, "
                    "wBytes=%u, rBytes=%u, RingSize=%u, wRecords=%u, status_addr=%p\n",
                    i+1,
                    status->ring_id,
                    status->name,
                    status->flags,
                    status->verbose_level,
                    status->written_bytes,
                    status->read_bytes,
                    status->ring_buffer_byte_size,
                    status->written_records, status);
            status++;
        }
    } else {
        printMsg("Failed to get Ringbuffer status\n");
    }

    free(status);
    status = NULL;

    return WIFI_SUCCESS;
}

static wifi_error LoggerGetFeature()
{
    int ret;
    unsigned int support = 0;

    const char *mapFeatures[] = {
        "MEMORY_DUMP",
        "PER_PACKET_TX_RX_STATUS",
        "CONNECT_EVENT",
        "POWER_EVENT",
        "WAKE_LOCK",
        "VERBOSE",
        "WATCHDOG_TIMER"
    };

    ret = hal_fn.wifi_get_logger_supported_feature_set(wlan0Handle, &support);

    if (ret == WIFI_SUCCESS) {
        printMsg("Logger supported features: %02x  [", support);

        if (support & WIFI_LOGGER_MEMORY_DUMP_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[0]);
        if (support & WIFI_LOGGER_PER_PACKET_TX_RX_STATUS_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[1]);
        if (support & WIFI_LOGGER_CONNECT_EVENT_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[2]);
        if (support & WIFI_LOGGER_POWER_EVENT_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[3]);
        if (support & WIFI_LOGGER_WAKE_LOCK_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[4]);
        if (support & WIFI_LOGGER_VERBOSE_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[5]);
        if (support & WIFI_LOGGER_WATCHDOG_TIMER_SUPPORTED)
            printMsg(" \"%s\" ", mapFeatures[6]);
        printMsg("]\n");
    } else {
        printMsg("Failed to get Logger supported features\n");
    }

    return WIFI_SUCCESS;
}

static wifi_error LoggerSetLogHandler()
{
    wifi_ring_buffer_data_handler handler;
    handler.on_ring_buffer_data = &onRingBufferData;

    printMsg("Setting log handler\n");
    int result = hal_fn.wifi_set_log_handler(loggerCmdId, wlan0Handle, handler);

    if (result == WIFI_SUCCESS) {
        EventInfo info;
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);
            if (info.type == EVENT_TYPE_LOGGER_RINGBUFFER_DATA)
                break;
        }
    } else {
        printMsg("Failed set Log handler: %d\n", result);
    }
    return WIFI_SUCCESS;
}

static wifi_error LoggerSetAlertHandler()
{
    loggerCmdId = getNewCmdId();
    wifi_alert_handler handler;
    handler.on_alert = &onAlert;

    printMsg("Create alert handler\n");
    int result = hal_fn.wifi_set_alert_handler(loggerCmdId, wlan0Handle, handler);

    if (result == WIFI_SUCCESS) {
        EventInfo info;
        while (true) {
            memset(&info, 0, sizeof(info));
            getEventFromCache(info);
            if (info.type == EVENT_TYPE_LOGGER_ALERT_DATA)
                break;
        }
    } else {
        printMsg("Failed set Alert handler: %d\n", result);
    }
    return WIFI_SUCCESS;
}

static void runLogger()
{
    switch (log_cmd) {
        case LOG_GET_FW_VER:
            LoggerGetFW();
            break;
        case LOG_GET_DRV_VER:
            LoggerGetDriver();
            break;
        case LOG_GET_RING_STATUS:
            LoggerGetRingbufferStatus();
            break;
        case LOG_GET_FEATURE:
            LoggerGetFeature();
            break;
        case LOG_GET_MEMDUMP:
            LoggerGetMemdump();
            break;
        case LOG_GET_RING_DATA:
            LoggerGetRingData();
            break;
        case LOG_START:
            LoggerStart();
            break;
        case LOG_SET_LOG_HANDLER:
            LoggerSetLogHandler();
            break;
        case LOG_SET_ALERT_HANDLER:
            LoggerSetAlertHandler();
            break;
        default:
            break;
    }
}

byte parseHexChar(char ch) {
    if (isdigit(ch))
        return ch - '0';
    else if ('A' <= ch && ch <= 'F')
        return ch - 'A' + 10;
    else if ('a' <= ch && ch <= 'f')
        return ch - 'a' + 10;
    else {
        printMsg("invalid character in bssid %c\n", ch);
        return 0;
    }
}

byte parseHexByte(char ch1, char ch2) {
    return (parseHexChar(ch1) << 4) | parseHexChar(ch2);
}

void parseMacAddress(const char *str, mac_addr addr) {
    addr[0] = parseHexByte(str[0], str[1]);
    addr[1] = parseHexByte(str[3], str[4]);
    addr[2] = parseHexByte(str[6], str[7]);
    addr[3] = parseHexByte(str[9], str[10]);
    addr[4] = parseHexByte(str[12], str[13]);
    addr[5] = parseHexByte(str[15], str[16]);
}

void parseMacOUI(char *str, unsigned char *addr) {
    addr[0] = parseHexByte(str[0], str[1]);
    addr[1] = parseHexByte(str[3], str[4]);
    addr[2] = parseHexByte(str[6], str[7]);
    printMsg("read mac OUI: %02x:%02x:%02x\n", addr[0],
            addr[1], addr[2]);
}

void readTestOptions(int argc, char *argv[]) {

    printf("Total number of argc #%d\n", argc);
    for (int j = 1; j < argc-1; j++) {
        if (strcmp(argv[j], "-max_ap") == 0 && isdigit(argv[j+1][0])) {
            stest_max_ap = atoi(argv[++j]);
            printf(" max_ap #%d\n", stest_max_ap);
        } else if (strcmp(argv[j], "-base_period") == 0 && isdigit(argv[j+1][0])) {
            stest_base_period = atoi(argv[++j]);
            printf(" base_period #%d\n", stest_base_period);
        } else if (strcmp(argv[j], "-threshold") == 0 && isdigit(argv[j+1][0])) {
            stest_threshold_percent = atoi(argv[++j]);
            printf(" threshold #%d\n", stest_threshold_percent);
        } else if (strcmp(argv[j], "-avg_RSSI") == 0 && isdigit(argv[j+1][0])) {
            swctest_rssi_sample_size = atoi(argv[++j]);
            printf(" avg_RSSI #%d\n", swctest_rssi_sample_size);
        } else if (strcmp(argv[j], "-ap_loss") == 0 && isdigit(argv[j+1][0])) {
            swctest_rssi_lost_ap = atoi(argv[++j]);
            printf(" ap_loss #%d\n", swctest_rssi_lost_ap);
        } else if (strcmp(argv[j], "-ap_breach") == 0 && isdigit(argv[j+1][0])) {
            swctest_rssi_min_breaching = atoi(argv[++j]);
            printf(" ap_breach #%d\n", swctest_rssi_min_breaching);
        } else if (strcmp(argv[j], "-ch_threshold") == 0 && isdigit(argv[j+1][0])) {
            swctest_rssi_ch_threshold = atoi(argv[++j]);
            printf(" ch_threshold #%d\n", swctest_rssi_ch_threshold);
        } else if (strcmp(argv[j], "-wt_event") == 0 && isdigit(argv[j+1][0])) {
            max_event_wait = atoi(argv[++j]);
            printf(" wt_event #%d\n", max_event_wait);
        } else if (strcmp(argv[j], "-low_th") == 0 && isdigit(argv[j+1][0])) {
            htest_low_threshold = atoi(argv[++j]);
            printf(" low_threshold #-%d\n", htest_low_threshold);
        } else if (strcmp(argv[j], "-high_th") == 0 && isdigit(argv[j+1][0])) {
            htest_high_threshold = atoi(argv[++j]);
            printf(" high_threshold #-%d\n", htest_high_threshold);
        } else if (strcmp(argv[j], "-hotlist_bssids") == 0 && isxdigit(argv[j+1][0])) {
            j++;
            for (num_hotlist_bssids = 0;
                    j < argc && isxdigit(argv[j][0]);
                    j++, num_hotlist_bssids++) {
                parseMacAddress(argv[j], hotlist_bssids[num_hotlist_bssids]);
            }
            j -= 1;
        } else if (strcmp(argv[j], "-channel_list") == 0 && isxdigit(argv[j+1][0])) {
            j++;
            for (num_channels = 0; j < argc && isxdigit(argv[j][0]); j++, num_channels++) {
                channel_list[num_channels] = atoi(argv[j]);
            }
            j -= 1;
        } else if ((strcmp(argv[j], "-get_ch_list") == 0)) {
            if(strcmp(argv[j + 1], "a") == 0) {
                band = WIFI_BAND_A_WITH_DFS;
            } else if(strcmp(argv[j + 1], "bg") == 0) {
                band = WIFI_BAND_BG;
            } else if(strcmp(argv[j + 1], "abg") == 0) {
                band = WIFI_BAND_ABG_WITH_DFS;
            } else if(strcmp(argv[j + 1], "a_nodfs") == 0) {
                band = WIFI_BAND_A;
            } else if(strcmp(argv[j + 1], "dfs") == 0) {
                band = WIFI_BAND_A_DFS;
            } else if(strcmp(argv[j + 1], "abg_nodfs") == 0) {
                band = WIFI_BAND_ABG;
            }
            j++;
        } else if (strcmp(argv[j], "-scan_mac_oui") == 0 && isxdigit(argv[j+1][0])) {
            parseMacOUI(argv[++j], mac_oui);
        } else if ((strcmp(argv[j], "-ssid") == 0)) {
            num_epno_ssids++;
            if (num_epno_ssids < 32) {
                memcpy(epno_ssid[num_epno_ssids].ssid, argv[j + 1], strlen(argv[j + 1]));
                printf(" SSID %s\n", epno_ssid[num_epno_ssids].ssid);
                j++;
            }
        } else if ((strcmp(argv[j], "-auth") == 0)) {
            if (num_epno_ssids < 32) {
               epno_ssid[num_epno_ssids].auth_bit_field = atoi(argv[++j]);
               printf(" auth %d\n", epno_ssid[num_epno_ssids].auth_bit_field);
            }

        } else if ((strcmp(argv[j], "-rssi") == 0) && isdigit(argv[j+1][0])) {
            if (num_epno_ssids < 32) {
               epno_ssid[num_epno_ssids].rssi_threshold = atoi(argv[++j]) * -1;
               printf(" rssi thresh %d\n", epno_ssid[num_epno_ssids].rssi_threshold);

            }
        } else if ((strcmp(argv[j], "-hidden") == 0)) {
            if (num_epno_ssids < 32) {
               epno_ssid[num_epno_ssids].flags |= atoi(argv[++j]) ? EPNO_HIDDEN: 0;
               printf(" flags %d\n", epno_ssid[num_epno_ssids].flags);
            }
        } else if ((strcmp(argv[j], "-trig") == 0)) {
            if (num_epno_ssids < 32) {
                if ((strcmp(argv[j + 1], "a") == 0)) {
                   epno_ssid[num_epno_ssids].flags |= EPNO_A_BAND_TRIG;
                } else if ((strcmp(argv[j + 1], "bg") == 0)) {
                   epno_ssid[num_epno_ssids].flags |= EPNO_BG_BAND_TRIG;
                } else if ((strcmp(argv[j + 1], "abg") == 0)) {
                   epno_ssid[num_epno_ssids].flags |= EPNO_ABG_BAND_TRIG;
                }
               printf(" flags %d\n", epno_ssid[num_epno_ssids].flags);
            }
            j++;
        } else if (strcmp(argv[j], "-whitelist_ssids") == 0) {
            j++;
            for (num_whitelist_ssids = 0;
                        j < argc && num_whitelist_ssids < 16 && (argv[j][0] != '-');
                        j++, num_whitelist_ssids++) {
                strncpy(whitelist_ssids[num_whitelist_ssids], argv[j], strlen(argv[j]));
                whitelist_ssids[num_whitelist_ssids][strlen(argv[j])] = '\0';
            }
            j -= 1;
        } else if (strcmp(argv[j], "-a_boost_th") == 0 && isdigit(argv[j+1][0])) {
            A_band_boost_threshold = atoi(argv[++j]);
            printf(" A_band_boost_threshold #-%d\n", A_band_boost_threshold);
        } else if (strcmp(argv[j], "-a_penalty_th") == 0 && isdigit(argv[j+1][0])) {
            A_band_penalty_threshold = atoi(argv[++j]);
            printf(" A_band_penalty_threshold #-%d\n", A_band_penalty_threshold);
        } else if (strcmp(argv[j], "-a_boost_factor") == 0 && isdigit(argv[j+1][0])) {
            A_band_boost_factor = atoi(argv[++j]);
            printf(" A_band_boost_factor #%d\n", A_band_boost_factor);
        } else if (strcmp(argv[j], "-a_penalty_factor") == 0 && isdigit(argv[j+1][0])) {
            A_band_penalty_factor = atoi(argv[++j]);
            printf(" A_band_penalty_factor #%d\n", A_band_penalty_factor);
        } else if (strcmp(argv[j], "-max_boost") == 0 && isdigit(argv[j+1][0])) {
            A_band_max_boost = atoi(argv[++j]);
            printf(" A_band_max_boost #%d\n", A_band_max_boost);
        } else if (strcmp(argv[j], "-hysteresis") == 0 && isdigit(argv[j+1][0])) {
            lazy_roam_hysteresis = atoi(argv[++j]);
            printf(" lazy_roam_hysteresiss #%d\n", lazy_roam_hysteresis);
        } else if (strcmp(argv[j], "-alert_trigger") == 0 && isdigit(argv[j+1][0])) {
            alert_roam_rssi_trigger = atoi(argv[++j]);
            printf(" alert_roam_rssi_trigger #%d\n", alert_roam_rssi_trigger);
        } else if (strcmp(argv[j], "-lazy_roam") == 0 && isdigit(argv[j+1][0])) {
            lazy_roam = atoi(argv[++j]);
            printf(" lazy_roam #%d\n", lazy_roam);
        } else if (strcmp(argv[j], "-pref_bssid") == 0 && isxdigit(argv[j+1][0])) {
            j++;
            for (num_pref_bssids = 0; j < argc-1 && isxdigit(argv[j][0]); j++,
                 num_pref_bssids++) {
                parseMacAddress(argv[j], pref_bssids[num_pref_bssids]);
                rssi_modifier[num_pref_bssids] = atoi(argv[++j]);
                printf(" rssi_modifier #%d\n", rssi_modifier[num_pref_bssids]);
            }
            j -= 1;
        } else if (strcmp(argv[j], "-blacklist_bssids") == 0 && isxdigit(argv[j+1][0])) {
            j++;
            for (num_blacklist_bssids = 0;
                    j < argc && isxdigit(argv[j][0]) && num_whitelist_ssids < 16;
                    j++, num_blacklist_bssids++) {
                parseMacAddress(argv[j], blacklist_bssids[num_blacklist_bssids]);
            }
            j -= 1;
        }
    }
}
void readRTTOptions(int argc, char *argv[]) {
    for (int j = 1; j < argc-1; j++) {
        if ((strcmp(argv[j], "-get_ch_list") == 0)) {
            if(strcmp(argv[j + 1], "a") == 0) {
                band = WIFI_BAND_A_WITH_DFS;
            } else if(strcmp(argv[j + 1], "bg") == 0) {
                band = WIFI_BAND_BG;
            } else if(strcmp(argv[j + 1], "abg") == 0) {
                band = WIFI_BAND_ABG_WITH_DFS;
            } else if(strcmp(argv[j + 1], "a_nodfs") == 0) {
                band = WIFI_BAND_A;
            } else if(strcmp(argv[j + 1], "dfs") == 0) {
                band = WIFI_BAND_A_DFS;
            } else if(strcmp(argv[j + 1], "abg_nodfs") == 0) {
                band = WIFI_BAND_ABG;
            }
            j++;
        } else if ((strcmp(argv[j], "-l") == 0)) {
            /*
             * If this option is specified but there is no file name,
             * use a default file from rtt_aplist.
             */
            if (++j != argc-1)
                strcpy(rtt_aplist, argv[j]);
            rtt_from_file = 1;
        } else if ((strcmp(argv[j], "-n") == 0) && isdigit(argv[j+1][0])) {
            default_rtt_param.num_burst = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-f") == 0) && isdigit(argv[j+1][0])) {
            default_rtt_param.num_frames_per_burst = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-r") == 0) && isdigit(argv[j+1][0])) {
            default_rtt_param.num_retries_per_ftm = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-m") == 0) && isdigit(argv[j+1][0])) {
            default_rtt_param.num_retries_per_ftmr = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-b") == 0) && isdigit(argv[j+1][0])) {
            default_rtt_param.burst_duration = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-max_ap") == 0) && isdigit(argv[j+1][0])) {
            max_ap = atoi(argv[++j]);
        } else if ((strcmp(argv[j], "-o") == 0)) {
            /*
             * If this option is specified but there is no file name,
             * use a default file from rtt_aplist.
             */
            if (++j != argc-1)
                strcpy(rtt_aplist, argv[j]);
            rtt_to_file = 1;
        }
    }
}

void readLoggerOptions(int argc, char *argv[])
{
    void printUsage();          // declaration for below printUsage()
    int j = 1;

    if (argc < 3) {
        printUsage();
        return;
    }

    if ((strcmp(argv[j], "-start") == 0) && (argc == 13)) {
        log_cmd = LOG_START;
        memset(&default_logger_param, 0, sizeof(default_logger_param));

        j++;
        if ((strcmp(argv[j], "-d") == 0) && isdigit(argv[j+1][0]))
            default_logger_param.verbose_level = (unsigned int)atoi(argv[++j]);
        if ((strcmp(argv[++j], "-f") == 0) && isdigit(argv[j+1][0]))
            default_logger_param.flags = atoi(argv[++j]);
        if ((strcmp(argv[++j], "-i") == 0) && isdigit(argv[j+1][0]))
            default_logger_param.max_interval_sec = atoi(argv[++j]);
        if ((strcmp(argv[++j], "-s") == 0) && isdigit(argv[j+1][0]))
            default_logger_param.min_data_size = atoi(argv[++j]);
        if ((strcmp(argv[++j], "-n") == 0))
            memcpy(default_logger_param.ring_name, argv[j+1], strlen(argv[j+1]));
        return;
    } else if ((strcmp(argv[j], "-get") == 0) && (argc > 3)) {
        if ((strcmp(argv[j+1], "fw") == 0)) {
            log_cmd = LOG_GET_FW_VER;
        } else if ((strcmp(argv[j+1], "driver") == 0)) {
            log_cmd = LOG_GET_DRV_VER;
        } else if ((strcmp(argv[j+1], "memdump") == 0)) {
            log_cmd = LOG_GET_MEMDUMP;
            j++;
            if ((j+1 < argc-1) && (strcmp(argv[j+1], "-o") == 0)) {
                // If this option is specified but there is no file name,
                // use a default file from DEFAULT_MEMDUMP_FILE.
                j++;
                if (j+1 < argc-1)
                    strcpy(mem_dump_file, argv[j+1]);
            }
        } else if ((strcmp(argv[j+1], "ringstatus") == 0)) {
            log_cmd = LOG_GET_RING_STATUS;
        } else if ((strcmp(argv[j+1], "feature") == 0)) {
            log_cmd = LOG_GET_FEATURE;
        } else if ((strcmp(argv[j+1], "ringdata") == 0)) {
            log_cmd = LOG_GET_RING_DATA;
            j+=2;
            if ((strcmp(argv[j], "-n") == 0))
                memcpy(default_ring_name, argv[j+1], strlen(argv[j+1]));
        } else {
            printf("\nUse correct logger option:\n");
            printUsage();
        }
        return;
    } else if ((strcmp(argv[j], "-set") == 0) && (argc > 3)) {
        if ((strcmp(argv[j+1], "loghandler") == 0)) {
            log_cmd = LOG_SET_LOG_HANDLER;
        } else if ((strcmp(argv[j+1], "alerthandler") == 0)) {
            log_cmd = LOG_SET_ALERT_HANDLER;
        }
    } else {
        printf("\nUse correct logger option:\n");
        printUsage();

        return;
    }
}

wifi_iface_stat link_stat;
wifi_radio_stat trx_stat;
wifi_peer_info peer_info[32];
wifi_rate_stat rate_stat[32];
void onLinkStatsResults(wifi_request_id id, wifi_iface_stat *iface_stat,
        int num_radios, wifi_radio_stat *radio_stat)
{
    int num_peer = iface_stat->num_peers;
    printf("onLinkStatsResults num_peers = %d radio_stats %p \n", num_peer, radio_stat);
    memcpy(&trx_stat, radio_stat, sizeof(wifi_radio_stat));
    memcpy(&link_stat, iface_stat, sizeof(wifi_iface_stat));

    memcpy(peer_info, iface_stat->peer_info, num_peer*sizeof(wifi_peer_info));
    int num_rate = peer_info[0].num_rate;
    printMsg("onLinkStatsResults num_rate = %d \n", num_rate);

    memcpy(&rate_stat, iface_stat->peer_info->rate_stats, num_rate*sizeof(wifi_rate_stat));
}

void printFeatureListBitMask(void)
{
    printMsg("WIFI_FEATURE_INFRA              0x0001      - Basic infrastructure mode\n");
    printMsg("WIFI_FEATURE_INFRA_5G           0x0002      - Support for 5 GHz Band\n");
    printMsg("WIFI_FEATURE_HOTSPOT            0x0004      - Support for GAS/ANQP\n");
    printMsg("WIFI_FEATURE_P2P                0x0008      - Wifi-Direct\n");
    printMsg("WIFI_FEATURE_SOFT_AP            0x0010      - Soft AP\n");
    printMsg("WIFI_FEATURE_GSCAN              0x0020      - Google-Scan APIs\n");
    printMsg("WIFI_FEATURE_NAN                0x0040      - Neighbor Awareness Networking\n");
    printMsg("WIFI_FEATURE_D2D_RTT            0x0080      - Device-to-device RTT\n");
    printMsg("WIFI_FEATURE_D2AP_RTT           0x0100      - Device-to-AP RTT\n");
    printMsg("WIFI_FEATURE_BATCH_SCAN         0x0200      - Batched Scan (legacy)\n");
    printMsg("WIFI_FEATURE_PNO                0x0400      - Preferred network offload\n");
    printMsg("WIFI_FEATURE_ADDITIONAL_STA     0x0800      - Support for two STAs\n");
    printMsg("WIFI_FEATURE_TDLS               0x1000      - Tunnel directed link setup\n");
    printMsg("WIFI_FEATURE_TDLS_OFFCHANNEL    0x2000      - Support for TDLS off channel\n");
    printMsg("WIFI_FEATURE_EPR                0x4000      - Enhanced power reporting\n");
    printMsg("WIFI_FEATURE_AP_STA             0x8000      - Support for AP STA Concurrency\n");
}

const char *rates[] = {
    "1Mbps",
    "2Mbps",
    "5.5Mbps",
    "6Mbps",
    "9Mbps",
    "11Mbps",
    "12Mbps",
    "18Mbps",
    "24Mbps",
    "36Mbps",
    "48Mbps",
    "54Mbps",
    "VHT MCS0 ss1",
    "VHT MCS1 ss1",
    "VHT MCS2 ss1",
    "VHT MCS3 ss1",
    "VHT MCS4 ss1",
    "VHT MCS5 ss1",
    "VHT MCS6 ss1",
    "VHT MCS7 ss1",
    "VHT MCS8 ss1",
    "VHT MCS9 ss1",
    "VHT MCS0 ss2",
    "VHT MCS1 ss2",
    "VHT MCS2 ss2",
    "VHT MCS3 ss2",
    "VHT MCS4 ss2",
    "VHT MCS5 ss2",
    "VHT MCS6 ss2",
    "VHT MCS7 ss2",
    "VHT MCS8 ss2",
    "VHT MCS9 ss2"
};

void printLinkStats(wifi_iface_stat link_stat, wifi_radio_stat trx_stat)
{
    printMsg("Printing link layer statistics:\n");
    printMsg("-------------------------------\n");
    printMsg("beacon_rx = %d\n", link_stat.beacon_rx);
    printMsg("RSSI = %d\n", link_stat.rssi_mgmt);
    printMsg("AC_BE:\n");
    printMsg("txmpdu = %d\n", link_stat.ac[WIFI_AC_BE].tx_mpdu);
    printMsg("rxmpdu = %d\n", link_stat.ac[WIFI_AC_BE].rx_mpdu);
    printMsg("mpdu_lost = %d\n", link_stat.ac[WIFI_AC_BE].mpdu_lost);
    printMsg("retries = %d\n", link_stat.ac[WIFI_AC_BE].retries);
    printMsg("AC_BK:\n");
    printMsg("txmpdu = %d\n", link_stat.ac[WIFI_AC_BK].tx_mpdu);
    printMsg("rxmpdu = %d\n", link_stat.ac[WIFI_AC_BK].rx_mpdu);
    printMsg("mpdu_lost = %d\n", link_stat.ac[WIFI_AC_BK].mpdu_lost);
    printMsg("AC_VI:\n");
    printMsg("txmpdu = %d\n", link_stat.ac[WIFI_AC_VI].tx_mpdu);
    printMsg("rxmpdu = %d\n", link_stat.ac[WIFI_AC_VI].rx_mpdu);
    printMsg("mpdu_lost = %d\n", link_stat.ac[WIFI_AC_VI].mpdu_lost);
    printMsg("AC_VO:\n");
    printMsg("txmpdu = %d\n", link_stat.ac[WIFI_AC_VO].tx_mpdu);
    printMsg("rxmpdu = %d\n", link_stat.ac[WIFI_AC_VO].rx_mpdu);
    printMsg("mpdu_lost = %d\n", link_stat.ac[WIFI_AC_VO].mpdu_lost);
    printMsg("\n");
    printMsg("Printing radio statistics:\n");
    printMsg("--------------------------\n");
    printMsg("on time = %d\n", trx_stat.on_time);
    printMsg("tx time = %d\n", trx_stat.tx_time);
    printMsg("rx time = %d\n", trx_stat.rx_time);
    printMsg("\n");
    printMsg("Printing rate statistics:\n");
    printMsg("-------------------------\n");
    printMsg("%27s %12s %14s %15s\n", "TX",  "RX", "LOST", "RETRIES");
    for (int i=0; i < 32; i++) {
        printMsg("%-15s  %10d   %10d    %10d    %10d\n",
                rates[i], rate_stat[i].tx_mpdu, rate_stat[i].rx_mpdu,
                rate_stat[i].mpdu_lost, rate_stat[i].retries);
    }
}

void getLinkStats(void)
{
    wifi_stats_result_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_link_stats_results = &onLinkStatsResults;

    int result = hal_fn.wifi_get_link_stats(0, wlan0Handle, handler);
    if (result < 0) {
        printMsg("failed to get link statistics - %d\n", result);
    } else {
        printLinkStats(link_stat, trx_stat);
    }
}

void getChannelList(void)
{
    wifi_channel channel[MAX_CH_BUF_SIZE];
    int num_channels = 0, i;

    int result = hal_fn.wifi_get_valid_channels(wlan0Handle, band, MAX_CH_BUF_SIZE,
            channel, &num_channels);
    printMsg("Number of channels - %d\nChannel List:\n",num_channels);
    for (i = 0; i < num_channels; i++) {
        printMsg("%d MHz\n", channel[i]);
    }
}

void getFeatureSet(void)
{
    feature_set set;
    int result = hal_fn.wifi_get_supported_feature_set(wlan0Handle, &set);

    if (result < 0) {
        printMsg("Error %d\n",result);
        return;
    }
    printFeatureListBitMask();
    printMsg("Supported feature set bit mask - %x\n", set);
    return;
}

void getFeatureSetMatrix(void)
{
    feature_set set[MAX_FEATURE_SET];
    int size;

    int result = hal_fn.wifi_get_concurrency_matrix(wlan0Handle, MAX_FEATURE_SET, set, &size);

    if (result < 0) {
        printMsg("Error %d\n",result);
        return;
    }
    printFeatureListBitMask();
    for (int i = 0; i < size; i++)
        printMsg("Concurrent feature set - %x\n", set[i]);
    return;
}

static wifi_error setWhitelistBSSIDs()
{
    wifi_ssid params[16];
    memset(&params, 0, sizeof(params));
    int cmdId;

    if (num_whitelist_ssids == -1)
        return WIFI_SUCCESS;

    for (int i = 0; i < num_whitelist_ssids; i++)
	    memcpy(params[i].ssid, whitelist_ssids[i], sizeof(params[i].ssid));

    printMsg("whitelist SSIDs:\n");
    for (int i = 0; i < num_whitelist_ssids; i++) {
        printMsg("%d.\t%s\n", i, params[i].ssid);
    }

    cmdId = getNewCmdId();
    return hal_fn.wifi_set_ssid_white_list(cmdId, wlan0Handle, num_whitelist_ssids, params);
}

static wifi_error setRoamParams()
{
    wifi_roam_params params;
    memset(&params, 0, sizeof(params));
    int cmdId;

    params.A_band_boost_threshold  = -A_band_boost_threshold;
    params.A_band_penalty_threshold = -A_band_penalty_threshold;
    params.A_band_boost_factor = A_band_boost_factor;
    params.A_band_penalty_factor = A_band_penalty_factor;
    params.A_band_max_boost = A_band_max_boost;
    params.lazy_roam_hysteresis = lazy_roam_hysteresis;
    params.alert_roam_rssi_trigger = -alert_roam_rssi_trigger;

    cmdId = getNewCmdId();
    printMsg("Setting Roam params\n");
    return hal_fn.wifi_set_gscan_roam_params(cmdId, wlan0Handle, &params);
}


static wifi_error setBSSIDPreference()
{
    int cmdId;
    wifi_bssid_preference prefs[16];

    if (num_pref_bssids == -1)
        return WIFI_SUCCESS;

    memset(&prefs, 0, sizeof(prefs));

    for (int i = 0; i < num_pref_bssids; i++) {
	    memcpy(prefs[i].bssid, pref_bssids[i], sizeof(mac_addr));
	    prefs[i].rssi_modifier = rssi_modifier[i];
    }

    printMsg("BSSID\t\t\trssi_modifier\n");
    for (int i = 0; i < num_pref_bssids; i++) {
        mac_addr &addr = prefs[i].bssid;
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\t%d\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5],
                prefs[i].rssi_modifier);
    }

    cmdId = getNewCmdId();
    printMsg("Setting BSSID pref\n");
    return hal_fn.wifi_set_bssid_preference(cmdId, wlan0Handle, num_pref_bssids, prefs);
}

static wifi_error setLazyRoam()
{
    int cmdId;
    cmdId = getNewCmdId();
    printMsg("Lazy roam\n");
    return hal_fn.wifi_enable_lazy_roam(cmdId, wlan0Handle, lazy_roam);
}

static wifi_error setBlacklist()
{   
    if (num_blacklist_bssids == -1)
        return WIFI_SUCCESS;
    wifi_bssid_params params;

    params.num_bssid = num_blacklist_bssids;
    cmdId = getNewCmdId();
    printMsg("Setting Blacklist BSSIDs\n");
    for (int i = 0; i < num_blacklist_bssids; i++) {
        mac_addr &addr = params.bssids[i];
        memcpy(&params.bssids[i], &blacklist_bssids[i], sizeof(mac_addr) );
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5]);
    }
    return hal_fn.wifi_set_bssid_blacklist(cmdId, wlan0Handle, params);
}

static void testLazyRoam()
{
    int result;

    result = setRoamParams();
    if (result == WIFI_SUCCESS) {
        printMsg("Set Roaming Parameters\n");
    } else {
        printMsg("Could not set Roaming Parameters : %d\n", result);
    }
    result = setBlacklist();
    if (result == WIFI_SUCCESS) {
        printMsg("Set Blacklist Parameters\n");
    } else {
        printMsg("Could not set Roaming Parameters : %d\n", result);
    }
    result = setBSSIDPreference();
    if (result == WIFI_SUCCESS) {
        printMsg("Set BSSID preference\n");
    } else {
        printMsg("Could not set BSSID preference : %d\n", result);
    }
    result = setWhitelistBSSIDs();
    if (result == WIFI_SUCCESS) {
        printMsg("whitelisted SSIDs\n");
    } else {
        printMsg("Could not set SSID whitelist : %d\n", result);
    }
    result = setLazyRoam();
    if (result == WIFI_SUCCESS) {
        printMsg("Lazy roam command successful\n");
    } else {
        printMsg("Could not set Lazy Roam : %d\n", result);
    }
}

void printUsage() {
    printf("Usage:	halutil [OPTION]\n");
    printf(" -s 			  start AP scan test\n");
    printf(" -swc			  start Significant Wifi change test\n");
    printf(" -h 			  start Hotlist APs scan test\n");
    printf(" -ss			  stop scan test\n");
    printf(" -max_ap		  Max AP for scan \n");
    printf(" -base_period	  Base period for scan \n");
    printf(" -threshold 	  Threshold scan test\n");
    printf(" -avg_RSSI		  samples for averaging RSSI\n");
    printf(" -ap_loss		  samples to confirm AP loss\n");
    printf(" -ap_breach 	  APs breaching threshold\n");
    printf(" -ch_threshold	  Change in threshold\n");
    printf(" -wt_event		  Waiting event for test\n");
    printf(" -low_th		  Low threshold for hotlist APs\n");
    printf(" -hight_th		  High threshold for hotlist APs\n");
    printf(" -hotlist_bssids  BSSIDs for hotlist test\n");
    printf(" -stats 	  print link layer statistics\n");
    printf(" -get_ch_list <a/bg/abg/a_nodfs/abg_nodfs/dfs>	Get channel list\n");
    printf(" -get_feature_set  Get Feature set\n");
    printf(" -get_feature_matrix  Get concurrent feature matrix\n");
    printf(" -rtt [-get_ch_list <a/bg/abg>] [-i <burst_period of 100ms unit> [0 - 31] ]"
            "    [-n <exponents of 2 = (num_bursts)> [0 - 15]]\n"
            "    [-f <num_frames_per_burst>] [-r <num_retries_per_ftm>]\n"
            "    [-m <num_retries_per_ftmr>] [-b <burst_duration [2-11 or 15]>]"
            "    [-max_ap <count of allowed max AP>] [-l <file to read>] [-o <file to be stored>]\n");
    printf(" -cancel_rtt      cancel current RTT process\n");
    printf(" -get_capa_rtt Get the capability of RTT such as 11mc");
    printf(" -scan_mac_oui XY:AB:CD\n");
    printf(" -nodfs <0|1>	  Turn OFF/ON non-DFS locales\n");
    printf(" -country <alpha2 country code> Set country\n");
    printf(" -ePNO Configure ePNO SSIDs\n");
    printf(" -lazy_roam enable/disable lazy roam with default params\n");
    printf(" -a_boost_th A band boost threshold\n");
    printf(" -a_penalty_th A band penalty threshold\n");
    printf(" -a_boost_factor A band boost factor\n");
    printf(" -a_penalty_factor A band penalty factor\n");
    printf(" -max_boost max allowed boost\n");
    printf(" -hysteresis cur AP boost hysteresis\n");
    printf(" -alert_trigger alert roam trigger threshold\n");
    printf(" -blacklist_bssids blacklist bssids\n");
    printf(" -pref_bssid preference BSSID/RSSI pairs\n");
    printf(" -whitelist_ssids whitelist SSIDs\n");
    printf(" -logger [-start] [-d <debug_level> -f <flags> -i <max_interval_sec>\n"
           "                   -s <min_data_size> -n <ring_name>]\n"
           "         [-get]   [fw] [driver] [feature] [memdump -o <filename>]\n"
           "                  [ringstatus] [ringdata -n <ring_name>]\n"
           "         [-set]   [loghandler] [alerthandler]\n");
}

static bool isLazyRoamParam(char *arg)
{
    if ((strcmp(arg, "-blacklist_bssids") == 0)) {
        num_blacklist_bssids = 0;
        return true;
    }
    if ((strcmp(arg, "-pref_bssid") == 0)) {
        num_pref_bssids = 0;
        return true;
    }
    if ((strcmp(arg, "-whitelist_ssids") == 0)) {
        num_whitelist_ssids = 0;
        return true;
    }
    return ((strcmp(arg, "-lazy_roam") == 0) ||
            (strcmp(arg, "-a_boost_th") == 0) ||
            (strcmp(arg, "-a_penalty_th") == 0) ||
            (strcmp(arg, "-a_boost_factor") == 0) ||
            (strcmp(arg, "-a_penalty_factor") == 0) ||
            (strcmp(arg, "-max_boost") == 0) ||
            (strcmp(arg, "-hysteresis") == 0) ||
            (strcmp(arg, "-alert_trigger") == 0));
}

int main(int argc, char *argv[]) {

    pthread_mutex_init(&printMutex, NULL);

    if (init() != 0) {
        printMsg("could not initiate HAL");
        return -1;
    } else {
        printMsg("successfully initialized HAL; wlan0 = %p\n", wlan0Handle);
    }
    sem_init(&event_thread_mutex,0,0);

    pthread_cond_init(&eventCacheCondition, NULL);
    pthread_mutex_init(&eventCacheMutex, NULL);

    pthread_t tidEvent;
    pthread_create(&tidEvent, NULL, &eventThreadFunc, NULL);

    sem_wait(&event_thread_mutex);
    //sleep(2);     // let the thread start

    if (argc < 2 || argv[1][0] != '-') {
        printUsage();
        goto cleanup;
    }
    memset(mac_oui, 0, 3);

    if (strcmp(argv[1], "-s") == 0) {
        readTestOptions(argc, argv);
        setPnoMacOui();
        testScan();
    } else if(strcmp(argv[1], "-swc") == 0){
        readTestOptions(argc, argv);
        setPnoMacOui();
        trackSignificantChange();
    } else if (strcmp(argv[1], "-ss") == 0) {
        // Stop scan so clear the OUI too
        setPnoMacOui();
        testStopScan();
    } else if ((strcmp(argv[1], "-h") == 0)  ||
            (strcmp(argv[1], "-hotlist_bssids") == 0)) {
        readTestOptions(argc, argv);
        setPnoMacOui();
        testHotlistAPs();
    } else if (strcmp(argv[1], "-stats") == 0) {
        getLinkStats();
    } else if (strcmp(argv[1], "-rtt") == 0) {
        readRTTOptions(argc, ++argv);
        testRTT();
    } else if (strcmp(argv[1], "-cancel_rtt") == 0) {
        cancelRTT();
    } else if (strcmp(argv[1], "-get_capa_rtt") == 0) {
        getRTTCapability();
    } else if ((strcmp(argv[1], "-get_ch_list") == 0)) {
        readTestOptions(argc, argv);
        getChannelList();
    } else if ((strcmp(argv[1], "-get_feature_set") == 0)) {
        getFeatureSet();
    } else if ((strcmp(argv[1], "-get_feature_matrix") == 0)) {
        getFeatureSetMatrix();
    } else if ((strcmp(argv[1], "-scan_mac_oui") == 0)) {
        readTestOptions(argc, argv);
        setPnoMacOui();
        testScan();
    } else if (strcmp(argv[1], "-nodfs") == 0) {
        u32 nodfs = 0;
        if (argc > 2)
            nodfs = (u32)atoi(argv[2]);
        hal_fn.wifi_set_nodfs_flag(wlan0Handle, nodfs);
    } else if ((strcmp(argv[1], "-ePNO") == 0)) {
        memset(epno_ssid, 0, 16 * sizeof(epno_ssid[0]));
        num_epno_ssids = -1;
        readTestOptions(argc, argv);
        num_epno_ssids++;
        testPNO();
    } else if (strcmp(argv[1], "-country") == 0) {
        char *country_code;
        if (argc > 2)
            country_code = argv[2];
        printf("Fix Setting wifi_set_country_code\n");
        printf("***************************************\n");
        hal_fn.wifi_set_country_code(wlan0Handle, country_code);
    } else if ((strcmp(argv[1], "-logger") == 0)) {
        readLoggerOptions(argc, ++argv);
        runLogger();
    } else if (strcmp(argv[1], "-help") == 0) {
        printUsage();
    } else if (isLazyRoamParam(argv[1])) {
        readTestOptions(argc, argv);
        testLazyRoam();
    } else {
        printUsage();
    }

cleanup:
    cleanup();
    return 0;
}
