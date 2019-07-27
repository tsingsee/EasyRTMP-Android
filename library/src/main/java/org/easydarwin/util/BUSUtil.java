package org.easydarwin.util;

import com.squareup.otto.Bus;
import com.squareup.otto.ThreadEnforcer;

public class BUSUtil {
    public static final Bus BUS = new Bus(ThreadEnforcer.ANY);
}
