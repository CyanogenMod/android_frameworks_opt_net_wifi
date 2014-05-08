
#include <stdlib.h>
#include <pthread.h>

#include "wifi_hal.h"

#define LOG_TAG  "WifiHAL"

#include <utils/Log.h>
#include <inttypes.h>

#define EVENT_BUF_SIZE 2048

static wifi_handle halHandle;
static wifi_interface_handle *ifaceHandles;
static int numIfaceHandles;
static int cmdId = 0;

static int init() {
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
            printf("found interface %s\n", buf);
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


/* -------------------------------------------  */
/* commands and events                          */
/* -------------------------------------------  */

static void onScanResults(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {

    printf("Received scan results\n");

    for (unsigned i = 0; i < num_results; i++) {
        printScanResult(results[i]);
    }
}

static int scanCmdId;

static bool startScan() {

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results = &onScanResults;

    scanCmdId = getNewCmdId();

    return wifi_start_gscan(scanCmdId, ifaceHandles[0], params, handler) == WIFI_SUCCESS;
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
        sleep(2);
        stopScan();
        printf("stopped scan\n");
    }
}

int main(int argc, char *argv[]) {

    printf("successfully initialized HAL\n");

    if (init() != 0) {
        printf("could not initiate HAL");
        return -1;
    } else {
        printf("successfully initialized HAL\n");
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
