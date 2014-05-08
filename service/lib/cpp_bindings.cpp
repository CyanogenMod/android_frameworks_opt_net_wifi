
#include <stdint.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netlink/genl/genl.h>
#include <netlink/genl/family.h>
#include <netlink/genl/ctrl.h>
#include <linux/rtnetlink.h>
#include <netpacket/packet.h>
#include <linux/filter.h>
#include <linux/errqueue.h>

#include <linux/pkt_sched.h>
#include <netlink/object-api.h>
#include <netlink/netlink.h>
#include <netlink/socket.h>
#include <netlink-types.h>

#include <linux/nl80211.h>
#include <ctype.h>

#include "wifi_hal.h"
#include "common.h"
#include "cpp_bindings.h"

#define min(x,y)        (((x) < (y)) ? (x) : (y))
#define max(x,y)        (((x) < (y)) ? (y) : (x))

void appendFmt(char *buf, int &offset, const char *fmt, ...)
{
    va_list params;
    va_start(params, fmt);
    offset += vsprintf(buf + offset, fmt, params);
    va_end(params);
}

#define C2S(x)  case x: return #x;

static const char *cmdToString(int cmd)
{
	switch (cmd) {
	C2S(NL80211_CMD_UNSPEC)
	C2S(NL80211_CMD_GET_WIPHY)
	C2S(NL80211_CMD_SET_WIPHY)
	C2S(NL80211_CMD_NEW_WIPHY)
	C2S(NL80211_CMD_DEL_WIPHY)
	C2S(NL80211_CMD_GET_INTERFACE)
	C2S(NL80211_CMD_SET_INTERFACE)
	C2S(NL80211_CMD_NEW_INTERFACE)
	C2S(NL80211_CMD_DEL_INTERFACE)
	C2S(NL80211_CMD_GET_KEY)
	C2S(NL80211_CMD_SET_KEY)
	C2S(NL80211_CMD_NEW_KEY)
	C2S(NL80211_CMD_DEL_KEY)
	C2S(NL80211_CMD_GET_BEACON)
	C2S(NL80211_CMD_SET_BEACON)
	C2S(NL80211_CMD_START_AP)
	C2S(NL80211_CMD_STOP_AP)
	C2S(NL80211_CMD_GET_STATION)
	C2S(NL80211_CMD_SET_STATION)
	C2S(NL80211_CMD_NEW_STATION)
	C2S(NL80211_CMD_DEL_STATION)
	C2S(NL80211_CMD_GET_MPATH)
	C2S(NL80211_CMD_SET_MPATH)
	C2S(NL80211_CMD_NEW_MPATH)
	C2S(NL80211_CMD_DEL_MPATH)
	C2S(NL80211_CMD_SET_BSS)
	C2S(NL80211_CMD_SET_REG)
	C2S(NL80211_CMD_REQ_SET_REG)
	C2S(NL80211_CMD_GET_MESH_CONFIG)
	C2S(NL80211_CMD_SET_MESH_CONFIG)
	C2S(NL80211_CMD_SET_MGMT_EXTRA_IE)
	C2S(NL80211_CMD_GET_REG)
	C2S(NL80211_CMD_GET_SCAN)
	C2S(NL80211_CMD_TRIGGER_SCAN)
	C2S(NL80211_CMD_NEW_SCAN_RESULTS)
	C2S(NL80211_CMD_SCAN_ABORTED)
	C2S(NL80211_CMD_REG_CHANGE)
	C2S(NL80211_CMD_AUTHENTICATE)
	C2S(NL80211_CMD_ASSOCIATE)
	C2S(NL80211_CMD_DEAUTHENTICATE)
	C2S(NL80211_CMD_DISASSOCIATE)
	C2S(NL80211_CMD_MICHAEL_MIC_FAILURE)
	C2S(NL80211_CMD_REG_BEACON_HINT)
	C2S(NL80211_CMD_JOIN_IBSS)
	C2S(NL80211_CMD_LEAVE_IBSS)
	C2S(NL80211_CMD_TESTMODE)
	C2S(NL80211_CMD_CONNECT)
	C2S(NL80211_CMD_ROAM)
	C2S(NL80211_CMD_DISCONNECT)
	C2S(NL80211_CMD_SET_WIPHY_NETNS)
	C2S(NL80211_CMD_GET_SURVEY)
	C2S(NL80211_CMD_NEW_SURVEY_RESULTS)
	C2S(NL80211_CMD_SET_PMKSA)
	C2S(NL80211_CMD_DEL_PMKSA)
	C2S(NL80211_CMD_FLUSH_PMKSA)
	C2S(NL80211_CMD_REMAIN_ON_CHANNEL)
	C2S(NL80211_CMD_CANCEL_REMAIN_ON_CHANNEL)
	C2S(NL80211_CMD_SET_TX_BITRATE_MASK)
	C2S(NL80211_CMD_REGISTER_FRAME)
	C2S(NL80211_CMD_FRAME)
	C2S(NL80211_CMD_FRAME_TX_STATUS)
	C2S(NL80211_CMD_SET_POWER_SAVE)
	C2S(NL80211_CMD_GET_POWER_SAVE)
	C2S(NL80211_CMD_SET_CQM)
	C2S(NL80211_CMD_NOTIFY_CQM)
	C2S(NL80211_CMD_SET_CHANNEL)
	C2S(NL80211_CMD_SET_WDS_PEER)
	C2S(NL80211_CMD_FRAME_WAIT_CANCEL)
	C2S(NL80211_CMD_JOIN_MESH)
	C2S(NL80211_CMD_LEAVE_MESH)
	C2S(NL80211_CMD_UNPROT_DEAUTHENTICATE)
	C2S(NL80211_CMD_UNPROT_DISASSOCIATE)
	C2S(NL80211_CMD_NEW_PEER_CANDIDATE)
	C2S(NL80211_CMD_GET_WOWLAN)
	C2S(NL80211_CMD_SET_WOWLAN)
	C2S(NL80211_CMD_START_SCHED_SCAN)
	C2S(NL80211_CMD_STOP_SCHED_SCAN)
	C2S(NL80211_CMD_SCHED_SCAN_RESULTS)
	C2S(NL80211_CMD_SCHED_SCAN_STOPPED)
	C2S(NL80211_CMD_SET_REKEY_OFFLOAD)
	C2S(NL80211_CMD_PMKSA_CANDIDATE)
	C2S(NL80211_CMD_TDLS_OPER)
	C2S(NL80211_CMD_TDLS_MGMT)
	C2S(NL80211_CMD_UNEXPECTED_FRAME)
	C2S(NL80211_CMD_PROBE_CLIENT)
	C2S(NL80211_CMD_REGISTER_BEACONS)
	C2S(NL80211_CMD_UNEXPECTED_4ADDR_FRAME)
	C2S(NL80211_CMD_SET_NOACK_MAP)
	C2S(NL80211_CMD_CH_SWITCH_NOTIFY)
	C2S(NL80211_CMD_START_P2P_DEVICE)
	C2S(NL80211_CMD_STOP_P2P_DEVICE)
	C2S(NL80211_CMD_CONN_FAILED)
	C2S(NL80211_CMD_SET_MCAST_RATE)
	C2S(NL80211_CMD_SET_MAC_ACL)
	C2S(NL80211_CMD_RADAR_DETECT)
	C2S(NL80211_CMD_GET_PROTOCOL_FEATURES)
	C2S(NL80211_CMD_UPDATE_FT_IES)
	C2S(NL80211_CMD_FT_EVENT)
	C2S(NL80211_CMD_CRIT_PROTOCOL_START)
	C2S(NL80211_CMD_CRIT_PROTOCOL_STOP)
	//C2S(NL80211_CMD_GET_COALESCE)
	//C2S(NL80211_CMD_SET_COALESCE)
	//C2S(NL80211_CMD_CHANNEL_SWITCH)
	//C2S(NL80211_CMD_VENDOR)
	//C2S(NL80211_CMD_SET_QOS_MAP)
	default:
		return "NL80211_CMD_UNKNOWN";
	}
}

