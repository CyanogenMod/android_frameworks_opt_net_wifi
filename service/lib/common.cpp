
#include <stdlib.h>
#include <netlink/handlers.h>

#include "wifi_hal.h"
#include "common.h"

wifi_error wifi_register_handler(wifi_handle handle, int cmd, nl_recvmsg_msg_cb_t func, void *arg)
{
    hal_info *info = (hal_info *)handle;

    /* TODO: check for multiple handlers? */

    if (info->num_event_cb < info->alloc_event_cb) {
        info->event_cb[info->num_event_cb].nl_cmd  = cmd;
        info->event_cb[info->num_event_cb].vendor_id  = 0;
        info->event_cb[info->num_event_cb].vendor_subcmd  = 0;
        info->event_cb[info->num_event_cb].cb_func = func;
        info->event_cb[info->num_event_cb].cb_arg  = arg;
        info->num_event_cb++;
        ALOGI("Successfully added event handler %p for command %d", func, cmd);
        return WIFI_SUCCESS;
    } else {
        return WIFI_ERROR_OUT_OF_MEMORY;
    }
}

wifi_error wifi_register_vendor_handler(wifi_handle handle,
        uint32_t id, int subcmd, nl_recvmsg_msg_cb_t func, void *arg)
{
    hal_info *info = (hal_info *)handle;

    /* TODO: check for multiple handlers? */

    if (info->num_event_cb < info->alloc_event_cb) {
        info->event_cb[info->num_event_cb].nl_cmd  = NL80211_CMD_VENDOR;
        info->event_cb[info->num_event_cb].vendor_id  = id;
        info->event_cb[info->num_event_cb].vendor_subcmd  = subcmd;
        info->event_cb[info->num_event_cb].cb_func = func;
        info->event_cb[info->num_event_cb].cb_arg  = arg;
        info->num_event_cb++;
        ALOGI("Successfully added event handler %p for vendor 0x%0x", func, id);
        return WIFI_SUCCESS;
    } else {
        return WIFI_ERROR_OUT_OF_MEMORY;
    }
}

void wifi_unregister_handler(wifi_handle handle, int cmd)
{
    hal_info *info = (hal_info *)handle;

    if (cmd == NL80211_CMD_VENDOR) {
        ALOGE("Must use wifi_unregister_vendor_handler to remove vendor handlers");
    }

    for (int i = 0; i < info->num_event_cb; i++) {
        if (info->event_cb[i].nl_cmd == cmd) {
            memmove(&info->event_cb[i], &info->event_cb[i+1],
                (info->num_event_cb - i) * sizeof(cb_info));
            info->num_event_cb--;
            ALOGI("Successfully removed event handler for command %d", cmd);
            return;
        }
    }
}

void wifi_unregister_vendor_handler(wifi_handle handle, uint32_t id, int subcmd)
{
    hal_info *info = (hal_info *)handle;

    for (int i = 0; i < info->num_event_cb; i++) {

        if (info->event_cb[i].nl_cmd == NL80211_CMD_VENDOR
                && info->event_cb[i].vendor_id == id
                && info->event_cb[i].vendor_subcmd == subcmd) {

            memmove(&info->event_cb[i], &info->event_cb[i+1],
                (info->num_event_cb - i) * sizeof(cb_info));
            info->num_event_cb--;
            ALOGI("Successfully removed event handler for vendor 0x%0x", id);
            return;
        }
    }
}


wifi_error wifi_register_cmd(wifi_handle handle, int id, WifiCommand *cmd)
{
    hal_info *info = (hal_info *)handle;

    if (info->num_cmd < info->alloc_cmd) {
        info->cmd[info->num_cmd].id   = id;
        info->cmd[info->num_cmd].cmd  = cmd;
        info->num_cmd++;
        ALOGI("Successfully added command %d: %p", id, cmd);
        return WIFI_SUCCESS;
    } else {
        return WIFI_ERROR_OUT_OF_MEMORY;
    }
}

WifiCommand *wifi_unregister_cmd(wifi_handle handle, int id)
{
    hal_info *info = (hal_info *)handle;

    for (int i = 0; i < info->num_cmd; i++) {
        if (info->cmd[i].id == id) {
            WifiCommand *cmd = info->cmd[i].cmd;
            memmove(&info->cmd[i], &info->cmd[i+1], (info->num_cmd - i) * sizeof(cmd_info));
            info->num_cmd--;
            ALOGI("Successfully removed command %d: %p", id, cmd);
            return cmd;
        }
    }

    return NULL;
}

void wifi_unregister_cmd(wifi_handle handle, WifiCommand *cmd)
{
    hal_info *info = (hal_info *)handle;

    for (int i = 0; i < info->num_cmd; i++) {
        if (info->cmd[i].cmd == cmd) {
            int id = info->cmd[i].id;
            memmove(&info->cmd[i], &info->cmd[i+1], (info->num_cmd - i) * sizeof(cmd_info));
            info->num_cmd--;
            ALOGI("Successfully removed command %d: %p", id, cmd);
            return;
        }
    }
}

