#include <stdint.h>

#include "wifi_hal.h"

#define LOG_TAG  "WifiHAL"

#include <utils/Log.h>
#include <inttypes.h>
#include <sys/socket.h>
#include <linux/if.h>

#define EVENT_BUF_SIZE 2048

static wifi_handle halHandle;
static wifi_interface_handle *ifaceHandles;
static wifi_interface_handle wlan0Handle;
static wifi_interface_handle p2p0Handle;
static int numIfaceHandles;
static int cmdId = 0;
static int ioctl_sock = 0;

int linux_set_iface_flags(int sock, const char *ifname, int dev_up)
{
	struct ifreq ifr;
	int ret;

    printf("setting interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");

	if (sock < 0) {
		printf("Bad socket: %d\n", sock);
		return -1;
	}

	memset(&ifr, 0, sizeof(ifr));
	strlcpy(ifr.ifr_name, ifname, IFNAMSIZ);

    printf("reading old value\n");

	if (ioctl(sock, SIOCGIFFLAGS, &ifr) != 0) {
		ret = errno ? -errno : -999;
		printf("Could not read interface %s flags: %d\n", ifname, errno);
		return ret;
	} else {
        printf("writing new value\n");
	}

	if (dev_up) {
		if (ifr.ifr_flags & IFF_UP) {
            printf("interface %s is already up\n", ifname);
			return 0;
	    }
		ifr.ifr_flags |= IFF_UP;
	} else {
		if (!(ifr.ifr_flags & IFF_UP)) {
            printf("interface %s is already down\n", ifname);
			return 0;
		}
		ifr.ifr_flags &= ~IFF_UP;
	}


	if (ioctl(sock, SIOCSIFFLAGS, &ifr) != 0) {
		printf("Could not set interface %s flags \n", ifname);
		return ret;
	} else {
		printf("set interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");
	}

    printf("Done\n");
	return 0;
}


static int init() {

    ioctl_sock = socket(PF_INET, SOCK_DGRAM, 0);
    if (ioctl_sock < 0) {
		printf("Bad socket: %d\n", ioctl_sock);
        return errno;
    } else {
		printf("Good socket: %d\n", ioctl_sock);
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
                printf("found interface %s\n", buf);
                wlan0Handle = ifaceHandles[i];
            } else if (strcmp(buf, "p2p0") == 0) {
                printf("found interface %s\n", buf);
                p2p0Handle = ifaceHandles[i];
            }
        }
    }

    return res;
}

static void cleaned_up_handler(wifi_handle handle) {
    printf("HAL cleaned up handler\n");
    halHandle = NULL;
    ifaceHandles = NULL;
}

static void cleanup() {
    printf("cleaning up HAL\n");

    wifi_cleanup(halHandle, cleaned_up_handler);

    /* wait for clean_up_handler to execute */
    /*
    while (halHandle != NULL) {
        sleep(100);
    }
    */
}

static void *eventThreadFunc(void *context) {

    printf("starting wifi event loop\n");
    wifi_event_loop(halHandle);
    printf("out of wifi event loop\n");

    return NULL;
}


static int getNewCmdId() {
    return cmdId++;
}

/* -------------------------------------------  */
/* helpers                                      */
/* -------------------------------------------  */

void printScanResult(wifi_scan_result result) {

    printf("SSID = %s\n", result.ssid);

    printf("BSSID = %02x:%02x:%02x:%02x:%02x:%02x\n", result.bssid[0], result.bssid[1],
            result.bssid[2], result.bssid[3], result.bssid[4], result.bssid[5]);

    printf("RSSI = %d\n", result.rssi);
    printf("Channel = %d\n", result.channel);
    printf("timestamp = %lld\n", result.ts);
    printf("rtt = %lld\n", result.rtt);
    printf("rtt_sd = %lld\n", result.rtt_sd);
}

void printScanCapabilities(wifi_gscan_capabilities capabilities)
{
    printf("max_scan_cache_size = %d\n", capabilities.max_scan_cache_size);
    printf("max_scan_buckets = %d\n", capabilities.max_scan_buckets);
    printf("max_ap_cache_per_scan = %d\n", capabilities.max_ap_cache_per_scan);
    printf("max_rssi_sample_size = %d\n", capabilities.max_rssi_sample_size);
    printf("max_scan_reporting_threshold = %d\n", capabilities.max_scan_reporting_threshold);
    printf("max_hotlist_aps = %d\n", capabilities.max_hotlist_aps);
    printf("max_significant_wifi_change_aps = %d\n", capabilities.max_significant_wifi_change_aps);
}


/* -------------------------------------------  */
/* commands and events                          */
/* -------------------------------------------  */

static void onScanResults(wifi_request_id id, unsigned num_results) {

    printf("Received scan results\n");

    /*for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }*/
}

static int scanCmdId;

static bool startScan() {

    /* Get capabilties */
    wifi_gscan_capabilities capabilities;
    int result = wifi_get_gscan_capabilities(wlan0Handle, &capabilities);
    if (result < 0) {
        printf("failed to get scan capabilities - %d\n", result);
        printf("trying scan anyway ..\n");
    } else {
        printScanCapabilities(capabilities);
    }

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = &onScanResults;

    scanCmdId = getNewCmdId();

    return wifi_start_gscan(scanCmdId, wlan0Handle, params, handler) == WIFI_SUCCESS;
}

static void stopScan() {
    if (scanCmdId != 0) {
        wifi_stop_gscan(scanCmdId, ifaceHandles[0]);
        scanCmdId = 0;
    }
}

/* -------------------------------------------  */
/* tests                                        */
/* -------------------------------------------  */

void testScan() {
    printf("starting scan\n");
    if (!startScan()) {
        printf("failed to start scan!!\n");
    } else {
        sleep(40);
        stopScan();
        printf("stopped scan\n");
    }
}

int main(int argc, char *argv[]) {

    if (init() != 0) {
        printf("could not initiate HAL");
        return -1;
    } else {
        printf("successfully initialized HAL; wlan0 = %p\n", wlan0Handle);
    }

    pthread_t tid;
    pthread_create(&tid, NULL, &eventThreadFunc, NULL);

    sleep(2);     // let the thread start

    if (argc == 2) {
        if (argv[1][0] != '-') {
            printf("%s: invalid usage", argv[0]);
            goto cleanup;
        }

        if (argv[1][1] == 's') {
            testScan();
        }
    }

cleanup:
    cleanup();
    // pthread_join(tid, NULL);
    return 0;
}
