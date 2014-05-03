#include <stdint.h>
#include <stdlib.h>

#include "wifi_hal.h"

#define LOG_TAG  "WifiHAL"

#include <utils/Log.h>
#include <inttypes.h>
#include <sys/socket.h>
#include <linux/if.h>
#include <ctype.h>
#include <stdarg.h>

pthread_mutex_t printMutex;
void printMsg(const char *fmt, ...)
{
    pthread_mutex_lock(&printMutex);
    va_list l;
    va_start(l, fmt);

    vprintf(fmt, l);
    va_end(l);
    pthread_mutex_unlock(&printMutex);
}


#define EVENT_BUF_SIZE 2048

static wifi_handle halHandle;
static wifi_interface_handle *ifaceHandles;
static wifi_interface_handle wlan0Handle;
static wifi_interface_handle p2p0Handle;
static int numIfaceHandles;
static int cmdId = 0;
static int ioctl_sock = 0;
static int max_event_wait = 5;
static int stest_max_ap = 10;
static int stest_base_period = 1000;
static int stest_threshold = 80;
static int swctest_rssi_sample_size =  3;
static int swctest_rssi_lost_ap =  3;
static int swctest_rssi_min_breaching =  2;
static int swctest_rssi_ch_threshold =  1;
static int htest_low_threshold =  90;
static int htest_high_threshold =  10;

mac_addr hotlist_bssids[16];
int num_hotlist_bssids = 0;

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
    }else {
      printMsg("writing new value\n");
    }

    if (dev_up) {
      if (ifr.ifr_flags & IFF_UP) {
        printMsg("interface %s is already up\n", ifname);
        return 0;
      }
      ifr.ifr_flags |= IFF_UP;
    }else {
      if (!(ifr.ifr_flags & IFF_UP)) {
        printMsg("interface %s is already down\n", ifname);
        return 0;
      }
      ifr.ifr_flags &= ~IFF_UP;
    }

    if (ioctl(sock, SIOCSIFFLAGS, &ifr) != 0) {
      printMsg("Could not set interface %s flags \n", ifname);
      return ret;
    }else {
      printMsg("set interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");
    }
    printMsg("Done\n");
    return 0;
}


