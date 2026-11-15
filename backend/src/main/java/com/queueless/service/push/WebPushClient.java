package com.queueless.service.push;

import com.queueless.entity.CustomerPushSubscription;

public interface WebPushClient {

    PushSendResult sendPush(CustomerPushSubscription subscription, String payloadJson);
}
