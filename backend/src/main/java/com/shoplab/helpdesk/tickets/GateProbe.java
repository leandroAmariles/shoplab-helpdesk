package com.shoplab.helpdesk.tickets;

public class GateProbe {
    public int pick(boolean flag) {
        int value = 1;
        if (flag) {
            value = 2;
        }
        return value;
    }
}