static int init() {

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

    wifi_error res = wifi_initialize(&halHandle);
    if (res < 0) {
        return res;
    }

    res = wifi_get_ifaces(halHandle, &numIfaceHandles, &ifaceHandles);
    if (res < 0) {
        return res;
    }

    char buf[EVENT_BUF_SIZE];
    for (int i = 0; i < numIfaceHandles; i++) {
        if (wifi_get_iface_name(ifaceHandles[i], buf, sizeof(buf)) == WIFI_SUCCESS) {
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
    wifi_cleanup(halHandle, cleaned_up_handler);
}

static void *eventThreadFunc(void *context) {

    printMsg("starting wifi event loop\n");
    wifi_event_loop(halHandle);
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

void printScanCapabilities(wifi_gscan_capabilities capabilities)
{
    printMsg("max_scan_cache_size = %d\n", capabilities.max_scan_cache_size);
    printMsg("max_scan_buckets = %d\n", capabilities.max_scan_buckets);
    printMsg("max_ap_cache_per_scan = %d\n", capabilities.max_ap_cache_per_scan);
    printMsg("max_rssi_sample_size = %d\n", capabilities.max_rssi_sample_size);
    printMsg("max_scan_reporting_threshold = %d\n", capabilities.max_scan_reporting_threshold);
    printMsg("max_hotlist_aps = %d\n", capabilities.max_hotlist_aps);
    printMsg("max_significant_wifi_change_aps = %d\n", capabilities.max_significant_wifi_change_aps);
}


/* -------------------------------------------  */
/* commands and events                          */
/* -------------------------------------------  */

typedef enum {
    EVENT_TYPE_SCAN_RESULTS_AVAILABLE = 1000,
    EVENT_TYPE_HOTLIST_AP_FOUND = 1001,
    EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE = 1002
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

static int scanCmdId;
static int hotlistCmdId;
static int significantChangeCmdId;

static bool startScan( void (*pfnOnResultsAvailable)(wifi_request_id, unsigned),
                       int max_ap_per_scan, int base_period, int report_threshold) {

    /* Get capabilties */
    wifi_gscan_capabilities capabilities;
    int result = wifi_get_gscan_capabilities(wlan0Handle, &capabilities);
    if (result < 0) {
        printMsg("failed to get scan capabilities - %d\n", result);
        printMsg("trying scan anyway ..\n");
    } else {
        printScanCapabilities(capabilities);
    }

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    /* create a schedule to scan channels 1, 6, 11 every 5 second and
     * scan 36, 40, 44, 149, 153, 157, 161 165 every 10 second */

    params.max_ap_per_scan = max_ap_per_scan;
    params.base_period = base_period;                      // 5 second
    params.report_threshold = report_threshold;
    params.num_buckets = 2;

    params.buckets[0].bucket = 0;
    params.buckets[0].band = WIFI_BAND_UNSPECIFIED;
    params.buckets[0].period = 5000;                // 5 second
    params.buckets[0].num_channels = 3;

    params.buckets[0].channels[0].channel = 2412;
    params.buckets[0].channels[1].channel = 2437;
    params.buckets[0].channels[2].channel = 2462;

    params.buckets[1].bucket = 1;
    params.buckets[1].band = WIFI_BAND_UNSPECIFIED;
    params.buckets[1].period = 10000;               // 10 second
    params.buckets[1].num_channels = 8;

    params.buckets[1].channels[0].channel = 5180;
    params.buckets[1].channels[1].channel = 5200;
    params.buckets[1].channels[2].channel = 5220;
    params.buckets[1].channels[3].channel = 5745;
    params.buckets[1].channels[4].channel = 5765;
    params.buckets[1].channels[5].channel = 5785;
    params.buckets[1].channels[6].channel = 5805;
    params.buckets[1].channels[7].channel = 5825;

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = pfnOnResultsAvailable;

    scanCmdId = getNewCmdId();
    printMsg("Starting scan --->\n");
    return wifi_start_gscan(scanCmdId, wlan0Handle, params, handler) == WIFI_SUCCESS;
}

static void stopScan() {
    if (scanCmdId != 0) {
        wifi_stop_gscan(scanCmdId, wlan0Handle);
        scanCmdId = 0;
    } else {
        wifi_scan_cmd_params params;
        memset(&params, 0, sizeof(params));
        /* create a schedule to scan channels 1, 6, 11 every 5 second */

        params.max_ap_per_scan = 10;
        params.base_period = 5000;                      // 5 second
        params.report_threshold = 80;
        params.num_buckets = 1;

        params.buckets[0].bucket = 0;
        params.buckets[0].band = WIFI_BAND_UNSPECIFIED;
        params.buckets[0].period = 5000;                // 5 second
        params.buckets[0].num_channels = 3;

        params.buckets[0].channels[0].channel = 2412;
        params.buckets[0].channels[1].channel = 2437;
        params.buckets[0].channels[2].channel = 2462;

        wifi_scan_result_handler handler;
        memset(&handler, 0, sizeof(handler));
        handler.on_scan_results_available = &onScanResultsAvailable;

        scanCmdId = getNewCmdId();
        if (wifi_start_gscan(scanCmdId, wlan0Handle, params, handler) == WIFI_SUCCESS) {
            wifi_stop_gscan(scanCmdId, wlan0Handle);
        }
    }
}

static void retrieveScanResults() {

    wifi_scan_result results[256];
    memset(results, 0, sizeof(wifi_scan_result) * 256);
    printMsg("Retrieve Scan results available -->\n");
    int num_results = 256;
    int result = wifi_get_cached_gscan_results(wlan0Handle, 1, results, &num_results);
    if (result < 0) {
        printMsg("failed to fetch scan results : %d\n", result);
        return;
    } else {
        printMsg("fetched %d scan results\n", num_results);
    }

    printScanHeader();
    for (int i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
}

static void onHotlistAPFound(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {

    printMsg("Found hotlist APs\n");
    for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
    putEventInCache(EVENT_TYPE_HOTLIST_AP_FOUND, "Found a hotlist AP");
}

static wifi_error setHotlistAPsUsingScanResult(wifi_bssid_hotlist_params *params){
    printMsg("testHotlistAPs Scan started, waiting for event ...\n");
    EventInfo info;
    memset(&info, 0, sizeof(info));
    getEventFromCache(info);

    wifi_scan_result results[256];
    memset(results, 0, sizeof(wifi_scan_result) * 256);

    printMsg("Retrieving scan results for Hotlist AP setting\n");
    int num_results = 256;
    int result = wifi_get_cached_gscan_results(wlan0Handle, 1, results, &num_results);
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
        memcpy(params->bssids[i].bssid, results[i].bssid, sizeof(mac_addr));
        params->bssids[i].low  = -htest_low_threshold;
        params->bssids[i].high = -htest_high_threshold;
    }
    params->num = stest_max_ap;
    return WIFI_SUCCESS;
}

static wifi_error setHotlistAPs() {
    wifi_bssid_hotlist_params params;
    memset(&params, 0, sizeof(params));

    if (num_hotlist_bssids > 0) {
      for (int i = 0; i < num_hotlist_bssids; i++) {
          memcpy(params.bssids[i].bssid, hotlist_bssids[i], sizeof(mac_addr));
          params.bssids[i].low  = -htest_low_threshold;
          params.bssids[i].high = -htest_high_threshold;
      }
      params.num = num_hotlist_bssids;
    } else {
      setHotlistAPsUsingScanResult(&params);
    }

    printMsg("BSSID\t\t\tHIGH\tLOW\n");
    for (int i = 0; i < params.num; i++) {
        mac_addr &addr = params.bssids[i].bssid;
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\t%d\t%d\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5],
                params.bssids[i].high, params.bssids[i].low);
    }

    wifi_hotlist_ap_found_handler handler;
    handler.on_hotlist_ap_found = &onHotlistAPFound;

    hotlistCmdId = getNewCmdId();
    printMsg("Setting hotlist APs threshold\n");
    return wifi_set_bssid_hotlist(hotlistCmdId, wlan0Handle, params, handler);
}

static void resetHotlistAPs() {
    printMsg(", stoping Hotlist AP scanning\n");
    wifi_reset_bssid_hotlist(hotlistCmdId, wlan0Handle);
}


static void testHotlistAPs(){

    EventInfo info;
    memset(&info, 0, sizeof(info));

    printMsg("starting Hotlist AP scanning\n");
    if (!startScan(&onScanResultsAvailable, stest_max_ap,stest_base_period, stest_threshold)) {
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
        } else if (info.type == EVENT_TYPE_HOTLIST_AP_FOUND) {
            printMsg("Found Hotlist APs");
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

static void onSignificantWifiChange(wifi_request_id id,
        unsigned num_results, wifi_scan_result *results)
{
    printMsg("Significant wifi change for %d\n", num_results);
    for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
    putEventInCache(EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE, "significant wifi change noticed");
}

static int SelectSignificantAPsFromScanResults() {
    wifi_scan_result results[256];
    memset(results, 0, sizeof(wifi_scan_result) * 256);
    printMsg("Retrieving scan results for significant wifi change setting\n");
    int num_results = 256;
    int result = wifi_get_cached_gscan_results(wlan0Handle, 1, results, &num_results);
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
        memcpy(params.bssids[i].bssid, results[i].bssid, sizeof(mac_addr));
        params.bssids[i].low  = results[i].rssi - swctest_rssi_ch_threshold;
        params.bssids[i].high = results[i].rssi + swctest_rssi_ch_threshold;
    }
    params.num = stest_max_ap;

    printMsg("Settting Significant change params rssi_sample_size#%d lost_ap_sample_size#%d"
        " and min_breaching#%d\n", params.rssi_sample_size, params.lost_ap_sample_size , params.min_breaching);
    printMsg("BSSID\t\t\tHIGH\tLOW\n");
    for (int i = 0; i < params.num; i++) {
        mac_addr &addr = params.bssids[i].bssid;
        printMsg("%02x:%02x:%02x:%02x:%02x:%02x\t%d\t%d\n", addr[0],
                addr[1], addr[2], addr[3], addr[4], addr[5],
                params.bssids[i].high, params.bssids[i].low);
    }
    wifi_significant_change_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_significant_change = &onSignificantWifiChange;

    int id = getNewCmdId();
    return wifi_set_significant_change_handler(id, wlan0Handle, params, handler);

}

static void untrackSignificantChange() {
    printMsg(", Stop tracking SignificantChange\n");
    wifi_reset_bssid_hotlist(hotlistCmdId, wlan0Handle);
}

static void trackSignificantChange() {
    printMsg("starting trackSignificantChange\n");

    if (!startScan(&onScanResultsAvailable, stest_max_ap,stest_base_period, stest_threshold)) {
        printMsg("trackSignificantChange failed to start scan!!\n");
        return;
    } else {
        printMsg("trackSignificantChange Scan started, waiting for event ...\n");
    }

    EventInfo info;
    memset(&info, 0, sizeof(info));
    getEventFromCache(info);

    int result = SelectSignificantAPsFromScanResults();
    if(result == WIFI_SUCCESS){
      printMsg("Waiting for significant wifi change event\n");
      while(true) {
          memset(&info, 0, sizeof(info));
          getEventFromCache(info);

          if (info.type == EVENT_TYPE_SCAN_RESULTS_AVAILABLE) {
              retrieveScanResults();
          }else if(info.type == EVENT_TYPE_SIGNIFICANT_WIFI_CHANGE){
              printMsg("Received significant wifi change");
              if(--max_event_wait > 0)
                printMsg(", waiting for more event ::%d\n", max_event_wait);
              else
                break;
          }
      }
      untrackSignificantChange();
    }else{
      printMsg("Failed to set significant change  ::%d\n", result);
    }

}




/* -------------------------------------------  */
/* tests                                        */
/* -------------------------------------------  */

void testScan() {
    printf("starting scan with max_ap_per_scan#%d  base_period#%d  threshold#%d \n",
           stest_max_ap,stest_base_period, stest_threshold);
    if (!startScan(&onScanResultsAvailable, stest_max_ap,stest_base_period, stest_threshold)) {
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

byte parseHexChar(char ch) {
    if (isdigit(ch))
        return ch - '0';
    else if ('A' <= ch && ch <= 'F')
        return ch - 'A' + 10;
    else if ('a' <= ch && ch <= 'f')
        return ch - 'a' + 10;
    else {
        printMsg("invalid character in bssid %c", ch);
        return 0;
    }
}

byte parseHexByte(char ch1, char ch2) {
    return (parseHexChar(ch1) << 4) | parseHexChar(ch2);
}

void parseMacAddress(char *str, mac_addr addr) {
    addr[0] = parseHexByte(str[0], str[1]);
    addr[1] = parseHexByte(str[3], str[4]);
    addr[2] = parseHexByte(str[6], str[7]);
    addr[3] = parseHexByte(str[9], str[10]);
    addr[4] = parseHexByte(str[12], str[13]);
    addr[5] = parseHexByte(str[15], str[16]);
    printMsg("read mac addr: %02x:%02x:%02x:%02x:%02x:%02x\n", addr[0],
            addr[1], addr[2], addr[3], addr[4], addr[5]);
}

void readTestOptions(int argc, char *argv[]){

   printf("Total number of argc #%d\n", argc);
   for(int j = 1; j < argc-1; j++){
     if (strcmp(argv[j], "-max_ap") == 0 && isdigit(argv[j+1][0])) {
       stest_max_ap = atoi(argv[++j]);
       printf(" max_ap #%d\n", stest_max_ap);
     } else if (strcmp(argv[j], "-base_period") == 0 && isdigit(argv[j+1][0])) {
       stest_base_period = atoi(argv[++j]);
       printf(" base_period #%d\n", stest_base_period);
     } else if (strcmp(argv[j], "-threshold") == 0 && isdigit(argv[j+1][0])) {
       stest_threshold = atoi(argv[++j]);
       printf(" threshold #%d\n", stest_threshold);
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
       for (num_hotlist_bssids = 0; j < argc && isxdigit(argv[j][0]); j++, num_hotlist_bssids++) {
         parseMacAddress(argv[j], hotlist_bssids[num_hotlist_bssids]);
       }
       j -= 1;
     }

   }
}

wifi_iface_stat link_stat;
void onLinkStatsResults(wifi_request_id id, wifi_iface_stat *iface_stat,
         int num_radios, wifi_radio_stat *radio_stat)
{
    memcpy(&link_stat, iface_stat, sizeof(wifi_iface_stat));
}

void printLinkStats(wifi_iface_stat link_stat)
{
    printMsg("printing link layer statistics:\n");
    printMsg("beacon_rx = %d\n", link_stat.beacon_rx);
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
}

void getLinkStats(void)
{
    wifi_stats_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_link_stats_results = &onLinkStatsResults;

    int result = wifi_get_link_stats(0, wlan0Handle, handler);
    if (result < 0) {
        printMsg("failed to get link statistics - %d\n", result);
    } else {
        printLinkStats(link_stat);
    }
}

int main(int argc, char *argv[]) {

    pthread_mutex_init(&printMutex, NULL);

    if (init() != 0) {
        printMsg("could not initiate HAL");
        return -1;
    } else {
        printMsg("successfully initialized HAL; wlan0 = %p\n", wlan0Handle);
    }

    pthread_cond_init(&eventCacheCondition, NULL);
    pthread_mutex_init(&eventCacheMutex, NULL);

    pthread_t tidEvent;
    pthread_create(&tidEvent, NULL, &eventThreadFunc, NULL);

    sleep(2);     // let the thread start

    if (argc < 2 || argv[1][0] != '-') {
        printf("Usage:  halutil [OPTION]\n");
        printf(" -s               start AP scan test\n");
        printf(" -swc             start Significant Wifi change test\n");
        printf(" -h               start Hotlist APs scan test\n");
        printf(" -ss              stop scan test\n");
        printf(" -max_ap          Max AP for scan \n");
        printf(" -base_period     Base period for scan \n");
        printf(" -threshold       Threshold scan test\n");
        printf(" -avg_RSSI        samples for averaging RSSI\n");
        printf(" -ap_loss         samples to confirm AP loss\n");
        printf(" -ap_breach       APs breaching threshold\n");
        printf(" -ch_threshold    Change in threshold\n");
        printf(" -wt_event        Waiting event for test\n");
        printf(" -low_th          Low threshold for hotlist APs\n");
        printf(" -hight_th        High threshold for hotlist APs\n");
        printf(" -hotlist_bssids  BSSIDs for hotlist test\n");
        printf(" -stats  	  print link layer statistics\n");
        goto cleanup;
    }

    if (strcmp(argv[1], "-s") == 0) {
        readTestOptions(argc, argv);
        testScan();
    }else if(strcmp(argv[1], "-swc") == 0){
        readTestOptions(argc, argv);
        trackSignificantChange();
    }else if (strcmp(argv[1], "-ss") == 0) {
        testStopScan();
    }else if(strcmp(argv[1], "-h") == 0) {
        readTestOptions(argc, argv);
        testHotlistAPs();
    }else if (strcmp(argv[1], "-stats") == 0) {
	getLinkStats();
    }

cleanup:
    cleanup();
    return 0;
}