void WifiEvent::log() {
    parse();

    byte *data = (byte *)genlmsg_attrdata(mHeader, 0);
    int len = genlmsg_attrlen(mHeader, 0);
    ALOGD("cmd = %s, len = %d", get_cmdString(), len);

    for (int i = 0; i < len; i += 16) {
        char line[81];
        int linelen = min(16, len - i);
        int offset = 0;
        appendFmt(line, offset, "%02x", data[i]);
        for (int j = 1; j < linelen; j++) {
            appendFmt(line, offset, " %02x", data[i+j]);
        }

        for (int j = linelen; j < 16; j++) {
            appendFmt(line, offset, "   ");
        }

        line[23] = '-';

        appendFmt(line, offset, "  ");

        for (int j = 0; j < linelen; j++) {
            if (isprint(data[i+j])) {
                appendFmt(line, offset, "%c", data[i+j]);
            } else {
                appendFmt(line, offset, "-");
            }
        }

        ALOGD(line);
    }

    ALOGD("-- End of message --");
}

const char *WifiEvent::get_cmdString() {
    return cmdToString(get_cmd());
}


int WifiEvent::parse() {
    if (mHeader != NULL) {
        return WIFI_SUCCESS;
    }
    mHeader = (genlmsghdr *)nlmsg_data(nlmsg_hdr(mMsg));
    int result = nla_parse(mAttributes, NL80211_ATTR_MAX_INTERNAL, genlmsg_attrdata(mHeader, 0),
          genlmsg_attrlen(mHeader, 0), NULL);

    ALOGD("event len = %d", nlmsg_hdr(mMsg)->nlmsg_len);
    return result;
}

