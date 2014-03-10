
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

#include "wifi_hal.h"
#include "common.h"
#include "cpp_bindings.h"

int WifiEvent::parse() {
    mHeader = (genlmsghdr *)nlmsg_data(nlmsg_hdr(mMsg));
    return nla_parse(mAttributes, NL80211_ATTR_MAX, genlmsg_attrdata(mHeader, 0),
          genlmsg_attrlen(mHeader, 0), NULL);
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
    int res = wifi_register_handler(mInfo, cmd, event_handler, this);
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
        ALOGE("Failed to parse reply message = %d", res);
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
