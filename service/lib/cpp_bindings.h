
#include "wifi_hal.h"
#include "common.h"
#include "sync.h"

class WifiEvent
{
    /* TODO: remove this when nl headers are updated */
    static const unsigned NL80211_ATTR_MAX_INTERNAL = 256;
private:
    struct nl_msg *mMsg;
    struct genlmsghdr *mHeader;
    struct nlattr *mAttributes[NL80211_ATTR_MAX_INTERNAL + 1];

public:
    WifiEvent(nl_msg *msg) {
        mMsg = msg;
        mHeader = NULL;
        memset(mAttributes, 0, sizeof(mAttributes));
    }
    ~WifiEvent() {
        /* don't destroy mMsg; it doesn't belong to us */
    }

    void log();

    int parse();

    genlmsghdr *header() {
        return mHeader;
    }

    int get_cmd() {
        return mHeader->cmd;
    }

    const char *get_cmdString();

    nlattr ** attributes() {
        return mAttributes;
    }

    nlattr *get_attribute(int attribute) {
        return mAttributes[attribute];
    }

    uint8_t get_u8(int attribute) {
        return nla_get_u8(mAttributes[attribute]);
    }

    uint16_t get_u16(int attribute) {
        return nla_get_u16(mAttributes[attribute]);
    }

    uint32_t get_u32(int attribute) {
        return nla_get_u32(mAttributes[attribute]);
    }

    uint64_t get_u64(int attribute) {
        return nla_get_u64(mAttributes[attribute]);
    }

    int len(int attribute) {
        return nla_len(mAttributes[attribute]);
    }

    void *get_data(int attribute) {
        return nla_data(mAttributes[attribute]);
    }

};

class nl_iterator {
    struct nlattr *pos;
    int rem;
public:
    nl_iterator(struct nlattr *attr) {
        pos = (struct nlattr *)nla_data(attr);
        rem = nla_len(attr);
    }
    bool has_next() {
        return nla_ok(pos, rem);
    }
    void next() {
        pos = (struct nlattr *)nla_next(pos, &(rem));
    }
    struct nlattr *get() {
        return pos;
    }
};

class WifiRequest
{
private:
    int mFamily;
    struct nl_msg *mMsg;

public:
    WifiRequest(int family) {
        mMsg = NULL;
        mFamily = family;
    }
    ~WifiRequest() {
        destroy();
    }

    void destroy() {
        if (mMsg) {
            nlmsg_free(mMsg);
            mMsg = NULL;
        }
    }

    nl_msg *getMessage() {
        return mMsg;
    }

    /* Command assembly helpers */
    int create(int family, uint8_t cmd, int flags, int hdrlen);
    int create(uint8_t cmd, int flags, int hdrlen) {
        return create(mFamily, cmd, flags, hdrlen);
    }
    int create(uint8_t cmd) {
        return create(cmd, 0, 0);
    }

    int create(uint32_t id, int subcmd);

    int put_u8(int attribute, uint8_t value) {
        return nla_put(mMsg, attribute, sizeof(value), &value);
    }
    int put_u16(int attribute, uint16_t value) {
        return nla_put(mMsg, attribute, sizeof(value), &value);
    }
    int put_u32(int attribute, uint32_t value) {
        return nla_put(mMsg, attribute, sizeof(value), &value);
    }
    int put_u64(int attribute, uint64_t value) {
        return nla_put(mMsg, attribute, sizeof(value), &value);
    }
    int put_string(int attribute, const char *value) {
        return nla_put(mMsg, attribute, strlen(value) + 1, value);
    }
    int put_addr(int attribute, mac_addr value) {
        return nla_put(mMsg, attribute, sizeof(mac_addr), &value);
    }

    struct nlattr * attr_start(int attribute) {
        return nla_nest_start(mMsg, attribute);
    }
    void attr_end(struct nlattr *attr) {
        nla_nest_end(mMsg, attr);
    }

    int set_iface_id(int ifindex) {
        return put_u32(NL80211_ATTR_IFINDEX, ifindex);
    }
};

class WifiCommand
{
protected:
    hal_info *mInfo;
    WifiRequest mMsg;
    Condition mCondition;
    wifi_request_id mId;
public:
    WifiCommand(wifi_handle handle, wifi_request_id id)
            : mMsg(((hal_info *)handle)->nl80211_family_id), mId(id)
    {
        mInfo = (hal_info *)handle;
        ALOGV("WifiCommand %p created", this);
    }

    virtual ~WifiCommand() {
        ALOGV("WifiCommand %p destroyed", this);
    }

    wifi_request_id id() {
        return mId;
    }

    virtual int create() = 0;
    virtual int cancel() {
        /* by default there is no way to cancel */
        return WIFI_ERROR_NOT_SUPPORTED;
    }

    int requestResponse();
    int requestEvent(int cmd);
    int requestVendorEvent(uint32_t id, int subcmd);

protected:

    /* Override this method to parse reply and dig out data; save it in the object */
    virtual int handleResponse(WifiEvent reply) {
        ALOGI("skipping a response");
        return NL_SKIP;
    }

    /* Override this method to parse event and dig out data; save it in the object */
    virtual int handleEvent(WifiEvent event) {
        ALOGI("got an event");
        return NL_SKIP;
    }

    int registerHandler(int cmd) {
        return wifi_register_handler(mInfo, cmd, &event_handler, this);
    }

    void unregisterHandler(int cmd) {
        wifi_unregister_handler(mInfo, cmd);
    }

    int registerVendorHandler(uint32_t id, int subcmd) {
        return wifi_register_vendor_handler(mInfo, id, subcmd, &event_handler, this);
    }

    void unregisterVendorHandler(uint32_t id, int subcmd) {
        wifi_unregister_vendor_handler(mInfo, id, subcmd);
    }

private:

    /* Event handling */
    static int response_handler(struct nl_msg *msg, void *arg);

    static int event_handler(struct nl_msg *msg, void *arg);

    /* Other event handlers */
    static int ack_handler(struct nl_msg *msg, void *arg);

    static int finish_handler(struct nl_msg *msg, void *arg);

    static int error_handler(struct sockaddr_nl *nla, struct nlmsgerr *err, void *arg);
};

/* nl message processing macros (required to pass C++ type checks) */

#define for_each_attr(pos, nla, rem) \
    for (pos = (nlattr *)nla_data(nla), rem = nla_len(nla); \
        nla_ok(pos, rem); \
        pos = (nlattr *)nla_next(pos, &(rem)))

