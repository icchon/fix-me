package com.github.icchon;

public enum BrokerSessionState {
    DISCONNECTED,
    AWAITING_ID,
    READY_TO_LOGON,
    AWAITING_LOGON_ACK,
    ESTABLISHED,
}