int WifiRequest::create(int family, uint8_t cmd, int flags, int hdrlen) {
    mMsg = nlmsg_alloc();
    if (mMsg != NULL) {
        genlmsg_put(mMsg, /* pid = */ 0, /* seq = */ 0, family,
                hdrlen, flags, cmd, /* version = */ 0);
        return WIFI_SUCCESS;
    } else {
        return WIFI_ERROR_OUT_OF_MEMORY;
    }
}

int WifiRequest::create(uint32_t id, int subcmd) {
    int res = create(NL80211_CMD_VENDOR);
    if (res < 0) {
        return res;
    }

    res = put_u32(NL80211_ATTR_VENDOR_ID, id);
    if (res < 0) {
        return res;
    }

    res = put_u32(NL80211_ATTR_VENDOR_SUBCMD, subcmd);
    if (res < 0) {
        return res;
    }
    return 0;
}

int WifiCommand::requestResponse() {
    struct nl_cb *cb = NULL;
    int err = create();                 /* create the message */
    if (err < 0)
        goto out;

    cb = nl_cb_alloc(NL_CB_DEFAULT);    /* override the callbacks */
    if (!cb)
        goto out;

    err = nl_send_auto_complete(mInfo->cmd_sock, mMsg.getMessage());    /* send message */
    if (err < 0)
        goto out;

    err = 1;

    nl_cb_err(cb, NL_CB_CUSTOM, error_handler, &err);
    nl_cb_set(cb, NL_CB_FINISH, NL_CB_CUSTOM, finish_handler, &err);
    nl_cb_set(cb, NL_CB_ACK, NL_CB_CUSTOM, ack_handler, &err);
    nl_cb_set(cb, NL_CB_VALID, NL_CB_CUSTOM, response_handler, this);

    while (err > 0) {                   /* wait for reply */
        int res = nl_recvmsgs(mInfo->cmd_sock, cb);
        if (res) {
            ALOGE("nl80211: %s->nl_recvmsgs failed: %d", __func__, res);
        }
    }
out:
    nl_cb_put(cb);
    return err;
}

int WifiCommand::requestEvent(int cmd) {

    ALOGD("requesting event %d", cmd);

    int res = wifi_register_handler(mInfo, cmd, event_handler, this);
    if (res < 0) {
        return res;
    }

    res = create();                                                 /* create the message */
    if (res < 0)
        goto out;

    ALOGD("waiting for response %d", cmd);

    res = nl_send_auto_complete(mInfo->cmd_sock, mMsg.getMessage());    /* send message */
    if (res < 0)
        goto out;

    ALOGD("waiting for event %d", cmd);
    res = mCondition.wait();
    if (res < 0)
        goto out;

out:
    wifi_unregister_handler(mInfo, cmd);
    return res;
}

int WifiCommand::requestVendorEvent(uint32_t id, int subcmd) {

    int res = wifi_register_vendor_handler(mInfo, id, subcmd, event_handler, this);
    if (res < 0) {
        return res;
    }

    res = create();                                                 /* create the message */
    if (res < 0)
        goto out;

    res = nl_send_auto_complete(mInfo->cmd_sock, mMsg.getMessage());    /* send message */
    if (res < 0)
        goto out;

    res = mCondition.wait();
    if (res < 0)
        goto out;

out:
    wifi_unregister_vendor_handler(mInfo, id, subcmd);
    return res;
}

/* Event handlers */
int WifiCommand::response_handler(struct nl_msg *msg, void *arg) {
    WifiCommand *cmd = (WifiCommand *)arg;
    WifiEvent reply(msg);
    int res = reply.parse();
    if (res < 0) {
        ALOGE("Failed to parse reply message = %d", res);
        return NL_SKIP;
    } else {
        return cmd->handleResponse(reply);
    }
}

int WifiCommand::event_handler(struct nl_msg *msg, void *arg) {
    WifiCommand *cmd = (WifiCommand *)arg;
    WifiEvent event(msg);
    int res = event.parse();
    if (res < 0) {
        ALOGE("Failed to parse event = %d", res);
        res = NL_SKIP;
    } else {
        res = cmd->handleEvent(event);
    }

    cmd->mCondition.signal();
    return res;
}

/* Other event handlers */
int WifiCommand::ack_handler(struct nl_msg *msg, void *arg) {
    int *err = (int *)arg;
    *err = 0;
    return NL_STOP;
}

int WifiCommand::finish_handler(struct nl_msg *msg, void *arg) {
    int *ret = (int *)arg;
    *ret = 0;
    return NL_SKIP;
}

int WifiCommand::error_handler(struct sockaddr_nl *nla, struct nlmsgerr *err, void *arg) {
    int *ret = (int *)arg;
    *ret = err->error;
    return NL_SKIP;
}
